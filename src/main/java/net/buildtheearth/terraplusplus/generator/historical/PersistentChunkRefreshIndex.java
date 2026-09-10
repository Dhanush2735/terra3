// ============================================================
// FILE: PersistentChunkRefreshIndex.java
// CHANGES:
//   - Removed inline syncHistoricalWater() from onChunkLoad
//     — was causing deadlock (chunk lock held during load,
//     setBlock() tried to re-acquire it).
//   - onChunkLoad now only queues refresh safely.
//   - postJumpRescanCountdown rescan in onServerTick
//     now calls syncHistoricalWaterPublic on ALL in-memory
//     chunks regardless of needsRefresh result.
//     This fixes semi-loaded chunks that were marked
//     as already refreshed before water sync ran.
//   - recordChunkSeen() does not write year (only visited).
//   - needsRefresh() returns true for UNSET_YEAR.
//   - All prior fixes retained.
// ============================================================
package net.buildtheearth.terraplusplus.generator.historical;

import net.buildtheearth.terraplusplus.generator.TerraChunkGenerator;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.level.ChunkEvent;
import net.minecraftforge.event.level.LevelEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.StandardOpenOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Mod.EventBusSubscriber(modid = "terraplusplus")
public final class PersistentChunkRefreshIndex {

    private static final Logger LOGGER =
        LogManager.getLogger("Terra++ChunkIndex");

    // --------------------------------------------------
    private static final int META_MAGIC   = 0x5450504D;
    private static final int REGION_MAGIC = 0x54505052;
    private static final int META_VERSION = 1;

    private static final int REGION_VERSION_LEGACY = 1;
    private static final int REGION_VERSION        = 2;

    // --------------------------------------------------
    private static final int REGION_BITS  = 5;
    private static final int REGION_SIZE  = 1 << REGION_BITS;
    private static final int REGION_MASK  = REGION_SIZE - 1;
    private static final int REGION_CELLS =
        REGION_SIZE * REGION_SIZE;
    private static final int BITSET_BYTES =
        (REGION_CELLS + 7) >> 3;

    private static final short UNSET_YEAR =
        Short.MIN_VALUE;

    // --------------------------------------------------
    private static final int FLUSH_INTERVAL_TICKS      = 200;
    private static final int STALE_SCAN_INTERVAL_TICKS = 100;
    private static final int STALE_SCAN_MAX_PER_TICK   = 50;
    // Existing .mca chunks can predate/circumvent the Terra index. Reconcile
    // their presence gradually so a later time jump never treats real terrain
    // as "never generated".
    private static final int RECONCILE_CHUNKS_PER_TICK = 64;
    private static final int POST_JUMP_RESCAN_MAX_QUEUE = 128;

    // --------------------------------------------------
    private static final Object LOCK = new Object();

    // --------------------------------------------------
    private static volatile boolean initialized = false;
    // Background generation tasks may outlive the integrated server shutdown.
    // Never let one re-create index files while the world is closing.
    private static volatile boolean stopping = false;
    private static volatile Path worldRoot;
    private static volatile Path baseDir;
    private static volatile Path regionsDir;
    private static volatile Path metaFile;

    // --------------------------------------------------
    private static volatile int globalGeneration = 0;
    private static volatile int lastJumpYear =
        TerraTimeManager.BASE_YEAR;

    // --------------------------------------------------
    private static final ConcurrentHashMap<Long, RegionData>
        regionCache = new ConcurrentHashMap<>();
    private static final Set<Long> dirtyRegions =
        ConcurrentHashMap.newKeySet();
    private static final Set<Long> loadedChunks =
        ConcurrentHashMap.newKeySet();
    private static final ArrayDeque<Long> reconciliationQueue = new ArrayDeque<>();
    private static volatile boolean reconciliationQueued = false;
    private static int reconciliationFound = 0;
    private static int reconciliationAdded = 0;

    // --------------------------------------------------
    private static int tickCounter = 0;

    // --------------------------------------------------
    private static int debugLogCount = 0;
    private static final int DEBUG_LOG_MAX = 20;

    /** Runtime lifecycle for diagnostic and duplicate-refresh control. The
     * durable truth remains visited + chunkYear; after restart stale chunks are
     * recomputed from those persistent values. */
    public enum RefreshState {
        DISCOVERED, STALE, QUEUED, REFRESHING, COMPLETE, DEFERRED
    }
    private static final ConcurrentHashMap<Long, RefreshState> refreshStates =
        new ConcurrentHashMap<>();

    // Runtime-only time-jump test harness. It intentionally records chunks,
    // not guessed block types: a chunk is considered updated only after the
    // normal transaction reaches markChunkRefreshed().
    private static final Set<Long> auditTargets = ConcurrentHashMap.newKeySet();
    // Runtime marker for a target chunk's one-hop second boundary refresh.
    private static final Set<Long> secondBoundaryRefreshes = ConcurrentHashMap.newKeySet();
    private static volatile int auditGeneration = -1;
    private static volatile int auditYear = Integer.MIN_VALUE;
    private static int auditLastReportTick = 0;
    private static final int AUDIT_REPORT_INTERVAL_TICKS = 200;

    private PersistentChunkRefreshIndex() {}

    // --------------------------------------------------
    private static final class RegionData {
        final int rx;
        final int rz;

        final BitSet visited =
            new BitSet(REGION_CELLS);

        final short[] chunkYears =
            new short[REGION_CELLS];

        boolean dirty = false;

        RegionData(int rx, int rz) {
            this.rx = rx;
            this.rz = rz;
            Arrays.fill(
                this.chunkYears, UNSET_YEAR);
        }
    }

    // --------------------------------------------------
    // PUBLIC API
    // --------------------------------------------------

    public static void ensureInitialized(
            ServerLevel level) {
        if (level == null || stopping) return;
        if (level.dimension() != Level.OVERWORLD)
            return;

        Path root = level.getServer()
            .getWorldPath(LevelResource.ROOT);

        if (initialized && root.equals(worldRoot))
            return;

        synchronized (LOCK) {
            if (initialized
                    && root.equals(worldRoot))
                return;

            flushAllInternal();
            clearRuntimeOnly();

            try {
                worldRoot  = root;
                baseDir    = root
                    .resolve("data")
                    .resolve("terra_chunk_index");
                regionsDir =
                    baseDir.resolve("regions");
                metaFile   =
                    baseDir.resolve("meta.dat");

                Files.createDirectories(regionsDir);
                loadMetaInternal();

                initialized   = true;
                debugLogCount = 0;
                queueRegionReconciliation();

                LOGGER.info(
                    "PersistentChunkRefreshIndex "
                    + "init: jumpGen={} "
                    + "lastJumpYear={} dir={}",
                    globalGeneration,
                    lastJumpYear, baseDir);

            } catch (Exception e) {
                LOGGER.error(
                    "Chunk index init failed: {}",
                    e.getMessage());
            }
        }
    }

    public static boolean recordChunkSeen(
            ServerLevel level, int cx, int cz) {
        if (stopping) return false;
        ensureInitialized(level);
        if (!initialized) return false;
        loadedChunks.add(chunkKey(cx, cz));

        synchronized (LOCK) {
            RegionData region = getRegionInternal(
                regionX(cx), regionZ(cz));
            int idx = localIndex(cx, cz);

            if (region.visited.get(idx))
                return false;

            region.visited.set(idx);
            markDirty(region);
            refreshStates.put(chunkKey(cx, cz), RefreshState.DISCOVERED);

            if (debugLogCount < DEBUG_LOG_MAX) {
                debugLogCount++;
                LOGGER.debug(
                    "First seen [{},{}] "
                    + "chunkYear=UNSET "
                    + "currentYear={}",
                    cx, cz,
                    TerraTimeManager
                        .getCurrentYear());
            }
            return true;
        }
    }

    /** Mark a chunk found in an on-disk MCA region without claiming it is loaded. */
    private static void recordPersistedChunk(int cx, int cz) {
        synchronized (LOCK) {
            RegionData region = getRegionInternal(regionX(cx), regionZ(cz));
            int idx = localIndex(cx, cz);
            if (!region.visited.get(idx)) {
                region.visited.set(idx);
                markDirty(region);
                reconciliationAdded++;
            }
        }
    }

    /** Read MCA location tables once on world load; no chunk NBT is decoded. */
    private static void queueRegionReconciliation() {
        if (worldRoot == null || reconciliationQueued) return;
        reconciliationQueued = true;
        Path regionDir = worldRoot.resolve("region");
        if (!Files.isDirectory(regionDir)) return;
        try (var files = Files.list(regionDir)) {
            files.filter(path -> path.getFileName().toString().matches("r\\.-?\\d+\\.-?\\d+\\.mca"))
                .forEach(path -> enqueueRegionLocations(path));
            LOGGER.info("Chunk index reconciliation queued {} persisted chunks", reconciliationFound);
        } catch (Exception e) {
            LOGGER.debug("Chunk index reconciliation scan: {}", e.getMessage());
        }
    }

    private static void enqueueRegionLocations(Path file) {
        String[] parts = file.getFileName().toString().split("\\.");
        if (parts.length != 4) return;
        try {
            int rx = Integer.parseInt(parts[1]);
            int rz = Integer.parseInt(parts[2]);
            if (Files.size(file) < 4096) return;
            byte[] header = new byte[4096];
            try (var input = Files.newInputStream(file)) {
                int read = input.read(header);
                if (read < 4096) return;
            }
            ByteBuffer locations = ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN);
            for (int i = 0; i < REGION_CELLS; i++) {
                if (locations.getInt(i * 4) == 0) continue;
                int cx = (rx << REGION_BITS) + (i & REGION_MASK);
                int cz = (rz << REGION_BITS) + (i >> REGION_BITS);
                reconciliationQueue.add(chunkKey(cx, cz));
                reconciliationFound++;
            }
        } catch (Exception e) {
            LOGGER.debug("Chunk index region {}: {}", file.getFileName(), e.getMessage());
        }
    }

    private static void drainReconciliation() {
        int processed = 0;
        while (processed++ < RECONCILE_CHUNKS_PER_TICK) {
            Long key = reconciliationQueue.poll();
            if (key == null) break;
            recordPersistedChunk((int) (key >> 32), (int) (long) key);
        }
        if (reconciliationQueued && reconciliationQueue.isEmpty() && reconciliationFound > 0) {
            LOGGER.info("Chunk index reconciliation complete: persisted={} newlyIndexed={}",
                    reconciliationFound, reconciliationAdded);
            reconciliationFound = 0;
            reconciliationAdded = 0;
        }
    }

    public static void markChunkRefreshQueued(int cx, int cz) {
        long key = chunkKey(cx, cz);
        refreshStates.put(key, RefreshState.QUEUED);
        if (auditGeneration == globalGeneration) auditTargets.add(key);
    }

    /** Begins a bounded runtime audit for one /terra timejump. */
    public static void beginTimeJumpAudit(int generation, int year) {
        auditTargets.clear();
        secondBoundaryRefreshes.clear();
        auditGeneration = generation;
        auditYear = year;
        auditLastReportTick = tickCounter;
        LOGGER.info("TIMEJUMP_UPDATE_AUDIT start generation={} year={}", generation, year);
    }

    public static void markChunkRefreshing(int cx, int cz) {
        refreshStates.put(chunkKey(cx, cz), RefreshState.REFRESHING);
    }

    public static void markChunkDeferred(int cx, int cz) {
        refreshStates.put(chunkKey(cx, cz), RefreshState.DEFERRED);
    }

    /** A failed runtime pass must never be represented as complete. The
     * durable chunk year is intentionally unchanged, so restart/load retries
     * it as well. */
    public static void markChunkRefreshFailed(int cx, int cz) {
        refreshStates.put(chunkKey(cx, cz), RefreshState.STALE);
    }

    /** A persisted neighbour operation requires the target to execute its own
     * ownership transaction even when it was current before the boundary work. */
    public static void invalidateChunk(ServerLevel level, int cx, int cz) {
        if (stopping) return;
        ensureInitialized(level);
        if (!initialized) return;
        synchronized (LOCK) {
            RegionData region = getRegionInternal(regionX(cx), regionZ(cz));
            int idx = localIndex(cx, cz);
            region.visited.set(idx);
            region.chunkYears[idx] = UNSET_YEAR;
            markDirty(region);
            refreshStates.put(chunkKey(cx, cz), RefreshState.STALE);
        }
    }

    public static boolean isTimeJumpAuditComplete() {
        if (auditGeneration != globalGeneration || auditTargets.isEmpty()) return false;
        for (long key : auditTargets)
            if (refreshStates.get(key) != RefreshState.COMPLETE) return false;
        return true;
    }

    public static void beginSecondBoundaryRefresh(int cx, int cz) {
        secondBoundaryRefreshes.add(chunkKey(cx, cz));
    }

    public static boolean isSecondBoundaryRefresh(int cx, int cz) {
        return secondBoundaryRefreshes.contains(chunkKey(cx, cz));
    }

    public static RefreshState getRefreshState(int cx, int cz) {
        return refreshStates.get(chunkKey(cx, cz));
    }

    public static boolean needsRefresh(
            ServerLevel level, int cx, int cz) {
        if (stopping) return false;
        ensureInitialized(level);
        if (!initialized) return false;

        synchronized (LOCK) {
            RegionData region = getRegionInternal(
                regionX(cx), regionZ(cz));
            int idx = localIndex(cx, cz);

            if (!region.visited.get(idx))
                return false;

            short stored = region.chunkYears[idx];
            boolean stale = stored == UNSET_YEAR || stored != currentYearShort();
            if (stale) {
                long key = chunkKey(cx, cz);
                // needsRefresh() is also used as a guard while a queued pass
                // runs. Do not erase its QUEUED/REFRESHING diagnostic state.
                refreshStates.compute(key, (ignored, previous) ->
                    previous == RefreshState.QUEUED
                        || previous == RefreshState.REFRESHING
                        || previous == RefreshState.DEFERRED
                    ? previous : RefreshState.STALE);
            }
            return stale;
        }
    }

    public static void markChunkRefreshed(
            ServerLevel level, int cx, int cz) {
        if (stopping) return;
        ensureInitialized(level);
        if (!initialized) return;

        synchronized (LOCK) {
            RegionData region = getRegionInternal(
                regionX(cx), regionZ(cz));
            int idx = localIndex(cx, cz);

            boolean wasVisited = region.visited.get(idx);
            region.visited.set(idx);

            short current = currentYearShort();
            if (!wasVisited || region.chunkYears[idx] != current) {
                region.chunkYears[idx] = current;
                markDirty(region);
            }
            refreshStates.put(chunkKey(cx, cz), RefreshState.COMPLETE);
            secondBoundaryRefreshes.remove(chunkKey(cx, cz));
        }
    }

    public static void markChunkYear(
            ServerLevel level, int cx, int cz,
            int year) {
        if (stopping) return;
        ensureInitialized(level);
        if (!initialized) return;

        synchronized (LOCK) {
            RegionData region = getRegionInternal(
                regionX(cx), regionZ(cz));
            int idx = localIndex(cx, cz);

            boolean wasVisited = region.visited.get(idx);
            region.visited.set(idx);
            short y = clampYearToShort(year);
            if (!wasVisited || region.chunkYears[idx] != y) {
                region.chunkYears[idx] = y;
                markDirty(region);
            }
        }
    }

    public static int getChunkYear(
            ServerLevel level, int cx, int cz) {
        if (stopping) return Integer.MIN_VALUE;
        ensureInitialized(level);
        if (!initialized) return Integer.MIN_VALUE;

        synchronized (LOCK) {
            RegionData region = getRegionInternal(
                regionX(cx), regionZ(cz));
            int idx = localIndex(cx, cz);
            short y = region.chunkYears[idx];
            return y == UNSET_YEAR
                ? Integer.MIN_VALUE : y;
        }
    }

    public static void advanceRefreshGeneration(
            ServerLevel level, int year) {
        if (stopping) return;
        ensureInitialized(level);
        if (!initialized) return;

        synchronized (LOCK) {
            globalGeneration++;
            lastJumpYear = year;
            saveMetaInternal();
        }

        LOGGER.info(
            "Advanced jump generation -> {} "
            + "for year {}",
            globalGeneration, year);
    }

    public static List<ChunkPos>
            getLoadedChunkSnapshot(
            ServerLevel level) {
        ensureInitialized(level);
        List<ChunkPos> out =
            new ArrayList<>(loadedChunks.size());
        for (long key : loadedChunks) {
            int cx = (int)(key >> 32);
            int cz = (int) key;
            out.add(new ChunkPos(cx, cz));
        }
        return out;
    }

    public static List<ChunkPos>
            getVisitedChunksNearPlayers(
            ServerLevel level, int radius) {
        ensureInitialized(level);
        Set<ChunkPos> out = new HashSet<>();

        synchronized (LOCK) {
            for (var player : level.players()) {
                int px = player.chunkPosition().x;
                int pz = player.chunkPosition().z;

                for (RegionData region :
                        regionCache.values()) {
                    int rMinX =
                        region.rx << REGION_BITS;
                    int rMinZ =
                        region.rz << REGION_BITS;
                    int rMaxX =
                        rMinX + REGION_SIZE - 1;
                    int rMaxZ =
                        rMinZ + REGION_SIZE - 1;

                    if (rMaxX < px - radius
                            || rMinX > px + radius
                            || rMaxZ < pz - radius
                            || rMinZ > pz + radius)
                        continue;

                    for (int i = 0;
                            i < REGION_CELLS; i++) {
                        if (!region.visited.get(i))
                            continue;
                        int lx = i & REGION_MASK;
                        int lz = i >> REGION_BITS;
                        int cx = rMinX + lx;
                        int cz = rMinZ + lz;
                        if (Math.abs(cx - px)
                                    <= radius
                                && Math.abs(cz - pz)
                                    <= radius)
                            out.add(new ChunkPos(
                                cx, cz));
                    }
                }
            }
        }
        return new ArrayList<>(out);
    }

    public static List<ChunkPos>
            getVisitedChunksNear(
            ServerLevel level,
            int centerX, int centerZ,
            int radius) {
        ensureInitialized(level);
        List<ChunkPos> out = new ArrayList<>();
        synchronized (LOCK) {
            for (RegionData region : regionCache.values()) {
                int rMinX = region.rx << REGION_BITS;
                int rMinZ = region.rz << REGION_BITS;
                int rMaxX = rMinX + REGION_SIZE - 1;
                int rMaxZ = rMinZ + REGION_SIZE - 1;
                if (rMaxX < centerX - radius || rMinX > centerX + radius
                        || rMaxZ < centerZ - radius || rMinZ > centerZ + radius)
                    continue;
                for (int i = 0; i < REGION_CELLS; i++) {
                    if (!region.visited.get(i)) continue;
                    int cx = rMinX + (i & REGION_MASK);
                    int cz = rMinZ + (i >> REGION_BITS);
                    if (Math.abs(cx - centerX) <= radius
                            && Math.abs(cz - centerZ) <= radius)
                        out.add(new ChunkPos(cx, cz));
                }
            }
        }
        return out;
    }

    private static boolean isNearAnyPlayer(ServerLevel level, ChunkPos pos, int radius) {
        if (level.players().isEmpty()) return true;
        long limit = (long) radius * radius;
        for (var player : level.players()) {
            long dx = (long) pos.x - player.chunkPosition().x;
            long dz = (long) pos.z - player.chunkPosition().z;
            if (dx * dx + dz * dz <= limit) return true;
        }
        return false;
    }

    public static int getRegionGeneration(
            int cx, int cz) {
        synchronized (LOCK) {
            long key = regionKey(
                regionX(cx), regionZ(cz));
            return regionCache.containsKey(key)
                ? globalGeneration : -1;
        }
    }

    public static int  getLoadedChunkCount() {
        return loadedChunks.size();
    }
    public static int  getGeneration() {
        return globalGeneration;
    }
    public static int  getRegionCacheSize() {
        return regionCache.size();
    }
    public static int  getDirtyRegionCount() {
        return dirtyRegions.size();
    }
    public static int  getLastJumpYear() {
        return lastJumpYear;
    }

    public static void forceFlush() {
        synchronized (LOCK) {
            flushAllInternal();
        }
    }

    private static void reportTimeJumpAudit(ServerLevel level) {
        if (auditGeneration != globalGeneration || auditTargets.isEmpty()) return;
        int complete = 0, queued = 0, refreshing = 0, stale = 0, deferred = 0;
        List<String> pendingSamples = new ArrayList<>();
        for (long key : auditTargets) {
            RefreshState state = refreshStates.get(key);
            if (state == RefreshState.COMPLETE) { complete++; continue; }
            if (state == RefreshState.QUEUED) queued++;
            else if (state == RefreshState.REFRESHING) refreshing++;
            else if (state == RefreshState.DEFERRED) deferred++;
            else stale++;
            if (pendingSamples.size() < 8) pendingSamples.add("[" + (int)(key >> 32) + "," + (int)key + "]=" + state);
        }
        String samples = pendingSamples.isEmpty() ? "none" : String.join(" ", pendingSamples);
        LOGGER.info("TIMEJUMP_UPDATE_AUDIT generation={} year={} tracked={} complete={} queued={} refreshing={} stale={} deferred={} pending={}",
                auditGeneration, auditYear, auditTargets.size(), complete, queued,
                refreshing, stale, deferred, samples);
    }

    // --------------------------------------------------
    // EVENT HOOKS
    // --------------------------------------------------

    @SubscribeEvent
    public static void onLevelLoad(
            LevelEvent.Load event) {
        if (event.getLevel()
                instanceof ServerLevel level
                && level.dimension()
                == Level.OVERWORLD) {
            stopping = false;
            // Axis 5: restore branch registry before any chunk
            // staleness check computes a fingerprint.
            try {
                WorldLineRegistry.load(level.getServer()
                    .getWorldPath(net.minecraft.world.level
                        .storage.LevelResource.ROOT));
                // Axis 6: restore forged universe parameters
                // before any custom-slot dimension generates.
                net.buildtheearth.terraplusplus.generator
                    .UniverseTransforms.loadForged(
                        level.getServer().getWorldPath(
                            net.minecraft.world.level
                                .storage.LevelResource.ROOT));
            } catch (Exception e) {
                LOGGER.warn("worldline load failed: {}",
                    e.getMessage());
            }
            ensureInitialized(level);
        }
    }

    @SubscribeEvent
    public static void onLevelUnload(
            LevelEvent.Unload event) {
        if (event.getLevel()
                instanceof ServerLevel level
                && level.dimension()
                == Level.OVERWORLD) {
            // Level unload can precede ServerStopping on an integrated server.
            // Block late async callbacks from reopening the just-closed index.
            stopping = true;
            synchronized (LOCK) {
                flushAllInternal();
                GeneratedBlockOwnershipIndex.flush();
                DeferredChunkRefreshLedger.flush();
                BoundaryPairRefreshLedger.flush();
                ProvisionalServing.clear();
                clearRuntimeOnly();
                initialized = false;
            }
            LOGGER.info(
                "PersistentChunkRefreshIndex "
                + "unloaded");
        }
    }

    @SubscribeEvent
    public static void onChunkLoad(
            ChunkEvent.Load event) {
        if (!(event.getLevel()
                instanceof ServerLevel level))
            return;
        if (level.dimension() != Level.OVERWORLD || stopping)
            return;

        int cx = event.getChunk().getPos().x;
        int cz = event.getChunk().getPos().z;

        recordChunkSeen(level, cx, cz);
        boolean deferredBoundaryRefresh = false;

        if (!TerraTimeManager.isTimeEnabled())
            return;

        // Queue only — no inline block modification
        // to avoid chunk load deadlock
        if (deferredBoundaryRefresh || needsRefresh(level, cx, cz)) {
            TerraChunkGenerator
                .scheduleSingleChunkRefresh(
                    level, new ChunkPos(cx, cz));
            LOGGER.debug(
                "CHUNK_LOAD [{},{}] stale -> "
                + "refresh queued",
                cx, cz);
        }
    }

    @SubscribeEvent
    public static void onChunkUnload(
            ChunkEvent.Unload event) {
        if (!(event.getLevel()
                instanceof ServerLevel level))
            return;
        if (level.dimension() != Level.OVERWORLD)
            return;

        int cx = event.getChunk().getPos().x;
        int cz = event.getChunk().getPos().z;
        loadedChunks.remove(chunkKey(cx, cz));
    }

    @SubscribeEvent
    public static void onServerTick(
            TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END)
            return;
        if (stopping || !initialized) return;

        tickCounter++;
        drainReconciliation();

        ServerLevel level = getOverworld();
        if (level == null) return;

        TerraChunkGenerator.processTimeJumpBoundaryRing(level);

        if (tickCounter - auditLastReportTick >= AUDIT_REPORT_INTERVAL_TICKS) {
            auditLastReportTick = tickCounter;
            reportTimeJumpAudit(level);
        }

        // --------------------------------------------------
        if (tickCounter % FLUSH_INTERVAL_TICKS == 0)
            flushDirtyRegionsInternal();

        // --------------------------------------------------
        // When countdown reaches zero, scan ALL
        // in-memory chunks and:
        // 1. Queue every observed chunk through the normal ownership
        //    transaction. Water must never mutate outside that transaction.
        // 2. Queue stale chunks for vegetation and
        //    structure refresh via normal queue.
        if (TerraChunkGenerator
                .postJumpRescanCountdown > 0) {
            TerraChunkGenerator
                .postJumpRescanCountdown--;

            if (TerraChunkGenerator
                    .postJumpRescanCountdown == 0
                    && TerraTimeManager
                        .isTimeEnabled()) {

                var gen =
                    TerraChunkGenerator
                    .activeInstance;

                int waterSynced  = 0;
                int waterFailed  = 0;
                int totalScanned = 0;
                int queued       = 0;
                int skipped      = 0;
                int deferred     = 0;
                // ChunkMap#getChunks has no stable official runtime name and
                // reflection therefore failed every jump. The event-maintained
                // snapshot is deterministic, mapping-safe, and contains every
                // chunk observed through Forge ChunkEvent.Load.
                final String scanMethod = "eventSnapshot";
                // Water is applied only by the normal pass-1 transaction.
                // This rescan must not enqueue an arbitrary snapshot first,
                // otherwise it defeats the nearby-player ordering and cap.
                List<ChunkPos> rescanSnapshot = getLoadedChunkSnapshot(level);
                rescanSnapshot.sort(java.util.Comparator.comparingLong(pos -> {
                    long best = Long.MAX_VALUE;
                    for (var player : level.players()) {
                        long dx = (long) pos.x - player.chunkPosition().x;
                        long dz = (long) pos.z - player.chunkPosition().z;
                        best = Math.min(best, dx * dx + dz * dz);
                    }
                    return best;
                }));
                for (ChunkPos pos : rescanSnapshot) {
                    if (level.hasChunk(pos.x, pos.z)) totalScanned++;
                }

                // --------------------------------------------------
                // Queue newly loaded stale chunks nearest to players first.
                for (ChunkPos pos : rescanSnapshot) {
                    if (queued >= POST_JUMP_RESCAN_MAX_QUEUE)
                        break;
                    if (!level.hasChunk(
                            pos.x, pos.z))
                        continue;
                    if (!isNearAnyPlayer(level, pos, 20)) {
                        markChunkDeferred(pos.x, pos.z);
                        deferred++;
                        continue;
                    }
                    if (needsRefresh(
                            level,
                            pos.x, pos.z)) {
                        TerraChunkGenerator
                            .scheduleSingleChunkRefresh(
                                level, pos);
                        queued++;
                    } else {
                        skipped++;
                    }
                }

                LOGGER.info(
                    "POST_JUMP_RESCAN: "
                    + "method={} "
                    + "waterScanned={} "
                    + "waterRefreshQueued={} "
                    + "waterFailed={} "
                    + "queued={} skipped={} deferred={} "
                    + "year={}",
                    scanMethod,
                    totalScanned,
                    waterSynced,
                    waterFailed,
                    queued,
                    skipped,
                    deferred,
                    TerraTimeManager
                        .getCurrentYear());
            }
        }

        // --------------------------------------------------
        if (tickCounter
                    % STALE_SCAN_INTERVAL_TICKS == 0
                && TerraTimeManager.isTimeEnabled()) {
            int queued = 0;
            for (ChunkPos pos :
                    getLoadedChunkSnapshot(level)) {
                if (queued
                        >= STALE_SCAN_MAX_PER_TICK)
                    break;
                if (!level.hasChunk(pos.x, pos.z))
                    continue;
                if (!isNearAnyPlayer(level, pos, 20)) {
                    markChunkDeferred(pos.x, pos.z);
                    continue;
                }
                if (needsRefresh(
                        level, pos.x, pos.z)) {
                    TerraChunkGenerator
                        .scheduleSingleChunkRefresh(
                            level, pos);
                    queued++;
                }
            }
            if (queued > 0)
                LOGGER.debug(
                    "STALE_SCAN queued {} chunks "
                    + "year={} loaded={}",
                    queued,
                    TerraTimeManager.getCurrentYear(),
                    loadedChunks.size());
        }
    }

    @SubscribeEvent
    public static void onServerStopping(
            ServerStoppingEvent event) {
        stopping = true;
        synchronized (LOCK) {
            flushAllInternal();
            GeneratedBlockOwnershipIndex.flush();
                DeferredChunkRefreshLedger.flush();
                BoundaryPairRefreshLedger.flush();
            clearRuntimeOnly();
            initialized = false;
        }
        LOGGER.info(
            "PersistentChunkRefreshIndex saved "
            + "on shutdown");
    }

    // --------------------------------------------------
    // INTERNAL REGION LOGIC
    // --------------------------------------------------

    private static void markDirty(
            RegionData region) {
        region.dirty = true;
        dirtyRegions.add(
            regionKey(region.rx, region.rz));
    }

    private static RegionData getRegionInternal(
            int rx, int rz) {
        long key = regionKey(rx, rz);
        RegionData cached = regionCache.get(key);
        if (cached != null) return cached;

        RegionData loaded =
            loadRegionInternal(rx, rz);
        regionCache.put(key, loaded);
        if (loaded.dirty)
            dirtyRegions.add(key);
        return loaded;
    }

    private static RegionData loadRegionInternal(
            int rx, int rz) {
        Path file = regionFile(rx, rz);
        if (!Files.exists(file))
            return new RegionData(rx, rz);

        try (DataInputStream in =
                new DataInputStream(
                new BufferedInputStream(
                Files.newInputStream(file)))) {

            int magic   = in.readInt();
            int version = in.readInt();
            int fileRx  = in.readInt();
            int fileRz  = in.readInt();

            if (magic != REGION_MAGIC
                    || fileRx != rx
                    || fileRz != rz) {
                LOGGER.warn(
                    "Bad region file {}, rebuilding",
                    file.getFileName());
                return rebuiltRegion(rx, rz);
            }

            if (version == REGION_VERSION) {
                RegionData region =
                    new RegionData(rx, rz);
                byte[] vb = new byte[BITSET_BYTES];
                in.readFully(vb);
                region.visited.or(
                    BitSet.valueOf(vb));
                for (int i = 0;
                        i < REGION_CELLS; i++)
                    region.chunkYears[i] =
                        in.readShort();
                region.dirty = false;
                return region;
            }

            if (version == REGION_VERSION_LEGACY)
                return loadLegacyRegionV1(
                    in, rx, rz);

            LOGGER.warn(
                "Unknown region version {} "
                + "in {}, rebuilding",
                version, file.getFileName());
            return rebuiltRegion(rx, rz);

        } catch (Exception e) {
            LOGGER.warn(
                "Failed loading region [{},{}]: {}",
                rx, rz, e.getMessage());
            return rebuiltRegion(rx, rz);
        }
    }

    private static RegionData rebuiltRegion(int rx, int rz) {
        RegionData region = new RegionData(rx, rz);
        // getRegionInternal() registers dirty rebuilt data for rewrite.
        region.dirty = true;
        return region;
    }

    private static RegionData loadLegacyRegionV1(
            DataInputStream in, int rx, int rz)
            throws Exception {
        RegionData region = new RegionData(rx, rz);

        int legacyGen = in.readInt();

        byte[] vb = new byte[BITSET_BYTES];
        byte[] rb = new byte[BITSET_BYTES];
        in.readFully(vb);
        in.readFully(rb);

        BitSet visited   = BitSet.valueOf(vb);
        BitSet refreshed = BitSet.valueOf(rb);

        region.visited.or(visited);

        short migratedYear =
            clampYearToShort(lastJumpYear);
        boolean sameEpoch =
            legacyGen == globalGeneration;

        for (int i = 0; i < REGION_CELLS; i++) {
            if (!visited.get(i))
                region.chunkYears[i] = UNSET_YEAR;
            else if (sameEpoch && refreshed.get(i))
                region.chunkYears[i] = migratedYear;
            else
                region.chunkYears[i] = UNSET_YEAR;
        }

        region.dirty = true;

        LOGGER.info(
            "Migrated legacy region [{},{}] "
            + "legacyGen={} globalGen={} year={}",
            rx, rz, legacyGen,
            globalGeneration, lastJumpYear);

        return region;
    }

    private static void saveRegionInternal(
            RegionData region) {
        try {
            Files.createDirectories(regionsDir);
            Path file = regionFile(
                region.rx, region.rz);

            byte[] vb = Arrays.copyOf(
                region.visited.toByteArray(),
                BITSET_BYTES);

            try (DataOutputStream out =
                    new DataOutputStream(
                    new BufferedOutputStream(
                    Files.newOutputStream(file,
                        StandardOpenOption.CREATE,
                        StandardOpenOption
                            .TRUNCATE_EXISTING,
                        StandardOpenOption
                            .WRITE)))) {
                out.writeInt(REGION_MAGIC);
                out.writeInt(REGION_VERSION);
                out.writeInt(region.rx);
                out.writeInt(region.rz);
                out.write(vb);
                for (int i = 0;
                        i < REGION_CELLS; i++)
                    out.writeShort(
                        region.chunkYears[i]);
            }

            region.dirty = false;

        } catch (Exception e) {
            LOGGER.warn(
                "Failed saving region [{},{}]: {}",
                region.rx, region.rz,
                e.getMessage());
        }
    }

    private static void loadMetaInternal() {
        globalGeneration = 0;
        lastJumpYear = TerraTimeManager.BASE_YEAR;

        if (!Files.exists(metaFile)) {
            saveMetaInternal();
            return;
        }

        try (DataInputStream in =
                new DataInputStream(
                new BufferedInputStream(
                Files.newInputStream(metaFile)))) {

            int magic   = in.readInt();
            int version = in.readInt();

            if (magic != META_MAGIC
                    || version != META_VERSION) {
                LOGGER.warn(
                    "Bad meta file, resetting");
                saveMetaInternal();
                return;
            }

            globalGeneration = in.readInt();
            lastJumpYear     = in.readInt();

        } catch (Exception e) {
            LOGGER.warn(
                "Failed loading meta: {}",
                e.getMessage());
            globalGeneration = 0;
            lastJumpYear =
                TerraTimeManager.BASE_YEAR;
        }
    }

    private static void saveMetaInternal() {
        try {
            Files.createDirectories(baseDir);
            try (DataOutputStream out =
                    new DataOutputStream(
                    new BufferedOutputStream(
                    Files.newOutputStream(metaFile,
                        StandardOpenOption.CREATE,
                        StandardOpenOption
                            .TRUNCATE_EXISTING,
                        StandardOpenOption
                            .WRITE)))) {
                out.writeInt(META_MAGIC);
                out.writeInt(META_VERSION);
                out.writeInt(globalGeneration);
                out.writeInt(lastJumpYear);
            }
        } catch (Exception e) {
            LOGGER.warn(
                "Failed saving meta: {}",
                e.getMessage());
        }
    }

    private static void
            flushDirtyRegionsInternal() {
        synchronized (LOCK) {
            if (dirtyRegions.isEmpty()) return;

            List<Long> keys =
                new ArrayList<>(dirtyRegions);
            int flushed = 0;

            for (long key : keys) {
                RegionData region =
                    regionCache.get(key);
                if (region != null
                        && region.dirty) {
                    saveRegionInternal(region);
                    flushed++;
                }
                dirtyRegions.remove(key);
            }

            saveMetaInternal();

            if (flushed > 0
                    && debugLogCount
                        < DEBUG_LOG_MAX) {
                debugLogCount++;
                LOGGER.debug(
                    "Flushed {} dirty regions",
                    flushed);
            }
        }
    }

    private static void flushAllInternal() {
        if (baseDir == null) return;
        flushDirtyRegionsInternal();
    }

    private static void clearRuntimeOnly() {
        loadedChunks.clear();
        refreshStates.clear();
        auditTargets.clear();
        secondBoundaryRefreshes.clear();
        auditGeneration = -1;
        auditYear = Integer.MIN_VALUE;
        reconciliationQueue.clear();
        reconciliationQueued = false;
        reconciliationFound = 0;
        reconciliationAdded = 0;
        regionCache.clear();
        dirtyRegions.clear();
        tickCounter = 0;
    }

    // --------------------------------------------------
    // HELPERS
    // --------------------------------------------------

    private static short currentYearShort() {
        // World-line fingerprint: the stored short encodes
        // year + active timeline branch. Switching branch makes
        // every previously-refreshed chunk read as stale — the
        // same mechanism a year jump uses. On the main timeline
        // this is the plain year short, so existing region files
        // stay valid.
        return WorldLineRegistry.fingerprintShort(
            clampYearToShort(
                TerraTimeManager.getCurrentYear()));
    }

    private static short clampYearToShort(
            int year) {
        int y = Math.max(
            TerraTimeManager.MIN_YEAR,
            Math.min(
                TerraTimeManager.MAX_YEAR, year));
        return (short) y;
    }

    private static Path regionFile(
            int rx, int rz) {
        return regionsDir.resolve(
            "r." + rx + "." + rz + ".bin");
    }

    private static long regionKey(
            int rx, int rz) {
        return ((long) rx << 32)
            | (rz & 0xFFFFFFFFL);
    }

    private static long chunkKey(
            int cx, int cz) {
        return ((long) cx << 32)
            | (cz & 0xFFFFFFFFL);
    }

    private static int regionX(int cx) {
        return Math.floorDiv(cx, REGION_SIZE);
    }

    private static int regionZ(int cz) {
        return Math.floorDiv(cz, REGION_SIZE);
    }

    private static int localIndex(
            int cx, int cz) {
        int lx = cx & REGION_MASK;
        int lz = cz & REGION_MASK;
        return (lz << REGION_BITS) | lx;
    }

    private static ServerLevel getOverworld() {
        try {
            var server =
                net.minecraftforge.server
                .ServerLifecycleHooks
                .getCurrentServer();
            if (server == null) return null;
            return server.overworld();
        } catch (Exception e) {
            return null;
        }
    }
}
