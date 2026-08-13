// ============================================================
// FILE: WikiDataLookup.java
// Looks up start dates for named structures via Wikidata
// SPARQL endpoint and Wikipedia API.
// Results are cached in memory and on disk.
// Hierarchy: Wikidata SPARQL → Wikipedia infobox → null
// ============================================================
package net.buildtheearth.terraplusplus.generator.historical;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public class WikiDataLookup {

    private static final Logger LOGGER =
        LogManager.getLogger("Terra++WikiData");

    // ── CONSTANTS ─────────────────────────────────────────────
    private static final String WIKIDATA_SPARQL =
        "https://query.wikidata.org/sparql";
    private static final String WIKIPEDIA_API =
        "https://en.wikipedia.org/w/api.php";
    private static final int  HTTP_TIMEOUT_MS  = 8000;
    private static final int  MEM_CACHE_MAX    = 4096;
    private static final long DISK_TTL_MS      =
        90L * 24 * 60 * 60 * 1000;
    // Max radius in degrees for Wikidata geo search
    private static final double GEO_RADIUS_DEG = 0.05;
    // Minimum name length to attempt lookup
    private static final int    MIN_NAME_LEN   = 4;
    // How many consecutive failures before pausing
    private static final int    MAX_FAILURES   = 5;
    private static final long   PAUSE_MS       = 60_000L;

    // ── RESULT ────────────────────────────────────────────────

    public static class WikiResult {
        public final int    year;
        public final String source; // "wikidata" or "wikipedia"
        public final String label;

        public WikiResult(int year, String source,
                String label) {
            this.year   = year;
            this.source = source;
            this.label  = label;
        }

        public static final WikiResult NOT_FOUND =
            new WikiResult(
                HistoricalDateParser.UNKNOWN_YEAR,
                "none", null);

        public boolean found() {
            return year !=
                HistoricalDateParser.UNKNOWN_YEAR;
        }
    }

    // ── FIELDS ────────────────────────────────────────────────

    // Key: normalized name → WikiResult
    private final ConcurrentHashMap<String, WikiResult>
        memCache = new ConcurrentHashMap<>();

    // Key: normalized name → "pending" sentinel
    private final ConcurrentHashMap<String,
        CompletableFuture<WikiResult>> pending =
        new ConcurrentHashMap<>();

    private final ExecutorService pool;
    private final Path    diskDir;
    private final boolean diskOk;

    private final AtomicInteger totalLookups =
        new AtomicInteger();
    private final AtomicInteger cacheHits    =
        new AtomicInteger();
    private final AtomicInteger wikiHits     =
        new AtomicInteger();
    private final AtomicInteger failures     =
        new AtomicInteger();

    private volatile int  consecutiveFailures = 0;
    private volatile long pauseUntilMs        = 0;
    private volatile boolean enabled          = true;

    // Singleton
    private static volatile WikiDataLookup INSTANCE;

    public static WikiDataLookup getInstance() {
        if (INSTANCE == null)
            synchronized (WikiDataLookup.class) {
                if (INSTANCE == null)
                    INSTANCE = new WikiDataLookup();
            }
        return INSTANCE;
    }

    private WikiDataLookup() {
        this.pool = Executors.newFixedThreadPool(2,
            r -> {
                Thread t = new Thread(r,
                    "Terra++WikiData");
                t.setDaemon(true);
                return t;
            });

        Path dir = Paths.get(
            "terra_cache", "wikidata");
        boolean ok = false;
        try {
            Files.createDirectories(dir);
            ok = true;
        } catch (Exception e) {
            LOGGER.warn("WikiData cache dir: {}",
                e.getMessage());
        }
        this.diskDir = dir;
        this.diskOk  = ok;

        LOGGER.info("WikiDataLookup init");
    }

    // ── PUBLIC API ────────────────────────────────────────────

    /**
     * Look up start year for a named structure.
     *
     * @param name  Structure name (from OsmStructure.name)
     * @param lat   Centre latitude
     * @param lon   Centre longitude
     * @return WikiResult — check result.found() before use
     */
    public WikiResult lookup(String name,
            double lat, double lon) {
        if (!enabled) return WikiResult.NOT_FOUND;
        if (name == null
                || name.length() < MIN_NAME_LEN)
            return WikiResult.NOT_FOUND;
        if (System.currentTimeMillis() < pauseUntilMs)
            return WikiResult.NOT_FOUND;

        String key = normalizeKey(name);
        totalLookups.incrementAndGet();

        // Memory cache
        WikiResult cached = memCache.get(key);
        if (cached != null) {
            cacheHits.incrementAndGet();
            return cached;
        }

        // Disk cache
        if (diskOk) {
            WikiResult disk = loadDisk(key);
            if (disk != null) {
                memCache.put(key, disk);
                cacheHits.incrementAndGet();
                return disk;
            }
        }

        // Network lookup — blocking with timeout
        try {
            CompletableFuture<WikiResult> future =
                pending.computeIfAbsent(key,
                    k -> CompletableFuture
                        .supplyAsync(
                            () -> doLookup(
                                k, name, lat, lon),
                            pool));
            return future.get(
                HTTP_TIMEOUT_MS * 2L,
                TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            pending.remove(key);
            return WikiResult.NOT_FOUND;
        }
    }

    /** Return only already-cached data; never starts or waits for network work. */
    public WikiResult lookupCached(String name) {
        if (name == null || name.length() < MIN_NAME_LEN) return WikiResult.NOT_FOUND;
        String key = normalizeKey(name);
        WikiResult cached = memCache.get(key);
        if (cached != null) return cached;
        if (diskOk) {
            WikiResult disk = loadDisk(key);
            if (disk != null) {
                memCache.put(key, disk);
                return disk;
            }
        }
        return WikiResult.NOT_FOUND;
    }

    /**
     * Non-blocking prefetch — fires and forgets.
     * Result will be in cache when lookup() is called.
     */
    public void prefetch(String name,
            double lat, double lon) {
        if (!enabled) return;
        if (name == null
                || name.length() < MIN_NAME_LEN)
            return;
        String key = normalizeKey(name);
        if (memCache.containsKey(key)) return;
        if (pending.containsKey(key)) return;
        pending.computeIfAbsent(key,
            k -> CompletableFuture.supplyAsync(
                () -> doLookup(k, name, lat, lon),
                pool));
    }

    // ── LOOKUP PIPELINE ───────────────────────────────────────

    private WikiResult doLookup(String key,
            String name, double lat, double lon) {
        try {
            // Step 1: Wikidata SPARQL by name + geo
            WikiResult wd =
                queryWikidata(name, lat, lon);
            if (wd.found()) {
                store(key, wd);
                wikiHits.incrementAndGet();
                consecutiveFailures = 0;
                LOGGER.debug(
                    "WikiData hit: '{}' → {}",
                    name, wd.year);
                return wd;
            }

            // Step 2: Wikipedia infobox by name
            WikiResult wp = queryWikipedia(name);
            if (wp.found()) {
                store(key, wp);
                wikiHits.incrementAndGet();
                consecutiveFailures = 0;
                LOGGER.debug(
                    "Wikipedia hit: '{}' → {}",
                    name, wp.year);
                return wp;
            }

            // Cache negative result so we don't
            // re-query the same name repeatedly
            store(key, WikiResult.NOT_FOUND);
            return WikiResult.NOT_FOUND;

        } catch (Exception e) {
            consecutiveFailures++;
            failures.incrementAndGet();
            LOGGER.debug("WikiData lookup '{}': {}",
                name, e.getMessage());
            if (consecutiveFailures >= MAX_FAILURES) {
                pauseUntilMs =
                    System.currentTimeMillis()
                    + PAUSE_MS;
                consecutiveFailures = 0;
                LOGGER.warn(
                    "WikiData pausing {}s",
                    PAUSE_MS / 1000);
            }
            pending.remove(key);
            return WikiResult.NOT_FOUND;
        } finally {
            pending.remove(key);
        }
    }

    // ── WIKIDATA SPARQL ───────────────────────────────────────

    private WikiResult queryWikidata(String name,
            double lat, double lon) {
        // SPARQL: find entity near lat/lon with
        // matching label, return inception date
        String sparql = "SELECT ?item ?label "
            + "?inception WHERE { "
            + "  SERVICE wikibase:around { "
            + "    ?item wdt:P625 ?coord . "
            + "    bd:serviceParam "
            + "      wikibase:center "
            + "        \"Point("
            + String.format("%.6f %.6f", lon, lat)
            + ")\"^^geo:wktLiteral . "
            + "    bd:serviceParam "
            + "      wikibase:radius \""
            + String.format("%.1f",
                GEO_RADIUS_DEG * 111.0)
            + "\" . "
            + "  } "
            + "  ?item rdfs:label ?label . "
            + "  FILTER(LANG(?label) = \"en\") "
            + "  OPTIONAL { "
            + "    ?item wdt:P571 ?inception . "
            + "  } "
            + "  FILTER(CONTAINS(LCASE(?label), \""
            + escapeSparql(name.toLowerCase())
            + "\")) "
            + "} LIMIT 5";

        HttpURLConnection conn = null;
        try {
            String url = WIKIDATA_SPARQL
                + "?format=json&query="
                + URLEncoder.encode(sparql,
                    StandardCharsets.UTF_8);
            conn = openGet(url);
            conn.setRequestProperty("Accept",
                "application/sparql-results+json");

            if (conn.getResponseCode() != 200)
                return WikiResult.NOT_FOUND;

            JsonObject root = parseJson(conn);
            JsonArray bindings = root
                .getAsJsonObject("results")
                .getAsJsonArray("bindings");

            for (JsonElement el : bindings) {
                JsonObject b = el.getAsJsonObject();
                if (!b.has("inception"))
                    continue;
                String dateStr = b
                    .getAsJsonObject("inception")
                    .get("value").getAsString();
                String label = b.has("label")
                    ? b.getAsJsonObject("label")
                        .get("value").getAsString()
                    : name;
                int year = parseIsoDate(dateStr);
                if (year !=
                        HistoricalDateParser
                            .UNKNOWN_YEAR)
                    return new WikiResult(
                        year, "wikidata", label);
            }
        } catch (Exception e) {
            LOGGER.debug("SPARQL error: {}",
                e.getMessage());
        } finally {
            if (conn != null) conn.disconnect();
        }
        return WikiResult.NOT_FOUND;
    }

    // ── WIKIPEDIA INFOBOX ─────────────────────────────────────

    private WikiResult queryWikipedia(String name) {
        HttpURLConnection conn = null;
        try {
            // Search for page by name
            String searchUrl = WIKIPEDIA_API
                + "?action=query&list=search"
                + "&srsearch="
                + URLEncoder.encode(name,
                    StandardCharsets.UTF_8)
                + "&format=json&utf8=1"
                + "&srlimit=1";
            conn = openGet(searchUrl);
            if (conn.getResponseCode() != 200)
                return WikiResult.NOT_FOUND;

            JsonObject root = parseJson(conn);
            JsonArray hits = root
                .getAsJsonObject("query")
                .getAsJsonArray("search");
            if (hits.isEmpty())
                return WikiResult.NOT_FOUND;

            String pageTitle = hits.get(0)
                .getAsJsonObject()
                .get("title").getAsString();
            conn.disconnect();

            // Fetch page extract
            String extractUrl = WIKIPEDIA_API
                + "?action=query&prop=revisions"
                + "&rvprop=content&rvslots=main"
                + "&titles="
                + URLEncoder.encode(pageTitle,
                    StandardCharsets.UTF_8)
                + "&format=json&utf8=1"
                + "&rvsection=0";
            conn = openGet(extractUrl);
            if (conn.getResponseCode() != 200)
                return WikiResult.NOT_FOUND;

            JsonObject root2 = parseJson(conn);
            String wikitext = extractWikitext(root2);
            if (wikitext == null)
                return WikiResult.NOT_FOUND;

            int year = parseInfoboxYear(wikitext);
            if (year !=
                    HistoricalDateParser.UNKNOWN_YEAR)
                return new WikiResult(
                    year, "wikipedia", pageTitle);

        } catch (Exception e) {
            LOGGER.debug("Wikipedia error: {}",
                e.getMessage());
        } finally {
            if (conn != null) conn.disconnect();
        }
        return WikiResult.NOT_FOUND;
    }

    // ── WIKITEXT YEAR EXTRACTION ──────────────────────────────

    private static int parseInfoboxYear(
            String wikitext) {
        // Look for common infobox date fields
        String[] fields = {
            "built", "opened", "founded",
            "established", "constructed",
            "completed", "inception",
            "year_built", "start_date",
            "completion_date", "inauguration",
            "dedicated", "built_on",
            "creation_date"
        };
        String lower = wikitext.toLowerCase();
        for (String field : fields) {
            int idx = lower.indexOf(field);
            if (idx < 0) continue;
            // Take the next 40 chars after the field
            String after = wikitext.substring(
                Math.min(idx + field.length(),
                    wikitext.length()),
                Math.min(idx + field.length() + 40,
                    wikitext.length()));
            int year = extractYearFromString(after);
            if (year !=
                    HistoricalDateParser.UNKNOWN_YEAR)
                return year;
        }
        return HistoricalDateParser.UNKNOWN_YEAR;
    }

    private static int extractYearFromString(
            String s) {
        // Find first 4-digit number in range
        java.util.regex.Matcher m =
            java.util.regex.Pattern
                .compile("(-?\\d{3,4})")
                .matcher(s);
        while (m.find()) {
            try {
                int y = Integer.parseInt(m.group(1));
                if (y >= -3000 && y <= 2024)
                    return y;
            } catch (NumberFormatException ignored) {}
        }
        return HistoricalDateParser.UNKNOWN_YEAR;
    }

    // ── ISO DATE PARSE ────────────────────────────────────────

    private static int parseIsoDate(String iso) {
        // Wikidata returns dates like:
        // "+1823-00-00T00:00:00Z"
        // "-0500-00-00T00:00:00Z"
        if (iso == null) return
            HistoricalDateParser.UNKNOWN_YEAR;
        try {
            String s = iso.trim();
            boolean neg = s.startsWith("-");
            if (neg) s = s.substring(1);
            else if (s.startsWith("+"))
                s = s.substring(1);
            String yearStr = s.split("-")[0];
            int year = Integer.parseInt(yearStr);
            if (neg) year = -year;
            if (year >= -10000 && year <= 9999)
                return year;
        } catch (Exception ignored) {}
        return HistoricalDateParser.UNKNOWN_YEAR;
    }

    // ── DISK CACHE ────────────────────────────────────────────

    private void store(String key, WikiResult r) {
        memCache.put(key, r);
        evictIfNeeded();
        if (diskOk) saveDisk(key, r);
    }

    private void saveDisk(String key, WikiResult r) {
        try {
            String content = r.year + "\t"
                + (r.source != null
                    ? r.source : "none")
                + "\t"
                + (r.label != null
                    ? r.label : "");
            Files.writeString(
                diskPath(key), content,
                StandardCharsets.UTF_8);
        } catch (Exception ignored) {}
    }

    private WikiResult loadDisk(String key) {
        try {
            Path p = diskPath(key);
            if (!Files.exists(p)) return null;
            long age = System.currentTimeMillis()
                - Files.getLastModifiedTime(p)
                    .toMillis();
            if (age > DISK_TTL_MS) {
                Files.deleteIfExists(p);
                return null;
            }
            String content = Files.readString(p,
                StandardCharsets.UTF_8).trim();
            String[] parts = content.split("\t", 3);
            if (parts.length < 2) return null;
            int year = Integer.parseInt(parts[0]);
            String source = parts[1];
            String label  = parts.length > 2
                ? parts[2] : null;
            return new WikiResult(year, source,
                label);
        } catch (Exception e) {
            return null;
        }
    }

    private Path diskPath(String key) {
        // Use first 2 chars as subdir to avoid
        // too many files in one directory
        String safe = key.replaceAll(
            "[^a-z0-9_]", "_");
        if (safe.length() > 64)
            safe = safe.substring(0, 64);
        return diskDir.resolve(safe + ".txt");
    }

    // ── HELPERS ───────────────────────────────────────────────

    private HttpURLConnection openGet(String url)
            throws Exception {
        HttpURLConnection c = (HttpURLConnection)
            new URL(url).openConnection();
        c.setRequestMethod("GET");
        c.setConnectTimeout(HTTP_TIMEOUT_MS);
        c.setReadTimeout(HTTP_TIMEOUT_MS);
        c.setRequestProperty("User-Agent",
            "TerraPlusPlus/2.0 Minecraft");
        return c;
    }

    private JsonObject parseJson(
            HttpURLConnection conn) throws Exception {
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(
                    conn.getInputStream(),
                    StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            char[] buf = new char[4096];
            int n;
            while ((n = br.read(buf)) != -1)
                sb.append(buf, 0, n);
            return JsonParser.parseString(
                sb.toString()).getAsJsonObject();
        }
    }

    private String extractWikitext(JsonObject root) {
        try {
            JsonObject pages = root
                .getAsJsonObject("query")
                .getAsJsonObject("pages");
            for (String pageId : pages.keySet()) {
                JsonObject page =
                    pages.getAsJsonObject(pageId);
                if (!page.has("revisions"))
                    continue;
                JsonArray revs =
                    page.getAsJsonArray("revisions");
                if (revs.isEmpty()) continue;
                JsonObject slots = revs.get(0)
                    .getAsJsonObject()
                    .getAsJsonObject("slots");
                if (slots == null
                        || !slots.has("main"))
                    continue;
                return slots
                    .getAsJsonObject("main")
                    .get("*").getAsString();
            }
        } catch (Exception ignored) {}
        return null;
    }

    private static String normalizeKey(String name) {
        return name.toLowerCase().trim()
            .replaceAll("\\s+", "_")
            .replaceAll("[^a-z0-9_]", "");
    }

    private static String escapeSparql(String s) {
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"");
    }

    private void evictIfNeeded() {
        if (memCache.size() <= MEM_CACHE_MAX) return;
        // Evict NOT_FOUND entries first
        memCache.entrySet().removeIf(
            e -> !e.getValue().found());
        if (memCache.size() <= MEM_CACHE_MAX) return;
        // Evict oldest entries (approximate)
        int toRemove = memCache.size()
            - MEM_CACHE_MAX + MEM_CACHE_MAX / 4;
        var iter = memCache.entrySet().iterator();
        while (iter.hasNext() && toRemove-- > 0) {
            iter.next(); iter.remove();
        }
    }

    // ── STATS ─────────────────────────────────────────────────

    public int getTotalLookups() {
        return totalLookups.get(); }
    public int getCacheHits()    {
        return cacheHits.get(); }
    public int getWikiHits()     {
        return wikiHits.get(); }
    public int getFailures()     {
        return failures.get(); }
    public boolean isEnabled()   { return enabled; }
    public void setEnabled(boolean e) {
        this.enabled = e;
        if (e) {
            consecutiveFailures = 0;
            pauseUntilMs = 0;
        }
    }

    public void shutdown() {
        pool.shutdown();
        try {
            if (!pool.awaitTermination(
                    3, TimeUnit.SECONDS))
                pool.shutdownNow();
        } catch (InterruptedException e) {
            pool.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}