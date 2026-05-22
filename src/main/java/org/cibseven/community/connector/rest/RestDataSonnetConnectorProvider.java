package org.cibseven.community.connector.rest;

import org.cibseven.connect.spi.Connector;
import org.cibseven.connect.spi.ConnectorProvider;

/**
 * Service-loader entry point for the connector.
 *
 * <p>CIB seven's Connect engine discovers connectors through
 * {@link java.util.ServiceLoader}: this class is named in
 * {@code META-INF/services/org.cibseven.connect.spi.ConnectorProvider}. The
 * engine calls {@link #getConnectorId()} to register the {@code rest-datasonnet}
 * id and {@link #createConnectorInstance()} to obtain the connector.
 */
public class RestDataSonnetConnectorProvider implements ConnectorProvider {

    @Override
    public String getConnectorId() {
        return RestDataSonnetConnector.CONNECTOR_ID;
    }

    @Override
    public Connector<?> createConnectorInstance() {
        return new RestDataSonnetConnector();
    }
}
