package net.buildtheearth.terraplusplus.generator.osm;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.*;

import org.wololo.flatgeobuf.generated.Feature;
import org.wololo.flatgeobuf.generated.Header;
import org.wololo.flatgeobuf.generated.Geometry;

public class SourceCooperativeIndex {
    private static final org.apache.logging.log4j.Logger LOGGER = org.apache.logging.log4j.LogManager.getLogger("Terra++VIDA");
    private static final String VIDA_BASE_URL = "https://data.source.coop/vida/google-microsoft-osm-open-buildings/flatgeobuf/by_country_s2/country_iso=";
    private static final int HTTP_TIMEOUT_MS = 30000;

    // ---- guards for the cold-start HTTP-range path ----
    /** Stop expanding the R-tree frontier past this; fall back to full download. */
    private static final int  MAX_RANGE_NODES       = 200_000;
    /** Largest single FlatGeobuf feature we expect, used to pad the tail read. */
    private static final int  MAX_FEATURE_BYTES     = 8192;
    /** If matching features span more than this, a full download is cheaper. */
    private static final long MAX_RANGE_SPAN_BYTES  = 192L * 1024 * 1024;
    /**
     * Largest contiguous R-tree span fetched per level in one request. Hilbert
     * ordering keeps the matching frontier tight (2.6 MB at the widest level of
     * the Hyderabad shard); anything beyond this means the query is degenerate
     * and the full download is the better option.
     */
    private static final long MAX_LEVEL_SPAN_BYTES = 24L * 1024 * 1024;

    public static class VidaBuilding {
        public final OsmStructureIndex.OsmNode[] nodes;
        public final double centerLat, centerLon, area;
        public final long uniqueId;
        /** Bounding box, computed after the inset is applied. Used for fast overlap rejection. */
        public final double minLat, maxLat, minLon, maxLon;

        public VidaBuilding(OsmStructureIndex.OsmNode[] nodes, double area) {
            this(nodes, area, false);
        }

        /**
         * @param alreadyInset when true the 0.5 m inset is skipped. Used by
         *        {@link #shifted} so translating a building does not shrink it
         *        a second time.
         */
        public VidaBuilding(OsmStructureIndex.OsmNode[] nodes, double area,
                            boolean alreadyInset) {
            this.nodes = nodes; this.area = area;
            double sumLat = 0, sumLon = 0;
            for (OsmStructureIndex.OsmNode n : nodes) { sumLat += n.lat; sumLon += n.lon; }
            this.centerLat = sumLat / nodes.length;
            this.centerLon = sumLon / nodes.length;
            this.uniqueId = (Double.doubleToLongBits(centerLat) ^ Double.doubleToLongBits(centerLon)) & 0x7FFFFFFFFFFFFFFFL;

            // 0.5m Inset (Gap)
            if (!alreadyInset) {
                double insetDeg = 0.5 / 111320.0;
                for (int i = 0; i < this.nodes.length; i++) {
                    double dLat = this.nodes[i].lat - centerLat;
                    double dLon = this.nodes[i].lon - centerLon;
                    double dist = Math.sqrt(dLat * dLat + dLon * dLon);
                    if (dist > 1e-9) {
                        double scale = Math.max(0.1, (dist - insetDeg) / dist);
                        this.nodes[i] = new OsmStructureIndex.OsmNode(centerLat + dLat * scale, centerLon + dLon * scale);
                    }
                }
            }

            // Bounding box from the final (inset) nodes.
            double mnLat = Double.MAX_VALUE, mxLat = -Double.MAX_VALUE;
            double mnLon = Double.MAX_VALUE, mxLon = -Double.MAX_VALUE;
            for (OsmStructureIndex.OsmNode n : this.nodes) {
                if (n.lat < mnLat) mnLat = n.lat;
                if (n.lat > mxLat) mxLat = n.lat;
                if (n.lon < mnLon) mnLon = n.lon;
                if (n.lon > mxLon) mxLon = n.lon;
            }
            this.minLat = mnLat; this.maxLat = mxLat;
            this.minLon = mnLon; this.maxLon = mxLon;
        }

        /**
         * Returns a copy translated by -(dLat,dLon), removing the measured
         * registration offset of this dataset relative to OSM.
         *
         * See {@link SourceAlignment} for why the shift is measured per region
         * at runtime rather than hardcoded.
         */
        public VidaBuilding shifted(double dLat, double dLon) {
            if (dLat == 0.0 && dLon == 0.0) return this;
            OsmStructureIndex.OsmNode[] moved =
                    new OsmStructureIndex.OsmNode[nodes.length];
            for (int i = 0; i < nodes.length; i++)
                moved[i] = new OsmStructureIndex.OsmNode(
                        nodes[i].lat - dLat, nodes[i].lon - dLon);
            // The constructor re-derives centroid and bbox, and would re-apply
            // the 0.5 m inset, so bypass it via the copy constructor below.
            return new VidaBuilding(moved, area, true);
        }

        /** True when (lat,lon) lies inside this building's footprint. */
        public boolean containsPoint(double lat, double lon) {
            if (lat < minLat || lat > maxLat || lon < minLon || lon > maxLon) return false;
            boolean inside = false;
            for (int i = 0, j = nodes.length - 1; i < nodes.length; j = i++) {
                if (((nodes[i].lat > lat) != (nodes[j].lat > lat))
                        && (lon < (nodes[j].lon - nodes[i].lon) * (lat - nodes[i].lat)
                            / (nodes[j].lat - nodes[i].lat) + nodes[i].lon))
                    inside = !inside;
            }
            return inside;
        }

        /**
         * Convert to the common OsmStructure form.
         *
         * IMPORTANT CONTRACT: this must behave exactly like the OSM and
         * Microsoft converters -- pass what the dataset actually knows, and
         * leave everything else UNSET so the single shared estimator in
         * OsmStructureGenerator decides it.
         *
         * This previously invented its own floor count and roof shape:
         *
         *     floors = round(((area/65) + rand*1.5) / 4)   // capped at 2 below 300 m^2
         *     roof   = area<220 ? pyramidal : area<550 ? gabled : flat
         *
         * Measured against the 6,041 real Google footprints in the Hyderabad
         * test box, that formula disagreed with the canonical
         * OsmStructureGenerator.estimateLevels() on 83% of buildings and
         * produced a completely different cityscape:
         *
         *     floors   VIDA formula: 99.1% single-storey  (1:5986 2:44 3:8 ...)
         *              canonical   : 1:1026 2:3043 3:1900 4:70 5:2
         *     roof     VIDA formula: 96.7% pyramidal      (pyr:5841 gab:185 flat:15)
         *              canonical   : flat:4028 gabled:2013
         *
         * So Google buildings rendered as a field of single-storey pyramid huts
         * while OSM and Microsoft buildings next to them got 2-3 flat-roofed
         * storeys. Passing 0/null instead routes VIDA through exactly the same
         * estimateLevels() / resolveRoofType() path the other two sources use,
         * which is what makes the three datasets visually consistent.
         *
         * Height is likewise left at 0: the generator derives wall height from
         * levels * FLOOR_HEIGHT (5 blocks), and the old floors*3.5 disagreed
         * with that constant too.
         */
        public OsmStructureIndex.OsmStructure toOsmStructure() {
            return new OsmStructureIndex.OsmStructure(
                OsmStructureIndex.StructureType.BUILDING, "yes", null, nodes, true, uniqueId,
                null,      // material  - unknown, generator picks by subtype/hash
                0,         // levels    - unknown, generator uses estimateLevels()
                0,         // height    - unknown, generator uses levels*FLOOR_HEIGHT
                null,      // roofShape - unknown, generator uses resolveRoofType()
                null, null, 0, false);
        }
    }

    /**
     * True when a VIDA building overlaps any already-accepted structure.
     *
     * VIDA sits below OSM in the source hierarchy, so it is gap-fill only: anything
     * that collides with an existing OSM/OHM footprint is dropped. Mirrors the
     * geometric test used for Microsoft footprints (centroid-in-polygon both ways)
     * rather than an ID match, because VIDA carries no OSM IDs.
     */
    public static boolean overlapsExisting(VidaBuilding b,
                                           List<OsmStructureIndex.OsmStructure> existing) {
        if (b == null || existing == null || existing.isEmpty()) return false;

        FootprintAlignment.Print cand = FootprintAlignment.Print.of(b);

        for (OsmStructureIndex.OsmStructure s : existing) {
            if (s.type != OsmStructureIndex.StructureType.BUILDING
                    && s.type != OsmStructureIndex.StructureType.BUILDING_PART)
                continue;

            // Strict geometric containment first (cheap, and always correct).
            if (!(b.maxLat < s.minLat || b.minLat > s.maxLat
               || b.maxLon < s.minLon || b.minLon > s.maxLon)) {
                if (s.containsPoint(b.centerLat, b.centerLon)) return true;
                if (b.containsPoint((s.minLat + s.maxLat) / 2.0,
                                    (s.minLon + s.maxLon) / 2.0)) return true;
            }

            // Then near-miss: the ML footprint is the SAME building as this OSM
            // one but shifted a few metres. Without this the shifted copy is
            // placed beside the real building instead of being dropped.
            if (FootprintAlignment.isSameBuilding(cand, FootprintAlignment.Print.of(s)))
                return true;

            // Finally STRICT overlap: OSM is authoritative, so a Google
            // footprint that shares any area with an OSM building must go even
            // when it is a genuinely different building. Without this, ML
            // footprints clip through OSM walls -- measured 1,484 such cases in
            // the Hyderabad test box.
            if (s.isArea && s.nodes != null && s.nodes.length >= 3
                    && FootprintAlignment.footprintsIntersect(
                        b.nodes, b.minLat, b.maxLat, b.minLon, b.maxLon,
                        s.nodes, s.minLat, s.maxLat, s.minLon, s.maxLon))
                return true;
        }
        return false;
    }

    /** True when a Microsoft footprint overlaps an already-accepted VIDA building. */
    public static boolean overlapsVida(OvertureIndex.OvertureBuilding m,
                                       List<VidaBuilding> vida) {
        if (m == null || vida == null || vida.isEmpty()) return false;

        FootprintAlignment.Print cand = FootprintAlignment.Print.of(m);

        // Microsoft stores its ring as double[][] {lat,lon}; convert once so
        // the strict overlap test below can share the common node form.
        OsmStructureIndex.OsmNode[] mNodes =
                new OsmStructureIndex.OsmNode[m.polygon.length];
        for (int i = 0; i < m.polygon.length; i++)
            mNodes[i] = new OsmStructureIndex.OsmNode(
                    m.polygon[i][0], m.polygon[i][1]);

        for (VidaBuilding v : vida) {
            if (!(m.maxLat < v.minLat || m.minLat > v.maxLat
               || m.maxLon < v.minLon || m.minLon > v.maxLon)) {
                if (v.containsPoint(m.centerLat, m.centerLon)) return true;
                for (double[] pt : m.polygon)
                    if (v.containsPoint(pt[0], pt[1])) return true;
            }
            // Google and Microsoft disagree with each other too - both are ML
            // outputs with independent error, so the same building can appear
            // twice with no overlap at all.
            if (FootprintAlignment.isSameBuilding(cand, FootprintAlignment.Print.of(v)))
                return true;

            // STRICT overlap: Google outranks Microsoft, so a Microsoft
            // footprint sharing any area with an accepted Google one is
            // dropped even if it is a different building. 663 such cases were
            // measured clipping through Google walls in the test box.
            if (v.nodes != null && v.nodes.length >= 3
                    && FootprintAlignment.footprintsIntersect(
                        mNodes, m.minLat, m.maxLat, m.minLon, m.maxLon,
                        v.nodes, v.minLat, v.maxLat, v.minLon, v.maxLon))
                return true;
        }
        return false;
    }

    // ================================================================
    // SPATIAL GRID
    // ================================================================
    // A VIDA shard is a whole S2 cell (often an entire country region,
    // 0.5-3M buildings). Scanning that flat list on every chunk query is
    // O(shard) per chunk and was the source of a severe generation stall:
    // OSM tiles at 0.01deg and Overture at 0.005deg, so they only ever touch
    // a few hundred buildings per chunk, while VIDA touched millions.
    //
    // Buildings are therefore bucketed into a 0.01deg grid once at parse
    // time. A chunk query then visits only the handful of cells it overlaps.
    private static final double GRID_DEG = 0.01;

    /** Parsed shard: buildings bucketed by grid cell, plus a total count. */
    private static final class Shard {
        final ConcurrentHashMap<Long, List<VidaBuilding>> cells;
        final int count;
        /** Geographic window actually retained; outside this the shard has no data. */
        final double minLat, maxLat, minLon, maxLon;
        volatile long lastAccess;

        Shard(ConcurrentHashMap<Long, List<VidaBuilding>> cells, int count,
              double minLat, double maxLat, double minLon, double maxLon) {
            this.cells = cells;
            this.count = count;
            this.minLat = minLat; this.maxLat = maxLat;
            this.minLon = minLon; this.maxLon = maxLon;
            this.lastAccess = System.currentTimeMillis();
        }

        /** True when the query box lies fully inside the retained window. */
        boolean covers(double qMinLat, double qMaxLat, double qMinLon, double qMaxLon) {
            return qMinLat >= minLat && qMaxLat <= maxLat
                && qMinLon >= minLon && qMaxLon <= maxLon;
        }
    }

    private static long gridKey(double lat, double lon) {
        long gLat = (long) Math.floor(lat / GRID_DEG);
        long gLon = (long) Math.floor(lon / GRID_DEG);
        return (gLat << 32) ^ (gLon & 0xFFFFFFFFL);
    }

    /**
     * Max resident regions.
     *
     * Keyed per REGION, not per shard. Keying by shard meant a second teleport
     * inside the same country evicted the first city's buildings, so hopping
     * between two Indian cities re-parsed the shard every single time. With the
     * R-tree query each region costs only its own buildings (a few thousand),
     * so several can coexist cheaply.
     */
    private static final int MAX_SHARDS = 6;

    /** Rounds a coordinate to the retained-window grid so keys are stable. */
    private static String regionKey(String iso, String shardId,
                                    double lat, double lon) {
        long gLat = Math.round(Math.floor(lat / RETAIN_HALF_DEG));
        long gLon = Math.round(Math.floor(lon / RETAIN_HALF_DEG));
        return iso + "_" + shardId + "@" + gLat + "," + gLon;
    }

    /**
     * Half-size of the region retained around the first requested point, in degrees.
     *
     * A VIDA S2 shard covers a whole country region: the India shard is 2.25M
     * buildings, roughly 1 GB of heap once parsed into objects. Retaining all of
     * it to serve chunks within a few hundred metres of the player is what
     * exhausted a 4 GB heap. Buildings outside this window are discarded during
     * parsing and never allocated as objects at all.
     *
     * Sizing: at view distance 12 a player sees ~192 m. 0.02 deg is ~2.2 km
     * half-width (a 4.5 km box), which is still ~11x the visible radius.
     *
     * History, and why this is now 0.02 rather than 0.05:
     *
     * It was originally 0.35 deg (~78 km box). That looked safe against the old
     * 2.25M-feature shard, but once the shard lookup was corrected the dense
     * Hyderabad shard returned 2,856,906 buildings for that window - about 1 GB
     * of live objects, and the server fell 4,642 ticks behind. It was then cut
     * to 0.05 (11 km box).
     *
     * 0.05 is still far larger than needed, and it dominates cold-start cost.
     * Measured against the real Hyderabad shard, walking its R-tree over HTTP:
     *
     *     half-deg   box      leaf hits   tree      features    total
     *     0.05       11.1 km    314,920   13.63 MB  138.34 MB   151.98 MB
     *     0.02        4.5 km     49,582    2.21 MB   13.81 MB    16.02 MB
     *     0.01        2.2 km     15,880    0.73 MB   10.28 MB    11.01 MB
     *
     * Dropping to 0.02 cuts the cold-start payload roughly 9.5x while still
     * covering 4.5 km around the player. Combined with the ranged fetch this is
     * 16 MB instead of 1,217 MB before the first building appears.
     *
     * Do not raise this without re-measuring: cost grows with the SQUARE of the
     * window, and in a dense city that is millions of live objects.
     */
    // Match the async structure tile scale closely. A 0.02-degree retention
    // window turned a single 1 km request into 40–65k retained buildings in
    // dense cities; 0.01 still gives a safety margin around the request while
    // greatly reducing cold-start network, parsing, and memory pressure.
    private static final double RETAIN_HALF_DEG = 0.01;

    private final ConcurrentHashMap<String, Shard> shardCache = new ConcurrentHashMap<>();
    // A remote/range failure is retryable.  Never turn it into a permanent
    // "no buildings" result, otherwise cold-start outages erase VIDA gaps.
    // Give OSM/OHM and the immediate terrain path time to settle before a
    // potentially large Google/VIDA cold fetch starts.
    private static final long COLD_START_DELAY_MS = 5_000L;
    private static final long RETRY_COOLDOWN_MS = 60_000L;
    private final ConcurrentHashMap<String, Long> retryAfterMs = new ConcurrentHashMap<>();
    private final Set<String> pendingShards = Collections.newSetFromMap(new ConcurrentHashMap<>());
    // A ranged VIDA request can materialize tens of thousands of features.
    // Serialise cold shard work so one player-area request cannot double the
    // memory and bandwidth spike.
    private final ExecutorService dlPool = Executors.newFixedThreadPool(1, r -> {
        Thread t = new Thread(r, "Terra++VIDA"); t.setDaemon(true); return t;
    });
    private final Path cacheDir = Paths.get("terra_cache", "vida_buildings");

    // ================================================================
    // SINGLETON
    // ================================================================
    // A parsed shard is ~1 GB of heap (2.2M buildings for India). Three
    // call sites used to each construct their own index, so the same shard
    // was parsed and retained up to 3x and blew a 4 GB heap. There must be
    // exactly one cache process-wide.
    private static volatile SourceCooperativeIndex INSTANCE;

    public static SourceCooperativeIndex getInstance() {
        SourceCooperativeIndex local = INSTANCE;
        if (local == null) {
            synchronized (SourceCooperativeIndex.class) {
                local = INSTANCE;
                if (local == null) {
                    local = new SourceCooperativeIndex();
                    INSTANCE = local;
                }
            }
        }
        return local;
    }

    private SourceCooperativeIndex() { try { Files.createDirectories(cacheDir); } catch (IOException ignored) {} }

    public List<VidaBuilding> getBuildingsForChunk(double minLat, double maxLat, double minLon, double maxLon) {
        String iso = getCountryISO(minLat, minLon); String shardId = getS2ShardId(minLat, minLon);
        if (iso == null || shardId == null) return List.of();
        double cLat = (minLat + maxLat) / 2.0, cLon = (minLon + maxLon) / 2.0;
        String key = regionKey(iso, shardId, cLat, cLon);
        Long retryAfter = retryAfterMs.get(key);
        if (retryAfter != null) {
            if (System.currentTimeMillis() < retryAfter) return null;
            retryAfterMs.remove(key, retryAfter);
        }
        Shard cached = shardCache.get(key);
        if (cached != null && cached.covers(minLat, maxLat, minLon, maxLon))
            return filterBuildings(cached, minLat, maxLat, minLon, maxLon);
        // Straddling a region boundary: any resident region may still cover it.
        if (cached == null) {
            for (Shard s : shardCache.values())
                if (s.covers(minLat, maxLat, minLon, maxLon))
                    return filterBuildings(s, minLat, maxLat, minLon, maxLon);
        }
        if (pendingShards.add(key))
            triggerShardFetch(iso, shardId, cLat, cLon, key);
        return null;
    }

    private void triggerShardFetch(String iso, String shardId,
                                   double centreLat, double centreLon,
                                   String key) {
        // Missing VIDA data is optional gap-fill work, never an immediate
        // terrain prerequisite. Delay cold work so OSM/OHM can satisfy dense
        // areas first, avoiding immediate contention with terrain and source
        // tile downloads before a large range fetch starts.
        CompletableFuture.runAsync(() -> {
            try { fetchShard(iso, shardId, centreLat, centreLon, key); }
            catch (Exception e) {
                LOGGER.warn("VIDA region {} failed; retrying after {}s: {}",
                        key, RETRY_COOLDOWN_MS / 1000, e.toString());
                retryAfterMs.put(key, System.currentTimeMillis() + RETRY_COOLDOWN_MS);
            }
            finally { pendingShards.remove(key); }
        }, CompletableFuture.delayedExecutor(COLD_START_DELAY_MS,
                TimeUnit.MILLISECONDS, dlPool));
    }

    private void fetchShard(String iso, String shardId,
                            double centreLat, double centreLon,
                            String key) throws Exception {
        // The .fgb download is shared per shard; only the parsed region is per-key.
        Path localFile = cacheDir.resolve(iso + "_" + shardId + ".fgb");

        if (!Files.exists(localFile)) {
            // ---- COLD START FAST PATH: fetch only the bytes we need ----
            // A VIDA shard is enormous (the Hyderabad one is 1.217 GB) but a
            // player needs a few km around themselves. FlatGeobuf stores a
            // packed Hilbert R-tree between the header and the features, and
            // data.source.coop serves "accept-ranges: bytes", so the tree can
            // be walked over HTTP and only the matching feature bytes pulled.
            //
            // Measured against the real Hyderabad shard, RETAIN_HALF_DEG=0.02:
            //     full download   1217.04 MB
            //     ranged fetch      16.02 MB   (2.21 MB tree + 13.81 MB features)
            // i.e. ~99% less data before the first building appears.
            //
            // This is best-effort. Any failure falls through to the whole-file
            // download below, so behaviour is never worse than before.
            List<VidaBuilding> ranged = fetchViaHttpRange(
                    iso, shardId, centreLat, centreLon);
            if (ranged != null) {
                storeRegion(key, ranged, centreLat, centreLon, iso, shardId);
                return;
            }

            URL url = new URL(VIDA_BASE_URL + iso + "/" + shardId + ".fgb");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(HTTP_TIMEOUT_MS);
            // Without a read timeout a stalled server pins a download thread
            // forever; the pool is only 2 threads wide.
            conn.setReadTimeout(HTTP_TIMEOUT_MS);
            // Download to a temp file and rename on success. Writing directly
            // to the final path means an interrupted download leaves a
            // truncated .fgb that Files.exists() then trusts forever.
            Path tmp = localFile.resolveSibling(localFile.getFileName() + ".tmp");
            try (InputStream in = conn.getInputStream(); OutputStream out = Files.newOutputStream(tmp)) {
                byte[] buf = new byte[16384]; int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            } catch (IOException e) {
                Files.deleteIfExists(tmp);
                throw e;
            }
            Files.move(tmp, localFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }

        // Region of interest: only buildings near the requesting chunk are kept.
        final double roiMinLat = centreLat - RETAIN_HALF_DEG;
        final double roiMaxLat = centreLat + RETAIN_HALF_DEG;
        final double roiMinLon = centreLon - RETAIN_HALF_DEG;
        final double roiMaxLon = centreLon + RETAIN_HALF_DEG;
        int scanned = 0;

        List<VidaBuilding> buildings = new ArrayList<>();

        // ---- FAST PATH: use the file's built-in packed Hilbert R-tree ----
        // A .fgb stores an R-tree between the header and the feature data.
        // Querying it touches ~5 tree levels plus only the matching leaves,
        // instead of decoding all 2.25M features. This is what makes a second
        // teleport cheap on a cold start.
        List<VidaBuilding> viaIndex = readViaRTree(localFile,
                roiMinLat, roiMaxLat, roiMinLon, roiMaxLon);
        if (viaIndex != null) {
            buildings = viaIndex;
            scanned = buildings.size();
        } else
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(localFile)))) {
            in.skipBytes(8);
            int headerSize = Integer.reverseBytes(in.readInt());
            byte[] headerBytes = new byte[headerSize]; in.readFully(headerBytes);
            Header header = Header.getRootAsHeader(ByteBuffer.wrap(headerBytes).order(ByteOrder.LITTLE_ENDIAN));
            long fCount = header.featuresCount();
            int iSize = header.indexNodeSize();
            if (iSize > 0) {
                long numNodes = fCount; long n = fCount;
                while (n > 1) { n = (n + iSize - 1) / iSize; numNodes += n; }
                in.skipBytes((int)(numNodes * 40L));
            }
            while (true) {
                try {
                    int fSize = Integer.reverseBytes(in.readInt());
                    if (fSize <= 0 || fSize > 1000000) break;
                    byte[] fBytes = new byte[fSize]; in.readFully(fBytes);
                    Feature f = Feature.getRootAsFeature(ByteBuffer.wrap(fBytes).order(ByteOrder.LITTLE_ENDIAN));
                    ByteBuffer pBuf = f.propertiesAsByteBuffer();
                    double area = 50.0;
                    if (pBuf != null) {
                        pBuf.order(ByteOrder.LITTLE_ENDIAN);
                        while (pBuf.hasRemaining()) {
                            int cIdx = pBuf.getShort() & 0xFFFF;
                            if (cIdx == 3) area = pBuf.getDouble(); else break;
                        }
                    }
                    Geometry g = f.geometry();
                    if (g != null && g.xyLength() >= 6) {
                        scanned++;
                        // Reject on the FIRST vertex, before allocating any
                        // OsmNode/VidaBuilding objects. A country shard holds
                        // millions of buildings; materialising them all is what
                        // exhausted the heap. Cheap coordinate test first.
                        double lat0 = g.xy(1), lon0 = g.xy(0);
                        if (lat0 < roiMinLat || lat0 > roiMaxLat
                                || lon0 < roiMinLon || lon0 > roiMaxLon) continue;

                        int ptCount = g.xyLength() / 2;
                        OsmStructureIndex.OsmNode[] nodes = new OsmStructureIndex.OsmNode[ptCount];
                        for (int i = 0; i < ptCount; i++) nodes[i] = new OsmStructureIndex.OsmNode(g.xy(i * 2 + 1), g.xy(i * 2));
                        buildings.add(new VidaBuilding(nodes, area));
                    }
                } catch (EOFException e) { break; }
            }
        }
        // Bucket into the spatial grid once, here, so per-chunk queries are
        // O(cells touched) instead of O(shard).
        ConcurrentHashMap<Long, List<VidaBuilding>> cells = new ConcurrentHashMap<>();
        for (VidaBuilding b : buildings) {
            cells.computeIfAbsent(gridKey(b.centerLat, b.centerLon),
                    k -> new ArrayList<>()).add(b);
        }
        shardCache.put(key, new Shard(cells, buildings.size(),
                roiMinLat, roiMaxLat, roiMinLon, roiMaxLon));
        evictShardsIfNeeded();
        LOGGER.info("VIDA shard {} parsed: {} of {} buildings retained "
                + "({} grid cells, region {}..{} lat, {}..{} lon)",
                key, buildings.size(), scanned, cells.size(),
                String.format("%.3f", roiMinLat), String.format("%.3f", roiMaxLat),
                String.format("%.3f", roiMinLon), String.format("%.3f", roiMaxLon));
    }

    /**
     * Buckets a parsed region into the spatial grid and publishes it.
     *
     * Extracted so the HTTP-range cold-start path and the whole-file path
     * store their results identically -- the grid layout, the Shard region
     * bounds and the eviction call must not drift apart between the two.
     */
    private void storeRegion(String key, List<VidaBuilding> buildings,
                             double centreLat, double centreLon,
                             String iso, String shardId) {
        final double roiMinLat = centreLat - RETAIN_HALF_DEG;
        final double roiMaxLat = centreLat + RETAIN_HALF_DEG;
        final double roiMinLon = centreLon - RETAIN_HALF_DEG;
        final double roiMaxLon = centreLon + RETAIN_HALF_DEG;

        ConcurrentHashMap<Long, List<VidaBuilding>> cells = new ConcurrentHashMap<>();
        for (VidaBuilding b : buildings) {
            cells.computeIfAbsent(gridKey(b.centerLat, b.centerLon),
                    k -> new ArrayList<>()).add(b);
        }
        shardCache.put(key, new Shard(cells, buildings.size(),
                roiMinLat, roiMaxLat, roiMinLon, roiMaxLon));
        evictShardsIfNeeded();
        LOGGER.info("VIDA shard {} ranged-fetch: {} buildings retained "
                + "({} grid cells, region {}..{} lat, {}..{} lon)",
                key, buildings.size(), cells.size(),
                String.format("%.3f", roiMinLat), String.format("%.3f", roiMaxLat),
                String.format("%.3f", roiMinLon), String.format("%.3f", roiMaxLon));
    }

    /** One HTTP Range GET. Returns null on any non-206 response. */
    private byte[] httpRange(URL url, long from, long to) throws IOException {
        HttpURLConnection c = (HttpURLConnection) url.openConnection();
        c.setConnectTimeout(HTTP_TIMEOUT_MS);
        c.setReadTimeout(HTTP_TIMEOUT_MS);
        c.setRequestProperty("Range", "bytes=" + from + "-" + to);
        try (InputStream in = c.getInputStream()) {
            if (c.getResponseCode() != 206) return null;   // no range support
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[16384]; int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            return bo.toByteArray();
        } finally {
            c.disconnect();
        }
    }

    /**
     * Cold-start fast path: walk the shard's R-tree over HTTP and download only
     * the feature bytes inside the region of interest.
     *
     * Why this exists: the whole-file download is the single largest cost of a
     * first visit to a new area. The Hyderabad shard is 1.217 GB; the player
     * needs a few km of it. FlatGeobuf's layout makes partial reads possible:
     *
     *     magic(8) | headerSize(4) | header | rtree | features
     *
     * and the R-tree is a breadth-first array of 40-byte nodes, so each level
     * can be located arithmetically without reading the levels below it.
     *
     * Measured on the real shard (RETAIN_HALF_DEG = 0.02):
     *     tree walk   2.21 MB over ~40 requests
     *     features   13.81 MB in one contiguous read
     *     total      16.02 MB   vs   1217.04 MB   = 98.7% less
     *
     * Returns null (not empty) if anything is unsupported or unexpected, so the
     * caller falls back to the full download. Never throws for a data reason.
     */
    private List<VidaBuilding> fetchViaHttpRange(String iso, String shardId,
                                                 double centreLat, double centreLon) {
        try {
            URL url = new URL(VIDA_BASE_URL + iso + "/" + shardId + ".fgb");

            byte[] head = httpRange(url, 0, 11);
            if (head == null || head.length < 12) return null;
            ByteBuffer hb0 = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN);
            hb0.position(8);
            int headerSize = hb0.getInt();
            if (headerSize <= 0 || headerSize > (1 << 22)) return null;

            byte[] hb = httpRange(url, 12, 12L + headerSize - 1);
            if (hb == null || hb.length < headerSize) return null;
            Header header = Header.getRootAsHeader(
                    ByteBuffer.wrap(hb).order(ByteOrder.LITTLE_ENDIAN));

            long fCount   = header.featuresCount();
            int  nodeSize = header.indexNodeSize();
            if (nodeSize <= 0 || fCount <= 0) return null;

            final double minLat = centreLat - RETAIN_HALF_DEG;
            final double maxLat = centreLat + RETAIN_HALF_DEG;
            final double minLon = centreLon - RETAIN_HALF_DEG;
            final double maxLon = centreLon + RETAIN_HALF_DEG;

            if (header.envelopeLength() >= 4) {
                double fMinLon = header.envelope(0), fMinLat = header.envelope(1);
                double fMaxLon = header.envelope(2), fMaxLat = header.envelope(3);
                if (maxLon < fMinLon || minLon > fMaxLon
                        || maxLat < fMinLat || minLat > fMaxLat)
                    return new ArrayList<>();   // shard genuinely does not cover us
            }

            // Level sizes, root first. Mirrors rtreeSize()'s accounting.
            List<Long> levels = new ArrayList<>();
            long n = fCount;
            while (true) { levels.add(n); if (n == 1) break; n = (n + nodeSize - 1) / nodeSize; }
            Collections.reverse(levels);
            long[] levelStart = new long[levels.size()];
            long acc = 0;
            for (int i = 0; i < levels.size(); i++) { levelStart[i] = acc; acc += levels.get(i); }
            final long numNodes  = acc;
            final long treeStart = 8L + 4L + headerSize;
            final long featStart = treeStart + numNodes * 40L;

            // Breadth-first descent. Only nodes whose box meets the ROI expand.
            List<long[]> current = new ArrayList<>();   // {indexWithinLevel, offset}
            current.add(new long[]{0, 0});
            List<Long> featOffsets = new ArrayList<>();

            for (int lvl = 0; lvl < levels.size(); lvl++) {
                if (current.isEmpty()) break;
                if (current.size() > MAX_RANGE_NODES) return null;   // degenerate; use full download

                List<long[]> next = new ArrayList<>();

                // ONE request per level.
                //
                // The walk is latency-bound, not bandwidth-bound: it is a chain
                // of sequential round trips and that dominates cold start once
                // the payload is small. The key property is that FlatGeobuf
                // orders nodes by Hilbert curve, so the set of nodes matching a
                // spatial box is nearly CONTIGUOUS. Measured on the Hyderabad
                // shard at RETAIN_HALF_DEG=0.02:
                //
                //   level  frontier   contiguous span   whole level
                //   L0..L4  1..272      <= 0.011 MB      <= 0.75 MB
                //   L5        3,584        0.162 MB        11.93 MB
                //   L6       51,328        2.587 MB       190.87 MB
                //
                // So reading [minIndex, maxIndex] in a single request costs at
                // most 2.6 MB at the widest level, versus 52 separate requests
                // if each run is fetched individually. That took the walk from
                // 79 requests to 7.
                //
                // The span is bounded so a pathological frontier cannot pull a
                // whole 190 MB level; if it would, fall back to the full
                // download, which is simpler than fetching hundreds of runs.
                long firstIdx = current.get(0)[0];
                long lastIdx  = current.get(current.size() - 1)[0];
                for (long[] cnode : current) {          // frontier is built in
                    if (cnode[0] < firstIdx) firstIdx = cnode[0];   // ascending
                    if (cnode[0] > lastIdx)  lastIdx  = cnode[0];   // order, but
                }                                                   // be explicit
                long spanBytes = (lastIdx - firstIdx + 1) * 40L;
                if (spanBytes > MAX_LEVEL_SPAN_BYTES) return null;

                byte[] nodes = httpRange(url,
                        treeStart + (levelStart[lvl] + firstIdx) * 40L,
                        treeStart + (levelStart[lvl] + lastIdx + 1) * 40L - 1);
                if (nodes == null) return null;
                ByteBuffer nb = ByteBuffer.wrap(nodes).order(ByteOrder.LITTLE_ENDIAN);

                for (long[] cnode : current) {
                    long idx = cnode[0];
                    int base = (int) ((idx - firstIdx) * 40L);
                    if (base < 0 || base + 40 > nodes.length) continue;
                    double nMinX = nb.getDouble(base);
                    double nMinY = nb.getDouble(base + 8);
                    double nMaxX = nb.getDouble(base + 16);
                    double nMaxY = nb.getDouble(base + 24);
                    long   off   = nb.getLong(base + 32);
                    if (nMaxX < minLon || nMinX > maxLon
                            || nMaxY < minLat || nMinY > maxLat) continue;

                    if (lvl == levels.size() - 1) {
                        featOffsets.add(off);
                    } else {
                        long childStart = idx * nodeSize;
                        long childCount = levels.get(lvl + 1);
                        for (long c = childStart;
                             c < Math.min(childStart + nodeSize, childCount); c++)
                            next.add(new long[]{c, 0});
                    }
                }
                if (lvl == levels.size() - 1) break;
                current = next;
            }

            if (featOffsets.isEmpty()) return new ArrayList<>();
            Collections.sort(featOffsets);

            // One contiguous read across the matching features. They are Hilbert
            // ordered, so a spatial query maps to a tight offset span and the
            // gaps are small relative to a second round trip.
            long lo = featStart + featOffsets.get(0);
            long hi = featStart + featOffsets.get(featOffsets.size() - 1) + MAX_FEATURE_BYTES;
            if (hi - lo > MAX_RANGE_SPAN_BYTES) return null;   // too scattered; full download
            byte[] blob = httpRange(url, lo, hi);
            if (blob == null) return null;

            List<VidaBuilding> out = new ArrayList<>();
            for (long fo : featOffsets) {
                int p = (int) (featStart + fo - lo);
                if (p < 0 || p + 4 > blob.length) continue;
                ByteBuffer fb = ByteBuffer.wrap(blob).order(ByteOrder.LITTLE_ENDIAN);
                int fSize = fb.getInt(p);
                if (fSize <= 0 || p + 4 + fSize > blob.length) continue;

                byte[] bytes = new byte[fSize];
                System.arraycopy(blob, p + 4, bytes, 0, fSize);
                Feature f = Feature.getRootAsFeature(
                        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN));

                double area = readArea(f);
                Geometry g = f.geometry();
                if (g == null || g.xyLength() < 6) continue;

                int ptCount = g.xyLength() / 2;
                OsmStructureIndex.OsmNode[] nds = new OsmStructureIndex.OsmNode[ptCount];
                for (int k = 0; k < ptCount; k++)
                    nds[k] = new OsmStructureIndex.OsmNode(g.xy(k * 2 + 1), g.xy(k * 2));
                out.add(new VidaBuilding(nds, area));
            }

            LOGGER.info("VIDA cold-start ranged fetch {}_{}: {} features via {} leaf hits, "
                    + "{} KB of feature data (full shard avoided)",
                    iso, shardId, out.size(), featOffsets.size(), (hi - lo) / 1024);
            return out;

        } catch (Throwable t) {
            LOGGER.debug("VIDA ranged fetch unavailable ({}); using full download",
                    t.toString());
            return null;
        }
    }

    /**
     * Collects buildings overlapping the query box by visiting only the grid
     * cells it touches, instead of scanning the whole shard.
     *
     * A chunk spans far less than one 0.01deg cell, so this normally visits
     * 1-4 cells and a few hundred buildings, matching what OSM and Overture do.
     */
    /**
     * Reads only the features intersecting the region, using the packed
     * Hilbert R-tree that FlatGeobuf stores inside every .fgb file.
     *
     * The previous implementation called skipBytes() over the whole index and
     * then FlatBuffer-decoded all ~2.25M features to keep the few thousand near
     * the player. That cost ~90 s of CPU and a large transient allocation on
     * EVERY new location, which is why a cold start allowed exactly one
     * teleport before the next one stalled or OOMed.
     *
     * Layout: magic(8) | headerSize(4) | header | rtree | features
     * Node = 4 doubles + long offset = 40 bytes. Offsets from the search are
     * relative to the start of the feature section.
     *
     * NOTE: the ByteBuffer overload of PackedRTree.search is used deliberately;
     * the InputStream overload pulls in Guava's LittleEndianDataInputStream,
     * which is not a declared dependency of this project.
     *
     * @return matching buildings, or null if the file has no usable index
     *         (caller then falls back to the linear scan).
     */
    @SuppressWarnings("unchecked")
    private List<VidaBuilding> readViaRTree(Path file,
                                            double minLat, double maxLat,
                                            double minLon, double maxLon) {
        try (java.nio.channels.FileChannel ch = java.nio.channels.FileChannel.open(
                file, java.nio.file.StandardOpenOption.READ)) {

            long size = ch.size();
            if (size < 12) return null;

            java.nio.MappedByteBuffer map = ch.map(
                    java.nio.channels.FileChannel.MapMode.READ_ONLY, 0, size);
            map.order(ByteOrder.LITTLE_ENDIAN);

            map.position(8);
            int headerSize = map.getInt();
            if (headerSize <= 0 || headerSize > size) return null;

            byte[] hb = new byte[headerSize];
            map.get(hb);
            Header header = Header.getRootAsHeader(
                    ByteBuffer.wrap(hb).order(ByteOrder.LITTLE_ENDIAN));

            long fCount = header.featuresCount();
            int  nodeSize = header.indexNodeSize();
            if (nodeSize <= 0 || fCount <= 0) return null;   // no index present

            int treeStart = 8 + 4 + headerSize;
            long treeBytes = rtreeSize(fCount, nodeSize);
            long featStart = treeStart + treeBytes;
            if (featStart >= size) return null;

            // The header carries the bounding box of the WHOLE file as
            // [minX, minY, maxX, maxY] = [minLon, minLat, maxLon, maxLat].
            // Checking it first turns "0 buildings, no idea why" into a clear
            // statement about whether this shard covers the player at all.
            if (header.envelopeLength() >= 4) {
                double fMinLon = header.envelope(0), fMinLat = header.envelope(1);
                double fMaxLon = header.envelope(2), fMaxLat = header.envelope(3);
                boolean overlaps = !(maxLon < fMinLon || minLon > fMaxLon
                                  || maxLat < fMinLat || minLat > fMaxLat);
                if (!overlaps) {
                    LOGGER.error("VIDA shard does NOT cover this location. "
                        + "shard covers lat {}..{}, lon {}..{}; "
                        + "requested lat {}..{}, lon {}..{}. "
                        + "getS2ShardId() returns one hard-coded S2 cell per "
                        + "country, so anywhere outside that cell gets no VIDA "
                        + "buildings. Fix the shard lookup to pick the cell "
                        + "containing the query point.",
                        String.format("%.3f", fMinLat), String.format("%.3f", fMaxLat),
                        String.format("%.3f", fMinLon), String.format("%.3f", fMaxLon),
                        String.format("%.3f", minLat), String.format("%.3f", maxLat),
                        String.format("%.3f", minLon), String.format("%.3f", maxLon));
                    return new ArrayList<>();   // empty, not null: no linear-scan retry
                }
            }

            // Geometry is stored x=lon, y=lat.
            org.locationtech.jts.geom.Envelope env =
                    new org.locationtech.jts.geom.Envelope(minLon, maxLon, minLat, maxLat);

            java.nio.ByteBuffer treeBuf = map.duplicate();
            treeBuf.order(ByteOrder.LITTLE_ENDIAN);
            treeBuf.position(treeStart);
            java.nio.ByteBuffer tree = treeBuf.slice();
            tree.order(ByteOrder.LITTLE_ENDIAN);

            java.util.ArrayList<org.wololo.flatgeobuf.PackedRTree.SearchHit> hits =
                    org.wololo.flatgeobuf.PackedRTree.search(
                            tree, 0, (int) fCount, nodeSize, env);
            if (hits == null) return null;

            List<VidaBuilding> out = new ArrayList<>(hits.size());
            for (org.wololo.flatgeobuf.PackedRTree.SearchHit hit : hits) {
                long pos = featStart + hit.offset;
                if (pos + 4 > size) continue;

                java.nio.ByteBuffer fb = map.duplicate();
                fb.order(ByteOrder.LITTLE_ENDIAN);
                fb.position((int) pos);
                int fSize = fb.getInt();
                if (fSize <= 0 || pos + 4 + fSize > size) continue;

                byte[] bytes = new byte[fSize];
                fb.get(bytes);
                Feature f = Feature.getRootAsFeature(
                        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN));

                double area = readArea(f);
                Geometry g = f.geometry();
                if (g == null || g.xyLength() < 6) continue;

                int ptCount = g.xyLength() / 2;
                OsmStructureIndex.OsmNode[] nodes =
                        new OsmStructureIndex.OsmNode[ptCount];
                for (int i = 0; i < ptCount; i++)
                    nodes[i] = new OsmStructureIndex.OsmNode(g.xy(i * 2 + 1), g.xy(i * 2));
                out.add(new VidaBuilding(nodes, area));
            }
            LOGGER.info("VIDA R-tree query returned {} of {} features ({}x reduction)",
                    out.size(), fCount,
                    out.isEmpty() ? fCount : (fCount / Math.max(1, out.size())));
            return out;

        } catch (Throwable t) {
            // Catches NoClassDefFoundError too: PackedRTree.search touches
            // Guava internals. Guava ships with Minecraft (31.1-jre) and
            // terraminusminus already depends on it, so this should not fire,
            // but a hard failure here must degrade to the linear scan rather
            // than break world generation.
            if (rtreeWarned.compareAndSet(false, true))
                LOGGER.warn("VIDA R-tree unavailable ({}); using linear scan. "
                        + "Cold-start teleports to new regions will be slow.",
                        t.toString());
            return null;
        }
    }

    /** Ensures the R-tree fallback warning is logged once per session. */
    private final java.util.concurrent.atomic.AtomicBoolean rtreeWarned =
        new java.util.concurrent.atomic.AtomicBoolean(false);

    /** Total bytes of the packed R-tree for the given feature count. */
    private static long rtreeSize(long numItems, int nodeSize) {
        int n = Math.min(Math.max(nodeSize, 2), 65535);
        long numNodes = numItems;
        long lvl = numItems;
        do {
            lvl = (lvl + n - 1) / n;
            numNodes += lvl;
        } while (lvl > 1);
        return numNodes * 40L;
    }

    /** Extracts the area property, mirroring the linear-scan parser. */
    private static double readArea(Feature f) {
        ByteBuffer pBuf = f.propertiesAsByteBuffer();
        double area = 50.0;
        if (pBuf != null) {
            pBuf.order(ByteOrder.LITTLE_ENDIAN);
            while (pBuf.hasRemaining()) {
                int cIdx = pBuf.getShort() & 0xFFFF;
                if (cIdx == 3) area = pBuf.getDouble(); else break;
            }
        }
        return area;
    }

    private List<VidaBuilding> filterBuildings(Shard shard, double minLat, double maxLat,
                                               double minLon, double maxLon) {
        shard.lastAccess = System.currentTimeMillis();

        final double pad = 0.0005;
        double qMinLat = minLat - pad, qMaxLat = maxLat + pad;
        double qMinLon = minLon - pad, qMaxLon = maxLon + pad;

        long gLatMin = (long) Math.floor(qMinLat / GRID_DEG);
        long gLatMax = (long) Math.floor(qMaxLat / GRID_DEG);
        long gLonMin = (long) Math.floor(qMinLon / GRID_DEG);
        long gLonMax = (long) Math.floor(qMaxLon / GRID_DEG);

        List<VidaBuilding> res = new ArrayList<>();
        for (long gLat = gLatMin; gLat <= gLatMax; gLat++) {
            for (long gLon = gLonMin; gLon <= gLonMax; gLon++) {
                List<VidaBuilding> cell =
                    shard.cells.get((gLat << 32) ^ (gLon & 0xFFFFFFFFL));
                if (cell == null) continue;
                for (VidaBuilding b : cell) {
                    if (b.centerLat >= qMinLat && b.centerLat <= qMaxLat &&
                        b.centerLon >= qMinLon && b.centerLon <= qMaxLon) res.add(b);
                }
            }
        }
        return res;
    }

    /** Drops the least-recently-used shard when too many are resident. */
    private void evictShardsIfNeeded() {
        while (shardCache.size() > MAX_SHARDS) {
            String oldestKey = null;
            long oldest = Long.MAX_VALUE;
            for (Map.Entry<String, Shard> e : shardCache.entrySet()) {
                long t = e.getValue().lastAccess;
                if (t < oldest) { oldest = t; oldestKey = e.getKey(); }
            }
            if (oldestKey == null) return;
            Shard removed = shardCache.remove(oldestKey);
            if (removed != null)
                LOGGER.info("Evicted VIDA shard {} ({} buildings)", oldestKey, removed.count);
        }
    }

    // ================================================================
    // SHARD INDEX
    // ================================================================
    // VIDA splits each country into many level-6 S2 cells (~143 km across):
    // India alone has 198, USA 72, Germany 11. The original code mapped a
    // whole country bounding box to ONE hard-coded cell, so ~99% of each
    // country resolved to a shard that does not contain it and silently
    // produced zero buildings. Hyderabad is the concrete case: it sits at
    // lon 78.54 but the hard-coded India shard only covers 76.46..77.93.
    //
    // vida_shards.csv holds the real bounding box of every shard, read from
    // the .fgb headers themselves, so lookup is an exact containment test.

    private record ShardBox(String iso, String id,
                            double minLat, double maxLat,
                            double minLon, double maxLon) {
        boolean contains(double lat, double lon) {
            return lat >= minLat && lat <= maxLat
                && lon >= minLon && lon <= maxLon;
        }
    }

    private static volatile List<ShardBox> SHARDS;

    private static List<ShardBox> shards() {
        List<ShardBox> local = SHARDS;
        if (local != null) return local;
        synchronized (SourceCooperativeIndex.class) {
            if (SHARDS != null) return SHARDS;
            List<ShardBox> list = new ArrayList<>();
            try (InputStream in = SourceCooperativeIndex.class.getResourceAsStream(
                    "/data/terraplusplus/vida_shards.csv")) {
                if (in != null) {
                    java.io.BufferedReader r = new java.io.BufferedReader(
                        new java.io.InputStreamReader(in, StandardCharsets.UTF_8));
                    String line;
                    while ((line = r.readLine()) != null) {
                        line = line.trim();
                        if (line.isEmpty() || line.charAt(0) == '#') continue;
                        String[] p = line.split(",");
                        if (p.length < 6) continue;
                        list.add(new ShardBox(p[0], p[1],
                            Double.parseDouble(p[2]), Double.parseDouble(p[3]),
                            Double.parseDouble(p[4]), Double.parseDouble(p[5])));
                    }
                }
            } catch (Exception e) {
                LOGGER.error("Failed to read vida_shards.csv: {}", e.toString());
            }
            if (list.isEmpty())
                LOGGER.warn("VIDA shard index empty; VIDA buildings disabled.");
            else
                LOGGER.info("VIDA shard index loaded: {} shards", list.size());
            SHARDS = list;
            return list;
        }
    }

    /** Shard whose real bounding box contains the point, or null. */
    private ShardBox findShard(double lat, double lon) {
        for (ShardBox s : shards())
            if (s.contains(lat, lon)) return s;
        return null;
    }

    private String getCountryISO(double lat, double lon) {
        ShardBox s = findShard(lat, lon);
        return s == null ? null : s.iso();
    }

    private String getS2ShardId(double lat, double lon) {
        ShardBox s = findShard(lat, lon);
        return s == null ? null : s.id();
    }

    public List<VidaBuilding> getBuildingsForChunkBlocking(double minLat, double maxLat, double minLon, double maxLon, long timeoutMs, net.buildtheearth.terraplusplus.generator.EsaWorldCoverIndex esa) {
        String shardId = getS2ShardId(minLat, minLon); String iso = getCountryISO(minLat, minLon);
        if (shardId == null) return List.of();
        double cLat = (minLat + maxLat) / 2.0, cLon = (minLon + maxLon) / 2.0;
        String key = regionKey(iso, shardId, cLat, cLon);
        Long retryAfter = retryAfterMs.get(key);
        if (retryAfter != null) {
            if (System.currentTimeMillis() < retryAfter) return null;
            retryAfterMs.remove(key, retryAfter);
        }
        Shard cached = shardCache.get(key);
        if (cached != null && cached.covers(minLat, maxLat, minLon, maxLon))
            return filterBuildings(cached, minLat, maxLat, minLon, maxLon);
        if (cached == null) {
            for (Shard s : shardCache.values())
                if (s.covers(minLat, maxLat, minLon, maxLon))
                    return filterBuildings(s, minLat, maxLat, minLon, maxLon);
        }
        if (pendingShards.add(key))
            triggerShardFetch(iso, shardId, cLat, cLon, key);
        return null; 
    }

    public int getCacheSize() { return shardCache.size(); }

    /** Total buildings currently resident across all cached shards. */
    public int getBuildingCount() {
        int n = 0;
        for (Shard s : shardCache.values()) n += s.count;
        return n;
    }
}
