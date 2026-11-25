// ============================================================
// FILE: TerraCheckCommand.java (NEW — /terracheck diagnostics)
//
// Answers, in game, the question "are the data sources really
// working — even on a cold start?":
//
//   /terracheck        instant status: Earth generator live?,
//                      your lat/lon, active source mode, year,
//                      structure pipeline counters, and the
//                      cache state (EMPTY = cold start).
//   /terracheck fetch  live probes, run OFF the server thread
//                      (never blocks a tick — the Finding-1
//                      lesson): do OSM, OHM, Microsoft and
//                      VIDA answer at all, and how fast?
//                      30 s cooldown between probe runs.
//   /terracheck here   what do the three sources hold for
//                      YOUR chunk right now (tiles loaded?
//                      VIDA shard arrived yet? structures by
//                      type). The cold-start test.
//   /terracheck find <osmId>
//                      how did the real parser classify this
//                      one feature? (The Charminar test.)
//
// ALL results are written to the Minecraft log under
// [TerraCheck] as well as to chat — the log is the record.
//
// Verdict words are honest: ANSWERS = the pipe responded,
// NO ANSWER = it did not. Nothing here claims buildings are
// correct — only that the pipes and the pipeline are alive.
//
// BATCH 2 (2026-11-18, from the batch-1 owner's log):
//   - probes: ANSWERS only on HTTP 2xx/3xx (a 502 is NO
//     ANSWER), and the OSM probe now knocks on the real door
//     structures use (api.openstreetmap.org), not only
//     Overpass.
//   - status: the async-road counters are labelled as such —
//     they are NOT total world placements.
//   - here: OSM items list buildings/parts first; new
//     HEIGHTS count (tagged levels vs tagged height vs
//     guessed); Microsoft gets its full counters plus a
//     plain VERDICT (never asked / still waiting / tiles
//     here but empty / buildings here); new REFILL section
//     reports the async refill road for this chunk.
//
// BATCH 3 (2026-11-18, directed: "solve VIDA late and
// Microsoft"): the fixes live in the sources themselves —
// MicrosoftIndex (download streamed to disk, progress
// lines, FAILED line on any error incl. memory errors),
// SourceCooperativeIndex.prefetch (no cold-start delay)
// called from TerraChunkGenerator.warmDestination (VIDA
// was the one source warmup never fired), and
// AsyncStructureManager (a dead lowest tier can no longer
// veto tile completion — that veto was the dead refill
// road). New readouts here: MICROSOFT download state
// (lines read / age / time since progress), VIDA pending
// regions, REFILL completed-without counters + verdict.
// Batch 3 also tests ALL sources (owner directive): ESA
// WorldCover, water (OSM+OHM), the Overture appetizer,
// elevation, and OHM — which must honestly report OFF BY
// SWITCH while timejump is disabled. Roads/rails/bridges
// remain covered by the OSM byType count above.
//
// BATCH 4 (2026-11-18, owner directive: "freedom to test
// all the upcoming problems"): tests the open problems the
// sea-level review found, still inside plain /terracheck:
//   EXTREMES — elevation + game-Y at nine world extremes
//     (Everest, Kilimanjaro, Dead Sea shore, Death Valley,
//     a Dutch polder, Caspian, Mariana Trench, Bay of
//     Bengal, Hyderabad control). Far tiles may read NOT
//     LOADED on the first run (fetch continues in the
//     background) — rerun /terracheck and they land.
//   SEAM — pure-maths audit of the height scales: the
//     1,000 m join step, below-sea 10:1 squash, trench
//     squash, and the world ceiling/floor.
//   SENTINEL SCAN — does THIS chunk currently read the
//     -50 "no data" sentinel anywhere? (A chunk generated
//     on the sentinel becomes fake ocean.)
//   DEAD SEA GRID — 25 samples over the Dead Sea: source
//     DEM spikes (satellite voids) made visible.
//   SOURCE STACK — buildings held for this chunk by each
//     source side by side (the Microsoft step-3 pre-test:
//     who carries a chunk when OSM is thin?).
//   fetch — Wikidata + Wikipedia doors added (the wiki
//     lookup source was never tested).
// ============================================================
package net.buildtheearth.terraplusplus.commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.LongArgumentType;

import net.buildtheearth.terraplusplus.config.TerraWorldConfig;
import net.buildtheearth.terraplusplus.generator.CaveData;
import net.buildtheearth.terraplusplus.generator.EsaWorldCoverIndex;
import net.buildtheearth.terraplusplus.generator.HeightSpikeGuard;
import net.buildtheearth.terraplusplus.generator.KarstData;
import net.buildtheearth.terraplusplus.generator.TerraBiomeProvider;
import net.buildtheearth.terraplusplus.generator.TerraChunkGenerator;
import net.buildtheearth.terraplusplus.generator.TerraDensityFunctions;
import net.buildtheearth.terraplusplus.generator.historical.OhmStructureIndex;
import net.buildtheearth.terraplusplus.generator.historical.TerraTimeManager;
import net.buildtheearth.terraplusplus.generator.osm.AsyncStructureManager;
import net.buildtheearth.terraplusplus.generator.osm.MicrosoftIndex;
import net.buildtheearth.terraplusplus.generator.osm.OsmStructureIndex;
import net.buildtheearth.terraplusplus.generator.osm.OvertureOsmIndex;
import net.buildtheearth.terraplusplus.generator.osm.SourceCooperativeIndex;
import net.buildtheearth.terraplusplus.generator.osm.StructurePlacementQueue;
import net.buildtheearth.terraplusplus.generator.osm.StructureTickHandler;
import net.buildtheearth.terraplusplus.generator.water.RiverLakeEngine;
import net.buildtheearth.terraplusplus.util.TerraCachePaths;
import net.buildtheearth.terraminusminus.generator.EarthGeneratorSettings;
import net.buildtheearth.terraminusminus.projection.GeographicProjection;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

public class TerraCheckCommand {

    // Owner directive (2026-11-18): /terracheck is the test
    // bench for understanding Terra3. Every test answers into
    // the Minecraft log under [TerraCheck], so results can be
    // read from latest.log without watching the game screen.
    private static final Logger LOGGER =
            LogManager.getLogger("TerraCheck");

    private static final AtomicLong LAST_FETCH = new AtomicLong(0);
    private static final long FETCH_COOLDOWN_MS = 30_000L;

    private static volatile GeographicProjection projection;
    private static volatile boolean projectionFailed = false;

    // The real endpoints, copied from the code that uses them:
    // OsmStructureIndex (api.openstreetmap.org), OhmStructureIndex,
    // MicrosoftIndex (dataset-links.csv), SourceCooperativeIndex
    // (VIDA at data.source.coop). Owner request: the old
    // "OSM (Overpass)" probe is no longer shown here — it kept
    // reporting NO ANSWER and confused the door list. This only
    // removes the probe line; the Overpass fallback code itself
    // is left in place as a last-resort path.
    private static final String[][] PROBES = {
        {"OSM tiles (api.openstreetmap.org, the door structures use)",
         "https://api.openstreetmap.org/api/0.6/capabilities"},
        {"OHM (historical Overpass)",
         "https://overpass-api.openhistoricalmap.org"},
        {"Microsoft (building index)",
         "https://minedbuildings.z5.web.core.windows.net/global-buildings/dataset-links.csv"},
        {"VIDA (source.coop host)", "https://data.source.coop/"},
        // Batch 4: the WikiDataLookup source (building years
        // for timejump dating) was never tested. These are
        // ITS doors, copied from WikiDataLookup.java.
        {"Wikidata (SPARQL door)",
         "https://query.wikidata.org/sparql"},
        {"Wikipedia (API door)",
         "https://en.wikipedia.org/w/api.php"},
    };

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
                Commands.literal("terracheck")
                        .requires(src -> src.hasPermission(2))
                        .executes(ctx -> all(ctx.getSource()))
                        .then(Commands.literal("fetch")
                                .executes(ctx -> fetch(ctx.getSource())))
                        .then(Commands.literal("here")
                                .executes(ctx -> here(ctx.getSource())))
                        .then(Commands.literal("coast")
                                .executes(ctx -> coastPerf(ctx.getSource())))
                        .then(Commands.literal("find")
                                .then(Commands.argument("osmid",
                                        LongArgumentType.longArg())
                                        .executes(ctx -> find(ctx.getSource(),
                                                LongArgumentType.getLong(
                                                        ctx, "osmid")))))
        );
    }

    private static MutableComponent prefix() {
        return Component.literal("[TerraCheck] ")
                .withStyle(ChatFormatting.AQUA);
    }

    private static void line(CommandSourceStack source, String text,
                             ChatFormatting color) {
        // Chat for the player AND the log for the record —
        // the log is the primary test output.
        LOGGER.info("[TerraCheck] {}", text.trim());
        source.sendSuccess(() -> Component.literal(text)
                .withStyle(color), false);
    }

    private static void log(String text) {
        LOGGER.info("[TerraCheck] {}", text);
    }

    // ---------------------------------------------------------
    // Plain /terracheck — the ONLY thing the owner types.
    // Runs the whole batch by itself: status, the chunk
    // (here) test, and the source (fetch) probes. Results go
    // to chat AND to latest.log under [TerraCheck].
    // ---------------------------------------------------------
    private static int all(CommandSourceStack source) {
        log("=== /terracheck RUN ALL ===");
        // Location FIRST, always (owner 2026-11-20: "why
        // terracheck dont have lat and lon of command used
        // place?"). Previously lat/lon only appeared inside
        // status() via a separately parsed default
        // projection — silently absent when that failed and
        // easy to miss in chat. Now the place the command
        // was used is printed before every test, using the
        // active world's own projection (geoOf), with the
        // default projection only as fallback.
        ServerPlayer locPlayer = source.getPlayer();
        if (locPlayer == null) {
            line(source, "  Location: no player (console) —"
                    + " run /terracheck in game for lat/lon.",
                    ChatFormatting.YELLOW);
        } else {
            double[] geo = playerGeo(locPlayer);
            if (geo != null) {
                var cp = locPlayer.chunkPosition();
                line(source, String.format(
                        "  LOCATION: lat %.6f, lon %.6f"
                        + "  (block %d, %d | chunk %d, %d)",
                        geo[1], geo[0],
                        locPlayer.getBlockX(), locPlayer.getBlockZ(),
                        cp.x, cp.z),
                        ChatFormatting.WHITE);
            } else {
                line(source, "  LOCATION: could not convert"
                        + " your block position to lat/lon"
                        + " (projection not ready).",
                        ChatFormatting.RED);
            }
        }
        status(source);
        here(source);
        biomeHere(source);
        elevSourceHere(source);
        chunkBuildHere(source);
        coastPerf(source);
        fetch(source);
        log("=== /terracheck RUN ALL: tests started"
                + " (async results follow in this log) ===");
        return 1;
    }

    // ---------------------------------------------------------
    // /terracheck — instant, main-thread, cheap reads only
    // ---------------------------------------------------------
    private static int status(CommandSourceStack source) {
        source.sendSuccess(() -> prefix()
                .append(Component.literal("Terra 3 health report")
                        .withStyle(ChatFormatting.WHITE)), false);

        boolean gen = TerraChunkGenerator.activeInstance != null;
        line(source, gen
                ? "  Earth generator: ACTIVE"
                : "  Earth generator: NOT ACTIVE (not a Terra world?)",
                gen ? ChatFormatting.GREEN : ChatFormatting.RED);

        ServerPlayer player = source.getPlayer();
        if (player != null) {
            double[] geo = playerGeo(player);
            if (geo != null) {
                line(source, String.format(
                        "  You are at: lat %.6f, lon %.6f",
                        geo[1], geo[0]), ChatFormatting.GRAY);
            } else {
                line(source, "  You are at: (lat/lon unavailable"
                        + " — projection not ready)",
                        ChatFormatting.YELLOW);
            }
        }

        String mode = "unknown";
        try {
            mode = TerraChunkGenerator.getActiveSource();
        } catch (Throwable ignored) {
        }
        line(source, "  Building source mode: " + mode.toUpperCase()
                + "  (switch with /terra source osm|Microsoft|both)",
                ChatFormatting.GRAY);
        line(source, "  Height spike guard: "
                + (HeightSpikeGuard.enabled ? "ON" : "OFF")
                + "  repaired " + HeightSpikeGuard.TOTAL_REPAIRED.get()
                + " px  (switch with /terra heights guard on|off)",
                HeightSpikeGuard.enabled
                        ? ChatFormatting.GREEN : ChatFormatting.RED);

        if (TerraTimeManager.isTimeEnabled()) {
            line(source, "  Historical clock: ON, year "
                    + TerraTimeManager.getCurrentYear()
                    + " (OHM is the source for this year)",
                    ChatFormatting.GRAY);
        } else {
            line(source, "  Historical clock: OFF (modern world, OSM data)",
                    ChatFormatting.GRAY);
        }

        if (StructureTickHandler.isInitialized()) {
            AsyncStructureManager async = StructureTickHandler.getAsyncManager();
            StructurePlacementQueue pq = StructureTickHandler.getPlacementQueue();
            if (async != null) {
                line(source, String.format(
                        "  Async refill road: %d pending, %d done, "
                        + "%d retries, %d successes",
                        async.getPendingCount(), async.getCompletedCount(),
                        async.getTotalRetries(), async.getTotalSuccesses()),
                        ChatFormatting.GRAY);
            }
            if (pq != null) {
                line(source, String.format(
                        "  Placement queue: %d active, %d deferred, %d placed",
                        pq.getActiveQueueSize(), pq.getDeferredSize(),
                        pq.getTotalPlaced()), ChatFormatting.GRAY);
            }
            // Batch-2 fix: batch 1 called this "Placed so far"
            // and it read as 0 while generation placed thousands
            // of buildings. These counters belong ONLY to the
            // async road; world generation shows in the log as
            // [TerraStruct] PLACED lines.
            line(source, String.format(
                    "  Async-road placements ONLY (not world generation):"
                    + " %d chunks, %d blocks",
                    StructureTickHandler.getTotalChunksPlaced(),
                    StructureTickHandler.getTotalBlocksPlaced()),
                    ChatFormatting.GRAY);
        } else {
            line(source, "  Structure pipeline: not initialised yet",
                    ChatFormatting.YELLOW);
        }

        line(source, cacheLine(), ChatFormatting.GRAY);
        line(source, "  Plain /terracheck runs every test (status + "
                + "chunk + probes). Full results: chat here, and "
                + "latest.log — search [TerraCheck].",
                ChatFormatting.DARK_GRAY);
        return 1;
    }

    private static String cacheLine() {
        try {
            Path root = TerraCachePaths.root();
            if (root == null || !Files.isDirectory(root)) {
                return "  Cache: none yet — COLD START (first area "
                        + "will fetch from the internet)";
            }
            AtomicLong files = new AtomicLong();
            AtomicLong bytes = new AtomicLong();
            try (Stream<Path> walk = Files.walk(root)) {
                walk.limit(500_000).forEach(p -> {
                    if (Files.isRegularFile(p)) {
                        files.incrementAndGet();
                        try {
                            bytes.addAndGet(Files.size(p));
                        } catch (Exception ignored) {
                        }
                    }
                });
            }
            if (files.get() == 0) {
                return "  Cache: EMPTY — COLD START (first area will "
                        + "fetch from the internet)";
            }
            return String.format("  Cache: warm — %d files, %.1f MB at %s",
                    files.get(), bytes.get() / 1_000_000.0, root);
        } catch (Throwable t) {
            return "  Cache: unreadable (" + t.getClass().getSimpleName() + ")";
        }
    }

    // ---------------------------------------------------------
    // ELEVATION SOURCE CHECK — part of plain /terracheck
    // (2026-11-20, owner: "in terracheck also check for
    // elevation sources"). Proves in-game where heights
    // came from: which source served the tile under the
    // player, session-wide source counts for BOTH books,
    // parent-zoom fallback use, and the owner Y rule's
    // low-zone counters (new book >= Y65, old book <= Y64,
    // transition gap in between — two books are BY DESIGN
    // now; a tile from an UNKNOWN source is the red flag).
    // ---------------------------------------------------------
    private static int elevSourceHere(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            line(source, "  elevation source check needs a player"
                    + " (run /terracheck in game).",
                    ChatFormatting.RED);
            return 0;
        }
        TerraChunkGenerator gen = TerraChunkGenerator.activeInstance;
        if (gen == null) {
            line(source, "  Earth generator: NOT ACTIVE"
                    + " (not a Terra world?)", ChatFormatting.RED);
            return 0;
        }
        double[] geo = geoOf(gen, player.getBlockX(),
                player.getBlockZ());
        if (geo == null) {
            line(source, "  elevation source check: projection"
                    + " not ready.", ChatFormatting.RED);
            return 0;
        }
        double lat = geo[1], lon = geo[0];
        line(source, "  elevation source check — full report in"
                + " the log, search [TerraCheck]",
                ChatFormatting.GRAY);
        CompletableFuture.runAsync(() -> {
            try {
                TerraDensityFunctions df = gen.getDfPublic();
                if (df == null) {
                    log("ELEVATION SOURCE: density functions not"
                            + " ready — skipped");
                    return;
                }
                // Sampling once also makes sure the tile under
                // the player is loaded, so its source is known.
                boolean oldOnly = TerraDensityFunctions.oldBookOnlyMode();
                double elev = df.getElevationFast(lat, lon);
                String key = df.tileKeyAt(lat, lon);
                String src = df.tileSourceAt(lat, lon);
                var counts = df.tileSourceCounts();
                int live = counts.getOrDefault(
                        "live Mapterhorn", 0);
                int disk = counts.getOrDefault(
                        "disk cache (mapterhorn-v2)", 0);
                int failed = counts.getOrDefault("failed", 0);
                int other = counts.getOrDefault("other", 0);
                int total = live + disk + failed + other;
                int fallbacks = df.parentFallbackCount();
                var oldCounts = df.oldTileSourceCounts();
                int oldLive = oldCounts.getOrDefault(
                        "live Terrarium", 0);
                int oldDisk = oldCounts.getOrDefault(
                        "disk cache (Terrarium-y-v1)", 0);
                int oldFailed = oldCounts.getOrDefault(
                        "failed", 0);
                int oldOther = oldCounts.getOrDefault(
                        "other", 0);
                int[] lowStats = df.lowBookStats();
                long oldDiskTiles = df.oldDiskTileCount();
                long diskTiles = df.diskTileCount();
                Path tilesRoot = df.tilesRootDir();
                long legacyV1 = countPngs(
                        tilesRoot.resolve("mapterhorn-v1"));
                long legacyOld = countLegacyPngs(tilesRoot);

                log(String.format("ELEVATION SOURCE @ %.6f, %.6f",
                        lat, lon));
                if (oldOnly)
                    log("  ELEVATION MODE: OLD BOOK ONLY (TEST"
                            + " RUN — started with"
                            + " -Dterra.oldBookOnly=true): every"
                            + " height comes from the old book"
                            + " (Terrarium) alone; the new-book"
                            + " tile counts below should stay 0."
                            + " Samples served by the old book"
                            + " this session: "
                            + df.oldOnlySamples() + ". Restart"
                            + " WITHOUT the flag to return to"
                            + " the owner Y rule.");
                log(String.format("  your height tile %s: %s"
                        + "  (sampled elevation %.1f m)",
                        key, src, elev));
                log(String.format("  this session: %d tiles in"
                        + " memory — live Mapterhorn %d, disk"
                        + " cache %d, failed %d%s",
                        total, live, disk, failed,
                        other > 0 ? ", OTHER " + other : ""));
                log(String.format("  old book (Terrarium, Y rule"
                        + " low zone only): live %d, disk cache"
                        + " %d, failed %d%s — %d tiles on disk;"
                        + " samples so far: %d fully old"
                        + " (below -1 m), %d blended in the"
                        + " -1..+5 m transition gap",
                        oldLive, oldDisk, oldFailed,
                        oldOther > 0 ? ", OTHER " + oldOther : "",
                        oldDiskTiles, lowStats[0], lowStats[1]));
                log(String.format("  parent-zoom fallback used:"
                        + " %d time(s) (0 is healthy; hits mean"
                        + " samples sat on coarse parent heights)",
                        fallbacks));
                log(String.format("  disk cache: %s — %s tiles"
                        + " stored", df.diskCacheDir(),
                        diskTiles < 0 ? "?"
                                : String.valueOf(diskTiles)));
                if (legacyV1 > 0 || legacyOld > 0)
                    log(String.format("  leftover tiles from"
                        + " older builds on disk: %d (previous"
                        + " cache folder) + %d (oldest archives)"
                        + " — IGNORED, this build never reads"
                        + " them (the Y rule's active old-book"
                        + " cache is Terrarium-y-v1, counted"
                        + " above); safe to delete those folders",
                        legacyV1, legacyOld));
                if (other > 0 || oldOther > 0)
                    log("  => ELEVATION SOURCE VERDICT: RED FLAG"
                        + " — " + (other + oldOther) + " tile(s)"
                        + " from an unknown source! An old jar"
                        + " may be running.");
                else if (oldOnly)
                    log("  => ELEVATION SOURCE VERDICT: TEST MODE"
                        + " — old book only, as launched. Judge"
                        + " this run by eye and by the CHUNK"
                        + " BUILD section (heights vs fresh"
                        + " samples), not by the Y-rule split."
                        + " The new book being absent here is"
                        + " the point of the test, not a fault.");
                else if ("FAILED".equals(src))
                    log("  => ELEVATION SOURCE VERDICT: your tile"
                        + " FAILED to load (connection?) — heights"
                        + " here may be fallback quality; re-check"
                        + " when back online.");
                else
                    log("  => ELEVATION SOURCE VERDICT: OK —"
                        + " books split by height as designed"
                        + " (owner Y rule): new book at/above"
                        + " +5 m, old book at/below -1 m, mixed"
                        + " in the gap between; every tile above"
                        + " came from its own book's cache.");
            } catch (Throwable t) {
                log("ELEVATION SOURCE: ERROR " + t);
            }
        });
        return 1;
    }

    // ---------------------------------------------------------
    // CHUNK BUILD CHECK — part of plain /terracheck
    // (2026-11-20, owner idea: "use terracheck to check chunks
    // how it is being build"; design in
    // TERRACHECK_CHUNK_BUILD_PLAN.md). This is Part A, the
    // FORENSIC half: a saved chunk stores no build provenance
    // (no source, no timestamp, no "coastline was late" flag),
    // so the check re-derives the chunk's birth certificate
    // from its body — the same math proven offline in the
    // NW(22)/NW(23) save audits:
    //   1. height fingerprint: saved surface vs what the
    //      CURRENT rules produce for the same columns. Offset
    //      ~0 = built by this build; a chunk sitting metres
    //      above the current book (NW(23): +2.5..+5.3 m) is an
    //      OLD-BOOK / OLD-JAR suspect.
    //   2. lid test: the player chunk's saved median surface
    //      vs its loaded neighbours' medians (the NW(20)
    //      "teleported chunk elevated" shape).
    //   3. shore audit: fresh rules say OCEAN but the world
    //      is dry = missing-sea / guessed-shore suspect (the
    //      Juhu NW(24) failure: COAST WAIT expired before the
    //      coastline batch landed). Honest downgrade: if the
    //      coastline map itself is not loaded NOW, this is
    //      reported INCONCLUSIVE, never as a verdict.
    //   4. biome census: all 256 saved biomes of the player
    //      chunk vs fresh rules (catches fossil biomes like
    //      the Juhu inland-desert chunk as a count, not just
    //      at one point).
    // Only already-loaded chunks are read (getChunkNow, never
    // force-load). The block snapshot runs on the server
    // thread; all classification runs async. Structures and
    // player edits can blur the fingerprint, so verdicts say
    // SUSPECT, not guilty.
    // ---------------------------------------------------------
    private static final class ChunkBuildSnap {
        final int cx, cz, minBX, minBZ;
        boolean loaded = false;
        final int[] groundY = new int[256];
        final int[] surfY = new int[256];
        final boolean[] hasWater = new boolean[256];
        final String[] biome = new String[256];
        final String[] groundName = new String[256];

        ChunkBuildSnap(int cx, int cz) {
            this.cx = cx;
            this.cz = cz;
            this.minBX = cx * 16;
            this.minBZ = cz * 16;
            Arrays.fill(groundY, Integer.MIN_VALUE);
            Arrays.fill(surfY, Integer.MIN_VALUE);
            Arrays.fill(biome, "unknown");
            Arrays.fill(groundName, "unknown");
        }
    }

    private static final class ChunkBuildStats {
        final ChunkBuildSnap snap;
        final List<Double> landDiffs = new ArrayList<>();
        final List<Double> savedLandY = new ArrayList<>();
        final List<Double> oceanDiffs = new ArrayList<>();
        int wetLand = 0;
        int freshOcean = 0, wetOcean = 0, dryOcean = 0;

        ChunkBuildStats(ChunkBuildSnap snap) {
            this.snap = snap;
        }
    }

    private static double medianOf(List<Double> vals) {
        if (vals == null || vals.isEmpty()) return Double.NaN;
        double[] a = new double[vals.size()];
        for (int i = 0; i < a.length; i++) a[i] = vals.get(i);
        Arrays.sort(a);
        int mid = a.length / 2;
        return a.length % 2 == 1 ? a[mid]
                : (a[mid - 1] + a[mid]) / 2.0;
    }

    private static int pctWithin(List<Double> vals, double tol) {
        if (vals == null || vals.isEmpty()) return 0;
        int inside = 0;
        for (double v : vals) if (Math.abs(v) <= tol) inside++;
        return (int) Math.round(100.0 * inside / vals.size());
    }

    private static int chunkBuildHere(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            line(source, "  chunk build check needs a player"
                    + " (run /terracheck in game).",
                    ChatFormatting.RED);
            return 0;
        }
        TerraChunkGenerator gen = TerraChunkGenerator.activeInstance;
        if (gen == null) {
            line(source, "  chunk build check: Earth generator"
                    + " NOT ACTIVE (not a Terra world?)",
                    ChatFormatting.RED);
            return 0;
        }
        if (!(player.level() instanceof ServerLevel level)) {
            log("CHUNK BUILD: player is not in a server level"
                    + " — skipped");
            return 0;
        }
        MinecraftServer server = source.getServer();
        if (server == null) {
            log("CHUNK BUILD: no server — skipped");
            return 0;
        }
        var cp = player.chunkPosition();
        final int pcx = cp.x, pcz = cp.z;
        line(source, "  chunk build check [chunk " + pcx + ","
                + pcz + " + neighbours] — full report in the"
                + " log, search CHUNK BUILD", ChatFormatting.GRAY);
        server.execute(() -> {
            try {
                List<ChunkBuildSnap> snaps = new ArrayList<>();
                BlockPos.MutableBlockPos pos =
                        new BlockPos.MutableBlockPos();
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        int cx = pcx + dx, cz = pcz + dz;
                        ChunkBuildSnap snap =
                                new ChunkBuildSnap(cx, cz);
                        snaps.add(snap);
                        var chunk = level.getChunkSource()
                                .getChunkNow(cx, cz);
                        if (chunk == null) continue;
                        snap.loaded = true;
                        for (int lx = 0; lx < 16; lx++) {
                            for (int lz = 0; lz < 16; lz++) {
                                int idx = lx * 16 + lz;
                                int bx = snap.minBX + lx;
                                int bz = snap.minBZ + lz;
                                int ws = chunk.getHeight(
                                        Heightmap.Types.WORLD_SURFACE,
                                        lx, lz);
                                int of = chunk.getHeight(
                                        Heightmap.Types.OCEAN_FLOOR,
                                        lx, lz);
                                snap.surfY[idx] = ws - 1;
                                snap.groundY[idx] = of - 1;
                                boolean water = false;
                                try {
                                    BlockState top = chunk
                                            .getBlockState(pos.set(
                                                    bx, snap.surfY[idx],
                                                    bz));
                                    if (top.is(net.minecraft.world
                                            .level.block.Blocks.WATER)) {
                                        water = true;
                                    } else if (snap.surfY[idx] - 1
                                            > snap.groundY[idx]) {
                                        BlockState below = chunk
                                                .getBlockState(pos.set(
                                                        bx,
                                                        snap.surfY[idx] - 1,
                                                        bz));
                                        if (below.is(net.minecraft
                                                .world.level.block
                                                .Blocks.WATER)) {
                                            water = true;
                                        }
                                    }
                                } catch (Throwable ignored) {}
                                snap.hasWater[idx] = water;
                                try {
                                    snap.groundName[idx] = blockName(
                                            chunk.getBlockState(pos.set(
                                                    bx, snap.groundY[idx],
                                                    bz)));
                                } catch (Throwable ignored) {}
                                try {
                                    int by = Math.max(
                                            level.getMinBuildHeight(),
                                            Math.min(
                                                    level.getMaxBuildHeight()
                                                            - 1,
                                                    snap.surfY[idx]));
                                    snap.biome[idx] = level.getBiome(
                                                    pos.set(bx, by, bz))
                                            .unwrapKey()
                                            .map(k -> k.location()
                                                    .toString())
                                            .orElse("unknown");
                                } catch (Throwable ignored) {}
                            }
                        }
                    }
                }
                CompletableFuture.runAsync(() ->
                        analyseChunkBuild(source, gen, snaps,
                                pcx, pcz));
            } catch (Throwable t) {
                log("CHUNK BUILD snapshot ERROR "
                        + t.getClass().getSimpleName() + ": "
                        + t.getMessage());
            }
        });
        return 1;
    }

    private static void analyseChunkBuild(CommandSourceStack source,
            TerraChunkGenerator gen, List<ChunkBuildSnap> snaps,
            int pcx, int pcz) {
        try {
            TerraDensityFunctions df = gen.getDfPublic();
            if (df == null) {
                log("CHUNK BUILD: density functions not ready"
                        + " — skipped");
                return;
            }
            TerraBiomeProvider bp = null;
            EsaWorldCoverIndex esa = null;
            try {
                bp = gen.getBiomeProviderPublic();
                esa = gen.getWorldCoverPublic();
            } catch (Throwable ignored) {}

            List<ChunkBuildStats> all = new ArrayList<>();
            ChunkBuildStats mine = null;
            int biomeCompared = 0, biomeMismatch = 0;
            int esaUnknown = 0;
            List<String> biomeExamples = new ArrayList<>();

            for (ChunkBuildSnap snap : snaps) {
                ChunkBuildStats st = new ChunkBuildStats(snap);
                all.add(st);
                if (snap.cx == pcx && snap.cz == pcz) mine = st;
                if (!snap.loaded) {
                    log("CHUNK BUILD [" + snap.cx + "," + snap.cz
                            + "]: not loaded — skipped");
                    continue;
                }
                boolean isMine = snap.cx == pcx && snap.cz == pcz;
                for (int lx = 0; lx < 16; lx++) {
                    for (int lz = 0; lz < 16; lz++) {
                        int idx = lx * 16 + lz;
                        int bx = snap.minBX + lx;
                        int bz = snap.minBZ + lz;
                        TerraDensityFunctions.TerrainColumn col;
                        try {
                            col = df.classify(bx, bz);
                        } catch (Throwable t) {
                            continue;
                        }
                        if (col == null) continue;
                        if (col.type == TerraDensityFunctions
                                .TerrainType.LAND) {
                            if (snap.hasWater[idx]) {
                                st.wetLand++;
                            } else if (snap.groundY[idx]
                                    != Integer.MIN_VALUE) {
                                st.landDiffs.add((double) (
                                        snap.groundY[idx]
                                                - col.surfaceY));
                                st.savedLandY.add(
                                        (double) snap.groundY[idx]);
                            }
                        } else if (col.type == TerraDensityFunctions
                                .TerrainType.OCEAN
                                || col.type == TerraDensityFunctions
                                        .TerrainType.SHALLOW_WATER) {
                            st.freshOcean++;
                            if (snap.hasWater[idx]) {
                                st.wetOcean++;
                                if (snap.groundY[idx]
                                        != Integer.MIN_VALUE) {
                                    st.oceanDiffs.add((double) (
                                            snap.groundY[idx]
                                                    - col.surfaceY));
                                }
                            } else {
                                st.dryOcean++;
                            }
                        }
                        if (isMine && bp != null && esa != null) {
                            try {
                                var wc = esa.getClass(
                                        col.latitude, col.longitude);
                                if (wc == EsaWorldCoverIndex
                                        .WorldCoverClass.UNKNOWN) {
                                    esaUnknown++;
                                }
                                boolean shore = false;
                                try {
                                    shore = gen.isShoreCellPublic(
                                            bx, bz);
                                } catch (Throwable ignored) {}
                                var holder = bp.getBiomeForLocation(
                                        col.latitude, col.longitude,
                                        col.elevationMeters, col.type,
                                        wc, shore);
                                String expected = holder != null
                                        ? holder.unwrapKey()
                                                .map(k -> k.location()
                                                        .toString())
                                                .orElse("?")
                                        : "null";
                                biomeCompared++;
                                if (!expected.equals(
                                        snap.biome[idx])) {
                                    biomeMismatch++;
                                    if (biomeExamples.size() < 6) {
                                        biomeExamples.add(String.format(
                                                "[%d,%d] saved=%s "
                                                + "fresh=%s type=%s "
                                                + "elev=%.0fm wc=%s",
                                                bx, bz, snap.biome[idx],
                                                expected, col.type,
                                                col.elevationMeters,
                                                wc));
                                    }
                                }
                            } catch (Throwable ignored) {}
                        }
                    }
                }
                double landMed = medianOf(st.landDiffs);
                double savedMed = medianOf(st.savedLandY);
                double oceanMed = medianOf(st.oceanDiffs);
                log(String.format(
                        "CHUNK BUILD [%d,%d]: loaded=%s "
                        + "landSamples=%d medianDiff=%s within3=%d%% "
                        + "savedMedianY=%s wetLandCols=%d "
                        + "freshOceanCols=%d wet=%d dry=%d "
                        + "oceanFloorMedianDiff=%s",
                        snap.cx, snap.cz, snap.loaded,
                        st.landDiffs.size(),
                        Double.isNaN(landMed) ? "n/a"
                                : String.format("%+.1f m", landMed),
                        pctWithin(st.landDiffs, 3.0),
                        Double.isNaN(savedMed) ? "n/a"
                                : String.format("%.0f", savedMed),
                        st.wetLand, st.freshOcean, st.wetOcean,
                        st.dryOcean,
                        Double.isNaN(oceanMed) ? "n/a"
                                : String.format("%+.1f m", oceanMed)));
            }

            if (mine == null || !mine.snap.loaded) {
                line(source, "  CHUNK BUILD VERDICT: your chunk"
                        + " is not loaded — skipped",
                        ChatFormatting.YELLOW);
                return;
            }

            // Lid test: player chunk saved median vs neighbours.
            List<Double> neighbourMedians = new ArrayList<>();
            for (ChunkBuildStats st : all) {
                if (st == mine || !st.snap.loaded) continue;
                double m = medianOf(st.savedLandY);
                if (!Double.isNaN(m)) neighbourMedians.add(m);
            }
            double mySavedMed = medianOf(mine.savedLandY);
            double neighbourMed = medianOf(neighbourMedians);
            double lidDelta = (Double.isNaN(mySavedMed)
                    || Double.isNaN(neighbourMed))
                    ? Double.NaN : mySavedMed - neighbourMed;

            // Shore readiness NOW (honesty gate for the verdict).
            boolean coastLoaded = false;
            int coastSegs = -1;
            String coastSide = "UNKNOWN";
            try {
                double[] cg = df.toGeo(mine.snap.minBX + 8,
                        mine.snap.minBZ + 8);
                if (cg != null) {
                    var osm = gen.getRlePublic().getOsmWaterIndex();
                    coastLoaded = osm.isTileCached(
                            osm.getTileKeyPublic(cg[1], cg[0]));
                    coastSegs = osm.coastSegCountAround(cg[1], cg[0]);
                    coastSide = String.valueOf(
                            osm.classifyCoastlineMemOnly(cg[1], cg[0]));
                }
            } catch (Throwable ignored) {}
            log("CHUNK BUILD shore witness NOW: coastlineTile="
                    + (coastLoaded ? "LOADED" : "NOT LOADED")
                    + " segmentsAround=" + coastSegs
                    + " centreSide=" + coastSide);

            List<String> flags = new ArrayList<>();
            List<String> notes = new ArrayList<>();

            double landMed = medianOf(mine.landDiffs);
            int within3 = pctWithin(mine.landDiffs, 3.0);
            String fingerText;
            if (mine.landDiffs.size() >= 20) {
                if (Math.abs(landMed) <= 2.0 && within3 >= 70) {
                    fingerText = String.format(
                            "MATCHES current rules (median %+.1f m,"
                            + " %d%% within 3 m)", landMed, within3);
                } else if (landMed >= 3.0) {
                    fingerText = String.format(
                            "RAISED vs current rules (median %+.1f m)"
                            + " — OLD-BOOK/OLD-JAR or fallback"
                            + " SUSPECT", landMed);
                    flags.add("height fingerprint raised "
                            + String.format("%+.1f m", landMed)
                            + " vs current rules (old-book/old-jar"
                            + " suspect)");
                } else if (landMed <= -3.0) {
                    fingerText = String.format(
                            "LOWERED vs current rules (median %+.1f m)"
                            + " — SUSPECT", landMed);
                    flags.add("height fingerprint lowered "
                            + String.format("%+.1f m", landMed)
                            + " vs current rules");
                } else {
                    fingerText = String.format(
                            "MIXED (median %+.1f m, only %d%% within"
                            + " 3 m) — does not cleanly match the"
                            + " current rules; structures, roads or"
                            + " player edits can blur this",
                            landMed, within3);
                    flags.add("height fingerprint MIXED (median "
                            + String.format("%+.1f m", landMed)
                            + ", " + within3 + "% within 3 m)");
                }
            } else {
                fingerText = "SKIPPED — only "
                        + mine.landDiffs.size()
                        + " dry land columns (ocean/water chunk?)";
            }

            if (!Double.isNaN(lidDelta)) {
                if (lidDelta >= 3.0) {
                    flags.add("LID SUSPECT — this chunk sits "
                            + String.format("%+.1f m", lidDelta)
                            + " above its neighbours");
                } else if (lidDelta <= -3.0) {
                    flags.add("SUNKEN SUSPECT — this chunk sits "
                            + String.format("%+.1f m", lidDelta)
                            + " below its neighbours");
                }
            }

            String shoreText;
            if (mine.freshOcean >= 16) {
                if (mine.dryOcean >= 16
                        && mine.dryOcean * 2 >= mine.freshOcean) {
                    if (coastLoaded) {
                        shoreText = "MISSING-SEA / GUESSED-SHORE"
                                + " SUSPECT — fresh rules say ocean"
                                + " for " + mine.freshOcean
                                + " columns but the world is dry in "
                                + mine.dryOcean + " of them";
                        flags.add("fresh rules say ocean for "
                                + mine.freshOcean + " columns but "
                                + mine.dryOcean
                                + " are dry in the world"
                                + " (missing-sea / guessed shore)");
                    } else {
                        shoreText = "sea mismatch ("
                                + mine.dryOcean + "/" + mine.freshOcean
                                + " fresh-ocean columns dry) but the"
                                + " coastline map is NOT LOADED now"
                                + " — INCONCLUSIVE, wait for the"
                                + " coastline tile and rerun";
                        notes.add(shoreText);
                    }
                } else {
                    shoreText = "ocean columns wet " + mine.wetOcean
                            + "/" + mine.freshOcean + " (dry "
                            + mine.dryOcean + ")";
                }
            } else {
                shoreText = "no real sea in this chunk (fresh ocean"
                        + " columns=" + mine.freshOcean + ")";
            }

            String biomeText;
            if (biomeCompared < 128) {
                biomeText = "SKIPPED — biome provider/ESA not ready"
                        + " (" + biomeCompared + " columns compared)";
            } else if (esaUnknown > 128) {
                biomeText = "INCONCLUSIVE — ESA land cover UNKNOWN"
                        + " for " + esaUnknown
                        + "/256 columns; rerun after the tile lands";
                notes.add(biomeText);
            } else if (biomeMismatch >= 32) {
                biomeText = "FOSSIL SUSPECT — saved biome differs"
                        + " from fresh rules in " + biomeMismatch
                        + "/" + biomeCompared + " columns";
                flags.add("biome fossil suspect: " + biomeMismatch
                        + "/" + biomeCompared
                        + " columns differ from fresh rules");
            } else {
                biomeText = "biomes match fresh rules in "
                        + (biomeCompared - biomeMismatch) + "/"
                        + biomeCompared + " columns";
            }
            for (String ex : biomeExamples) {
                log("CHUNK BUILD BIOME DIFF " + ex);
            }

            line(source, String.format(
                    "  CHUNK BUILD [%d,%d]: %s | saved median Y %s"
                    + " vs neighbours %s%s",
                    pcx, pcz, fingerText,
                    Double.isNaN(mySavedMed) ? "n/a"
                            : String.format("%.0f", mySavedMed),
                    Double.isNaN(neighbourMed) ? "n/a"
                            : String.format("%.0f", neighbourMed),
                    Double.isNaN(lidDelta) ? ""
                            : String.format(" (%+.1f m)", lidDelta)),
                    flags.isEmpty() ? ChatFormatting.GREEN
                            : ChatFormatting.YELLOW);
            line(source, "  CHUNK BUILD shore/biome: " + shoreText
                    + " | " + biomeText,
                    flags.isEmpty() ? ChatFormatting.GREEN
                            : ChatFormatting.YELLOW);
            if (flags.isEmpty()) {
                line(source, "  => CHUNK BUILD VERDICT: OK — this"
                        + " chunk looks built by the current rules"
                        + (notes.isEmpty() ? ""
                                : " (notes in log)"),
                        ChatFormatting.GREEN);
            } else {
                line(source, "  => CHUNK BUILD VERDICT: CHECK — "
                        + String.join("; ", flags)
                        + " (full per-chunk table in log)",
                        ChatFormatting.RED);
            }
            for (String n : notes) {
                log("CHUNK BUILD NOTE: " + n);
            }
        } catch (Throwable t) {
            log("CHUNK BUILD ERROR " + t.getClass().getSimpleName()
                    + ": " + t.getMessage());
        }
    }

    private static long countPngs(Path dir) {
        try {
            if (dir == null || !Files.isDirectory(dir)) return 0;
            try (var walk = Files.walk(dir)) {
                return walk.filter(p -> Files.isRegularFile(p)
                        && p.toString().endsWith(".png"))
                        .limit(500_000).count();
            }
        } catch (Throwable t) {
            return 0;
        }
    }

    private static long countLegacyPngs(Path tilesRoot) {
        try {
            if (tilesRoot == null || !Files.isDirectory(tilesRoot))
                return 0;
            try (var walk = Files.walk(tilesRoot)) {
                return walk.filter(p -> Files.isRegularFile(p)
                        && p.toString().endsWith(".png")
                        && !p.toString().contains("mapterhorn-v1")
                        && !p.toString().contains("mapterhorn-v2")
                        && !p.toString().contains("Terrarium-y-v1"))
                        .limit(500_000).count();
            }
        } catch (Throwable t) {
            return 0;
        }
    }

    // ---------------------------------------------------------
    // /terracheck fetch — async reachability probes
    // ---------------------------------------------------------
    // ---------------------------------------------------------
    // BIOME CHECKER — part of plain /terracheck (2026-11-20,
    // owner: "use only /terracheck"). Runs automatically in
    // all() for where the player stands. Explains the place:
    // climate numbers, satellite land cover, the biome the
    // rules choose NOW, the ground family it paints,
    // karst/cave context, and red-flag warnings when the
    // answer smells wrong. Also compares with the biome
    // actually saved in the world (old-chunk trap).
    // ---------------------------------------------------------
    private static int biomeHere(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            line(source, "  biome check needs a player"
                    + " (run /terracheck in game).",
                    ChatFormatting.RED);
            return 0;
        }
        TerraChunkGenerator gen = TerraChunkGenerator.activeInstance;
        if (gen == null) {
            line(source, "  Earth generator: NOT ACTIVE"
                    + " (not a Terra world?)", ChatFormatting.RED);
            return 0;
        }
        double[] geo = geoOf(gen, player.getBlockX(),
                player.getBlockZ());
        if (geo == null) {
            line(source, "  biome check: projection not ready.",
                    ChatFormatting.RED);
            return 0;
        }
        String worldBiome = null;
        try {
            worldBiome = source.getLevel()
                    .getBiome(player.blockPosition()).unwrapKey()
                    .map(k -> k.location().toString()).orElse("?");
        } catch (Throwable ignored) {}
        biomeCheck(source, gen, geo[1], geo[0], worldBiome);
        return 1;
    }

    private static void biomeCheck(CommandSourceStack source,
            TerraChunkGenerator gen, double lat, double lon,
            String worldBiome) {
        line(source, String.format(
                "  biome check @ %.6f, %.6f — full report in"
                + " the log, search [TerraCheck]", lat, lon),
                ChatFormatting.GRAY);
        CompletableFuture.runAsync(() -> {
            try {
                TerraBiomeProvider bp = gen.getBiomeProviderPublic();
                EsaWorldCoverIndex esa = gen.getWorldCoverPublic();
                TerraDensityFunctions df = gen.getDfPublic();
                if (bp == null || esa == null || df == null) {
                    log("BIOME CHECK: provider/ESA/elevation"
                            + " not ready — skipped");
                    return;
                }
                double[] bxz = df.fromGeo(lat, lon);
                int bx = bxz != null ? (int) Math.floor(bxz[0]) : 0;
                int bz = bxz != null ? (int) Math.floor(bxz[1]) : 0;
                var col = bxz != null ? df.classify(bx, bz) : null;
                double elev = col != null ? col.elevationMeters
                        : df.getElevationFast(lat, lon);
                var tt = col != null ? col.type
                        : TerraDensityFunctions.TerrainType.INVALID;
                double[] clim = bp.getClimateSample(lat, lon);
                double precip = clim[1], temp = clim[2];
                var wc = esa.getClass(lat, lon);
                boolean shore = false;
                try { shore = gen.isShoreCellPublic(bx, bz); }
                catch (Throwable ignored) {}
                var holder = bp.getBiomeForLocation(
                        lat, lon, elev, tt, wc, shore);
                String biome = holder != null ? holder.unwrapKey()
                        .map(k -> k.location().toString()).orElse("?")
                        : "null";
                int ground = bp.getBiomeTypeCode(lat, lon, elev, wc);
                int karst = KarstData.score(lat, lon);
                int cavesNear = 0;
                try {
                    cavesNear = CaveData.inBox(lat - 0.01, lat + 0.01,
                            lon - 0.01, lon + 0.01).size();
                } catch (Throwable ignored) {}

                log(String.format("BIOME CHECK @ %.6f, %.6f"
                        + " (block %d, %d)", lat, lon, bx, bz));
                log(String.format("  terrain=%s elevation=%.0f m"
                        + " shore=%s", tt, elev, shore));
                log(String.format("  climate: temp=%.1f C"
                        + " rain=%.0f mm/yr (%s) soil=%.0f",
                        temp, precip, bp.describeAridity(precip),
                        clim[0]));
                log(String.format("  land cover (ESA): %s", wc));
                log(String.format("  karst score=%d/100 (%s);"
                        + " known cave points within ~1 km: %d",
                        karst,
                        karst >= KarstData.KARST_MIN_SCORE
                            ? "karst — caves possible"
                            : "no karst caves",
                        cavesNear));
                log(String.format("  => BIOME: %s", biome));
                log(String.format("  => GROUND: %s",
                        codeName(ground)));

                java.util.List<String> warn =
                        new java.util.ArrayList<>();
                if (biome.contains("badlands") && precip < 50)
                    warn.add("mesa/badlands in HYPER-ARID land"
                            + " — the Sahara rule (2026-11-20)"
                            + " makes this DESERT in fresh"
                            + " chunks; suspect OLD CHUNK or"
                            + " an old jar");
                if (ground == 2 && precip < 50)
                    warn.add("mesa ground paint in hyper-arid"
                            + " land — same Sahara-rule check");
                if (biome.contains("desert") && precip >= 400)
                    warn.add("desert biome but rain is "
                            + Math.round(precip)
                            + " mm/yr — too wet for that name");
                if ((biome.contains("snowy")
                        || biome.contains("frozen")
                        || biome.contains("ice")) && temp > 8)
                    warn.add("frozen biome but climate temp is "
                            + String.format("%.1f", temp) + " C");
                if (biome.contains("jungle") && precip < 1200)
                    warn.add("jungle biome but rain only "
                            + Math.round(precip) + " mm/yr");
                if (wc == EsaWorldCoverIndex.WorldCoverClass.BARE
                        && precip < 150
                        && (biome.contains("savanna")
                            || biome.contains("plains")
                            || biome.contains("forest")))
                    warn.add("green biome (" + biome + ") on"
                            + " bare, dry land — inspect the"
                            + " ground blocks here");
                if (worldBiome != null
                        && !worldBiome.equals(biome))
                    warn.add("world chunk here says " + worldBiome
                            + " but fresh rules say " + biome
                            + " — chunk was probably generated"
                            + " by an older build; fresh chunks"
                            + " will use the new answer");
                if (warn.isEmpty())
                    log("  verdict: OK — biome, climate, land"
                            + " cover and ground all agree");
                else
                    for (String w : warn) log("  ⚠ " + w);
                line(source, String.format(
                        "  biome @ %.4f, %.4f => %s | ground %s"
                        + " | %.0f m, %.0f mm/yr, ESA %s%s",
                        lat, lon, biome, codeName(ground), elev,
                        precip, wc,
                        warn.isEmpty() ? " | OK"
                            : " | ⚠ " + warn.size()
                                + " warning(s) in log"),
                        warn.isEmpty() ? ChatFormatting.GREEN
                            : ChatFormatting.YELLOW);
            } catch (Throwable t) {
                log("BIOME CHECK ERROR "
                        + t.getClass().getSimpleName() + ": "
                        + t.getMessage());
            }
        });
    }

    private static int fetch(CommandSourceStack source) {
        long now = System.currentTimeMillis();
        long last = LAST_FETCH.get();
        if (now - last < FETCH_COOLDOWN_MS) {
            line(source, "  Probes already ran " + ((now - last) / 1000)
                    + "s ago — wait for the 30s cooldown.",
                    ChatFormatting.YELLOW);
            return 0;
        }
        LAST_FETCH.set(now);
        line(source, "  Asking all " + PROBES.length + " doors "
                + "to answer (runs off-thread, results in a few "
                + "seconds)…", ChatFormatting.GRAY);

        MinecraftServer server = source.getServer();
        CompletableFuture.runAsync(() -> {
            String[] results = new String[PROBES.length];
            for (int i = 0; i < PROBES.length; i++) {
                results[i] = probe(PROBES[i][0], PROBES[i][1]);
            }
            server.execute(() -> {
                for (String r : results) {
                    boolean ok = r.contains("ANSWERS");
                    line(source, "  " + r,
                            ok ? ChatFormatting.GREEN : ChatFormatting.RED);
                }
                line(source, "  Note: ANSWERS means that door answered "
                        + "just now. It does not prove data for YOUR "
                        + "place arrived — the chunk test above (search "
                        + "[TerraCheck] in the log) is the proof for that.",
                        ChatFormatting.DARK_GRAY);
            });
        });
        return 1;
    }

    private static String probe(String name, String url) {
        long t0 = System.currentTimeMillis();
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(5_000);
            conn.setReadTimeout(8_000);
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", "TerraCheck/1.0");
            conn.setRequestProperty("Range", "bytes=0-2047");
            int code = conn.getResponseCode();
            long ms = System.currentTimeMillis() - t0;
            // Batch-2 fix: a server that answers 4xx/5xx has
            // NOT answered the question — batch 1 called an
            // HTTP 502 "ANSWERS", which was a lie.
            if (code >= 200 && code < 400) {
                return name + ": ANSWERS (HTTP " + code + ", " + ms + " ms)";
            }
            return name + ": NO ANSWER (HTTP " + code
                    + " — the server replied but refused/failed, "
                    + ms + " ms)";
        } catch (Exception e) {
            long ms = System.currentTimeMillis() - t0;
            return name + ": NO ANSWER (" + e.getClass().getSimpleName()
                    + ", " + ms + " ms)";
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    // ---------------------------------------------------------
    // /terracheck coast — multiple controlled tests to find
    // the ACTUAL beach/coastline slowdown instead of guessing.
    // It separates the suspects and times each one at the
    // player's current chunk:
    //   FETCH: is the coastline tile here? If not, how long
    //          does the shared wait used by generation take?
    //   SCAN:  how long does ONE "which side of the coastline
    //          am I on?" decision take, and how many coastline
    //          segments are loaded around here?
    //   ELEV:  how long does elevation alone take?
    //   CLASS: how long does one full classify() take?
    //   PAINT: how long does the generator's 64x64 classify
    //          grid for one chunk take (4,096 decisions)?
    //   BEACH: how long do the 16 biome columns + shore probes
    //          (isShoreCellPublic) take?
    //   ESA:   how long did the first ESA answer take, and how
    //          long does a warm ESA answer take?
    // Read-only: it classifies and times, but places nothing.
    // For clean numbers run /terracheck coast ALONE at a
    // beach (plain /terracheck also starts it, alongside the
    // other tests, which can add noise).
    // ---------------------------------------------------------
    private static int coastPerf(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            line(source, "  coast-test needs a player (run it in game).",
                    ChatFormatting.RED);
            return 0;
        }
        TerraChunkGenerator gen = TerraChunkGenerator.activeInstance;
        if (gen == null) {
            line(source, "  Earth generator: NOT ACTIVE (not a Terra world?)",
                    ChatFormatting.RED);
            return 0;
        }
        var cp = player.chunkPosition();
        final int px = player.getBlockX(), pz = player.getBlockZ();
        final int minBX = cp.getMinBlockX(), minBZ = cp.getMinBlockZ();
        final int chunkX = cp.x, chunkZ = cp.z;
        final MinecraftServer server = source.getServer();
        line(source, "  coast-test running off-thread for chunk ["
                + chunkX + "," + chunkZ + "] — full results in the"
                + " log, search TEST coastperf", ChatFormatting.GRAY);
        CompletableFuture.runAsync(() -> coastPerfAsync(
                source, server, gen, px, pz, minBX, minBZ, chunkX, chunkZ));
        return 1;
    }

    private static double coastAvgUs(long nanos, int count) {
        return count <= 0 ? 0.0 : (nanos / 1000.0) / count;
    }

    private static void coastPerfAsync(CommandSourceStack source,
            MinecraftServer server, TerraChunkGenerator gen,
            int px, int pz, int minBX, int minBZ, int chunkX, int chunkZ) {
        String summary;
        try {
            var df = gen.getDfPublic();
            if (df == null || !df.isProjectionLoaded()) {
                log("TEST coastperf: no density functions/projection"
                        + " — skipped");
                return;
            }
            var rle = gen.getRlePublic();
            var osm = rle == null ? null : rle.getOsmWaterIndex();
            var esa = gen.getWorldCoverPublic();

            var centre = df.classify(px, pz);
            if (centre == null || Double.isNaN(centre.latitude)
                    || Double.isNaN(centre.longitude)) {
                log("TEST coastperf: could not classify player position"
                        + " — skipped");
                return;
            }
            final double lat = centre.latitude, lon = centre.longitude;
            log(String.format(
                    "TEST coastperf BEGIN chunk=[%d,%d] block=[%d,%d]"
                    + " lat=%.6f lon=%.6f centreType=%s elev=%.1fm",
                    chunkX, chunkZ, px, pz, lat, lon,
                    centre.type, centre.elevationMeters));

            // ── TEST 1: FETCH STATE ──────────────────────────
            boolean cachedBefore = false;
            int segsBefore = -1;
            if (osm != null) {
                try {
                    cachedBefore = osm.isTileCached(
                            osm.getTileKeyPublic(lat, lon));
                    segsBefore = osm.coastSegCountAround(lat, lon);
                } catch (Exception ignored) {}
            }
            log(String.format(
                    "TEST coastperf FETCH-STATE before: osmPresent=%s"
                    + " osmTileCached=%s coastSegmentsAround=%d",
                    osm != null, cachedBefore, segsBefore));

            // ── TEST 2: FETCH WAIT (only if not already here) ─
            // Uses the exact shared wait generation uses, so the
            // number is the real COAST WAIT cost for tile #1.
            boolean cachedAfter = cachedBefore;
            long fetchMs = cachedBefore ? 0 : -1;
            if (osm != null && !cachedBefore) {
                long f0 = System.currentTimeMillis();
                try {
                    cachedAfter = osm.getTileBlockingShared(lat, lon, 12000L);
                } catch (Exception ignored) {}
                fetchMs = System.currentTimeMillis() - f0;
            }
            int segs = -1;
            String centreSide = "NO_OSM";
            if (osm != null) {
                try {
                    segs = osm.coastSegCountAround(lat, lon);
                    centreSide = String.valueOf(
                            osm.classifyCoastlineMemOnly(lat, lon));
                } catch (Exception ignored) {}
            }
            log(String.format(
                    "TEST coastperf FETCH test: cachedBefore=%s"
                    + " cachedAfter=%s waitMs=%d segmentsAround=%d"
                    + " centreCoastSide=%s",
                    cachedBefore, cachedAfter, fetchMs, segs, centreSide));

            // Warm-up: same JIT/tile-cache state for every test.
            for (int i = 0; i < 50; i++) {
                df.getElevationInterpolated(lat, lon);
                if (osm != null) osm.classifyCoastlineMemOnly(lat, lon);
                df.classify(px, pz);
            }

            // ── TEST 3: ONE COASTLINE DECISION (the scan) ────
            double scanUs = 0;
            if (osm != null) {
                int n = 2000;
                long t0 = System.nanoTime();
                for (int i = 0; i < n; i++)
                    osm.classifyCoastlineMemOnly(lat, lon);
                scanUs = coastAvgUs(System.nanoTime() - t0, n);
            }
            log(String.format(
                    "TEST coastperf SCAN test: one coastline decision"
                    + " avg=%.1f us (segmentsAround=%d)", scanUs, segs));

            // ── TEST 4: ELEVATION ALONE ──────────────────────
            int elevN = 1000;
            long eT0 = System.nanoTime();
            for (int i = 0; i < elevN; i++)
                df.getElevationInterpolated(lat, lon);
            double elevUs = coastAvgUs(System.nanoTime() - eT0, elevN);
            log(String.format(
                    "TEST coastperf ELEV test: one elevation sample"
                    + " avg=%.1f us", elevUs));

            // ── TEST 5: ONE FULL classify() ──────────────────
            int classN = 500;
            long cT0 = System.nanoTime();
            for (int i = 0; i < classN; i++) df.classify(px, pz);
            double classifyUs = coastAvgUs(System.nanoTime() - cT0, classN);
            log(String.format(
                    "TEST coastperf CLASSIFY test: one full classify"
                    + " avg=%.1f us (scan %.1f + elev %.1f = %.1f;"
                    + " rest is projection/other checks)",
                    classifyUs, scanUs, elevUs, scanUs + elevUs));

            // ── TEST 6: THE GENERATOR'S 64x64 PAINT GRID ─────
            // Exactly what generateTerrain classifies for one
            // chunk (chunk + 24-block border): 4,096 classify()
            // calls. This is the sustained per-chunk cost.
            int startX = minBX - 24, startZ = minBZ - 24;
            int land = 0, water = 0, other = 0, invalid = 0;
            long pT0 = System.nanoTime();
            for (int gx = 0; gx < 64; gx++) {
                for (int gz = 0; gz < 64; gz++) {
                    var c = df.classify(startX + gx, startZ + gz);
                    var t = c.type;
                    if (t == TerraDensityFunctions.TerrainType.LAND
                            || t == TerraDensityFunctions.TerrainType.COASTLINE
                            || t == TerraDensityFunctions.TerrainType.RIVER_BANK
                            || t == TerraDensityFunctions.TerrainType.LAKE_SHORE)
                        land++;
                    else if (t == TerraDensityFunctions.TerrainType.OCEAN
                            || t == TerraDensityFunctions.TerrainType.SHALLOW_WATER
                            || t == TerraDensityFunctions.TerrainType.RIVER
                            || t == TerraDensityFunctions.TerrainType.LAKE
                            || t == TerraDensityFunctions.TerrainType.BELOW_SEA_WATER)
                        water++;
                    else if (t == TerraDensityFunctions.TerrainType.INVALID)
                        invalid++;
                    else
                        other++;
                }
            }
            long painterNs = System.nanoTime() - pT0;
            double painterMs = painterNs / 1_000_000.0;
            double painterAvgUs = coastAvgUs(painterNs, 4096);
            boolean boundary = land > 0 && water > 0;
            log(String.format(
                    "TEST coastperf PAINT-GRID test: 4096 classify"
                    + " calls took %.1f ms (avg %.1f us) — land=%d"
                    + " water=%d other=%d invalid=%d boundaryChunk=%s",
                    painterMs, painterAvgUs, land, water, other,
                    invalid, boundary));

            // ── TEST 7: BIOME COLUMNS + BEACH PROBES ─────────
            long classify16Ns = 0, shoreNs = 0;
            int shoreTrue = 0;
            for (int bx = 0; bx < 4; bx++) {
                for (int bz = 0; bz < 4; bz++) {
                    int bxBlock = minBX + bx * 4;
                    int bzBlock = minBZ + bz * 4;
                    long t0 = System.nanoTime();
                    df.classify(bxBlock, bzBlock);
                    classify16Ns += System.nanoTime() - t0;
                    long s0 = System.nanoTime();
                    if (gen.isShoreCellPublic(bxBlock, bzBlock)) shoreTrue++;
                    shoreNs += System.nanoTime() - s0;
                }
            }
            double classify16Ms = classify16Ns / 1_000_000.0;
            double shoreMs = shoreNs / 1_000_000.0;
            log(String.format(
                    "TEST coastperf BEACH test: 16 biome classify"
                    + " took %.1f ms; isShoreCellPublic x16 took"
                    + " %.1f ms (shoreTrue=%d/16) — the shore test"
                    + " re-classifies the centre + up to 16 probes"
                    + " per column", classify16Ms, shoreMs, shoreTrue));

            // ── TEST 8: ESA FIRST ANSWER vs WARM ANSWER ──────
            long esaFirstMs = -1;
            String esaFirst = "NO_ESA";
            double esaUs = 0;
            if (esa != null) {
                long t0 = System.currentTimeMillis();
                var wc = esa.getClassBlocking(lat, lon, 6000L);
                esaFirstMs = System.currentTimeMillis() - t0;
                esaFirst = String.valueOf(wc);
                for (int i = 0; i < 20; i++) esa.getClass(lat, lon);
                int n = 1000;
                long n0 = System.nanoTime();
                for (int i = 0; i < n; i++) esa.getClass(lat, lon);
                esaUs = coastAvgUs(System.nanoTime() - n0, n);
            }
            log(String.format(
                    "TEST coastperf ESA test: first getClassBlocking"
                    + " = %s in %d ms; warm getClass avg=%.1f us",
                    esaFirst, esaFirstMs, esaUs));

            // ── VERDICT ──────────────────────────────────────
            String verdict;
            if (osm == null) {
                verdict = "NO OSM WATER INDEX — coastline cannot"
                        + " be tested in this world";
            } else if (!cachedAfter) {
                verdict = "FETCH PROBLEM — coastline tile still NOT"
                        + " ready after the shared 12 s wait"
                        + " (fetchMs=" + fetchMs + "); the other"
                        + " timings were measured in guessed-shore"
                        + " mode and must be re-run after the tile"
                        + " lands";
            } else if (!boundary) {
                verdict = "NOT A BEACH/COAST BOUNDARY CHUNK —"
                        + " this chunk's 64x64 grid has land=" + land
                        + " water=" + water + "; stand in a chunk"
                        + " that contains BOTH land/sand and sea,"
                        + " then re-run /terracheck coast";
            } else if (painterMs >= 100 && scanUs >= 15
                    && scanUs >= classifyUs * 0.70) {
                // NW(28) calibration: at Juhu the paint grid
                // measured 137 ms/chunk with the scan at 97% of
                // classify — the old 250 ms bar called that "no
                // single big cost", which was wrong. A scan that
                // dominates classify AND pushes the grid past
                // 100 ms/chunk is the generating problem.
                verdict = "GENERATING PROBLEM — the 64x64 paint"
                        + " grid costs " + String.format("%.0f", painterMs)
                        + " ms/chunk and the coastline scan is "
                        + String.format("%.0f", scanUs / Math.max(1.0, classifyUs) * 100.0)
                        + "% of classify (" + String.format("%.1f", scanUs)
                        + " us per decision over " + segs
                        + " segments): the repeated coastline scan"
                        + " is the main measured cost";
            } else if (shoreMs >= 100 && shoreMs >= painterMs * 0.35) {
                verdict = "GENERATING PROBLEM — beach shore"
                        + " probes are a major extra cost ("
                        + String.format("%.0f", shoreMs) + " ms per"
                        + " chunk on top of painting)";
            } else if (esaFirstMs >= 1000) {
                verdict = "WAIT PROBLEM — ESA's first answer took "
                        + esaFirstMs + " ms; cold beach chunks can"
                        + " park on it (painting/biome waits)";
            } else {
                verdict = "NO SINGLE BIG COST IN THIS CHUNK —"
                        + " compare the numbers: fetchMs=" + fetchMs
                        + " paintGridMs=" + String.format("%.0f", painterMs)
                        + " shoreProbeMs=" + String.format("%.0f", shoreMs)
                        + " esaFirstMs=" + esaFirstMs;
            }
            log("TEST coastperf VERDICT: " + verdict);
            log(String.format(
                    "TEST coastperf SUMMARY chunk=[%d,%d]"
                    + " fetchMs=%d segments=%d scanUs=%.1f"
                    + " elevUs=%.1f classifyUs=%.1f"
                    + " paintGridMs=%.1f paintAvgUs=%.1f"
                    + " biomeClassify16Ms=%.1f shoreProbeMs=%.1f"
                    + " shoreTrue=%d esaFirst=%s esaFirstMs=%d"
                    + " esaUs=%.1f boundary=%s",
                    chunkX, chunkZ, fetchMs, segs, scanUs, elevUs,
                    classifyUs, painterMs, painterAvgUs, classify16Ms,
                    shoreMs, shoreTrue, esaFirst, esaFirstMs, esaUs,
                    boundary));
            summary = "Coast test done: " + verdict;
        } catch (Throwable t) {
            log("TEST coastperf ERROR " + t.getClass().getSimpleName()
                    + ": " + t.getMessage());
            summary = "Coast test ERROR — see log (TEST coastperf)";
        }
        if (server != null) {
            final String s = summary;
            server.execute(() -> line(source, "  " + s,
                    s.contains("ERROR") ? ChatFormatting.RED
                            : ChatFormatting.AQUA));
        }
    }

    // ---------------------------------------------------------
    // /terracheck here — read-only test of the player's chunk.
    // Runs OFF the server thread (the Finding-1 lesson: never
    // block a tick) and answers only into the log:
    //   - do the three sources HAVE data for this chunk yet?
    //     OSM tiles loaded? Microsoft tiles loaded/missing?
    //     VIDA shard arrived, or still in flight (cold start)?
    //   - what does OSM actually hold here, counted by type,
    //     with the first structures described in full.
    // It places nothing and changes nothing.
    // ---------------------------------------------------------
    private static int here(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            line(source, "  here-test needs a player (run it in game).",
                    ChatFormatting.RED);
            return 0;
        }
        TerraChunkGenerator gen = TerraChunkGenerator.activeInstance;
        if (gen == null) {
            line(source, "  Earth generator: NOT ACTIVE (not a Terra world?)",
                    ChatFormatting.RED);
            return 0;
        }
        var cp = player.chunkPosition();
        final int chunkX = cp.x, chunkZ = cp.z;
        final int minBX = cp.getMinBlockX(), maxBX = cp.getMaxBlockX();
        final int minBZ = cp.getMinBlockZ(), maxBZ = cp.getMaxBlockZ();
        final TerraWorldConfig config = gen.getWorldConfig();
        line(source, "  here-test running off-thread for chunk ["
                + chunkX + "," + chunkZ + "] — results in the log,"
                + " search [TerraCheck]", ChatFormatting.GRAY);

        biomeGroundCheck(source, gen, player, chunkX, chunkZ,
                minBX, minBZ);

        coastCheck(gen, player);

        CompletableFuture.runAsync(() -> {
            try {
                double[][] corners = {
                    geoOf(gen, minBX, minBZ), geoOf(gen, maxBX, minBZ),
                    geoOf(gen, minBX, maxBZ), geoOf(gen, maxBX, maxBZ)};
                double minLat = Double.MAX_VALUE, maxLat = -Double.MAX_VALUE;
                double minLon = Double.MAX_VALUE, maxLon = -Double.MAX_VALUE;
                for (double[] g : corners) {
                    if (g == null) continue;
                    minLon = Math.min(minLon, g[0]);
                    maxLon = Math.max(maxLon, g[0]);
                    minLat = Math.min(minLat, g[1]);
                    maxLat = Math.max(maxLat, g[1]);
                }
                log(String.format(
                        "TEST here BEGIN chunk=[%d,%d] lat %.6f..%.6f"
                        + " lon %.6f..%.6f", chunkX, chunkZ,
                        minLat, maxLat, minLon, maxLon));
                log(String.format(
                        "TEST here config: buildings=%s roads=%s"
                        + " railways=%s bridges=%s vida=%s"
                        + " microsoft/overture=%s timejump=%s"
                        + " ohm=%s year=%d",
                        config.buildings, config.roads,
                        config.railways, config.bridges,
                        config.vidaBuildings,
                        config.overtureBuildings,
                        TerraTimeManager.isTimeEnabled(),
                        config.ohmEnabled,
                        TerraTimeManager.getCurrentYear()));

                // --- OSM ---
                OsmStructureIndex osm = gen.getStructureIndexPublic();
                if (osm == null) {
                    log("TEST here OSM: index is null");
                } else {
                    boolean loaded = osm.areTilesLoaded(
                            minLat, maxLat, minLon, maxLon);
                    List<OsmStructureIndex.OsmStructure> list =
                            osm.getStructuresForChunk(
                                    minLat, maxLat, minLon, maxLon, config);
                    EnumMap<OsmStructureIndex.StructureType, Integer>
                            byType = new EnumMap<>(
                                    OsmStructureIndex.StructureType.class);
                    for (OsmStructureIndex.OsmStructure s : list) {
                        byType.merge(s.type, 1, Integer::sum);
                    }
                    log(String.format(
                            "TEST here OSM: tilesLoaded=%s cacheTiles=%d"
                            + " structures=%d byType=%s",
                            loaded, osm.getCacheSize(),
                            list.size(), byType));
                    // Batch-2 fix: batch 1 listed the first 25
                    // in raw order and roads filled the list.
                    // Buildings and building-parts now come
                    // first (stable sort — original order kept
                    // inside each group).
                    List<OsmStructureIndex.OsmStructure> ordered =
                            new ArrayList<>(list);
                    ordered.sort(Comparator.comparingInt(
                            (OsmStructureIndex.OsmStructure s) ->
                                    s.type == OsmStructureIndex
                                            .StructureType.BUILDING ? 0
                                  : s.type == OsmStructureIndex
                                            .StructureType.BUILDING_PART ? 1
                                  : 2));
                    int shown = 0;
                    for (OsmStructureIndex.OsmStructure s : ordered) {
                        if (shown++ >= 25) break;
                        log("TEST here OSM item: " + describe(s));
                    }
                    if (ordered.size() > 25) {
                        log("TEST here OSM item: ... ("
                                + (ordered.size() - 25) + " more not shown)");
                    }
                    // Batch-2 new test TC-HEIGHTS: where do
                    // building heights come from in this chunk —
                    // a real tag, or a guess?
                    int bCount = 0, taggedLevels = 0,
                        taggedHeight = 0, guessed = 0, tallest = 0;
                    for (OsmStructureIndex.OsmStructure s : list) {
                        if (s.type != OsmStructureIndex.StructureType.BUILDING
                                && s.type != OsmStructureIndex
                                        .StructureType.BUILDING_PART) {
                            continue;
                        }
                        bCount++;
                        if (s.levels > 0) {
                            taggedLevels++;
                        } else if (s.height > 0) {
                            taggedHeight++;
                        } else {
                            guessed++;
                        }
                        tallest = Math.max(tallest,
                                s.resolvedHeightBlocks());
                    }
                    log(String.format(
                            "TEST here HEIGHTS: buildingsAndParts=%d"
                            + " taggedLevels=%d taggedHeightOnly=%d"
                            + " noTagGuessed=%d tallestBlocks=%d",
                            bCount, taggedLevels, taggedHeight,
                            guessed, tallest));
                    // The Charminar regression check, built into
                    // the plain /terracheck run (owner types
                    // nothing extra): how did the real parser
                    // classify way 238186115, if it is here?
                    for (OsmStructureIndex.OsmStructure s : list) {
                        if (s.osmId == 238186115L) {
                            log("TEST here CHARMINAR osmId=238186115:"
                                    + " FOUND -> " + describe(s));
                            break;
                        }
                    }
                }

                // --- Microsoft / Overture ---
                try {
                    MicrosoftIndex ms = MicrosoftIndex.getInstance();
                    boolean loaded = ms.areTilesLoaded(
                            minLat, maxLat, minLon, maxLon);
                    int missing = ms.countMissingTiles(
                            minLat, maxLat, minLon, maxLon);
                    List<MicrosoftIndex.MicrosoftBuilding> builds =
                            ms.getBuildingsForChunk(
                                    minLat, maxLat, minLon, maxLon);
                    log(String.format(
                            "TEST here MICROSOFT: tilesLoaded=%s"
                            + " missingTiles=%d cacheTiles=%d"
                            + " buildingsHere=%d totalBuildings=%d",
                            loaded, missing, ms.getCacheSize(),
                            builds == null ? -1 : builds.size(),
                            ms.getTotalBuildings()));
                    // Batch-2: batch 1 could not tell WHY
                    // Microsoft was empty. The index's own
                    // counters separate the four stories:
                    // switched off / never asked / asked but
                    // still waiting / tiles here but genuinely
                    // no buildings.
                    log(String.format(
                            "TEST here MICROSOFT counters: enabled=%s"
                            + " queries=%d msftHits=%d diskHits=%d"
                            + " emptyTiles=%d sources=%s",
                            ms.isEnabled(), ms.getTotalQueries(),
                            ms.getMsftHits(), ms.getDiskHits(),
                            ms.getEmptyTiles(),
                            ms.getSourceBreakdown()));
                    // Batch-3: batch 2 could only say "asked, still
                    // waiting". Now the download itself reports:
                    // lines read so far, its age, and how long since
                    // it last moved. Rising lines between two checks
                    // = slow but alive; frozen lines with growing
                    // ages = genuinely stuck.
                    String dlState = ms.getDownloadState();
                    log("TEST here MICROSOFT download: indexLoaded="
                            + ms.isIndexLoaded() + " state=" + dlState);
                    String msVerdict;
                    if (!ms.isEnabled()) {
                        msVerdict = "SWITCHED OFF — Microsoft will"
                                + " never fill gaps in this world";
                    } else if (builds != null && !builds.isEmpty()) {
                        msVerdict = "BUILDINGS HERE (" + builds.size()
                                + ") — Microsoft data has arrived"
                                + " for this chunk";
                    } else if (ms.getTotalQueries() == 0) {
                        msVerdict = "NEVER ASKED — no Microsoft query"
                                + " has run yet this session, so"
                                + " nothing could have arrived";
                    } else if (dlState.startsWith("DOWNLOADING")) {
                        msVerdict = "DOWNLOADING NOW — " + dlState
                                + ". Run /terracheck again in"
                                + " 30-60 s: lines must RISE. Frozen"
                                + " lines with growing age = STUCK"
                                + " download (batch-3 should stream"
                                + " it to disk instead)";
                    } else if (missing > 0) {
                        msVerdict = "ASKED, NOT DOWNLOADING, NOT"
                                + " CACHED — " + missing
                                + " tile(s) missing and no download"
                                + " is running (state above). Search"
                                + " this log for MICROSOFT FAILED:"
                                + " if present, that is the cause; if"
                                + " absent, the download never"
                                + " started for this area";
                    } else {
                        msVerdict = "TILES HERE, NO BUILDINGS — the"
                                + " data arrived and this chunk is"
                                + " genuinely empty in Microsoft";
                    }
                    log("TEST here MICROSOFT VERDICT: " + msVerdict);
                } catch (Throwable t) {
                    log("TEST here MICROSOFT: ERROR "
                            + t.getClass().getSimpleName()
                            + ": " + t.getMessage());
                }

                // --- VIDA (source.coop) ---
                try {
                    SourceCooperativeIndex vida =
                            SourceCooperativeIndex.getInstance();
                    List<SourceCooperativeIndex.VidaBuilding> builds =
                            vida.getBuildingsForChunk(
                                    minLat, maxLat, minLon, maxLon);
                    if (builds == null) {
                        log("TEST here VIDA: shard NOT LOADED YET"
                                + " (fetch in flight — the cold-start"
                                + " moment) cachedShards="
                                + vida.getCacheSize());
                    } else {
                        log(String.format(
                                "TEST here VIDA: buildingsHere=%d"
                                + " cachedShards=%d"
                                + " totalCachedBuildings=%d",
                                builds.size(), vida.getCacheSize(),
                                vida.getBuildingCount()));
                    }
                    // Batch-3: is a fetch actually in flight?
                    // pendingRegions>0 = DOWNLOADING (batch-3
                    // prefetch starts it at warmup, not at first
                    // decoration); 0 pending + 0 cached = NOT
                    // ASKED YET in this area.
                    log(String.format(
                            "TEST here VIDA state: cachedRegions=%d"
                            + " pendingRegions=%d — buildingsHere>0"
                            + " = READY; pendingRegions>0 ="
                            + " DOWNLOADING; both 0 here = NOT"
                            + " ASKED YET for this area",
                            vida.getCacheSize(),
                            vida.getPendingCount()));
                } catch (Throwable t) {
                    log("TEST here VIDA: ERROR "
                            + t.getClass().getSimpleName()
                            + ": " + t.getMessage());
                }
                // --- Async refill road (batch-2 test TC-REFILL) ---
                // The cold-start question: when a chunk is born
                // before its data arrives, does the refill road
                // later finish it? This asks the refill road
                // what it holds for THIS chunk right now.
                try {
                    if (StructureTickHandler.isInitialized()) {
                        AsyncStructureManager async =
                                StructureTickHandler.getAsyncManager();
                        StructurePlacementQueue pq =
                                StructureTickHandler.getPlacementQueue();
                        double cLat = (minLat + maxLat) / 2.0;
                        double cLon = (minLon + maxLon) / 2.0;
                        List<OsmStructureIndex.OsmStructure> done =
                                async == null ? null
                                : async.getCompletedStructures(
                                        cLat, cLon,
                                        minLat, maxLat, minLon, maxLon);
                        log(String.format(
                                "TEST here REFILL: pending=%d"
                                + " completedTiles=%d retries=%d"
                                + " successes=%d queueActive=%d"
                                + " queueDeferred=%d queuePlaced=%d"
                                + " refillStructuresForThisChunk=%s",
                                async == null ? -1 : async.getPendingCount(),
                                async == null ? -1 : async.getCompletedCount(),
                                async == null ? -1 : async.getTotalRetries(),
                                async == null ? -1 : async.getTotalSuccesses(),
                                pq == null ? -1 : pq.getActiveQueueSize(),
                                pq == null ? -1 : pq.getDeferredSize(),
                                pq == null ? -1 : pq.getTotalPlaced(),
                                done == null
                                    ? "NONE YET (this chunk's tile is"
                                      + " still pending/retrying)"
                                    : String.valueOf(done.size())));
                        // Batch-3 verdict: batch 2 showed
                        // successes=0 forever because Microsoft's
                        // missing answer vetoed every tile. Now a
                        // tile must complete within minutes even
                        // if Microsoft never answers (counted
                        // separately as completedWithoutMsft).
                        if (async != null) {
                            log(String.format(
                                    "TEST here REFILL degraded:"
                                    + " completedWithoutMsft=%d"
                                    + " completedWithoutVida=%d",
                                    async.getTilesCompletedWithoutMsft(),
                                    async.getTilesCompletedWithoutVida()));
                            if (async.getTotalSuccesses() > 0) {
                                log("TEST here REFILL VERDICT:"
                                        + " WORKS — "
                                        + async.getTotalSuccesses()
                                        + " tile(s) completed and"
                                        + " their buildings were"
                                        + " queued for placement"
                                        + " (queuePlaced above)."
                                        + " Late VIDA/Microsoft can"
                                        + " no longer block this"
                                        + " road");
                            } else if (async.getPendingCount() > 0) {
                                log("TEST here REFILL VERDICT:"
                                        + " STILL WAITING — tiles"
                                        + " pending. With batch 3"
                                        + " they must complete within"
                                        + " a few minutes even if"
                                        + " Microsoft never answers."
                                        + " If successes is still 0"
                                        + " here after ~5 minutes in"
                                        + " this area, the refill"
                                        + " road is still broken");
                            } else {
                                log("TEST here REFILL VERDICT:"
                                        + " NOTHING QUEUED — no chunk"
                                        + " here has needed refill"
                                        + " (all data was ready at"
                                        + " generation, or generation"
                                        + " has not run here yet)");
                            }
                        }
                    } else {
                        log("TEST here REFILL: structure pipeline not"
                                + " initialised yet");
                    }
                } catch (Throwable t) {
                    log("TEST here REFILL: ERROR "
                            + t.getClass().getSimpleName()
                            + ": " + t.getMessage());
                }
                // --- ESA WorldCover (batch-3: test ALL sources) ---
                // Ground cover, not buildings: what is on the
                // ground here (built-up, trees, grass, crops...).
                // Sampled at chunk centre + 4 corners with the
                // same non-blocking call generation uses.
                try {
                    EsaWorldCoverIndex esa = gen.getWorldCoverPublic();
                    double cLat = (minLat + maxLat) / 2.0;
                    double cLon = (minLon + maxLon) / 2.0;
                    double[][] pts = {
                        {cLat, cLon}, {minLat, minLon}, {minLat, maxLon},
                        {maxLat, minLon}, {maxLat, maxLon}};
                    EnumMap<EsaWorldCoverIndex.WorldCoverClass, Integer>
                            classes = new EnumMap<>(
                                    EsaWorldCoverIndex.WorldCoverClass.class);
                    for (double[] p : pts) {
                        classes.merge(esa.getClass(p[0], p[1]),
                                1, Integer::sum);
                    }
                    boolean esaReady = false;
                    for (EsaWorldCoverIndex.WorldCoverClass wc
                            : classes.keySet())
                        if (wc != EsaWorldCoverIndex.WorldCoverClass.UNKNOWN)
                            esaReady = true;
                    log(String.format(
                            "TEST here ESA WORLDCOVER: enabled=%s"
                            + " tileLoaded=%s classes(5 samples)=%s"
                            + " queries=%d hits=%d s3Fetches=%d"
                            + " headerTiles=%d resultsCached=%d",
                            esa.isEnabled(),
                            esa.isTileLoaded(cLat, cLon),
                            classes, esa.getTotalQueries(),
                            esa.getCacheHits(), esa.getS3Fetches(),
                            esa.getTileCacheSize(),
                            esa.getResultCacheSize()));
                    log("TEST here ESA VERDICT: " + (!esa.isEnabled()
                            ? "SWITCHED OFF — ground cover/vegetation"
                              + " has no data source in this world"
                            : esaReady
                            ? "READY — ground-cover data is being"
                              + " served here (classes above; a city"
                              + " chunk should read mostly BUILT_UP)"
                            : "NOT LOADED YET — tile fetch triggered;"
                              + " run /terracheck again in ~30 s. If"
                              + " still UNKNOWN, ESA S3 is not"
                              + " answering (search WorldCover lines"
                              + " in this log)"));
                } catch (Throwable t) {
                    log("TEST here ESA WORLDCOVER: ERROR "
                            + t.getClass().getSimpleName()
                            + ": " + t.getMessage());
                }

                // --- Water (OSM + OHM water indexes) ---
                try {
                    RiverLakeEngine rle = gen.getRlePublic();
                    log(String.format(
                            "TEST here WATER: osmEnabled=%s"
                            + " ohmEnabled=%s totalQueries=%d"
                            + " osmQueries=%d ohmQueries=%d"
                            + " osmHits=%d ohmHits=%d cacheHits=%d"
                            + " osmTilesCached=%d ohmTilesCached=%d",
                            rle.isOsmEnabled(), rle.isOhmEnabled(),
                            rle.getTotalQueries(),
                            rle.getOsmTotalQueries(),
                            rle.getOhmTotalQueries(),
                            rle.getOsmHits(), rle.getOhmHits(),
                            rle.getCacheHits(),
                            rle.getOsmCacheSize(),
                            rle.getOhmCacheSize()));
                    String wVerdict;
                    if (!rle.isOsmEnabled() && !rle.isOhmEnabled()) {
                        wVerdict = "SWITCHED OFF — no water source"
                                + " is enabled in this world";
                    } else if (rle.getTotalQueries() == 0) {
                        wVerdict = "NEVER ASKED — no water query"
                                + " has run yet this session";
                    } else if (rle.getOsmCacheSize()
                            + rle.getOhmCacheSize() > 0) {
                        wVerdict = "DATA ARRIVED — water tiles are"
                                + " cached (fetch road alive)."
                                + " Whether water is PLACED in the"
                                + " world is a separate question for"
                                + " the log and the owner's eyes";
                    } else {
                        wVerdict = "ASKED, NOTHING CACHED YET —"
                                + " water download in flight or"
                                + " failing (search WATER lines in"
                                + " this log)";
                    }
                    log("TEST here WATER VERDICT: " + wVerdict);
                } catch (Throwable t) {
                    log("TEST here WATER: ERROR "
                            + t.getClass().getSimpleName()
                            + ": " + t.getMessage());
                }

                // --- Overture appetizer (backup source) ---
                try {
                    OvertureOsmIndex ov = OvertureOsmIndex.getInstance();
                    List<OsmStructureIndex.OsmStructure> os =
                            ov.getStructuresForArea(
                                    minLat, maxLat, minLon, maxLon,
                                    1500L);
                    log(String.format(
                            "TEST here OVERTURE (appetizer/backup):"
                            + " cacheTiles=%d structuresHere=%s",
                            ov.getCacheSize(),
                            os == null
                                ? "null (no tiles resident)"
                                : String.valueOf(os.size())));
                    log("TEST here OVERTURE STATE: "
                            + ov.getDiagnostics());
                    log("TEST here OVERTURE VERDICT: " + (os == null
                            ? "NOT SERVING — no tiles resident for"
                              + " this chunk (see OVERTURE lines in"
                              + " this log; note healthy OSM keeps"
                              + " this backup dormant by design, so"
                              + " judge it together with those lines)"
                            : os.isEmpty()
                            ? "SERVING, AREA EMPTY — tiles resident;"
                              + " this chunk is genuinely empty in"
                              + " Overture"
                            : "SERVING — " + os.size()
                              + " structures resident for this chunk"));
                } catch (Throwable t) {
                    log("TEST here OVERTURE: ERROR "
                            + t.getClass().getSimpleName()
                            + ": " + t.getMessage());
                }

                // --- Elevation (terrain heights) ---
                try {
                    TerraDensityFunctions df = gen.getDfPublic();
                    double cLat = (minLat + maxLat) / 2.0;
                    double cLon = (minLon + maxLon) / 2.0;
                    double elev = df == null ? Double.NaN
                            : df.getElevationFast(cLat, cLon);
                    log(String.format(
                            "TEST here ELEVATION: projectionLoaded=%s"
                            + " centreElev=%s tileCache=%d"
                            + " elevCache=%d heightGuard=%s"
                            + " guardRepairs=%d (anchor=%d)",
                            df != null && df.isProjectionLoaded(),
                            Double.isFinite(elev)
                                ? String.format("%.1f m", elev)
                                : "NOT LOADED",
                            df == null ? -1 : df.getCacheSize(),
                            df == null ? -1 : df.getElevationCacheSize(),
                            HeightSpikeGuard.enabled ? "ON" : "OFF",
                            HeightSpikeGuard.TOTAL_REPAIRED.get(),
                            HeightSpikeGuard.ANCHOR_REPAIRED.get()));
                    log("TEST here ELEVATION VERDICT: "
                            + (Double.isFinite(elev)
                            ? "READY — elevation data is being served"
                              + " (value above; Hyderabad should"
                              + " read roughly 500-600 m)"
                            : "NOT LOADED — no elevation for this"
                              + " point yet; terrain here cannot be"
                              + " trusted until it arrives"));
                } catch (Throwable t) {
                    log("TEST here ELEVATION: ERROR "
                            + t.getClass().getSimpleName()
                            + ": " + t.getMessage());
                }

                // --- OHM historical structures ---
                try {
                    if (!TerraTimeManager.isTimeEnabled()) {
                        log("TEST here OHM VERDICT: OFF BY SWITCH"
                                + " — timejump is disabled, so the"
                                + " historical source is idle BY"
                                + " DESIGN and modern OSM is the"
                                + " structure source. Nothing to"
                                + " test until timejump is"
                                + " re-enabled");
                    } else {
                        OhmStructureIndex ohm = gen.getOhmIndexPublic();
                        double cLat = (minLat + maxLat) / 2.0;
                        double cLon = (minLon + maxLon) / 2.0;
                        OhmStructureIndex.OhmTile ohmTile =
                                ohm.query(cLat, cLon);
                        log(String.format(
                                "TEST here OHM: enabled=%s"
                                + " tileValid=%s structures=%d"
                                + " year=%d",
                                ohm.isEnabled(),
                                ohmTile != null && ohmTile.valid,
                                ohmTile == null
                                        || ohmTile.structures == null
                                        ? -1
                                        : ohmTile.structures.length,
                                TerraTimeManager.getCurrentYear()));
                        log("TEST here OHM VERDICT: "
                                + (ohmTile != null && ohmTile.valid
                                ? "ANSWERING — historical tile data"
                                  + " is being served for this chunk"
                                : "NOT ANSWERING YET — tile not"
                                  + " ready (fetch in flight or"
                                  + " failed)"));
                    }
                } catch (Throwable t) {
                    log("TEST here OHM: ERROR "
                            + t.getClass().getSimpleName()
                            + ": " + t.getMessage());
                }

                // ═══ BATCH 4: sea-level & extremes ═══
                // North/south of sea level was reviewed on
                // 2026-11-18 (sandbox-verified formulas). These
                // sections interrogate the LIVE game object so
                // the owner's log proves the same in-game.
                try {
                    TerraDensityFunctions df = gen.getDfPublic();
                    if (df == null || !df.isProjectionLoaded()) {
                        log("TEST here EXTREMES: SKIPPED — "
                                + "density functions not ready");
                    } else {
                        // name, lat, lon — fixed world extremes
                        Object[][] pts = {
                            {"Everest summit",    27.9881,  86.9250},
                            {"Kilimanjaro",       -3.0758,  37.3533},
                            {"Hyderabad control", 17.3617,  78.4747},
                            {"Dead Sea W shore",  31.3965,  35.5200},
                            {"Death Valley",      36.2298, -116.7677},
                            {"Flevoland polder",  52.4300,   5.4700},
                            {"Caspian Sea",       42.0000,  50.5000},
                            {"Mariana Trench",    11.3700, 142.5900},
                            {"Bay of Bengal",     16.0000,  87.0000},
                        };
                        int notLoaded = 0, odd = 0;
                        for (Object[] p : pts) {
                            String nm = (String) p[0];
                            double la = (Double) p[1];
                            double lo = (Double) p[2];
                            double e = df.getElevationFast(la, lo);
                            if (e == -50.0 || Double.isNaN(e)) {
                                notLoaded++;
                                log(String.format(
                                        "TEST here EXTREMES %-18s"
                                        + " lat=%.4f lon=%.4f:"
                                        + " NOT LOADED (far tile "
                                        + "still fetching; rerun"
                                        + " /terracheck)",
                                        nm, la, lo));
                                continue;
                            }
                            int dryY = TerraDensityFunctions
                                    .elevMetersToSurfaceY(e, true);
                            int ocY = TerraDensityFunctions
                                    .elevMetersToSurfaceY(e, false);
                            String note = "";
                            if (nm.startsWith("Everest")
                                    && dryY < 1900) {
                                note = " <-- ODD: summit Y "
                                        + "should be ~2000-2032";
                                odd++;
                            }
                            if (nm.startsWith("Mariana")
                                    && ocY > -1900) {
                                note = " <-- ODD: trench floor "
                                        + "should be ~-2020";
                                odd++;
                            }
                            if (nm.startsWith("Hyderabad")
                                    && (e < 400 || e > 700)) {
                                note = " <-- ODD: control should"
                                        + " read ~500 m";
                                odd++;
                            }
                            if (nm.startsWith("Dead Sea")
                                    && (e < -700 || e > 0)) {
                                note = " <-- DATA ODD: real shore"
                                        + " is about -430..-400 m";
                                odd++;
                            }
                            log(String.format(
                                    "TEST here EXTREMES %-18s"
                                    + " lat=%.4f lon=%.4f:"
                                    + " elev=%.1f m dryLandY=%d"
                                    + " oceanFloorY=%d%s",
                                    nm, la, lo, e, dryY, ocY,
                                    note));
                        }
                        log("TEST here EXTREMES VERDICT: "
                                + (notLoaded == pts.length
                                ? "NO DATA YET — every far tile "
                                  + "is still fetching. Wait a "
                                  + "minute and rerun /terracheck"
                                : odd == 0
                                ? "PASS — every loaded extreme "
                                  + "converts to a sane game Y "
                                  + "(" + notLoaded + " still "
                                  + "loading)"
                                : "CHECK — " + odd + " value(s) "
                                  + "flagged ODD above (data "
                                  + "problem, not a code problem:"
                                  + " the Y maths was proven on "
                                  + "these same formulas)"));
                    }
                } catch (Throwable t) {
                    log("TEST here EXTREMES: ERROR "
                            + t.getClass().getSimpleName()
                            + ": " + t.getMessage());
                }

                // SEAM: pure maths, no network — the joins of
                // the height scales, printed from the game's
                // own converter.
                try {
                    int y999 = TerraDensityFunctions
                            .elevMetersToSurfaceY(999, true);
                    int y1000 = TerraDensityFunctions
                            .elevMetersToSurfaceY(1000, true);
                    int y1001 = TerraDensityFunctions
                            .elevMetersToSurfaceY(1001, true);
                    int y2000 = TerraDensityFunctions
                            .elevMetersToSurfaceY(2000, true);
                    int y8848 = TerraDensityFunctions
                            .elevMetersToSurfaceY(8848, true);
                    int step = y1001 - y1000;
                    log(String.format(
                            "TEST here SEAM above-sea: 999m->Y%d"
                            + " 1000m->Y%d 1001m->Y%d (join "
                            + "step=%d blocks) 2000m->Y%d "
                            + "8848m->Y%d (world top=2032)",
                            y999, y1000, y1001, step, y2000,
                            y8848));
                    log(String.format(
                            "TEST here SEAM below-sea (dry): "
                            + "-1m->Y%d -2m->Y%d -10m->Y%d "
                            + "-86m->Y%d -430m->Y%d -1000m->Y%d "
                            + "(squash floor=13)",
                            TerraDensityFunctions
                                    .elevMetersToSurfaceY(-1, true),
                            TerraDensityFunctions
                                    .elevMetersToSurfaceY(-2, true),
                            TerraDensityFunctions
                                    .elevMetersToSurfaceY(-10, true),
                            TerraDensityFunctions
                                    .elevMetersToSurfaceY(-86, true),
                            TerraDensityFunctions
                                    .elevMetersToSurfaceY(-430, true),
                            TerraDensityFunctions
                                    .elevMetersToSurfaceY(-1000,
                                            true)));
                    log(String.format(
                            "TEST here SEAM ocean floor: 0m->Y%d"
                            + " -2000m->Y%d -6000m->Y%d "
                            + "-11034m->Y%d (world floor=-2032)",
                            TerraDensityFunctions
                                    .elevMetersToSurfaceY(0, false),
                            TerraDensityFunctions
                                    .elevMetersToSurfaceY(-2000,
                                            false),
                            TerraDensityFunctions
                                    .elevMetersToSurfaceY(-6000,
                                            false),
                            TerraDensityFunctions
                                    .elevMetersToSurfaceY(-11034,
                                            false)));
                    log("TEST here SEAM VERDICT: " + (step <= 3
                            ? "PASS — 1000 m join step is "
                              + step + " block(s), cosmetic only;"
                              + " above-sea 1:1 to 1000 m then "
                              + "squashed, below-sea squashed "
                              + "10:1, trench squashed past "
                              + "2000 m — all by design, nothing"
                              + " clips (8848 m -> Y" + y8848
                              + " <= 2032)"
                            : "CHECK — 1000 m join step is "
                              + step + " blocks (bigger than the"
                              + " expected <=3); expect a small "
                              + "cliff ring at the 1000 m contour"
                              + " around mountain ranges"));
                } catch (Throwable t) {
                    log("TEST here SEAM: ERROR "
                            + t.getClass().getSimpleName()
                            + ": " + t.getMessage());
                }

                // SENTINEL SCAN: is any part of THIS chunk
                // currently reading the -50 "no data" value?
                // A chunk generated on that value is stamped
                // fake ocean (sea-level review, problem #1).
                try {
                    TerraDensityFunctions df = gen.getDfPublic();
                    if (df == null) {
                        log("TEST here SENTINEL SCAN: SKIPPED"
                                + " — no density functions");
                    } else {
                        double cLat = (minLat + maxLat) / 2.0;
                        double cLon = (minLon + maxLon) / 2.0;
                        double[][] sp = {
                            {cLat, cLon},
                            {minLat, minLon}, {minLat, maxLon},
                            {maxLat, minLon}, {maxLat, maxLon}};
                        int sent = 0;
                        StringBuilder vals = new StringBuilder();
                        for (double[] s : sp) {
                            double e = df.getElevationFast(
                                    s[0], s[1]);
                            if (e == -50.0 || Double.isNaN(e))
                                sent++;
                            vals.append(String.format("%.0f ",
                                    e));
                        }
                        log(String.format(
                                "TEST here SENTINEL SCAN: %d/5 "
                                + "samples on the -50 sentinel "
                                + "(centre+corners elev: %s)",
                                sent, vals.toString().trim()));
                        log("TEST here SENTINEL VERDICT: "
                                + (sent == 0
                                ? "SAFE — real heights everywhere"
                                  + " in this chunk; nothing here"
                                  + " can be stamped fake ocean"
                                : "AT RISK — " + sent + " sample(s)"
                                  + " have no real height yet. A"
                                  + " chunk generated right now "
                                  + "could be saved as fake ocean."
                                  + " Wait for tiles, then "
                                  + "regenerate (delete the chunk "
                                  + "or revisit in a fresh world)"
                                  + " if it already generated"));
                    }
                } catch (Throwable t) {
                    log("TEST here SENTINEL SCAN: ERROR "
                            + t.getClass().getSimpleName()
                            + ": " + t.getMessage());
                }

                // DEAD SEA GRID: the satellite DEM over the
                // Dead Sea has voids (sandbox saw +180 m in
                // mid-sea). 25 samples make the dirt visible.
                try {
                    TerraDensityFunctions df = gen.getDfPublic();
                    if (df == null) {
                        log("TEST here DEAD SEA GRID: SKIPPED");
                    } else {
                        int loaded = 0, sent = 0, pos = 0;
                        double mn = Double.MAX_VALUE;
                        double mx = -Double.MAX_VALUE;
                        for (int i = 0; i < 5; i++)
                            for (int j = 0; j < 5; j++) {
                                double e = df.getElevationFast(
                                        31.10 + 0.15 * i,
                                        35.45 + 0.05 * j);
                                if (e == -50.0
                                        || Double.isNaN(e)) {
                                    sent++;
                                } else {
                                    loaded++;
                                    mn = Math.min(mn, e);
                                    mx = Math.max(mx, e);
                                    if (e > 0) pos++;
                                }
                            }
                        log(String.format(
                                "TEST here DEAD SEA GRID: loaded"
                                + "=%d sentinel=%d min=%.1f m "
                                + "max=%.1f m positiveSpikes=%d/25"
                                + " (real sea area lies about "
                                + "-430..-380 m; high values = "
                                + "the surrounding hills)",
                                loaded, sent,
                                loaded == 0 ? 0 : mn,
                                loaded == 0 ? 0 : mx, pos));
                        // Monster-point: inside the Caspian Sea
                        // garbage rows, where the raw book reads
                        // -9,935.2 m in flat water and the old
                        // neighbour repair repairs 0 pixels
                        // (workshop measurement on the real Java
                        // guard). The zoom-anchor should serve
                        // about -29 m here when the guard is ON.
                        double monster = df.getElevationFast(42.0002, 50.4987);
                        log(String.format(
                                "TEST here CASPIAN MONSTER-POINT"
                                + " (42.0002, 50.4987): elev=%s"
                                + " guard=%s repairs=%d (anchor=%d)"
                                + " — raw book says -9935.2 m here;"
                                + " about -29 m = anchor working",
                                monster == -50.0 || Double.isNaN(monster)
                                    ? "NOT LOADED"
                                    : String.format("%.1f m", monster),
                                HeightSpikeGuard.enabled ? "ON" : "OFF",
                                HeightSpikeGuard.TOTAL_REPAIRED.get(),
                                HeightSpikeGuard.ANCHOR_REPAIRED.get()));
                        log("TEST here DEAD SEA VERDICT: "
                                + (loaded == 0
                                ? "NO DATA YET — far tiles still"
                                  + " fetching; rerun /terracheck"
                                : mx > 0
                                ? "SOURCE DATA DIRTY — positive "
                                  + "spikes over the sea are "
                                  + "satellite voids in the DEM. "
                                  + "The game has outlier repair,"
                                  + " but expect lumpy terrain; "
                                  + "only eyes can judge it "
                                  + "(/tpll 31.5 35.55)"
                                : "CLEAN — all 25 samples below "
                                  + "sea level as they should be"));
                    }
                } catch (Throwable t) {
                    log("TEST here DEAD SEA GRID: ERROR "
                            + t.getClass().getSimpleName()
                            + ": " + t.getMessage());
                }

                // ═══ BATCH 4: source stack comparison ═══
                // The Microsoft step-3 pre-test: who actually
                // HOLDS buildings for this chunk? (OSM leads in
                // mapped towns by design; the gap-fillers earn
                // their keep where OSM is thin.) Resident data
                // only — this section never forces big waits.
                try {
                    int osmB = -1;
                    OsmStructureIndex osm2 =
                            gen.getStructureIndexPublic();
                    if (osm2 != null) {
                        List<OsmStructureIndex.OsmStructure> l =
                                osm2.getStructuresForChunk(
                                        minLat, maxLat, minLon,
                                        maxLon, config);
                        if (l != null) {
                            int c = 0;
                            for (OsmStructureIndex.OsmStructure s
                                    : l)
                                if (s.type == OsmStructureIndex
                                        .StructureType.BUILDING
                                        || s.type == OsmStructureIndex
                                        .StructureType.BUILDING_PART)
                                    c++;
                            osmB = c;
                        }
                    }
                    int vidaB = -1;
                    List<SourceCooperativeIndex.VidaBuilding> vl =
                            SourceCooperativeIndex.getInstance()
                                    .getBuildingsForChunk(
                                            minLat, maxLat,
                                            minLon, maxLon);
                    if (vl != null) vidaB = vl.size();
                    int msftB = -1;
                    List<MicrosoftIndex.MicrosoftBuilding> ml =
                            MicrosoftIndex.getInstance()
                                    .getBuildingsForChunk(
                                            minLat, maxLat,
                                            minLon, maxLon);
                    if (ml != null) msftB = ml.size();
                    int ovtB = -1;
                    List<OsmStructureIndex.OsmStructure> ol =
                            OvertureOsmIndex.getInstance()
                                    .getStructuresForArea(
                                            minLat, maxLat,
                                            minLon, maxLon, 0L);
                    if (ol != null) {
                        int c = 0;
                        for (OsmStructureIndex.OsmStructure s
                                : ol)
                            if (s.type == OsmStructureIndex
                                    .StructureType.BUILDING)
                                c++;
                        ovtB = c;
                    }
                    log(String.format(
                            "TEST here SOURCE STACK: buildings "
                            + "for this chunk — osm=%s vida=%s "
                            + "microsoft=%s overture=%s "
                            + "(-1 = source has nothing resident"
                            + " yet)",
                            osmB, vidaB, msftB, ovtB));
                    int bestFiller = Math.max(vidaB,
                            Math.max(msftB, ovtB));
                    log("TEST here SOURCE STACK VERDICT: "
                            + (osmB <= 0 && bestFiller > 0
                            ? "GAP-FILLERS CARRY THIS CHUNK — "
                              + "OSM holds no buildings here and "
                              + "the fillers do (" + bestFiller
                              + "). This is the case Microsoft "
                              + "step 3 must keep serving before "
                              + "any retirement"
                            : osmB > 0
                            ? "OSM LEADS here (" + osmB
                              + " buildings) — normal for "
                              + "mapped areas; fillers present: "
                              + "vida=" + vidaB + " microsoft="
                              + msftB + " overture=" + ovtB
                            : "NOTHING RESIDENT YET — cold "
                              + "chunk for every source; rerun "
                              + "/terracheck shortly"));
                } catch (Throwable t) {
                    log("TEST here SOURCE STACK: ERROR "
                            + t.getClass().getSimpleName()
                            + ": " + t.getMessage());
                }

                log("TEST here END chunk=[" + chunkX + "," + chunkZ + "]");
            } catch (Throwable t) {
                log("TEST here ERROR " + t.getClass().getSimpleName()
                        + ": " + t.getMessage());
            }
        });
        return 1;
    }

    // ---------------------------------------------------------
    // /terracheck find <osmId> — how did the REAL parser
    // classify this one feature, in the player's chunk?
    // (The Charminar question: way 238186115.) Answers into
    // the log only; places nothing.
    // ---------------------------------------------------------
    private static int find(CommandSourceStack source, long osmId) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            line(source, "  find-test needs a player (run it in game).",
                    ChatFormatting.RED);
            return 0;
        }
        TerraChunkGenerator gen = TerraChunkGenerator.activeInstance;
        if (gen == null) {
            line(source, "  Earth generator: NOT ACTIVE (not a Terra world?)",
                    ChatFormatting.RED);
            return 0;
        }
        var cp = player.chunkPosition();
        final int minBX = cp.getMinBlockX(), maxBX = cp.getMaxBlockX();
        final int minBZ = cp.getMinBlockZ(), maxBZ = cp.getMaxBlockZ();
        final TerraWorldConfig config = gen.getWorldConfig();
        line(source, "  find-test running off-thread for osmId "
                + osmId + " — results in the log, search [TerraCheck]",
                ChatFormatting.GRAY);

        CompletableFuture.runAsync(() -> {
            try {
                double[][] corners = {
                    geoOf(gen, minBX, minBZ), geoOf(gen, maxBX, minBZ),
                    geoOf(gen, minBX, maxBZ), geoOf(gen, maxBX, maxBZ)};
                double minLat = Double.MAX_VALUE, maxLat = -Double.MAX_VALUE;
                double minLon = Double.MAX_VALUE, maxLon = -Double.MAX_VALUE;
                for (double[] g : corners) {
                    if (g == null) continue;
                    minLon = Math.min(minLon, g[0]);
                    maxLon = Math.max(maxLon, g[0]);
                    minLat = Math.min(minLat, g[1]);
                    maxLat = Math.max(maxLat, g[1]);
                }
                OsmStructureIndex osm = gen.getStructureIndexPublic();
                if (osm == null) {
                    log("TEST find osmId=" + osmId + ": OSM index is null");
                    return;
                }
                List<OsmStructureIndex.OsmStructure> list =
                        osm.getStructuresForChunk(
                                minLat, maxLat, minLon, maxLon, config);
                OsmStructureIndex.OsmStructure hit = null;
                for (OsmStructureIndex.OsmStructure s : list) {
                    if (s.osmId == osmId || s.parentOsmId == osmId) {
                        hit = s;
                        break;
                    }
                }
                if (hit == null) {
                    log("TEST find osmId=" + osmId + ": NOT PRESENT in"
                            + " this chunk (" + list.size()
                            + " OSM structures searched) — either the"
                            + " tile never arrived, or the parser"
                            + " dropped it before this list");
                } else {
                    log("TEST find osmId=" + osmId + ": FOUND -> "
                            + describe(hit));
                    log(String.format(
                            "TEST find detail: isArea=%s isPart=%s"
                            + " nodes=%d lat %.6f..%.6f lon %.6f..%.6f"
                            + " visibleThisYear=%s",
                            hit.isArea, hit.isPart,
                            hit.nodes == null ? 0 : hit.nodes.length,
                            hit.minLat, hit.maxLat,
                            hit.minLon, hit.maxLon,
                            hit.isVisibleAt(
                                    TerraTimeManager.getCurrentYear())));
                }
            } catch (Throwable t) {
                log("TEST find ERROR " + t.getClass().getSimpleName()
                        + ": " + t.getMessage());
            }
        });
        return 1;
    }

    // ---------------------------------------------------------
    // BIOME/GROUND audit (biome fix step 2): snapshot the actual
    // top blocks + biome palette on the server thread, then do
    // the heavier ESA/climate/expected-ground maths off-thread.
    // ---------------------------------------------------------
    /** COAST bench (missing-sea fix): is the land/sea decision
     *  working here? Samples classify() at the player and 300 m
     *  in the four block directions, with the coastline-area
     *  answer printed beside each sample. A healthy coast shows
     *  OCEAN samples on the sea side while standing on a beach. */
    // World truth for one column: -1 = chunk not loaded,
    // 0 = loaded, no water in the top 40 blocks, 1 = water
    // found. labelOut[0] receives a short text for the log
    // line. The NW(11) lesson: classify() alone said SEA
    // FOUND while the painter had turned the sea back into
    // land, so the verdict must also look at real blocks.
    private static int worldWater(ServerPlayer player,
            int bx, int bz, String[] labelOut) {
        labelOut[0] = "world=not-loaded";
        try {
            if (!(player.level() instanceof ServerLevel lvl))
                return -1;
            var ch = lvl.getChunkSource()
                .getChunkNow(bx >> 4, bz >> 4);
            if (ch == null) return -1;
            int lx = Math.floorMod(bx, 16);
            int lz = Math.floorMod(bz, 16);
            int surf = ch.getHeight(
                Heightmap.Types.WORLD_SURFACE, lx, lz);
            BlockPos.MutableBlockPos pos =
                new BlockPos.MutableBlockPos();
            int topY = surf - 1;
            BlockState top = ch.getBlockState(
                pos.set(bx, topY, bz));
            boolean water = false;
            for (int y = topY;
                    y >= Math.max(
                        lvl.getMinBuildHeight(), topY - 40);
                    y--) {
                if (ch.getBlockState(pos.set(bx, y, bz))
                        .is(net.minecraft.world.level.block
                            .Blocks.WATER)) {
                    water = true;
                    break;
                }
            }
            labelOut[0] = "world=" + (water ? "WATER" : "dry")
                + " top=" + blockName(top) + "@" + topY;
            return water ? 1 : 0;
        } catch (Exception e) {
            labelOut[0] = "world=error";
            return -1;
        }
    }

    private static void coastCheck(TerraChunkGenerator gen,
            ServerPlayer player) {
        try {
            var df = gen.getDfPublic();
            if (df == null || !df.isProjectionLoaded()) {
                log("TEST here COAST: no projection — skipped");
                return;
            }
            var osm = gen.getRlePublic().getOsmWaterIndex();
            int px = player.getBlockX(), pz = player.getBlockZ();
            var here = df.classify(px, pz);
            boolean cached = false;
            try {
                cached = osm.isTileCached(osm.getTileKeyPublic(
                    here.latitude, here.longitude));
            } catch (Exception ignored) {}
            log(String.format(
                "TEST here COAST: osmTileCached=%s here=%s "
                + "elev=%.1fm lat=%.5f lon=%.5f",
                cached, here.type, here.elevationMeters,
                here.latitude, here.longitude));
            boolean seaSeen =
                here.type == TerraDensityFunctions
                    .TerrainType.OCEAN
                || here.type == TerraDensityFunctions
                    .TerrainType.SHALLOW_WATER;
            String[] hereWorld = new String[1];
            int hereWater = worldWater(player, px, pz, hereWorld);
            boolean worldSeaSeen = seaSeen && hereWater == 1;
            int seaSamplesLoaded = (seaSeen && hereWater >= 0)
                ? 1 : 0;
            log("TEST here COAST world-here: " + hereWorld[0]
                + " (real blocks under your feet; a beach "
                + "column can read dry sand while the map "
                + "already calls it shallow water - judge the "
                + "sea by the WEST sample in loaded ground)");
            boolean coastMapSeen = false;
            String[] names = {"WEST", "EAST", "NORTH", "SOUTH"};
            int[][] offs = {
                {-300, 0}, {300, 0}, {0, -300}, {0, 300}};
            for (int i = 0; i < 4; i++) {
                var c = df.classify(
                    px + offs[i][0], pz + offs[i][1]);
                var side = osm.classifyCoastlineMemOnly(
                    c.latitude, c.longitude);
                if ("OCEAN".equals(String.valueOf(side))
                    || "COASTLINE".equals(String.valueOf(side)))
                    coastMapSeen = true;
                boolean sampleSea = c.type == TerraDensityFunctions
                        .TerrainType.OCEAN
                    || c.type == TerraDensityFunctions
                        .TerrainType.SHALLOW_WATER;
                if (sampleSea)
                    seaSeen = true;
                String[] sampleWorld = new String[1];
                int sampleWater = worldWater(player,
                    px + offs[i][0], pz + offs[i][1], sampleWorld);
                if (sampleSea && sampleWater >= 0)
                    seaSamplesLoaded++;
                if (sampleSea && sampleWater == 1)
                    worldSeaSeen = true;
                log(String.format(
                    "TEST here COAST %s 300m: type=%s coast=%s "
                    + "elev=%.1fm",
                    names[i], c.type, side, c.elevationMeters)
                    + " " + sampleWorld[0]);
            }
            String verdict;
            if (worldSeaSeen)
                verdict = "SEA FOUND IN WORLD — real water "
                    + "blocks present at a sea sample";
            else if (seaSeen && seaSamplesLoaded > 0)
                verdict = "MAP SAYS SEA BUT WORLD IS DRY — "
                    + "classify says ocean, yet no water blocks "
                    + "in the loaded sea samples (sea lost in "
                    + "generation, or repair has not run there)";
            else if (seaSeen)
                verdict = "SEA ON MAP ONLY — the sea samples "
                    + "are not loaded; walk into the sea, wait, "
                    + "and re-run";
            else if (!cached && !coastMapSeen)
                verdict = "COASTLINE MAP NOT LOADED — the sea "
                    + "decision is being guessed from elevation "
                    + "(missing-sea risk); wait ~30s and re-run";
            else
                verdict = "NO SEA NEARBY — all samples land "
                    + "(normal inland, or the coastline area "
                    + "says this is land)";
            log("TEST here COAST VERDICT: " + verdict);
        } catch (Throwable t) {
            log("TEST here COAST: ERROR "
                + t.getClass().getSimpleName()
                + ": " + t.getMessage());
        }
    }

    private static void biomeGroundCheck(CommandSourceStack source,
            TerraChunkGenerator gen, ServerPlayer player,
            int chunkX, int chunkZ, int minBX, int minBZ) {
        try {
            if (!(player.level() instanceof ServerLevel level)) {
                log("TEST here BIOME/GROUND: player is not in a "
                        + "server level — skipped");
                return;
            }
            MinecraftServer server = source.getServer();
            if (server == null) {
                log("TEST here BIOME/GROUND: no server — skipped");
                return;
            }
            server.execute(() -> {
                try {
                    var chunk = level.getChunkSource()
                            .getChunkNow(chunkX, chunkZ);
                    if (chunk == null) {
                        log("TEST here BIOME/GROUND: chunk ["
                                + chunkX + "," + chunkZ + "] is "
                                + "not loaded — skipped");
                        return;
                    }
                    String[] top = new String[256];
                    String[] biomes = new String[256];
                    int[] topY = new int[256];
                    BlockPos.MutableBlockPos pos =
                            new BlockPos.MutableBlockPos();
                    for (int lx = 0; lx < 16; lx++) {
                        for (int lz = 0; lz < 16; lz++) {
                            int idx = lx * 16 + lz;
                            int bx = minBX + lx, bz = minBZ + lz;
                            int surf = chunk.getHeight(
                                    Heightmap.Types.WORLD_SURFACE,
                                    lx, lz);
                            int y = Math.min(
                                    level.getMaxBuildHeight() - 1,
                                    surf + 1);
                            int minY = Math.max(
                                    level.getMinBuildHeight(),
                                    surf - 96);
                            BlockState st = chunk.getBlockState(
                                    pos.set(bx, y, bz));
                            while (y > minY && st.isAir()) {
                                y--;
                                st = chunk.getBlockState(
                                        pos.set(bx, y, bz));
                            }
                            topY[idx] = y;
                            top[idx] = blockName(st);
                            biomes[idx] = level.getBiome(
                                            pos.set(bx, y, bz))
                                    .unwrapKey()
                                    .map(k -> k.location().toString())
                                    .orElse("unknown");
                        }
                    }
                    CompletableFuture.runAsync(() ->
                            analyseBiomeGround(gen, chunkX, chunkZ,
                                    minBX, minBZ, top, biomes, topY));
                } catch (Throwable t) {
                    log("TEST here BIOME/GROUND snapshot ERROR "
                            + t.getClass().getSimpleName() + ": "
                            + t.getMessage());
                }
            });
        } catch (Throwable t) {
            log("TEST here BIOME/GROUND ERROR "
                    + t.getClass().getSimpleName() + ": "
                    + t.getMessage());
        }
    }

    private static void analyseBiomeGround(TerraChunkGenerator gen,
            int chunkX, int chunkZ, int minBX, int minBZ,
            String[] top, String[] biomes, int[] topY) {
        try {
            TerraBiomeProvider bp = gen.getBiomeProviderPublic();
            EsaWorldCoverIndex esa = gen.getWorldCoverPublic();
            TerraDensityFunctions df = gen.getDfPublic();
            if (bp == null || esa == null || df == null) {
                log("TEST here BIOME/GROUND: biome provider, ESA "
                        + "or elevation is not ready — skipped");
                return;
            }

            // Climate truth samples: builtin dataset only, no
            // network. These four places catch the old bug where
            // precipitation silently read the temperature channel.
            Object[][] climatePts = {
                {"Hyderabad", 17.3850, 78.4867, 600.0, 1000.0, 24.0, 30.0},
                {"Cairo", 30.0444, 31.2357, 0.0, 80.0, 19.0, 24.0},
                {"Amazon", -3.1000, -60.0000, 1800.0, 2600.0, 24.0, 29.0},
                {"Berlin", 52.5200, 13.4050, 500.0, 900.0, 6.0, 11.0},
            };
            int climateBad = 0;
            for (Object[] p : climatePts) {
                String nm = (String) p[0];
                double la = (Double) p[1], lo = (Double) p[2];
                double[] c = bp.getClimateSample(la, lo);
                boolean ok = c[1] >= (Double) p[3]
                        && c[1] <= (Double) p[4]
                        && c[2] >= (Double) p[5]
                        && c[2] <= (Double) p[6];
                if (!ok) climateBad++;
                log(String.format(
                        "TEST here CLIMATE %-10s lat=%.4f lon=%.4f "
                        + "soil=%.0f precip=%.0f mm/yr temp=%.1f C "
                        + "aridity=%s %s",
                        nm, la, lo, c[0], c[1], c[2],
                        bp.describeAridity(c[1]),
                        ok ? "OK" : "<-- CHECK range"));
            }
            log("TEST here CLIMATE VERDICT: " + (climateBad == 0
                    ? "PASS — rainfall/temperature channels read "
                      + "like the real climate dataset"
                    : "CHECK — " + climateBad + " known place(s) "
                      + "outside expected climate range; biome "
                      + "decisions cannot be trusted yet"));

            Map<String, Integer> topCounts = new HashMap<>();
            Map<String, Integer> biomeCounts = new HashMap<>();
            for (int i = 0; i < 256; i++) {
                topCounts.merge(top[i], 1, Integer::sum);
                biomeCounts.merge(biomes[i], 1, Integer::sum);
            }

            EnumMap<EsaWorldCoverIndex.WorldCoverClass, Integer>
                    esaCounts = new EnumMap<>(
                            EsaWorldCoverIndex.WorldCoverClass.class);
            Map<String, Integer> rawCodes = new HashMap<>();
            Map<String, Integer> effCodes = new HashMap<>();
            int unknown = 0, evaluated = 0, covered = 0, mismatch = 0;
            List<String> examples = new ArrayList<>();
            int shoreCells = 0, shoreBeachName = 0, shoreShoreTop = 0;
            List<String> shoreExamples = new ArrayList<>();
            for (int lx = 0; lx < 16; lx++) {
                for (int lz = 0; lz < 16; lz++) {
                    int idx = lx * 16 + lz;
                    int bx = minBX + lx, bz = minBZ + lz;
                    double[] geo = df.toGeo(bx, bz); // [lon, lat]
                    if (geo == null || Double.isNaN(geo[0])
                            || Double.isNaN(geo[1])) continue;
                    double lon = geo[0], lat = geo[1];
                    double elev = df.getElevationFast(lat, lon);
                    EsaWorldCoverIndex.WorldCoverClass wc =
                            esa.getClass(lat, lon);
                    if (wc == EsaWorldCoverIndex.WorldCoverClass.UNKNOWN)
                        unknown++;
                    esaCounts.merge(wc, 1, Integer::sum);
                    int raw = bp.getBiomeTypeCode(lat, lon, elev, wc);
                    int eff = gen.getSurfaceTypeCodePublic(bx, bz);
                    rawCodes.merge(codeName(raw), 1, Integer::sum);
                    effCodes.merge(codeName(eff), 1, Integer::sum);
                    // Step 4 SHORE truth (the generator's own
                    // test): is this a shore column, and did
                    // the saved world agree — beach-family
                    // biome name + shore ground on top?
                    boolean shoreCell = false;
                    try {
                        shoreCell = gen.isShoreCellPublic(bx, bz);
                    } catch (Throwable ignored) {}
                    if (shoreCell) {
                        shoreCells++;
                        String bname = biomes[idx];
                        if (bname.contains("beach")
                                || bname.contains("stony_shore")
                                || bname.contains("mangrove")) {
                            shoreBeachName++;
                        }
                        String aname = top[idx];
                        if (aname.equals("minecraft:sand")
                                || aname.equals("minecraft:gravel")
                                || aname.equals("minecraft:mud")
                                || aname.equals("minecraft:red_sand")) {
                            shoreShoreTop++;
                        } else if (shoreExamples.size() < 4) {
                            shoreExamples.add(String.format(
                                    "[%d,%d] biome=%s top=%s "
                                    + "wc=%s elev=%.0fm y=%d",
                                    bx, bz, bname, aname, wc,
                                    elev, topY[idx]));
                        }
                    }
                    java.util.Set<String> expected =
                            expectedGroundSet(
                                    eff, elev, bx, bz, gen);
                    String actual = top[idx];
                    if (!isNaturalGroundName(actual)) {
                        covered++;
                        continue;
                    }
                    evaluated++;
                    if (expected != null && !expected.contains(actual)) {
                        mismatch++;
                        if (examples.size() < 8) {
                            examples.add(String.format(
                                    "[%d,%d] wc=%s code=%s/%s "
                                    + "expected=%s actual=%s "
                                    + "biome=%s elev=%.0fm y=%d",
                                    bx, bz, wc, raw, eff, expected,
                                    actual, biomes[idx], elev, topY[idx]));
                        }
                    }
                }
            }

            double[] centreGeo = gen.toGeoPublic(minBX + 8, minBZ + 8);
            if (centreGeo != null) {
                double[] c = bp.getClimateSample(
                        centreGeo[1], centreGeo[0]);
                log(String.format(
                        "TEST here CLIMATE HERE: lat=%.5f lon=%.5f "
                        + "soil=%.0f precip=%.0f mm/yr temp=%.1f C "
                        + "aridity=%s",
                        centreGeo[1], centreGeo[0], c[0], c[1], c[2],
                        bp.describeAridity(c[1])));
            }
            // DATA readiness at the centre: were the map tiles
            // actually in hand when this area generated?
            try {
                if (centreGeo != null) {
                    var osm = gen.getRlePublic()
                            .getOsmWaterIndex();
                    boolean coastCached = osm.isTileCached(
                            osm.getTileKeyPublic(
                                    centreGeo[1], centreGeo[0]));
                    log("TEST here DATA: coastlineTile="
                            + (coastCached ? "CACHED" : "MISSING")
                            + " esaUnknown=" + unknown + "/256"
                            + (coastCached && unknown == 0
                                ? " — both maps in hand here"
                                : " — some map data missing here; "
                                  + "coast/ground decisions may "
                                  + "have used fallbacks"));
                }
            } catch (Throwable ignored) {}
            log("TEST here BIOME/GROUND chunk=[" + chunkX + ","
                    + chunkZ + "] ESA(256)=" + esaCounts);
            log("TEST here BIOME/GROUND BIOME(256)="
                    + new java.util.TreeMap<>(biomeCounts));
            log("TEST here BIOME/GROUND TOP(256)="
                    + new java.util.TreeMap<>(topCounts));
            log("TEST here BIOME/GROUND CODES raw="
                    + new java.util.TreeMap<>(rawCodes)
                    + " effective=" + new java.util.TreeMap<>(effCodes));
            log(String.format(
                    "TEST here BIOME/GROUND SUMMARY: esaUnknown=%d "
                    + "naturalEvaluated=%d coveredOrWaterOrStruct=%d "
                    + "mismatch=%d",
                    unknown, evaluated, covered, mismatch));
            for (String ex : examples) {
                log("TEST here BIOME/GROUND MISMATCH " + ex);
            }
            log(String.format(
                    "TEST here SHORE: shoreCells=%d/256 "
                    + "beachBiomeCells=%d shoreTopCells=%d",
                    shoreCells, shoreBeachName, shoreShoreTop));
            for (String ex : shoreExamples) {
                log("TEST here SHORE MISS " + ex);
            }
            log("TEST here SHORE VERDICT: " + (shoreCells == 0
                    ? "NO SHORE IN THIS CHUNK — stand on the "
                      + "sand at the water edge and rerun; an "
                      + "inland chunk cannot judge beaches"
                    : shoreBeachName * 4 >= shoreCells * 3
                      && shoreShoreTop * 4 >= shoreCells * 3
                    ? "PASS — shore columns carry beach-family "
                      + "biomes and shore ground (Step 4 visible "
                      + "in this chunk)"
                    : "CHECK — shore exists here but Step 4 is "
                      + "not fully visible: beachBiome "
                      + shoreBeachName + "/" + shoreCells
                      + ", shoreTop " + shoreShoreTop + "/"
                      + shoreCells + " (chunk may predate the "
                      + "Step 4 build, or the name/paint route "
                      + "is not firing — see SHORE MISS lines)"));
            log("TEST here BIOME/GROUND VERDICT: " + (unknown > 128
                    ? "NOT READY — ESA WorldCover is UNKNOWN for "
                      + "more than half this chunk; rerun "
                      + "/terracheck after the tile lands"
                    : evaluated == 0
                    ? "NO NATURAL GROUND TO JUDGE — every column "
                      + "is water, vegetation, structure or other "
                      + "cover in this chunk"
                    : mismatch == 0
                    ? "PASS — natural top blocks match the "
                      + "current expected ground rules here"
                    : "CHECK — " + mismatch + "/" + evaluated
                      + " natural columns differ from the "
                      + "current expected ground rules (see "
                      + "MISMATCH lines; structures/water in a "
                      + "column can still explain some)"));
        } catch (Throwable t) {
            log("TEST here BIOME/GROUND ERROR "
                    + t.getClass().getSimpleName() + ": "
                    + t.getMessage());
        }
    }

    private static String blockName(BlockState state) {
        if (state == null || state.isAir()) return "minecraft:air";
        return BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
    }

    private static String codeName(int code) {
        return switch (code) {
            case 1 -> "1-desert";
            case 2 -> "2-mesa";
            case 3 -> "3-frozen";
            case 4 -> "4-swamp";
            case 5 -> "5-savanna";
            case 6 -> "6-shrubland";
            case 7 -> "7-bare-hot";
            case 9 -> "9-beach";
            case 10 -> "10-builtup-raw";
            case 11 -> "11-dirt-path";
            default -> "0-default";
        };
    }

    // Step 5: ground palettes are patch mixes now, plus a
    // steep-rock override — several tops are CORRECT per
    // code. The bench therefore expects a set, not a block.
    private static java.util.Set<String> expectedGroundSet(
            int code, double elev, int bx, int bz,
            TerraChunkGenerator gen) {
        java.util.Set<String> s = new java.util.HashSet<>();
        switch (code) {
            case 1, 2, 3 -> s.add(blockName(
                    TerraDensityFunctions.getLandSurfaceBlock(
                            elev, code,
                            code == 2
                                    ? gen.getRoughnessPublic(bx, bz)
                                    : 0.0)));
            case 4 -> {
                s.add("minecraft:mud");
                s.add("minecraft:grass_block");
                s.add("minecraft:dirt");
                s.add("minecraft:coarse_dirt");
            }
            case 5 -> {
                s.add("minecraft:grass_block");
                s.add("minecraft:coarse_dirt");
                s.add("minecraft:stone");
            }
            case 6 -> {
                s.add("minecraft:grass_block");
                s.add("minecraft:coarse_dirt");
                s.add("minecraft:dirt");
                s.add("minecraft:red_sand");
                s.add("minecraft:gravel");
                s.add("minecraft:stone");
            }
            case 7 -> s.add("minecraft:red_sand");
            case 9 -> s.add("minecraft:sand");
            case 10 -> s.add("minecraft:tuff");
            case 11 -> s.add("minecraft:dirt_path");
            default -> s.add(blockName(
                    TerraDensityFunctions.getLandSurfaceBlock(
                            elev, 0, 0.0)));
        }
        // Steep-rock override (Step 5): bare rock may top
        // most flat palettes on steep ground.
        if (code == 0 || code == 1 || code == 4 || code == 5
                || code == 6 || code == 7) {
            s.add("minecraft:stone");
            s.add("minecraft:sandstone");
            s.add("minecraft:red_sandstone");
        }
        return s;
    }

    private static boolean isNaturalGroundName(String name) {
        if (name == null) return false;
        if (name.equals("minecraft:terracotta")
                || name.endsWith("_terracotta")) return true;
        return switch (name) {
            case "minecraft:grass_block", "minecraft:dirt",
                 "minecraft:coarse_dirt", "minecraft:dirt_path",
                 "minecraft:sand", "minecraft:red_sand",
                 "minecraft:gravel", "minecraft:stone",
                 "minecraft:deepslate", "minecraft:snow_block",
                 "minecraft:packed_ice", "minecraft:ice",
                 "minecraft:sandstone", "minecraft:red_sandstone",
                 "minecraft:tuff", "minecraft:clay",
                 "minecraft:mud", "minecraft:muddy_mangrove_roots",
                 "minecraft:podzol", "minecraft:mycelium" -> true;
            default -> false;
        };
    }

    private static double[] geoOf(TerraChunkGenerator gen, int bx, int bz) {
        try {
            return gen.toGeoPublic(bx, bz); // [lon, lat]
        } catch (Throwable t) {
            return null;
        }
    }

    // Player position as [lon, lat]. Prefers the ACTIVE
    // world's own projection (correct for any world
    // settings); falls back to the parsed BTE default
    // projection. Null when neither is available — callers
    // must say so in chat instead of printing nothing
    // (owner lat/lon report, 2026-11-20).
    private static double[] playerGeo(ServerPlayer player) {
        TerraChunkGenerator gen = TerraChunkGenerator.activeInstance;
        if (gen != null) {
            double[] g = geoOf(gen, player.getBlockX(),
                    player.getBlockZ());
            if (g != null && !Double.isNaN(g[0])
                    && !Double.isNaN(g[1])) return g;
        }
        GeographicProjection proj = getProjection();
        if (proj != null) {
            try {
                double[] g = proj.toGeo(player.getX(), player.getZ());
                if (g != null && !Double.isNaN(g[0])
                        && !Double.isNaN(g[1])) return g;
            } catch (Throwable ignored) {}
        }
        return null;
    }

    private static String describe(OsmStructureIndex.OsmStructure s) {
        return String.format(
                "osmId=%d type=%s sub=%s name=%s levels=%d height=%.1f"
                + " resolvedBlocks=%d minBlocks=%d colour=%s"
                + " material=%s roof=%s layer=%d parent=%d years=%d..%d",
                s.osmId, s.type, s.subType, s.name, s.levels, s.height,
                s.resolvedHeightBlocks(), s.minHeightBlocks(),
                s.colour, s.material, s.roofShape, s.layer,
                s.parentOsmId, s.startYear, s.endYear);
    }

    private static GeographicProjection getProjection() {
        if (projection == null && !projectionFailed) {
            synchronized (TerraCheckCommand.class) {
                if (projection == null && !projectionFailed) {
                    try {
                        EarthGeneratorSettings settings =
                                EarthGeneratorSettings.parse(
                                        EarthGeneratorSettings.BTE_DEFAULT_SETTINGS);
                        projection = settings.projection();
                    } catch (Throwable t) {
                        projectionFailed = true;
                    }
                }
            }
        }
        return projection;
    }
}
