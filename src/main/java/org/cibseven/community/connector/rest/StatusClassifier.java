package org.cibseven.community.connector.rest;

import java.util.Locale;
import java.util.Set;

/**
 * Classifies an HTTP result into one of the connector's outcomes and decides
 * retryability.
 *
 * <p>Pure logic: no HTTP, no engine, no DataSonnet. Every input is a plain
 * value, so this class is exercised by fast unit tests with zero
 * infrastructure. See {@code docs/error-handling.md} and
 * {@code docs/configuration.md} for the model this implements.
 *
 * <pre>
 * Outcome decision (classify):
 *   status 200-299 .......................... SUCCESS
 *   status in businessErrorStatuses ......... BUSINESS_ERROR  (REST_ERROR_&lt;status&gt;)
 *   any other non-2xx status ................ SYSTEM_FAULT
 *   transport failure (post-retry) .......... SYSTEM_FAULT  (decided by the caller)
 *
 * Retry decision (idempotency-aware):
 *   status response ......... retry if status in retryableStatuses (any method:
 *                             the server answered, so nothing ran twice)
 *   transport failure ....... GET/PUT/DELETE/HEAD/OPTIONS: retry on any failure
 *                             POST/PATCH: retry only on a CONNECT_TIMEOUT
 *                             (provably before send), unless retryNonIdempotent
 * </pre>
 */
public final class StatusClassifier {

    /** The connector's classification of a completed HTTP result. */
    public enum Outcome { SUCCESS, BUSINESS_ERROR, SYSTEM_FAULT }

    /**
     * A transport-layer failure, modelled as a plain enum so this class stays
     * free of any HTTP-client dependency. {@code HttpInvoker} maps the
     * underlying Apache HttpClient exception onto one of these.
     */
    public enum TransportFailure { CONNECT_TIMEOUT, READ_TIMEOUT, CONNECTION_FAILURE }

    /** Methods safe to repeat: a retry cannot cause a duplicate side effect. */
    private static final Set<String> IDEMPOTENT_METHODS =
        Set.of("GET", "HEAD", "OPTIONS", "PUT", "DELETE", "TRACE");

    /**
     * Classifies a completed HTTP response by its status code.
     *
     * @param statusCode            the HTTP status code
     * @param businessErrorStatuses non-2xx statuses the modeler declared as
     *                              business outcomes; never {@code null}
     * @return the outcome for this status
     */
    public Outcome classify(int statusCode, Set<Integer> businessErrorStatuses) {
        if (isSuccess(statusCode)) {
            return Outcome.SUCCESS;
        }
        return businessErrorStatuses.contains(statusCode)
            ? Outcome.BUSINESS_ERROR
            : Outcome.SYSTEM_FAULT;
    }

    /** Returns {@code true} for any 2xx status. */
    public boolean isSuccess(int statusCode) {
        return statusCode >= 200 && statusCode <= 299;
    }

    /**
     * The {@code REST_ERROR_<status>} code for a business error, e.g.
     * {@code REST_ERROR_404}. Defined for any status; only meaningful for a
     * {@link Outcome#BUSINESS_ERROR} outcome.
     */
    public String errorCode(int statusCode) {
        return "REST_ERROR_" + statusCode;
    }

    /**
     * Whether a status response should be retried. A status response means the
     * server answered, so retrying is method-agnostic: nothing was processed
     * twice.
     *
     * @param statusCode        the HTTP status code
     * @param retryableStatuses statuses the modeler declared retryable; never {@code null}
     */
    public boolean isRetryableStatus(int statusCode, Set<Integer> retryableStatuses) {
        return retryableStatuses.contains(statusCode);
    }

    /**
     * Whether a transport failure should be retried, idempotency-aware.
     *
     * <p>Idempotent methods retry on any transport failure. Non-idempotent
     * methods (POST, PATCH) retry only on a {@link TransportFailure#CONNECT_TIMEOUT}
     * &mdash; the one failure that proves the request never reached the server
     * &mdash; unless {@code retryNonIdempotent} is set.
     *
     * @param failure            the transport failure; {@code null} returns false
     * @param method             the HTTP method, case- and whitespace-insensitive;
     *                           {@code null} returns false
     * @param retryNonIdempotent if true, POST/PATCH retry like idempotent methods;
     *                           use only when the endpoint carries an idempotency key
     */
    public boolean isRetryableTransportFailure(TransportFailure failure,
                                               String method,
                                               boolean retryNonIdempotent) {
        if (failure == null || method == null) {
            return false;
        }
        if (isIdempotent(method) || retryNonIdempotent) {
            return true;
        }
        return failure == TransportFailure.CONNECT_TIMEOUT;
    }

    private boolean isIdempotent(String method) {
        return IDEMPOTENT_METHODS.contains(method.trim().toUpperCase(Locale.ROOT));
    }
}
