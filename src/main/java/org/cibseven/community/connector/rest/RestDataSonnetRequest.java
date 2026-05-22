package org.cibseven.community.connector.rest;

import org.cibseven.connect.impl.AbstractConnectorRequest;
import org.cibseven.connect.spi.Connector;

/**
 * The connector's request object: it carries the {@code <camunda:inputParameter>}
 * values into {@link RestDataSonnetConnector#execute}.
 *
 * <p>{@link AbstractConnectorRequest} already provides parameter storage and the
 * {@code execute()} method (which delegates to the connector), so this class is
 * just the typed constructor.
 */
public class RestDataSonnetRequest extends AbstractConnectorRequest<RestDataSonnetResponse> {

    public RestDataSonnetRequest(Connector<?> connector) {
        super(connector);
    }
}
