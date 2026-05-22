package org.cibseven.community.connector.rest;

/**
 * Thrown by {@link HttpInvoker} when a response body exceeds the configured
 * {@code maxResponseBytes} cap.
 *
 * <p>A terminal condition: the connector turns it into a system-fault incident,
 * never a retry (a re-read would only grow the body again). It guards the engine
 * heap against a misconfigured or pathological endpoint that returns a huge
 * payload.
 */
public class ResponseTooLargeException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final long limitBytes;

    public ResponseTooLargeException(long limitBytes) {
        super("HTTP response body exceeded the " + limitBytes + "-byte limit");
        this.limitBytes = limitBytes;
    }

    /** The cap that was exceeded, in bytes. */
    public long limitBytes() {
        return limitBytes;
    }
}
