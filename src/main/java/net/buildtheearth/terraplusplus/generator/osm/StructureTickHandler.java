// ============================================================
// FILE: StructureTickHandler.java
// FIX: Moved drainRefreshQueue() BEFORE placement queue
//      null check so loaded chunks always refresh after
//      time jump even if StructureTickHandler not yet init.
//      Replaced notifyPlayers() with sendChunkToClients()
//      that sends per-block updates for structure blocks.
//      Added isLikelyStructureBlock() helper.
//      All other logic unchanged.
// ============================================================
package net.buildtheearth.terraplusplus.generator.osm;

import net.buildtheearth.terraplusplus.config.TerraWorldConfig;
import net.buildtheearth.terraplusplus.generator.TerraDensityFunctions;
import net.buildtheearth.terraplusplus.generator.TerraChunkGenerator;
import net.buildtheearth.terraplusplus.generator.historical.PersistentChunkRefreshIndex;
import net.buildtheearth.terraplusplus.generator.historical.TerraTimeCommand;
import net.buildtheearth.terraplusplus.generator.historical.TerraTimeManager;
import net.buildtheearth.terraplusplus.generator.historical.HistoricalDateParser;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.server.ServerLifecycleHooks;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Mod.EventBusSubscriber(modid = "terraplusplus")
public class StructureTickHandler {

    private static final Logger LOGGER =
        LogManager.getLogger("Terra++Tick");

    // ── TICK CONSTANTS ─────────────────────────────────────────
    private static final int  CHUNKS_PER_TICK     = 2;
    private static final int  MAX_BLOCKS_PER_TICK = 5000;
    // Bound CPU spent in one queue entry; large source tiles continue on later ticks.
    private static final int  MAX_STRUCTURES_PER_BATCH = 128;
    private static final int  TICK_INTERVAL       = 2;

    // ── WAITING MAP CONSTANTS ──────────────────────────────────
    private static final long WAITING_MAX_AGE_MS  = 300_000L;
    private static final int  WAITING_SCAN_INTERVAL = 20;
    private static final int  WAITING_MAX_SIZE    = 2048;

    // ── GRACE PERIOD CONSTANTS ─────────────────────────────────
    private static final int  GRACE_PERIOD_TICKS  = 600;
    private static final int  WARN_INTERVAL_TICKS = 100;
    private static final int  GRACE_MAX_SIZE       = 256;

    // ── YEAR GATE CONSTANTS ────────────────────────────────────
    private static final int  YEAR_CHECK_INTERVAL = 1200;

    // ── RESCAN CONSTANTS ───────────────────────────────────────
    private static final int  RESCAN_RADIUS       = 20;

    // ── STATIC STATE ───────────────────────────────────────────
    private static volatile StructurePlacementQueue
        placementQueue;
    private static volatile OsmStructureGenerator
        structureGen;
    private static volatile AsyncStructureManager
        asyncManager;
    private static volatile TerraDensityFunctions tdf;
    private static volatile TerraWorldConfig config;

    private static int tickCounter = 0;

    private static final AtomicInteger totalChunksPlaced =
        new AtomicInteger(0);
    private static final AtomicInteger totalBlocksPlaced =
        new AtomicInteger(0);
    private static final AtomicInteger dbgCount =
        new AtomicInteger(0);

    // ── WAITING MAP ────────────────────────────────────────────
    private static final ConcurrentHashMap<Long,WaitingEntry>
        waitingMap = new ConcurrentHashMap<>();

    private static class WaitingEntry {
        final StructurePlacementQueue.PlacementEntry entry;
        final long queuedTimeMs;
        WaitingEntry(
                StructurePlacementQueue.PlacementEntry e){
            this.entry = e;
            this.queuedTimeMs =
                System.currentTimeMillis();
        }
        boolean isExpired() {
            return System.currentTimeMillis()
                - queuedTimeMs > WAITING_MAX_AGE_MS;
        }
    }

    // ── GRACE PERIOD MAP ───────────────────────────────────────
    private static final ConcurrentHashMap<Long,GraceEntry>
        gracePeriodMap = new ConcurrentHashMap<>();

    private static class GraceEntry {
        final StructurePlacementQueue.PlacementEntry entry;
        final int startTick;
        int lastWarnTick;
        GraceEntry(
                StructurePlacementQueue.PlacementEntry e,
                int startTick) {
            this.entry = e;
            this.startTick = startTick;
            this.lastWarnTick = startTick;
        }
        boolean isGraceExpired(int currentTick) {
            return currentTick - startTick
                >= GRACE_PERIOD_TICKS;
        }
        boolean shouldWarn(int currentTick) {
            return currentTick - lastWarnTick
                >= WARN_INTERVAL_TICKS;
        }
    }

    // ── YEAR-GATED MAP ─────────────────────────────────────────
    private static final
        ConcurrentHashMap<Long,YearGatedEntry>
        yearGatedMap = new ConcurrentHashMap<>();

    private static class YearGatedEntry {
        final StructurePlacementQueue.PlacementEntry entry;
        final long queuedTimeMs;
        YearGatedEntry(
                StructurePlacementQueue.PlacementEntry e){
            this.entry = e;
            this.queuedTimeMs =
                System.currentTimeMillis();
        }
        boolean isExpired() {
            return System.currentTimeMillis()
                - queuedTimeMs > 86_400_000L;
        }
    }

    // ── CHUNK KEY ──────────────────────────────────────────────
    private static long chunkKey(int cx, int cz) {
        return ((long) cx << 32) | (cz & 0xFFFFFFFFL);
    }

    // ── INITIALIZATION ─────────────────────────────────────────
    public static void initialize(
            StructurePlacementQueue queue,
            OsmStructureGenerator generator,
            AsyncStructureManager async,
            TerraDensityFunctions densityFunctions,
            TerraWorldConfig worldConfig) {
        placementQueue = queue;
        structureGen   = generator;
        asyncManager   = async;
        tdf            = densityFunctions;
        config         = worldConfig;
        LOGGER.info(
            "StructureTickHandler initialized "
            +"({}chunks/tick, interval={}, "
            +"waitMax={}s, grace={}s, yearGate={}, "
            +"rescanRadius={})",
            CHUNKS_PER_TICK, TICK_INTERVAL,
            WAITING_MAX_AGE_MS / 1000,
            GRACE_PERIOD_TICKS / 20,
            worldConfig.timeEnabled ? "ON" : "OFF",
            RESCAN_RADIUS);
    }

    public static boolean isInitialized() {
        return placementQueue != null
            && structureGen != null;
    }

    public static StructurePlacementQueue
            getPlacementQueue() {
        return placementQueue;
    }

    public static AsyncStructureManager
            getAsyncManager() {
        return asyncManager;
    }

    // ── STATS ──────────────────────────────────────────────────
    public static int getTotalChunksPlaced() {
        return totalChunksPlaced.get();
    }
    public static int getTotalBlocksPlaced() {
        return totalBlocksPlaced.get();
    }
    public static int getWaitingMapSize() {
        return waitingMap.size();
    }
    public static int getGracePeriodMapSize() {
        return gracePeriodMap.size();
    }
    public static int getYearGatedMapSize() {
        return yearGatedMap.size();
    }

    // ══════════════════════════════════════════════════════════
    // MAIN TICK HANDLER
    // ══════════════════════════════════════════════════════════

    @SubscribeEvent
    public static void onServerTick(
            TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;

        tickCounter++;

        ServerLevel level = getOverworld();
        if (level == null) return;

        // ── ALWAYS DRAIN LOADED-CHUNK REFRESH QUEUE ───────────
        // This MUST run before the placement queue null
        // check. If it runs after, loaded chunks never
        // refresh after a time jump because StructureTickHandler
        // may not be initialized yet when the jump happens.
        // Unloaded chunks update correctly because they use
        // the normal generation path. Loaded chunks only
        // update via this drain call.
        if (TerraChunkGenerator.activeInstance != null
                && TerraChunkGenerator
                    .hasRefreshPending()) {
            try {
                TerraChunkGenerator.activeInstance
                    .drainRefreshQueue(level);
            } catch (Exception e) {
                LOGGER.debug(
                    "Refresh drain error: {}",
                    e.getMessage());
            }
        }

        // ── STRUCTURE PLACEMENT REQUIRES INIT ─────────────────
        // Everything below needs placementQueue and
        // structureGen. Return early if not ready.
        if (placementQueue == null
                || structureGen == null) return;

        // ── GRACE PERIOD ───────────────────────────────────────
        if (!gracePeriodMap.isEmpty())
            processGracePeriod(level);

        // ── WAITING MAP SCAN ───────────────────────────────────
        if (tickCounter % WAITING_SCAN_INTERVAL == 0
                && !waitingMap.isEmpty())
            scanWaitingMap(level);

        // ── YEAR-GATED SCAN ────────────────────────────────────
        if (tickCounter % YEAR_CHECK_INTERVAL == 0
                && !yearGatedMap.isEmpty())
            scanYearGatedMap(level);

        // ── POST JUMP SEMI-LOADED RESCAN ───────────────────────
        if (TerraChunkGenerator.postJumpRescanCountdown > 0){
            TerraChunkGenerator.postJumpRescanCountdown--;

            if (TerraChunkGenerator.postJumpRescanCountdown
                    == 0) {
                try {
                    List<ChunkPos> candidates =
                        PersistentChunkRefreshIndex
                        .getVisitedChunksNearPlayers(
                            level, RESCAN_RADIUS);

                    int inMemory  = 0;
                    int stale     = 0;
                    int requeued  = 0;

                    for (ChunkPos cp : candidates) {
                        ChunkAccess chunk =
                            level.getChunkSource()
                            .getChunk(
                                cp.x, cp.z, false);
                        boolean mem = chunk != null;
                        boolean isStale =
                            PersistentChunkRefreshIndex
                            .needsRefresh(
                                level, cp.x, cp.z);

                        if (mem) inMemory++;
                        if (isStale) stale++;

                        LOGGER.debug(
                            "RESCAN [{},{}] "
                            +"mem={} stale={}",
                            cp.x, cp.z, mem, isStale);

                        if (mem && isStale) {
                            TerraChunkGenerator
                                .scheduleVegRefreshOnly(
                                    level, cp);
                            requeued++;
                        }
                    }

                    LOGGER.info(
                        "RESCAN_DONE candidates={} "
                        +"inMemory={} stale={} "
                        +"requeued={}",
                        candidates.size(),
                        inMemory, stale, requeued);

                } catch (Exception e) {
                    LOGGER.warn(
                        "RESCAN error: {}",
                        e.getMessage());
                }
            }
        }

        // ── MAIN PLACEMENT LOOP ────────────────────────────────
        if (tickCounter % TICK_INTERVAL != 0) return;
        if (placementQueue.isEmpty()) return;

        int chunksProcessed = 0, blocksPlaced = 0;

        while (chunksProcessed < CHUNKS_PER_TICK
                && blocksPlaced < MAX_BLOCKS_PER_TICK) {

            StructurePlacementQueue.PlacementEntry entry =
                placementQueue.poll();
            if (entry == null) break;

            long key = chunkKey(
                entry.chunkX, entry.chunkZ);

            // ── YEAR GATE ──────────────────────────────────────
            if (config != null && config.timeEnabled) {
                if (!isEntryVisibleThisYear(entry)) {
                    if (!yearGatedMap.containsKey(key)
                            && yearGatedMap.size()
                            < GRACE_MAX_SIZE * 4) {
                        yearGatedMap.put(key,
                            new YearGatedEntry(entry));
                        if (dbgCount.get() < 50)
                            LOGGER.debug(
                                "Year-gated [{},{}] "
                                +"yr={}",
                                entry.chunkX,
                                entry.chunkZ,
                                TerraTimeManager
                                    .getCurrentYear());
                    }
                    continue;
                }
            }

            // ── CHUNK LOAD CHECK ───────────────────────────────
            if (!level.hasChunk(
                    entry.chunkX, entry.chunkZ)) {
                if (!waitingMap.containsKey(key)) {
                    if (waitingMap.size()
                            < WAITING_MAX_SIZE) {
                        waitingMap.put(key,
                            new WaitingEntry(entry));
                        if (dbgCount.get() < 50)
                            LOGGER.debug(
                                "Chunk [{},{}] not loaded"
                                +" ({} waiting)",
                                entry.chunkX,
                                entry.chunkZ,
                                waitingMap.size());
                    } else {
                        if (asyncManager != null)
                            asyncManager
                                .registerDeferred(
                                    entry.tileLat,
                                    entry.tileLon,
                                    entry.chunkX,
                                    entry.chunkZ);
                        LOGGER.debug(
                            "Waiting map full "
                            +"deferred [{},{}]",
                            entry.chunkX,
                            entry.chunkZ);
                    }
                }
                continue;
            }

            LevelChunk chunk = level.getChunk(
                entry.chunkX, entry.chunkZ);
            if (chunk == null) {
                placementQueue.requeue(entry);
                continue;
            }

            // ── PLAYER FOOTPRINT CHECK ─────────────────────────
            if (isAnyPlayerInFootprint(level, entry)) {
                if (!gracePeriodMap.containsKey(key)
                        && gracePeriodMap.size()
                        < GRACE_MAX_SIZE) {
                    gracePeriodMap.put(key,
                        new GraceEntry(
                            entry, tickCounter));
                    warnPlayersInFootprint(
                        level, entry, true);
                    if (dbgCount.get() < 50)
                        LOGGER.debug(
                            "Grace started [{},{}]",
                            entry.chunkX,
                            entry.chunkZ);
                }
                continue;
            }

            // ── CHUNK VALIDITY ─────────────────────────────────
            if (!isChunkStillValid(level, chunk)) {
                if (waitingMap.size() < WAITING_MAX_SIZE)
                    waitingMap.put(key,
                        new WaitingEntry(entry));
                continue;
            }

            // ── HISTORICAL REFRESH OWNERSHIP ─────────────────
            // A time jump has its own two-pass transaction. Letting this
            // normal async placement queue write the same features first was
            // exactly the duplicate path reported by PLACEMENT_TEST: the
            // queue placed a building, then TerraChunkGenerator placed it
            // again while completing the stale chunk refresh.
            if (TerraTimeManager.isTimeEnabled()
                    && PersistentChunkRefreshIndex.needsRefresh(
                        level, entry.chunkX, entry.chunkZ)) {
                TerraChunkGenerator.scheduleSingleChunkRefresh(level,
                    new ChunkPos(entry.chunkX, entry.chunkZ));
                placementQueue.markPlaced(entry);
                if (dbgCount.getAndIncrement() < 40)
                    LOGGER.info("Historical refresh owns async entry [{},{}]; normal placement skipped",
                        entry.chunkX, entry.chunkZ);
                continue;
            }

            // ── PLACE STRUCTURES ─────────────────────────────
            try {
                int from = Math.min(entry.nextStructureIndex, entry.structures.size());
                int to = Math.min(entry.structures.size(), from + MAX_STRUCTURES_PER_BATCH);
                List<OsmStructureIndex.OsmStructure> batch = entry.structures.subList(from, to);
                int placed = placeStructuresInChunk(chunk, entry, level, batch);
                // Covers the normal async placement queue as well as the
                // historical refresh path. Log only after successful placement.
                TerraChunkGenerator.placementDuplicateTest(chunk.getPos(), batch);
                entry.nextStructureIndex = to;
                blocksPlaced += placed;
                chunksProcessed++;
                totalBlocksPlaced.addAndGet(placed);
                chunk.setUnsaved(true);

                if (to < entry.structures.size()) {
                    placementQueue.requeueContinuation(entry);
                    if (dbgCount.getAndIncrement() < 20)
                        LOGGER.info("Placed {} blocks in [{},{}] batch {}-{} of {}",
                                placed, entry.chunkX, entry.chunkZ, from, to,
                                entry.structures.size());
                } else {
                    placementQueue.markPlaced(entry);
                    totalChunksPlaced.incrementAndGet();
                    if (dbgCount.getAndIncrement() < 20)
                        LOGGER.info("Placed {} blocks in [{},{}] ({} structs attempt {})",
                                placed, entry.chunkX, entry.chunkZ,
                                entry.structures.size(), entry.placementAttempts);
                    // Resync once per finished entry, not once per partial batch.
                    sendChunkToClients(level, chunk);
                }
            } catch (Exception e) {
                LOGGER.error("Failed structs in [{},{}]: {}",
                        entry.chunkX, entry.chunkZ, e.getMessage());
                placementQueue.requeue(entry);
            }
        }

        // ── PERIODIC STATS ─────────────────────────────────────
        if (tickCounter % 1200 == 0) {
            int qs =
                placementQueue.getActiveQueueSize();
            int ds = placementQueue.getDeferredSize();
            int ws = waitingMap.size();
            int gs = gracePeriodMap.size();
            int ys = yearGatedMap.size();
            int rs = TerraChunkGenerator
                .getRefreshQueueSize();
            int rc =
                TerraChunkGenerator
                .postJumpRescanCountdown;
            if (qs>0||ds>0||ws>0||gs>0
                    ||ys>0||rs>0||rc>0)
                LOGGER.info(
                    "Tick stats: q={} def={} wait={}"
                    +" grace={} yr={} ref={} "
                    +"rescan={} placed={}c/{}b",
                    qs,ds,ws,gs,ys,rs,rc,
                    totalChunksPlaced.get(),
                    totalBlocksPlaced.get());
        }
    }

    // ══════════════════════════════════════════════════════════
    // SEND CHUNK TO CLIENTS
    // ══════════════════════════════════════════════════════════
    // Called ONCE after all structures placed in chunk.
    // Sends all non-air blocks in structure height range
    // as one batch — no mid-placement chaos.
    private static void sendChunkToClients(
            ServerLevel level, LevelChunk chunk) {
        try {
            chunk.setUnsaved(true);
            int cmx = chunk.getPos().getMinBlockX();
            int cmz = chunk.getPos().getMinBlockZ();
            BlockPos.MutableBlockPos pos =
                new BlockPos.MutableBlockPos();

            int globalMinY = level.getMaxBuildHeight();
            int globalMaxY = level.getMinBuildHeight();

            for (int lx = 0; lx < 16; lx++) {
                for (int lz = 0; lz < 16; lz++) {
                    int sy;
                    try {
                        sy = chunk.getHeight(
                            Heightmap.Types
                                .WORLD_SURFACE,
                            lx, lz);
                    } catch (Exception e) {
                        continue;
                    }
                    if (sy - 2 < globalMinY)
                        globalMinY = sy - 2;
                    if (sy + 60 > globalMaxY)
                        globalMaxY = sy + 60;
                }
            }

            globalMinY = Math.max(globalMinY,
                level.getMinBuildHeight());
            globalMaxY = Math.min(globalMaxY,
                level.getMaxBuildHeight() - 1);

            for (int lx = 0; lx < 16; lx++) {
                for (int lz = 0; lz < 16; lz++) {
                    int bx = cmx + lx;
                    int bz = cmz + lz;
                    for (int y = globalMinY;
                            y <= globalMaxY; y++) {
                        pos.set(bx, y, bz);
                        BlockState st =
                            chunk.getBlockState(pos);
                        if (st.isAir()) continue;
                        level.sendBlockUpdated(
                            pos, st, st, 3);
                    }
                }
            }

            for (var be : chunk.getBlockEntities()
                    .values()) {
                level.blockEntityChanged(
                    be.getBlockPos());
            }
        } catch (Exception e) {
            LOGGER.debug(
                "sendChunkToClients [{},{}]: {}",
                chunk.getPos().x, chunk.getPos().z,
                e.getMessage());
        }
    }

    // ── STRUCTURE BLOCK DETECTION ──────────────────────────────
    private static boolean isLikelyStructureBlock(
            BlockState s) {
        if (s == null || s.isAir()) return false;
        return s.is(Blocks.WHITE_CONCRETE)
            || s.is(Blocks.LIGHT_GRAY_CONCRETE)
            || s.is(Blocks.GRAY_CONCRETE)
            || s.is(Blocks.BLACK_CONCRETE)
            || s.is(Blocks.BROWN_CONCRETE)
            || s.is(Blocks.CYAN_CONCRETE)
            || s.is(Blocks.YELLOW_CONCRETE)
            || s.is(Blocks.RED_CONCRETE)
            || s.is(Blocks.ORANGE_CONCRETE)
            || s.is(Blocks.LIME_CONCRETE)
            || s.is(Blocks.GLASS_PANE)
            || s.is(Blocks.LIGHT_BLUE_STAINED_GLASS)
            || s.is(Blocks.OAK_DOOR)
            || s.is(Blocks.IRON_DOOR)
            || s.is(Blocks.SPRUCE_DOOR)
            || s.is(Blocks.QUARTZ_BLOCK)
            || s.is(Blocks.SMOOTH_QUARTZ)
            || s.is(Blocks.QUARTZ_PILLAR)
            || s.is(Blocks.RAIL)
            || s.is(Blocks.IRON_BARS)
            || s.is(Blocks.LANTERN)
            || s.is(Blocks.SEA_LANTERN)
            || s.is(Blocks.GLOWSTONE)
            || s.is(Blocks.BRICKS)
            || s.is(Blocks.POLISHED_ANDESITE)
            || s.is(Blocks.POLISHED_DIORITE)
            || s.is(Blocks.SMOOTH_STONE)
            || s.is(Blocks.STONE_BRICKS)
            || s.is(Blocks.COBBLESTONE)
            || s.is(Blocks.STONE_BRICK_WALL)
            || s.is(Blocks.COBBLESTONE_WALL)
            || s.is(Blocks.OAK_FENCE)
            || s.is(Blocks.OAK_PLANKS)
            || s.is(Blocks.SPRUCE_PLANKS)
            || s.is(Blocks.BIRCH_PLANKS)
            || s.is(Blocks.DARK_OAK_PLANKS)
            || s.is(Blocks.OAK_LOG)
            || s.is(Blocks.STRIPPED_OAK_LOG)
            || s.is(Blocks.STONE_SLAB)
            || s.is(Blocks.SMOOTH_STONE_SLAB)
            || s.is(Blocks.OAK_STAIRS)
            || s.is(Blocks.COBBLESTONE_STAIRS)
            || s.is(Blocks.STONE_BRICK_STAIRS)
            || s.is(Blocks.TUFF)
            || s.is(Blocks.DIRT_PATH)
            || s.is(Blocks.GRAVEL);
    }

    // ══════════════════════════════════════════════════════════
    // GRACE PERIOD PROCESSOR
    // ══════════════════════════════════════════════════════════

    private static void processGracePeriod(
            ServerLevel level) {
        List<Long> toPlace  = new ArrayList<>();
        List<Long> toRemove = new ArrayList<>();

        for (var e : gracePeriodMap.entrySet()) {
            long key      = e.getKey();
            GraceEntry ge = e.getValue();
            boolean in = isAnyPlayerInFootprint(
                level, ge.entry);

            if (!in) {
                toPlace.add(key);
                if (dbgCount.get() < 50)
                    LOGGER.debug(
                        "Grace resolved [{},{}]",
                        ge.entry.chunkX,
                        ge.entry.chunkZ);
            } else if (ge.isGraceExpired(tickCounter)){
                teleportPlayersToRoof(level, ge.entry);
                toPlace.add(key);
                LOGGER.info(
                    "Grace expired [{},{}] teleporting",
                    ge.entry.chunkX, ge.entry.chunkZ);
            } else {
                if (ge.shouldWarn(tickCounter)) {
                    warnPlayersInFootprint(
                        level, ge.entry, false);
                    ge.lastWarnTick = tickCounter;
                }
            }
        }

        for (long key : toPlace) {
            GraceEntry ge =
                gracePeriodMap.remove(key);
            if (ge != null && placementQueue != null)
                placementQueue.requeuePriority(
                    ge.entry);
        }
        for (long key : toRemove)
            gracePeriodMap.remove(key);
    }

    // ══════════════════════════════════════════════════════════
    // PLAYER FOOTPRINT DETECTION
    // ══════════════════════════════════════════════════════════

    private static boolean isAnyPlayerInFootprint(
            ServerLevel level,
            StructurePlacementQueue.PlacementEntry
            entry) {
        if (tdf == null) return false;
        if (entry.structures == null
                || entry.structures.isEmpty())
            return false;
        int cbx = entry.chunkX * 16;
        int cbz = entry.chunkZ * 16;

        for (ServerPlayer p : level.players()) {
            double dx = p.getX() - (cbx + 8);
            double dz = p.getZ() - (cbz + 8);
            if (dx*dx + dz*dz > 64*64) continue;
            double pLat, pLon;
            try {
                double[] geo = tdf.toGeo(
                    (int)p.getX(), (int)p.getZ());
                if (geo == null) continue;
                pLon = geo[0]; pLat = geo[1];
                if (Double.isNaN(pLat)
                        || Double.isNaN(pLon))
                    continue;
            } catch (Exception e) { continue; }
            for (OsmStructureIndex.OsmStructure s :
                    entry.structures) {
                if (s.type != OsmStructureIndex
                        .StructureType.BUILDING
                    && s.type != OsmStructureIndex
                        .StructureType.BUILDING_PART)
                    continue;
                if (!s.isArea || s.nodes.length < 3)
                    continue;
                if (s.containsPoint(pLat, pLon))
                    return true;
            }
        }
        return false;
    }

    // ══════════════════════════════════════════════════════════
    // PLAYER WARNINGS
    // ══════════════════════════════════════════════════════════

    private static void warnPlayersInFootprint(
            ServerLevel level,
            StructurePlacementQueue.PlacementEntry
            entry, boolean isFirst) {
        if (tdf == null) return;
        if (entry.structures == null
                || entry.structures.isEmpty())
            return;
        int cbx = entry.chunkX * 16;
        int cbz = entry.chunkZ * 16;

        for (ServerPlayer p : level.players()) {
            double dx = p.getX() - (cbx + 8);
            double dz = p.getZ() - (cbz + 8);
            if (dx*dx + dz*dz > 64*64) continue;
            double pLat, pLon;
            try {
                double[] geo = tdf.toGeo(
                    (int)p.getX(), (int)p.getZ());
                if (geo == null) continue;
                pLon = geo[0]; pLat = geo[1];
                if (Double.isNaN(pLat)
                        || Double.isNaN(pLon))
                    continue;
            } catch (Exception e) { continue; }
            for (OsmStructureIndex.OsmStructure s :
                    entry.structures) {
                if (s.type != OsmStructureIndex
                        .StructureType.BUILDING
                    && s.type != OsmStructureIndex
                        .StructureType.BUILDING_PART)
                    continue;
                if (!s.isArea || s.nodes.length < 3)
                    continue;
                if (!s.containsPoint(pLat, pLon))
                    continue;
                GraceEntry ge = gracePeriodMap.get(
                    chunkKey(
                        entry.chunkX, entry.chunkZ));
                int secs = ge != null
                    ? Math.max(0,
                        (GRACE_PERIOD_TICKS
                        -(tickCounter-ge.startTick))
                        /20)
                    : GRACE_PERIOD_TICKS/20;
                String name =
                    (s.name != null
                    && !s.name.isEmpty())
                    ? "\""+s.name+"\""
                    : "a structure";
                String msg = isFirst
                    ? "§6[Terra++] §eA structure is "
                        +"emerging here ("+name+"). "
                        +"§cMove away within "
                        +secs+" seconds!"
                    : "§6[Terra++] §c"+name
                        +" will appear in "
                        +secs+"s! Move away!";
                p.sendSystemMessage(
                    Component.literal(msg));
                break;
            }
        }
    }

    // ══════════════════════════════════════════════════════════
    // TELEPORT TO ROOF
    // ══════════════════════════════════════════════════════════

    private static void teleportPlayersToRoof(
            ServerLevel level,
            StructurePlacementQueue.PlacementEntry
            entry) {
        if (tdf == null) return;
        if (entry.structures == null
                || entry.structures.isEmpty())
            return;
        int cbx = entry.chunkX * 16;
        int cbz = entry.chunkZ * 16;

        for (ServerPlayer p : level.players()) {
            double dx = p.getX() - (cbx + 8);
            double dz = p.getZ() - (cbz + 8);
            if (dx*dx + dz*dz > 64*64) continue;
            double pLat, pLon;
            try {
                double[] geo = tdf.toGeo(
                    (int)p.getX(), (int)p.getZ());
                if (geo == null) continue;
                pLon = geo[0]; pLat = geo[1];
                if (Double.isNaN(pLat)
                        || Double.isNaN(pLon))
                    continue;
            } catch (Exception e) { continue; }
            for (OsmStructureIndex.OsmStructure s :
                    entry.structures) {
                if (s.type != OsmStructureIndex
                        .StructureType.BUILDING
                    && s.type != OsmStructureIndex
                        .StructureType.BUILDING_PART)
                    continue;
                if (!s.isArea || s.nodes.length < 3)
                    continue;
                if (!s.containsPoint(pLat, pLon))
                    continue;
                int surfY = level.getHeight(
                    Heightmap.Types.WORLD_SURFACE,
                    (int)p.getX(), (int)p.getZ());
                int est = s.resolvedHeightBlocks();
                if (est < 0) est = 5;
                int roofY = Math.min(
                    surfY + est + 2,
                    level.getMaxBuildHeight() - 1);
                roofY = Math.max(roofY, surfY + 1);
                p.teleportTo(
                    p.getX(), (double)roofY, p.getZ());
                p.sendSystemMessage(
                    Component.literal(
                        "§6[Terra++] §cTime is up! "
                        +"Moved to the roof."));
                LOGGER.info(
                    "Teleported {} to roof Y={} "
                    +"at [{},{}]",
                    p.getName().getString(), roofY,
                    entry.chunkX, entry.chunkZ);
                break;
            }
        }
    }

    // ══════════════════════════════════════════════════════════
    // YEAR GATE
    // ══════════════════════════════════════════════════════════

    private static boolean isEntryVisibleThisYear(
            StructurePlacementQueue.PlacementEntry
            entry) {
        if (config == null || !config.timeEnabled)
            return true;
        if (entry.structures == null
                || entry.structures.isEmpty())
            return true;
        int yr = TerraTimeManager.getCurrentYear();
        for (OsmStructureIndex.OsmStructure s :
                entry.structures)
            if (s.isVisibleAt(yr)) return true;
        return false;
    }

    private static void scanYearGatedMap(
            ServerLevel level) {
        if (!config.timeEnabled) return;
        int yr = TerraTimeManager.getCurrentYear();
        List<Long> toRelease = new ArrayList<>();
        List<Long> toExpire  = new ArrayList<>();

        for (var e : yearGatedMap.entrySet()) {
            long key = e.getKey();
            YearGatedEntry ye = e.getValue();
            if (ye.isExpired()) {
                toExpire.add(key); continue;
            }
            if (isEntryVisibleThisYear(ye.entry)) {
                toRelease.add(key);
                if (dbgCount.get() < 50)
                    LOGGER.debug(
                        "Year gate released [{},{}] "
                        +"yr={}",
                        ye.entry.chunkX,
                        ye.entry.chunkZ, yr);
            }
        }

        for (long key : toRelease) {
            YearGatedEntry ye =
                yearGatedMap.remove(key);
            if (ye != null && placementQueue != null)
                placementQueue.requeuePriority(
                    ye.entry);
        }
        for (long key : toExpire)
            yearGatedMap.remove(key);

        if (!yearGatedMap.isEmpty()
                && tickCounter % 6000 == 0)
            LOGGER.debug(
                "Year-gated: {} entries yr={}",
                yearGatedMap.size(), yr);
    }

    // ══════════════════════════════════════════════════════════
    // WAITING MAP SCANNER
    // ══════════════════════════════════════════════════════════

    private static void scanWaitingMap(
            ServerLevel level) {
        long now = System.currentTimeMillis();
        List<Long> toRemove = new ArrayList<>();
        List<StructurePlacementQueue.PlacementEntry>
            toRequeue = new ArrayList<>();

        for (var e : waitingMap.entrySet()) {
            long key = e.getKey();
            WaitingEntry we = e.getValue();
            if (we.isExpired()) {
                toRemove.add(key);
                if (dbgCount.get() < 50)
                    LOGGER.debug(
                        "Waiting expired [{},{}] {}s",
                        we.entry.chunkX,
                        we.entry.chunkZ,
                        (now-we.queuedTimeMs)/1000);
                continue;
            }
            if (level.hasChunk(
                    we.entry.chunkX,
                    we.entry.chunkZ)) {
                toRemove.add(key);
                toRequeue.add(we.entry);
                if (dbgCount.get() < 50)
                    LOGGER.debug(
                        "Chunk [{},{}] loaded requeue",
                        we.entry.chunkX,
                        we.entry.chunkZ);
            }
        }

        for (long key : toRemove)
            waitingMap.remove(key);
        for (var entry : toRequeue)
            if (placementQueue != null)
                placementQueue.requeuePriority(entry);

        if (!waitingMap.isEmpty()
                && tickCounter % 200 == 0)
            LOGGER.debug(
                "Waiting: {} entries oldest={}s",
                waitingMap.size(),
                waitingMap.values().stream()
                    .mapToLong(w->now-w.queuedTimeMs)
                    .max().orElse(0)/1000);
    }

    // ══════════════════════════════════════════════════════════
    // CHUNK VALIDITY
    // ══════════════════════════════════════════════════════════

    private static boolean isChunkStillValid(
            ServerLevel level, LevelChunk chunk) {
        try {
            if (chunk == null) return false;
            if (!level.hasChunk(
                    chunk.getPos().x,
                    chunk.getPos().z))
                return false;
            chunk.getBlockState(new BlockPos(
                chunk.getPos().getMinBlockX(),
                chunk.getMinBuildHeight(),
                chunk.getPos().getMinBlockZ()));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    // ══════════════════════════════════════════════════════════
    // STRUCTURE PLACEMENT
    // ══════════════════════════════════════════════════════════

    private static int placeStructuresInChunk(
            LevelChunk chunk,
            StructurePlacementQueue.PlacementEntry entry,
            ServerLevel level,
            List<OsmStructureIndex.OsmStructure> batch) {
        if (tdf == null || config == null) return 0;
        int cx = chunk.getPos().getMinBlockX();
        int cz = chunk.getPos().getMinBlockZ();
        BlockPos.MutableBlockPos pos =
            new BlockPos.MutableBlockPos();
        int blocksPlaced = 0;

        double[][] geoLat = new double[16][16];
        double[][] geoLon = new double[16][16];
        int[][] surfaceYs = new int[16][16];
        boolean[][] valid = new boolean[16][16];
        double minLat = Double.POSITIVE_INFINITY, maxLat = Double.NEGATIVE_INFINITY;
        double minLon = Double.POSITIVE_INFINITY, maxLon = Double.NEGATIVE_INFINITY;

        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                double[] geo =
                    tdf.toGeo(cx+lx, cz+lz);
                if (geo == null
                        || Double.isNaN(geo[0])
                        || Double.isNaN(geo[1])) {
                    valid[lx][lz] = false;
                    continue;
                }
                geoLon[lx][lz] = geo[0];
                geoLat[lx][lz] = geo[1];
                minLon = Math.min(minLon, geo[0]); maxLon = Math.max(maxLon, geo[0]);
                minLat = Math.min(minLat, geo[1]); maxLat = Math.max(maxLat, geo[1]);
                TerraDensityFunctions.TerrainColumn
                    col = tdf.classify(cx+lx, cz+lz);
                surfaceYs[lx][lz] = col.surfaceY;
                valid[lx][lz] = true;
            }
        }

        // Defensive second-stage filter. AsyncStructureManager filters before
        // enqueue, but entries may also arrive from deferred/legacy paths.
        // Avoid 256 × thousands of needless point-vs-structure checks.
        final double structureMargin = 0.0005;
        List<OsmStructureIndex.OsmStructure> localStructures = new ArrayList<>();
        for (OsmStructureIndex.OsmStructure structure : batch) {
            if (structure.maxLat < minLat - structureMargin
                    || structure.minLat > maxLat + structureMargin
                    || structure.maxLon < minLon - structureMargin
                    || structure.minLon > maxLon + structureMargin)
                continue;
            localStructures.add(structure);
        }
        if (localStructures.isEmpty()) return 0;

        int cellCount = 0;
        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                if (!valid[lx][lz]) continue;
                double lat = geoLat[lx][lz];
                double lon = geoLon[lx][lz];
                int sy = surfaceYs[lx][lz];

                for (OsmStructureIndex.OsmStructure s
                        : localStructures) {
                    if (!isEnabled(s.type, config))
                        continue;
                    if (config.timeEnabled
                            && !s.isVisibleAt(
                            TerraTimeManager
                                .getCurrentYear()))
                        continue;
                    double m = 0.0005;
                    if (lat < s.minLat-m
                        || lat > s.maxLat+m
                        || lon < s.minLon-m
                        || lon > s.maxLon+m)
                        continue;
                    BlockState before =
                        chunk.getBlockState(
                            pos.set(cx+lx, sy, cz+lz));
                    structureGen.placeSingleBlock(
                        chunk, pos, cx+lx, cz+lz, sy,
                        s, lat, lon, entry.structures);
                    BlockState after =
                        chunk.getBlockState(
                            pos.set(cx+lx, sy, cz+lz));
                    if (before != after) blocksPlaced++;
                }

                cellCount++;
                if (cellCount % 64 == 0) {
                    if (!isChunkStillValid(
                            level, chunk)) {
                        LOGGER.debug(
                            "Chunk [{},{}] invalid "
                            +"mid-place ({} blocks)",
                            entry.chunkX, entry.chunkZ,
                            blocksPlaced);
                        return blocksPlaced;
                    }
                }
            }
        }
        return blocksPlaced;
    }

    private static boolean isEnabled(
            OsmStructureIndex.StructureType type,
            TerraWorldConfig cfg) {
        return switch (type) {
            case BUILDING,
                 BUILDING_PART -> cfg.buildings;
            case ROAD,
                 FOOTWAY,
                 CYCLEWAY     -> cfg.roads;
            case RAILWAY      -> cfg.railways;
            case BRIDGE       -> cfg.bridges;
            case WALL,
                 FENCE,
                 BARRIER      -> cfg.walls;
            case GARDEN,
                 PARK,
                 HEDGE        -> cfg.gardens;
            case FARMLAND,
                 FOREST,
                 MEADOW,
                 PARKING      -> cfg.landuse;
            case STREET_LAMP  -> cfg.streetLights;
        };
    }

    // ── UTILITY ────────────────────────────────────────────────
    private static ServerLevel getOverworld() {
        try {
            var server = ServerLifecycleHooks
                .getCurrentServer();
            if (server == null) return null;
            return server.overworld();
        } catch (Exception e) {
            return null;
        }
    }
}