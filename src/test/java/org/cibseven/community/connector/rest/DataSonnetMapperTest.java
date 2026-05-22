package org.cibseven.community.connector.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

class DataSonnetMapperTest {

    private final DataSonnetMapper mapper = new DataSonnetMapper();

    /** Strips all whitespace so assertions do not depend on DataSonnet's output formatting. */
    private static String compact(String json) {
        return json.replaceAll("\\s", "");
    }

    // --- transform ----------------------------------------------------------

    @Test
    void transform_scalarScript_producesResult() {
        String out = mapper.transform("payload.a + payload.b", "{\"a\":2,\"b\":3}");
        assertEquals("5", out.trim());
    }

    @Test
    void transform_objectScript_producesJson() {
        String out = mapper.transform(
            "{ greeting: \"hi \" + payload.name }", "{\"name\":\"world\"}");
        assertEquals("{\"greeting\":\"hiworld\"}", compact(out));
    }

    @Test
    void transform_withVersionHeader_works() {
        String script = "/** DataSonnet version=2.0 */\npayload.name";
        String out = mapper.transform(script, "{\"name\":\"erki\"}");
        assertEquals("\"erki\"", out.trim());
    }

    @Test
    void transform_compileFailure_throwsMappingException() {
        assertThrows(DataSonnetMappingException.class,
            () -> mapper.transform("{ this is not valid jsonnet", "{}"));
    }

    @Test
    void transform_evalFailure_throwsMappingException() {
        assertThrows(DataSonnetMappingException.class,
            () -> mapper.transform("error \"boom\"", "{}"));
    }

    @Test
    void transform_nullScript_throwsMappingException() {
        assertThrows(DataSonnetMappingException.class,
            () -> mapper.transform(null, "{}"));
    }

    @Test
    void transform_nullPayload_throwsMappingException() {
        assertThrows(DataSonnetMappingException.class,
            () -> mapper.transform("payload", null));
    }

    // --- cache --------------------------------------------------------------

    @Test
    void cache_sameScriptCompiledOnce() {
        String script = "payload.x";
        mapper.transform(script, "{\"x\":1}");
        mapper.transform(script, "{\"x\":2}");
        mapper.transform(script, "{\"x\":3}");
        assertEquals(1, mapper.cachedScriptCount());
    }

    @Test
    void cache_distinctScriptsEachCached() {
        mapper.transform("payload.x", "{\"x\":1}");
        mapper.transform("payload.y", "{\"y\":2}");
        assertEquals(2, mapper.cachedScriptCount());
    }

    @Test
    void cache_isBounded_evictsBeyondCap() {
        DataSonnetMapper small = new DataSonnetMapper(2);
        small.transform("payload.a", "{\"a\":1}");
        small.transform("payload.b", "{\"b\":1}");
        small.transform("payload.c", "{\"c\":1}");
        assertEquals(2, small.cachedScriptCount()); // capped at 2, eldest evicted
    }

    @Test
    void cache_failedCompileIsNotCached() {
        assertThrows(DataSonnetMappingException.class,
            () -> mapper.transform("{ broken", "{}"));
        assertEquals(0, mapper.cachedScriptCount());
    }

    @Test
    void constructor_rejectsNonPositiveCap() {
        assertThrows(IllegalArgumentException.class, () -> new DataSonnetMapper(0));
    }

    // --- concurrency (eng-review decision D4) -------------------------------

    @Test
    void transform_isThreadSafeUnderConcurrentLoad() throws Exception {
        String script = "{ doubled: payload.n * 2 }";
        int threads = 16;
        int callsPerThread = 100;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Callable<Boolean>> tasks = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                tasks.add(() -> {
                    for (int i = 0; i < callsPerThread; i++) {
                        String out = compact(mapper.transform(script, "{\"n\":21}"));
                        if (!"{\"doubled\":42}".equals(out)) {
                            return false;
                        }
                    }
                    return true;
                });
            }
            for (Future<Boolean> f : pool.invokeAll(tasks)) {
                assertTrue(f.get(), "a concurrent transform produced a wrong result");
            }
            assertEquals(1, mapper.cachedScriptCount()); // compiled exactly once
        } finally {
            pool.shutdownNow();
        }
    }
}
