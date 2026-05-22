package org.cibseven.community.connector.rest;

import com.datasonnet.Mapper;
import com.datasonnet.document.DefaultDocument;
import com.datasonnet.document.Document;
import com.datasonnet.document.MediaTypes;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Runs DataSonnet scripts, caching the compiled {@link Mapper} for each script.
 *
 * <p>DataSonnet compilation is not free, and the connector runs the same inline
 * scripts on every process execution. This helper compiles each distinct script
 * once and reuses it.
 *
 * <p><b>Concurrency.</b> The connector is a single shared instance hit by many
 * job-executor threads, so this helper is too. The compiled-{@code Mapper} cache
 * is a bounded LRU map guarded by its own monitor; a compiled {@code Mapper} is
 * reused across threads (DataSonnet compiles once, transforms many).
 * {@code DataSonnetMapperTest} pins that this is safe under concurrent load.
 *
 * <p><b>Bounded.</b> The cache is capped (LRU eviction past the cap), so a long
 * running engine with many deployed process versions cannot leak memory through
 * an ever-growing set of distinct inline scripts.
 *
 * <p><b>Scope.</b> This helper is String-in / String-out: DataSonnet operates on
 * JSON text. Coercing an engine value (a Spin {@code SpinJsonNode}, etc.) into a
 * JSON String is a boundary concern handled by the connector, not here, so this
 * helper carries no Connect SPI or Spin dependency and is unit-tested in
 * isolation.
 *
 * <pre>
 *   transform(script, payloadJson)
 *     |
 *     +-- getOrCompile(script) --- cache hit ---> reuse the Mapper
 *     |                       \--- cache miss --> new Mapper(script), cache it
 *     |                                           (compile failure -> throw)
 *     +-- mapper.transform(payloadJson)
 *                                           (eval failure -> throw)
 * </pre>
 */
public final class DataSonnetMapper {

    /** Default cap on the number of distinct compiled scripts held in memory. */
    public static final int DEFAULT_MAX_CACHED_SCRIPTS = 256;

    /** LRU cache of compiled Mappers, keyed on raw script text. Guarded by its own monitor. */
    private final Map<String, Mapper> cache;

    public DataSonnetMapper() {
        this(DEFAULT_MAX_CACHED_SCRIPTS);
    }

    public DataSonnetMapper(int maxCachedScripts) {
        if (maxCachedScripts < 1) {
            throw new IllegalArgumentException(
                "maxCachedScripts must be >= 1, got " + maxCachedScripts);
        }
        this.cache = new LruMapperCache(maxCachedScripts);
    }

    /**
     * Transforms {@code payloadJson} with the given DataSonnet {@code script}.
     * The script sees the payload as the {@code payload} input variable.
     *
     * @param script      the DataSonnet script (raw text, including any version header)
     * @param payloadJson the input, as a JSON string
     * @return the transformed result, as a JSON string
     * @throws DataSonnetMappingException if the script fails to compile or evaluate
     */
    public String transform(String script, String payloadJson) {
        if (script == null) {
            throw new DataSonnetMappingException("DataSonnet script is null", null);
        }
        if (payloadJson == null) {
            throw new DataSonnetMappingException("DataSonnet payload is null", null);
        }
        Mapper mapper = getOrCompile(script);
        try {
            // Tag the input as JSON so the script sees `payload` as a parsed
            // value, not a raw string; ask for JSON back.
            Document<?> result = mapper.transform(
                new DefaultDocument<>(payloadJson, MediaTypes.APPLICATION_JSON),
                Collections.emptyMap(),
                MediaTypes.APPLICATION_JSON);
            return String.valueOf(result.getContent());
        } catch (RuntimeException e) {
            throw new DataSonnetMappingException(
                "DataSonnet script failed to evaluate: " + e.getMessage(), e);
        }
    }

    /** Number of distinct compiled scripts currently cached. Intended for tests. */
    public int cachedScriptCount() {
        synchronized (cache) {
            return cache.size();
        }
    }

    /**
     * Returns the compiled Mapper for the script, compiling and caching it on a
     * miss. Compilation happens under the cache monitor: it is a cold path (each
     * distinct script compiles once), and serializing it means a script used by
     * two threads at once compiles exactly once. A failed compile is not cached.
     */
    private Mapper getOrCompile(String script) {
        synchronized (cache) {
            Mapper mapper = cache.get(script);
            if (mapper == null) {
                mapper = compile(script);
                cache.put(script, mapper);
            }
            return mapper;
        }
    }

    private Mapper compile(String script) {
        try {
            return new Mapper(script);
        } catch (RuntimeException e) {
            throw new DataSonnetMappingException(
                "DataSonnet script failed to compile: " + e.getMessage(), e);
        }
    }

    /** A size-bounded, access-order (LRU) map of compiled Mappers. */
    private static final class LruMapperCache extends LinkedHashMap<String, Mapper> {

        private static final long serialVersionUID = 1L;

        private final int maxEntries;

        LruMapperCache(int maxEntries) {
            super(16, 0.75f, true); // accessOrder = true -> LRU
            this.maxEntries = maxEntries;
        }

        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Mapper> eldest) {
            return size() > maxEntries;
        }
    }
}
