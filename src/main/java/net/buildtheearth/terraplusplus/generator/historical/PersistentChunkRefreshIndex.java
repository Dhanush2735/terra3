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
import java.nio.file.StandardOpenOption;
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

    // ── FILE FORMAT CONSTANTS ─────────────────────────────────
    private static final int META_MAGIC   = 0x5450504D;
    private static final int REGION_MAGIC = 0x54505052;
    private static final int META_VERSION = 1;

    private static final int REGION_VERSION_LEGACY = 1;
    private static final int REGION_VERSION        = 2;

    // ── REGION LAYOUT ─────────────────────────────────────────
    private static final int REGION_BITS  = 5;
    private static final int REGION_SIZE  = 1 << REGION_BITS;
    private static final int REGION_MASK  = REGION_SIZE - 1;
    private static final int REGION_CELLS =
        REGION_SIZE * REGION_SIZE;
    private static final int BITSET_BYTES =
        (REGION_CELLS + 7) >> 3;

    private static final short UNSET_YEAR =
        Short.MIN_VALUE;

    // ── TIMING ────────────────────────────────────────────────
    private static final int FLUSH_INTERVAL_TICKS      = 200;
    private static final int STALE_SCAN_INTERVAL_TICKS = 100;
    private static final int STALE_SCAN_MAX_PER_TICK   = 50;

    // ── LOCK ──────────────────────────────────────────────────
    private static final Object LOCK = new Object();

    // ── PATHS ─────────────────────────────────────────────────
    private static volatile boolean initialized = false;
    private static volatile Path worldRoot;
    private static volatile Path baseDir;
    private static volatile Path regionsDir;
    private static volatile Path metaFile;

    // ── GLOBAL STATE ──────────────────────────────────────────
    private static volatile int globalGeneration = 0;
    private static volatile int lastJumpYear =
        TerraTimeManager.BASE_YEAR;

    // ── CACHES ────────────────────────────────────────────────
    private static final ConcurrentHashMap<Long, RegionData>
        regionCache = new ConcurrentHashMap<>();
    private static final Set<Long> dirtyRegions =
        ConcurrentHashMap.newKeySet();
    private static final Set<Long> loadedChunks =
        ConcurrentHashMap.newKeySet();

    // ── TICK COUNTER ──────────────────────────────────────────
    private static int tickCounter = 0;

    // ── DEBUG ─────────────────────────────────────────────────
    private static int debugLogCount = 0;
    private static final int DEBUG_LOG_MAX = 20;

    private PersistentChunkRefreshIndex() {}

    // ── REGION DATA ───────────────────────────────────────────
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

    // ═══════════════════════════════════════════════
    // PUBLIC API
    // ═══════════════════════════════════════════════

    public static void ensureInitialized(
            ServerLevel level) {
        if (level == null) return;
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
        ensureInitialized(level);
        loadedChunks.add(chunkKey(cx, cz));

        synchronized (LOCK) {
            RegionData region = getRegionInternal(
                regionX(cx), regionZ(cz));
            int idx = localIndex(cx, cz);

            if (region.visited.get(idx))
                return false;

            region.visited.set(idx);
            markDirty(region);

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

    public static boolean needsRefresh(
            ServerLevel level, int cx, int cz) {
        ensureInitialized(level);

        synchronized (LOCK) {
            RegionData region = getRegionInternal(
                regionX(cx), regionZ(cz));
            int idx = localIndex(cx, cz);

            if (!region.visited.get(idx))
                return false;

            short stored = region.chunkYears[idx];
            if (stored == UNSET_YEAR) return true;

            return stored != currentYearShort();
        }
    }

    public static void markChunkRefreshed(
            ServerLevel level, int cx, int cz) {
        ensureInitialized(level);

        synchronized (LOCK) {
            RegionData region = getRegionInternal(
                regionX(cx), regionZ(cz));
            int idx = localIndex(cx, cz);

            region.visited.set(idx);

            short current = currentYearShort();
            if (region.chunkYears[idx] != current) {
                region.chunkYears[idx] = current;
                markDirty(region);
            }
        }
    }

    public static void markChunkYear(
            ServerLevel level, int cx, int cz,
            int year) {
        ensureInitialized(level);

        synchronized (LOCK) {
            RegionData region = getRegionInternal(
                regionX(cx), regionZ(cz));
            int idx = localIndex(cx, cz);

            region.visited.set(idx);
            short y = clampYearToShort(year);
            if (region.chunkYears[idx] != y) {
                region.chunkYears[idx] = y;
                markDirty(region);
            }
        }
    }

    public static int getChunkYear(
            ServerLevel level, int cx, int cz) {
        ensureInitialized(level);

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
        ensureInitialized(level);

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
        return getVisitedChunksNearPlayers(
            level, radius);
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

    // ═══════════════════════════════════════════════
    // EVENT HOOKS
    // ═══════════════════════════════════════════════

    @SubscribeEvent
    public static void onLevelLoad(
            LevelEvent.Load event) {
        if (event.getLevel()
                instanceof ServerLevel level
                && level.dimension()
                == Level.OVERWORLD)
            ensureInitialized(level);
    }

    @SubscribeEvent
    public static void onLevelUnload(
            LevelEvent.Unload event) {
        if (event.getLevel()
                instanceof ServerLevel level
                && level.dimension()
                == Level.OVERWORLD) {
            synchronized (LOCK) {
                flushAllInternal();
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
        if (level.dimension() != Level.OVERWORLD)
            return;

        int cx = event.getChunk().getPos().x;
        int cz = event.getChunk().getPos().z;

        recordChunkSeen(level, cx, cz);

        if (!TerraTimeManager.isTimeEnabled())
            return;

        // Queue only — no inline block modification
        // to avoid chunk load deadlock
        if (needsRefresh(level, cx, cz)) {
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
        if (!initialized) return;

        tickCounter++;

        ServerLevel level = getOverworld();
        if (level == null) return;

        // ── FLUSH DIRTY REGIONS ───────────────────
        if (tickCounter % FLUSH_INTERVAL_TICKS == 0)
            flushDirtyRegionsInternal();

        // ── POST JUMP RESCAN ──────────────────────
        // When countdown reaches zero, scan ALL
        // in-memory chunks and:
        // 1. Call syncHistoricalWaterPublic on every
        //    chunk found in memory regardless of
        //    needsRefresh result. This is the fix for
        //    semi-loaded chunks that were marked as
        //    already refreshed before water sync ran.
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
                boolean usedChunkMap = false;

                // ── WATER SYNC VIA CHUNKMAP ───────
                // Reaches semi-loaded chunks that
                // getChunk(x,z,false) misses.
                // Must run regardless of needsRefresh
                // so chunks marked stale=false but
                // still containing water get fixed.
                if (gen != null) {
                    try {
                        var chunkSource =
                            level.getChunkSource();
                        var chunkMap =
                            chunkSource.chunkMap;

                        java.lang.reflect.Method
                            getChunks =
                            chunkMap.getClass()
                            .getDeclaredMethod(
                                "getChunks");
                        getChunks.setAccessible(true);

                        @SuppressWarnings("unchecked")
                        Iterable<net.minecraft
                            .server.level.ChunkHolder>
                            holders =
                            (Iterable<net.minecraft
                            .server.level.ChunkHolder>)
                            getChunks.invoke(chunkMap);

                        if (holders != null) {
                            usedChunkMap = true;
                            for (var holder : holders) {
                                totalScanned++;
                                LevelChunk lc = null;
                                try {
                                    lc = holder
                                        .getTickingChunk();
                                    if (lc == null) {
                                        var fut =
                                            holder
                                            .getFullChunkFuture();
                                        if (fut != null
                                            && fut
                                            .isDone()){
                                            var res =
                                                fut.getNow(
                                                    null);
                                            if (res
                                                != null){
                                                var opt =
                                                    res
                                                    .left();
                                                if (opt
                                                    != null
                                                    && opt
                                                    .isPresent())
                                                    lc =
                                                    opt
                                                    .get();
                                            }
                                        }
                                    }
                                    if (lc == null)
                                        continue;
                                    gen
                                    .syncHistoricalWaterPublic(
                                        level, lc);
                                    waterSynced++;
                                } catch (Exception e) {
                                    waterFailed++;
                                }
                            }
                        }
                    } catch (Exception e) {
                        LOGGER.warn(
                            "POST_JUMP_RESCAN "
                            + "chunkMap failed: {} "
                            + "— using snapshot "
                            + "fallback",
                            e.getMessage());
                    }

                    // ── FALLBACK ──────────────────
                    // If chunkMap did not work,
                    // fall back to snapshot.
                    if (!usedChunkMap
                            || totalScanned == 0) {
                        totalScanned = 0;
                        waterSynced  = 0;
                        waterFailed  = 0;
                        for (ChunkPos pos :
                                getLoadedChunkSnapshot(
                                    level)) {
                            totalScanned++;
                            try {
                                if (!level.hasChunk(
                                    pos.x, pos.z))
                                    continue;
                                LevelChunk lc =
                                    level.getChunk(
                                        pos.x, pos.z);
                                if (lc == null)
                                    continue;
                                gen
                                .syncHistoricalWaterPublic(
                                    level, lc);
                                waterSynced++;
                            } catch (Exception e) {
                                waterFailed++;
                            }
                        }
                    }
                }

                // ── QUEUE STALE CHUNKS ────────────
                // Also queue chunks that still need
                // vegetation and structure refresh.
                for (ChunkPos pos :
                        getLoadedChunkSnapshot(
                            level)) {
                    if (!level.hasChunk(
                            pos.x, pos.z))
                        continue;
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
                    + "waterSynced={} "
                    + "waterFailed={} "
                    + "queued={} skipped={} "
                    + "year={}",
                    usedChunkMap
                        ? "chunkMap" : "fallback",
                    totalScanned,
                    waterSynced,
                    waterFailed,
                    queued,
                    skipped,
                    TerraTimeManager
                        .getCurrentYear());
            }
        }

        // ── STALE SCAN ────────────────────────────
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
        synchronized (LOCK) {
            flushAllInternal();
            clearRuntimeOnly();
            initialized = false;
        }
        LOGGER.info(
            "PersistentChunkRefreshIndex saved "
            + "on shutdown");
    }

    // ═══════════════════════════════════════════════
    // INTERNAL REGION LOGIC
    // ═══════════════════════════════════════════════

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
                return new RegionData(rx, rz);
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
            return new RegionData(rx, rz);

        } catch (Exception e) {
            LOGGER.warn(
                "Failed loading region [{},{}]: {}",
                rx, rz, e.getMessage());
            return new RegionData(rx, rz);
        }
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
        regionCache.clear();
        dirtyRegions.clear();
        tickCounter = 0;
    }

    // ═══════════════════════════════════════════════
    // HELPERS
    // ═══════════════════════════════════════════════

    private static short currentYearShort() {
        return clampYearToShort(
            TerraTimeManager.getCurrentYear());
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