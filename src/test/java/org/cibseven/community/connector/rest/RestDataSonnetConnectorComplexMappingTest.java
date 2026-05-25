package org.cibseven.community.connector.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.cibseven.connect.spi.ConnectorResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Demonstrates the connector's value by exercising complex request and response
 * DataSonnet mappings against realistic external API shapes — the kind of
 * mismatch a modeler hits when an internal canonical domain model meets an
 * external ERP / WMS API.
 *
 * <p>Each test asserts on <b>both directions</b>:
 * <ol>
 *   <li>the request body the server received (proves the request mapping
 *       reshaped the internal model into the external API shape);</li>
 *   <li>the {@code result} returned to the process (proves the response
 *       mapping reshaped the external response back to the internal model).</li>
 * </ol>
 *
 * <p>The mappings cover: nested-object reshaping, field renaming, conditional
 * value mapping, array transformation, filtering, aggregation, computed
 * totals, lookup-table status normalisation, and {@code std} stdlib calls.
 */
class RestDataSonnetConnectorComplexMappingTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private HttpServer server;
    private RestDataSonnetConnector connector;
    private int port;

    /** Captures what the stub server received, so each test can assert on it. */
    private final AtomicReference<String> capturedRequestBody = new AtomicReference<>();

    @BeforeEach
    void setUp() throws IOException {
        capturedRequestBody.set(null);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);

        // POST /erp/orders — the external ERP. Captures the inbound body and
        // returns a realistic, deeply-nested ERP response so we can prove the
        // response mapping flattens and renormalises it.
        server.createContext("/erp/orders", ex -> {
            capturedRequestBody.set(new String(readRequestBody(ex), StandardCharsets.UTF_8));
            respond(ex, 201, ERP_RESPONSE);
        });

        // PUT /wms/locations/TLL-1/inventory — the external WMS. Same pattern.
        server.createContext("/wms/locations/TLL-1/inventory", ex -> {
            capturedRequestBody.set(new String(readRequestBody(ex), StandardCharsets.UTF_8));
            respond(ex, 200, WMS_RESPONSE);
        });

        server.start();
        port = server.getAddress().getPort();
        connector = new RestDataSonnetConnector();
    }

    @AfterEach
    void tearDown() throws IOException {
        connector.close();
        server.stop(0);
    }

    // =======================================================================
    // POST /erp/orders — create an order in an external ERP system.
    //
    // Internal canonical model carries a nested customer + items priced in
    // major units (EUR). The ERP wants flat field names, an address split out
    // under different keys, line totals + an overall total in minor units
    // (cents), and a shipping priority enum derived from `expedited`.
    // =======================================================================

    @Test
    void post_createOrder_reshapesNestedDomainModelIntoErpApi() throws Exception {
        Map<String, Object> p = new HashMap<>();
        p.put("url", "http://127.0.0.1:" + port + "/erp/orders");
        p.put("method", "POST");
        p.put("source", INTERNAL_ORDER);
        p.put("requestMapping", ERP_REQUEST_MAPPING);
        p.put("responseMapping", ERP_RESPONSE_MAPPING);
        p.put("retryCount", "0");

        ConnectorResponse r = run(p);

        // The connector succeeded against the ERP.
        assertEquals("SUCCESS", (String) r.getResponseParameter("restOutcome"));
        assertEquals(201, (int) r.getResponseParameter("statusCode"));

        // --- prove the REQUEST mapping ---------------------------------------
        JsonNode sentToErp = JSON.readTree(capturedRequestBody.get());

        assertEquals("C-9001", sentToErp.get("external_customer_ref").asText());
        assertEquals("billing@acme.example", sentToErp.get("buyer_email").asText());
        assertEquals("RUSH", sentToErp.get("shipping_priority").asText(),
            "expedited=true must be reshaped to the ERP's RUSH priority enum");
        assertEquals("EUR", sentToErp.get("currency_iso").asText());
        assertEquals("gift wrap please", sentToErp.get("memo").asText());

        // The billing address was split out under new field names.
        JsonNode ship = sentToErp.get("ship_to");
        assertEquals("1 Acme Way", ship.get("addr_line_1").asText());
        assertEquals("Tallinn",    ship.get("addr_city").asText());
        assertEquals("10115",      ship.get("addr_postcode").asText());
        assertEquals("EE",         ship.get("addr_country_iso2").asText());

        // Each line item was renamed and priced in minor units. The internal
        // model has unitPrice in EUR; the ERP wants amount_cents per line:
        //   WIDGET-A: 19.99 * 3 * 100 = 5997
        //   WIDGET-B: 49.50 * 1 * 100 = 4950
        //   GIZMO-7 :  4.20 * 5 * 100 = 2100
        JsonNode lines = sentToErp.get("line_items");
        assertEquals(3, lines.size());
        assertEquals("WIDGET-A", lines.get(0).get("item_code").asText());
        assertEquals(3,          lines.get(0).get("qty").asInt());
        assertEquals(5997,       lines.get(0).get("amount_cents").asInt());
        assertEquals("WIDGET-B", lines.get(1).get("item_code").asText());
        assertEquals(4950,       lines.get(1).get("amount_cents").asInt());
        assertEquals("GIZMO-7",  lines.get(2).get("item_code").asText());
        assertEquals(2100,       lines.get(2).get("amount_cents").asInt());

        // A computed total across the line items (5997 + 4950 + 2100 = 13047).
        assertEquals(13047, sentToErp.get("total_cents").asInt(),
            "total_cents must be computed from the line items by the mapping");

        // --- prove the RESPONSE mapping --------------------------------------
        String resultJson = (String) r.getResponseParameter("result");
        JsonNode result = JSON.readTree(resultJson);

        assertEquals("ERP-44792", result.get("orderId").asText());
        assertEquals("PENDING",   result.get("status").asText(),
            "PENDING_FULFILLMENT must be normalised to the internal PENDING status");
        assertEquals("C-9001",    result.get("customerId").asText());

        JsonNode mappedLines = result.get("lines");
        assertEquals(3, mappedLines.size());
        assertEquals("WIDGET-A", mappedLines.get(0).get("sku").asText());
        assertEquals(3,          mappedLines.get(0).get("quantity").asInt());
        assertEquals(5997,       mappedLines.get(0).get("lineTotalCents").asInt());
        assertEquals("TLL-1",    mappedLines.get(0).get("warehouse").asText());
        assertEquals("RIX-3",    mappedLines.get(2).get("warehouse").asText());

        // A unique, sorted list of warehouses touched — aggregated from the
        // per-line `warehouse` field by the response mapping.
        JsonNode warehouses = result.get("warehousesUsed");
        assertEquals(2, warehouses.size());
        assertEquals("RIX-3", warehouses.get(0).asText());
        assertEquals("TLL-1", warehouses.get(1).asText());

        // ERP audit fields are deliberately dropped by the response mapping —
        // they never reach the internal model.
        assertFalse(result.has("audit"),  "ERP audit metadata must not leak into the internal result");
    }

    // =======================================================================
    // PUT /wms/locations/{id}/inventory — bulk inventory adjustment.
    //
    // Internal model carries signed deltas (-2, 0, +5) per SKU, with optional
    // lot refs. The WMS wants a different shape: zero deltas filtered out, and
    // each signed delta split into an explicit direction (IN/OUT) + an absolute
    // magnitude.
    // =======================================================================

    @Test
    void put_inventoryAdjustment_filtersAndSplitsSignedDeltas() throws Exception {
        Map<String, Object> p = new HashMap<>();
        p.put("url", "http://127.0.0.1:" + port + "/wms/locations/TLL-1/inventory");
        p.put("method", "PUT");
        p.put("source", INTERNAL_INVENTORY_ADJUSTMENT);
        p.put("requestMapping", WMS_REQUEST_MAPPING);
        p.put("responseMapping", WMS_RESPONSE_MAPPING);
        p.put("retryCount", "0");

        ConnectorResponse r = run(p);

        assertEquals("SUCCESS", (String) r.getResponseParameter("restOutcome"));
        assertEquals(200, (int) r.getResponseParameter("statusCode"));

        // --- prove the REQUEST mapping ---------------------------------------
        JsonNode sentToWms = JSON.readTree(capturedRequestBody.get());

        assertEquals("TLL-1",                    sentToWms.get("location").asText());
        assertEquals("STOCK_TAKE_CORRECTION",    sentToWms.get("reasonCode").asText());

        JsonNode movements = sentToWms.get("movements");
        assertNotNull(movements);
        // The internal model had 3 adjustments; the GIZMO-7 entry with delta=0
        // must be filtered out by the request mapping.
        assertEquals(2, movements.size(),
            "zero-delta adjustments must be filtered out before sending to the WMS");

        // Positive delta → direction IN, absQty = delta.
        JsonNode mvA = movements.get(0);
        assertEquals("WIDGET-A",   mvA.get("itemCode").asText());
        assertEquals("IN",         mvA.get("direction").asText());
        assertEquals(5,            mvA.get("absQty").asInt());
        assertEquals("L-2024-Q4-A", mvA.get("lotRef").asText());

        // Negative delta → direction OUT, absQty = |delta|; lotRef carries
        // through as null because the internal lot was null.
        JsonNode mvB = movements.get(1);
        assertEquals("WIDGET-B", mvB.get("itemCode").asText());
        assertEquals("OUT",      mvB.get("direction").asText());
        assertEquals(2,          mvB.get("absQty").asInt());
        assertTrue(mvB.get("lotRef").isNull(),
            "a missing internal lot must carry through to the WMS as an explicit null");

        // --- prove the RESPONSE mapping --------------------------------------
        JsonNode result = JSON.readTree((String) r.getResponseParameter("result"));

        assertEquals(2, result.get("applied").asInt());
        assertFalse(result.get("hasWarnings").asBoolean(),
            "an empty warnings array must collapse to hasWarnings=false");

        JsonNode stockBySku = result.get("stockBySku");
        assertEquals(2, stockBySku.size());
        assertEquals("WIDGET-A", stockBySku.get(0).get("sku").asText());
        assertEquals(105,        stockBySku.get(0).get("onHand").asInt());
        assertEquals("WIDGET-B", stockBySku.get(1).get("sku").asText());
        assertEquals(18,         stockBySku.get(1).get("onHand").asInt());
    }

    // --- helpers ------------------------------------------------------------

    private ConnectorResponse run(Map<String, Object> params) {
        RestDataSonnetRequest request = connector.createRequest();
        request.setRequestParameters(params);
        return connector.execute(request);
    }

    private static void respond(HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
        ex.close();
    }

    private static byte[] readRequestBody(HttpExchange ex) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] chunk = new byte[2048];
        int read;
        while ((read = ex.getRequestBody().read(chunk)) != -1) {
            out.write(chunk, 0, read);
        }
        return out.toByteArray();
    }

    // =======================================================================
    // Fixtures — internal models, external mappings, external responses.
    // Kept at the bottom so the tests above read top-to-bottom as a narrative.
    // =======================================================================

    private static final String INTERNAL_ORDER =
        "{"
        + "  \"customer\": {"
        + "    \"id\": \"C-9001\","
        + "    \"name\": \"Acme Corp\","
        + "    \"email\": \"billing@acme.example\","
        + "    \"billingAddress\": {"
        + "      \"street\": \"1 Acme Way\","
        + "      \"city\":   \"Tallinn\","
        + "      \"country\":\"EE\","
        + "      \"zip\":    \"10115\""
        + "    }"
        + "  },"
        + "  \"items\": ["
        + "    {\"sku\": \"WIDGET-A\", \"quantity\": 3, \"unitPrice\": 19.99},"
        + "    {\"sku\": \"WIDGET-B\", \"quantity\": 1, \"unitPrice\": 49.50},"
        + "    {\"sku\": \"GIZMO-7\",  \"quantity\": 5, \"unitPrice\":  4.20}"
        + "  ],"
        + "  \"currency\": \"EUR\","
        + "  \"expedited\": true,"
        + "  \"notes\": \"gift wrap please\""
        + "}";

    /**
     * Request mapping for POST /erp/orders. Reshapes the internal canonical
     * order into the ERP's flat-ish shape; computes line totals and an overall
     * total in minor units; maps the boolean {@code expedited} to the ERP's
     * shipping-priority enum.
     */
    private static final String ERP_REQUEST_MAPPING =
        "/** DataSonnet version=2.0 */\n"
        + "local toCents(item) = std.floor(item.unitPrice * item.quantity * 100 + 0.5);\n"
        + "{\n"
        + "  external_customer_ref: payload.customer.id,\n"
        + "  buyer_email:           payload.customer.email,\n"
        + "  shipping_priority:     if payload.expedited then \"RUSH\" else \"STANDARD\",\n"
        + "  ship_to: {\n"
        + "    addr_line_1:       payload.customer.billingAddress.street,\n"
        + "    addr_city:         payload.customer.billingAddress.city,\n"
        + "    addr_postcode:     payload.customer.billingAddress.zip,\n"
        + "    addr_country_iso2: payload.customer.billingAddress.country\n"
        + "  },\n"
        + "  line_items: std.map(function(item) {\n"
        + "    item_code:    item.sku,\n"
        + "    qty:          item.quantity,\n"
        + "    amount_cents: toCents(item)\n"
        + "  }, payload.items),\n"
        + "  currency_iso: payload.currency,\n"
        + "  total_cents:  std.foldl(function(acc, item) acc + toCents(item), payload.items, 0),\n"
        + "  memo:         payload.notes\n"
        + "}\n";

    /** The ERP's response shape — deeply nested, with audit fields the internal model does not want. */
    private static final String ERP_RESPONSE =
        "{"
        + "  \"order\": {"
        + "    \"id\": \"ERP-44792\","
        + "    \"state\": \"PENDING_FULFILLMENT\","
        + "    \"external_customer_ref\": \"C-9001\","
        + "    \"created_at_unix\": 1716000000,"
        + "    \"lines\": ["
        + "      {\"line_no\": 1, \"item_code\": \"WIDGET-A\", \"qty\": 3, \"amount_cents\": 5997, \"warehouse\": \"TLL-1\"},"
        + "      {\"line_no\": 2, \"item_code\": \"WIDGET-B\", \"qty\": 1, \"amount_cents\": 4950, \"warehouse\": \"TLL-1\"},"
        + "      {\"line_no\": 3, \"item_code\": \"GIZMO-7\",  \"qty\": 5, \"amount_cents\": 2100, \"warehouse\": \"RIX-3\"}"
        + "    ],"
        + "    \"audit\": {"
        + "      \"received_by\": \"api-gateway-3\","
        + "      \"trace_id\": \"tr-abc123\""
        + "    }"
        + "  }"
        + "}";

    /**
     * Response mapping for POST /erp/orders. Renormalises the ERP shape into
     * the internal model: drops the audit envelope, normalises the status via
     * a lookup table, renames line fields, and aggregates a unique sorted list
     * of warehouses touched.
     */
    private static final String ERP_RESPONSE_MAPPING =
        "/** DataSonnet version=2.0 */\n"
        + "local order = payload.order;\n"
        + "local statusMap = {\n"
        + "  \"PENDING_FULFILLMENT\": \"PENDING\",\n"
        + "  \"READY_TO_SHIP\":       \"READY\",\n"
        + "  \"SHIPPED\":             \"SHIPPED\"\n"
        + "};\n"
        + "{\n"
        + "  orderId:    order.id,\n"
        + "  status:     if std.objectHas(statusMap, order.state) then statusMap[order.state] else order.state,\n"
        + "  customerId: order.external_customer_ref,\n"
        + "  lines: std.map(function(l) {\n"
        + "    sku:            l.item_code,\n"
        + "    quantity:       l.qty,\n"
        + "    lineTotalCents: l.amount_cents,\n"
        + "    warehouse:      l.warehouse\n"
        + "  }, order.lines),\n"
        + "  warehousesUsed: std.uniq(std.sort(std.map(function(l) l.warehouse, order.lines)))\n"
        + "}\n";

    private static final String INTERNAL_INVENTORY_ADJUSTMENT =
        "{"
        + "  \"warehouseId\": \"TLL-1\","
        + "  \"reason\": \"STOCK_TAKE_CORRECTION\","
        + "  \"adjustments\": ["
        + "    {\"sku\": \"WIDGET-A\", \"delta\":  5, \"lot\": \"L-2024-Q4-A\"},"
        + "    {\"sku\": \"WIDGET-B\", \"delta\": -2, \"lot\": null},"
        + "    {\"sku\": \"GIZMO-7\",  \"delta\":  0, \"lot\": \"L-2025-Q1-G\"}"
        + "  ]"
        + "}";

    /**
     * Request mapping for PUT /wms/locations/.../inventory. Filters out
     * zero-delta adjustments (the WMS treats them as errors), and splits the
     * signed delta into an explicit direction enum + an absolute magnitude.
     */
    private static final String WMS_REQUEST_MAPPING =
        "/** DataSonnet version=2.0 */\n"
        + "{\n"
        + "  location:   payload.warehouseId,\n"
        + "  reasonCode: payload.reason,\n"
        + "  movements: std.map(function(a) {\n"
        + "    itemCode:  a.sku,\n"
        + "    direction: if a.delta > 0 then \"IN\" else \"OUT\",\n"
        + "    absQty:    if a.delta > 0 then a.delta else -a.delta,\n"
        + "    lotRef:    a.lot\n"
        + "  }, std.filter(function(a) a.delta != 0, payload.adjustments))\n"
        + "}\n";

    /** The WMS's response — nested under {@code result}, with a warnings array. */
    private static final String WMS_RESPONSE =
        "{"
        + "  \"result\": {"
        + "    \"applied\": 2,"
        + "    \"skipped\": 0,"
        + "    \"newStockLevels\": ["
        + "      {\"itemCode\": \"WIDGET-A\", \"onHand\": 105},"
        + "      {\"itemCode\": \"WIDGET-B\", \"onHand\":  18}"
        + "    ],"
        + "    \"warnings\": []"
        + "  }"
        + "}";

    /**
     * Response mapping for PUT /wms/locations/.../inventory. Lifts the
     * envelope, renames the per-SKU rows, and collapses the warnings array to
     * a boolean for the internal model.
     */
    private static final String WMS_RESPONSE_MAPPING =
        "/** DataSonnet version=2.0 */\n"
        + "{\n"
        + "  applied: payload.result.applied,\n"
        + "  stockBySku: std.map(function(l) {\n"
        + "    sku:    l.itemCode,\n"
        + "    onHand: l.onHand\n"
        + "  }, payload.result.newStockLevels),\n"
        + "  hasWarnings: std.length(payload.result.warnings) > 0\n"
        + "}\n";
}
