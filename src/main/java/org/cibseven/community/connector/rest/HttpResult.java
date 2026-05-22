package org.cibseven.community.connector.rest;

import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

/**
 * The outcome of one HTTP attempt by {@link HttpInvoker}.
 *
 * <p>An attempt either completes with a response (of any status &mdash; a 200
 * and a 404 are both {@code RESPONSE}s here, classification is a later step) or
 * fails at the transport layer before a response arrives. An oversize response
 * is neither: {@link HttpInvoker} throws {@link ResponseTooLargeException} for
 * that, because it is terminal and never retried.
 */
public final class HttpResult {

    /** Whether the attempt produced an HTTP response or failed in transport. */
    public enum Kind { RESPONSE, TRANSPORT_FAILURE }

    private final Kind kind;
    private final int statusCode;
    private final Map<String, String> headers;
    private final String body;
    private final StatusClassifier.TransportFailure transportFailure;
    private final String detail;

    private HttpResult(Kind kind,
                       int statusCode,
                       Map<String, String> headers,
                       String body,
                       StatusClassifier.TransportFailure transportFailure,
                       String detail) {
        this.kind = kind;
        this.statusCode = statusCode;
        this.headers = headers;
        this.body = body;
        this.transportFailure = transportFailure;
        this.detail = detail;
    }

    /** A completed HTTP response of any status. */
    public static HttpResult response(int statusCode, Map<String, String> headers, String body) {
        // HTTP header names are case-insensitive; store them so a lookup by any
        // casing works (a DataSonnet script should not have to guess the case).
        Map<String, String> safeHeaders;
        if (headers == null) {
            safeHeaders = Collections.emptyMap();
        } else {
            TreeMap<String, String> caseInsensitive = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
            caseInsensitive.putAll(headers);
            safeHeaders = Collections.unmodifiableMap(caseInsensitive);
        }
        return new HttpResult(Kind.RESPONSE, statusCode, safeHeaders,
            body == null ? "" : body, null, null);
    }

    /** A transport-layer failure: no HTTP response arrived. */
    public static HttpResult transportFailure(StatusClassifier.TransportFailure failure,
                                              String detail) {
        if (failure == null) {
            throw new IllegalArgumentException("failure is required");
        }
        return new HttpResult(Kind.TRANSPORT_FAILURE, 0,
            Collections.emptyMap(), null, failure, detail);
    }

    public Kind kind() {
        return kind;
    }

    public boolean isResponse() {
        return kind == Kind.RESPONSE;
    }

    /** The HTTP status code. Only valid when {@link #isResponse()}. */
    public int statusCode() {
        requireResponse();
        return statusCode;
    }

    /** The response headers. Only valid when {@link #isResponse()}. */
    public Map<String, String> headers() {
        requireResponse();
        return headers;
    }

    /** The response body, never {@code null}. Only valid when {@link #isResponse()}. */
    public String body() {
        requireResponse();
        return body;
    }

    /** The transport-failure kind. Only valid for a {@code TRANSPORT_FAILURE} result. */
    public StatusClassifier.TransportFailure transportFailure() {
        if (kind != Kind.TRANSPORT_FAILURE) {
            throw new IllegalStateException("not a transport failure: " + kind);
        }
        return transportFailure;
    }

    /** A human-readable failure detail for a transport failure; may be {@code null}. */
    public String detail() {
        return detail;
    }

    private void requireResponse() {
        if (kind != Kind.RESPONSE) {
            throw new IllegalStateException("not an HTTP response: " + kind);
        }
    }
}
