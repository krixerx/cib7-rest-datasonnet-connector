package org.cibseven.community.connector.rest;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One HTTP request for {@link HttpInvoker} to execute: an immutable value object.
 *
 * <p>The {@code url} is expected to be complete, query string included &mdash;
 * assembling query parameters is a connector-side concern, not the HTTP layer's.
 * {@code connectTimeout} is deliberately absent: in Apache HttpClient 5.x it is a
 * connection-manager-level setting, not per-request, so {@link HttpInvoker} takes
 * it once at construction. {@code readTimeoutMs} is genuinely per-request.
 */
public final class HttpRequestSpec {

    private final String method;
    private final String url;
    private final Map<String, String> headers;
    private final String body;
    private final int readTimeoutMs;
    private final long maxResponseBytes;

    /**
     * @param method           the HTTP method (e.g. {@code GET}); required
     * @param url              the complete target URL, query string included; required
     * @param headers          request headers; {@code null} is treated as empty
     * @param body             the request body, or {@code null} for none (e.g. GET)
     * @param readTimeoutMs    per-request response timeout in ms; must be &gt; 0
     * @param maxResponseBytes response-body size cap in bytes; must be &gt; 0
     */
    public HttpRequestSpec(String method,
                           String url,
                           Map<String, String> headers,
                           String body,
                           int readTimeoutMs,
                           long maxResponseBytes) {
        if (isBlank(method)) {
            throw new IllegalArgumentException("method is required");
        }
        if (isBlank(url)) {
            throw new IllegalArgumentException("url is required");
        }
        if (readTimeoutMs <= 0) {
            throw new IllegalArgumentException("readTimeoutMs must be > 0, got " + readTimeoutMs);
        }
        if (maxResponseBytes <= 0) {
            throw new IllegalArgumentException(
                "maxResponseBytes must be > 0, got " + maxResponseBytes);
        }
        this.method = method.trim();
        this.url = url.trim();
        this.headers = headers == null
            ? Collections.emptyMap()
            : Collections.unmodifiableMap(new LinkedHashMap<>(headers));
        this.body = body;
        this.readTimeoutMs = readTimeoutMs;
        this.maxResponseBytes = maxResponseBytes;
    }

    public String method() {
        return method;
    }

    public String url() {
        return url;
    }

    /** Request headers, never {@code null}. */
    public Map<String, String> headers() {
        return headers;
    }

    /** The request body, or {@code null} for none. */
    public String body() {
        return body;
    }

    public int readTimeoutMs() {
        return readTimeoutMs;
    }

    public long maxResponseBytes() {
        return maxResponseBytes;
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }
}
