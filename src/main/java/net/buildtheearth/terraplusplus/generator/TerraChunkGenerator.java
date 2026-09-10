// ============================================================
// FILE: TerraChunkGenerator.java
// FIX: Removed instant road placement from refresh passes.
//      Roads now generate with buildings in pass 2 via
//      OsmStructureGenerator.generateStructuresFromList().
//      Loaded chunks behave identically to unloaded chunks.
//      Client chunk resync via ClientboundLevelChunkWithLightPacket
//      after structure placement so nothing is invisible.
//      Removed: placeRoadBlockDirect, road separation in
//      refreshChunkForYear, road filtering in
//      refreshBuildingsForChunk, road logic in
//      placeWorldCoverVegetation.
//      All prior fixes retained.
// ============================================================
package net.buildtheearth.terraplusplus.generator;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.buildtheearth.terraplusplus.config.TerraWorldConfig;
import net.buildtheearth.terraplusplus.generator.historical.*;
import net.buildtheearth.terraplusplus.generator.osm.*;
import net.buildtheearth.terraplusplus.generator.water.*;
import net.minecraft.core.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.*;
import net.minecraft.world.level.biome.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.chunk.*;
import net.minecraft.world.level.levelgen.*;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraftforge.server.ServerLifecycleHooks;
import org.apache.logging.log4j.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public class TerraChunkGenerator extends ChunkGenerator {

    private static final Logger LOGGER =
        LogManager.getLogger("Terra++Generator");

    public static final Codec<TerraChunkGenerator> CODEC =
        RecordCodecBuilder.create(i -> i.group(
            BiomeSource.CODEC.fieldOf("biome_source")
                .forGetter(g -> g.biomeSource),
            Codec.STRING.optionalFieldOf("projection","bte")
                .forGetter(g -> g.projectionName),
            Codec.INT.optionalFieldOf("zoom",15)
                .forGetter(g -> g.zoom),
            TerraWorldConfig.CODEC
                .optionalFieldOf("world_config",
                    TerraWorldConfig.DEFAULT)
                .forGetter(g -> g.worldConfig),
            // Axis 6: which universe this dimension hosts.
            Codec.STRING.optionalFieldOf("universe",
                    UniverseTransforms.PRIME)
                .forGetter(g -> g.universeId)
        ).apply(i, TerraChunkGenerator::new));

    // ── CONSTANTS ─────────────────────────────────────────────
    private static final int SL =
        TerraDensityFunctions.SEA_LEVEL;
    private static final int MY =
        TerraDensityFunctions.MIN_Y;
    private static final int XY =
        TerraDensityFunctions.MAX_Y;
    private static final int GD =
        TerraDensityFunctions.GEN_DEPTH;
    private static final double WEM =
        TerraDensityFunctions.DRY_LAND_MIN_ELEV;
    private static final int B=24,G=16+B*2,MD=1,
        LD=1800,LT=100;
    private static final int BSWD=40,BSWR=8,FMAW=200;
    private static final long WATER_TILE_WAIT_MS=12000L;
    private static final double
        DEPRESSION_THRESHOLD_RIVER=3.0;
    private static final double
        DEPRESSION_THRESHOLD_LAKE=5.0;
    private static final int LAKE_MIN_CELLS=6;
    private static final long
        WATER_RETRY_MAX_AGE_MS=300_000L;
    private static final long
        STRUCTURE_FETCH_TIMEOUT_MS=8000L;
    /** FAST-TELEPORT FIX (run 13): chunk generation used to
     *  block up to 8s PER SOURCE inside applyBiomeDecoration
     *  waiting for downloads — destination chunks could not
     *  reach FULL until Overpass answered, so /tpll waited on
     *  the network (126s in run 13) while the terrain itself
     *  was ready in 1s. Generation now waits at most this long
     *  (covers disk/memory hits + a warm fast-API fetch); on a
     *  miss the appetizer serves resident Overture data and the
     *  proven swap machinery (117/117 in run 13) upgrades the
     *  chunk when the main course lands. */
    public static final long GEN_STRUCT_WAIT_MS=1500L;
    /** ORDER->APPETIZER LATENCY FIX: the generation-path
     *  appetizer polled at 0ms — but /tpll's prefetch puts the
     *  Overture tiles in flight ~2-3s before chunks decorate,
     *  so a 0ms poll missed tiles that were SECONDS from
     *  landing and the chunk generated bare. Decoration runs on
     *  Worker-Main async threads (not the server thread), so a
     *  short bounded wait is legal there — the old code blocked
     *  8s on these very threads. getStructuresForArea already
     *  dispatches all missing tiles in parallel and plates
     *  whatever is resident at the deadline (partial serve).
     *  Server-thread polls (fetchStructuresCachedOnly) stay 0ms. */
    public static final long GEN_APPETIZER_WAIT_MS=1500L;
    private static final int
        PRESENCE_EMPTY=0,
        PRESENCE_TERRAIN=1,
        PRESENCE_DECORATED=2;
    private static final int[]
        BDX={-1,1,0,0,-1,-1,1,1};
    private static final int[]
        BDZ={0,0,-1,1,-1,1,-1,1};

        // ADD alongside REFRESH_PER_TICK:
    private static final int
        // Post-jump work must not turn one server tick into a large source and
        // vegetation burst. Nearby chunks are ordered first; throughput stays
        // bounded while the queue drains over subsequent ticks.
        REFRESH_PER_TICK_BURST = 12;

    // ── BLOCK STATES ──────────────────────────────────────────
    private static final BlockState
        BEDROCK  =Blocks.BEDROCK.defaultBlockState(),
        DEEPSLATE=Blocks.DEEPSLATE.defaultBlockState(),
        STONE    =Blocks.STONE.defaultBlockState(),
        DIRT     =Blocks.DIRT.defaultBlockState(),
        GRAVEL   =Blocks.GRAVEL.defaultBlockState(),
        CLAY     =Blocks.CLAY.defaultBlockState(),
        SAND     =Blocks.SAND.defaultBlockState(),
        SANDSTONE=Blocks.SANDSTONE.defaultBlockState(),
        WATER    =Blocks.WATER.defaultBlockState(),
        LAVA     =Blocks.LAVA.defaultBlockState(),
        SNOW     =Blocks.SNOW.defaultBlockState(),
        GRASS    =Blocks.GRASS_BLOCK.defaultBlockState(),
        AIR      =Blocks.AIR.defaultBlockState(),
        SEAGRASS =Blocks.SEAGRASS.defaultBlockState(),
        TSGL=Blocks.TALL_SEAGRASS.defaultBlockState()
            .setValue(DoublePlantBlock.HALF,
                DoubleBlockHalf.LOWER),
        TSGU=Blocks.TALL_SEAGRASS.defaultBlockState()
            .setValue(DoublePlantBlock.HALF,
                DoubleBlockHalf.UPPER),
        DBUSH =Blocks.DEAD_BUSH.defaultBlockState(),
        CACTUS=Blocks.CACTUS.defaultBlockState(),
        COARSE_DIRT=
            Blocks.COARSE_DIRT.defaultBlockState(),
        RED_SAND=Blocks.RED_SAND.defaultBlockState(),
        TUFF    =Blocks.TUFF.defaultBlockState();

    // ── BUILT_UP SURFACE BLOCKS ───────────────────────────────
    private static final BlockState
        BUILTUP_GRASS =
            Blocks.GRASS_BLOCK.defaultBlockState(),
        BUILTUP_COARSE=
            Blocks.COARSE_DIRT.defaultBlockState(),
        BUILTUP_DPATH =
            Blocks.DIRT_PATH.defaultBlockState(),
        BUILTUP_TUFF  =
            Blocks.TUFF.defaultBlockState();

    // ── BUILT_UP ERA BOUNDARIES ───────────────────────────────
    private static final int
        BUILTUP_DENSE_FOREST_END =-1000,
        BUILTUP_THIN_FOREST_END  =    0,
        BUILTUP_SPARSE_TREES_END =  500,
        BUILTUP_GRASSLAND_END    = 1000,
        BUILTUP_COARSE_DIRT_END  = 1500,
        BUILTUP_DIRT_PATH_END    = 1800,
        BUILTUP_TUFF_START       = 1900;

    // ── FIELDS ────────────────────────────────────────────────
    private final String projectionName;
    private final int zoom;
    /** Axis 6: universe id hosted by this generator/dimension. */
    private final String universeId;
    public String getUniverseId(){ return universeId; }
    private volatile TerraWorldConfig worldConfig;
    private volatile boolean configResolved=false;
    private volatile TerraDensityFunctions df;
    private volatile RiverLakeEngine rle;
    private volatile AsyncWaterManager asyncWaterManager;
    private volatile TerraBiomeProvider terraBiomes;
    private volatile OsmStructureIndex structureIndex;
    private volatile OsmStructureGenerator
        structureGenerator;
    private volatile MicrosoftIndex overtureIndex;
    private volatile SourceCooperativeIndex vidaIndex;
    private volatile AsyncStructureManager asyncManager;
    private volatile StructurePlacementQueue
        placementQueue;
    private volatile EsaWorldCoverIndex worldCover;
    private volatile OhmStructureIndex ohmIndex;
    private volatile HistoricalStructureManager
        historicalManager;

    private final AtomicInteger dbgCnt  =
        new AtomicInteger();
    private final AtomicInteger biomeDbg=
        new AtomicInteger();
    private final AtomicInteger surfDbg =
        new AtomicInteger();
    private final AtomicInteger reachDbg=
        new AtomicInteger();

    private final ConcurrentHashMap<Long,Boolean>
        terrChunks=new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long,Boolean>
        strChunks=new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long,long[]>
        waterRetryChunks=new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long,
        List<OhmStructureIndex.OhmStructure>>
        upcomingCache=new ConcurrentHashMap<>();

    // Stores fetched structures per chunk so all passes
    // share one fetch
    private final ConcurrentHashMap<Long,
        List<OsmStructureIndex.OsmStructure>>
        refreshStructureCache=new ConcurrentHashMap<>();

    // One historical refresh can span ticks: vegetation/water is applied in
    // pass 1 and structures in pass 2. Keep the exact writes until pass 2
    // commits the complete ownership record.
    private final ConcurrentHashMap<Long,
        List<GeneratedBlockOwnershipIndex.OwnedBlock>> refreshOwnershipCache=
        new ConcurrentHashMap<>();
    private static final ThreadLocal<List<GeneratedBlockOwnershipIndex.OwnedBlock>>
        historicalWriteCapture = new ThreadLocal<>();

    private static void beginHistoricalWriteCapture() {
        historicalWriteCapture.set(new ArrayList<>());
    }

    private static List<GeneratedBlockOwnershipIndex.OwnedBlock> endHistoricalWriteCapture() {
        List<GeneratedBlockOwnershipIndex.OwnedBlock> writes = historicalWriteCapture.get();
        historicalWriteCapture.remove();
        return writes == null ? List.of() : writes;
    }

    /** Historical code must use this instead of direct level.setBlock(). The
     * wrapper records exact final states only while a refresh transaction is
     * active, so normal gameplay and generation are never claimed as owned. */
    private static void setHistoricalBlock(net.minecraft.server.level.ServerLevel level,
            BlockPos pos, BlockState state, int flags) {
        level.setBlock(pos, state, flags);
        List<GeneratedBlockOwnershipIndex.OwnedBlock> writes = historicalWriteCapture.get();
        if (writes != null) writes.add(new GeneratedBlockOwnershipIndex.OwnedBlock(
            pos.asLong(), Block.getId(state)));
    }

    private static volatile String activeSource="both";
    public static void setActiveSource(String s){
        activeSource=s;}
    public static String getActiveSource(){
        return activeSource;}

    // ── LOADED CHUNK REFRESH — TWO PASS ──────────────────────
    // Pass 1: vegetation only (10/tick)
    //         Clears trees at structure footprints
    //         No road placement
    // Pass 2: ALL structures — roads + buildings together
    //         via OsmStructureGenerator (2/tick)
    //         Client resync via chunk packet after placement
    private static final ConcurrentLinkedQueue<ChunkPos>
        refreshQueue=new ConcurrentLinkedQueue<>();
    private static final ConcurrentLinkedQueue<ChunkPos>
        structureRefreshQueue=
            new ConcurrentLinkedQueue<>();
    private static final Set<Long>
        queuedVegRefresh=ConcurrentHashMap.newKeySet();
    private static final Set<Long>
        queuedStructRefresh=ConcurrentHashMap.newKeySet();
    // A chunk may become structurally ready before its vegetation pass has
    // obtained source data.  Do not let pass 2 mark it current in that order:
    // doing so permanently skips pass 1 and leaves old trees/ground behind.
    // This is deliberately runtime-only; after a restart chunkYear makes an
    // interrupted refresh stale again and the two phases restart safely.
    private static final Set<Long>
        vegetationPhaseComplete=ConcurrentHashMap.newKeySet();

    // Diagnostic-only duplicate detector. It does not block placement: it
    // records whether the same feature signature is successfully sent to the
    // same chunk more than once in one time-jump generation.
    private static final ConcurrentHashMap<String, Long>
        successfulPlacementSignatures = new ConcurrentHashMap<>();
    private static final int PLACEMENT_TEST_MAX = 200_000;

    // ── REFRESH ENGINE (unified pipeline; replaces the dual-queue
    //    system when enabled). Toggle for A/B migration safety.
    public static volatile boolean USE_REFRESH_ENGINE = true;
    // ── APPETIZER PROTOCOL (cold-start progressive serve) ──
    // OSM pending -> serve fast sources provisionally, swap on
    // OSM arrival. Toggle for A/B safety, same as the engine.
    public static volatile boolean APPETIZER_SERVING = true;
    private volatile net.buildtheearth.terraplusplus.generator
        .historical.RefreshEngine refreshEngine;

    public net.buildtheearth.terraplusplus.generator.historical
            .RefreshEngine getRefreshEngine() {
        if (refreshEngine == null) synchronized (this) {
            if (refreshEngine == null)
                refreshEngine = new net.buildtheearth.terraplusplus
                    .generator.historical.RefreshEngine(
                        new EngineWorker(), 6);
        }
        return refreshEngine;
    }

    /** Adapter: the engine drives, these proven methods place. */
    private final class EngineWorker implements
            net.buildtheearth.terraplusplus.generator.historical
                .RefreshEngine.ChunkWorker {
        @Override public List<?> peekData(
                net.minecraft.server.level.ServerLevel level,
                ChunkPos pos) {
            try {
                if (!worldConfig.hasAnyStructures())
                    return java.util.List.of();
                LevelChunk ch = level.getChunk(pos.x, pos.z);
                return fetchStructuresCachedOnly(level, ch);
            } catch (Exception e) { return null; }
        }
        @Override public void requestData(
                net.minecraft.server.level.ServerLevel level,
                ChunkPos pos) {
            // fetchStructuresCachedOnly already queues the async
            // retry on a miss; nothing more needed here.
        }
        @Override public void escalateData(
                net.minecraft.server.level.ServerLevel level,
                ChunkPos pos) {
            // Tile wanted >10s: push a prefetch straight at the
            // structure index (single-tile fast path, endpoint
            // rotation included) instead of waiting for the async
            // manager's own retry cadence.
            try {
                var d = getDf();
                if (d == null) return;
                double[] gc = d.toGeo(pos.getMinBlockX() + 8,
                    pos.getMinBlockZ() + 8);
                if (gc == null) return;
                getStructureIndex().prefetchTiles(
                    gc[1] - 0.006, gc[1] + 0.006,
                    gc[0] - 0.006, gc[0] + 0.006);
            } catch (Exception ignored) {}
        }
        @Override public boolean runClearing(
                net.minecraft.server.level.ServerLevel level,
                ChunkPos pos, List<?> data) {
            try {
                LevelChunk ch = level.getChunk(pos.x, pos.z);
                @SuppressWarnings("unchecked")
                List<OsmStructureIndex.OsmStructure> structs =
                    (List<OsmStructureIndex.OsmStructure>) data;
                if (!GeneratedBlockOwnershipIndex
                        .clearOwnedBlocks(level, pos))
                    return false;
                PersistentChunkRefreshIndex
                    .markChunkRefreshing(pos.x, pos.z);
                beginHistoricalWriteCapture();
                boolean ok = refreshChunkForYear(level, ch, structs);
                List<GeneratedBlockOwnershipIndex.OwnedBlock> writes =
                    endHistoricalWriteCapture();
                if (!ok) return false;
                long k = ck(pos.x, pos.z);
                refreshOwnershipCache.put(k, writes);
                if (structs != null && !structs.isEmpty())
                    refreshStructureCache.put(k, structs);
                clearBoundaryOrphans(level, pos.x, pos.z);
                return true;
            } catch (Exception e) {
                endHistoricalWriteCapture();
                return false;
            }
        }
        @Override public boolean runStructures(
                net.minecraft.server.level.ServerLevel level,
                ChunkPos pos, List<?> data) {
            try {
                LevelChunk ch = level.getChunk(pos.x, pos.z);
                return refreshStructuresForChunk(level, ch);
            } catch (Exception e) { return false; }
        }
        @Override public void markDone(
                net.minecraft.server.level.ServerLevel level,
                ChunkPos pos) {
            PersistentChunkRefreshIndex
                .markChunkRefreshed(level, pos.x, pos.z);
        }
    }

    public static volatile TerraChunkGenerator
        activeInstance=null;
    /**
     * Half-width of the OSM/Overture structure prefetch window, in degrees.
     *
     * OSM tiles are 0.01 deg, so 0.005 covers the chunk's own tile plus the
     * immediate neighbours it will spill into - enough to hide latency without
     * flooding Overpass. Larger values were rejected: the rate limiter is
     * shared, and over-fetching starves the tile the player is standing on.
     */
    private static final double STRUCT_PREFETCH_DEG=0.005;

    private static final int REFRESH_PER_TICK=10;
    private static final int STRUCT_REFRESH_PER_TICK=2;
    private static final int TIMEJUMP_IMMEDIATE_RADIUS = 20;
    private static final int TIMEJUMP_IMMEDIATE_MAX = 256;
    private static final int TIMEJUMP_BOUNDARY_RING_MAX = 128;
    private static final int TIMEJUMP_BOUNDARY_LOADS_PER_TICK = 2;
    private static final ConcurrentLinkedQueue<ChunkPos> timeJumpBoundaryRing = new ConcurrentLinkedQueue<>();
    private static volatile boolean timeJumpBoundaryAwaiting = false;
        public static volatile int
        postJumpRescanCountdown = 0;

    // ── CONSTRUCTORS ──────────────────────────────────────────
    public TerraChunkGenerator(BiomeSource bs,String pn,
            int z,TerraWorldConfig wc,String universe){
        super(bs);
        projectionName=pn;zoom=z;worldConfig=wc;
        universeId=UniverseTransforms.get(universe).id();
        // The PRIME generator is the shared singleton other
        // systems reach through activeInstance; exotic-universe
        // dimensions must not steal it.
        if(UniverseTransforms.PRIME.equals(universeId)
                ||activeInstance==null)
            activeInstance=this;
        LOGGER.info("TerraChunkGenerator: proj={} zoom={}"
            +" Y=[{},{}] universe={} cfg={}",
            pn,z,MY,XY,universeId,wc);
    }
    public TerraChunkGenerator(BiomeSource bs,String pn,
            int z,TerraWorldConfig wc){
        this(bs,pn,z,wc,UniverseTransforms.PRIME);}
    public TerraChunkGenerator(BiomeSource bs,String pn,
            int z){
        this(bs,pn,z,TerraWorldConfig.DEFAULT);}
    public TerraChunkGenerator(BiomeSource bs,String pn){
        this(bs,pn,15);}
    public TerraWorldConfig getWorldConfig(){
        resolveConfig();return worldConfig;}

    // ── REFRESH QUEUE PUBLIC API ──────────────────────────────
    private static long rk(int cx,int cz){
        return((long)cx<<32)|(cz&0xFFFFFFFFL);}

    private static boolean qv(ChunkPos cp){
        long k=rk(cp.x,cp.z);
        if(!queuedVegRefresh.add(k))return false;
        // A newly scheduled year invalidates a completion from an earlier
        // refresh generation. Internal retry paths do not call qv(), so they
        // retain their completed prerequisite.
        vegetationPhaseComplete.remove(k);
        refreshQueue.add(cp);
        return true;}

    private static boolean qs(ChunkPos cp){
        long k=rk(cp.x,cp.z);
        if(!queuedStructRefresh.add(k))return false;
        structureRefreshQueue.add(cp);
        return true;}

    /** A time jump supersedes every pre-jump queue entry. Keeping those
     * entries made qv()/qs() reject the newly sorted nearby work, so old spawn
     * chunks consumed the budget while the player's loaded chunks waited. */
    public static void resetRefreshQueuesForTimeJump() {
        if (activeInstance != null
                && activeInstance.refreshEngine != null)
            activeInstance.refreshEngine.clear();
        refreshQueue.clear();
        structureRefreshQueue.clear();
        queuedVegRefresh.clear();
        queuedStructRefresh.clear();
        vegetationPhaseComplete.clear();
        successfulPlacementSignatures.clear();
        timeJumpBoundaryRing.clear();
        timeJumpBoundaryAwaiting = false;
        TerraChunkGenerator instance = activeInstance;
        if (instance != null) {
            instance.refreshStructureCache.clear();
            instance.refreshOwnershipCache.clear();
            // Prevent an old async placement entry from racing the new
            // historical transaction after a time jump.
            instance.getPlacementQueue().discardActiveForTimeJump();
        }
    }

    public static void captureTimeJumpBoundaryRing(net.minecraft.server.level.ServerLevel level) {
        if (level == null) return;
        Set<Long> unique = new HashSet<>();
        List<ChunkPos> candidates = new ArrayList<>();
        for (ChunkPos base : PersistentChunkRefreshIndex.getLoadedChunkSnapshot(level))
            for (int dx=-1; dx<=1; dx++) for (int dz=-1; dz<=1; dz++) {
                if (dx==0 && dz==0) continue;
                int x=base.x+dx, z=base.z+dz;
                if (!level.hasChunk(x,z) && unique.add(rk(x,z))) candidates.add(new ChunkPos(x,z));
            }
        candidates.sort(java.util.Comparator.comparingLong(cp -> {
            long best=Long.MAX_VALUE;
            for(ServerPlayer p:level.players()) { long dx=(long)cp.x-p.chunkPosition().x, dz=(long)cp.z-p.chunkPosition().z; best=Math.min(best,dx*dx+dz*dz); }
            return best;
        }));
        timeJumpBoundaryRing.clear();
        for (int i=0;i<Math.min(TIMEJUMP_BOUNDARY_RING_MAX,candidates.size());i++) timeJumpBoundaryRing.offer(candidates.get(i));
        timeJumpBoundaryAwaiting=!timeJumpBoundaryRing.isEmpty();
        LOGGER.info("TIMEJUMP_BOUNDARY_RING captured={} queued={}", candidates.size(), timeJumpBoundaryRing.size());
    }

    public static void processTimeJumpBoundaryRing(net.minecraft.server.level.ServerLevel level) {
        if (!timeJumpBoundaryAwaiting || level==null || !PersistentChunkRefreshIndex.isTimeJumpAuditComplete()) return;
        for (int i=0;i<TIMEJUMP_BOUNDARY_LOADS_PER_TICK;i++) {
            ChunkPos pos=timeJumpBoundaryRing.poll();
            if(pos==null){timeJumpBoundaryAwaiting=false;break;}
            try { level.getChunk(pos.x,pos.z); PersistentChunkRefreshIndex.invalidateChunk(level,pos.x,pos.z); scheduleSingleChunkRefresh(level,pos); }
            catch(Exception e){LOGGER.debug("TIMEJUMP_BOUNDARY_RING [{},{}]: {}",pos.x,pos.z,e.getMessage());}
        }
        if(!timeJumpBoundaryAwaiting) LOGGER.info("TIMEJUMP_BOUNDARY_RING complete");
    }

    public static void scheduleSingleChunkRefresh(
            net.minecraft.server.level.ServerLevel level,
            ChunkPos cp){
        if(activeInstance==null||level==null
                ||!level.hasChunk(cp.x,cp.z))return;
        PersistentChunkRefreshIndex.markChunkRefreshQueued(cp.x, cp.z);
        if (USE_REFRESH_ENGINE) {
            activeInstance.getRefreshEngine().submit(cp,
                net.buildtheearth.terraplusplus.generator
                    .historical.RefreshEngine.Reason.CHUNK_LOAD);
            return;
        }
        qv(cp);
        if(activeInstance.getWorldConfig()
                .hasAnyStructures())
            qs(cp);
    }

    public static void scheduleVegRefreshOnly(
            net.minecraft.server.level.ServerLevel level,
            ChunkPos cp){
        if(activeInstance==null||level==null)return;
        var chunk=level.getChunkSource()
            .getChunk(cp.x,cp.z,false);
        if(chunk==null)return;
        PersistentChunkRefreshIndex.markChunkRefreshQueued(cp.x, cp.z);
        qv(cp);}

    public static void scheduleLoadedChunkRefresh(
            net.minecraft.server.level.ServerLevel level){
        if(activeInstance==null||level==null)return;
        int va=0,sa=0,deferred=0;
        List<ChunkPos> refreshOrder = PersistentChunkRefreshIndex
                .getLoadedChunkSnapshot(level);
        // A ConcurrentHashMap snapshot has arbitrary order. Refresh the
        // nearest player area first so a time jump becomes visibly coherent
        // without making distant loaded chunks compete for source work.
        refreshOrder.sort(java.util.Comparator.comparingLong(cp -> {
            long best = Long.MAX_VALUE;
            for (ServerPlayer player : level.players()) {
                long dx = (long) cp.x - player.chunkPosition().x;
                long dz = (long) cp.z - player.chunkPosition().z;
                best = Math.min(best, dx * dx + dz * dz);
            }
            return best;
        }));
        for(ChunkPos cp: refreshOrder){
            if(!level.hasChunk(cp.x,cp.z))continue;
            long nearest = Long.MAX_VALUE;
            for (ServerPlayer player : level.players()) {
                long dx = (long) cp.x - player.chunkPosition().x;
                long dz = (long) cp.z - player.chunkPosition().z;
                nearest = Math.min(nearest, dx * dx + dz * dz);
            }
            if (!level.players().isEmpty()
                    && nearest > (long) TIMEJUMP_IMMEDIATE_RADIUS * TIMEJUMP_IMMEDIATE_RADIUS) {
                PersistentChunkRefreshIndex.markChunkDeferred(cp.x, cp.z);
                deferred++;
                continue;
            }
            if (va >= TIMEJUMP_IMMEDIATE_MAX
                    || (activeInstance.getWorldConfig().hasAnyStructures()
                    && sa >= TIMEJUMP_IMMEDIATE_MAX)) {
                PersistentChunkRefreshIndex.markChunkDeferred(cp.x, cp.z);
                deferred++;
                continue;
            }
            PersistentChunkRefreshIndex.markChunkRefreshQueued(cp.x, cp.z);
            if (USE_REFRESH_ENGINE) {
                if (activeInstance.getRefreshEngine().submit(cp,
                        net.buildtheearth.terraplusplus.generator
                            .historical.RefreshEngine.Reason.TIME_JUMP))
                    va++;
                continue;
            }
            if(qv(cp))va++;
            if(activeInstance.getWorldConfig()
                    .hasAnyStructures()
                    &&qs(cp))sa++;
        }
        // TIMEJUMP DRAIN FIX: a jump just queued a burst of
        // chunks the player is actively waiting on — let the
        // engine drain at burst budget for the next minute
        // instead of 8ms/tick politeness.
        if (USE_REFRESH_ENGINE && va > 0)
            activeInstance.getRefreshEngine().burst();
        LOGGER.info(
            "Scheduled refresh: +{}veg +{}struct deferred={}"
            +" loadedNow={} queued={}",
            va,sa,deferred,
            PersistentChunkRefreshIndex
                .getLoadedChunkCount(),
            getRefreshQueueSize());}

    public static boolean hasRefreshPending(){
        if (USE_REFRESH_ENGINE && activeInstance != null
                && activeInstance.refreshEngine != null
                && !activeInstance.refreshEngine.isIdle())
            return true;
        return !refreshQueue.isEmpty()
            ||!structureRefreshQueue.isEmpty();}

    public static int getRefreshQueueSize(){
        int engine = (USE_REFRESH_ENGINE
            && activeInstance != null
            && activeInstance.refreshEngine != null)
            ? activeInstance.refreshEngine.depth() : 0;
        return engine + refreshQueue.size()
            +structureRefreshQueue.size();}

    // ── TREE/VEGETATION CHECK ─────────────────────────────────
    private static boolean isTreeOrVegetation(
            BlockState s){
        if(s==null||s.isAir())return false;
        return isTreeBlock(s)
            ||s.is(Blocks.GRASS)
            ||s.is(Blocks.TALL_GRASS)
            ||s.is(Blocks.FERN)
            ||s.is(Blocks.LARGE_FERN)
            ||s.is(Blocks.DEAD_BUSH)
            ||s.is(Blocks.DANDELION)
            ||s.is(Blocks.POPPY)
            ||s.is(Blocks.CORNFLOWER)
            ||s.is(Blocks.AZURE_BLUET)
            ||s.is(Blocks.MOSS_CARPET)
            ||s.is(Blocks.SUGAR_CANE)
            ||s.is(Blocks.WHEAT)
            ||s.is(Blocks.FARMLAND)
            ||s.is(Blocks.BAMBOO)
            ||s.is(Blocks.VINE);}

    // ── STRUCTURE FOOTPRINT CHECK ─────────────────────────────
    private static boolean isInsideAnyStructure(
            double lat,double lon,
            List<OsmStructureIndex.OsmStructure> structs){
        if(structs==null||structs.isEmpty())
            return false;
        for(var s:structs){
            if(s.nodes==null||s.nodes.length<2)
                continue;
            if(lat<s.minLat-0.0002
                    ||lat>s.maxLat+0.0002
                    ||lon<s.minLon-0.0002
                    ||lon>s.maxLon+0.0002)
                continue;
            if(s.isArea&&s.nodes.length>=3){
                if(s.containsPoint(lat,lon))
                    return true;
            }else{
                if(isNearLine(lat,lon,s.nodes,
                        getLineMargin(s.type)))
                    return true;
            }
        }
        return false;}

    private static double getLineMargin(
            OsmStructureIndex.StructureType type){
        return switch(type){
            case ROAD->0.00006;
            case FOOTWAY,CYCLEWAY->0.00003;
            case RAILWAY->0.00004;
            default->0.00005;
        };}

    private static boolean isNearLine(
            double lat,double lon,
            OsmStructureIndex.OsmNode[] nodes,
            double margin){
        for(int i=0;i<nodes.length-1;i++){
            double d=OsmStructureIndex.OsmStructure
                .ptSegDist(lat,lon,
                    nodes[i].lat,nodes[i].lon,
                    nodes[i+1].lat,nodes[i+1].lon);
            if(d<margin)return true;
        }
        return false;}

    // ── ROAD TYPE CHECK ───────────────────────────────────────
    private static boolean isRoadType(
            OsmStructureIndex.StructureType type){
        return type==OsmStructureIndex
                .StructureType.ROAD
            ||type==OsmStructureIndex
                .StructureType.FOOTWAY
            ||type==OsmStructureIndex
                .StructureType.CYCLEWAY
            ||type==OsmStructureIndex
                .StructureType.RAILWAY;}

    // ── CLEAR TREES IN STRUCTURE FOOTPRINT ────────────────────
    private static void clearTreesInFootprint(
            net.minecraft.server.level.ServerLevel level,
            int bx,int groundY,int bz){
        BlockPos.MutableBlockPos pos=
            new BlockPos.MutableBlockPos();
        for(int y=groundY+1;
                y<=Math.min(groundY+60,XY-1);y++){
            pos.set(bx,y,bz);
            BlockState s=level.getBlockState(pos);
            if(s.isAir())continue;
            if(isTreeOrVegetation(s))
                setHistoricalBlock(level, pos,AIR,3);
            else break;
        }
        pos.set(bx,groundY,bz);
        BlockState surf=level.getBlockState(pos);
        if(surf.is(Blocks.DIRT)){
            pos.set(bx,groundY+1,bz);
            if(level.getBlockState(pos).isAir()){
                pos.set(bx,groundY,bz);
                setHistoricalBlock(level, pos,GRASS,3);
            }
        }
    }

    // ── CLEAR CHUNK BOUNDARY ORPHANS ──────────────────────────
    private static void clearBoundaryOrphans(net.minecraft.server.level.ServerLevel level,int chunkX,int chunkZ) {
        // Fixed Layer-2.1 ring is captured once at time jump; no recursive discovery.
    }

    // ── SEND CHUNK TO CLIENT ──────────────────────────────────
    // Called ONCE after all structures are fully placed.
    // Walks entire chunk surface and sends block updates
    // for ALL non-air blocks in the structure height range.
    // This is NOT per-block during placement — it is one
    // batch after everything is done, so no visual chaos.
    private static void sendChunkToClients(
            net.minecraft.server.level.ServerLevel level,
            LevelChunk chunk){
        try{
            chunk.setUnsaved(true);

            // NOTE: the 16x16 WORLD_SURFACE scan that used to compute a
            // globalMinY/globalMaxY window is gone with the per-block loop.
            // The chunk packet carries every section, so there is nothing to
            // bound.

            // ONE packet for the whole chunk.
            //
            // This used to loop 16 x 16 x (span) and call
            // level.sendBlockUpdated() for every non-air block. With the
            // span of sy-2..sy+60 that is 15,872 block reads and up to
            // ~15,000 individual packets PER CHUNK -- and at 2 chunks/tick,
            // ~31,700 per tick, each also triggering lighting and neighbour
            // shape updates on the server thread. It was one of the two main
            // causes of "Can't keep up!".
            //
            // ClientboundLevelChunkWithLightPacket resends the entire chunk,
            // including block entities and light, in a single packet. The
            // file header claimed this was already being done; it was not.
            var pkt=new net.minecraft.network.protocol.game
                .ClientboundLevelChunkWithLightPacket(
                    chunk,level.getLightEngine(),null,null);
            int cx=chunk.getPos().x, cz=chunk.getPos().z;
            for(ServerPlayer p:level.players()){
                // Only players tracking this chunk. View distance is in
                // chunks, so compare in chunk space.
                int vd=level.getServer().getPlayerList()
                    .getViewDistance()+1;
                if(Math.abs((p.blockPosition().getX()>>4)-cx)<=vd
                        &&Math.abs((p.blockPosition().getZ()>>4)-cz)<=vd)
                    p.connection.send(pkt);
            }
        }catch(Exception e){
            LOGGER.debug(
                "sendChunkToClients [{},{}]: {}",
                chunk.getPos().x,chunk.getPos().z,
                e.getMessage());
        }
    }

    // ── FETCH STRUCTURES SHARED HELPER ────────────────────────
    /**
     * NON-BLOCKING structure lookup for the server tick thread.
     *
     * ROOT CAUSE OF "Can't keep up!":
     * drainRefreshQueue() runs inside ServerTickEvent, i.e. on the server main
     * thread, and called fetchStructuresForChunk(..., 8000) which ends in
     * CompletableFuture.get(8000 ms). A single cache miss therefore froze the
     * whole server for up to 8 s = 160 ticks of debt. With the post-jump burst
     * of 64 chunks/tick the theoretical worst case is 64 x 8 s = 512 s in ONE
     * tick, and the log shows exactly this shape: a time jump queues 1,216
     * chunks at 00:02:35 and by 00:02:56 the server is 2,933 ticks behind --
     * 106 s of new debt in 21 s of wall clock.
     *
     * This variant never waits. If the data is already in memory it is
     * returned; otherwise the tile is handed to AsyncStructureManager (which
     * already exists and already works -- the log shows "OSM retry success"
     * 2.3 s after a queued retry) and null is returned so the caller re-queues
     * the chunk. The chunk fills in a second or two later instead of stalling
     * the world.
     *
     * @return structures if cached, or null when not ready yet
     */
    private List<OsmStructureIndex.OsmStructure>
            fetchStructuresCachedOnly(
            net.minecraft.server.level.ServerLevel level,
            LevelChunk ch){
        try{
            var d=getDf();
            if(d==null||!d.isProjectionLoaded())return null;
            double[] gc=d.toGeo(
                ch.getPos().getMinBlockX()+8,
                ch.getPos().getMinBlockZ()+8);
            if(gc==null||Double.isNaN(gc[0])
                    ||Double.isNaN(gc[1]))return List.of();

            double mnLa=gc[1],mxLa=gc[1];
            double mnLo=gc[0],mxLo=gc[0];
            for(double[] g:new double[][]{
                d.toGeo(ch.getPos().getMinBlockX(),
                    ch.getPos().getMinBlockZ()),
                d.toGeo(ch.getPos().getMinBlockX()+15,
                    ch.getPos().getMinBlockZ()),
                d.toGeo(ch.getPos().getMinBlockX(),
                    ch.getPos().getMinBlockZ()+15),
                d.toGeo(ch.getPos().getMinBlockX()+15,
                    ch.getPos().getMinBlockZ()+15)})
                if(g!=null&&!Double.isNaN(g[0])
                        &&!Double.isNaN(g[1])){
                    mnLa=Math.min(mnLa,g[1]);
                    mxLa=Math.max(mxLa,g[1]);
                    mnLo=Math.min(mnLo,g[0]);
                    mxLo=Math.max(mxLo,g[0]);}

            // Historical refresh must use the same OHM → OSM → VIDA →
            // Microsoft accepted result as async placement, never raw OSM.
            if (worldConfig.timeEnabled) {
                AsyncStructureManager async = getAsyncManager();
                List<OsmStructureIndex.OsmStructure> accepted = async
                        .getCompletedStructures((mnLa + mxLa) / 2,
                                (mnLo + mxLo) / 2,
                                mnLa, mxLa, mnLo, mxLo);
                if (accepted != null) return accepted;
                async.queueRetry((mnLa + mxLa) / 2,
                        (mnLo + mxLo) / 2, ch.getPos().x, ch.getPos().z);
                // ── APPETIZER (live path — RUN-12 FIX) ──────────
                // The original appetizer branch lived in
                // fetchStructuresForChunk, which lost its last
                // caller in the RefreshEngine migration: runs
                // 10-12 had tiles resident (26 cached) yet zero
                // APPETIZER lines, because THIS method is what
                // EngineWorker.peekData actually calls. Server
                // thread, so budget=0: plate only what prefetch
                // already made resident, never wait. Year filter
                // mirrors the cached path below — at 3000 BCE an
                // appetizer of modern buildings would be wrong.
                // RUN-13 FIX (churn): only serve when the OSM
                // tile is genuinely NOT resident. Run 13 proved
                // the serve->swap cycle works (117/117) but all
                // serves happened AFTER OSM arrived (async merge
                // still combining) — each was instantly swapped
                // by presenceCheck = pointless double placement.
                // areTilesLoaded is a pure map lookup.
                // RUN-16 FIX (deleted roads/buildings): the
                // appetizer must NEVER be served as REFRESH
                // data for a chunk that already has content.
                // The refresh pipeline wipes first and
                // re-places from whatever this method returns —
                // during the post-jump burst the merge was
                // often still combining, so decorated chunks
                // got wiped and re-placed with a handful of
                // Overture silhouettes (and the corrective
                // swap deferred). Appetizer is for EMPTY
                // plates only: presence < DECORATED. A
                // decorated chunk parks in NEEDS_DATA until
                // the complete merge exists — no data, no wipe.
                if (APPETIZER_SERVING
                        && getChunkPresence(ch) < PRESENCE_DECORATED
                        && !getStructureIndex().areTilesLoaded(
                            mnLa, mxLa, mnLo, mxLo)) {
                    var ovOsm = net.buildtheearth.terraplusplus
                        .generator.osm.OvertureOsmIndex
                        .getInstance().getStructuresForArea(
                            mnLa, mxLa, mnLo, mxLo, 0);
                    if (ovOsm != null && !ovOsm.isEmpty()) {
                        int year = TerraTimeManager.getCurrentYear();
                        List<OsmStructureIndex.OsmStructure> vis =
                            new ArrayList<>(ovOsm.size());
                        for (var s : ovOsm)
                            if (HistoricalDateParser.isVisibleAt(
                                    s.startYear, s.endYear,
                                    year, true))
                                vis.add(s);
                        if (!vis.isEmpty()) {
                            net.buildtheearth.terraplusplus
                                .generator.historical
                                .ProvisionalServing.markServed(
                                    ch.getPos().x, ch.getPos().z,
                                    (mnLa + mxLa) / 2,
                                    (mnLo + mxLo) / 2, vis.size());
                            return vis;
                        }
                    }
                }
                return null;
            }

            // Are the OSM tiles already resident? areTilesLoaded() is a pure
            // map lookup and never touches the network.
            if(!getStructureIndex().areTilesLoaded(
                    mnLa,mxLa,mnLo,mxLo)){
                getAsyncManager().queueRetry(
                    (mnLa+mxLa)/2,(mnLo+mxLo)/2,
                    ch.getPos().x,ch.getPos().z);
                // APPETIZER (live path, non-time worlds): same
                // 0ms resident-only serve as the timeEnabled
                // branch above. RUN-12 FIX.
                if (APPETIZER_SERVING) {
                    var ovOsm = net.buildtheearth.terraplusplus
                        .generator.osm.OvertureOsmIndex
                        .getInstance().getStructuresForArea(
                            mnLa, mxLa, mnLo, mxLo, 0);
                    if (ovOsm != null && !ovOsm.isEmpty()) {
                        net.buildtheearth.terraplusplus
                            .generator.historical
                            .ProvisionalServing.markServed(
                                ch.getPos().x, ch.getPos().z,
                                (mnLa + mxLa) / 2,
                                (mnLo + mxLo) / 2, ovOsm.size());
                        return new ArrayList<>(ovOsm);
                    }
                }
                return null;
            }

            // Cache-resident path: getStructuresForChunk (no "Blocking")
            // reads memory only.
            List<OsmStructureIndex.OsmStructure> all=
                new ArrayList<>();
            var os=getStructureIndex()
                .getStructuresForChunk(
                    mnLa,mxLa,mnLo,mxLo,worldConfig);
            if(os!=null&&!os.isEmpty())all.addAll(os);

            // ── YEAR FILTER ──────────────────────────────────
            // MUST mirror fetchStructuresForChunk, which routes through
            // HistoricalStructureManager and therefore applies
            // HistoricalDateParser.isVisibleAt() to every structure.
            //
            // This method reads the RAW OSM index, which is year-agnostic.
            // Without the filter below every building rendered regardless of
            // the current year, so teleporting at 3000 BCE produced a modern
            // city in the surrounding chunks while the rest of the world was
            // correctly empty. Regression introduced when this non-blocking
            // path was added; the blocking path it replaced always had the
            // timeEnabled branch.
            if(worldConfig.timeEnabled&&!all.isEmpty()){
                int year=TerraTimeManager.getCurrentYear();
                List<OsmStructureIndex.OsmStructure> visible=
                    new ArrayList<>(all.size());
                for(var s:all)
                    if(HistoricalDateParser.isVisibleAt(
                            s.startYear,s.endYear,year,true))
                        visible.add(s);
                return visible;
            }
            return all;
        }catch(Exception e){
            return null;
        }
    }

    private List<OsmStructureIndex.OsmStructure>
            fetchStructuresForChunk(
            net.minecraft.server.level.ServerLevel level,
            LevelChunk ch,long timeoutMs){
        try{
            var d=getDf();
            if(d==null||!d.isProjectionLoaded())
                return null;
            double[] gc=d.toGeo(
                ch.getPos().getMinBlockX()+8,
                ch.getPos().getMinBlockZ()+8);
            if(gc==null||Double.isNaN(gc[0])
                    ||Double.isNaN(gc[1]))
                return List.of();
            double mnLa=gc[1],mxLa=gc[1];
            double mnLo=gc[0],mxLo=gc[0];
            for(double[] g:new double[][]{
                d.toGeo(ch.getPos().getMinBlockX(),
                    ch.getPos().getMinBlockZ()),
                d.toGeo(ch.getPos().getMinBlockX()+15,
                    ch.getPos().getMinBlockZ()),
                d.toGeo(ch.getPos().getMinBlockX(),
                    ch.getPos().getMinBlockZ()+15),
                d.toGeo(ch.getPos().getMinBlockX()+15,
                    ch.getPos().getMinBlockZ()+15)})
                if(g!=null&&!Double.isNaN(g[0])
                        &&!Double.isNaN(g[1])){
                    mnLa=Math.min(mnLa,g[1]);
                    mxLa=Math.max(mxLa,g[1]);
                    mnLo=Math.min(mnLo,g[0]);
                    mxLo=Math.max(mxLo,g[0]);}
            List<OsmStructureIndex.OsmStructure> all=
                new ArrayList<>();
            boolean osmPending=false;
            if(worldConfig.timeEnabled){
                var merged=getHistoricalManager()
                    .getStructuresForChunk(
                        mnLa,mxLa,mnLo,mxLo,
                        timeoutMs);
                if(merged==null){
                    // APPETIZER PROTOCOL: OSM/OHM still cooking.
                    // Old behaviour: return null -> bare chunk
                    // until async retry. New: serve the fast
                    // sources NOW, mark provisional; the OSM
                    // arrival hook swaps in the main course.
                    osmPending=true;
                }else if(!merged.visible.isEmpty())
                    all.addAll(merged.visible);
            }else{
                String src=activeSource;
                if("osm".equals(src)
                        ||"both".equals(src)){
                    var os=getStructureIndex()
                        .getStructuresForChunkBlocking(
                            mnLa,mxLa,mnLo,mxLo,
                            worldConfig,timeoutMs);
                    if(os==null){
                        osmPending=true; // appetizer path below
                    }else if(!os.isEmpty())all.addAll(os);
                }
                // ── TIER 3: VIDA (after OSM, before Microsoft) ──
                List<SourceCooperativeIndex.VidaBuilding>
                    vidaAccepted=new ArrayList<>();
                if(("vida".equals(src)
                        ||"both".equals(src))
                        &&worldConfig.vidaBuildings)
                    try{
                        var vb=getVidaIndex()
                            .getBuildingsForChunkBlocking(
                                mnLa,mxLa,mnLo,mxLo,
                                timeoutMs,null);
                        if(vb!=null){
                            // Remove this dataset's positional bias against
                            // OSM before any comparison. Measured per region
                            // at runtime; see SourceAlignment.
                            var vPrints=new ArrayList<
                                FootprintAlignment.Print>(vb.size());
                            for(var b:vb)
                                vPrints.add(FootprintAlignment.Print.of(b));
                            var vShift=SourceAlignment.shiftFor(
                                "vida",(mnLa+mxLa)/2,(mnLo+mxLo)/2,
                                vPrints,all);
                            for(var raw:vb){
                                var b=raw.shifted(vShift.dLat,vShift.dLon);
                                if(SourceCooperativeIndex
                                        .overlapsExisting(b,all))
                                    continue;
                                // ML footprints sometimes land on the
                                // carriageway; OSM has no building there to
                                // compare against, so reject by road geometry.
                                if(FootprintAlignment.sitsOnRoad(
                                        FootprintAlignment.Print.of(b),all))
                                    continue;
                                vidaAccepted.add(b);
                                all.add(b.toOsmStructure());}}
                    }catch(Exception e){
                        LOGGER.debug("VIDA fetch [{},{}]: {}",
                            ch.getPos().x,ch.getPos().z,
                            e.getMessage());}
                if(("overture".equals(src)
                        ||"both".equals(src))
                        &&worldConfig.overtureBuildings)
                    try{
                        var ov=getMicrosoftIndex();
                        if(ov.isEnabled()){
                            var ob=ov
                                .getBuildingsForChunkBlocking(
                                    mnLa,mxLa,mnLo,mxLo,
                                    timeoutMs);
                            if(ob!=null&&!ob.isEmpty()){
                                // Microsoft has its own, different bias.
                                var mPrints=new ArrayList<
                                    FootprintAlignment.Print>(ob.size());
                                for(var b:ob)
                                    mPrints.add(
                                        FootprintAlignment.Print.of(b));
                                var mShift=SourceAlignment.shiftFor(
                                    "overture",(mnLa+mxLa)/2,(mnLo+mxLo)/2,
                                    mPrints,all);
                                for(var raw:ob){
                                    var b=raw.shifted(
                                        mShift.dLat,mShift.dLon);
                                    // Lowest tier: yield to
                                    // OSM and to VIDA.
                                    if(overlaps(b,all))
                                        continue;
                                    if(SourceCooperativeIndex
                                            .overlapsVida(
                                                b,vidaAccepted))
                                        continue;
                                    if(FootprintAlignment.sitsOnRoad(
                                            FootprintAlignment.Print.of(b),
                                            all))
                                        continue;
                                    all.add(
                                        b.toOsmStructure());}}
                        }
                    }catch(Exception e){
                        LOGGER.debug(
                            "FetchStruct [{},{}]: {}",
                            ch.getPos().x,
                            ch.getPos().z,
                            e.getMessage());
                    }
            }

            // ── APPETIZER (cold-start progressive serve) ──────
            // OSM tile still cooking: serve the fast sources NOW
            // (appetizer hierarchy: VIDA/Google > Microsoft; the
            // Overture(OSM) GeoParquet tier upgrades this later)
            // and record the chunk as provisional. When the OSM
            // tile lands, AsyncStructureManager fires the swap.
            // Warm areas never reach here (OSM answers from disk
            // instantly), so this is strictly a cold-start path.
            if(osmPending){
                if(!APPETIZER_SERVING)
                    return null;   // feature off: old behaviour
                int before=all.size();
                try{
                    // OVERTURE(OSM) — appetizer tier 1. The
                    // real Overture: OSM-derived planet tiles,
                    // fast even on a truly cold first visit
                    // (run 9 proved MSFT/VIDA are NOT: 40s/143s
                    // cold, so OSM won the race and the
                    // appetizer starved). Buildings AND roads.
                    // RUN-10 FIX: 1500ms starved from India —
                    // 2-3 range reads x ~400ms RTT x 3 themes.
                    // 6s bounded on a worker thread is legal
                    // (tier-2 rules) and OSM itself takes 15s+.
                    var ovOsm=net.buildtheearth.terraplusplus
                        .generator.osm.OvertureOsmIndex
                        .getInstance()
                        .getStructuresForArea(
                            mnLa,mxLa,mnLo,mxLo,6000);
                    if(ovOsm!=null&&!ovOsm.isEmpty())
                        all.addAll(ovOsm);
                    // VIDA (Google) — appetizer tier 2
                    if(worldConfig.vidaBuildings){
                        var vb=getVidaIndex()
                            .getBuildingsForChunkBlocking(
                                mnLa,mxLa,mnLo,mxLo,
                                1500,null);
                        if(vb!=null)
                            for(var b:vb){
                                if(SourceCooperativeIndex
                                        .overlapsExisting(b,all))
                                    continue;
                                all.add(b.toOsmStructure());}
                    }
                    // Microsoft — appetizer tier 3
                    if(worldConfig.overtureBuildings){
                        var ov=getMicrosoftIndex();
                        if(ov.isEnabled()){
                            var ob=ov
                                .getBuildingsForChunkBlocking(
                                    mnLa,mxLa,mnLo,mxLo,1500);
                            if(ob!=null)
                                for(var b:ob){
                                    if(overlaps(b,all))continue;
                                    all.add(b.toOsmStructure());}
                        }
                    }
                }catch(Exception e){
                    LOGGER.debug("appetizer [{},{}]: {}",
                        ch.getPos().x,ch.getPos().z,
                        e.getMessage());
                }
                if(all.size()>before){
                    // Something to serve: track for the swap.
                    net.buildtheearth.terraplusplus.generator
                        .historical.ProvisionalServing
                        .markServed(ch.getPos().x,
                            ch.getPos().z,
                            (mnLa+mxLa)/2,(mnLo+mxLo)/2,
                            all.size()-before);
                    return all;
                }
                // Appetizer kitchen also empty/cold: behave
                // exactly as before (null = pending, async
                // retry owns it).
                return null;
            }
            return all;
        }catch(Exception e){
            LOGGER.debug("fetchStructs [{},{}]: {}",
                ch.getPos().x,ch.getPos().z,
                e.getMessage());
            return null;
        }
    }

    // ── MARK CHUNK WITH CURRENT YEAR ──────────────────────────
    private void markChunkCurrentYear(ChunkAccess ch){
        try{
            var server=ServerLifecycleHooks
                .getCurrentServer();
            if(server==null||server.overworld()==null
                    ||ch==null)return;
            PersistentChunkRefreshIndex
                .markChunkRefreshed(
                    server.overworld(),
                    ch.getPos().x,
                    ch.getPos().z);
        }catch(Exception e){
            LOGGER.debug(
                "markChunkCurrentYear [{},{}]: {}",
                ch!=null?ch.getPos().x:0,
                ch!=null?ch.getPos().z:0,
                e.getMessage());
        }
    }

    // ── DRAIN REFRESH QUEUE ───────────────────────────────────
    // Pass 1: vegetation only (10/tick)
    //         No road placement — roads handled in pass 2
    // Pass 2: ALL structures — roads + buildings together
    //         via OsmStructureGenerator (2/tick)
    //         Client chunk resync after placement
    public void drainRefreshQueue(
            net.minecraft.server.level.ServerLevel level){

        // Water source downloads complete on AsyncWaterManager workers. Only
        // this server-thread drain mutates blocks in the now-ready chunks.
        if (asyncWaterManager != null) {
            for (int i = 0; i < 2; i++) {
                ChunkPos waterPos = asyncWaterManager.pollReady();
                if (waterPos == null) break;
                try {
                    if (level.hasChunk(waterPos.x, waterPos.z))
                        scheduleSingleChunkRefresh(level, waterPos);
                } catch (Exception e) {
                    LOGGER.debug("Async water placement [{},{}]: {}",
                            waterPos.x, waterPos.z, e.getMessage());
                }
            }
        }

        // ── UNIFIED ENGINE PATH ───────────────────────────────
        // One owner, time-budgeted, push-based data readiness.
        // The legacy dual-queue path below remains for A/B
        // comparison (USE_REFRESH_ENGINE=false).
        if (USE_REFRESH_ENGINE) {
            getRefreshEngine().tick(level);
            return;
        }

        // ── PASS 1: VEGETATION ONLY ──────────────────────────
    int refreshLimit =
        TerraChunkGenerator
            .postJumpRescanCountdown > 0
        ? REFRESH_PER_TICK_BURST
        : REFRESH_PER_TICK;
    int vp = 0;
    while (vp < refreshLimit
            && !refreshQueue.isEmpty()) {
            ChunkPos pos=refreshQueue.poll();
            if(pos==null)break;
            queuedVegRefresh.remove(rk(pos.x,pos.z));
            try{
                if(!level.hasChunk(pos.x,pos.z)){
                    vp++;continue;}
                if(!PersistentChunkRefreshIndex
                        .needsRefresh(
                            level,pos.x,pos.z)){
                    vp++;continue;}
                LevelChunk ch=
                    level.getChunk(pos.x,pos.z);
                long k=ck(pos.x,pos.z);
                // Fetch structures for tree clearing in footprints only.
                //
                // NON-BLOCKING. This runs on the server tick thread, so it
                // must never wait on the network. On a cache miss the tile is
                // queued for async fetch and the chunk is re-queued, which is
                // what the two-pass refresh was designed for.
                List<OsmStructureIndex.OsmStructure>
                    structs=null;
                if(worldConfig.hasAnyStructures()){
                    structs=fetchStructuresCachedOnly(
                        level,ch);
                    if(structs==null){
                        // Not resident yet. Put the chunk back and move on;
                        // do NOT stall the tick waiting for it.
                        if(queuedVegRefresh.add(
                                rk(pos.x,pos.z)))
                            refreshQueue.offer(pos);
                        vp++;continue;
                    }
                    if(!structs.isEmpty())
                        refreshStructureCache.put(
                            k,structs);
                }

                // Vegetation pass — clears trees at
                // structure footprints, no road placement. Target source data
                // is resident now, so it is safe to remove old tracked blocks.
                if (!GeneratedBlockOwnershipIndex.clearOwnedBlocks(level, pos)) {
                    if (queuedVegRefresh.add(rk(pos.x,pos.z)))
                        refreshQueue.offer(pos);
                    PersistentChunkRefreshIndex.markChunkRefreshFailed(pos.x, pos.z);
                    vp++; continue;
                }
                PersistentChunkRefreshIndex.markChunkRefreshing(pos.x, pos.z);
                beginHistoricalWriteCapture();
                if (!refreshChunkForYear(level,ch,structs)) {
                    // Projection/data readiness failed. Keep the durable year
                    // stale and retry rather than losing this refresh entry.
                    if (queuedVegRefresh.add(rk(pos.x,pos.z)))
                        refreshQueue.offer(pos);
                    endHistoricalWriteCapture();
                    PersistentChunkRefreshIndex.markChunkRefreshFailed(pos.x, pos.z);
                    vp++; continue;
                }

                refreshOwnershipCache.put(k, endHistoricalWriteCapture());
                vegetationPhaseComplete.add(rk(pos.x,pos.z));
                if(!worldConfig.hasAnyStructures()) {
                    vegetationPhaseComplete.remove(rk(pos.x,pos.z));
                    if (GeneratedBlockOwnershipIndex.commit(level, pos,
                            refreshOwnershipCache.remove(k))) {
                        PersistentChunkRefreshIndex
                            .markChunkRefreshed(level,pos.x,pos.z);
                    } else {
                        if (queuedVegRefresh.add(rk(pos.x,pos.z)))
                            refreshQueue.offer(pos);
                        PersistentChunkRefreshIndex.markChunkRefreshFailed(pos.x, pos.z);
                    }
                }

                clearBoundaryOrphans(level,
                    pos.x,pos.z);

            }catch(Exception e){
                LOGGER.debug("VegRefresh [{},{}]: {}",
                    pos.x,pos.z,e.getMessage());
                endHistoricalWriteCapture();
                if (queuedVegRefresh.add(rk(pos.x,pos.z)))
                    refreshQueue.offer(pos);
                PersistentChunkRefreshIndex.markChunkRefreshFailed(pos.x, pos.z);
            }
            vp++;
        }

        // ── PASS 2: ALL STRUCTURES (ROADS + BUILDINGS) ───────
        {
            int sp=0;
            while(sp<STRUCT_REFRESH_PER_TICK
                    &&!structureRefreshQueue.isEmpty()){
                ChunkPos pos=
                    structureRefreshQueue.poll();
                if(pos==null)break;
                queuedStructRefresh
                    .remove(rk(pos.x,pos.z));
                try{
                    if(!level.hasChunk(pos.x,pos.z)){
                        sp++;continue;}
                    if(!PersistentChunkRefreshIndex
                            .needsRefresh(
                                level,pos.x,pos.z)){
                        sp++;continue;}
                    LevelChunk ch=
                        level.getChunk(pos.x,pos.z);
                    long refreshKey = rk(pos.x,pos.z);
                    // Pass 2 is not allowed to commit the durable year before
                    // pass 1 completed. This prevents a fast structure cache
                    // hit from skipping vegetation/tree cleanup.
                    if (!vegetationPhaseComplete.contains(refreshKey)) {
                        if (queuedStructRefresh.add(refreshKey))
                            structureRefreshQueue.offer(pos);
                        sp++; continue;
                    }
                    PersistentChunkRefreshIndex.markChunkRefreshing(pos.x, pos.z);
                    boolean ok=
                        refreshStructuresForChunk(
                            level,ch);
                    if(ok) {
                        vegetationPhaseComplete.remove(refreshKey);
                        PersistentChunkRefreshIndex
                            .markChunkRefreshed(
                                level,pos.x,pos.z);
                    } else {
                        if (queuedStructRefresh.add(refreshKey))
                            structureRefreshQueue.offer(pos);
                        PersistentChunkRefreshIndex.markChunkRefreshFailed(pos.x, pos.z);
                    }
                }catch(Exception e){
                    LOGGER.debug(
                        "StructRefresh [{},{}]: {}",
                        pos.x,pos.z,e.getMessage());
                    if (queuedStructRefresh.add(rk(pos.x,pos.z)))
                        structureRefreshQueue.offer(pos);
                    PersistentChunkRefreshIndex.markChunkRefreshFailed(pos.x, pos.z);
                }
                sp++;
            }
        }

        // Evict stale cache entries
        if(refreshStructureCache.size()>2048)
            refreshStructureCache.clear();
    }

    // ── HISTORICAL WATER SYNC FOR LOADED CHUNKS ───────────────
    // Converts stale water blocks to the correct surface block
    // for the current year. For built-up areas at modern years,
    // places TUFF directly. For natural areas, places GRASS.
    // Called at the top of refreshChunkForYear() before
    // vegetation or structure placement.

        public boolean syncHistoricalWaterPublic(
            net.minecraft.server.level.ServerLevel level,
            net.minecraft.world.level.chunk.LevelChunk ch) {
        if (!worldConfig.waterBodies) return true;
        return syncHistoricalWater(level, ch);
    }

    /** @return true only when all water source decisions needed by this chunk
     * are resident; false keeps the refresh transaction stale for retry. */
    public boolean syncHistoricalWater(
            net.minecraft.server.level.ServerLevel level,
            LevelChunk ch) {
        try {
            var d = getDf();
            if (d == null || !d.isProjectionLoaded())
                return false;
            boolean pendingWater = false;

            var rle  = getRle();
            var esa  = getWorldCover();
            int cmx  = ch.getPos().getMinBlockX();
            int cmz  = ch.getPos().getMinBlockZ();
            int mb   = level.getMinBuildHeight();
            int currentYear =
                TerraTimeManager.getCurrentYear();
            BlockPos.MutableBlockPos pos =
                new BlockPos.MutableBlockPos();

            int placedColumns = 0;
            int drainedColumns = 0;

            for (int lx = 0; lx < 16; lx++) {
                for (int lz = 0; lz < 16; lz++) {
                    int bx = cmx + lx;
                    int bz = cmz + lz;

                    // ── DETECT WATER COLUMN ────────────
                    // Scan downward from the heightmap
                    // surface looking for water blocks
                    int sy;
                    try {
                        sy = ch.getHeight(
                            Heightmap.Types
                                .WORLD_SURFACE,
                            lx, lz);
                    } catch (Exception e) {
                        continue;
                    }

                    if (sy <= MY + 5
                            || sy >= XY - 5)
                        continue;

                    boolean hasWater   = false;
                    int waterTopY      = -1;
                    int waterBottomY   = -1;

                    for (int y = sy;
                            y >= Math.max(
                                mb, sy - 40);
                            y--) {
                        pos.set(bx, y, bz);
                        BlockState bs =
                            level.getBlockState(pos);
                        if (bs.is(Blocks.WATER)) {
                            hasWater = true;
                            if (waterTopY < 0)
                                waterTopY = y;
                            waterBottomY = y;
                        } else if (hasWater) {
                            // First solid block below
                            // water — this is the floor
                            break;
                        }
                    }

                    // Do not require existing water here. A time jump can make
                    // an OHM/OSM water feature appear on terrain that was land
                    // in the previous year.

                    // ── GEO LOOKUP ────────────────────
                    double[] geo = d.toGeo(bx, bz);
                    if (geo == null) continue;
                    double lon = geo[0], lat = geo[1];
                    if (Double.isNaN(lat)
                            || Double.isNaN(lon))
                        continue;

                    // ── SKIP PERMANENT OCEAN ──────────
                    // Never drain real ocean —
                    // only inland / historical water
                    double baseElev;
                    try {
                        baseElev =
                            d.getElevationFast(
                                lat, lon);
                    } catch (Exception e) {
                        continue;
                    }

                    if (baseElev <= WEM) continue;

                    try {
                        var col = d.classify(bx, bz);
                        if (col.type ==
                                TerraDensityFunctions
                                .TerrainType.OCEAN
                            || col.type ==
                                TerraDensityFunctions
                                .TerrainType
                                .SHALLOW_WATER)
                            continue;
                    } catch (Exception e) {
                        continue;
                    }

                    // ── CHECK CURRENT-YEAR WATER ──────
                    // Never interpret an absent source tile as "no water".
                    // Trigger a non-blocking prefetch and leave this column
                    // unchanged until a later refresh can make a real decision.
                    boolean ohmCached = rle.getOhmWaterIndex().isTileCached(
                            rle.getOhmWaterIndex().getTileKeyPublic(lat, lon));
                    boolean osmCached = rle.getOsmWaterIndex().isTileCached(
                            rle.getOsmWaterIndex().getTileKeyPublic(lat, lon));
                    if (!ohmCached && !osmCached) {
                        // A missing source tile is PENDING, never proof of
                        // dry land. AsyncWaterManager retries it and requeues
                        // this chunk once a real OSM/OHM result is available.
                        getAsyncWaterManager().request(lat, lon, ch.getPos());
                        pendingWater = true;
                        continue;
                    }

                    WaterBodyInfo currentWater;
                    try {
                        currentWater = rle.analyze(bx, bz, lat, lon, baseElev);
                    } catch (Exception e) {
                        // Query failed — be cautious, do not alter terrain.
                        continue;
                    }

                    // Water exists in the target year: carve any previous
                    // elevation terrain and build its current mapped bed/water
                    // column. This also refreshes a body that already existed.
                    if (currentWater != null && currentWater.isWater) {
                        if (placeHistoricalMappedWater(level, ch, bx, bz, lx, lz,
                                currentWater, baseElev, mb)) placedColumns++;
                        continue;
                    }

                    // No current mapped water and no old water to remove.
                    if (!hasWater) continue;

                    // ── DECIDE REPLACEMENT BLOCK ──────
                    // For built-up areas at modern
                    // years → TUFF (road/urban surface)
                    // For everything else → GRASS
                    BlockState replacementTop;
                    BlockState replacementFill;

                    boolean isBuiltUp = false;
                    try {
                        var wc = esa.getClass(lat, lon);
                        if (wc == EsaWorldCoverIndex
                                .WorldCoverClass
                                .BUILT_UP) {
                            int h =
                                TerraDensityFunctions
                                .blockHash(bx, bz);
                            int devOffset =
                                (h % 401) - 200;
                            int effectiveYear =
                                currentYear - devOffset;
                            if (effectiveYear >=
                                    BUILTUP_TUFF_START) {
                                isBuiltUp = true;
                            }
                        }
                    } catch (Exception ignored) {}

                    if (isBuiltUp) {
                        // Modern built-up area —
                        // top surface is TUFF,
                        // fill below with STONE
                        replacementTop  = TUFF;
                        replacementFill = STONE;
                    } else {
                        // Natural area — top is GRASS,
                        // fill below with DIRT
                        replacementTop  = GRASS;
                        replacementFill = DIRT;
                    }

                    // ── CONVERT WATER COLUMN ──────────
                    // Bottom water block and everything
                    // below it that is aquatic veg →
                    // fill block.
                    // Top water block → top surface.
                    // Everything between → fill block.
                    // Any aquatic veg on the floor or
                    // just above → fill block.

                    for (int y = waterBottomY;
                            y <= waterTopY; y++) {
                        pos.set(bx, y, bz);
                        BlockState bs =
                            level.getBlockState(pos);

                        boolean isAquatic =
                            bs.is(Blocks.WATER)
                            || bs.is(Blocks.SEAGRASS)
                            || bs.is(
                                Blocks.TALL_SEAGRASS)
                            || bs.is(Blocks.KELP)
                            || bs.is(
                                Blocks.KELP_PLANT);

                        if (!isAquatic) continue;

                        if (y == waterTopY) {
                            // Top of the water column
                            // becomes the surface block
                            setHistoricalBlock(level,
                                pos,
                                replacementTop, 3);
                        } else {
                            // Everything below becomes
                            // fill material
                            setHistoricalBlock(level,
                                pos,
                                replacementFill, 3);
                        }
                    }

                    // Also clean one block above the
                    // top in case of kelp/seagrass
                    // that extended above waterTopY
                    for (int y = waterTopY + 1;
                            y <= waterTopY + 3; y++) {
                        pos.set(bx, y, bz);
                        BlockState bs =
                            level.getBlockState(pos);
                        if (bs.is(Blocks.WATER)
                                || bs.is(
                                    Blocks.SEAGRASS)
                                || bs.is(
                                    Blocks.TALL_SEAGRASS)
                                || bs.is(Blocks.KELP)
                                || bs.is(
                                    Blocks.KELP_PLANT))
                            setHistoricalBlock(level,
                                pos,
                                AIR, 3);
                        else break;
                    }

                    // Also clean one block below the
                    // bottom in case seagrass rooted
                    // into the floor block
                    if (waterBottomY - 1 >= mb) {
                        pos.set(bx,
                            waterBottomY - 1, bz);
                        BlockState bs =
                            level.getBlockState(pos);
                        if (bs.is(Blocks.SEAGRASS)
                                || bs.is(
                                    Blocks.TALL_SEAGRASS)
                                || bs.is(Blocks.KELP)
                                || bs.is(
                                    Blocks.KELP_PLANT))
                            setHistoricalBlock(level,
                                pos,
                                replacementFill, 3);
                    }

                    drainedColumns++;
                }
            }

            if (placedColumns + drainedColumns > 0) {
                // Force heightmap recalculation so
                // the vegetation pass sees the correct
                // surface Y after water conversion.
                // Without this, ch.getHeight() still
                // returns the old water surface Y and
                // placeBuiltUpProgression skips the
                // column entirely.
                try {
                    ch.setUnsaved(true);
                    // Rebuild heightmaps with a real top-down scan.
                    //
                    // The old code called heightmap.update(lx, 0, lz, STONE)
                    // per column. Vanilla Heightmap.update early-exits when
                    // y <= firstAvailable - 2, and terrain here sits hundreds
                    // of blocks above y=0, so every call returned false and
                    // the heightmap was NEVER rebuilt (audit FINDING 2).
                    // Consequence: after a time-jump water drain,
                    // ch.getHeight() kept returning the old water surface Y
                    // and placeBuiltUpProgression skipped the column.
                    //
                    // primeHeightmaps scans each column from the top section
                    // downward and sets the true first-opaque Y — exactly
                    // the rebuild the comment above always promised.
                    Heightmap.primeHeightmaps(ch,
                        java.util.EnumSet.of(
                            Heightmap.Types.WORLD_SURFACE,
                            Heightmap.Types.MOTION_BLOCKING,
                            Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                            Heightmap.Types.OCEAN_FLOOR));
                } catch (Exception e) {
                    LOGGER.debug(
                        "Heightmap rebuild [{},{}]: {}",
                        ch.getPos().x, ch.getPos().z,
                        e.getMessage());
                }

                LOGGER.debug(
                    "syncWater [{},{}]: placed={} drained={} "
                    + "(builtUp={}) year={}",
                    ch.getPos().x, ch.getPos().z,
                    placedColumns, drainedColumns,
                    isBuiltUpChunk(ch, esa, d),
                    currentYear);
            }
            return !pendingWater;

        } catch (Exception e) {
            LOGGER.debug(
                "syncHistoricalWater [{},{}]: {}",
                ch.getPos().x, ch.getPos().z,
                e.getMessage());
            return false;
        }
    }

    /**
     * Applies a source-confirmed OHM/OSM water body to an existing chunk after
     * a time jump. Terrain is removed only within the feature's water column;
     * recognised structure blocks are retained (for example bridge decks).
     */
    private boolean placeHistoricalMappedWater(
            net.minecraft.server.level.ServerLevel level, LevelChunk chunk,
            int blockX, int blockZ, int localX, int localZ,
            WaterBodyInfo water, double elevation, int minBuildY) {
        try {
            int surfaceY = water.waterSurfaceY > 0 ? water.waterSurfaceY
                    : TerraDensityFunctions.elevMetersToSurfaceY(elevation, true);
            surfaceY = Math.max(minBuildY + 2, Math.min(XY - 1, surfaceY));
            int floorY = Math.max(minBuildY + 1,
                    surfaceY - Math.max(2, water.profileDepthBlocks()));
            int topY = Math.min(XY, Math.max(surfaceY,
                    chunk.getHeight(Heightmap.Types.WORLD_SURFACE, localX, localZ)));
            BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

            // Remove the old elevation column, plants, and stale water above
            // the new floor. Structures are deliberately left in place.
            for (int y = floorY + 1; y <= topY; y++) {
                pos.set(blockX, y, blockZ);
                BlockState state = level.getBlockState(pos);
                if (!isStructBlock(state) && !state.isAir())
                    setHistoricalBlock(level, pos, AIR, 3);
            }

            int hash = TerraDensityFunctions.blockHash(blockX, blockZ);
            BlockState bed = water.isRiver()
                    ? (hash % 3 == 0 ? SAND : GRAVEL)
                    : (water.edgeFactor < 0.3 ? CLAY : GRAVEL);
            for (int y = Math.max(minBuildY + 1, floorY - 3); y <= floorY; y++) {
                pos.set(blockX, y, blockZ);
                if (!isStructBlock(level.getBlockState(pos)))
                    setHistoricalBlock(level, pos, bed, 3);
            }
            for (int y = floorY + 1; y <= surfaceY; y++) {
                pos.set(blockX, y, blockZ);
                if (!isStructBlock(level.getBlockState(pos)))
                    setHistoricalBlock(level, pos, WATER, 3);
            }
            return true;
        } catch (Exception e) {
            LOGGER.debug("Historical water placement [{},{}]: {}",
                    blockX, blockZ, e.getMessage());
            return false;
        }
    }

    // Small helper for the debug log only
    private boolean isBuiltUpChunk(
            LevelChunk ch,
            EsaWorldCoverIndex esa,
            TerraDensityFunctions d) {
        try {
            double[] gc = d.toGeo(
                ch.getPos().getMinBlockX() + 8,
                ch.getPos().getMinBlockZ() + 8);
            if (gc == null) return false;
            return esa.getClass(gc[1], gc[0])
                == EsaWorldCoverIndex
                    .WorldCoverClass.BUILT_UP;
        } catch (Exception e) {
            return false;
        }
    }

    // ── PASS 1: VEGETATION ONLY ───────────────────────────────
    private boolean refreshChunkForYear(
            net.minecraft.server.level.ServerLevel level,
            LevelChunk ch) {
        return refreshChunkForYear(level, ch, null);
    }

    // Vegetation pass — no road placement.
    // Clears trees at structure footprints.
    // Places built-up progression INCLUDING inside
    // structure footprints so TUFF appears under roads.
    //
    // NEW: Runs syncHistoricalWater() first to drain
    // stale water blocks from previous year before
    // any vegetation or surface changes.
    private boolean refreshChunkForYear(
            net.minecraft.server.level.ServerLevel level,
            LevelChunk ch,
            List<OsmStructureIndex.OsmStructure>
                structs) {
        if (ch == null) return false;
        try {
            // ── PASS 0: HISTORICAL WATER SYNC ─────────
            // Must run before vegetation so drained
            // areas get proper surface restoration
            // before trees/grass are placed on them.
            if (worldConfig.waterBodies && !syncHistoricalWater(level, ch)) {
                return false;
            }

            var esa = getWorldCover();
            if (!esa.isEnabled()) return true;
            var d = getDf();
            if (d == null || !d.isProjectionLoaded())
                return false;
            int cmx = ch.getPos().getMinBlockX();
            int cmz = ch.getPos().getMinBlockZ();
            int currentYear =
                TerraTimeManager.getCurrentYear();
            List<OhmStructureIndex.OhmStructure>
                upcoming = upcomingCache.getOrDefault(
                    ck(ch.getPos().x, ch.getPos().z),
                    List.of());
            BlockPos.MutableBlockPos pos =
                new BlockPos.MutableBlockPos();

            List<OsmStructureIndex.OsmStructure>
                allStructs = structs != null
                    ? structs : List.of();

            for (int lx = 0; lx < 16; lx++) {
                for (int lz = 0; lz < 16; lz++) {
                    int bx = cmx + lx, bz = cmz + lz;
                    int sy = ch.getHeight(
                        Heightmap.Types.WORLD_SURFACE,
                        lx, lz);
                    if (sy <= SL || sy >= XY - 10)
                        continue;
                    double[] geo = d.toGeo(bx, bz);
                    if (geo == null) continue;
                    double lon = geo[0], lat = geo[1];
                    if (Double.isNaN(lat)
                            || Double.isNaN(lon))
                        continue;

                    int groundY = findGroundY(
                        level, bx, sy, bz);
                    if (groundY < 0) groundY = sy;

                    // Check if inside any structure
                    boolean inStructure =
                        isInsideAnyStructure(
                            lat, lon, allStructs);

                    // Get world cover class
                    var wc = esa.getClass(lat, lon);

                    // Handle built-up areas FIRST —
                    // even inside structure footprints
                    // so TUFF is placed under roads
                    if (wc == EsaWorldCoverIndex
                            .WorldCoverClass
                            .BUILT_UP) {
                        BlockState surf =
                            level.getBlockState(
                                new BlockPos(
                                    bx, groundY, bz));
                        if (isNaturalSurfaceBlock(
                                surf)) {
                            int h =
                                TerraDensityFunctions
                                .blockHash(bx, bz);
                            int devOffset =
                                (h % 401) - 200;
                            int effectiveYear =
                                currentYear
                                - devOffset;
                            if (effectiveYear >=
                                BUILTUP_SPARSE_TREES_END)
                                removeTreesAboveSurface(
                                    level, bx,
                                    groundY, bz);
                            pos.set(bx, groundY, bz);
                            placeBuiltUpProgression(
                                level, ch, pos,
                                bx, bz, groundY,
                                lat, lon, currentYear,
                                upcoming, List.of());
                        }
                        if (inStructure) {
                            clearTreesInFootprint(
                                level, bx,
                                groundY, bz);
                        }
                        continue;
                    }

                    // For non-built-up areas inside
                    // structure footprints: just
                    // clear trees, skip vegetation
                    if (inStructure) {
                        clearTreesInFootprint(
                            level, bx, groundY, bz);
                        continue;
                    }

                    // Skip non-vegetated areas
                    if (wc == EsaWorldCoverIndex
                            .WorldCoverClass.UNKNOWN
                        || wc == EsaWorldCoverIndex
                            .WorldCoverClass.WATER
                        || wc == EsaWorldCoverIndex
                            .WorldCoverClass.SNOW_ICE
                        || wc == EsaWorldCoverIndex
                            .WorldCoverClass.BARE)
                        continue;

                    BlockState surf =
                        level.getBlockState(
                            new BlockPos(
                                bx, groundY, bz));

                    if (!isNaturalSurfaceBlock(surf))
                        continue;

                    pos.set(bx, groundY + 1, bz);
                    BlockState above =
                        level.getBlockState(pos);
                    if (isYearSensitiveVegetation(
                            above))
                        setHistoricalBlock(level, pos, AIR, 3);

                    if (!level.getBlockState(pos)
                            .isAir())
                        continue;
                    if (!isPlantable(surf)) continue;

                    double temp =
                        estimateTempFromLat(lat);

                    if (wc == EsaWorldCoverIndex
                            .WorldCoverClass
                            .TREE_COVER
                        || wc == EsaWorldCoverIndex
                            .WorldCoverClass
                            .SHRUBLAND) {
                        double fd =
                            HistoricalStructureManager
                            .getForestDensity(
                                lat, lon, upcoming,
                                List.of(),
                                currentYear);
                        if (fd <= 0.0) continue;
                        if (fd < 1.0) {
                            int bh =
                                TerraDensityFunctions
                                .blockHash(bx, bz);
                            if ((bh % 100)
                                    >= (int)(fd * 100))
                                continue;
                        }
                    }

                    switch (wc) {
                        case TREE_COVER ->
                            placeTree(level,
                                bx, groundY, bz,
                                lat, lon, temp, surf);
                        case SHRUBLAND ->
                            placeShrub(level, pos,
                                bx, groundY, bz,
                                temp);
                        case GRASSLAND ->
                            placeGrassVeg(level, pos,
                                bx, groundY, bz,
                                temp);
                        case CROPLAND -> {
                            if (currentYear < -5000)
                                placeGrassVeg(level,
                                    pos, bx, groundY,
                                    bz, temp);
                            else
                                placeCrop(level, bx,
                                    groundY, bz);
                        }
                        case WETLAND ->
                            placeWetlandVeg(level,
                                pos, bx, groundY,
                                bz, temp);
                        case MANGROVES ->
                            placeMangrove(level,
                                bx, groundY, bz);
                        case MOSS_LICHEN -> {
                            int h =
                                TerraDensityFunctions
                                .blockHash(bx, bz);
                            if (h % 8 == 0) {
                                pos.set(bx,
                                    groundY + 1, bz);
                                if (level
                                    .getBlockState(pos)
                                    .isAir())
                                    setHistoricalBlock(level, pos,
                                        Blocks
                                        .MOSS_CARPET
                                        .defaultBlockState(),
                                        3);
                            }
                        }
                        default -> {}
                    }
                }
            }
            return true;
        } catch (Exception e) {
            LOGGER.debug(
                "refreshChunk [{},{}]: {}",
                ch.getPos().x, ch.getPos().z,
                e.getMessage());
            return false;
        }
    }

    // ── FIND REAL GROUND Y ────────────────────────────────────
private static int findGroundY(
        net.minecraft.server.level.ServerLevel level,
        int bx, int sy, int bz) {
    BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
    for (int y = sy; y >= sy - 60 && y >= MY; y--) {
        pos.set(bx, y, bz);
        BlockState s = level.getBlockState(pos);
        
        // If we hit water, this is NOT a valid ground for a structure
        if (s.is(Blocks.WATER)) return -1; 
        
        if (s.isAir()) continue;
        if (isTreeBlock(s)) continue;
        if (isNaturalSurfaceBlock(s)) return y;
        return sy;
    }
    return sy;
}

        // ── CLEAR GENERATED STRUCTURES ────────────────────────────
    // Removes all Terra-generated structure blocks from
    // a loaded chunk. Called before re-generating structures
    // so loaded chunks behave like unloaded chunks.
    // Only clears blocks that are structure materials —
    // terrain, water, vegetation left intact.
    private static void clearGeneratedStructures(
            net.minecraft.server.level.ServerLevel level,
            LevelChunk ch){
        int cmx=ch.getPos().getMinBlockX();
        int cmz=ch.getPos().getMinBlockZ();
        BlockPos.MutableBlockPos pos=
            new BlockPos.MutableBlockPos();
        for(int lx=0;lx<16;lx++){
            for(int lz=0;lz<16;lz++){
                int bx=cmx+lx,bz=cmz+lz;
                int sy;
                try{
                    sy=ch.getHeight(
                        Heightmap.Types.WORLD_SURFACE,
                        lx,lz);
                }catch(Exception e){continue;}
                if(sy<=SL||sy>=XY-5)continue;

                // Find true ground — lowest non-structure
                // non-air block at or below surface
                int groundY=sy;
                for(int y=sy;y>=sy-30&&y>=MY;y--){
                    pos.set(bx,y,bz);
                    BlockState s=level.getBlockState(pos);
                    if(s.isAir())continue;
                    if(isGeneratedStructureBlock(s))
                        continue;
                    if(isNaturalSurfaceBlock(s)){
                        groundY=y;break;}
                    groundY=y;break;
                }

                // Clear everything above ground that
                // is a generated structure block
                for(int y=groundY;
                        y<=Math.min(sy+60,XY-1);y++){
                    pos.set(bx,y,bz);
                    BlockState s=level.getBlockState(pos);
                    if(s.isAir())continue;
                    if(isGeneratedStructureBlock(s)){
                        // Replace with air above ground
                        // Restore natural surface at
                        // ground level
                        if(y==groundY){
                            setHistoricalBlock(level, pos,GRASS,3);
                        }else{
                            setHistoricalBlock(level, pos,AIR,3);
                        }
                    }
                }
            }
        }
    }

    // ── GENERATED STRUCTURE BLOCK CHECK ───────────────────────
    // Returns true if a block was placed by
    // OsmStructureGenerator — roads, buildings, rails, etc.
    // Does NOT match natural terrain or vegetation blocks.
    private static boolean isGeneratedStructureBlock(
            BlockState s){
        if(s==null||s.isAir())return false;
        // Building materials
        if(s.is(Blocks.WHITE_CONCRETE))return true;
        if(s.is(Blocks.LIGHT_GRAY_CONCRETE))return true;
        if(s.is(Blocks.GRAY_CONCRETE))return true;
        if(s.is(Blocks.BLACK_CONCRETE))return true;
        if(s.is(Blocks.BROWN_CONCRETE))return true;
        if(s.is(Blocks.CYAN_CONCRETE))return true;
        if(s.is(Blocks.YELLOW_CONCRETE))return true;
        if(s.is(Blocks.RED_CONCRETE))return true;
        if(s.is(Blocks.ORANGE_CONCRETE))return true;
        if(s.is(Blocks.LIME_CONCRETE))return true;
        // Windows / glass
        if(s.is(Blocks.GLASS_PANE))return true;
        if(s.is(Blocks.LIGHT_BLUE_STAINED_GLASS))
            return true;
        // Doors
        if(s.is(Blocks.OAK_DOOR))return true;
        if(s.is(Blocks.IRON_DOOR))return true;
        if(s.is(Blocks.SPRUCE_DOOR))return true;
        // Decorative building
        if(s.is(Blocks.QUARTZ_BLOCK))return true;
        if(s.is(Blocks.SMOOTH_QUARTZ))return true;
        if(s.is(Blocks.QUARTZ_PILLAR))return true;
        if(s.is(Blocks.BRICKS))return true;
        if(s.is(Blocks.POLISHED_ANDESITE))return true;
        if(s.is(Blocks.POLISHED_DIORITE))return true;
        if(s.is(Blocks.SMOOTH_STONE))return true;
        // Rails
        if(s.is(Blocks.RAIL))return true;
        // Fences / walls / barriers
        if(s.is(Blocks.IRON_BARS))return true;
        if(s.is(Blocks.STONE_BRICK_WALL))return true;
        if(s.is(Blocks.COBBLESTONE_WALL))return true;
        if(s.is(Blocks.OAK_FENCE))return true;
        // Lights
        if(s.is(Blocks.LANTERN))return true;
        if(s.is(Blocks.SEA_LANTERN))return true;
        if(s.is(Blocks.GLOWSTONE))return true;
        // Roof / floor
        if(s.is(Blocks.OAK_PLANKS))return true;
        if(s.is(Blocks.SPRUCE_PLANKS))return true;
        if(s.is(Blocks.BIRCH_PLANKS))return true;
        if(s.is(Blocks.DARK_OAK_PLANKS))return true;
        if(s.is(Blocks.OAK_LOG))return true;
        if(s.is(Blocks.STRIPPED_OAK_LOG))return true;
        if(s.is(Blocks.STONE_BRICKS))return true;
        if(s.is(Blocks.COBBLESTONE))return true;
        // Road surfaces — these ARE structure blocks
        // when placed by OsmStructureGenerator
        // (gray/black concrete already covered above)
        if(s.is(Blocks.SMOOTH_STONE_SLAB))return true;
        if(s.is(Blocks.STONE_SLAB))return true;
        // Stairs used in buildings
        if(s.is(Blocks.OAK_STAIRS))return true;
        if(s.is(Blocks.COBBLESTONE_STAIRS))return true;
        if(s.is(Blocks.STONE_BRICK_STAIRS))return true;
        return false;
    }

    /** Log only real repeated successful placements in the same target chunk
     * and time-jump generation. This catches duplicate-building regressions
     * without treating a building spilling into a neighbour as a duplicate. */
    public static void placementDuplicateTest(ChunkPos chunk,
            List<OsmStructureIndex.OsmStructure> structures) {
        if (structures == null || structures.isEmpty()) return;
        if (successfulPlacementSignatures.size() > PLACEMENT_TEST_MAX)
            successfulPlacementSignatures.clear();
        long generation = PersistentChunkRefreshIndex.getGeneration();
        int year = TerraTimeManager.getCurrentYear();
        Set<String> batch = new HashSet<>();
        int repeated = 0;
        for (OsmStructureIndex.OsmStructure s : structures) {
            String feature;
            if (s.osmId != 0L) feature = "osm:" + s.osmId;
            else feature = "geo:" + Math.round(s.minLat * 1_000_000d) + ":"
                    + Math.round(s.minLon * 1_000_000d) + ":"
                    + Math.round(s.maxLat * 1_000_000d) + ":"
                    + Math.round(s.maxLon * 1_000_000d);
            String key = generation + ":" + year + ":" + chunk.x + ":" + chunk.z
                    + ":" + s.type + ":" + feature;
            if (!batch.add(key)) {
                LOGGER.warn("PLACEMENT_TEST duplicate input chunk=[{},{}] feature={} type={}",
                        chunk.x, chunk.z, feature, s.type);
                continue;
            }
            Long first = successfulPlacementSignatures.putIfAbsent(key,
                    System.currentTimeMillis());
            if (first != null) {
                repeated++;
                // RUN-3 FINDING: the historical-refresh drain and the async
                // placement queue both legitimately re-place the same chunk
                // within one generation (the second pass overwrites cleanly;
                // world state is correct). Per-feature WARN spam (thousands
                // of lines) buried real errors, so repeats are now counted
                // and summarised at DEBUG; a genuine regression still shows
                // as a nonzero repeat count in the summary line below.
                LOGGER.debug("PLACEMENT_TEST repeat chunk=[{},{}] feature={} type={} firstAgeMs={}",
                        chunk.x, chunk.z, feature, s.type,
                        Math.max(0L, System.currentTimeMillis() - first));
            }
        }
        if (repeated > 0)
            LOGGER.info("PLACEMENT_TEST chunk=[{},{}] year={} gen={}: {} repeats of {} features (dual-path re-place; expected during refresh)",
                    chunk.x, chunk.z, year, generation, repeated, structures.size());
        if (repeated == 0)
            LOGGER.debug("PLACEMENT_TEST success chunk=[{},{}] features={} year={} generation={}",
                    chunk.x, chunk.z, batch.size(), year, generation);
    }

    /** Read-only post-refresh check. It reports vegetation/tree-like blocks
     * still inside target-year building/bridge footprints; it never deletes or
     * claims those blocks. Wood alone is only a suspect because it may be an
     * intentional building material. */
    private void possibleStaleBlockAudit(net.minecraft.server.level.ServerLevel level,
            LevelChunk chunk, List<OsmStructureIndex.OsmStructure> structures,
            TerraDensityFunctions density) {
        if (structures == null || structures.isEmpty()) return;
        int leaves = 0, vines = 0, logs = 0, columns = 0;
        int minX = chunk.getPos().getMinBlockX();
        int minZ = chunk.getPos().getMinBlockZ();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int lx = 0; lx < 16; lx++) for (int lz = 0; lz < 16; lz++) {
            int bx = minX + lx, bz = minZ + lz;
            double[] geo = density.toGeo(bx, bz);
            if (geo == null || Double.isNaN(geo[0]) || Double.isNaN(geo[1])) continue;
            boolean targetFootprint = false;
            for (OsmStructureIndex.OsmStructure structure : structures) {
                if (structure.type != OsmStructureIndex.StructureType.BUILDING
                        && structure.type != OsmStructureIndex.StructureType.BUILDING_PART
                        && structure.type != OsmStructureIndex.StructureType.BRIDGE) continue;
                if (structure.nodes != null && structure.nodes.length >= 3
                        && structure.containsPoint(geo[1], geo[0])) {
                    targetFootprint = true;
                    break;
                }
            }
            if (!targetFootprint) continue;
            int surface = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, lx, lz);
            int localLeaves = 0, localVines = 0, localLogs = 0;
            // Bounded vertical inspection: no full world scan on a server tick.
            for (int y = Math.max(level.getMinBuildHeight(), surface - 48);
                    y <= Math.min(XY - 1, surface + 24); y++) {
                pos.set(bx, y, bz);
                BlockState state = level.getBlockState(pos);
                if (state.is(Blocks.OAK_LEAVES) || state.is(Blocks.SPRUCE_LEAVES)
                        || state.is(Blocks.BIRCH_LEAVES) || state.is(Blocks.JUNGLE_LEAVES)
                        || state.is(Blocks.ACACIA_LEAVES) || state.is(Blocks.DARK_OAK_LEAVES)
                        || state.is(Blocks.MANGROVE_LEAVES) || state.is(Blocks.CHERRY_LEAVES)) localLeaves++;
                else if (state.is(Blocks.VINE)) localVines++;
                else if (state.is(Blocks.OAK_LOG) || state.is(Blocks.SPRUCE_LOG)
                        || state.is(Blocks.BIRCH_LOG) || state.is(Blocks.JUNGLE_LOG)
                        || state.is(Blocks.ACACIA_LOG) || state.is(Blocks.DARK_OAK_LOG)
                        || state.is(Blocks.MANGROVE_LOG) || state.is(Blocks.CHERRY_LOG)) localLogs++;
            }
            if (localLeaves + localVines + localLogs > 0) {
                columns++;
                leaves += localLeaves;
                vines += localVines;
                logs += localLogs;
            }
        }
        if (leaves > 0 || vines > 0 || logs > 0)
            LOGGER.warn("POSSIBLE_STALE_BLOCK_AUDIT chunk=[{},{}] year={} footprintColumns={} leaves={} vines={} treeLikeLogs={} (read-only; logs may be intentional building wood)",
                    chunk.getPos().x, chunk.getPos().z, TerraTimeManager.getCurrentYear(),
                    columns, leaves, vines, logs);
    }

// ── UPDATED Pass 2: ALL STRUCTURES (ROADS + BUILDINGS) ──
private boolean refreshStructuresForChunk(
        net.minecraft.server.level.ServerLevel level,
        LevelChunk ch) {
    if (ch == null) return false;
    if (!PersistentChunkRefreshIndex.needsRefresh(
            level, ch.getPos().x, ch.getPos().z))
        return true;
    try {
        var d = getDf();
        if (d == null || !d.isProjectionLoaded())
            return false;
        long k = ck(ch.getPos().x, ch.getPos().z);
        // PEEK, do not remove. If placement below fails the chunk is retried,
        // and consuming the cache here meant the retry had to hit the network
        // again for data we already had.
        List<OsmStructureIndex.OsmStructure> all =
            refreshStructureCache.get(k);
        if (all == null) {
            // NON-BLOCKING: this runs on the server tick thread. A cache miss
            // returns false so the chunk stays queued and is retried once the
            // async fetch lands, instead of blocking the tick for 8 seconds.
            all = fetchStructuresCachedOnly(level, ch);
            if (all == null) return false;
        }
        if (all.isEmpty()) {
            // Pass 1 already cleared prior ownership and may have written
            // water/vegetation. Preserve those writes as this year's ledger.
            List<GeneratedBlockOwnershipIndex.OwnedBlock> transactionWrites =
                refreshOwnershipCache.remove(k);
            if (!GeneratedBlockOwnershipIndex.commit(level, ch.getPos(), transactionWrites))
                return false;
            ch.setUnsaved(true);
            return true;
        }

        // Prior owned content was cleared when pass 1 began the transaction.
        // Pass 2 only appends its exact final writes before committing.
        int cmx = ch.getPos().getMinBlockX();
        int cmz = ch.getPos().getMinBlockZ();
        
        // Filter out structures that shouldn't be underwater
        // We do this by checking the column type before placement
 // Inside refreshStructuresForChunk loop:
for (int lx = 0; lx < 16; lx++) {
    for (int lz = 0; lz < 16; lz++) {
        int bx = cmx + lx, bz = cmz + lz;
        int sy = ch.getHeight(Heightmap.Types.WORLD_SURFACE, lx, lz);
        if (sy <= SL || sy >= XY - 10) continue;

        double[] geo = d.toGeo(bx, bz);
        if (geo == null || Double.isNaN(geo[0]) || Double.isNaN(geo[1]))
            continue;

        // Check the terrain classification
        var col = d.classify(bx, bz);
        boolean isWaterTerrain = 
            col.type == TerraDensityFunctions.TerrainType.OCEAN ||
            col.type == TerraDensityFunctions.TerrainType.SHALLOW_WATER ||
            col.type == TerraDensityFunctions.TerrainType.LAKE ||
            col.type == TerraDensityFunctions.TerrainType.RIVER;

        if (isInsideAnyStructure(geo[1], geo[0], all)) {
            // Find ground, but our updated findGroundY returns -1 if it hits water
            int groundY = findGroundY(level, bx, sy, bz);

            // SKIP if it's water terrain OR findGroundY hit water
            if (isWaterTerrain || groundY == -1) {
                continue; 
            }

            clearTreesInFootprint(level, bx, groundY, bz);
        }
    }
}

        // 4. Generate structures
        // Note: The OsmStructureGenerator needs to be aware of the water check.
        // We pass the TerrainInfo to the generator or rely on the generator's 
        // internal check which we should now ensure mirrors this logic.
        OsmStructureGenerator structureGenerator = getStructureGenerator();
        structureGenerator.beginOwnershipCapture();
        boolean ok;
        List<GeneratedBlockOwnershipIndex.OwnedBlock> owned;
        try {
            ok = structureGenerator.generateStructuresFromList(ch, all);
        } finally {
            owned = structureGenerator.endOwnershipCapture();
        }

        if (!ok) return false;

        possibleStaleBlockAudit(level, ch, all, d);
        placementDuplicateTest(ch.getPos(), all);

        // The durable ledger is committed only after the generator reports a
        // complete placement. A crash/retry therefore cannot mark a partial
        // structure as current-year content.
        List<GeneratedBlockOwnershipIndex.OwnedBlock> transactionWrites =
            refreshOwnershipCache.remove(k);
        if (transactionWrites == null) transactionWrites = new ArrayList<>();
        transactionWrites.addAll(owned);
        if (!GeneratedBlockOwnershipIndex.commit(level, ch.getPos(), transactionWrites))
            return false;

        // Placement succeeded, so the cached structures are no longer needed.
        // (They are only released here; a failed placement keeps them so the
        // retry does not have to re-fetch from the network.)
        refreshStructureCache.remove(k);

        ch.setUnsaved(true);
        sendChunkToClients(level, ch);

        return true;
    } catch (Exception e) {
        LOGGER.debug("refreshStructures [{},{}]: {}",
            ch.getPos().x, ch.getPos().z, e.getMessage());
        return false;
    }
}

    // ── TREE REMOVAL ──────────────────────────────────────────
    private static void removeTreesAboveSurface(
            net.minecraft.server.level.ServerLevel level,
            int bx,int groundY,int bz){
        BlockPos.MutableBlockPos pos=
            new BlockPos.MutableBlockPos();
        for(int y=groundY+1;
                y<=Math.min(groundY+60,XY-1);y++){
            pos.set(bx,y,bz);
            BlockState s=level.getBlockState(pos);
            if(s.isAir())continue;
            if(isTreeBlock(s))
                setHistoricalBlock(level, pos,AIR,3);
            else break;
        }
    }

    private static boolean isTreeBlock(BlockState s){
        if(s==null||s.isAir())return false;
        return s.getBlock() instanceof LeavesBlock
            ||s.is(Blocks.OAK_LOG)
            ||s.is(Blocks.BIRCH_LOG)
            ||s.is(Blocks.SPRUCE_LOG)
            ||s.is(Blocks.JUNGLE_LOG)
            ||s.is(Blocks.ACACIA_LOG)
            ||s.is(Blocks.DARK_OAK_LOG)
            ||s.is(Blocks.MANGROVE_LOG)
            ||s.is(Blocks.BAMBOO)
            ||s.is(Blocks.VINE)
            ||s.is(Blocks.OAK_LEAVES)
            ||s.is(Blocks.BIRCH_LEAVES)
            ||s.is(Blocks.SPRUCE_LEAVES)
            ||s.is(Blocks.JUNGLE_LEAVES)
            ||s.is(Blocks.ACACIA_LEAVES)
            ||s.is(Blocks.DARK_OAK_LEAVES)
            ||s.is(Blocks.MANGROVE_LEAVES);}

    private static boolean isYearSensitiveVegetation(
            BlockState s){
        if(s==null||s.isAir())return false;
        return s.is(Blocks.GRASS)
            ||s.is(Blocks.TALL_GRASS)
            ||s.is(Blocks.FERN)
            ||s.is(Blocks.LARGE_FERN)
            ||s.is(Blocks.DEAD_BUSH)
            ||s.is(Blocks.DANDELION)
            ||s.is(Blocks.POPPY)
            ||s.is(Blocks.CORNFLOWER)
            ||s.is(Blocks.AZURE_BLUET)
            ||s.is(Blocks.MOSS_CARPET)
            ||s.is(Blocks.SUGAR_CANE)
            ||s.is(Blocks.WHEAT)
            ||s.is(Blocks.FARMLAND);}

    private static boolean isNaturalSurfaceBlock(
            BlockState s){
        if(s==null||s.isAir())return false;
        return s.is(Blocks.GRASS_BLOCK)
            ||s.is(Blocks.DIRT)
            ||s.is(Blocks.COARSE_DIRT)
            ||s.is(Blocks.DIRT_PATH)
            ||s.is(Blocks.SAND)
            ||s.is(Blocks.RED_SAND)
            ||s.is(Blocks.GRAVEL)
            ||s.is(Blocks.STONE)
            ||s.is(Blocks.DEEPSLATE)
            ||s.is(Blocks.BEDROCK)
            ||s.is(Blocks.SNOW_BLOCK)
            ||s.is(Blocks.PACKED_ICE)
            ||s.is(Blocks.ICE)
            ||s.is(Blocks.PODZOL)
            ||s.is(Blocks.MUD)
            ||s.is(Blocks.TUFF)
            ||s.is(Blocks.SANDSTONE)
            ||s.is(Blocks.RED_SANDSTONE)
            ||s.is(Blocks.CLAY)
            ||s.is(Blocks.WATER)
            ||s.is(Blocks.TERRACOTTA)
            ||s.is(Blocks.WHITE_TERRACOTTA)
            ||s.is(Blocks.ORANGE_TERRACOTTA)
            ||s.is(Blocks.RED_TERRACOTTA)
            ||s.is(Blocks.YELLOW_TERRACOTTA)
            ||s.is(Blocks.BROWN_TERRACOTTA)
            ||s.is(Blocks.LIGHT_GRAY_TERRACOTTA)
            ||s.is(Blocks.CYAN_TERRACOTTA);}

    // ── CONFIG ────────────────────────────────────────────────
    private void resolveConfig(){
        if(configResolved)return;
        synchronized(this){
            if(configResolved)return;
            configResolved=true;
            if(worldConfig!=TerraWorldConfig.DEFAULT){
                LOGGER.info("Using saved config: {}",
                    worldConfig);
                if(worldConfig.timeEnabled)
                    TerraTimeManager.initialize(
                        worldConfig.timeEnabled,
                        worldConfig.timeScale,
                        worldConfig.baseYear);
                return;
            }
            try{
                TerraWorldConfig p=
                    net.buildtheearth.terraplusplus
                    .client.TerraWorldTypeHelper
                    .consumePendingConfig();
                if(p!=null){
                    worldConfig=p;
                    LOGGER.info(
                        "Applied pending config: {}",
                        worldConfig);
                    if(worldConfig.timeEnabled)
                        TerraTimeManager.initialize(
                            worldConfig.timeEnabled,
                            worldConfig.timeScale,
                            worldConfig.baseYear);
                }else{
                    LOGGER.info(
                        "No pending config, using DEFAULT");
                    TerraTimeManager.initialize();
                }
            }catch(NoClassDefFoundError ignored){
                LOGGER.info(
                    "Server mode, keeping DEFAULT");
                TerraTimeManager.initialize();
            }catch(Exception e){
                LOGGER.debug("resolveConfig: {}",
                    e.getMessage());
            }
        }
    }

    // ── LAZY INIT ─────────────────────────────────────────────
    private TerraDensityFunctions getDf(){
        if(df==null)synchronized(this){
            if(df==null)df=
                new TerraDensityFunctions(zoom,universeId);}
        return df;}
    private RiverLakeEngine getRle(){
        if(rle==null)synchronized(this){
            if(rle==null)rle=
                new RiverLakeEngine(getDf());}
        return rle;}
    private AsyncWaterManager getAsyncWaterManager(){
        if(asyncWaterManager==null)synchronized(this){
            if(asyncWaterManager==null){
                RiverLakeEngine water=getRle();
                asyncWaterManager=new AsyncWaterManager(
                    water.getOsmWaterIndex(), water.getOhmWaterIndex());
                LOGGER.info("AsyncWaterManager init");}}
        return asyncWaterManager;}

    public void shutdownAsyncWater(){
        AsyncWaterManager water=asyncWaterManager;
        if(water!=null)water.shutdown();
    }
    private TerraBiomeProvider getTerraBiomes(){
        if(terraBiomes==null)synchronized(this){
            if(terraBiomes==null)try{
                terraBiomes=new TerraBiomeProvider(
                    biomeSource);
                LOGGER.info("TerraBiomeProvider init "
                    +"({} biomes)",
                    terraBiomes.getBiomeCount());
            }catch(Exception e){
                LOGGER.error(
                    "TerraBiomeProvider failed: {}",
                    e.getMessage());}}
        return terraBiomes;}
    private OsmStructureIndex getStructureIndex(){
        if(structureIndex==null)synchronized(this){
            if(structureIndex==null){
                structureIndex=new OsmStructureIndex();
                // APPETIZER race guard: lets markServed detect
                // "tile already arrived" and self-swap (the
                // standalone RACE TEST caught chunks that would
                // otherwise stay provisional forever).
                final OsmStructureIndex idx=structureIndex;
                net.buildtheearth.terraplusplus.generator
                    .historical.ProvisionalServing.presenceCheck=
                    (la,lo)->idx.areTilesLoaded(la,la,lo,lo);
                LOGGER.info("OsmStructureIndex init");}}
        return structureIndex;}

    // ── PARADOX ENGINE ACCESSORS ──────────────────────────────
    /** Public block→geo for ParadoxEngine (returns [lon,lat]). */
    public double[] toGeoPublic(int blockX,int blockZ){
        var d=getDf();
        return d==null?null:d.toGeo(blockX,blockZ);}
    /** Public structure index access for ParadoxEngine. */
    public OsmStructureIndex getStructureIndexPublic(){
        return getStructureIndex();}

    // ── COLD-START DESTINATION WARMUP ─────────────────────────
    // Run 7 forensics: /tpll to cold Durgam Cheruvu spent ~90s
    // generating terrain, and only THEN did chunks start asking
    // for structure/water/cover tiles — serially, per source.
    // The player wait and the network wait ran back-to-back.
    // This fires every source's prefetch in ONE parallel burst
    // at command time, so tiles download WHILE terrain
    // generates. Warm starts are untouched (prefetch methods
    // are mem+disk-checked no-ops on hits). ~0.01 deg covers
    // the teleport view radius.
    public void warmDestination(double lat,double lon){
        final double R=0.01;
        long t0=System.currentTimeMillis();
        int fired=0;
        try{getStructureIndex().prefetchTiles(
            lat-R,lat+R,lon-R,lon+R);fired++;
        }catch(Exception e){
            LOGGER.warn("WARMUP osm-struct failed: {}",
                e.getMessage());}
        // OVERTURE DEMOTION (user directive: "If osm is fast
        // there is no need for overture"): only prefetch the
        // appetizer when live OSM shows evidence of struggling
        // (API cooldown, recent failure, or last fetch >3s).
        // Healthy OSM = zero Overture bandwidth; the appetizer
        // machinery stays dormant and self-arms when needed.
        try{if(net.buildtheearth.terraplusplus.generator.osm
                .OsmStructureIndex.osmStruggling()){
            net.buildtheearth.terraplusplus.generator.osm
                .OvertureOsmIndex.getInstance()
                .prefetch(lat,lon);fired++;
            LOGGER.info("WARMUP: OSM struggling — Overture "
                +"appetizer armed");}
        }catch(Exception e){
            LOGGER.warn("WARMUP overture-osm failed: {}",
                e.getMessage());}
        try{if(worldConfig.overtureBuildings){
            getMicrosoftIndex().prefetchTiles(
                lat-R,lat+R,lon-R,lon+R);fired++;}
        }catch(Exception e){
            LOGGER.warn("WARMUP msft failed: {}",
                e.getMessage());}
        try{if(worldConfig.waterBodies){
            var rle=getRle();
            rle.getOsmWaterIndex().prefetch(lat,lon);
            rle.getOhmWaterIndex().prefetch(lat,lon);
            fired+=2;}
        }catch(Exception e){
            LOGGER.warn("WARMUP water failed: {}",
                e.getMessage());}
        try{getWorldCover().prefetch(lat,lon);fired++;
        }catch(Exception e){
            LOGGER.warn("WARMUP worldcover failed: {}",
                e.getMessage());}
        // 7th SOURCE (teleport/terrain fix): ELEVATION. It was
        // never prefetched — the one source every single column
        // of every chunk blocks on (up to 3s per missing tile,
        // serially). Warm the whole destination area so terrain
        // gen becomes pure math on memory hits.
        try{var d=getDf();
            if(d!=null&&d.isProjectionLoaded()){
                d.prefetchArea(lat-R,lat+R,lon-R,lon+R);
                fired++;}
        }catch(Exception e){
            LOGGER.warn("WARMUP elevation failed: {}",
                e.getMessage());}
        LOGGER.info("COLD WARMUP [{},{}]: {} sources "
            +"prefetching in parallel with terrain gen "
            +"({}ms to dispatch)",
            String.format("%.4f",lat),
            String.format("%.4f",lon),
            fired,System.currentTimeMillis()-t0);}

    private OsmStructureGenerator
            getStructureGenerator(){
        if(structureGenerator==null)synchronized(this){
            if(structureGenerator==null){
                structureGenerator=
                    new OsmStructureGenerator(
                        getStructureIndex(),getDf(),
                        worldConfig);
                LOGGER.info(
                    "OsmStructureGenerator init");}}
        return structureGenerator;}
    private MicrosoftIndex getMicrosoftIndex(){
        if(overtureIndex==null)synchronized(this){
            if(overtureIndex==null){
                overtureIndex=MicrosoftIndex.getInstance();
                LOGGER.info("MicrosoftIndex init");}}
        return overtureIndex;}
    private SourceCooperativeIndex getVidaIndex(){
        if(vidaIndex==null)synchronized(this){
            if(vidaIndex==null){
                vidaIndex=SourceCooperativeIndex.getInstance();
                LOGGER.info("SourceCooperativeIndex (VIDA) init");}}
        return vidaIndex;}
    private StructurePlacementQueue getPlacementQueue(){
        if(placementQueue==null)synchronized(this){
            if(placementQueue==null){
                placementQueue=
                    new StructurePlacementQueue();
                LOGGER.info("PlacementQueue init");}}
        return placementQueue;}
    private AsyncStructureManager getAsyncManager(){
        if(asyncManager==null)synchronized(this){
            if(asyncManager==null){
                asyncManager=new AsyncStructureManager(
                    getOhmIndex(),
                    getStructureIndex(),
                    getMicrosoftIndex(),
                    getStructureGenerator(),getDf(),
                    worldConfig,getPlacementQueue());
                LOGGER.info(
                    "AsyncStructureManager init");
                if(!StructureTickHandler.isInitialized())
                    StructureTickHandler.initialize(
                        getPlacementQueue(),
                        getStructureGenerator(),
                        asyncManager,getDf(),
                        worldConfig);}}
        return asyncManager;}
    private EsaWorldCoverIndex getWorldCover(){
        if(worldCover==null)synchronized(this){
            if(worldCover==null){
                worldCover=new EsaWorldCoverIndex();
                LOGGER.info(
                    "EsaWorldCoverIndex init");}}
        return worldCover;}
    private OhmStructureIndex getOhmIndex(){
        if(ohmIndex==null)synchronized(this){
            if(ohmIndex==null){
                ohmIndex=new OhmStructureIndex();
                ohmIndex.setEnabled(
                    worldConfig.ohmEnabled);
                LOGGER.info(
                    "OhmStructureIndex init "
                    +"(enabled={})",
                    worldConfig.ohmEnabled);}}
        return ohmIndex;}
    private HistoricalStructureManager
            getHistoricalManager(){
        if(historicalManager==null)synchronized(this){
            if(historicalManager==null){
                historicalManager=
                    new HistoricalStructureManager(
                        getOhmIndex(),
                        getStructureIndex(),
                        worldConfig);
                LOGGER.info(
                    "HistoricalStructureManager init");}}
        return historicalManager;}

    // ── CHUNK KEY ─────────────────────────────────────────────
    private long ck(int cx,int cz){
        return((long)cx<<32)|(cz&0xFFFFFFFFL);}

    private void evict(int cx,int cz){
        if(terrChunks.size()>8192)
            terrChunks.entrySet().removeIf(e->{
                int x=(int)(e.getKey()>>32);
                int z=(int)(e.getKey().longValue());
                return Math.abs(x-cx)>128
                    ||Math.abs(z-cz)>128;});
        if(strChunks.size()>65536)
            strChunks.entrySet().removeIf(e->{
                int x=(int)(e.getKey()>>32);
                int z=(int)(e.getKey().longValue());
                return Math.abs(x-cx)>512
                    ||Math.abs(z-cz)>512;});
        if(waterRetryChunks.size()>4096){
            long now=System.currentTimeMillis();
            waterRetryChunks.entrySet().removeIf(
                e->now-e.getValue()[0]
                    >WATER_RETRY_MAX_AGE_MS);}
        if(upcomingCache.size()>4096)
            upcomingCache.entrySet().removeIf(e->{
                int x=(int)(e.getKey()>>32);
                int z=(int)(e.getKey().longValue());
                return Math.abs(x-cx)>256
                    ||Math.abs(z-cz)>256;});}

    // ── NBT / PRESENCE ────────────────────────────────────────
    private boolean isTerrainPlaced(ChunkAccess ch){
        long k=ck(ch.getPos().x,ch.getPos().z);
        return terrChunks.containsKey(k)
            ||getChunkPresence(ch)>=PRESENCE_TERRAIN;}
    private void markTerrainPlaced(ChunkAccess ch){
        terrChunks.put(
            ck(ch.getPos().x,ch.getPos().z),true);}
    private boolean isDecorationPlaced(ChunkAccess ch){
        long k=ck(ch.getPos().x,ch.getPos().z);
        return strChunks.containsKey(k)
            ||getChunkPresence(ch)>=PRESENCE_DECORATED;}
    private void markDecorationPlaced(ChunkAccess ch){
        strChunks.put(
            ck(ch.getPos().x,ch.getPos().z),true);}

    // ── CHUNK PRESENCE ────────────────────────────────────────
    private int getChunkPresence(ChunkAccess ch){
        int cx=ch.getPos().getMinBlockX();
        int cz=ch.getPos().getMinBlockZ();
        BlockPos.MutableBlockPos p=
            new BlockPos.MutableBlockPos();
        boolean hasTerrain=false,hasStructure=false;
        int[][] samples={
            {0,0},{4,0},{8,0},{12,0},
            {0,4},{4,4},{8,4},{12,4},
            {0,8},{4,8},{8,8},{12,8},
            {0,12},{4,12},{8,12},{12,12}};
        for(int[] s:samples){
            int lx=s[0],lz=s[1],sy;
            try{sy=ch.getHeight(
                Heightmap.Types.WORLD_SURFACE,lx,lz);
            }catch(Exception e){continue;}
            if(sy>MY+10){
                p.set(cx+lx,sy,cz+lz);
                BlockState bs=ch.getBlockState(p);
                if(!bs.isAir()&&!bs.is(Blocks.WATER))
                    hasTerrain=true;}
            if(!hasStructure)
                for(int y=sy;y<=Math.min(sy+60,
                        ch.getMaxBuildHeight()-1);y++){
                    p.set(cx+lx,y,cz+lz);
                    if(isStructBlock(
                            ch.getBlockState(p))){
                        hasStructure=true;break;}}
            if(hasTerrain&&hasStructure)break;}
        if(hasStructure)return PRESENCE_DECORATED;
        if(hasTerrain)return PRESENCE_TERRAIN;
        return PRESENCE_EMPTY;}

    private static boolean isStructBlock(BlockState s){
        if(s.isAir())return false;
        return s.is(Blocks.WHITE_CONCRETE)
            ||s.is(Blocks.LIGHT_GRAY_CONCRETE)
            ||s.is(Blocks.GRAY_CONCRETE)
            ||s.is(Blocks.BLACK_CONCRETE)
            ||s.is(Blocks.BROWN_CONCRETE)
            ||s.is(Blocks.CYAN_CONCRETE)
            ||s.is(Blocks.YELLOW_CONCRETE)
            ||s.is(Blocks.RED_CONCRETE)
            ||s.is(Blocks.ORANGE_CONCRETE)
            ||s.is(Blocks.LIME_CONCRETE)
            ||s.is(Blocks.GLASS_PANE)
            ||s.is(Blocks.LIGHT_BLUE_STAINED_GLASS)
            ||s.is(Blocks.OAK_DOOR)
            ||s.is(Blocks.IRON_DOOR)
            ||s.is(Blocks.SPRUCE_DOOR)
            ||s.is(Blocks.QUARTZ_BLOCK)
            ||s.is(Blocks.SMOOTH_QUARTZ)
            ||s.is(Blocks.QUARTZ_PILLAR)
            ||s.is(Blocks.RAIL)
            ||s.is(Blocks.IRON_BARS)
            ||s.is(Blocks.LANTERN)
            ||s.is(Blocks.SEA_LANTERN)
            ||s.is(Blocks.BRICKS)
            ||s.is(Blocks.POLISHED_ANDESITE)
            ||s.is(Blocks.POLISHED_DIORITE)
            ||s.is(Blocks.SMOOTH_STONE)
            ||s.is(Blocks.STONE_BRICK_WALL)
            ||s.is(Blocks.COBBLESTONE_WALL)
            ||s.is(Blocks.OAK_FENCE);}

    // ── OVERRIDES ─────────────────────────────────────────────
    @Override protected Codec<? extends ChunkGenerator>
        codec(){return CODEC;}
    @Override public int getGenDepth(){return GD;}
    @Override public int getSeaLevel(){return SL;}
    @Override public int getMinY(){return MY;}
    @Override public void applyCarvers(WorldGenRegion a,
        long b,RandomState c,BiomeManager d,
        StructureManager e,ChunkAccess f,
        GenerationStep.Carving g){}
    @Override public void buildSurface(WorldGenRegion a,
        StructureManager b,RandomState c,
        ChunkAccess d){}
    @Override public void spawnOriginalMobs(
        WorldGenRegion a){}

    // ── FILL FROM NOISE ───────────────────────────────────────
    @Override
    public CompletableFuture<ChunkAccess> fillFromNoise(
            java.util.concurrent.Executor ex,Blender bl,
            RandomState rs,StructureManager sm,
            ChunkAccess ch){
        return CompletableFuture.supplyAsync(()->{
            int cx=ch.getPos().x,cz=ch.getPos().z;
            long k=ck(cx,cz);
            int presence=getChunkPresence(ch);
            if(presence>=PRESENCE_DECORATED){
                strChunks.put(k,true);
                terrChunks.put(k,true);
                assignBiomes(ch);return ch;}
            if(presence>=PRESENCE_TERRAIN){
                terrChunks.put(k,true);
                assignBiomes(ch);return ch;}
            if(terrChunks.containsKey(k)){
                assignBiomes(ch);return ch;}
            // COLD-START PIPELINE: kick data downloads THE MOMENT
            // terrain generation starts, not when decoration
            // discovers missing tiles minutes later. Terrain gen
            // is the prefetch window — generating and downloading
            // run simultaneously instead of back-to-back.
            // Area-deduped (one trigger per 0.01-deg tile ≈ 41
            // chunks); warm start = mem/disk hit = no-op.
            prefetchForChunk(cx,cz);
            generateTerrain(ch);
            assignBiomes(ch);
            markTerrainPlaced(ch);
            evict(cx,cz);
            return ch;},ex);}

    // ── GENERATION-TIME PREFETCH ──────────────────────────────
    /** 0.01-deg areas whose data prefetch already fired. Bounded:
     *  cleared when it grows past 4096 entries (an area re-fire
     *  is a cheap cache no-op anyway). */
    private final Set<Long> prefetchedAreas =
        java.util.concurrent.ConcurrentHashMap.newKeySet();

    private void prefetchForChunk(int cx,int cz){
        try{
            var d=getDf();
            if(d==null||!d.isProjectionLoaded())return;
            double[] g=d.toGeo((cx<<4)+8,(cz<<4)+8);
            if(g==null||Double.isNaN(g[0]))return;
            // LOADING-SCREEN FIX: don't warm 6 data sources
            // for open-Atlantic spawn chunks (projection
            // origin). Nothing to fetch there at any year.
            if(isDeepOcean(g[1],g[1],g[0],g[0]))return;
            long area=(((long)Math.floor(g[1]/0.01))<<32)
                |(((long)Math.floor(g[0]/0.01))&0xFFFFFFFFL);
            if(!prefetchedAreas.add(area))return;
            if(prefetchedAreas.size()>4096)
                prefetchedAreas.clear();
            // Already on a worker thread; prefetches are
            // fire-and-forget submits to each index's dl-pool.
            warmDestination(g[1],g[0]);
        }catch(Exception ignored){}}

    // ── LOADING-SCREEN FIX: deep-ocean detection ─────────────
    private final java.util.concurrent.atomic.AtomicInteger
        oceanFastLogged=
            new java.util.concurrent.atomic.AtomicInteger();
    /** True when every sampled corner+centre of the bbox is far
     *  below sea level (<-30m). Elevation comes from the same
     *  cache the terrain pass just filled, so this costs no
     *  network. -30m keeps every coastal, lake and shallow
     *  chunk on the normal path (Netherlands at -6m is safe). */
    private boolean isDeepOcean(double mnLa,double mxLa,
            double mnLo,double mxLo){
        try{
            var d=getDf();
            if(d==null)return false;
            double[][] pts={
                {mnLa,mnLo},{mnLa,mxLo},
                {mxLa,mnLo},{mxLa,mxLo},
                {(mnLa+mxLa)/2,(mnLo+mxLo)/2}};
            for(double[] p:pts)
                if(d.getElevationFast(p[0],p[1])>-30)
                    return false;
            return true;
        }catch(Exception e){return false;}}


    // ── BIOME DECORATION ──────────────────────────────────────
    @Override
    public void applyBiomeDecoration(WorldGenLevel lvl,
            ChunkAccess ch,StructureManager sm){
        resolveConfig();
        retryWaterIfNeeded(lvl,ch);
        int cx=ch.getPos().x,cz=ch.getPos().z;
        long k=ck(cx,cz);
        boolean markCurrentYear=false;

        if(isDecorationPlaced(ch)){
            List<OhmStructureIndex.OhmStructure>
                upcoming=upcomingCache.getOrDefault(
                    k,List.of());
            placeWorldCoverVegetation(lvl,ch,
                upcoming,List.of());
            return;}
        evict(cx,cz);
        if(worldConfig.cleanupArtifacts
                &&getChunkPresence(ch)
                <PRESENCE_DECORATED)
            cleanArtifacts(ch);
        if(!worldConfig.hasAnyStructures()){
            markDecorationPlaced(ch);
            placeWorldCoverVegetation(lvl,ch,
                List.of(),List.of());
            markChunkCurrentYear(ch);
            return;}
        try{
            double[] gc=getDf().toGeo(
                ch.getPos().getMinBlockX()+8,
                ch.getPos().getMinBlockZ()+8);
            if(gc==null||Double.isNaN(gc[0])
                    ||Double.isNaN(gc[1])){
                markDecorationPlaced(ch);
                placeWorldCoverVegetation(lvl,ch,
                    List.of(),List.of());
                markChunkCurrentYear(ch);
                return;}
            double cLon=gc[0],cLat=gc[1];
            double mnLa=cLat,mxLa=cLat,
                   mnLo=cLon,mxLo=cLon;
            for(double[] g:new double[][]{
                getDf().toGeo(
                    ch.getPos().getMinBlockX(),
                    ch.getPos().getMinBlockZ()),
                getDf().toGeo(
                    ch.getPos().getMinBlockX()+15,
                    ch.getPos().getMinBlockZ()),
                getDf().toGeo(
                    ch.getPos().getMinBlockX(),
                    ch.getPos().getMinBlockZ()+15),
                getDf().toGeo(
                    ch.getPos().getMinBlockX()+15,
                    ch.getPos().getMinBlockZ()+15)})
                if(g!=null&&!Double.isNaN(g[0])
                        &&!Double.isNaN(g[1])){
                    mnLa=Math.min(mnLa,g[1]);
                    mxLa=Math.max(mxLa,g[1]);
                    mnLo=Math.min(mnLo,g[0]);
                    mxLo=Math.max(mxLo,g[0]);}
            // LOADING-SCREEN FIX: deep-ocean fast path. World
            // spawn is the projection origin (open Atlantic,
            // -4835m); run 13's loading screen spent ~50s
            // asking OSM/OHM/WorldCover about 441 chunks of
            // open water. If every sample is deep ocean there
            // are no structures/vegetation at ANY year: mark
            // decorated and return without touching a single
            // data source. Elevation is already cached from
            // the terrain pass, so this is pure math. -30m
            // spares all coastal/shallow chunks.
            if(isDeepOcean(mnLa,mxLa,mnLo,mxLo)){
                if(oceanFastLogged.getAndIncrement()<10)
                    LOGGER.info("OCEAN FASTPATH [{},{}]: "
                        +"deep ocean chunk, all data "
                        +"sources skipped",cx,cz);
                markDecorationPlaced(ch);
                markChunkCurrentYear(ch);
                return;}
            List<OsmStructureIndex.OsmStructure> all=
                new ArrayList<>();
            List<OhmStructureIndex.OhmStructure>
                upcoming=new ArrayList<>();
            boolean retry=false;
            // FAST-TELEPORT FIX: short bounded wait instead of
            // 8s/source. Miss -> appetizer + async retry; the
            // chunk reaches FULL immediately and the swap
            // machinery upgrades it when downloads land.
            if(worldConfig.timeEnabled){
                var merged=getHistoricalManager()
                    .getStructuresForChunk(
                        mnLa,mxLa,mnLo,mxLo,
                        GEN_STRUCT_WAIT_MS);
                if(merged==null){
                    retry=true;
                    LOGGER.debug(
                        "Historical timeout [{},{}]",
                        cx,cz);
                }else{
                    all.addAll(merged.visible);
                    upcoming.addAll(merged.upcoming);
                    upcomingCache.put(k,upcoming);}
            }else{
                String src=activeSource;
                if("osm".equals(src)
                        ||"both".equals(src))try{
                    var idx=getStructureIndex();
                    var os=idx
                        .getStructuresForChunkBlocking(
                            mnLa,mxLa,mnLo,mxLo,
                            worldConfig,
                            GEN_STRUCT_WAIT_MS);
                    if(os!=null&&!os.isEmpty())
                        all.addAll(os);
                    else if(os==null){
                        retry=true;
                        LOGGER.debug(
                            "OSM timeout [{},{}]",
                            cx,cz);}
                    else if(!idx.areTilesLoaded(
                            mnLa,mxLa,mnLo,mxLo))
                        retry=true;
                }catch(Exception e){
                    LOGGER.debug("OSM [{},{}]: {}",
                        cx,cz,e.getMessage());
                    retry=true;}
                // ── TIER 3: VIDA (Google/MS/OSM open buildings) ──
                // Runs after OSM, before Microsoft. Gap-fill only:
                // anything overlapping an OSM footprint is dropped.
                List<SourceCooperativeIndex.VidaBuilding>
                    vidaAccepted=new ArrayList<>();
                if(("vida".equals(src)
                        ||"both".equals(src))
                        &&worldConfig.vidaBuildings)
                    try{
                        var vi=getVidaIndex();
                        var vb=vi
                            .getBuildingsForChunkBlocking(
                                mnLa,mxLa,mnLo,mxLo,
                                GEN_STRUCT_WAIT_MS,
                                null);
                        if(vb==null)
                            retry=true;   // shard still downloading
                        else{
                            var vPrints=new ArrayList<
                                FootprintAlignment.Print>(vb.size());
                            for(var b:vb)
                                vPrints.add(FootprintAlignment.Print.of(b));
                            var vShift=SourceAlignment.shiftFor(
                                "vida",(mnLa+mxLa)/2,(mnLo+mxLo)/2,
                                vPrints,all);
                            for(var raw:vb){
                            var b=raw.shifted(vShift.dLat,vShift.dLon);
                            if(SourceCooperativeIndex
                                    .overlapsExisting(b,all))
                                continue;
                            if(FootprintAlignment.sitsOnRoad(
                                    FootprintAlignment.Print.of(b),all))
                                continue;
                            vidaAccepted.add(b);
                            all.add(b.toOsmStructure());}}
                    }catch(Exception e){
                        LOGGER.debug("VIDA [{},{}]: {}",
                            cx,cz,e.getMessage());}
                if(("overture".equals(src)
                        ||"both".equals(src))
                        &&worldConfig.overtureBuildings)
                    try{
                        var ov=getMicrosoftIndex();
                        if(ov.isEnabled()){
                            var ob=ov
                                .getBuildingsForChunkBlocking(
                                    mnLa,mxLa,mnLo,mxLo,
                                    GEN_STRUCT_WAIT_MS);
                            if(ob!=null&&!ob.isEmpty()){
                                List<OsmStructureIndex
                                    .OsmStructure> blk=
                                    new ArrayList<>();
                                if("both".equals(src)){
                                    for(var s:all)
                                        if(s.type==
                                            OsmStructureIndex
                                            .StructureType
                                            .BUILDING
                                            ||s.type==
                                            OsmStructureIndex
                                            .StructureType
                                            .BRIDGE)
                                            blk.add(s);
                                }else try{
                                    var oc=
                                        getStructureIndex()
                                        .getStructuresForChunkBlocking(
                                            mnLa,mxLa,
                                            mnLo,mxLo,
                                            worldConfig,
                                            GEN_STRUCT_WAIT_MS);
                                    if(oc!=null)
                                        for(var s:oc)
                                            if(s.type==
                                                OsmStructureIndex
                                                .StructureType
                                                .BUILDING
                                                ||s.type==
                                                OsmStructureIndex
                                                .StructureType
                                                .BRIDGE)
                                                blk.add(s);
                                }catch(Exception e){
                                    LOGGER.debug(
                                        "OSM blk [{},{}]"
                                        +": {}",
                                        cx,cz,
                                        e.getMessage());}
                                var mPrints=new ArrayList<
                                    FootprintAlignment.Print>(ob.size());
                                for(var b:ob)
                                    mPrints.add(
                                        FootprintAlignment.Print.of(b));
                                var mShift=SourceAlignment.shiftFor(
                                    "overture",(mnLa+mxLa)/2,
                                    (mnLo+mxLo)/2,mPrints,all);
                                for(var raw:ob){
                                    var b=raw.shifted(
                                        mShift.dLat,mShift.dLon);
                                    if(!blk.isEmpty()
                                            &&overlaps(
                                                b,blk))
                                        continue;
                                    // Microsoft is the lowest tier:
                                    // also yield to VIDA placements.
                                    if(SourceCooperativeIndex
                                            .overlapsVida(
                                                b,vidaAccepted))
                                        continue;
                                    if(FootprintAlignment.sitsOnRoad(
                                            FootprintAlignment.Print.of(b),
                                            all))
                                        continue;
                                    all.add(
                                        b.toOsmStructure());}
                            }else if(!ov.areTilesLoaded(
                                    mnLa,mxLa,mnLo,mxLo))
                                retry=true;}
                    }catch(Exception e){
                        LOGGER.debug(
                            "Overture [{},{}]: {}",
                            cx,cz,e.getMessage());
                        retry=true;}
            }
            // ── APPETIZER (generation path — fast-teleport fix)
            // Main sources missed the short budget: plate any
            // resident Overture data NOW so the chunk generates
            // with buildings/roads instead of bare, and mark it
            // provisional; the async retry above still runs and
            // the swap upgrades to the main course on arrival.
            if(retry&&all.isEmpty()&&APPETIZER_SERVING){
                try{
                    var ovOsm=net.buildtheearth.terraplusplus
                        .generator.osm.OvertureOsmIndex
                        .getInstance().getStructuresForArea(
                            mnLa,mxLa,mnLo,mxLo,
                            GEN_APPETIZER_WAIT_MS);
                    if(ovOsm!=null&&!ovOsm.isEmpty()){
                        int yr=TerraTimeManager
                            .getCurrentYear();
                        for(var s:ovOsm)
                            if(!worldConfig.timeEnabled
                                    ||HistoricalDateParser
                                        .isVisibleAt(
                                            s.startYear,
                                            s.endYear,yr,true))
                                all.add(s);
                        if(!all.isEmpty())
                            net.buildtheearth.terraplusplus
                                .generator.historical
                                .ProvisionalServing.markServed(
                                    cx,cz,
                                    (mnLa+mxLa)/2,
                                    (mnLo+mxLo)/2,all.size());
                    }
                }catch(Exception e){
                    LOGGER.debug(
                        "gen appetizer [{},{}]: {}",
                        cx,cz,e.getMessage());}
            }
            if(!all.isEmpty()){
                boolean success=getStructureGenerator()
                    .generateStructuresFromList(ch,all);
                if(success){
                    placementDuplicateTest(ch.getPos(), all);
                    markDecorationPlaced(ch);
                    markCurrentYear=true;
                }else{
                    LOGGER.debug(
                        "Placement failed [{},{}]",
                        cx,cz);
                    retry=true;}
            }else if(!retry){
                markDecorationPlaced(ch);
                markCurrentYear=true;}
            if(retry)try{
                getAsyncManager().queueRetry(
                    cLat,cLon,cx,cz);
            }catch(Exception e){
                LOGGER.debug("Async [{},{}]: {}",
                    cx,cz,e.getMessage());}
        }catch(Exception e){
            LOGGER.error("Struct [{},{}]: {}",
                cx,cz,e.getMessage());
            markDecorationPlaced(ch);}
        List<OhmStructureIndex.OhmStructure> upcoming=
            upcomingCache.getOrDefault(k,List.of());
        placeWorldCoverVegetation(lvl,ch,upcoming,
            List.of());
        if(markCurrentYear)
            markChunkCurrentYear(ch);}

    // ── OVERLAP HELPERS ───────────────────────────────────────
    private static boolean overlaps(
            MicrosoftIndex.MicrosoftBuilding m,
            List<OsmStructureIndex.OsmStructure> blk){
        for(var o:blk){
            if(o.nodes==null||o.nodes.length<2)
                continue;
            if(o.type==OsmStructureIndex
                    .StructureType.BUILDING
                    &&o.isArea&&o.nodes.length>=3){
                double[][] p=toPoly(o.nodes);
                if(pip(m.centerLat,m.centerLon,p))
                    return true;
                for(double[] pt:m.polygon)
                    if(pip(pt[0],pt[1],p))return true;
                double cl=0,cn=0;
                for(var n:o.nodes){
                    cl+=n.lat;cn+=n.lon;}
                cl/=o.nodes.length;
                cn/=o.nodes.length;
                if(pip(cl,cn,m.polygon))return true;
                // Near-miss: ML footprints (Microsoft/Google) sit a few metres
                // off the hand-mapped OSM position, so they do not overlap and
                // used to be placed as a duplicate beside the real building.
                if(FootprintAlignment.isSameBuilding(
                        FootprintAlignment.Print.of(m),
                        FootprintAlignment.Print.of(o)))
                    return true;
                // STRICT: OSM outranks Microsoft, so ANY shared area removes
                // the Microsoft footprint, even for a different building.
                var mn=new OsmStructureIndex.OsmNode[m.polygon.length];
                for(int i=0;i<m.polygon.length;i++)
                    mn[i]=new OsmStructureIndex.OsmNode(
                        m.polygon[i][0],m.polygon[i][1]);
                if(FootprintAlignment.footprintsIntersect(
                        mn,m.minLat,m.maxLat,m.minLon,m.maxLon,
                        o.nodes,o.minLat,o.maxLat,o.minLon,o.maxLon))
                    return true;
            }else if(o.type==OsmStructureIndex
                    .StructureType.BRIDGE){
                double t=0.00009;
                if(pnl(m.centerLat,m.centerLon,
                        o.nodes,t))return true;
                for(double[] pt:m.polygon)
                    if(pnl(pt[0],pt[1],o.nodes,t))
                        return true;}}
        return false;}
    private static boolean pnl(double la,double lo,
            OsmStructureIndex.OsmNode[] n,double t){
        for(int i=0;i<n.length-1;i++)
            if(OsmStructureIndex.OsmStructure
                    .ptSegDist(la,lo,
                        n[i].lat,n[i].lon,
                        n[i+1].lat,n[i+1].lon)<t)
                return true;
        return false;}
    private static double[][] toPoly(
            OsmStructureIndex.OsmNode[] n){
        double[][] p=new double[n.length][2];
        for(int i=0;i<n.length;i++){
            p[i][0]=n[i].lat;p[i][1]=n[i].lon;}
        return p;}
    private static boolean pip(double la,double lo,
            double[][] p){
        boolean in=false;
        for(int i=0,j=p.length-1;i<p.length;j=i++)
            if(((p[i][0]>la)!=(p[j][0]>la))
                &&(lo<(p[j][1]-p[i][1])*(la-p[i][0])
                /(p[j][0]-p[i][0])+p[i][1]))in=!in;
        return in;}

    // ── WORLDCOVER VEGETATION ─────────────────────────────────
    // No road placement — roads handled in pass 2
    // Only skips vegetation at visible structure footprints
    private void placeWorldCoverVegetation(
            WorldGenLevel lvl,ChunkAccess ch,
            List<OhmStructureIndex.OhmStructure> upcoming,
            List<OsmStructureIndex.OsmStructure> visible){
        try{
            var esa=getWorldCover();
            if(!esa.isEnabled())return;
            var d=getDf();
            if(d==null||!d.isProjectionLoaded())return;
            int cmx=ch.getPos().getMinBlockX();
            int cmz=ch.getPos().getMinBlockZ();
            int currentYear=
                TerraTimeManager.getCurrentYear();
            BlockPos.MutableBlockPos pos=
                new BlockPos.MutableBlockPos();

            for(int lx=0;lx<16;lx++)
                for(int lz=0;lz<16;lz++){
                    int bx=cmx+lx,bz=cmz+lz;
                    int sy=ch.getHeight(
                        Heightmap.Types.WORLD_SURFACE,
                        lx,lz);
                    if(sy<=SL||sy>=XY-10)continue;
                    pos.set(bx,sy,bz);
                    BlockState surf=
                        lvl.getBlockState(pos);
                    pos.set(bx,sy+1,bz);
                    boolean aboveAir=
                        lvl.getBlockState(pos).isAir();
                    double[] geo=d.toGeo(bx,bz);
                    if(geo==null)continue;
                    double lon=geo[0],lat=geo[1];
                    if(Double.isNaN(lat)
                            ||Double.isNaN(lon))
                        continue;

                    // Skip vegetation at visible
                    // structure footprints
                    if(!visible.isEmpty()
                            &&isInsideAnyStructure(
                                lat,lon,visible))
                        continue;

                    var wc=esa.getClass(lat,lon);
                    if(wc==EsaWorldCoverIndex
                            .WorldCoverClass.UNKNOWN
                        ||wc==EsaWorldCoverIndex
                            .WorldCoverClass.WATER
                        ||wc==EsaWorldCoverIndex
                            .WorldCoverClass.SNOW_ICE
                        ||wc==EsaWorldCoverIndex
                            .WorldCoverClass.BARE)
                        continue;
                    if(wc==EsaWorldCoverIndex
                            .WorldCoverClass.BUILT_UP){
                        pos.set(bx,sy,bz);
                        surf=lvl.getBlockState(pos);
                        if(isNaturalSurfaceBlock(surf))
                            placeBuiltUpProgression(
                                lvl,ch,pos,bx,bz,sy,
                                lat,lon,currentYear,
                                upcoming,visible);
                        continue;}
                    if(!isPlantable(surf)||!aboveAir)
                        continue;
                    if(wc==EsaWorldCoverIndex
                            .WorldCoverClass.TREE_COVER
                        ||wc==EsaWorldCoverIndex
                            .WorldCoverClass.SHRUBLAND){
                        double fd=
                            HistoricalStructureManager
                            .getForestDensity(lat,lon,
                                upcoming,visible,
                                currentYear);
                        if(fd<=0.0)continue;
                        if(fd<1.0){
                            int bh=TerraDensityFunctions
                                .blockHash(bx,bz);
                            if((bh%100)>=(int)(fd*100))
                                continue;}}
                    double temp=
                        estimateTempFromLat(lat);
                    switch(wc){
                        case TREE_COVER->placeTree(
                            lvl,bx,sy,bz,lat,lon,
                            temp,surf);
                        case SHRUBLAND->placeShrub(
                            lvl,pos,bx,sy,bz,temp);
                        case GRASSLAND->placeGrassVeg(
                            lvl,pos,bx,sy,bz,temp);
                        case CROPLAND->{
                            if(currentYear<-5000)
                                placeGrassVeg(lvl,pos,
                                    bx,sy,bz,temp);
                            else placeCrop(lvl,bx,
                                sy,bz);}
                        case WETLAND->placeWetlandVeg(
                            lvl,pos,bx,sy,bz,temp);
                        case MANGROVES->placeMangrove(
                            lvl,bx,sy,bz);
                        case MOSS_LICHEN->{
                            int h=TerraDensityFunctions
                                .blockHash(bx,bz);
                            if(h%8==0){
                                pos.set(bx,sy+1,bz);
                                if(lvl.getBlockState(
                                        pos).isAir())
                                    lvl.setBlock(pos,
                                        Blocks.MOSS_CARPET
                                        .defaultBlockState(),
                                        3);}}
                        default->{}}}
        }catch(Exception e){
            LOGGER.debug("placeWCV [{},{}]: {}",
                ch.getPos().x,ch.getPos().z,
                e.getMessage());}}
    // ── BUILT_UP HISTORICAL PROGRESSION ──────────────────────
    private void placeBuiltUpProgression(
            WorldGenLevel lvl,ChunkAccess ch,
            BlockPos.MutableBlockPos pos,
            int bx,int bz,int sy,
            double lat,double lon,int currentYear,
            List<OhmStructureIndex.OhmStructure> upcoming,
            List<OsmStructureIndex.OsmStructure> visible){
        BlockState surfCheck=lvl.getBlockState(
            new BlockPos(bx,sy,bz));
        if(!isNaturalSurfaceBlock(surfCheck))return;
        int h=TerraDensityFunctions.blockHash(bx,bz);
        double temp=estimateTempFromLat(lat);
        int devOffset=(h%401)-200;
        int effectiveYear=currentYear-devOffset;

        if(effectiveYear<BUILTUP_DENSE_FOREST_END){
            pos.set(bx,sy,bz);
            BlockState cs=lvl.getBlockState(pos);
            if(cs.is(Blocks.TUFF)
                ||cs.is(Blocks.COARSE_DIRT)
                ||cs.is(Blocks.DIRT_PATH)
                ||cs.is(Blocks.DIRT))
                lvl.setBlock(pos,BUILTUP_GRASS,3);
            double fd=HistoricalStructureManager
                .getForestDensity(lat,lon,upcoming,
                    visible,currentYear);
            if(fd>0.0){
                pos.set(bx,sy+1,bz);
                if(lvl.getBlockState(pos).isAir()
                    &&isPlantable(lvl.getBlockState(
                        new BlockPos(bx,sy,bz))))
                    if(fd>=1.0
                            ||(h%100)<(int)(fd*100))
                        placeTree(lvl,bx,sy,bz,lat,
                            lon,temp,BUILTUP_GRASS);}
            return;}
        if(effectiveYear<BUILTUP_THIN_FOREST_END){
            pos.set(bx,sy,bz);
            BlockState cs=lvl.getBlockState(pos);
            if(cs.is(Blocks.TUFF)
                ||cs.is(Blocks.COARSE_DIRT)
                ||cs.is(Blocks.DIRT_PATH)
                ||cs.is(Blocks.DIRT))
                lvl.setBlock(pos,BUILTUP_GRASS,3);
            double progress=(double)(effectiveYear
                -BUILTUP_DENSE_FOREST_END)
                /(double)(BUILTUP_THIN_FOREST_END
                -BUILTUP_DENSE_FOREST_END);
            double td=(1.0-(progress*0.7))
                *HistoricalStructureManager
                .getForestDensity(lat,lon,upcoming,
                    visible,currentYear);
            pos.set(bx,sy+1,bz);
            if(lvl.getBlockState(pos).isAir()
                &&isPlantable(lvl.getBlockState(
                    new BlockPos(bx,sy,bz)))
                &&td>0.0
                &&(h%100)<(int)(td*100)){
                if(h%9==0)placeTree(lvl,bx,sy,bz,
                    lat,lon,temp,BUILTUP_GRASS);
                else if(h%4==0)lvl.setBlock(pos,
                    Blocks.GRASS.defaultBlockState(),
                    3);}
            return;}
        if(effectiveYear<BUILTUP_SPARSE_TREES_END){
            pos.set(bx,sy,bz);
            BlockState cs=lvl.getBlockState(pos);
            if(cs.is(Blocks.TUFF)
                ||cs.is(Blocks.COARSE_DIRT)
                ||cs.is(Blocks.DIRT_PATH)
                ||cs.is(Blocks.DIRT))
                lvl.setBlock(pos,BUILTUP_GRASS,3);
            double progress=(double)(effectiveYear
                -BUILTUP_THIN_FOREST_END)
                /(double)(BUILTUP_SPARSE_TREES_END
                -BUILTUP_THIN_FOREST_END);
            double td=0.30-(progress*0.25);
            pos.set(bx,sy+1,bz);
            if(lvl.getBlockState(pos).isAir()
                &&isPlantable(lvl.getBlockState(
                    new BlockPos(bx,sy,bz)))){
                if(td>0.0&&h%9==0
                        &&(h%100)<(int)(td*100))
                    placeTree(lvl,bx,sy,bz,lat,lon,
                        temp,BUILTUP_GRASS);
                else if(h%5==0)lvl.setBlock(pos,
                    Blocks.GRASS.defaultBlockState(),
                    3);
                else if(h%20==0)lvl.setBlock(pos,
                    Blocks.DANDELION
                        .defaultBlockState(),3);}
            return;}
        if(effectiveYear<BUILTUP_GRASSLAND_END){
            pos.set(bx,sy,bz);
            BlockState cs=lvl.getBlockState(pos);
            if(cs.is(Blocks.TUFF)
                ||cs.is(Blocks.COARSE_DIRT)
                ||cs.is(Blocks.DIRT_PATH)
                ||cs.is(Blocks.DIRT))
                lvl.setBlock(pos,BUILTUP_GRASS,3);
            double progress=(double)(effectiveYear
                -BUILTUP_SPARSE_TREES_END)
                /(double)(BUILTUP_GRASSLAND_END
                -BUILTUP_SPARSE_TREES_END);
            pos.set(bx,sy+1,bz);
            if(lvl.getBlockState(pos).isAir()
                &&isPlantable(lvl.getBlockState(
                    new BlockPos(bx,sy,bz)))){
                if(progress<0.7&&h%6==0)
                    lvl.setBlock(pos,
                        Blocks.GRASS
                            .defaultBlockState(),3);
                else if(progress<0.5&&h%25==0)
                    lvl.setBlock(pos,
                        Blocks.DANDELION
                            .defaultBlockState(),3);}
            return;}
        if(effectiveYear<BUILTUP_COARSE_DIRT_END){
            double progress=(double)(effectiveYear
                -BUILTUP_GRASSLAND_END)
                /(double)(BUILTUP_COARSE_DIRT_END
                -BUILTUP_GRASSLAND_END);
            BlockState tgt=
                (h%100)<(int)(progress*100)
                ?BUILTUP_COARSE:BUILTUP_GRASS;
            pos.set(bx,sy,bz);
            BlockState cs=lvl.getBlockState(pos);
            if(cs.is(Blocks.TUFF)
                ||cs.is(Blocks.DIRT_PATH)
                ||cs.is(Blocks.DIRT)
                ||cs.is(Blocks.GRASS_BLOCK)
                ||cs.is(Blocks.COARSE_DIRT))
                lvl.setBlock(pos,tgt,3);
            if(tgt==BUILTUP_GRASS&&h%12==0){
                pos.set(bx,sy+1,bz);
                if(lvl.getBlockState(pos).isAir())
                    lvl.setBlock(pos,
                        Blocks.GRASS
                            .defaultBlockState(),3);}
            return;}
        if(effectiveYear<BUILTUP_DIRT_PATH_END){
            double progress=(double)(effectiveYear
                -BUILTUP_COARSE_DIRT_END)
                /(double)(BUILTUP_DIRT_PATH_END
                -BUILTUP_COARSE_DIRT_END);
            BlockState tgt=
                (h%100)<(int)(progress*100)
                ?BUILTUP_DPATH:BUILTUP_COARSE;
            pos.set(bx,sy,bz);
            BlockState cs=lvl.getBlockState(pos);
            if(cs.is(Blocks.TUFF)
                ||cs.is(Blocks.DIRT_PATH)
                ||cs.is(Blocks.DIRT)
                ||cs.is(Blocks.GRASS_BLOCK)
                ||cs.is(Blocks.COARSE_DIRT))
                lvl.setBlock(pos,tgt,3);
            return;}
        if(effectiveYear<BUILTUP_TUFF_START){
            double progress=(double)(effectiveYear
                -BUILTUP_DIRT_PATH_END)
                /(double)(BUILTUP_TUFF_START
                -BUILTUP_DIRT_PATH_END);
            BlockState tgt=
                (h%100)<(int)(progress*100)
                ?BUILTUP_TUFF:BUILTUP_DPATH;
            pos.set(bx,sy,bz);
            BlockState cs=lvl.getBlockState(pos);
            if(!cs.isAir()&&!cs.is(Blocks.WATER))
                lvl.setBlock(pos,tgt,3);
            return;}
        // ERA 8: MODERN 1900+
        pos.set(bx,sy,bz);
        BlockState cs=lvl.getBlockState(pos);
        if(cs.is(Blocks.GRASS_BLOCK)
            ||cs.is(Blocks.DIRT)
            ||cs.is(Blocks.COARSE_DIRT)
            ||cs.is(Blocks.DIRT_PATH)
            ||cs.is(Blocks.SAND)
            ||cs.is(Blocks.GRAVEL))
            lvl.setBlock(pos,BUILTUP_TUFF,3);
        pos.set(bx,sy+1,bz);
        BlockState above=lvl.getBlockState(pos);
        if(above.is(Blocks.GRASS)
            ||above.is(Blocks.TALL_GRASS)
            ||above.is(Blocks.FERN)
            ||above.is(Blocks.DANDELION)
            ||above.is(Blocks.POPPY))
            lvl.setBlock(pos,AIR,3);}

    // ── PLANTABLE / TEMP / SEASIA ─────────────────────────────
    private static boolean isPlantable(BlockState s){
        return s.is(Blocks.GRASS_BLOCK)
            ||s.is(Blocks.DIRT)
            ||s.is(Blocks.COARSE_DIRT)
            ||s.is(Blocks.PODZOL)
            ||s.is(Blocks.MUD)
            ||s.is(Blocks.MUDDY_MANGROVE_ROOTS)
            ||s.is(Blocks.SAND)
            ||s.is(Blocks.RED_SAND);}
    private static double estimateTempFromLat(
            double lat){
        double a=Math.abs(lat);
        if(a<10)return 27;if(a<20)return 25;
        if(a<30)return 20;if(a<40)return 14;
        if(a<50)return 8;if(a<60)return 2;
        if(a<70)return -5;return -15;}
    private static boolean isSEAsiaBamboo(
            double lat,double lon){
        return lon>=95.0&&lon<=140.0
            &&lat>=-10.0&&lat<=25.0;}

    // ── TREE PLACEMENT ────────────────────────────────────────
    private void placeTree(WorldGenLevel lvl,int bx,
            int sy,int bz,double lat,double lon,
            double temp,BlockState surf){
        int h=TerraDensityFunctions.blockHash(bx,bz);
        if(h%9!=0)return;
        if((surf.is(Blocks.SAND)
                ||surf.is(Blocks.RED_SAND))
            &&h%25!=0)return;
        if(sy+1>=XY-15)return;
        if(!lvl.getBlockState(
                new BlockPos(bx,sy+1,bz)).isAir())
            return;
        if(isSEAsiaBamboo(lat,lon)&&temp>=22.0)
            placeBamboo(lvl,bx,sy,bz,h);
        else if(temp<0.0)
            placeSpruceTree(lvl,bx,sy,bz,h);
        else if(temp<5.0){
            if(h%2==0)placeSpruceTree(lvl,bx,sy,bz,h);
            else placePineTree(lvl,bx,sy,bz,h);}
        else if(temp<10.0){
            if(h%3==0)placeBirchTree(lvl,bx,sy,bz,h);
            else placeOakTree(lvl,bx,sy,bz,h);}
        else if(temp<15.0){int v=h%4;
            if(v==0)placeDarkOakTree(lvl,bx,sy,bz,h);
            else if(v==1)
                placeBirchTree(lvl,bx,sy,bz,h);
            else placeOakTree(lvl,bx,sy,bz,h);}
        else if(temp<22.0){
            if(h%3==0)
                placeAcaciaTree(lvl,bx,sy,bz,h);
            else placeOakTree(lvl,bx,sy,bz,h);}
        else{
            if(h%5==0)placeLargeJungleTree(
                lvl,bx,sy,bz,h);
            else placeSmallJungleTree(
                lvl,bx,sy,bz,h);}}
    private void placeOakTree(WorldGenLevel lvl,
            int x,int sy,int z,int h){
        int ht=6+(h%3);
        for(int y=1;y<=ht;y++)placeLog(lvl,x,sy+y,z,
            Blocks.OAK_LOG.defaultBlockState());
        int ls=sy+ht-1;
        placeLeafLayer(lvl,x,ls+2,z,
            Blocks.OAK_LEAVES,2);
        placeLeafLayer(lvl,x,ls+1,z,
            Blocks.OAK_LEAVES,2);
        placeLeafLayer(lvl,x,ls,z,
            Blocks.OAK_LEAVES,3);
        placeLeafLayer(lvl,x,ls-1,z,
            Blocks.OAK_LEAVES,3);}
    private void placeBirchTree(WorldGenLevel lvl,
            int x,int sy,int z,int h){
        int ht=5+(h%3);
        for(int y=1;y<=ht;y++)placeLog(lvl,x,sy+y,z,
            Blocks.BIRCH_LOG.defaultBlockState());
        int ls=sy+ht-1;
        placeLeafLayer(lvl,x,ls+2,z,
            Blocks.BIRCH_LEAVES,1);
        placeLeafLayer(lvl,x,ls+1,z,
            Blocks.BIRCH_LEAVES,2);
        placeLeafLayer(lvl,x,ls,z,
            Blocks.BIRCH_LEAVES,2);
        placeLeafLayer(lvl,x,ls-1,z,
            Blocks.BIRCH_LEAVES,2);}
    private void placeSpruceTree(WorldGenLevel lvl,
            int x,int sy,int z,int h){
        int ht=6+(h%5);
        for(int y=1;y<=ht;y++)placeLog(lvl,x,sy+y,z,
            Blocks.SPRUCE_LOG.defaultBlockState());
        for(int y=ht+1;y>=ht-4;y--){
            int dist=ht+1-y;
            placeLeafLayer(lvl,x,sy+y,z,
                Blocks.SPRUCE_LEAVES,
                Math.min(dist,3));}}
    private void placePineTree(WorldGenLevel lvl,
            int x,int sy,int z,int h){
        int ht=8+(h%4);
        for(int y=1;y<=ht;y++)placeLog(lvl,x,sy+y,z,
            Blocks.SPRUCE_LOG.defaultBlockState());
        for(int y=ht+1;y>=ht-3;y--){
            int dist=ht+1-y;
            placeLeafLayer(lvl,x,sy+y,z,
                Blocks.SPRUCE_LEAVES,
                Math.min(dist,2));}
        int lb=sy+ht/2;
        for(int y=lb;y<sy+ht-3;y+=2)
            placeLeafLayer(lvl,x,y,z,
                Blocks.SPRUCE_LEAVES,1);}
    private void placeDarkOakTree(WorldGenLevel lvl,
            int x,int sy,int z,int h){
        int ht=6+(h%2);
        for(int y=1;y<=ht;y++){
            placeLog(lvl,x,sy+y,z,
                Blocks.DARK_OAK_LOG
                    .defaultBlockState());
            placeLog(lvl,x+1,sy+y,z,
                Blocks.DARK_OAK_LOG
                    .defaultBlockState());
            placeLog(lvl,x,sy+y,z+1,
                Blocks.DARK_OAK_LOG
                    .defaultBlockState());
            placeLog(lvl,x+1,sy+y,z+1,
                Blocks.DARK_OAK_LOG
                    .defaultBlockState());}
        int ly=sy+ht;
        placeLeafLayer(lvl,x,ly+2,z,
            Blocks.DARK_OAK_LEAVES,2);
        placeLeafLayer(lvl,x,ly+1,z,
            Blocks.DARK_OAK_LEAVES,3);
        placeLeafLayer(lvl,x,ly,z,
            Blocks.DARK_OAK_LEAVES,3);
        placeLeafLayer(lvl,x,ly-1,z,
            Blocks.DARK_OAK_LEAVES,2);}
    private void placeAcaciaTree(WorldGenLevel lvl,
            int x,int sy,int z,int h){
        int ht=5+(h%3);
        for(int y=1;y<ht;y++)placeLog(lvl,x,sy+y,z,
            Blocks.ACACIA_LOG.defaultBlockState());
        int bd=h%4;
        int bx2=x+(bd<2?1:-1);
        int bz2=z+(bd%2==0?1:-1);
        placeLog(lvl,bx2,sy+ht,bz2,
            Blocks.ACACIA_LOG.defaultBlockState());
        int ly=sy+ht;
        placeLeafLayer(lvl,bx2,ly+1,bz2,
            Blocks.ACACIA_LEAVES,2);
        placeLeafLayer(lvl,bx2,ly,bz2,
            Blocks.ACACIA_LEAVES,3);
        int bx3=x+(bd<2?-1:1);
        placeLog(lvl,bx3,sy+ht-1,z,
            Blocks.ACACIA_LOG.defaultBlockState());
        placeLeafLayer(lvl,bx3,ly,z,
            Blocks.ACACIA_LEAVES,2);
        placeLeafLayer(lvl,bx3,ly-1,z,
            Blocks.ACACIA_LEAVES,2);}
    private void placeSmallJungleTree(
            WorldGenLevel lvl,int x,int sy,
            int z,int h){
        int ht=6+(h%3);
        for(int y=1;y<=ht;y++)placeLog(lvl,x,sy+y,z,
            Blocks.JUNGLE_LOG.defaultBlockState());
        int ly=sy+ht;
        placeLeafLayer(lvl,x,ly+1,z,
            Blocks.JUNGLE_LEAVES,1);
        placeLeafLayer(lvl,x,ly,z,
            Blocks.JUNGLE_LEAVES,2);
        placeLeafLayer(lvl,x,ly-1,z,
            Blocks.JUNGLE_LEAVES,2);}
    private void placeLargeJungleTree(
            WorldGenLevel lvl,int x,int sy,
            int z,int h){
        int ht=12+(h%9);
        for(int y=1;y<=ht;y++){
            placeLog(lvl,x,sy+y,z,
                Blocks.JUNGLE_LOG.defaultBlockState());
            placeLog(lvl,x+1,sy+y,z,
                Blocks.JUNGLE_LOG.defaultBlockState());
            placeLog(lvl,x,sy+y,z+1,
                Blocks.JUNGLE_LOG.defaultBlockState());
            placeLog(lvl,x+1,sy+y,z+1,
                Blocks.JUNGLE_LOG.defaultBlockState());}
        int ly=sy+ht;
        placeLeafLayer(lvl,x,ly+2,z,
            Blocks.JUNGLE_LEAVES,2);
        placeLeafLayer(lvl,x,ly+1,z,
            Blocks.JUNGLE_LEAVES,4);
        placeLeafLayer(lvl,x,ly,z,
            Blocks.JUNGLE_LEAVES,4);
        placeLeafLayer(lvl,x,ly-1,z,
            Blocks.JUNGLE_LEAVES,4);
        placeLeafLayer(lvl,x,ly-2,z,
            Blocks.JUNGLE_LEAVES,3);
        placeVines(lvl,x,ly,z,4,h);}
    private void placeBamboo(WorldGenLevel lvl,
            int x,int sy,int z,int h){
        if(h%4!=0)return;int ht=8+(h%8);
        BlockPos.MutableBlockPos p=
            new BlockPos.MutableBlockPos();
        int cs=2+(h%3);
        for(int i=0;i<cs;i++){
            int ox=((h>>(i*4))&3)-1;
            int oz=((h>>(i*4+2))&3)-1;
            int bh=Math.max(3,ht-(i*2));
            for(int y=1;y<=bh;y++){
                p.set(x+ox,sy+y,z+oz);
                if(lvl.getBlockState(p).isAir())
                    lvl.setBlock(p,
                        Blocks.BAMBOO
                            .defaultBlockState(),3);}}}
    private void placeShrub(WorldGenLevel lvl,
            BlockPos.MutableBlockPos pos,int bx,int sy,
            int bz,double temp){
        int h=TerraDensityFunctions.blockHash(bx,bz);
        if(h%6!=0)return;
        pos.set(bx,sy+1,bz);
        if(!lvl.getBlockState(pos).isAir())return;
        if(temp>=15.0)lvl.setBlock(pos,
            Blocks.DEAD_BUSH.defaultBlockState(),3);
        else if(temp>=5.0){
            if(h%3==0){
                placeLog(lvl,bx,sy+1,bz,
                    Blocks.OAK_LOG.defaultBlockState());
                placeLeafLayer(lvl,bx,sy+2,bz,
                    Blocks.OAK_LEAVES,1);}
            else lvl.setBlock(pos,
                Blocks.GRASS.defaultBlockState(),3);}
        else{
            if(h%4==0){
                placeLog(lvl,bx,sy+1,bz,
                    Blocks.SPRUCE_LOG
                        .defaultBlockState());
                placeLeafLayer(lvl,bx,sy+2,bz,
                    Blocks.SPRUCE_LEAVES,1);}
            else lvl.setBlock(pos,
                Blocks.LARGE_FERN
                    .defaultBlockState(),3);}}
    private void placeGrassVeg(WorldGenLevel lvl,
            BlockPos.MutableBlockPos pos,int bx,int sy,
            int bz,double temp){
        int h=TerraDensityFunctions.blockHash(bx,bz);
        if(h%3!=0)return;
        pos.set(bx,sy+1,bz);
        if(!lvl.getBlockState(pos).isAir())return;
        if(temp<0.0){
            if(h%8==0)lvl.setBlock(pos,
                Blocks.FERN.defaultBlockState(),3);}
        else if(temp<10.0){int v=h%8;
            if(v==0)lvl.setBlock(pos,
                Blocks.DANDELION.defaultBlockState(),3);
            else if(v==1)lvl.setBlock(pos,
                Blocks.POPPY.defaultBlockState(),3);
            else if(v==2)lvl.setBlock(pos,
                Blocks.CORNFLOWER.defaultBlockState(),3);
            else if(v==3)lvl.setBlock(pos,
                Blocks.AZURE_BLUET.defaultBlockState(),3);
            else if(v==4){
                lvl.setBlock(pos,Blocks.TALL_GRASS
                    .defaultBlockState().setValue(
                        DoublePlantBlock.HALF,
                        DoubleBlockHalf.LOWER),3);
                pos.set(bx,sy+2,bz);
                if(lvl.getBlockState(pos).isAir())
                    lvl.setBlock(pos,
                        Blocks.TALL_GRASS
                        .defaultBlockState().setValue(
                            DoublePlantBlock.HALF,
                            DoubleBlockHalf.UPPER),3);}
            else lvl.setBlock(pos,
                Blocks.GRASS.defaultBlockState(),3);}
        else if(temp<22.0){
            if(h%6==0)lvl.setBlock(pos,
                Blocks.GRASS.defaultBlockState(),3);
            else if(h%12==0)lvl.setBlock(pos,
                Blocks.DEAD_BUSH.defaultBlockState(),3);}
        else{if(h%5==0)lvl.setBlock(pos,
            Blocks.GRASS.defaultBlockState(),3);}}
    private void placeCrop(WorldGenLevel lvl,int bx,
            int sy,int bz){
        int h=TerraDensityFunctions.blockHash(bx,bz);
        if(h%4!=0)return;
        BlockPos above=new BlockPos(bx,sy+1,bz);
        BlockPos surfPos=new BlockPos(bx,sy,bz);
        if(!lvl.getBlockState(above).isAir())return;
        if(lvl.getBlockState(surfPos)
                .is(Blocks.GRASS_BLOCK)){
            lvl.setBlock(surfPos,
                Blocks.FARMLAND.defaultBlockState(),3);
            lvl.setBlock(above,Blocks.WHEAT
                .defaultBlockState().setValue(
                    net.minecraft.world.level.block
                        .CropBlock.AGE,h%8),3);}}
    private void placeWetlandVeg(WorldGenLevel lvl,
            BlockPos.MutableBlockPos pos,int bx,int sy,
            int bz,double temp){
        int h=TerraDensityFunctions.blockHash(bx,bz);
        if(h%5!=0)return;
        pos.set(bx,sy+1,bz);
        if(!lvl.getBlockState(pos).isAir())return;
        if(temp>=15.0){
            if(h%3==0){int ht=1+(h%3);
                for(int y=1;y<=ht;y++){
                    pos.set(bx,sy+y,bz);
                    if(lvl.getBlockState(pos).isAir())
                        lvl.setBlock(pos,
                            Blocks.SUGAR_CANE
                                .defaultBlockState(),
                            3);}}
            else lvl.setBlock(pos,
                Blocks.GRASS.defaultBlockState(),3);}
        else{
            if(h%3==0)lvl.setBlock(pos,
                Blocks.FERN.defaultBlockState(),3);
            else if(h%3==1){
                lvl.setBlock(pos,Blocks.TALL_GRASS
                    .defaultBlockState().setValue(
                        DoublePlantBlock.HALF,
                        DoubleBlockHalf.LOWER),3);
                pos.set(bx,sy+2,bz);
                if(lvl.getBlockState(pos).isAir())
                    lvl.setBlock(pos,
                        Blocks.TALL_GRASS
                        .defaultBlockState().setValue(
                            DoublePlantBlock.HALF,
                            DoubleBlockHalf.UPPER),3);}
            else lvl.setBlock(pos,
                Blocks.GRASS.defaultBlockState(),3);}}
    private void placeMangrove(WorldGenLevel lvl,
            int bx,int sy,int bz){
        int h=TerraDensityFunctions.blockHash(bx,bz);
        if(h%7!=0)return;
        if(!lvl.getBlockState(
                new BlockPos(bx,sy+1,bz)).isAir())
            return;
        int ht=4+(h%3);
        for(int y=1;y<=ht;y++)placeLog(lvl,bx,sy+y,bz,
            Blocks.MANGROVE_LOG.defaultBlockState());
        placeLeafLayer(lvl,bx,sy+ht+1,bz,
            Blocks.MANGROVE_LEAVES,2);
        placeLeafLayer(lvl,bx,sy+ht,bz,
            Blocks.MANGROVE_LEAVES,3);
        placeLeafLayer(lvl,bx,sy+ht-1,bz,
            Blocks.MANGROVE_LEAVES,2);}
    private void placeLeafLayer(WorldGenLevel lvl,
            int cx,int y,int cz,
            net.minecraft.world.level.block.Block lb,
            int radius){
        if(y>=XY||y<=MY)return;
        BlockPos.MutableBlockPos p=
            new BlockPos.MutableBlockPos();
        BlockState ls=lb.defaultBlockState();
        for(int dx=-radius;dx<=radius;dx++)
            for(int dz=-radius;dz<=radius;dz++){
                if(Math.abs(dx)+Math.abs(dz)
                    >radius+(radius>1?1:0))continue;
                p.set(cx+dx,y,cz+dz);
                BlockState ex=lvl.getBlockState(p);
                if(ex.isAir()||(ex.getBlock()
                        instanceof LeavesBlock))
                    lvl.setBlock(p,ls,3);}}
    private void placeLog(WorldGenLevel lvl,int x,
            int y,int z,BlockState log){
        if(y>=XY||y<=MY)return;
        BlockPos p=new BlockPos(x,y,z);
        if(lvl.getBlockState(p).isAir())
            lvl.setBlock(p,log,3);}
    private void placeVines(WorldGenLevel lvl,int cx,
            int leafY,int cz,int radius,int h){
        BlockPos.MutableBlockPos p=
            new BlockPos.MutableBlockPos();
        for(int dx=-radius;dx<=radius;dx++)
            for(int dz=-radius;dz<=radius;dz++){
                if(Math.abs(dx)+Math.abs(dz)!=radius)
                    continue;
                int vh=TerraDensityFunctions.blockHash(
                    cx+dx,cz+dz);
                if(vh%3!=0)continue;
                int vl=1+(vh%4);
                for(int vy=0;vy<vl;vy++){
                    p.set(cx+dx,leafY-vy,cz+dz);
                    if(lvl.getBlockState(p).isAir())
                        lvl.setBlock(p,
                            Blocks.VINE
                                .defaultBlockState(),
                            3);}}}

    // ── ARTIFACT CLEANUP ──────────────────────────────────────
    private void cleanArtifacts(ChunkAccess ch){
        if(getChunkPresence(ch)>=PRESENCE_DECORATED)
            return;
        BlockPos.MutableBlockPos p=
            new BlockPos.MutableBlockPos();
        int cx=ch.getPos().getMinBlockX();
        int cz=ch.getPos().getMinBlockZ();
        int mnB=ch.getMinBuildHeight();
        int mxB=ch.getMaxBuildHeight()-1;
        for(int lx=0;lx<16;lx++)
            for(int lz=0;lz<16;lz++){
                int bx=cx+lx,bz=cz+lz,sy;
                try{sy=ch.getHeight(
                    Heightmap.Types.WORLD_SURFACE,
                    lx,lz);
                }catch(Exception e){continue;}
                boolean oc=false;
                for(int y=sy;y<=SL;y++){
                    p.set(bx,y,bz);
                    if(ch.getBlockState(p)
                            .is(Blocks.WATER)){
                        oc=true;break;}}
                if(oc&&sy<=SL-10)continue;
                for(int y=Math.max(mnB,sy-15);
                        y<=Math.min(mxB,sy+20);y++){
                    p.set(bx,y,bz);
                    BlockState s=ch.getBlockState(p);
                    if(s.isAir()||isSafe(s))continue;
                    if(oc){ch.setBlockState(p,
                        y<=sy?STONE:y<=SL?WATER:AIR,
                        false);continue;}
                    if(isLava(s)||isPortal(s)
                            ||isGeode(s)){
                        ch.setBlockState(p,
                            fClean(y,sy),false);
                        continue;}
                    if(s.is(Blocks.DIRT)&&y>=sy){
                        p.set(bx,y+1,bz);
                        if(ch.getBlockState(p).isAir()){
                            p.set(bx,y,bz);
                            ch.setBlockState(p,GRASS,
                                false);}}}}}

    private boolean isSafe(BlockState s){
        return s.is(Blocks.STONE)
            ||s.is(Blocks.DEEPSLATE)
            ||s.is(Blocks.BEDROCK)
            ||s.is(Blocks.DIRT)
            ||s.is(Blocks.GRASS_BLOCK)
            ||s.is(Blocks.GRAVEL)
            ||s.is(Blocks.SAND)
            ||s.is(Blocks.SANDSTONE)
            ||s.is(Blocks.RED_SAND)
            ||s.is(Blocks.RED_SANDSTONE)
            ||s.is(Blocks.CLAY)
            ||s.is(Blocks.SNOW)
            ||s.is(Blocks.SNOW_BLOCK)
            ||s.is(Blocks.PACKED_ICE)
            ||s.is(Blocks.ICE)
            ||s.is(Blocks.COARSE_DIRT)
            ||s.is(Blocks.DIRT_PATH)
            ||s.is(Blocks.WATER)
            ||s.is(Blocks.SEAGRASS)
            ||s.is(Blocks.TALL_SEAGRASS)
            ||s.is(Blocks.KELP)
            ||s.is(Blocks.KELP_PLANT)
            ||s.is(Blocks.TERRACOTTA)
            ||s.is(Blocks.WHITE_TERRACOTTA)
            ||s.is(Blocks.ORANGE_TERRACOTTA)
            ||s.is(Blocks.RED_TERRACOTTA)
            ||s.is(Blocks.YELLOW_TERRACOTTA)
            ||s.is(Blocks.BROWN_TERRACOTTA)
            ||s.is(Blocks.LIGHT_GRAY_TERRACOTTA)
            ||s.is(Blocks.CYAN_TERRACOTTA)
            ||s.is(Blocks.BRICKS)
            ||s.is(Blocks.STONE_BRICKS)
            ||s.is(Blocks.COBBLESTONE)
            ||s.is(Blocks.SMOOTH_STONE)
            ||s.is(Blocks.POLISHED_ANDESITE)
            ||s.is(Blocks.POLISHED_DIORITE)
            ||s.is(Blocks.WHITE_CONCRETE)
            ||s.is(Blocks.LIGHT_GRAY_CONCRETE)
            ||s.is(Blocks.GRAY_CONCRETE)
            ||s.is(Blocks.BLACK_CONCRETE)
            ||s.is(Blocks.BROWN_CONCRETE)
            ||s.is(Blocks.CYAN_CONCRETE)
            ||s.is(Blocks.YELLOW_CONCRETE)
            ||s.is(Blocks.RED_CONCRETE)
            ||s.is(Blocks.ORANGE_CONCRETE)
            ||s.is(Blocks.LIME_CONCRETE)
            ||s.is(Blocks.OAK_PLANKS)
            ||s.is(Blocks.SPRUCE_PLANKS)
            ||s.is(Blocks.BIRCH_PLANKS)
            ||s.is(Blocks.DARK_OAK_PLANKS)
            ||s.is(Blocks.OAK_LOG)
            ||s.is(Blocks.STRIPPED_OAK_LOG)
            ||s.is(Blocks.QUARTZ_BLOCK)
            ||s.is(Blocks.SMOOTH_QUARTZ)
            ||s.is(Blocks.QUARTZ_PILLAR)
            ||s.is(Blocks.GLASS_PANE)
            ||s.is(Blocks.LIGHT_BLUE_STAINED_GLASS)
            ||s.is(Blocks.OAK_DOOR)
            ||s.is(Blocks.IRON_DOOR)
            ||s.is(Blocks.SPRUCE_DOOR)
            ||s.is(Blocks.RAIL)
            ||s.is(Blocks.IRON_BARS)
            ||s.is(Blocks.SEA_LANTERN)
            ||s.is(Blocks.GLOWSTONE)
            ||s.is(Blocks.LANTERN)
            ||s.is(Blocks.STONE_BRICK_WALL)
            ||s.is(Blocks.COBBLESTONE_WALL)
            ||s.is(Blocks.OAK_FENCE)
            ||s.is(Blocks.GRASS)
            ||s.is(Blocks.DEAD_BUSH)
            ||s.is(Blocks.CACTUS)
            ||s.is(Blocks.FARMLAND)
            ||s.is(Blocks.WHEAT)
            ||s.is(Blocks.OAK_LEAVES)
            ||s.is(Blocks.BIRCH_LEAVES)
            ||s.is(Blocks.SPRUCE_LEAVES)
            ||s.is(Blocks.DANDELION)
            ||s.is(Blocks.POPPY)
            ||s.is(Blocks.CORNFLOWER)
            ||s.is(Blocks.ALLIUM)
            ||s.is(Blocks.AZURE_BLUET);}

    private BlockState fClean(int y,int sy){
        return y>sy?AIR:y==sy?GRASS:y>=sy-3?DIRT
            :y<=0?DEEPSLATE:STONE;}
    private boolean isLava(BlockState s){
        return s.is(Blocks.LAVA)
            ||s.is(Blocks.OBSIDIAN)
            ||s.is(Blocks.MAGMA_BLOCK);}
    private boolean isPortal(BlockState s){
        return s.is(Blocks.CRYING_OBSIDIAN)
            ||s.is(Blocks.NETHERRACK)
            ||s.is(Blocks.NETHER_BRICKS)
            ||s.is(Blocks.NETHER_PORTAL);}
    private boolean isGeode(BlockState s){
        return s.is(Blocks.AMETHYST_BLOCK)
            ||s.is(Blocks.BUDDING_AMETHYST)
            ||s.is(Blocks.CALCITE)
            ||s.is(Blocks.SMOOTH_BASALT)
            ||s.is(Blocks.DRIPSTONE_BLOCK)
            ||s.is(Blocks.POINTED_DRIPSTONE);}

    // ── BIOME ASSIGNMENT ──────────────────────────────────────
    private void assignBiomes(ChunkAccess ch){
        try{
            var bp=getTerraBiomes();
            if(bp==null)return;
            var d=getDf();
            if(d==null||!d.isProjectionLoaded())return;
            var esa=getWorldCover();
            int cmx=ch.getPos().getMinBlockX();
            int cmz=ch.getPos().getMinBlockZ();
            boolean dbg=biomeDbg.getAndIncrement()<3;
            for(int si=0;si<ch.getSectionsCount();si++){
                var sec=ch.getSection(si);
                var bc=getWB(sec);if(bc==null)continue;
                for(int bx=0;bx<4;bx++)
                    for(int bz=0;bz<4;bz++){
                        int blockX=cmx+bx*4;
                        int blockZ=cmz+bz*4;
                        var col=d.classify(blockX,blockZ);
                        if(col.type==TerraDensityFunctions
                                .TerrainType.INVALID)
                            continue;
                        double la=col.latitude;
                        double lo=col.longitude;
                        double el=col.elevationMeters;
                        if(Double.isNaN(la)
                                ||Double.isNaN(lo))
                            continue;
                        var wcClass=
                            esa.getClass(la,lo);
                        Holder<Biome> bi=
                            bp.getBiomeForLocation(
                                la,lo,el,col.type,
                                wcClass);
                        if(bi==null)continue;
                        for(int by=0;by<4;by++)
                            bc.set(bx,by,bz,bi);
                        if(dbg&&bx==2&&bz==2
                            &&si==ch.getSectionsCount()
                                /2)
                            LOGGER.info(
                                "BIOME [{},{}] {},{}"
                                +" {}m {} wc={} -> {}",
                                ch.getPos().x,
                                ch.getPos().z,
                                String.format(
                                    "%.4f",la),
                                String.format(
                                    "%.4f",lo),
                                String.format(
                                    "%.1f",el),
                                col.type,wcClass,bi);}}
        }catch(Exception e){
            LOGGER.debug("assignBiomes: {}",
                e.getMessage());}}

    @SuppressWarnings("unchecked")
    private static PalettedContainer<Holder<Biome>>
            getWB(LevelChunkSection s){
        try{
            var ro=s.getBiomes();
            if(ro instanceof PalettedContainer)
                return(PalettedContainer<
                    Holder<Biome>>)ro;
            for(var f:LevelChunkSection.class
                    .getDeclaredFields())
                if(PalettedContainer.class
                        .isAssignableFrom(f.getType())
                    ||PalettedContainerRO.class
                        .isAssignableFrom(f.getType())){
                    f.setAccessible(true);
                    Object v=f.get(s);
                    if(v instanceof PalettedContainer){
                        var pc=(PalettedContainer<?>)v;
                        try{Object tv=pc.get(0,0,0);
                            if(tv instanceof Holder
                                &&((Holder<?>)tv).value()
                                instanceof Biome)
                                return(PalettedContainer<
                                    Holder<Biome>>)v;
                        }catch(Exception ig){}}}
        }catch(Exception ig){}return null;}

    // ── NOISE ─────────────────────────────────────────────────
    private static double hg(int x,int z,int s){
        int h=(x*73856093)^(z*19349663)^(s*83492791);
        h=((h>>16)^h)*0x45d9f3b;
        h=((h>>16)^h)*0x45d9f3b;
        h=(h>>16)^h;
        return(double)(h&0xFFFF)/32768.0-1.0;}
    private static double sn(double x,double z,int s){
        int ix=(int)Math.floor(x);
        int iz=(int)Math.floor(z);
        double fx=x-ix,fz=z-iz;
        fx=fx*fx*fx*(fx*(fx*6-15)+10);
        fz=fz*fz*fz*(fz*(fz*6-15)+10);
        return(hg(ix,iz,s)+(hg(ix+1,iz,s)
            -hg(ix,iz,s))*fx)
            +((hg(ix,iz+1,s)+(hg(ix+1,iz+1,s)
            -hg(ix,iz+1,s))*fx)
            -(hg(ix,iz,s)+(hg(ix+1,iz,s)
            -hg(ix,iz,s))*fx))*fz;}
    private static double fbm(int x,int z,int o,
            double sc,int s){
        double sm=0,a=1,mx=0,f=1.0/sc;
        for(int i=0;i<o;i++){
            sm+=sn(x*f,z*f,s+i*7919)*a;
            mx+=a;a*=0.5;f*=2;}
        return sm/mx;}
    private static double rn(int x,int z,int o,
            double sc,int s){
        double sm=0,a=1,mx=0,f=1.0/sc;
        for(int i=0;i<o;i++){
            double n=1-Math.abs(
                sn(x*f,z*f,s+i*7919));
            n*=n;sm+=n*a;mx+=a;a*=0.5;f*=2;}
        return sm/mx;}
    private static int amp(double d){
        return d<50?0:d<200?25:d<1000?80:d<2000?150
            :d<4000?35:d<6000?20:10;}
    private static int ofn(int x,int z,double dp){
        if(dp<50)return 0;
        double fd=dp<=200?(dp-50)/150.0:1.0;
        double ns=fbm(x,z,5,256,1000)*0.4
            +fbm(x,z,6,64,2000)*0.35
            +fbm(x,z,4,16,3000)*0.25;
        if(dp>200&&dp<4000){
            double r=rn(x,z,5,400,7000);
            double b=dp<500?0.15:dp<2000?0.30:0.20;
            ns=ns*(1-b)+r*b;}
        int rs=(int)Math.round(ns*amp(dp)*fd);
        if(dp>500&&dp<4000&&fd>=1){
            double sv=fbm(x,z,3,300,5000);
            if(sv>0.5){double pv=(sv-0.5)/0.5;
                rs+=(int)(pv*pv*(dp<2000?400:60));}}
        if(dp>100&&dp<2000&&fd>0.5){
            double c=fbm(x,z,4,200,6000);
            if(c<-0.45){double ct=(-0.45-c)/0.55;
                rs-=(int)(ct*ct*ct
                    *(dp<200?15:dp<1000?80:50)*fd);}}
        return rs;}
    private static boolean isAnc(
            TerraDensityFunctions.TerrainType t){
        return t==TerraDensityFunctions.TerrainType.LAND
            ||t==TerraDensityFunctions
                .TerrainType.COASTLINE
            ||t==TerraDensityFunctions.TerrainType.RIVER
            ||t==TerraDensityFunctions.TerrainType.LAKE
            ||t==TerraDensityFunctions
                .TerrainType.BELOW_SEA_WATER;}

    // ── SURFACE BLOCK TYPE ────────────────────────────────────
    private int gbt(int bx,int bz){
        var bp=getTerraBiomes();if(bp==null)return 0;
        var d=getDf();
        if(d==null||!d.isProjectionLoaded())return 0;
        var esa=getWorldCover();
        try{
            double[] g=d.toGeo(bx,bz);
            if(g==null)return 0;
            double lo=g[0],la=g[1];
            if(Double.isNaN(la)||Double.isNaN(lo))
                return 0;
            double el;
            try{el=d.getElevationFast(la,lo);}
            catch(Exception e){el=0;}
            var wcClass=esa.getClass(la,lo);
            int t=bp.getBiomeTypeCode(
                la,lo,el,wcClass);
            if(t==10)return 0;
            if(surfDbg.getAndIncrement()<10)
                LOGGER.info(
                    "SURFACE [{},{}] {},{} elev={}m "
                    +"wc={} -> {}",bx,bz,
                    String.format("%.4f",la),
                    String.format("%.4f",lo),
                    String.format("%.1f",el),wcClass,t);
            return t;
        }catch(Exception e){return 0;}}

    private BlockState getSurfaceBlock(int bt,
            double el,double rg){
        return switch(bt){
            case 1->TerraDensityFunctions
                .getLandSurfaceBlock(el,1,rg);
            case 2->TerraDensityFunctions
                .getLandSurfaceBlock(el,2,rg);
            case 3->TerraDensityFunctions
                .getLandSurfaceBlock(el,3,rg);
            case 4->GRASS;
            case 5->COARSE_DIRT;
            case 6->COARSE_DIRT;
            case 7->RED_SAND;
            case 9->SAND;
            case 10->TUFF;
            case 11->Blocks.DIRT_PATH
                .defaultBlockState();
            default->TerraDensityFunctions
                .getLandSurfaceBlock(el,0,rg);};}
    // ── UNDERGROUND ───────────────────────────────────────────
    private void ugLava(ChunkAccess c,
            BlockPos.MutableBlockPos p,
            int x,int z,int sy,int mb){
        p.set(x,mb,z);
        c.setBlockState(p,BEDROCK,false);
        int lt=sy-LD,lb=lt-LT;
        for(int y=mb+1;y<=sy-4;y++){
            p.set(x,y,z);
            if(y<=lb)c.setBlockState(p,LAVA,false);
            else if(y<=lt){
                int h=TerraDensityFunctions
                    .blockHash(x+y,z);
                c.setBlockState(p,
                    h%4==0?LAVA:y<=0
                        ?DEEPSLATE:STONE,false);}
            else c.setBlockState(p,
                y<=0?DEEPSLATE:STONE,false);}}
    private void ug(ChunkAccess c,
            BlockPos.MutableBlockPos p,
            int x,int z,int sy,int mb){
        p.set(x,mb,z);
        c.setBlockState(p,BEDROCK,false);
        for(int y=mb+1;y<=Math.min(0,sy-8);y++){
            p.set(x,y,z);
            c.setBlockState(p,DEEPSLATE,false);}
        for(int y=Math.max(1,mb+1);y<=sy-4;y++){
            p.set(x,y,z);
            c.setBlockState(p,STONE,false);}}

    // ── BSW / WATER BORDER ────────────────────────────────────
    private void smoothBSW(
            TerraDensityFunctions.TerrainColumn[][] c,
            int[][] h){
        for(int ps=0;ps<BSWR;ps++){
            boolean ch2=false;
            for(int gx=1;gx<G-1;gx++)
                for(int gz=1;gz<G-1;gz++){
                    var ct=c[gx][gz].type;
                    if(ct!=TerraDensityFunctions
                        .TerrainType.LAND)continue;
                    int mn=h[gx][gz];
                    boolean nb=false;
                    for(int dx=-1;dx<=1;dx++)
                        for(int dz=-1;dz<=1;dz++){
                            if(dx==0&&dz==0)continue;
                            if(c[gx+dx][gz+dz].type==
                                TerraDensityFunctions
                                .TerrainType
                                .BELOW_SEA_WATER)
                                nb=true;
                            if(h[gx+dx][gz+dz]<mn)
                                mn=h[gx+dx][gz+dz];}
                    if(!nb&&mn>=h[gx][gz])continue;
                    int cp=mn+BSWD;
                    if(h[gx][gz]>cp){
                        h[gx][gz]=cp;ch2=true;}}
            if(!ch2)break;}}

    private void reclassWB(
            TerraDensityFunctions.TerrainColumn[][] c,
            int[][] h,int[][] os,int mb){
        var st=new TerraDensityFunctions
            .TerrainType[G][G];
        int[][] so=new int[G][G];
        for(int gx=0;gx<G;gx++)
            for(int gz=0;gz<G;gz++){
                st[gx][gz]=c[gx][gz].type;
                so[gx][gz]=os[gx][gz];}
        for(int gx=1;gx<G-1;gx++)
            for(int gz=1;gz<G-1;gz++){
                var ct=st[gx][gz];
                if(ct!=TerraDensityFunctions
                        .TerrainType.OCEAN
                    &&ct!=TerraDensityFunctions
                        .TerrainType.SHALLOW_WATER)
                    continue;
                if(c[gx][gz].elevationMeters<=WEM)
                    continue;
                int bw=Integer.MIN_VALUE;
                boolean fw=false;
                var bt=TerraDensityFunctions
                    .TerrainType.RIVER_BANK;
                for(int dx=-1;dx<=1;dx++)
                    for(int dz=-1;dz<=1;dz++){
                        if(dx==0&&dz==0)continue;
                        var nt=st[gx+dx][gz+dz];
                        if(nt==TerraDensityFunctions
                                .TerrainType.RIVER
                            ||nt==TerraDensityFunctions
                                .TerrainType.LAKE
                            ||nt==TerraDensityFunctions
                                .TerrainType
                                .BELOW_SEA_WATER){
                            fw=true;
                            int nw=so[gx+dx][gz+dz];
                            if(nw>bw)bw=nw;
                            if(nt==TerraDensityFunctions
                                    .TerrainType.LAKE
                                ||nt==
                                    TerraDensityFunctions
                                    .TerrainType
                                    .BELOW_SEA_WATER)
                                bt=TerraDensityFunctions
                                    .TerrainType
                                    .LAKE_SHORE;}}
                if(!fw||bw==Integer.MIN_VALUE)continue;
                int by=Math.max(mb+4,bw);
                c[gx][gz]=new TerraDensityFunctions
                    .TerrainColumn(bt,
                        c[gx][gz].elevationMeters,by,
                        c[gx][gz].latitude,
                        c[gx][gz].longitude,
                        TerraDensityFunctions
                            .InvalidReason.NONE,null);
                h[gx][gz]=by;os[gx][gz]=by;}
        var s2=new TerraDensityFunctions
            .TerrainType[G][G];
        for(int gx=0;gx<G;gx++)
            for(int gz=0;gz<G;gz++)
                s2[gx][gz]=c[gx][gz].type;
        for(int gx=1;gx<G-1;gx++)
            for(int gz=1;gz<G-1;gz++){
                var ct=s2[gx][gz];
                if(ct!=TerraDensityFunctions
                        .TerrainType.OCEAN
                    &&ct!=TerraDensityFunctions
                        .TerrainType.SHALLOW_WATER)
                    continue;
                if(c[gx][gz].elevationMeters<=WEM)
                    continue;
                boolean hon=false;int hs=0,hc=0;
                for(int dx=-1;dx<=1;dx++)
                    for(int dz=-1;dz<=1;dz++){
                        if(dx==0&&dz==0)continue;
                        var nt=s2[gx+dx][gz+dz];
                        if(nt==TerraDensityFunctions
                                .TerrainType.OCEAN
                            ||nt==TerraDensityFunctions
                                .TerrainType
                                .SHALLOW_WATER){
                            if(c[gx+dx][gz+dz]
                                    .elevationMeters
                                    <=WEM)hon=true;}
                        else{hs+=h[gx+dx][gz+dz];hc++;}}
                if(hon||hc==0)continue;
                int by=Math.max(mb+4,hs/hc);
                c[gx][gz]=new TerraDensityFunctions
                    .TerrainColumn(
                        TerraDensityFunctions
                            .TerrainType.LAND,
                        c[gx][gz].elevationMeters,by,
                        c[gx][gz].latitude,
                        c[gx][gz].longitude,
                        TerraDensityFunctions
                            .InvalidReason.NONE,null);
                h[gx][gz]=by;os[gx][gz]=by;}}

    private boolean[][] oceanBFS(
            TerraDensityFunctions.TerrainColumn[][] c,
            int[][] h){
        boolean[][] r=new boolean[G][G];
        boolean[][] v=new boolean[G][G];
        ArrayDeque<int[]> q=new ArrayDeque<>();
        for(int gx=0;gx<G;gx++)
            for(int gz=0;gz<G;gz++){
                if(gx!=0&&gx!=G-1
                        &&gz!=0&&gz!=G-1)continue;
                var t=c[gx][gz].type;
                if((t==TerraDensityFunctions
                        .TerrainType.OCEAN
                    ||t==TerraDensityFunctions
                        .TerrainType.SHALLOW_WATER)
                    &&c[gx][gz].elevationMeters<=WEM){
                    r[gx][gz]=true;v[gx][gz]=true;
                    q.add(new int[]{gx,gz});}}
        while(!q.isEmpty()){
            int[] cl=q.poll();
            for(int dd=0;dd<8;dd++){
                int nx=cl[0]+BDX[dd];
                int nz=cl[1]+BDZ[dd];
                if(nx<0||nx>=G||nz<0||nz>=G
                        ||v[nx][nz])continue;
                v[nx][nz]=true;
                var nt=c[nx][nz].type;
                if(nt==TerraDensityFunctions
                        .TerrainType.OCEAN
                    ||nt==TerraDensityFunctions
                        .TerrainType.SHALLOW_WATER){
                    r[nx][nz]=true;
                    q.add(new int[]{nx,nz});}}}
        return r;}

    // ── MAIN TERRAIN GENERATION ───────────────────────────────
    private void generateTerrain(ChunkAccess ch){
        int cx=ch.getPos().getMinBlockX();
        int cz=ch.getPos().getMinBlockZ();
        int mb=ch.getMinBuildHeight();
        BlockPos.MutableBlockPos p=
            new BlockPos.MutableBlockPos();
        var d=getDf();var r=getRle();
        double cLa=0,cLo=0;
        // ── IN generateTerrain() ──────────────────────────────────
// Replace the existing prefetch/waterDataAvailable block:

        boolean waterDataAvailable=false;
        try{
            double[] gc=d.toGeo(cx+8,cz+8);
            if(gc!=null&&!Double.isNaN(gc[0])
                    &&!Double.isNaN(gc[1])){
                cLa=gc[1];cLo=gc[0];
                double ce=d.getElevationFast(cLa,cLo);
                if(worldConfig.waterBodies
                        &&ce>-200.0&&ce<5000.0){
                    // Never wait for public Overpass while generating a
                    // chunk. Terrain remains provisional and AsyncWaterManager
                    // requeues it for authoritative water placement once its
                    // geographic source tile is ready.
                    getAsyncWaterManager().request(cLa,cLo,ch.getPos());
                    waterDataAvailable = r.getOhmWaterIndex().isTileCached(
                        r.getOhmWaterIndex().getTileKeyPublic(cLa,cLo))
                        || r.getOsmWaterIndex().isTileCached(
                        r.getOsmWaterIndex().getTileKeyPublic(cLa,cLo));
                    r.prefetch(cLa,cLo);}
                getWorldCover().prefetch(cLa,cLo);
                // OSM/Overture structure tiles were NOT prefetched here, so
                // every chunk discovered its tile cold in applyBiomeDecoration
                // and paid a 4 s blocking timeout. The cold-start log shows 17
                // of them = 68 s of stalled worker threads.
                //
                // Kicking the fetch off during terrain generation (stage 1)
                // means the tile is usually resident by the time decoration
                // (stage 2) needs it. Fire-and-forget: never blocks.
                try{
                    double m=STRUCT_PREFETCH_DEG;
                    getStructureIndex().prefetchTiles(
                        cLa-m,cLa+m,cLo-m,cLo+m);
                    // Microsoft is a present-day ML source. Do not even
                    // prefetch its heavy index/tile data when the historical
                    // year is definitely earlier than this region's population
                    // floor; HistoricalStructureManager will make the same
                    // eligibility decision before lower-tier downloads.
                    boolean overtureEligible = !worldConfig.timeEnabled
                        || TerraTimeManager.getCurrentYear() >=
                            StructureDateEstimator.getPopulationFloor(cLa, cLo);
                    if(worldConfig.overtureBuildings && overtureEligible){
                        var ov=getMicrosoftIndex();
                        if(ov.isEnabled())
                            ov.prefetchTiles(
                                cLa-m,cLa+m,cLo-m,cLo+m);
                    }
                }catch(Exception ignored){}
            }
        }catch(Exception ig){
            LOGGER.debug("Prefetch [{},{}]: {}",
                ch.getPos().x,ch.getPos().z,
                ig.getMessage());}

        var cols=new TerraDensityFunctions
            .TerrainColumn[G][G];
        int[][] ht=new int[G][G],osy=new int[G][G];
        for(int gx=0;gx<G;gx++)
            for(int gz=0;gz<G;gz++){
                int bx=cx-B+gx,bz=cz-B+gz;
                var bc=d.classify(bx,bz);
                cols[gx][gz]=bc;
                switch(bc.type){
                    case LAND->{
                        ht[gx][gz]=bc.surfaceY;
                        osy[gx][gz]=bc.surfaceY;}
                    case SHALLOW_WATER,OCEAN->{
                        int fl=Math.max(mb+4,
                            Math.min(SL-1,
                            bc.surfaceY+ofn(cx-B+gx,
                                cz-B+gz,Math.abs(
                                bc.elevationMeters))));
                        ht[gx][gz]=fl;
                        osy[gx][gz]=bc.surfaceY;}
                    case INVALID->{
                        ht[gx][gz]=TerraDensityFunctions
                            .getOceanFloorY(-50);
                        osy[gx][gz]=ht[gx][gz];}
                    default->{
                        ht[gx][gz]=bc.surfaceY;
                        osy[gx][gz]=bc.surfaceY;}}}

        boolean usedFallback=false;
        for(int gx=0;gx<G;gx++)
            for(int gz=0;gz<G;gz++){
                int bx=cx-B+gx,bz=cz-B+gz;
                var bc=cols[gx][gz];
                if(bc.type!=TerraDensityFunctions
                    .TerrainType.LAND)continue;
                if(bc.elevationMeters<=-50.0
                    ||bc.elevationMeters>=5000.0)
                    continue;
                if(!worldConfig.waterBodies)continue;
                boolean placedWater=false;
                try{
                    var wb=r.analyze(bx,bz,
                        bc.latitude,bc.longitude,
                        bc.elevationMeters);
                    if(wb!=null&&wb.isWater){
                        int ws=wb.waterSurfaceY>0
                            ?wb.waterSurfaceY
                            :bc.surfaceY;
                        int fl=Math.max(mb+4,
                            ws-wb.profileDepthBlocks());
                        if(fl>ws-2)fl=ws-2;
                        cols[gx][gz]=
                            d.buildWaterColumn(wb,
                                bc.elevationMeters,
                                bc.latitude,
                                bc.longitude);
                        ht[gx][gz]=fl;
                        osy[gx][gz]=ws;
                        placedWater=true;}
                }catch(Exception e){
                    LOGGER.debug(
                        "WaterAnalysis [{},{}] "
                        +"gx={} gz={}: {}",
                        ch.getPos().x,ch.getPos().z,
                        gx,gz,e.getMessage());}
                if(!placedWater){
                    usedFallback=true;
                    applyDepressionFallback(cols,ht,
                        osy,gx,gz,bc,mb);}}

        if(usedFallback)
            consolidateFallbackBasins(
                cols,ht,osy,mb);
// ── IN waterDataAvailable retry check ────────────────────
// At the end of generateTerrain(), replace:
//   if(!waterDataAvailable&&worldConfig.waterBodies)
// The block is unchanged but now waterDataAvailable
// reflects OHM loaded state, so the retry fires
// correctly when OHM tile was not ready:

        if(!waterDataAvailable
                &&worldConfig.waterBodies){
            long k=ck(ch.getPos().x,ch.getPos().z);
            waterRetryChunks.put(k,new long[]{
                System.currentTimeMillis(),
                Double.doubleToLongBits(cLa),
                Double.doubleToLongBits(cLo)});}

        boolean hoc=false;
        outer:for(int gx=0;gx<G;gx++)
            for(int gz=0;gz<G;gz++){
                var t=cols[gx][gz].type;
                if(t==TerraDensityFunctions
                        .TerrainType.OCEAN
                    ||t==TerraDensityFunctions
                        .TerrainType.SHALLOW_WATER){
                    hoc=true;break outer;}}
        if(hoc){
            boolean[][] or=oceanBFS(cols,ht);
            for(int gx=0;gx<G;gx++)
                for(int gz=0;gz<G;gz++){
                    var cl=cols[gx][gz];
                    var t=cl.type;
                    if(t!=TerraDensityFunctions
                            .TerrainType.OCEAN
                        &&t!=TerraDensityFunctions
                            .TerrainType.SHALLOW_WATER)
                        continue;
                    if(or[gx][gz])continue;
                    int dy=Math.max(mb+4,Math.min(XY,
                        TerraDensityFunctions
                        .elevMetersToSurfaceY(
                            cl.elevationMeters,true)));
                    cols[gx][gz]=
                        new TerraDensityFunctions
                        .TerrainColumn(
                            TerraDensityFunctions
                                .TerrainType.LAND,
                            cl.elevationMeters,dy,
                            cl.latitude,cl.longitude,
                            TerraDensityFunctions
                                .InvalidReason.NONE,
                            null);
                    ht[gx][gz]=dy;osy[gx][gz]=dy;}}

        reclassWB(cols,ht,osy,mb);
        smoothBSW(cols,ht);
        // Terrace both sides of every water boundary. Each horizontal block
        // changes by at most one vertical block: terrain slopes into the
        // shoreline while river, lake, and ocean floors slope away from it.
        applyFloorTransitions(cols,ht,osy,mb);
        for(int ps=0;ps<12;ps++){
            boolean chg=false;
            int s=ps%2==0?1:G-2;
            int e=ps%2==0?G-1:0;
            int st=ps%2==0?1:-1;
            for(int gx=s;gx!=e;gx+=st)
                for(int gz=s;gz!=e;gz+=st){
                    if(isAnc(cols[gx][gz].type))
                        continue;
                    int cu=ht[gx][gz],hi=cu;
                    for(int i=-1;i<=1;i++)
                        for(int j=-1;j<=1;j++)
                            if(i!=0||j!=0)
                                hi=Math.max(hi,
                                    ht[gx+i][gz+j]);
                    int ma=hi-MD;
                    if(cu<ma){
                        ht[gx][gz]=ma;chg=true;}}
            if(!chg)break;}

        boolean[][] nwf=new boolean[G][G];
        int[][] lwn=new int[G][G];
        for(int gx=1;gx<G-1;gx++)
            for(int gz=1;gz<G-1;gz++){
                var ct=cols[gx][gz].type;
                if(ct==TerraDensityFunctions
                        .TerrainType.LAND
                    ||ct==TerraDensityFunctions
                        .TerrainType.RIVER_BANK
                    ||ct==TerraDensityFunctions
                        .TerrainType.LAKE_SHORE){
                    int sy=ht[gx][gz];
                    boolean fw=false;int ln=sy;
                    for(int dx=-1;dx<=1;dx++)
                        for(int dz=-1;dz<=1;dz++){
                            if(dx==0&&dz==0)continue;
                            var nt=cols[gx+dx][gz+dz]
                                .type;
                            if(nt==TerraDensityFunctions
                                    .TerrainType.RIVER
                                ||nt==
                                    TerraDensityFunctions
                                    .TerrainType.LAKE
                                ||nt==
                                    TerraDensityFunctions
                                    .TerrainType
                                    .BELOW_SEA_WATER
                                ||nt==
                                    TerraDensityFunctions
                                    .TerrainType
                                    .SHALLOW_WATER
                                ||nt==
                                    TerraDensityFunctions
                                    .TerrainType.OCEAN){
                                fw=true;
                                ln=Math.min(ln,
                                    ht[gx+dx][gz+dz]);}}
                    nwf[gx][gz]=fw;
                    lwn[gx][gz]=ln;}}

        try{
            for(int lx=0;lx<16;lx++)
                for(int lz=0;lz<16;lz++){
                    int gx=lx+B,gz=lz+B;
                    int bx=cx+lx,bz=cz+lz;
                    var cl=cols[gx][gz];
                    int sf=ht[gx][gz];
                    boolean nw=nwf[gx][gz];
                    int lw=lwn[gx][gz];
                    switch(cl.type){
                        case LAND->pLand(ch,p,bx,bz,
                            sf,cl.elevationMeters,mb,
                            nw,lw);
                        case RIVER->pRiver(ch,p,bx,bz,
                            cl,sf,osy[gx][gz],mb);
                        case LAKE->pLake(ch,p,bx,bz,
                            cl,sf,osy[gx][gz],mb);
                        case BELOW_SEA_WATER->{
                            if(cl.waterBody!=null
                                &&cl.waterBody.isRiver())
                                pRiver(ch,p,bx,bz,cl,
                                    sf,osy[gx][gz],mb);
                            else pLake(ch,p,bx,bz,cl,
                                sf,osy[gx][gz],mb);}
                        case RIVER_BANK,LAKE_SHORE->
                            pLand(ch,p,bx,bz,sf,
                                cl.elevationMeters,
                                mb,nw,lw);
                        case SHALLOW_WATER,OCEAN->
                            pUW(ch,p,bx,bz,sf,
                                cl.elevationMeters,mb);
                        case INVALID->
                            pUW(ch,p,bx,bz,sf,-50,mb);
                        case COASTLINE->pLand(ch,p,bx,
                            bz,sf,cl.elevationMeters,
                            mb,nw,lw);}}}
        catch(Exception ex){
            LOGGER.error("Chunk [{},{}] FAILED: {}",
                ch.getPos().x,ch.getPos().z,
                ex.getMessage());}}

    /**
     * Creates one-by-one terraced transitions at water boundaries.
     *
     * Land is carved outward from the shoreline at +1 Y per horizontal block.
     * Underwater floors are raised from the edge inward at -1 Y per horizontal
     * block. This removes the abrupt vertical wall between the elevation floor
     * and river/lake/ocean floors without changing the water surface itself.
     */
    private void applyFloorTransitions(
            TerraDensityFunctions.TerrainColumn[][] cols,
            int[][] ht, int[][] osy, int minBuildY) {
        boolean[][] water = new boolean[G][G];
        boolean[][] terrain = new boolean[G][G];
        boolean[][] changedTerrain = new boolean[G][G];

        for (int x = 0; x < G; x++) for (int z = 0; z < G; z++) {
            var type = cols[x][z].type;
            water[x][z] = isTransitionWater(type);
            terrain[x][z] = type == TerraDensityFunctions.TerrainType.LAND
                    || type == TerraDensityFunctions.TerrainType.RIVER_BANK
                    || type == TerraDensityFunctions.TerrainType.LAKE_SHORE;
        }

        // Water-floor edge: preserve a two-block minimum depth, then let the
        // floor descend one block per water cell towards its natural depth.
        for (int x = 1; x < G - 1; x++) for (int z = 1; z < G - 1; z++) {
            if (!water[x][z]) continue;
            boolean edge = false;
            for (int i = 0; i < 4; i++)
                if (!water[x + BDX[i]][z + BDZ[i]]) { edge = true; break; }
            if (!edge) continue;
            int surface = transitionWaterSurface(cols[x][z].type, osy[x][z]);
            int edgeFloor = Math.max(minBuildY + 1, surface - 2);
            ht[x][z] = Math.max(ht[x][z], edgeFloor);
        }
        for (int pass = 0; pass < B - 1; pass++) {
            int[][] next = copyHeights(ht);
            boolean any = false;
            for (int x = 1; x < G - 1; x++) for (int z = 1; z < G - 1; z++) {
                if (!water[x][z]) continue;
                int surface = transitionWaterSurface(cols[x][z].type, osy[x][z]);
                int desired = ht[x][z];
                for (int i = 0; i < 4; i++) {
                    int nx = x + BDX[i], nz = z + BDZ[i];
                    if (!water[nx][nz]) continue;
                    // A neighbour one block higher may only descend by one.
                    desired = Math.max(desired, ht[nx][nz] - 1);
                }
                desired = Math.min(desired, surface - 2);
                if (desired > next[x][z]) { next[x][z] = desired; any = true; }
            }
            ht = copyInto(next, ht);
            if (!any) break;
        }

        // Direct shoreline terrain becomes one block above the water surface.
        for (int x = 1; x < G - 1; x++) for (int z = 1; z < G - 1; z++) {
            if (!terrain[x][z]) continue;
            int surface = Integer.MIN_VALUE;
            for (int i = 0; i < 4; i++) {
                int nx = x + BDX[i], nz = z + BDZ[i];
                if (water[nx][nz]) surface = Math.max(surface,
                        transitionWaterSurface(cols[nx][nz].type, osy[nx][nz]));
            }
            if (surface == Integer.MIN_VALUE) continue;
            int bank = Math.max(minBuildY + 2, Math.min(XY - 1, surface + 1));
            if (ht[x][z] > bank) {
                ht[x][z] = bank;
                osy[x][z] = bank;
                changedTerrain[x][z] = true;
            }
        }

        // Continue that bank outward: one horizontal block equals one vertical
        // block, until it naturally meets the original elevation terrain.
        for (int pass = 0; pass < B - 1; pass++) {
            int[][] next = copyHeights(ht);
            boolean[][] nextChanged = new boolean[G][G];
            boolean any = false;
            for (int x = 1; x < G - 1; x++) for (int z = 1; z < G - 1; z++) {
                if (!terrain[x][z] || changedTerrain[x][z]) continue;
                int best = Integer.MAX_VALUE;
                for (int i = 0; i < 4; i++) {
                    int nx = x + BDX[i], nz = z + BDZ[i];
                    if (changedTerrain[nx][nz]) best = Math.min(best, ht[nx][nz] + 1);
                }
                if (best != Integer.MAX_VALUE && ht[x][z] > best) {
                    next[x][z] = best;
                    nextChanged[x][z] = true;
                    any = true;
                }
            }
            ht = copyInto(next, ht);
            for (int x = 1; x < G - 1; x++) for (int z = 1; z < G - 1; z++)
                if (nextChanged[x][z]) { changedTerrain[x][z] = true; osy[x][z] = ht[x][z]; }
            if (!any) break;
        }
    }

    private static boolean isTransitionWater(TerraDensityFunctions.TerrainType type) {
        return type == TerraDensityFunctions.TerrainType.RIVER
                || type == TerraDensityFunctions.TerrainType.LAKE
                || type == TerraDensityFunctions.TerrainType.BELOW_SEA_WATER
                || type == TerraDensityFunctions.TerrainType.SHALLOW_WATER
                || type == TerraDensityFunctions.TerrainType.OCEAN;
    }

    private static int transitionWaterSurface(
            TerraDensityFunctions.TerrainType type, int storedSurface) {
        return type == TerraDensityFunctions.TerrainType.OCEAN
                || type == TerraDensityFunctions.TerrainType.SHALLOW_WATER
                ? SL : storedSurface;
    }

    private static int[][] copyHeights(int[][] source) {
        int[][] copy = new int[G][G];
        for (int x = 0; x < G; x++) System.arraycopy(source[x], 0, copy[x], 0, G);
        return copy;
    }

    // Copy values back to the original height grid; callers retain its reference.
    private static int[][] copyInto(int[][] from, int[][] into) {
        for (int x = 0; x < G; x++) System.arraycopy(from[x], 0, into[x], 0, G);
        return into;
    }

    // ── WATER FAILSAFE ────────────────────────────────────────
    private void applyDepressionFallback(
            TerraDensityFunctions.TerrainColumn[][] cols,
            int[][] ht,int[][] osy,int gx,int gz,
            TerraDensityFunctions.TerrainColumn bc,
            int mb){
        double ns=0;int nc=0;
        double mn=Double.MAX_VALUE,
               mx=-Double.MAX_VALUE;
        int lc=0;
        for(int dx=-1;dx<=1;dx++)
            for(int dz=-1;dz<=1;dz++){
                if(dx==0&&dz==0)continue;
                int nx=gx+dx,nz=gz+dz;
                if(nx<0||nx>=G||nz<0||nz>=G)continue;
                var ncol=cols[nx][nz];
                if(ncol.type==TerraDensityFunctions
                        .TerrainType.OCEAN
                    ||ncol.type==TerraDensityFunctions
                        .TerrainType.SHALLOW_WATER
                    ||ncol.type==TerraDensityFunctions
                        .TerrainType.INVALID)continue;
                double ne=ncol.elevationMeters;
                ns+=ne;nc++;
                if(ne<mn)mn=ne;if(ne>mx)mx=ne;
                if(ne>bc.elevationMeters
                        +DEPRESSION_THRESHOLD_RIVER)
                    lc++;}
        if(nc<3)return;
        double na=ns/nc;
        double dep=na-bc.elevationMeters;
        boolean isr=dep>=DEPRESSION_THRESHOLD_RIVER
            &&lc>=4
            &&(mx-mn)>DEPRESSION_THRESHOLD_RIVER;
        boolean isl=dep>=DEPRESSION_THRESHOLD_LAKE
            &&lc>=5&&(mx-mn)<dep*1.5;
        if(!isr&&!isl)return;
        int ws=bc.surfaceY;
        int db=Math.max(2,
            Math.min(12,(int)(dep*0.5)));
        int fl=Math.max(mb+4,ws-db);
        if(fl>ws-1)fl=ws-2;if(fl<mb+4)fl=mb+4;
        WaterBodyInfo syn=new WaterBodyInfo(
            isr?WaterBodyInfo.WaterType.RIVER
                :WaterBodyInfo.WaterType.LAKE,
            WaterBodyInfo.Source.ELEVATION,true,
            bc.elevationMeters,(double)db,
            isr?20:60,isr?0.2:0.6,0.0,2,null,ws);
        cols[gx][gz]=new TerraDensityFunctions
            .TerrainColumn(
            isr?TerraDensityFunctions.TerrainType.RIVER
                :TerraDensityFunctions.TerrainType.LAKE,
            bc.elevationMeters,fl,
            bc.latitude,bc.longitude,
            TerraDensityFunctions.InvalidReason.NONE,
            syn);
        ht[gx][gz]=fl;osy[gx][gz]=ws;}

    private void consolidateFallbackBasins(
            TerraDensityFunctions.TerrainColumn[][] cols,
            int[][] ht,int[][] osy,int mb){
        boolean[][] visited=new boolean[G][G];
        for(int sx=1;sx<G-1;sx++)
            for(int sz=1;sz<G-1;sz++){
                if(visited[sx][sz])continue;
                var ct=cols[sx][sz].type;
                if(ct!=TerraDensityFunctions
                        .TerrainType.LAKE
                    &&ct!=TerraDensityFunctions
                        .TerrainType.RIVER)continue;
                List<int[]> comp=new ArrayList<>();
                ArrayDeque<int[]> queue=
                    new ArrayDeque<>();
                queue.add(new int[]{sx,sz});
                visited[sx][sz]=true;
                boolean hasRiver=false;
                while(!queue.isEmpty()){
                    int[] cell=queue.poll();
                    comp.add(cell);
                    int cx2=cell[0],cz2=cell[1];
                    if(cols[cx2][cz2].type==
                        TerraDensityFunctions
                            .TerrainType.RIVER)
                        hasRiver=true;
                    for(int i=0;i<4;i++){
                        int nx=cx2+BDX[i];
                        int nz=cz2+BDZ[i];
                        if(nx<1||nx>=G-1
                            ||nz<1||nz>=G-1)continue;
                        if(visited[nx][nz])continue;
                        var nt=cols[nx][nz].type;
                        if(nt==TerraDensityFunctions
                                .TerrainType.LAKE
                            ||nt==TerraDensityFunctions
                                .TerrainType.RIVER){
                            visited[nx][nz]=true;
                            queue.add(
                                new int[]{nx,nz});}}}
                if(hasRiver)continue;
                if(comp.size()<LAKE_MIN_CELLS)
                    for(int[] cell:comp){
                        int rx=cell[0],rz=cell[1];
                        var orig=cols[rx][rz];
                        int origSY=
                            TerraDensityFunctions
                            .elevMetersToSurfaceY(
                                orig.elevationMeters,
                                true);
                        cols[rx][rz]=
                            new TerraDensityFunctions
                            .TerrainColumn(
                                TerraDensityFunctions
                                    .TerrainType.LAND,
                                orig.elevationMeters,
                                origSY,
                                orig.latitude,
                                orig.longitude,
                                TerraDensityFunctions
                                    .InvalidReason.NONE,
                                null);
                        ht[rx][rz]=origSY;
                        osy[rx][rz]=origSY;}}}

// ── IN retryWaterIfNeeded() ───────────────────────────────
// Replace the existing method:

    public void retryWaterIfNeeded(
            WorldGenLevel lvl,ChunkAccess ch){
        long k=ck(ch.getPos().x,ch.getPos().z);
        long[] entry=waterRetryChunks.get(k);
        if(entry==null)return;
        long age=
            System.currentTimeMillis()-entry[0];
        if(age>WATER_RETRY_MAX_AGE_MS){
            waterRetryChunks.remove(k);return;}
        double cLa=
            Double.longBitsToDouble(entry[1]);
        double cLo=
            Double.longBitsToDouble(entry[2]);
        var r2=getRle();

        // Never wait for remote Overpass in a generation retry.  Subscribe the
        // chunk to the shared async geographic-tile task and return; its ready
        // queue will perform authoritative placement on the server thread.
        boolean ohmLoaded = r2.getOhmWaterIndex().isTileCached(
            r2.getOhmWaterIndex().getTileKeyPublic(cLa, cLo));
        boolean osmLoaded = r2.getOsmWaterIndex().isTileCached(
            r2.getOsmWaterIndex().getTileKeyPublic(cLa, cLo));
        if(!ohmLoaded&&!osmLoaded){
            getAsyncWaterManager().request(cLa, cLo, ch.getPos());
            return;
        }

        waterRetryChunks.remove(k);
        int cx2=ch.getPos().getMinBlockX();
        int cz2=ch.getPos().getMinBlockZ();
        int mb=ch.getMinBuildHeight();
        var d2=getDf();
        BlockPos.MutableBlockPos pos=
            new BlockPos.MutableBlockPos();
        for(int lx=0;lx<16;lx++)
            for(int lz=0;lz<16;lz++){
                int bx=cx2+lx,bz=cz2+lz;
                int sy=ch.getHeight(
                    Heightmap.Types.WORLD_SURFACE,
                    lx,lz);
                pos.set(bx,sy,bz);
                if(isStructBlock(
                        ch.getBlockState(pos)))
                    continue;
                try{
                    double[] geo=d2.toGeo(bx,bz);
                    if(geo==null)continue;
                    double lat=geo[1],lon=geo[0];
                    if(Double.isNaN(lat)
                            ||Double.isNaN(lon))
                        continue;
                    double elev=
                        d2.getElevationFast(lat,lon);
                    if(elev<=-50||elev>=5000)
                        continue;
                    var wb=r2.analyze(bx,bz,
                        lat,lon,elev);
                    if(!wb.isWater)continue;
                    int ws=wb.waterSurfaceY>0
                        ?wb.waterSurfaceY:sy;
                    int depthBlocks=
                        wb.profileDepthBlocks();
                    int fl=Math.max(mb+4,
                        ws-depthBlocks);
                    if(fl>ws-2)fl=ws-2;
                    if(fl<mb+4)fl=mb+4;
                    for(int y=fl+1;
                            y<=Math.max(ws,sy)+1;
                            y++){
                        pos.set(bx,y,bz);
                        BlockState bs=
                            ch.getBlockState(pos);
                        if(!bs.isAir()
                                &&!bs.is(
                                    Blocks.WATER))
                            ch.setBlockState(
                                pos,AIR,false);}
                    int h=TerraDensityFunctions
                        .blockHash(bx,bz);
                    BlockState floorBlock=
                        wb.isRiver()
                        ?(h%3==0?SAND:GRAVEL)
                        :(wb.edgeFactor<0.3
                            ?CLAY:GRAVEL);
                    for(int y=Math.max(mb+1,fl-2);
                            y<=fl;y++){
                        pos.set(bx,y,bz);
                        ch.setBlockState(
                            pos,floorBlock,false);}
                    for(int y=fl+1;y<=ws;y++){
                        pos.set(bx,y,bz);
                        ch.setBlockState(
                            pos,WATER,false);}
                }catch(Exception e){
                    LOGGER.debug(
                        "WaterRetry [{},{}]"
                        +" ({},{}): {}",
                        ch.getPos().x,
                        ch.getPos().z,
                        lx,lz,
                        e.getMessage());}}}

    // ── LAND / RIVER / LAKE / UNDERWATER ─────────────────────
    private void pLand(ChunkAccess c,
            BlockPos.MutableBlockPos p,
            int x,int z,int sy,double el,int mb,
            boolean nw,int lw){
        int bt=gbt(x,z);
        double rg=bt==2
            ?getDf().calculateRoughness(x,z):0;
        ugLava(c,p,x,z,sy,mb);
        if(nw&&sy-lw>4&&lw>MY+10){
            int ff=Math.max(mb+1,lw-2);
            int ft=Math.min(sy-8,lw+FMAW);
            for(int y=ff;y<=ft;y++){p.set(x,y,z);
                if(c.getBlockState(p).isAir())
                    c.setBlockState(p,
                        y<=0?DEEPSLATE:STONE,false);}}
        for(int y=Math.max(mb+1,sy-10);y<=sy-8;y++){
            p.set(x,y,z);
            if(c.getBlockState(p).isAir())
                c.setBlockState(p,STONE,false);}
        var db=TerraDensityFunctions
            .getDeepSubBlock(bt,rg);
        for(int y=Math.max(mb+1,sy-7);y<=sy-4;y++){
            p.set(x,y,z);
            c.setBlockState(p,db,false);}
        var sb=TerraDensityFunctions
            .getSubSurfaceBlock(el,bt,rg);
        for(int y=Math.max(mb+1,sy-3);y<sy;y++){
            p.set(x,y,z);
            c.setBlockState(p,sb,false);}
        var sf=getSurfaceBlock(bt,el,rg);
        p.set(x,sy,z);c.setBlockState(p,sf,false);
        if(sy+1<=XY&&(bt==3||el>4500
                ||(el>4000&&bt!=1))){
            p.set(x,sy+1,z);
            c.setBlockState(p,SNOW,false);}
        if(sy+1<=XY){
            int h=TerraDensityFunctions.blockHash(x,z);
            switch(bt){
                case 1->{
                    if(h%40==0){p.set(x,sy+1,z);
                        c.setBlockState(p,DBUSH,false);}
                    else if(h%120==0){
                        int ht2=1+(h%3);
                        for(int cy=1;
                                cy<=ht2&&sy+cy<=XY;
                                cy++){
                            p.set(x,sy+cy,z);
                            c.setBlockState(p,
                                CACTUS,false);}}}
                case 2->{
                    if(rg>0.4){if(h%25==0){
                        p.set(x,sy+1,z);
                        c.setBlockState(p,DBUSH,
                            false);}}
                    else if(h%20==0&&sf==RED_SAND){
                        p.set(x,sy+1,z);
                        c.setBlockState(p,DBUSH,
                            false);}}}}
        if(sy<SL){
            if(bt==9){
                for(int y=sy+1;y<=SL;y++){
                    p.set(x,y,z);
                    c.setBlockState(p,WATER,false);}}
            else{for(int y=sy+1;y<=SL+1;y++){
                p.set(x,y,z);
                if(c.getBlockState(p)
                        .is(Blocks.WATER))
                    c.setBlockState(p,AIR,false);}}}}
    private void pLand(ChunkAccess c,
            BlockPos.MutableBlockPos p,
            int x,int z,int sy,double el,int mb){
        pLand(c,p,x,z,sy,el,mb,false,sy);}

    private void pRiver(ChunkAccess c,
            BlockPos.MutableBlockPos p,
            int x,int z,
            TerraDensityFunctions.TerrainColumn cl,
            int sf,int ws,int mb){
        int fl=Math.max(mb+4,sf);
        if(fl>ws-2)fl=ws-2;if(fl<mb+4)fl=mb+4;
        ug(c,p,x,z,fl,mb);
        for(int y=Math.max(mb+1,fl-5);y<=fl-3;y++){
            p.set(x,y,z);
            c.setBlockState(p,STONE,false);}
        int h=TerraDensityFunctions.blockHash(x,z);
        var bd=h%3==0?SAND:GRAVEL;
        for(int y=Math.max(mb+1,fl-2);y<=fl;y++){
            p.set(x,y,z);
            c.setBlockState(p,bd,false);}
        for(int y=fl+1;y<=ws;y++){
            p.set(x,y,z);
            c.setBlockState(p,WATER,false);}
        if(ws-fl>=3&&ws-fl<=5&&h%8==0){
            p.set(x,fl+1,z);
            c.setBlockState(p,SEAGRASS,false);}}

    private void pLake(ChunkAccess c,
            BlockPos.MutableBlockPos p,
            int x,int z,
            TerraDensityFunctions.TerrainColumn cl,
            int sf,int ws,int mb){
        WaterBodyInfo wb=cl.waterBody;
        int fl=Math.max(mb+4,sf);
        if(fl>ws-2)fl=ws-2;if(fl<mb+4)fl=mb+4;
        ug(c,p,x,z,fl,mb);
        for(int y=Math.max(mb+1,fl-5);y<=fl-4;y++){
            p.set(x,y,z);
            c.setBlockState(p,STONE,false);}
        int h=TerraDensityFunctions.blockHash(x,z);
        double eg=wb!=null?wb.edgeFactor:0.5;
        var bd=eg<0.3?CLAY:eg<0.6
            ?(h%2==0?CLAY:GRAVEL)
            :(h%3==0?SAND:GRAVEL);
        for(int y=Math.max(mb+1,fl-3);y<=fl;y++){
            p.set(x,y,z);
            c.setBlockState(p,bd,false);}
        for(int y=fl+1;y<=ws;y++){
            p.set(x,y,z);
            c.setBlockState(p,WATER,false);}
        int wd=ws-fl;
        if(wd>=3&&wd<=4&&h%6==0){
            p.set(x,fl+1,z);
            c.setBlockState(p,SEAGRASS,false);}
        else if(wd>=4&&wd<=6&&h%10==0){
            p.set(x,fl+1,z);
            c.setBlockState(p,TSGL,false);
            if(fl+2<=ws){p.set(x,fl+2,z);
                c.setBlockState(p,TSGU,false);}}}

    private void pUW(ChunkAccess c,
            BlockPos.MutableBlockPos p,
            int x,int z,int fy,double el,int mb){
        double dp=Math.abs(el);
        ug(c,p,x,z,fy,mb);
        for(int y=Math.max(mb+1,fy-3);y<=fy-1;y++){
            p.set(x,y,z);
            c.setBlockState(p,DIRT,false);}
        p.set(x,fy,z);
        c.setBlockState(p,GRAVEL,false);
        int ws=pUWD(c,p,x,z,fy,dp);
        for(int y=ws;y<=SL;y++){
            p.set(x,y,z);
            c.setBlockState(p,WATER,false);}}

    private int pUWD(ChunkAccess c,
            BlockPos.MutableBlockPos p,
            int x,int z,int fy,double dp){
        int h=TerraDensityFunctions.blockHash(x,z);
        int ch2=h%100,ws=fy+1;
        var zn=TerraDensityFunctions.OceanZone
            .fromDepth(dp);
        switch(zn){
            case COASTAL->{
                if(ch2<25&&fy+1<=SL){
                    p.set(x,fy+1,z);
                    c.setBlockState(p,SEAGRASS,false);
                    ws=fy+2;}
                else if(ch2<35&&fy+2<=SL){
                    p.set(x,fy+1,z);
                    c.setBlockState(p,TSGL,false);
                    p.set(x,fy+2,z);
                    c.setBlockState(p,TSGU,false);
                    ws=fy+3;}
                else if(ch2<45&&fy+1<=SL){
                    p.set(x,fy,z);
                    c.setBlockState(p,
                        TerraDensityFunctions
                        .getRandomCoralBlock(h),false);
                    p.set(x,fy+1,z);
                    c.setBlockState(p,
                        TerraDensityFunctions
                        .getRandomCoral(h>>4),false);
                    ws=fy+2;}}
            case SHELF->{
                if(ch2<25){
                    ws=pKelp(c,p,x,z,fy,h);}
                else if(ch2<40&&fy+1<=SL){
                    p.set(x,fy+1,z);
                    c.setBlockState(p,SEAGRASS,false);
                    ws=fy+2;}
                else if(ch2<48&&fy+1<=SL){
                    p.set(x,fy,z);
                    c.setBlockState(p,
                        TerraDensityFunctions
                        .getRandomCoralBlock(h),false);
                    p.set(x,fy+1,z);
                    c.setBlockState(p,
                        TerraDensityFunctions
                        .getRandomCoral(h>>4),false);
                    ws=fy+2;}}
            case SLOPE->{
                if(ch2<8&&dp<500){
                    ws=pKelp(c,p,x,z,fy,h);}
                else if(ch2<15&&dp<400&&fy+1<=SL){
                    p.set(x,fy+1,z);
                    c.setBlockState(p,SEAGRASS,false);
                    ws=fy+2;}}
            default->{}}
        return Math.min(ws,SL+1);}

    private int pKelp(ChunkAccess c,
            BlockPos.MutableBlockPos p,
            int x,int z,int fy,int h){
        int wa=SL-fy;
        int mx=Math.max(1,
            Math.min(5+(h%22),wa-2));
        for(int k=1;k<=mx;k++){
            int ky=fy+k;if(ky>SL)break;
            p.set(x,ky,z);
            c.setBlockState(p,k<mx
                ?Blocks.KELP_PLANT.defaultBlockState()
                :Blocks.KELP.defaultBlockState(),
                false);}
        return fy+mx+1;}

    // ── HEIGHT / COLUMN ───────────────────────────────────────
    @Override
    public int getBaseHeight(int x,int z,
            Heightmap.Types t,LevelHeightAccessor l,
            RandomState r){
        try{var c=getDf().classify(x,z);
            if(c.type==TerraDensityFunctions
                    .TerrainType.LAND)
                return Math.min(c.surfaceY+1,XY);}
        catch(Exception ig){}return SL+1;}
    @Override
    public NoiseColumn getBaseColumn(int x,int z,
            LevelHeightAccessor l,RandomState r){
        BlockState[] s=new BlockState[GD];
        Arrays.fill(s,STONE);
        return new NoiseColumn(MY,s);}

    // ── F3 DEBUG ──────────────────────────────────────────────
    @Override
    public void addDebugScreenInfo(List<String> info,
            RandomState rs,BlockPos bp){
        try{
            var d=getDf();var rl=getRle();
            var tb=getTerraBiomes();
            var esa=getWorldCover();
            var cl=d.classify(bp.getX(),bp.getZ());
            info.add(String.format(
                "[Terra++] proj:%s zoom:%d "
                +"Y:[%d,%d] src:%s",
                projectionName,zoom,MY,XY,
                activeSource));
            if(worldConfig.timeEnabled){
                int yr=TerraTimeManager.getCurrentYear();
                int sc=TerraTimeManager.getTimeScale();
                String era=
                    TerraTimeManager.getEraName(yr);
                String fmt=
                    TerraTimeManager.formatYear(yr);
                info.add(String.format(
                    "[Terra++] year:%s era:%s speed:%dx",
                    fmt,era,sc));
                if(worldConfig.ohmEnabled
                        &&ohmIndex!=null)
                    info.add(String.format(
                        "[Terra++] ohm:t=%d q=%d %s",
                        ohmIndex.getCacheSize(),
                        ohmIndex.getTotalQueries(),
                        ohmIndex.isEnabled()
                            ?"":"[OFF]"));}
            info.add(String.format(
                "[Terra++] lat:%.6f lon:%.6f",
                cl.latitude,cl.longitude));
            info.add(String.format(
                "[Terra++] elev:%.1fm type:%s surfY:%d",
                cl.elevationMeters,cl.type,
                cl.surfaceY));
            if(cl.invalidReason!=TerraDensityFunctions
                    .InvalidReason.NONE)
                info.add("[Terra++] warn: "
                    +cl.invalidReason.message);
            var wcClass=esa.getClassBlocking(
                cl.latitude,cl.longitude,500);
            info.add(String.format(
                "[Terra++] worldcover:%s loaded:%s",
                wcClass.displayName,
                esa.isTileLoaded(
                    cl.latitude,cl.longitude)));
            if(tb!=null
                    &&!Double.isNaN(cl.latitude)
                    &&!Double.isNaN(cl.longitude)
                    &&cl.latitude!=0
                    &&cl.longitude!=0){
                int bt=tb.getBiomeTypeCode(
                    cl.latitude,cl.longitude,
                    cl.elevationMeters,wcClass);
                String[] tn={"default","desert","mesa",
                    "frozen","swamp","savanna",
                    "shrubland","bare-hot","urban",
                    "beach"};
                String nm=bt>=0&&bt<tn.length
                    ?tn[bt]:"unknown";
                var bi=tb.classifyAt(cl.latitude,
                    cl.longitude,cl.elevationMeters,
                    wcClass);
                double rg=bt==2
                    ?d.calculateRoughness(
                        bp.getX(),bp.getZ()):0;
                info.add(String.format(
                    "[Terra++] biome:%s surf:%s "
                    +"rough:%.2f",
                    bi!=null?bi.toString():"null",
                    nm,rg));}
            if(cl.waterBody!=null
                    &&cl.waterBody.isWater){
                var wb=cl.waterBody;
                info.add(String.format(
                    "[Terra++] %s w:%dm d:%dblk "
                    +"e:%.2f sY:%d bsl:%s %s%s",
                    wb.type,wb.widthMeters,
                    wb.profileDepthBlocks(),
                    wb.edgeFactor,wb.waterSurfaceY,
                    wb.isBelowSeaLevel(),wb.source,
                    wb.name!=null
                        ?" \""+wb.name+"\"":""));}
            else if(worldConfig.waterBodies
                &&cl.type==TerraDensityFunctions
                    .TerrainType.LAND
                &&cl.elevationMeters>WEM
                &&cl.elevationMeters<5500){
                var wb=rl.analyze(bp.getX(),bp.getZ(),
                    cl.latitude,cl.longitude,
                    cl.elevationMeters);
                if(wb.isWater)info.add(String.format(
                    "[Terra++] %s w:%dm d:%dblk "
                    +"e:%.2f sY:%d %s%s",
                    wb.type,wb.widthMeters,
                    wb.profileDepthBlocks(),
                    wb.edgeFactor,wb.waterSurfaceY,
                    wb.source,
                    wb.name!=null
                        ?" \""+wb.name+"\"":""));
                else if(wb.isBank())
                    info.add(String.format(
                    "[Terra++] BANK %s %.1fm%s",
                    wb.type,wb.edgeFactor-1.0,
                    wb.name!=null
                        ?" \""+wb.name+"\"":""));}
            if(cl.type==TerraDensityFunctions
                    .TerrainType.OCEAN
                ||cl.type==TerraDensityFunctions
                    .TerrainType.SHALLOW_WATER){
                double dp=Math.abs(cl.elevationMeters);
                var zn=TerraDensityFunctions.OceanZone
                    .fromDepth(dp);
                int ns=ofn(bp.getX(),bp.getZ(),dp);
                int nf=Math.max(MY+4,
                    Math.min(SL-1,cl.surfaceY+ns));
                info.add(String.format(
                    "[Terra++] depth:%.0fm base:Y=%d "
                    +"noise:%+d floor:Y=%d zone:%s",
                    dp,cl.surfaceY,ns,nf,
                    zn.displayName));}
            else if(cl.type==TerraDensityFunctions
                    .TerrainType.LAND)
                info.add(String.format(
                    "[Terra++] real:%.0fm above_sea:%d",
                    cl.elevationMeters,
                    cl.surfaceY-SL));
            else if(cl.type==TerraDensityFunctions
                    .TerrainType.BELOW_SEA_WATER)
                info.add(String.format(
                    "[Terra++] bsw: elev:%.0fm sY:%d",
                    cl.elevationMeters,cl.surfaceY));
            if(worldConfig.hasAnyStructures()
                    &&structureIndex!=null)
                info.add(String.format(
                    "[Terra++] osm: t:%d q:%d%s",
                    structureIndex.getCacheSize(),
                    structureIndex.getTotalQueries(),
                    structureIndex.isEnabled()
                        ?"":" [OFF]"));
            var ov=overtureIndex;
            if(ov!=null)info.add(String.format(
                "[Terra++] ms: t:%d q:%d b:%d %s%s",
                ov.getCacheSize(),
                ov.getTotalQueries(),
                ov.getTotalBuildings(),
                worldConfig.overtureSupplement
                    ?"supp":"repl",
                ov.isEnabled()?"":" [OFF]"));
            var am=asyncManager;
            if(am!=null)info.add(String.format(
                "[Terra++] async: p:%d c:%d r:%d s:%d",
                am.getPendingCount(),
                am.getCompletedCount(),
                am.getTotalRetries(),
                am.getTotalSuccesses()));
            var pq=placementQueue;
            if(pq!=null)info.add(String.format(
                "[Terra++] queue: a:%d d:%d p:%d",
                pq.getActiveQueueSize(),
                pq.getDeferredSize(),
                pq.getTotalPlaced()));
            if(StructureTickHandler.isInitialized())
                info.add(String.format(
                    "[Terra++] tick: c:%d b:%d w:%d",
                    StructureTickHandler
                        .getTotalChunksPlaced(),
                    StructureTickHandler
                        .getTotalBlocksPlaced(),
                    StructureTickHandler
                        .getWaitingMapSize()));
            info.add(String.format(
                "[Terra++] track: t:%d s:%d "
                +"wr:%d veg:%d str:%d",
                terrChunks.size(),strChunks.size(),
                waterRetryChunks.size(),
                refreshQueue.size(),
                structureRefreshQueue.size()));
            info.add(String.format(
                "[Terra++] pidx: gen=%d loaded=%d "
                +"regions=%d dirty=%d lastYear=%d",
                PersistentChunkRefreshIndex
                    .getGeneration(),
                PersistentChunkRefreshIndex
                    .getLoadedChunkCount(),
                PersistentChunkRefreshIndex
                    .getRegionCacheSize(),
                PersistentChunkRefreshIndex
                    .getDirtyRegionCount(),
                PersistentChunkRefreshIndex
                    .getLastJumpYear()));
            var sb=new StringBuilder(
                "[Terra++] feat:");
            if(worldConfig.buildings)sb.append(" bld");
            if(worldConfig.roads)sb.append(" rd");
            if(worldConfig.railways)sb.append(" rl");
            if(worldConfig.bridges)sb.append(" br");
            if(worldConfig.walls)sb.append(" wl");
            if(worldConfig.gardens)sb.append(" pk");
            if(worldConfig.landuse)sb.append(" lu");
            if(worldConfig.streetLights)
                sb.append(" lt");
            if(worldConfig.overtureBuildings)
                sb.append(" ms");
            if(worldConfig.timeEnabled)
                sb.append(" time");
            if(worldConfig.ohmEnabled)
                sb.append(" ohm");
            sb.append(" d=")
                .append(worldConfig.buildingDetail);
            info.add(sb.toString());
            info.add(String.format(
                "[Terra++] wc: headers=%d iTiles=%d "
                +"results=%d q=%d hits=%d s3=%d %s",
                esa.getTileCacheSize(),
                esa.getResultCacheSize(),
                esa.getResultCacheSize(),
                esa.getTotalQueries(),
                esa.getCacheHits(),
                esa.getS3Fetches(),
                esa.isEnabled()?"":" [OFF]"));
           // ── IN addDebugScreenInfo() ───────────────────────────────
// Replace the existing water tile debug line:

            info.add(String.format(
                "[Terra++] tile:%d elev:%d "
                +"water(osm):%d osm:%d/%d%s "
                +"water(ohm):%d ohm:%d/%d%s",
                d.getCacheSize(),
                d.getElevationCacheSize(),
                rl.getOsmCacheSize(),
                rl.getOsmCacheSize(),
                rl.getOsmTotalQueries(),
                rl.isOsmEnabled()?"":" [OFF]",
                rl.getOhmCacheSize(),
                rl.getOhmCacheSize(),
                rl.getOhmTotalQueries(),
                rl.isOhmEnabled()?"":" [OFF]"));
        }catch(Exception e){
            info.add("[Terra++] err:"
                +e.getMessage());}}
}                                