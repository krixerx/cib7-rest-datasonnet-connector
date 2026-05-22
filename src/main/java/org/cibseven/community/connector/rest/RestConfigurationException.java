package org.cibseven.community.connector.rest;

import org.cibseven.connect.ConnectorException;

/**
 * Thrown when the connector's input parameters are invalid — a missing or blank
 * {@code url}, an unsupported {@code method}, a negative timeout, a {@code source}
 * of an unusable type.
 *
 * <p>Bad configuration is a technical defect, not a business outcome: this
 * exception propagates out of the connector and CIB seven raises an incident.
 * The message names the offending parameter so the Cockpit incident is
 * actionable.
 */
public class RestConfigurationException extends ConnectorException {

    private static final long serialVersionUID = 1L;

    public RestConfigurationException(String message) {
        super(message);
    }
}
