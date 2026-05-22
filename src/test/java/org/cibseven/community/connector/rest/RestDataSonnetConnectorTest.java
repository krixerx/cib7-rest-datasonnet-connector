package org.cibseven.community.connector.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.cibseven.connect.ConnectorException;
import org.cibseven.connect.spi.ConnectorResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Exercises the full {@code execute()} orchestration against the JDK's built-in
 * HTTP server — no WireMock needed for this tier.
 */
class RestDataSonnetConnectorTest {

    private HttpServer server;
    private RestDataSonnetConnector connector;
    private int port;
    private final AtomicInteger flakyCalls = new AtomicInteger();

    @BeforeEach
    void setUp() throws IOException {
        flakyCalls.set(0);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/ok", ex -> respond(ex, 200, "{\"id\":\"o-1\",\"status\":\"NEW\"}"));
        server.createContext("/notfound", ex -> respond(ex, 404, "{\"message\":\"no such order\"}"));
        server.createContext("/unauthorized", ex -> respond(ex, 401, "{\"message\":\"bad token\"}"));
        server.createContext("/echo", ex -> respond(ex, 200,
            new String(readRequestBody(ex), StandardCharsets.UTF_8)));
        server.createContext("/flaky", ex -> {
            int n = flakyCalls.incrementAndGet();
            respond(ex, n < 3 ? 503 : 200, n < 3 ? "{}" : "{\"id\":\"o-9\"}");
        });
        server.createContext("/always503", ex -> respond(ex, 503, "{}"));
        server.start();
        port = server.getAddress().getPort();
        connector = new RestDataSonnetConnector();
    }

    @AfterEach
    void tearDown() throws IOException {
        connector.close();
        server.stop(0);
    }

    // --- SUCCESS ------------------------------------------------------------

    @Test
    void success_noResponseMapping_returnsRawBody() {
        ConnectorResponse r = run(params("/ok", "GET"));
        String outcome = r.getResponseParameter("restOutcome");
        int status = r.getResponseParameter("statusCode");
        String result = r.getResponseParameter("result");
        assertEquals("SUCCESS", outcome);
        assertEquals(200, status);
        assertTrue(result.contains("o-1"));
    }

    @Test
    void success_withResponseMapping_transformsResult() {
        Map<String, Object> p = params("/ok", "GET");
        p.put("responseMapping", "/** DataSonnet version=2.0 */\n{ orderId: payload.id }");
        ConnectorResponse r = run(p);
        String outcome = r.getResponseParameter("restOutcome");
        String result = r.getResponseParameter("result");
        assertEquals("SUCCESS", outcome);
        assertEquals("{\"orderId\":\"o-1\"}", result.replaceAll("\\s", ""));
    }

    // --- BUSINESS_ERROR -----------------------------------------------------

    @Test
    void declared404_returnsBusinessError() {
        Map<String, Object> p = params("/notfound", "GET");
        p.put("businessErrorStatuses", "404");
        ConnectorResponse r = run(p);
        String outcome = r.getResponseParameter("restOutcome");
        String code = r.getResponseParameter("restErrorCode");
        int status = r.getResponseParameter("statusCode");
        assertEquals("BUSINESS_ERROR", outcome);
        assertEquals("REST_ERROR_404", code);
        assertEquals(404, status);
        assertNotNull(r.getResponseParameter("restError"));
    }

    @Test
    void declared404_withErrorMapping_shapesRestError() {
        Map<String, Object> p = params("/notfound", "GET");
        p.put("businessErrorStatuses", "404");
        p.put("errorMapping",
            "/** DataSonnet version=2.0 */\n{ reason: \"not found\", code: payload.status }");
        ConnectorResponse r = run(p);
        String restError = ((String) r.getResponseParameter("restError")).replaceAll("\\s", "");
        assertEquals("BUSINESS_ERROR", (String) r.getResponseParameter("restOutcome"));
        assertTrue(restError.contains("\"code\":404"), restError);
    }

    // --- SYSTEM_FAULT (throws -> incident) ----------------------------------

    @Test
    void undeclared401_throwsConnectorException() {
        ConnectorException ex = assertThrows(ConnectorException.class,
            () -> run(params("/unauthorized", "GET")));
        assertTrue(ex.getMessage().contains("401"), ex.getMessage());
    }

    @Test
    void undeclared404_throwsConnectorException() {
        // 404 with no businessErrorStatuses is a system fault, not a business error
        assertThrows(ConnectorException.class, () -> run(params("/notfound", "GET")));
    }

    // --- RESPONSE_MAPPING_FAILED --------------------------------------------

    @Test
    void responseMappingFails_returnsRawBodyAsData() {
        Map<String, Object> p = params("/ok", "GET");
        p.put("responseMapping", "/** DataSonnet version=2.0 */\nerror \"cannot map\"");
        ConnectorResponse r = run(p);
        String outcome = r.getResponseParameter("restOutcome");
        String code = r.getResponseParameter("restErrorCode");
        String raw = r.getResponseParameter("restRawResponse");
        assertEquals("RESPONSE_MAPPING_FAILED", outcome);
        assertEquals("REST_ERROR_RESPONSE_MAPPING", code);
        assertTrue(raw.contains("o-1"), "the raw 2xx body must be preserved");
    }

    // --- request mapping ----------------------------------------------------

    @Test
    void requestMapping_transformsTheBodySent() {
        Map<String, Object> p = params("/echo", "POST");
        p.put("source", "{\"name\":\"erki\"}");
        p.put("requestMapping",
            "/** DataSonnet version=2.0 */\n{ greeting: \"hi \" + payload.name }");
        ConnectorResponse r = run(p);
        String result = ((String) r.getResponseParameter("result")).replaceAll("\\s", "");
        assertEquals("SUCCESS", (String) r.getResponseParameter("restOutcome"));
        assertEquals("{\"greeting\":\"hierki\"}", result);
    }

    @Test
    void requestMappingFails_throwsRestMappingException() {
        Map<String, Object> p = params("/echo", "POST");
        p.put("requestMapping", "/** DataSonnet version=2.0 */\nerror \"bad request\"");
        assertThrows(RestMappingException.class, () -> run(p));
    }

    // --- retry --------------------------------------------------------------

    @Test
    void retriesTransient503_thenSucceeds() {
        Map<String, Object> p = params("/flaky", "GET"); // 503, 503, then 200
        p.put("retryCount", "3");
        ConnectorResponse r = run(p);
        assertEquals("SUCCESS", (String) r.getResponseParameter("restOutcome"));
        assertEquals(3, flakyCalls.get());
    }

    @Test
    void retriesExhausted_undeclared503_throwsConnectorException() {
        Map<String, Object> p = params("/always503", "GET");
        p.put("retryCount", "1"); // 2 attempts, both 503; 503 undeclared -> system fault
        assertThrows(ConnectorException.class, () -> run(p));
    }

    // --- validation ---------------------------------------------------------

    @Test
    void missingUrl_throwsConfigurationException() {
        Map<String, Object> p = params("/ok", "GET");
        p.remove("url");
        assertThrows(RestConfigurationException.class, () -> run(p));
    }

    // --- helpers ------------------------------------------------------------

    private ConnectorResponse run(Map<String, Object> params) {
        RestDataSonnetRequest request = connector.createRequest();
        request.setRequestParameters(params);
        return connector.execute(request);
    }

    private Map<String, Object> params(String path, String method) {
        Map<String, Object> p = new HashMap<>();
        p.put("url", "http://127.0.0.1:" + port + path);
        p.put("method", method);
        p.put("source", "{}");
        p.put("retryCount", "2");
        p.put("retryDelay", "10");
        return p;
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
        exchange.close();
    }

    private static byte[] readRequestBody(HttpExchange exchange) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] chunk = new byte[2048];
        int read;
        while ((read = exchange.getRequestBody().read(chunk)) != -1) {
            out.write(chunk, 0, read);
        }
        return out.toByteArray();
    }
}
