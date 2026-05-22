package org.cibseven.community.connector.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.cibseven.community.connector.rest.StatusClassifier.TransportFailure;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class HttpInvokerTest {

    private HttpServer server;
    private HttpInvoker invoker;
    private int port;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/ok", ex -> respond(ex, 200, "{\"ok\":true}"));
        server.createContext("/notfound", ex -> respond(ex, 404, "{\"error\":\"nope\"}"));
        server.createContext("/error", ex -> respond(ex, 500, "boom"));
        server.createContext("/echo", ex -> {
            String reqBody = new String(readAll(ex.getRequestBody()), StandardCharsets.UTF_8);
            respond(ex, 200, reqBody);
        });
        server.createContext("/headers", ex -> {
            ex.getResponseHeaders().add("X-Test", "present");
            respond(ex, 200, "{}");
        });
        server.createContext("/checkheader", ex -> {
            String header = ex.getRequestHeaders().getFirst("X-Req");
            respond(ex, "abc".equals(header) ? 200 : 400, "{}");
        });
        server.createContext("/slow", ex -> {
            try {
                Thread.sleep(1500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            respond(ex, 200, "{}");
        });
        server.createContext("/redirect", ex -> {
            ex.getResponseHeaders().add("Location", baseUrl() + "/ok");
            respond(ex, 302, "");
        });
        server.createContext("/big", ex -> {
            StringBuilder body = new StringBuilder();
            for (int i = 0; i < 5000; i++) {
                body.append('x');
            }
            respond(ex, 200, body.toString());
        });
        server.start();
        port = server.getAddress().getPort();
        invoker = new HttpInvoker();
    }

    @AfterEach
    void tearDown() throws IOException {
        invoker.close();
        server.stop(0);
    }

    // --- responses ----------------------------------------------------------

    @Test
    void execute_get200_returnsResponseWithBody() {
        HttpResult result = invoker.execute(getSpec("/ok"));
        assertTrue(result.isResponse());
        assertEquals(200, result.statusCode());
        assertEquals("{\"ok\":true}", result.body());
    }

    @Test
    void execute_404_returnsResponseNotFailure() {
        HttpResult result = invoker.execute(getSpec("/notfound"));
        assertTrue(result.isResponse());
        assertEquals(404, result.statusCode());
        assertTrue(result.body().contains("nope"));
    }

    @Test
    void execute_500_returnsResponse() {
        assertEquals(500, invoker.execute(getSpec("/error")).statusCode());
    }

    @Test
    void execute_postWithBody_sendsBody() {
        HttpRequestSpec spec = new HttpRequestSpec(
            "POST", baseUrl() + "/echo", null, "{\"hello\":\"there\"}", 5000, 1_000_000);
        HttpResult result = invoker.execute(spec);
        assertEquals(200, result.statusCode());
        assertEquals("{\"hello\":\"there\"}", result.body());
    }

    @Test
    void execute_responseHeaders_captured() {
        HttpResult result = invoker.execute(getSpec("/headers"));
        assertEquals("present", result.headers().get("X-Test"));
    }

    @Test
    void execute_requestHeaders_sent() {
        HttpRequestSpec spec = new HttpRequestSpec(
            "GET", baseUrl() + "/checkheader", Map.of("X-Req", "abc"), null, 5000, 1_000_000);
        assertEquals(200, invoker.execute(spec).statusCode());
    }

    @Test
    void execute_redirect_notFollowed() {
        HttpResult result = invoker.execute(getSpec("/redirect"));
        assertTrue(result.isResponse());
        assertEquals(302, result.statusCode()); // surfaced as data, not followed to 200
    }

    // --- transport failures -------------------------------------------------

    @Test
    void execute_readTimeout_returnsTransportFailure() {
        HttpRequestSpec spec = new HttpRequestSpec(
            "GET", baseUrl() + "/slow", null, null, 300, 1_000_000);
        HttpResult result = invoker.execute(spec);
        assertEquals(HttpResult.Kind.TRANSPORT_FAILURE, result.kind());
        assertEquals(TransportFailure.READ_TIMEOUT, result.transportFailure());
    }

    @Test
    void execute_connectionRefused_returnsTransportFailure() throws IOException {
        int deadPort = findFreePort();
        HttpRequestSpec spec = new HttpRequestSpec(
            "GET", "http://127.0.0.1:" + deadPort + "/x", null, null, 2000, 1_000_000);
        HttpResult result = invoker.execute(spec);
        assertEquals(HttpResult.Kind.TRANSPORT_FAILURE, result.kind());
        assertEquals(TransportFailure.CONNECTION_FAILURE, result.transportFailure());
    }

    // --- maxResponseBytes ---------------------------------------------------

    @Test
    void execute_responseExceedsCap_throwsResponseTooLarge() {
        HttpRequestSpec spec = new HttpRequestSpec(
            "GET", baseUrl() + "/big", null, null, 5000, 1_000);
        assertThrows(ResponseTooLargeException.class, () -> invoker.execute(spec));
    }

    @Test
    void execute_responseWithinCap_ok() {
        HttpResult result = invoker.execute(
            new HttpRequestSpec("GET", baseUrl() + "/big", null, null, 5000, 10_000));
        assertEquals(200, result.statusCode());
        assertEquals(5000, result.body().length());
    }

    // --- guards -------------------------------------------------------------

    @Test
    void execute_nullSpec_throwsIllegalArgument() {
        assertThrows(IllegalArgumentException.class, () -> invoker.execute(null));
    }

    @Test
    void httpResult_statusCodeOnTransportFailure_throws() {
        HttpResult failure = HttpResult.transportFailure(TransportFailure.READ_TIMEOUT, "x");
        assertThrows(IllegalStateException.class, failure::statusCode);
    }

    // --- helpers ------------------------------------------------------------

    private String baseUrl() {
        return "http://127.0.0.1:" + port;
    }

    private HttpRequestSpec getSpec(String path) {
        return new HttpRequestSpec("GET", baseUrl() + path, null, null, 5000, 1_000_000);
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length > 0 ? bytes.length : -1);
        if (bytes.length > 0) {
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        }
        exchange.close();
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] chunk = new byte[4096];
        int read;
        while ((read = in.read(chunk)) != -1) {
            out.write(chunk, 0, read);
        }
        return out.toByteArray();
    }

    private static int findFreePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
