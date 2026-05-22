package org.cibseven.community.connector.rest;

import org.cibseven.connect.ConnectorException;

/**
 * Thrown when a <b>request</b>-mapping DataSonnet script fails.
 *
 * <p>A request-mapping failure happens before any HTTP call, so no remote side
 * effect occurred. It is a technical defect: this exception propagates out of
 * the connector and CIB seven raises an incident an operator resolves. It
 * extends {@link ConnectorException} so the engine treats it as a connector
 * failure.
 *
 * <p>A <i>response</i>-mapping failure is handled differently — see
 * {@code docs/error-handling.md} §4 — because the remote call already
 * succeeded; that path returns data rather than throwing.
 */
public class RestMappingException extends ConnectorException {

    private static final long serialVersionUID = 1L;

    public RestMappingException(String message, Throwable cause) {
        super(message, cause);
    }
}
