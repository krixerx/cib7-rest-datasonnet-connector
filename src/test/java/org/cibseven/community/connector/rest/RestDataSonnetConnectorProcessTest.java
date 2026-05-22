package org.cibseven.community.connector.rest;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.cibseven.bpm.engine.ProcessEngine;
import org.cibseven.bpm.engine.impl.cfg.ProcessEnginePlugin;
import org.cibseven.bpm.engine.impl.cfg.StandaloneInMemProcessEngineConfiguration;
import org.cibseven.bpm.engine.repository.Deployment;
import org.cibseven.bpm.engine.runtime.ProcessInstance;
import org.cibseven.connect.plugin.impl.ConnectProcessEnginePlugin;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Process-level test: the connector running inside a real in-memory CIB seven
 * engine, wrapped in the embedded-subprocess pattern. Proves the whole chain —
 * {@code <camunda:connector>} parsing, {@code execute()}, the outcome gateway,
 * the Error End Event, and the boundary event — works end to end, not just the
 * connector in isolation.
 */
class RestDataSonnetConnectorProcessTest {

    private static ProcessEngine engine;
    private HttpServer server;
    private Deployment deployment;
    private int port;

    @BeforeAll
    static void startEngine() {
        StandaloneInMemProcessEngineConfiguration cfg =
            new StandaloneInMemProcessEngineConfiguration();
        cfg.setJdbcUrl("jdbc:h2:mem:rest-datasonnet-process-test;DB_CLOSE_DELAY=-1");
        cfg.setHistory("full");
        cfg.setProcessEnginePlugins(
            List.<ProcessEnginePlugin>of(new ConnectProcessEnginePlugin()));
        engine = cfg.buildProcessEngine();
    }

    @AfterAll
    static void stopEngine() {
        if (engine != null) {
            engine.close();
        }
    }

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/ok", ex -> respond(ex, 200, "{\"id\":\"o-1\"}"));
        server.createContext("/notfound", ex -> respond(ex, 404, "{\"message\":\"nope\"}"));
        server.start();
        port = server.getAddress().getPort();
        deployment = engine.getRepositoryService().createDeployment()
            .addClasspathResource("process-test.bpmn")
            .deploy();
    }

    @AfterEach
    void tearDown() {
        if (deployment != null) {
            engine.getRepositoryService().deleteDeployment(deployment.getId(), true);
        }
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void success_subprocessCompletesAndReachesDone() {
        ProcessInstance pi = start("/ok", "404");
        assertTrue(passed(pi, "done"),
            "a 2xx should run the subprocess to completion and reach 'done'");
    }

    @Test
    void declared404_routesThroughTheBoundaryEvent() {
        ProcessInstance pi = start("/notfound", "404");
        assertTrue(passed(pi, "handledNotFound"),
            "a declared 404 should become a BUSINESS_ERROR, throw REST_ERROR_404 "
            + "from the Error End Event, and be caught by the boundary event");
    }

    @Test
    void undeclared404_failsTheInstanceAsASystemFault() {
        // 404 with no declared business status: the connector throws, so the
        // synchronous instance fails rather than routing anywhere.
        assertThrows(Exception.class, () -> start("/notfound", ""));
    }

    // --- helpers ------------------------------------------------------------

    private ProcessInstance start(String path, String businessErrorStatuses) {
        Map<String, Object> vars = new HashMap<>();
        vars.put("url", "http://127.0.0.1:" + port + path);
        vars.put("method", "GET");
        vars.put("source", "{}");
        vars.put("businessErrorStatuses", businessErrorStatuses);
        return engine.getRuntimeService().startProcessInstanceByKey("restTest", vars);
    }

    private boolean passed(ProcessInstance pi, String activityId) {
        return engine.getHistoryService()
            .createHistoricActivityInstanceQuery()
            .processInstanceId(pi.getId())
            .activityId(activityId)
            .count() > 0;
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
        exchange.close();
    }
}
