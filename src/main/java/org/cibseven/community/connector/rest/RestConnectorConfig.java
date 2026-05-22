package org.cibseven.community.connector.rest;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The connector's input parameters, read from the request parameter map and
 * validated up front.
 *
 * <p>A bad parameter throws {@link RestConfigurationException} naming the
 * offending input, so a forgotten {@code ${restBaseUrl}} variable becomes a
 * clear "url is required" incident instead of a NullPointerException or a
 * misrouted network error.
 *
 * <p>Pure logic: no HTTP, no engine. {@link #fromParameters} is unit-testable by
 * feeding it plain maps.
 */
public final class RestConnectorConfig {

    private static final Set<String> ALLOWED_METHODS =
        Set.of("GET", "POST", "PUT", "PATCH", "DELETE");
    private static final Set<Integer> DEFAULT_RETRYABLE_STATUSES =
        Set.of(408, 429, 500, 502, 503, 504);

    private final Object source;
    private final String url;
    private final String method;
    private final Map<String, String> headers;
    private final String requestMapping;
    private final String responseMapping;
    private final String errorMapping;
    private final Set<Integer> businessErrorStatuses;
    private final Set<Integer> retryableStatuses;
    private final int readTimeoutMs;
    private final int retryCount;
    private final int retryDelayMs;
    private final boolean retryNonIdempotent;
    private final long maxResponseBytes;

    private RestConnectorConfig(Object source, String url, String method,
                                Map<String, String> headers, String requestMapping,
                                String responseMapping, String errorMapping,
                                Set<Integer> businessErrorStatuses,
                                Set<Integer> retryableStatuses, int readTimeoutMs,
                                int retryCount, int retryDelayMs,
                                boolean retryNonIdempotent, long maxResponseBytes) {
        this.source = source;
        this.url = url;
        this.method = method;
        this.headers = headers;
        this.requestMapping = requestMapping;
        this.responseMapping = responseMapping;
        this.errorMapping = errorMapping;
        this.businessErrorStatuses = businessErrorStatuses;
        this.retryableStatuses = retryableStatuses;
        this.readTimeoutMs = readTimeoutMs;
        this.retryCount = retryCount;
        this.retryDelayMs = retryDelayMs;
        this.retryNonIdempotent = retryNonIdempotent;
        this.maxResponseBytes = maxResponseBytes;
    }

    /**
     * Reads and validates the connector input parameters.
     *
     * @param params the request parameter map (from {@code request.getRequestParameters()})
     * @throws RestConfigurationException if a required parameter is missing or a value is invalid
     */
    public static RestConnectorConfig fromParameters(Map<String, Object> params) {
        Map<String, Object> p = params == null ? Collections.emptyMap() : params;

        Object source = p.get("source");
        if (source == null) {
            throw new RestConfigurationException("'source' input parameter is required");
        }
        return new RestConnectorConfig(
            source,
            requireText(p, "url"),
            requireMethod(p),
            readStringMap(p.get("headers")),
            optionalText(p.get("requestMapping")),
            optionalText(p.get("responseMapping")),
            optionalText(p.get("errorMapping")),
            parseStatuses(p.get("businessErrorStatuses"), Collections.emptySet()),
            parseStatuses(p.get("retryableStatuses"), DEFAULT_RETRYABLE_STATUSES),
            intParam(p, "readTimeout", 30_000, 1),
            intParam(p, "retryCount", 3, 0),
            intParam(p, "retryDelay", 2_000, 0),
            boolParam(p, "retryNonIdempotent", false),
            longParam(p, "maxResponseBytes", 10_485_760L, 1L));
    }

    public Object source() { return source; }
    public String url() { return url; }
    public String method() { return method; }
    public Map<String, String> headers() { return headers; }
    public String requestMapping() { return requestMapping; }
    public String responseMapping() { return responseMapping; }
    public String errorMapping() { return errorMapping; }
    public Set<Integer> businessErrorStatuses() { return businessErrorStatuses; }
    public Set<Integer> retryableStatuses() { return retryableStatuses; }
    public int readTimeoutMs() { return readTimeoutMs; }
    public int retryCount() { return retryCount; }
    public int retryDelayMs() { return retryDelayMs; }
    public boolean retryNonIdempotent() { return retryNonIdempotent; }
    public long maxResponseBytes() { return maxResponseBytes; }

    // --- parsing helpers ---

    private static String requireText(Map<String, Object> p, String name) {
        Object v = p.get(name);
        String s = v == null ? null : v.toString().trim();
        if (s == null || s.isEmpty()) {
            throw new RestConfigurationException("'" + name + "' input parameter is required");
        }
        return s;
    }

    private static String requireMethod(Map<String, Object> p) {
        String method = requireText(p, "method").toUpperCase(Locale.ROOT);
        if (!ALLOWED_METHODS.contains(method)) {
            throw new RestConfigurationException(
                "'method' must be one of " + ALLOWED_METHODS + ", got '" + method + "'");
        }
        return method;
    }

    private static String optionalText(Object v) {
        if (v == null) {
            return null;
        }
        String s = v.toString();
        return s.trim().isEmpty() ? null : s;
    }

    private static int intParam(Map<String, Object> p, String name, int def, int min) {
        long n = longParam(p, name, def, min);
        return (int) n;
    }

    private static long longParam(Map<String, Object> p, String name, long def, long min) {
        Object v = p.get(name);
        if (v == null) {
            return def;
        }
        long n;
        if (v instanceof Number) {
            n = ((Number) v).longValue();
        } else {
            try {
                n = Long.parseLong(v.toString().trim());
            } catch (NumberFormatException e) {
                throw new RestConfigurationException(
                    "'" + name + "' must be an integer, got '" + v + "'");
            }
        }
        if (n < min) {
            throw new RestConfigurationException(
                "'" + name + "' must be >= " + min + ", got " + n);
        }
        return n;
    }

    private static boolean boolParam(Map<String, Object> p, String name, boolean def) {
        Object v = p.get(name);
        if (v == null) {
            return def;
        }
        if (v instanceof Boolean) {
            return (Boolean) v;
        }
        return Boolean.parseBoolean(v.toString().trim());
    }

    private static Set<Integer> parseStatuses(Object v, Set<Integer> def) {
        if (v == null) {
            return def;
        }
        String s = v.toString().trim();
        if (s.isEmpty()) {
            return def;
        }
        Set<Integer> out = new LinkedHashSet<>();
        for (String part : s.split(",")) {
            String token = part.trim();
            if (token.isEmpty()) {
                continue;
            }
            try {
                out.add(Integer.valueOf(token));
            } catch (NumberFormatException e) {
                throw new RestConfigurationException(
                    "status list contains a non-integer: '" + token + "'");
            }
        }
        return out;
    }

    private static Map<String, String> readStringMap(Object v) {
        if (!(v instanceof Map)) {
            return Collections.emptyMap();
        }
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : ((Map<?, ?>) v).entrySet()) {
            if (entry.getKey() != null) {
                out.put(String.valueOf(entry.getKey()),
                        entry.getValue() == null ? "" : String.valueOf(entry.getValue()));
            }
        }
        return out;
    }
}
