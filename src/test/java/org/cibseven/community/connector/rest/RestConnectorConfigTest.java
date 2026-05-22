package org.cibseven.community.connector.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class RestConnectorConfigTest {

    /** A minimal valid parameter map; each test mutates a fresh copy. */
    private static Map<String, Object> validParams() {
        Map<String, Object> p = new HashMap<>();
        p.put("source", "{}");
        p.put("url", "https://api.example.com/orders");
        p.put("method", "POST");
        return p;
    }

    @Test
    void minimalValid_appliesDefaults() {
        RestConnectorConfig cfg = RestConnectorConfig.fromParameters(validParams());
        assertEquals("https://api.example.com/orders", cfg.url());
        assertEquals("POST", cfg.method());
        assertEquals(30_000, cfg.readTimeoutMs());
        assertEquals(3, cfg.retryCount());
        assertEquals(2_000, cfg.retryDelayMs());
        assertEquals(10_485_760L, cfg.maxResponseBytes());
        assertFalse(cfg.retryNonIdempotent());
        assertTrue(cfg.businessErrorStatuses().isEmpty());
        assertTrue(cfg.retryableStatuses().contains(503));
        assertNull(cfg.requestMapping());
    }

    @Test
    void missingUrl_throws() {
        Map<String, Object> p = validParams();
        p.remove("url");
        assertThrows(RestConfigurationException.class, () -> RestConnectorConfig.fromParameters(p));
    }

    @Test
    void blankUrl_throws() {
        Map<String, Object> p = validParams();
        p.put("url", "   ");
        assertThrows(RestConfigurationException.class, () -> RestConnectorConfig.fromParameters(p));
    }

    @Test
    void missingSource_throws() {
        Map<String, Object> p = validParams();
        p.remove("source");
        assertThrows(RestConfigurationException.class, () -> RestConnectorConfig.fromParameters(p));
    }

    @Test
    void missingMethod_throws() {
        Map<String, Object> p = validParams();
        p.remove("method");
        assertThrows(RestConfigurationException.class, () -> RestConnectorConfig.fromParameters(p));
    }

    @Test
    void unsupportedMethod_throws() {
        Map<String, Object> p = validParams();
        p.put("method", "FETCH");
        assertThrows(RestConfigurationException.class, () -> RestConnectorConfig.fromParameters(p));
    }

    @Test
    void method_isUppercasedAndTrimmed() {
        Map<String, Object> p = validParams();
        p.put("method", "  get ");
        assertEquals("GET", RestConnectorConfig.fromParameters(p).method());
    }

    @Test
    void numericParam_asString_isParsed() {
        Map<String, Object> p = validParams();
        p.put("readTimeout", "5000");
        assertEquals(5000, RestConnectorConfig.fromParameters(p).readTimeoutMs());
    }

    @Test
    void numericParam_asInteger_isParsed() {
        Map<String, Object> p = validParams();
        p.put("readTimeout", 7000);
        assertEquals(7000, RestConnectorConfig.fromParameters(p).readTimeoutMs());
    }

    @Test
    void negativeReadTimeout_throws() {
        Map<String, Object> p = validParams();
        p.put("readTimeout", "-1");
        assertThrows(RestConfigurationException.class, () -> RestConnectorConfig.fromParameters(p));
    }

    @Test
    void nonIntegerReadTimeout_throws() {
        Map<String, Object> p = validParams();
        p.put("readTimeout", "soon");
        assertThrows(RestConfigurationException.class, () -> RestConnectorConfig.fromParameters(p));
    }

    @Test
    void retryCountZero_isAllowed() {
        Map<String, Object> p = validParams();
        p.put("retryCount", "0");
        assertEquals(0, RestConnectorConfig.fromParameters(p).retryCount());
    }

    @Test
    void negativeRetryCount_throws() {
        Map<String, Object> p = validParams();
        p.put("retryCount", "-2");
        assertThrows(RestConfigurationException.class, () -> RestConnectorConfig.fromParameters(p));
    }

    @Test
    void businessErrorStatuses_areParsed() {
        Map<String, Object> p = validParams();
        p.put("businessErrorStatuses", "404, 409");
        Set<Integer> statuses = RestConnectorConfig.fromParameters(p).businessErrorStatuses();
        assertEquals(2, statuses.size());
        assertTrue(statuses.contains(404) && statuses.contains(409));
    }

    @Test
    void nonIntegerInStatusList_throws() {
        Map<String, Object> p = validParams();
        p.put("businessErrorStatuses", "404,nope");
        assertThrows(RestConfigurationException.class, () -> RestConnectorConfig.fromParameters(p));
    }

    @Test
    void retryableStatuses_overrideReplacesDefault() {
        Map<String, Object> p = validParams();
        p.put("retryableStatuses", "500,503");
        Set<Integer> statuses = RestConnectorConfig.fromParameters(p).retryableStatuses();
        assertEquals(2, statuses.size());
        assertFalse(statuses.contains(429)); // overridden, not merged with the default
    }

    @Test
    void retryNonIdempotent_isParsed() {
        Map<String, Object> p = validParams();
        p.put("retryNonIdempotent", "true");
        assertTrue(RestConnectorConfig.fromParameters(p).retryNonIdempotent());
    }

    @Test
    void headers_areReadFromMap() {
        Map<String, Object> p = validParams();
        p.put("headers", Map.of("Authorization", "Bearer x"));
        assertEquals("Bearer x", RestConnectorConfig.fromParameters(p).headers().get("Authorization"));
    }
}
