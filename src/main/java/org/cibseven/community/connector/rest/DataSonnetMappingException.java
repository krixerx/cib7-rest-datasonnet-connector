package org.cibseven.community.connector.rest;

/**
 * Thrown when a DataSonnet script fails to compile or to evaluate.
 *
 * <p>This is a connector-internal exception, deliberately a plain
 * {@link RuntimeException}: {@link DataSonnetMapper} stays free of any Connect
 * SPI dependency so it can be unit-tested in isolation. The connector's
 * {@code execute()} catches this and rethrows it as a {@code RestMappingException}
 * (a {@code ConnectorException}), tagged with which script failed (request /
 * response / error), so the failure becomes an actionable Cockpit incident.
 */
public class DataSonnetMappingException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public DataSonnetMappingException(String message, Throwable cause) {
        super(message, cause);
    }
}
