package org.cibseven.community.connector.rest;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.cibseven.connect.impl.AbstractConnectorResponse;

/**
 * The connector's response object: it carries the output values that
 * {@code <camunda:outputParameter>} maps to process variables.
 *
 * <p>The connector returns a response only for the three modeler-facing
 * outcomes — SUCCESS, BUSINESS_ERROR, RESPONSE_MAPPING_FAILED. A system fault
 * throws instead, so there is no response. {@link RestDataSonnetConnector}
 * builds the output map per outcome and hands it here.
 */
public class RestDataSonnetResponse extends AbstractConnectorResponse {

    private final Map<String, Object> outputs;

    public RestDataSonnetResponse(Map<String, Object> outputs) {
        this.outputs = outputs == null
            ? Collections.emptyMap()
            : new LinkedHashMap<>(outputs);
    }

    @Override
    protected void collectResponseParameters(Map<String, Object> responseParameters) {
        responseParameters.putAll(outputs);
    }
}
