package org.cibseven.community.connector.rest;

import java.io.Closeable;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Logger;
import org.cibseven.connect.ConnectorException;
import org.cibseven.connect.impl.AbstractConnector;
import org.cibseven.connect.spi.ConnectorResponse;

/**
 * The {@code rest-datasonnet} Connect SPI connector: calls a REST API with
 * DataSonnet request / response / error mapping and a business-vs-system error
 * model.
 *
 * <p>The Connect engine creates one instance and reuses it across all
 * job-executor threads. It owns the three helpers; all are thread-safe.
 *
 * <pre>
 * execute(request):
 *   read + validate config (RestConnectorConfig)        -> RestConfigurationException
 *   coerce source to JSON, run requestMapping           -> RestMappingException (incident)
 *   HTTP invoke + idempotency-aware retry (HttpInvoker)
 *   classify the result (StatusClassifier):
 *     SUCCESS (2xx)        -> responseMapping -> result               (returned)
 *       responseMapping failed -> RESPONSE_MAPPING_FAILED, raw body   (returned)
 *     BUSINESS_ERROR       -> errorMapping -> restError                (returned)
 *     SYSTEM_FAULT         -> throw ConnectorException                (incident)
 *     transport failure    -> throw ConnectorException                (incident)
 * </pre>
 */
public class RestDataSonnetConnector
        extends AbstractConnector<RestDataSonnetRequest, RestDataSonnetResponse>
        implements Closeable {

    /** The connector id used in {@code <camunda:connectorId>}. */
    public static final String CONNECTOR_ID = "rest-datasonnet";

    private static final Logger LOG = Logger.getLogger(RestDataSonnetConnector.class.getName());

    private final StatusClassifier statusClassifier;
    private final DataSonnetMapper dataSonnetMapper;
    private final HttpInvoker httpInvoker;

    public RestDataSonnetConnector() {
        super(CONNECTOR_ID);
        this.statusClassifier = new StatusClassifier();
        this.dataSonnetMapper = new DataSonnetMapper();
        this.httpInvoker = new HttpInvoker();
    }

    @Override
    public RestDataSonnetRequest createRequest() {
        return new RestDataSonnetRequest(this);
    }

    @Override
    public ConnectorResponse execute(RestDataSonnetRequest request) {
        RestConnectorConfig cfg = RestConnectorConfig.fromParameters(request.getRequestParameters());

        // 1. request mapping (a failure here is a defect: no side effect happened)
        String requestBody = mapRequest(cfg);

        // 2. HTTP invoke + idempotency-aware retry
        HttpOutcome outcome = invokeWithRetry(cfg, requestBody);
        HttpResult result = outcome.result;

        // 3. a terminal transport failure is a system fault -> incident
        if (result.kind() == HttpResult.Kind.TRANSPORT_FAILURE) {
            throw new ConnectorException(
                "REST call " + cfg.method() + " " + cfg.url() + " failed at the transport"
                + " layer (" + result.transportFailure() + "): " + result.detail());
        }

        // 4. classify the HTTP response
        int status = result.statusCode();
        StatusClassifier.Outcome classified =
            statusClassifier.classify(status, cfg.businessErrorStatuses());

        if (classified == StatusClassifier.Outcome.SYSTEM_FAULT) {
            throw new ConnectorException(
                "REST call " + cfg.method() + " " + cfg.url() + " returned HTTP " + status
                + ", which is not a declared business status");
        }

        // Seed every output key so a <camunda:outputParameter> expression
        // resolves regardless of outcome; the not-applicable ones stay null.
        Map<String, Object> outputs = new LinkedHashMap<>();
        outputs.put("restOutcome", null);
        outputs.put("result", null);
        outputs.put("statusCode", status);
        outputs.put("responseHeaders", result.headers());
        outputs.put("restErrorCode", null);
        outputs.put("restError", null);
        outputs.put("restRawResponse", null);

        if (classified == StatusClassifier.Outcome.SUCCESS) {
            return mapSuccess(cfg, result, outputs);
        }
        return mapBusinessError(cfg, result, status, outcome.attempts, outputs);
    }

    @Override
    public void close() throws IOException {
        httpInvoker.close();
    }

    // --- request mapping ----------------------------------------------------

    private String mapRequest(RestConnectorConfig cfg) {
        String sourceJson = coerceToJson(cfg.source());
        if (cfg.requestMapping() == null) {
            return sourceJson; // identity passthrough
        }
        try {
            return dataSonnetMapper.transform(cfg.requestMapping(), sourceJson);
        } catch (DataSonnetMappingException e) {
            throw new RestMappingException(
                "request mapping failed for " + cfg.method() + " " + cfg.url()
                + ": " + e.getMessage(), e);
        }
    }

    // --- success path -------------------------------------------------------

    private RestDataSonnetResponse mapSuccess(RestConnectorConfig cfg, HttpResult result,
                                              Map<String, Object> outputs) {
        try {
            Object mapped = cfg.responseMapping() == null
                ? result.body()
                : dataSonnetMapper.transform(cfg.responseMapping(), result.body());
            outputs.put("restOutcome", "SUCCESS");
            outputs.put("result", mapped);
        } catch (DataSonnetMappingException e) {
            // the 2xx already happened: preserve the raw body and return as
            // data (not an incident), so an operator can recover without
            // re-submitting. See docs/error-handling.md section 4.
            outputs.put("restOutcome", "RESPONSE_MAPPING_FAILED");
            outputs.put("restErrorCode", "REST_ERROR_RESPONSE_MAPPING");
            outputs.put("restRawResponse", result.body());
            outputs.put("restError", "response mapping failed: " + e.getMessage());
        }
        return new RestDataSonnetResponse(outputs);
    }

    // --- business error path ------------------------------------------------

    private RestDataSonnetResponse mapBusinessError(RestConnectorConfig cfg, HttpResult result,
                                                    int status, int attempts,
                                                    Map<String, Object> outputs) {
        String errorCode = statusClassifier.errorCode(status);
        String errorContext = buildErrorContext(cfg, result, errorCode, attempts);
        Object restError;
        if (cfg.errorMapping() == null) {
            restError = errorContext; // the default error object is the context itself
        } else {
            try {
                restError = dataSonnetMapper.transform(cfg.errorMapping(), errorContext);
            } catch (DataSonnetMappingException e) {
                LOG.warning("error mapping failed for " + cfg.method() + " " + cfg.url()
                    + "; falling back to the default error object: " + e.getMessage());
                restError = errorContext;
            }
        }
        outputs.put("restOutcome", "BUSINESS_ERROR");
        outputs.put("restErrorCode", errorCode);
        outputs.put("restError", restError);
        return new RestDataSonnetResponse(outputs);
    }

    // --- HTTP invoke + retry ------------------------------------------------

    private HttpOutcome invokeWithRetry(RestConnectorConfig cfg, String body) {
        HttpRequestSpec spec = new HttpRequestSpec(cfg.method(), cfg.url(), cfg.headers(),
            body, cfg.readTimeoutMs(), cfg.maxResponseBytes());
        int maxAttempts = cfg.retryCount() + 1;
        HttpResult result = null;
        int attempt = 0;
        while (attempt < maxAttempts) {
            attempt++;
            try {
                result = httpInvoker.execute(spec);
            } catch (ResponseTooLargeException e) {
                throw new ConnectorException(
                    "REST response from " + cfg.url() + " exceeded the "
                    + e.limitBytes() + "-byte maxResponseBytes limit", e);
            }
            if (attempt >= maxAttempts || !shouldRetry(cfg, result)) {
                break;
            }
            sleep(cfg.retryDelayMs());
        }
        return new HttpOutcome(result, attempt);
    }

    private boolean shouldRetry(RestConnectorConfig cfg, HttpResult result) {
        if (result.kind() == HttpResult.Kind.RESPONSE) {
            return statusClassifier.isRetryableStatus(result.statusCode(), cfg.retryableStatuses());
        }
        return statusClassifier.isRetryableTransportFailure(
            result.transportFailure(), cfg.method(), cfg.retryNonIdempotent());
    }

    private static void sleep(int millis) {
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ConnectorException("retry wait was interrupted", e);
        }
    }

    // --- source coercion ----------------------------------------------------

    /**
     * Coerces the {@code source} input into a JSON string for DataSonnet. A
     * {@code String} is used as-is; a CIB seven Spin JSON value is serialised via
     * its {@code toString()}; any other type is an explicit configuration error.
     */
    private static String coerceToJson(Object source) {
        if (source instanceof String) {
            return (String) source;
        }
        String type = source.getClass().getName();
        if (type.startsWith("org.cibseven.spin") || type.contains("Spin")) {
            return source.toString();
        }
        throw new RestConfigurationException(
            "'source' must be a JSON String or a Spin JSON value, got " + type);
    }

    // --- error context JSON (handed to errorMapping as `payload`) -----------

    private static String buildErrorContext(RestConnectorConfig cfg, HttpResult result,
                                            String errorCode, int attempts) {
        return new StringBuilder(256)
            .append('{')
            .append("\"status\":").append(result.statusCode()).append(',')
            .append("\"errorCode\":").append(jsonString(errorCode)).append(',')
            .append("\"body\":").append(jsonString(result.body())).append(',')
            .append("\"headers\":").append(jsonObject(result.headers())).append(',')
            .append("\"request\":{")
            .append("\"url\":").append(jsonString(cfg.url())).append(',')
            .append("\"method\":").append(jsonString(cfg.method())).append(',')
            .append("\"attempts\":").append(attempts)
            .append('}')
            .append('}')
            .toString();
    }

    private static String jsonObject(Map<String, String> map) {
        StringBuilder sb = new StringBuilder().append('{');
        boolean first = true;
        for (Map.Entry<String, String> entry : map.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append(jsonString(entry.getKey())).append(':').append(jsonString(entry.getValue()));
        }
        return sb.append('}').toString();
    }

    /** Encodes a string as a JSON string literal (quoted, with escaping). */
    private static String jsonString(String s) {
        if (s == null) {
            return "null";
        }
        StringBuilder sb = new StringBuilder(s.length() + 2).append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':  sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                case '\b': sb.append("\\b"); break;
                case '\f': sb.append("\\f"); break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        return sb.append('"').toString();
    }

    /** The final HTTP result plus how many attempts the retry loop made. */
    private static final class HttpOutcome {
        final HttpResult result;
        final int attempts;

        HttpOutcome(HttpResult result, int attempts) {
            this.result = result;
            this.attempts = attempts;
        }
    }
}
