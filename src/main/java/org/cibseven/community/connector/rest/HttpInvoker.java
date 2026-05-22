package org.cibseven.community.connector.rest;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.apache.hc.client5.http.ConnectTimeoutException;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.protocol.HttpClientContext;
import org.apache.hc.core5.http.ClassicHttpRequest;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.apache.hc.core5.http.io.support.ClassicRequestBuilder;
import org.apache.hc.core5.util.TimeValue;
import org.apache.hc.core5.util.Timeout;

/**
 * Executes one HTTP attempt against an owned, pooled Apache HttpClient.
 *
 * <p>One {@code HttpInvoker} is built per connector instance and shared across
 * job-executor threads. It owns a {@link CloseableHttpClient} with a bounded
 * connection pool (Apache HttpClient is thread-safe). The connector's retry loop
 * calls {@link #execute} repeatedly; this class does exactly one attempt and
 * reports what happened.
 *
 * <pre>
 *   execute(spec)
 *     |
 *     +-- HTTP response arrives (any status) ....... HttpResult.response(...)
 *     +-- body exceeds maxResponseBytes ............ throw ResponseTooLargeException
 *     +-- transport failure (no response) .......... HttpResult.transportFailure(kind)
 *           ConnectTimeoutException -> CONNECT_TIMEOUT
 *           SocketTimeoutException  -> READ_TIMEOUT
 *           other IOException      -> CONNECTION_FAILURE  (DNS, refused, TLS)
 * </pre>
 *
 * <p><b>Redirects</b> are not followed: a 3xx is returned as a {@code RESPONSE}
 * for the connector to classify. This closes the redirect-SSRF vector and keeps
 * the reported status honest (see docs/design.md section 9).
 *
 * <p><b>Timeouts.</b> {@code readTimeoutMs} is applied per request (HttpClient
 * 5.x {@code responseTimeout}). The connect timeout is connection-manager level
 * in HttpClient 5.x, so it is set once here at construction, not per request.
 */
public final class HttpInvoker implements Closeable {

    public static final int DEFAULT_MAX_CONN_TOTAL = 100;
    public static final int DEFAULT_MAX_CONN_PER_ROUTE = 20;
    public static final int DEFAULT_CONNECT_TIMEOUT_MS = 5_000;

    private static final int READ_CHUNK_BYTES = 8_192;
    private static final long IDLE_EVICTION_SECONDS = 30L;

    private final CloseableHttpClient httpClient;

    public HttpInvoker() {
        this(DEFAULT_MAX_CONN_TOTAL, DEFAULT_MAX_CONN_PER_ROUTE, DEFAULT_CONNECT_TIMEOUT_MS);
    }

    public HttpInvoker(int maxConnTotal, int maxConnPerRoute, int connectTimeoutMs) {
        if (maxConnTotal < 1 || maxConnPerRoute < 1) {
            throw new IllegalArgumentException("connection pool sizes must be >= 1");
        }
        if (connectTimeoutMs <= 0) {
            throw new IllegalArgumentException(
                "connectTimeoutMs must be > 0, got " + connectTimeoutMs);
        }
        ConnectionConfig connectionConfig = ConnectionConfig.custom()
            .setConnectTimeout(Timeout.ofMilliseconds(connectTimeoutMs))
            .build();
        PoolingHttpClientConnectionManager connectionManager =
            PoolingHttpClientConnectionManagerBuilder.create()
                .setMaxConnTotal(maxConnTotal)
                .setMaxConnPerRoute(maxConnPerRoute)
                .setDefaultConnectionConfig(connectionConfig)
                .build();
        this.httpClient = HttpClients.custom()
            .setConnectionManager(connectionManager)
            .disableRedirectHandling()
            .evictIdleConnections(TimeValue.ofSeconds(IDLE_EVICTION_SECONDS))
            .evictExpiredConnections()
            .build();
    }

    /**
     * Executes one HTTP attempt.
     *
     * @param spec the request to execute
     * @return the response, or a transport failure
     * @throws ResponseTooLargeException if the body exceeds {@code spec.maxResponseBytes()}
     */
    public HttpResult execute(HttpRequestSpec spec) {
        if (spec == null) {
            throw new IllegalArgumentException("spec is required");
        }
        ClassicHttpRequest request = buildRequest(spec);
        HttpClientContext context = HttpClientContext.create();
        context.setRequestConfig(RequestConfig.custom()
            .setResponseTimeout(Timeout.ofMilliseconds(spec.readTimeoutMs()))
            .build());
        try {
            return httpClient.execute(request, context,
                response -> readResult(response, spec.maxResponseBytes()));
        } catch (ResponseTooLargeException e) {
            throw e; // terminal, never a retry
        } catch (IOException e) {
            return HttpResult.transportFailure(classify(e), e.toString());
        }
    }

    @Override
    public void close() throws IOException {
        httpClient.close();
    }

    private ClassicHttpRequest buildRequest(HttpRequestSpec spec) {
        ClassicRequestBuilder builder =
            ClassicRequestBuilder.create(spec.method()).setUri(spec.url());
        for (Map.Entry<String, String> header : spec.headers().entrySet()) {
            builder.addHeader(header.getKey(), header.getValue());
        }
        if (spec.body() != null) {
            builder.setEntity(new StringEntity(spec.body(), ContentType.APPLICATION_JSON));
        }
        return builder.build();
    }

    private HttpResult readResult(ClassicHttpResponse response, long maxResponseBytes)
            throws IOException {
        int statusCode = response.getCode();
        Map<String, String> headers = new LinkedHashMap<>();
        for (Header header : response.getHeaders()) {
            headers.put(header.getName(), header.getValue());
        }
        String body = "";
        HttpEntity entity = response.getEntity();
        if (entity != null) {
            try (InputStream in = entity.getContent()) {
                body = readCapped(in, maxResponseBytes);
            }
        }
        return HttpResult.response(statusCode, headers, body);
    }

    private String readCapped(InputStream in, long maxResponseBytes) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[READ_CHUNK_BYTES];
        long total = 0;
        int read;
        while ((read = in.read(chunk)) != -1) {
            total += read;
            if (total > maxResponseBytes) {
                throw new ResponseTooLargeException(maxResponseBytes);
            }
            buffer.write(chunk, 0, read);
        }
        return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
    }

    private StatusClassifier.TransportFailure classify(IOException e) {
        if (e instanceof ConnectTimeoutException) {
            return StatusClassifier.TransportFailure.CONNECT_TIMEOUT;
        }
        if (e instanceof SocketTimeoutException) {
            return StatusClassifier.TransportFailure.READ_TIMEOUT;
        }
        // UnknownHostException, HttpHostConnectException (refused), SSLException, ...
        return StatusClassifier.TransportFailure.CONNECTION_FAILURE;
    }
}
