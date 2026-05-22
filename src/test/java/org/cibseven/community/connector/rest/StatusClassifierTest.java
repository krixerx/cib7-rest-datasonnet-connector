package org.cibseven.community.connector.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import org.cibseven.community.connector.rest.StatusClassifier.Outcome;
import org.cibseven.community.connector.rest.StatusClassifier.TransportFailure;
import org.junit.jupiter.api.Test;

class StatusClassifierTest {

    private final StatusClassifier classifier = new StatusClassifier();

    /** The connector's default retryable-status set (see configuration.md §1). */
    private static final Set<Integer> DEFAULT_RETRYABLE =
        Set.of(408, 429, 500, 502, 503, 504);

    // --- classify -----------------------------------------------------------

    @Test
    void classify_2xx_isSuccess() {
        assertEquals(Outcome.SUCCESS, classifier.classify(200, Set.of()));
        assertEquals(Outcome.SUCCESS, classifier.classify(201, Set.of()));
        assertEquals(Outcome.SUCCESS, classifier.classify(204, Set.of()));
        assertEquals(Outcome.SUCCESS, classifier.classify(299, Set.of()));
    }

    @Test
    void classify_declaredNon2xx_isBusinessError() {
        assertEquals(Outcome.BUSINESS_ERROR, classifier.classify(404, Set.of(404)));
        assertEquals(Outcome.BUSINESS_ERROR, classifier.classify(409, Set.of(404, 409)));
    }

    @Test
    void classify_undeclaredNon2xx_isSystemFault() {
        assertEquals(Outcome.SYSTEM_FAULT, classifier.classify(404, Set.of()));
        assertEquals(Outcome.SYSTEM_FAULT, classifier.classify(401, Set.of(404)));
        assertEquals(Outcome.SYSTEM_FAULT, classifier.classify(500, Set.of(404)));
    }

    @Test
    void classify_sameStatus_dependsOnDeclaration() {
        // the design's killer case: a 404 is business or system per call site
        assertEquals(Outcome.BUSINESS_ERROR, classifier.classify(404, Set.of(404)));
        assertEquals(Outcome.SYSTEM_FAULT, classifier.classify(404, Set.of()));
    }

    @Test
    void classify_nonRetryable5xx_whenUndeclared_isSystemFault() {
        assertEquals(Outcome.SYSTEM_FAULT, classifier.classify(501, Set.of()));
    }

    @Test
    void classify_declared5xx_isBusinessError() {
        assertEquals(Outcome.BUSINESS_ERROR, classifier.classify(503, Set.of(503)));
    }

    @Test
    void classify_3xxAndSub2xx_areNon2xx() {
        assertEquals(Outcome.SYSTEM_FAULT, classifier.classify(301, Set.of()));
        assertEquals(Outcome.SYSTEM_FAULT, classifier.classify(199, Set.of()));
    }

    // --- isSuccess ----------------------------------------------------------

    @Test
    void isSuccess_boundaries() {
        assertFalse(classifier.isSuccess(199));
        assertTrue(classifier.isSuccess(200));
        assertTrue(classifier.isSuccess(299));
        assertFalse(classifier.isSuccess(300));
    }

    // --- errorCode ----------------------------------------------------------

    @Test
    void errorCode_isRestErrorPlusStatus() {
        assertEquals("REST_ERROR_404", classifier.errorCode(404));
        assertEquals("REST_ERROR_409", classifier.errorCode(409));
        assertEquals("REST_ERROR_503", classifier.errorCode(503));
    }

    // --- isRetryableStatus --------------------------------------------------

    @Test
    void isRetryableStatus_inSet_isRetryable() {
        assertTrue(classifier.isRetryableStatus(503, DEFAULT_RETRYABLE));
        assertTrue(classifier.isRetryableStatus(429, DEFAULT_RETRYABLE));
    }

    @Test
    void isRetryableStatus_notInSet_isNotRetryable() {
        assertFalse(classifier.isRetryableStatus(501, DEFAULT_RETRYABLE));
        assertFalse(classifier.isRetryableStatus(404, DEFAULT_RETRYABLE));
    }

    @Test
    void isRetryableStatus_emptySet_isNotRetryable() {
        assertFalse(classifier.isRetryableStatus(503, Set.of()));
    }

    // --- isRetryableTransportFailure ----------------------------------------

    @Test
    void transportFailure_idempotentMethod_alwaysRetries() {
        for (String method : new String[] {"GET", "PUT", "DELETE", "HEAD", "OPTIONS"}) {
            for (TransportFailure f : TransportFailure.values()) {
                assertTrue(classifier.isRetryableTransportFailure(f, method, false),
                    method + " + " + f + " should retry");
            }
        }
    }

    @Test
    void transportFailure_postAndPatch_retryOnlyOnConnectTimeout() {
        for (String method : new String[] {"POST", "PATCH"}) {
            assertTrue(classifier.isRetryableTransportFailure(
                TransportFailure.CONNECT_TIMEOUT, method, false),
                method + " should retry on a connect timeout");
            assertFalse(classifier.isRetryableTransportFailure(
                TransportFailure.READ_TIMEOUT, method, false),
                method + " must not retry on a read timeout");
            assertFalse(classifier.isRetryableTransportFailure(
                TransportFailure.CONNECTION_FAILURE, method, false),
                method + " must not retry on a connection failure");
        }
    }

    @Test
    void transportFailure_postWithRetryNonIdempotent_retriesEverything() {
        for (TransportFailure f : TransportFailure.values()) {
            assertTrue(classifier.isRetryableTransportFailure(f, "POST", true),
                "POST with retryNonIdempotent should retry on " + f);
        }
    }

    @Test
    void transportFailure_methodIsCaseAndWhitespaceInsensitive() {
        assertTrue(classifier.isRetryableTransportFailure(
            TransportFailure.READ_TIMEOUT, "  get ", false));
        assertFalse(classifier.isRetryableTransportFailure(
            TransportFailure.READ_TIMEOUT, "post", false));
    }

    @Test
    void transportFailure_nullInputs_doNotRetry() {
        assertFalse(classifier.isRetryableTransportFailure(null, "GET", false));
        assertFalse(classifier.isRetryableTransportFailure(
            TransportFailure.CONNECT_TIMEOUT, null, false));
    }
}
