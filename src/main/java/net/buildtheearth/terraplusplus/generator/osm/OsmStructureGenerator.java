// ============================================================
// FILE: OsmStructureGenerator.java
// FIX: generateStructuresFromList returns boolean.
//      Chunk validity check before each placement loop.
//      Returns false on early abort so caller can retry.
//      roadSurf changed to public static.
//      getRoadBlockFor added as public static method.
//      Both used by TerraChunkGenerator for instant
//      road placement via level.setBlock.
// ============================================================
package net.buildtheearth.terraplusplus.generator.osm;

import net.buildtheearth.terraplusplus.config.TerraWorldConfig;
import net.buildtheearth.terraplusplus.generator.TerraDensityFunctions;
import net.buildtheearth.terraplusplus.generator.historical.GeneratedBlockOwnershipIndex;
import net.buildtheearth.terraplusplus.generator.osm.OsmStructureIndex.BridgeStructure;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.chunk.ChunkStatus;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

public class OsmStructureGenerator {

    private static final Logger LOGGER =
        LogManager.getLogger("Terra++Struct/Gen");

    private final OsmStructureIndex index;
    private final TerraDensityFunctions tdf;
    private final TerraWorldConfig config;
    private final AtomicInteger dbgCount =
        new AtomicInteger(0);

    // Structure generation happens on one chunk at a time. The capture is
    // enabled only by the historical refresh path; normal terrain generation
    // deliberately has no ownership side effects.
    private final ThreadLocal<List<GeneratedBlockOwnershipIndex.OwnedBlock>> ownershipCapture =
        new ThreadLocal<>();

    public void beginOwnershipCapture() {
        ownershipCapture.set(new ArrayList<>());
    }

    public List<GeneratedBlockOwnershipIndex.OwnedBlock> endOwnershipCapture() {
        List<GeneratedBlockOwnershipIndex.OwnedBlock> result = ownershipCapture.get();
        ownershipCapture.remove();
        return result == null ? List.of() : result;
    }

    private void setOwned(ChunkAccess chunk, BlockPos pos, BlockState state, boolean moved) {
        chunk.setBlockState(pos, state, moved);
        List<GeneratedBlockOwnershipIndex.OwnedBlock> captured = ownershipCapture.get();
        if (captured != null)
            captured.add(new GeneratedBlockOwnershipIndex.OwnedBlock(
                pos.asLong(), Block.getId(state)));
    }

    private static final int FLOOR_HEIGHT = 5;
    private static final double WALL_THICKNESS_M = 1.2;
    private static final double CORNER_PILLAR_M = 0.8;
    private static final double WINDOW_SPACING_M = 3.5;
    private static final int BRIDGE_HEIGHT = 8;
    private static final int MIN_PILLAR_HEIGHT = 3;
    private static final int TUNNEL_DEPTH = 8;
    private static final int TUNNEL_HEIGHT = 4;
    private static final int TUNNEL_HALF_WIDTH = 3;
    private static final int MIN_LEVELS_FOR_STAIRS = 2;

    private static final double
        LAYER_END_SEPARATION_M = 8.0;
    private static final double
        TREE_TRANSPORT_CLEARANCE_M = 2.0;
    private static final double
        TREE_CORRIDOR_BUFFER_M = 4.0;
    private static final double
        PARALLEL_ANGLE_TOLERANCE_DEG = 20.0;

    // ── BLOCK STATES ──────────────────────────────────────────
    private static final BlockState
        BRICK = Blocks.BRICKS.defaultBlockState(),
        STONE_BRICK =
            Blocks.STONE_BRICKS.defaultBlockState(),
        COBBLESTONE =
            Blocks.COBBLESTONE.defaultBlockState(),
        SMOOTH_STONE =
            Blocks.SMOOTH_STONE.defaultBlockState(),
        POLISHED_ANDESITE =
            Blocks.POLISHED_ANDESITE.defaultBlockState(),
        POLISHED_DIORITE =
            Blocks.POLISHED_DIORITE.defaultBlockState(),
        WHITE_CONCRETE =
            Blocks.WHITE_CONCRETE.defaultBlockState(),
        LIGHT_GRAY_CONCRETE =
            Blocks.LIGHT_GRAY_CONCRETE
                .defaultBlockState(),
        GRAY_CONCRETE =
            Blocks.GRAY_CONCRETE.defaultBlockState(),
        BROWN_CONCRETE =
            Blocks.BROWN_CONCRETE.defaultBlockState(),
        CYAN_CONCRETE =
            Blocks.CYAN_CONCRETE.defaultBlockState(),
        YELLOW_CONCRETE =
            Blocks.YELLOW_CONCRETE.defaultBlockState(),
        RED_CONCRETE =
            Blocks.RED_CONCRETE.defaultBlockState(),
        ORANGE_CONCRETE =
            Blocks.ORANGE_CONCRETE.defaultBlockState(),
        LIME_CONCRETE =
            Blocks.LIME_CONCRETE.defaultBlockState(),
        BLACK_CONCRETE =
            Blocks.BLACK_CONCRETE.defaultBlockState(),
        WHITE_TERRACOTTA =
            Blocks.WHITE_TERRACOTTA.defaultBlockState(),
        LIGHT_GRAY_TERRACOTTA =
            Blocks.LIGHT_GRAY_TERRACOTTA
                .defaultBlockState(),
        BROWN_TERRACOTTA =
            Blocks.BROWN_TERRACOTTA.defaultBlockState(),
        RED_TERRACOTTA =
            Blocks.RED_TERRACOTTA.defaultBlockState(),
        ORANGE_TERRACOTTA =
            Blocks.ORANGE_TERRACOTTA.defaultBlockState(),
        YELLOW_TERRACOTTA =
            Blocks.YELLOW_TERRACOTTA.defaultBlockState(),
        CYAN_TERRACOTTA =
            Blocks.CYAN_TERRACOTTA.defaultBlockState(),
        OAK_PLANKS =
            Blocks.OAK_PLANKS.defaultBlockState(),
        SPRUCE_PLANKS =
            Blocks.SPRUCE_PLANKS.defaultBlockState(),
        BIRCH_PLANKS =
            Blocks.BIRCH_PLANKS.defaultBlockState(),
        DARK_OAK_PLANKS =
            Blocks.DARK_OAK_PLANKS.defaultBlockState(),
        OAK_LOG =
            Blocks.OAK_LOG.defaultBlockState(),
        STRIPPED_OAK_LOG =
            Blocks.STRIPPED_OAK_LOG.defaultBlockState(),
        GLASS_PANE =
            Blocks.GLASS_PANE.defaultBlockState(),
        LIGHT_BLUE_GLASS =
            Blocks.LIGHT_BLUE_STAINED_GLASS
                .defaultBlockState(),
        QUARTZ =
            Blocks.QUARTZ_BLOCK.defaultBlockState(),
        SMOOTH_QUARTZ =
            Blocks.SMOOTH_QUARTZ.defaultBlockState(),
        QUARTZ_PILLAR =
            Blocks.QUARTZ_PILLAR.defaultBlockState(),
        SANDSTONE =
            Blocks.SANDSTONE.defaultBlockState(),
        RED_SANDSTONE =
            Blocks.RED_SANDSTONE.defaultBlockState();

    private static final BlockState
        FLOOR_OAK =
            Blocks.OAK_PLANKS.defaultBlockState(),
        FLOOR_SPRUCE =
            Blocks.SPRUCE_PLANKS.defaultBlockState(),
        FLOOR_STONE =
            Blocks.SMOOTH_STONE.defaultBlockState(),
        FLOOR_POLISHED =
            Blocks.POLISHED_ANDESITE.defaultBlockState(),
        ROOF_BRICK =
            Blocks.BRICKS.defaultBlockState(),
        ROOF_DARK_OAK =
            Blocks.DARK_OAK_PLANKS.defaultBlockState(),
        ROOF_STONE_BRICK =
            Blocks.STONE_BRICKS.defaultBlockState(),
        ROOF_SPRUCE =
            Blocks.SPRUCE_PLANKS.defaultBlockState(),
        ROOF_COBBLE =
            Blocks.COBBLESTONE.defaultBlockState(),
        ROOF_SMOOTH_STONE =
            Blocks.SMOOTH_STONE.defaultBlockState();

    // ── ROAD BLOCK STATES ─────────────────────────────────────
    private static final BlockState
        ROAD_ASPHALT =
            Blocks.BLACK_CONCRETE.defaultBlockState(),
        ROAD_CONCRETE =
            Blocks.GRAY_CONCRETE.defaultBlockState(),
        ROAD_GRAVEL =
            Blocks.GRAVEL.defaultBlockState(),
        ROAD_SMOOTH =
            Blocks.SMOOTH_STONE.defaultBlockState(),
        ROAD_DIRT =
            Blocks.DIRT_PATH.defaultBlockState(),
        ROAD_SAND =
            Blocks.SAND.defaultBlockState(),
        SIDEWALK =
            Blocks.SMOOTH_STONE.defaultBlockState(),
        ROAD_MARKING_WHITE =
            Blocks.WHITE_CONCRETE.defaultBlockState(),
        ROAD_MARKING_YELLOW =
            Blocks.YELLOW_CONCRETE.defaultBlockState();

    // ── BRIDGE BLOCK STATES ───────────────────────────────────
    private static final BlockState
        BRIDGE_DECK =
            Blocks.SMOOTH_STONE.defaultBlockState(),
        BRIDGE_PILLAR =
            Blocks.STONE_BRICKS.defaultBlockState(),
        BRIDGE_ARCH =
            Blocks.POLISHED_ANDESITE.defaultBlockState(),
        BRIDGE_RAILING =
            Blocks.COBBLESTONE_WALL.defaultBlockState(),
        BRIDGE_TOWER =
            Blocks.QUARTZ_PILLAR.defaultBlockState(),
        BRIDGE_CABLE =
            Blocks.IRON_BARS.defaultBlockState(),
        BRIDGE_ROPE =
            Blocks.CHAIN.defaultBlockState(),
        BRIDGE_WOOD =
            Blocks.OAK_PLANKS.defaultBlockState(),
        BRIDGE_TRUSS =
            Blocks.IRON_BARS.defaultBlockState(),
        BRIDGE_STONE =
            Blocks.STONE_BRICKS.defaultBlockState(),
        BRIDGE_WATER =
            Blocks.WATER.defaultBlockState(),
        BRIDGE_PONTOON =
            Blocks.OAK_SLAB.defaultBlockState();

    // ── TUNNEL BLOCK STATES ───────────────────────────────────
    private static final BlockState
        TUNNEL_WALL =
            Blocks.STONE_BRICKS.defaultBlockState(),
        TUNNEL_CEILING =
            Blocks.SMOOTH_STONE.defaultBlockState(),
        TUNNEL_FLOOR =
            Blocks.SMOOTH_STONE.defaultBlockState(),
        TUNNEL_SUPPORT =
            Blocks.POLISHED_ANDESITE.defaultBlockState(),
        TUNNEL_LIGHT =
            Blocks.SEA_LANTERN.defaultBlockState();

    // ── MISC BLOCK STATES ─────────────────────────────────────
    private static final BlockState
        AIR = Blocks.AIR.defaultBlockState(),
        GRASS_BLOCK =
            Blocks.GRASS_BLOCK.defaultBlockState(),
        FARMLAND_BLOCK =
            Blocks.FARMLAND.defaultBlockState(),
        WHEAT_BLOCK = Blocks.WHEAT.defaultBlockState(),
        SHORT_GRASS = Blocks.GRASS.defaultBlockState(),
        FLOWER_DANDELION =
            Blocks.DANDELION.defaultBlockState(),
        FLOWER_POPPY =
            Blocks.POPPY.defaultBlockState(),
        FLOWER_CORNFLOWER =
            Blocks.CORNFLOWER.defaultBlockState(),
        FLOWER_ALLIUM =
            Blocks.ALLIUM.defaultBlockState(),
        FLOWER_AZURE =
            Blocks.AZURE_BLUET.defaultBlockState(),
        OAK_LEAVES =
            Blocks.OAK_LEAVES.defaultBlockState(),
        BIRCH_LEAVES =
            Blocks.BIRCH_LEAVES.defaultBlockState(),
        SPRUCE_LEAVES =
            Blocks.SPRUCE_LEAVES.defaultBlockState(),
        RAIL = Blocks.RAIL.defaultBlockState(),
        IRON_BARS =
            Blocks.IRON_BARS.defaultBlockState(),
        SEA_LANTERN =
            Blocks.SEA_LANTERN.defaultBlockState(),
        GLOWSTONE =
            Blocks.GLOWSTONE.defaultBlockState(),
        STONE_WALL =
            Blocks.STONE_BRICK_WALL.defaultBlockState(),
        OAK_FENCE =
            Blocks.OAK_FENCE.defaultBlockState(),
        PARKING_BLOCK =
            Blocks.SMOOTH_STONE.defaultBlockState(),
        LANTERN = Blocks.LANTERN.defaultBlockState(),
        // ── extended-coverage feature blocks ──
        STONE_BLOCK =
            Blocks.STONE.defaultBlockState(),
        SAND_BLOCK =
            Blocks.SAND.defaultBlockState(),
        WATER_BLOCK =
            Blocks.WATER.defaultBlockState(),
        POOL_RIM =
            Blocks.SMOOTH_QUARTZ.defaultBlockState(),
        PIER_DECK =
            Blocks.OAK_PLANKS.defaultBlockState(),
        RUNWAY_SURFACE =
            Blocks.GRAY_CONCRETE.defaultBlockState(),
        APRON_SURFACE =
            Blocks.LIGHT_GRAY_CONCRETE
                .defaultBlockState(),
        PLATFORM_SURFACE =
            Blocks.SMOOTH_STONE.defaultBlockState(),
        TOWER_WALL =
            Blocks.STONE_BRICKS.defaultBlockState(),
        TANK_WALL =
            Blocks.LIGHT_GRAY_CONCRETE
                .defaultBlockState(),
        MAST_FRAME =
            Blocks.IRON_BARS.defaultBlockState();

    private static final BlockState
        OAK_DOOR_L =
            Blocks.OAK_DOOR.defaultBlockState()
            .setValue(
                net.minecraft.world.level.block
                    .DoorBlock.HALF,
                net.minecraft.world.level.block.state
                    .properties.DoubleBlockHalf.LOWER),
        OAK_DOOR_U =
            Blocks.OAK_DOOR.defaultBlockState()
            .setValue(
                net.minecraft.world.level.block
                    .DoorBlock.HALF,
                net.minecraft.world.level.block.state
                    .properties.DoubleBlockHalf.UPPER),
        IRON_DOOR_L =
            Blocks.IRON_DOOR.defaultBlockState()
            .setValue(
                net.minecraft.world.level.block
                    .DoorBlock.HALF,
                net.minecraft.world.level.block.state
                    .properties.DoubleBlockHalf.LOWER),
        IRON_DOOR_U =
            Blocks.IRON_DOOR.defaultBlockState()
            .setValue(
                net.minecraft.world.level.block
                    .DoorBlock.HALF,
                net.minecraft.world.level.block.state
                    .properties.DoubleBlockHalf.UPPER),
        SPRUCE_DOOR_L =
            Blocks.SPRUCE_DOOR.defaultBlockState()
            .setValue(
                net.minecraft.world.level.block
                    .DoorBlock.HALF,
                net.minecraft.world.level.block.state
                    .properties.DoubleBlockHalf.LOWER),
        SPRUCE_DOOR_U =
            Blocks.SPRUCE_DOOR.defaultBlockState()
            .setValue(
                net.minecraft.world.level.block
                    .DoorBlock.HALF,
                net.minecraft.world.level.block.state
                    .properties.DoubleBlockHalf.UPPER);

    private record BuildingStyle(
            BlockState wall,
            BlockState wallAccent,
            BlockState cornerPillar,
            BlockState floor,
            BlockState ceiling,
            BlockState roof,
            BlockState windowBlock,
            BlockState doorLower,
            BlockState doorUpper,
            BlockState foundation,
            BlockState interiorLight,
            BlockState stepBlock,
            RoofType roofType){}

    private enum RoofType{
        FLAT,GABLED,HIPPED,PYRAMIDAL,DOME,ONION,MANSARD}

    public OsmStructureGenerator(
            OsmStructureIndex index,
            TerraDensityFunctions tdf,
            TerraWorldConfig config){
        this.index=index;
        this.tdf=tdf;
        this.config=config;
    }

    // ================================================================
    // PUBLIC STATIC ROAD BLOCK METHODS
    // Used by TerraChunkGenerator for instant road placement
    // ================================================================

    // Public static — returns correct BlockState for road
    // based on subType and surface tag.
    // Called directly from TerraChunkGenerator.
    public static BlockState roadSurf(
            String s,String sf){
        // ── ERA-AWARE MATERIALS ───────────────────────────────
        // Wires HistoricalStructureManager.getRoadMaterialKey into
        // actual block selection. Previously that method was dead
        // code (audit: "never called anywhere"), so a Roman road in
        // 200 BC rendered in black concrete because OSM tags the
        // modern road as asphalt. In historical mode the era now
        // CAPS the material: modern tags are ignored before the
        // era that introduced them; period-appropriate tags
        // (dirt, sett, gravel) still show through.
        if(net.buildtheearth.terraplusplus.generator
                .historical.TerraTimeManager
                .isTimeEnabled()){
            int year=net.buildtheearth.terraplusplus
                .generator.historical.TerraTimeManager
                .getCurrentYear();
            if(year<1950){
                BlockState era=eraRoadBlock(year);
                BlockState tagged=roadSurfModern(s,sf);
                // Let already-primitive tagged surfaces win
                // (a dirt track stays dirt in 1700), but cap
                // anything more advanced than the era allows.
                return roadRank(tagged)<=roadRank(era)
                    ?tagged:era;
            }
        }
        return roadSurfModern(s,sf);
    }

    /** Painted road markings appeared ~1918; suppress them earlier. */
    private static boolean markingsExistNow(){
        return !net.buildtheearth.terraplusplus.generator
                .historical.TerraTimeManager.isTimeEnabled()
            || net.buildtheearth.terraplusplus.generator
                .historical.TerraTimeManager.getCurrentYear()>=1920;
    }

    /** Era → material, mirroring
     *  HistoricalStructureManager.getRoadMaterialKey. */
    private static BlockState eraRoadBlock(int year){
        if(year<-500) return ROAD_DIRT;         // dirt_path
        if(year<500)  return COBBLESTONE;       // Roman roads
        if(year<1500) return ROAD_GRAVEL;       // medieval
        if(year<1800) return ROAD_SMOOTH;       // early paving
        if(year<1900) return COBBLESTONE;       // Victorian sett
        return ROAD_CONCRETE;                   // 1900-1950
    }

    /** Crude technology rank so a period-appropriate tag can
     *  undercut the era cap but never exceed it. */
    private static int roadRank(BlockState b){
        if(b==ROAD_DIRT||b==ROAD_SAND) return 0;
        if(b==ROAD_GRAVEL)             return 1;
        if(b==COBBLESTONE)             return 2;
        if(b==ROAD_SMOOTH)             return 3;
        if(b==ROAD_CONCRETE)           return 4;
        return 5;                      // asphalt/other
    }

    private static BlockState roadSurfModern(
            String s,String sf){
        if(sf!=null)return switch(sf.toLowerCase()){
            case "asphalt"->ROAD_ASPHALT;
            case "concrete"->ROAD_CONCRETE;
            case "paving_stones","sett"->ROAD_SMOOTH;
            case "gravel","fine_gravel"->ROAD_GRAVEL;
            case "dirt","earth","ground"->ROAD_DIRT;
            case "sand"->ROAD_SAND;
            default->ROAD_CONCRETE;
        };
        return switch(s!=null?s:""){
            case "motorway","trunk",
                 "motorway_link","trunk_link",
                 "primary","primary_link"
                ->ROAD_ASPHALT;
            case "secondary","tertiary",
                 "residential","living_street"
                ->ROAD_CONCRETE;
            case "service"->ROAD_SMOOTH;
            case "track","unclassified"->ROAD_GRAVEL;
            default->ROAD_CONCRETE;
        };
    }

    // Public static — returns correct BlockState for any
    // road-type structure including footway, cycleway,
    // railway.
    // Called directly from TerraChunkGenerator for
    // instant road placement via level.setBlock.
    public static BlockState getRoadBlockFor(
            OsmStructureIndex.OsmStructure s){
        return switch(s.type){
            case FOOTWAY->
                s.surface!=null
                ?switch(s.surface.toLowerCase()){
                    case "asphalt"->ROAD_SMOOTH;
                    case "paving_stones"->STONE_BRICK;
                    case "gravel"->ROAD_GRAVEL;
                    default->ROAD_DIRT;
                }
                :ROAD_DIRT;
            case CYCLEWAY->RED_CONCRETE;
            case RAILWAY->ROAD_GRAVEL;
            default->roadSurf(s.subType,s.surface);
        };
    }

    // ================================================================
    // DYNAMIC BRIDGE SCALING
    // ================================================================

    private int dynamicPillarSpacing(double totalLen){
        return(int)Math.max(8,Math.min(20,
            totalLen/8));}
    private int dynamicTowerHeight(double totalLen){
        return(int)Math.max(12,Math.min(30,
            (totalLen/15)+10));}
    private double dynamicArchSpan(double totalLen){
        return Math.max(10,Math.min(25,totalLen/6));}
    private int dynamicMinPillarHeight(
            int deckBase,int sy){
        return Math.max(MIN_PILLAR_HEIGHT,
            (deckBase-sy)/3);}

    // ================================================================
    // BRIDGE ZONE CHECK
    // ================================================================

    private boolean isUnderBridgeZone(double lat,
            double lon,
            List<OsmStructureIndex.OsmStructure>
            bridges){
        for(OsmStructureIndex.OsmStructure b:bridges){
            double hw=roadHW(
                b.subType!=null
                ?b.subType:"secondary",b.lanes)
                *config.roadWidthScale;
            double dist=b.distanceToLine(lat,lon);
            if(dist<=hw+2.0)return true;
        }
        return false;}

    private boolean isTransportationBuilding(
            OsmStructureIndex.OsmStructure s){
        if(s==null||s.subType==null)return false;
        String sub=s.subType.toLowerCase();
        return switch(sub){
            case "transportation","transport",
                 "train_station","station",
                 "railway_station","bus_station",
                 "terminal","public_transport",
                 "tram_station","subway_station",
                 "monorail_station"->true;
            default->false;
        };}

    // ── STATION/VIADUCT VERTICAL ALIGNMENT ────────────────────
    // OSM `layer` is a STACKING-ORDER hint, never an altitude.
    // Three real Hyderabad cases proved it:
    //  - viaduct crossings: Line 2 layer=4 crosses Line 3 layer=2
    //    (stacking), stations tagged layer=3 / layer=1 arbitrarily;
    //  - ordinary GROUND buildings tagged layer=1 purely for 2D
    //    draw order: 357060333 (building=yes), 51327327 "iLabs
    //    Centre" (10-storey office), 361511089 (building=roof) —
    //    layer*8 floated all of them 8 blocks in the air.
    // Evidence rule: a building sits ON THE GROUND unless
    // something physical proves elevation:
    //  1. transportation building + a rail viaduct passing
    //     through its footprint -> snap to that track's deck;
    //  2. explicit min_height / min_level -> handled separately
    //     via minHeightBlocks() (real vertical data, kept);
    //  3. otherwise -> ground. layer alone is never evidence.
    private final Set<Long> alignLogged =
        java.util.concurrent.ConcurrentHashMap.newKeySet();

    private int elevatedBuildingOffset(
            OsmStructureIndex.OsmStructure s,
            List<OsmStructureIndex.OsmStructure> bridges){
        if(s.layer<=0)return 0;
        if(isTransportationBuilding(s)
                &&bridges!=null&&!bridges.isEmpty()){
            double cLat=(s.minLat+s.maxLat)/2.0;
            double cLon=(s.minLon+s.maxLon)/2.0;
            // footprint radius (m) + margin: the track must
            // actually pass through/beside the box, not merely
            // share the bbox
            double rM=Math.max(
                (s.maxLat-s.minLat)*111320.0,
                (s.maxLon-s.minLon)*111320.0
                    *Math.cos(Math.toRadians(cLat)))/2.0+12.0;
            OsmStructureIndex.OsmStructure best=null;
            double bestD=Double.MAX_VALUE;
            for(OsmStructureIndex.OsmStructure b:bridges){
                if(!b.isRailBridge)continue;
                if(b.layer<=0)continue;
                if(b.maxLat<s.minLat||b.minLat>s.maxLat
                    ||b.maxLon<s.minLon||b.minLon>s.maxLon)
                    continue;
                double d=b.distanceToLine(cLat,cLon);
                if(d>rM)continue;
                if(d<bestD){bestD=d;best=b;}
            }
            if(best!=null){
                int snapped=best.layer*BRIDGE_HEIGHT;
                if(alignLogged.add(s.osmId))
                    LOGGER.info("STATION ALIGN: building {} "
                        +"(layer={}) snapped to rail viaduct {} "
                        +"(layer={}, deck offset {}) — layer "
                        +"tags are stacking hints, track wins",
                        s.osmId,s.layer,
                        best.osmId,best.layer,snapped);
                return snapped;
            }
        }
        // No physical evidence of elevation: ground it.
        if(alignLogged.add(s.osmId))
            LOGGER.info("GROUND ALIGN: building {} layer={} "
                +"ignored (no viaduct through footprint — "
                +"layer is 2D draw order, not altitude)",
                s.osmId,s.layer);
        return 0;
    }

    private double getAdjustedDistFromEnd(
            double dfn,
            OsmStructureIndex.OsmStructure bridge,
            List<OsmStructureIndex.OsmStructure>
            allBridges){
        if(bridge.layer<=1)return dfn;
        for(OsmStructureIndex.OsmStructure other:
                allBridges){
            if(other.osmId==bridge.osmId)continue;
            if(other.layer>=bridge.layer)continue;
            if(other.layer<=0)continue;
            OsmStructureIndex.OsmNode bStart=
                bridge.startNode();
            OsmStructureIndex.OsmNode bEnd=
                bridge.endNode();
            OsmStructureIndex.OsmNode oStart=
                other.startNode();
            OsmStructureIndex.OsmNode oEnd=
                other.endNode();
            if(bStart==null||bEnd==null
                    ||oStart==null||oEnd==null)
                continue;
            double dSS=haversineMeters(
                bStart.lat,bStart.lon,
                oStart.lat,oStart.lon);
            double dSE=haversineMeters(
                bStart.lat,bStart.lon,
                oEnd.lat,oEnd.lon);
            double dES=haversineMeters(
                bEnd.lat,bEnd.lon,
                oStart.lat,oStart.lon);
            double dEE=haversineMeters(
                bEnd.lat,bEnd.lon,
                oEnd.lat,oEnd.lon);
            double minEndDist=Math.min(
                Math.min(dSS,dSE),
                Math.min(dES,dEE));
            if(minEndDist<LAYER_END_SEPARATION_M)
                return Math.max(dfn,
                    LAYER_END_SEPARATION_M
                    -minEndDist);
        }
        return dfn;}

    // ================================================================
    // CHUNK VALIDITY CHECK
    // ================================================================

    private boolean isChunkValid(ChunkAccess chunk){
        if(chunk==null)return false;
        try{
            if(chunk instanceof LevelChunk lc){
                try{
                    chunk.getBlockState(new BlockPos(
                        chunk.getPos()
                            .getMinBlockX(),
                        chunk.getMinBuildHeight(),
                        chunk.getPos()
                            .getMinBlockZ()));
                    return true;
                }catch(Exception e){
                    return false;
                }
            }
            return true;
        }catch(Exception e){
            return false;
        }}

    // ================================================================
    // MAIN ENTRY POINTS
    // ================================================================

    public void generateStructures(ChunkAccess chunk){
        if(!config.hasAnyStructures())return;
        int cx=chunk.getPos().getMinBlockX();
        int cz=chunk.getPos().getMinBlockZ();
        double[] geoCenter=tdf.toGeo(cx+8,cz+8);
        if(geoCenter==null)return;
        double centerLon=geoCenter[0];
        double centerLat=geoCenter[1];
        if(Double.isNaN(centerLat)
                ||Double.isNaN(centerLon))return;
        List<OsmStructureIndex.OsmStructure>
            structures=index.getStructuresAt(
                centerLat,centerLon,config);
        if(structures.isEmpty())return;
        if(dbgCount.getAndIncrement()<10)
            LOGGER.info(
                "STRUCT chunk [{},{}]: {} structures",
                chunk.getPos().x,chunk.getPos().z,
                structures.size());
        generateStructuresFromList(chunk,structures);}

public boolean generateStructuresFromList(
        ChunkAccess chunk,
        List<OsmStructureIndex.OsmStructure>
        structures){
    if(structures==null||structures.isEmpty())
        return true;
    LOGGER.info(
        "[TerraStruct] PLACING chunk [{},{}] "
        + "structures={}",
        chunk.getPos().x, chunk.getPos().z,
        structures.size());
    if(!isChunkValid(chunk))return false;

    int cx=chunk.getPos().getMinBlockX();
    int cz=chunk.getPos().getMinBlockZ();
    BlockPos.MutableBlockPos pos=
        new BlockPos.MutableBlockPos();

    // Inland lakes (Durgam Cheruvu) are LAND + water
    // forever. The old "water on LAND → retry whole
    // chunk" abort skipped every over-water span.
    // Bridges/piers run on waterCell below; ground
    // structures already skip those columns.

    List<OsmStructureIndex.OsmStructure>
        roads=new ArrayList<>(),
        bridges=new ArrayList<>(),
        railways=new ArrayList<>(),
        fullBuildings=new ArrayList<>(),
        buildingParts=new ArrayList<>(),
        piers=new ArrayList<>();

    for(OsmStructureIndex.OsmStructure s:structures)
        switch(s.type){
            case ROAD,FOOTWAY,CYCLEWAY->
                roads.add(s);
            case BRIDGE->bridges.add(s);
            case RAILWAY->railways.add(s);
            case BUILDING->fullBuildings.add(s);
            case BUILDING_PART->
                buildingParts.add(s);
            case PIER->piers.add(s);
            default->{}
        }

    double[][] geoLat=new double[16][16];
    double[][] geoLon=new double[16][16];
    int[][] solidYs=new int[16][16];
    int[][] bridgeYs=new int[16][16];
    boolean[][] validCell=new boolean[16][16];

    for(int lx=0;lx<16;lx++)
        for(int lz=0;lz<16;lz++){
            double[] geo=
                tdf.toGeo(cx+lx,cz+lz);
            if(geo==null
                    ||Double.isNaN(geo[0])
                    ||Double.isNaN(geo[1])){
                validCell[lx][lz]=false;
                continue;}
            geoLon[lx][lz]=geo[0];
            geoLat[lx][lz]=geo[1];
            TerraDensityFunctions.TerrainColumn
                col=tdf.classify(cx+lx,cz+lz);
            solidYs[lx][lz]=findSolidGround(
                chunk,pos,cx+lx,cz+lz,
                col.surfaceY);
            bridgeYs[lx][lz]=findWaterSurface(
                chunk,pos,cx+lx,cz+lz,
                Math.max(col.surfaceY,
                    solidYs[lx][lz]));
            validCell[lx][lz]=true;}

    // ── WATER COLUMNS ─────────────────────────────────────
    // Water above solid ground is a real river/lake/ocean
    // that was not historically drained. Buildings, roads
    // and landuse must not pave the riverbed — but BRIDGES
    // and PIERS exist specifically to occupy those columns.
    // Previously this flag wiped validCell before the
    // bridge loop, so every over-water span (Durgam Cheruvu,
    // Musi crossings, harbour piers) never generated.
    boolean[][] waterCell=new boolean[16][16];
    for(int lx=0;lx<16;lx++)
        for(int lz=0;lz<16;lz++){
            if(!validCell[lx][lz])continue;
            int gy=solidYs[lx][lz];
            for(int y=gy+1;
                    y<=Math.min(gy+20,
                        chunk.getMaxBuildHeight()-2);
                    y++){
                pos.set(cx+lx,y,cz+lz);
                BlockState bs=
                    chunk.getBlockState(pos);
                if(isWaterBlock(bs)){
                    waterCell[lx][lz]=true;
                    break;}
                if(!bs.isAir())break;}
        }

    // ── BRIDGES + PIERS (allowed on water) ────────────────
    if(config.bridges){
        if(!isChunkValid(chunk))return false;
        for(int lx=0;lx<16;lx++)
            for(int lz=0;lz<16;lz++){
                if(!validCell[lx][lz])continue;
                for(OsmStructureIndex.OsmStructure
                        s:bridges)
                    placeBridge(chunk,pos,
                        cx+lx,cz+lz,
                        bridgeYs[lx][lz],s,
                        geoLat[lx][lz],
                        geoLon[lx][lz],
                        roads,railways,bridges);
                for(OsmStructureIndex.OsmStructure
                        s:piers)
                    placePier(chunk,pos,
                        cx+lx,cz+lz,
                        solidYs[lx][lz],
                        bridgeYs[lx][lz],s,
                        geoLat[lx][lz],
                        geoLon[lx][lz]);}}

    // Ground structures skip water; elevated ones already ran.
    for(int lx=0;lx<16;lx++)
        for(int lz=0;lz<16;lz++)
            if(waterCell[lx][lz])
                validCell[lx][lz]=false;

    // ── BUILDINGS ─────────────────────────────────────────
    if(config.buildings){
        if(!isChunkValid(chunk))return false;
        for(int lx=0;lx<16;lx++)
            for(int lz=0;lz<16;lz++){
                if(!validCell[lx][lz])continue;
                final int flx=lx,flz=lz;
                boolean underBridge=
                    isUnderBridgeZone(
                        geoLat[flx][flz],
                        geoLon[flx][flz],
                        bridges);
                boolean hasParts=
                    buildingParts.stream()
                    .anyMatch(p->p.containsPoint(
                        geoLat[flx][flz],
                        geoLon[flx][flz]));
                for(OsmStructureIndex.OsmStructure
                        s:fullBuildings){
                    if(underBridge
                            &&!isTransportationBuilding(s))
                        continue;
                    if(!hasParts)
                        placeBuilding(chunk,pos,
                            cx+lx,cz+lz,
                            solidYs[lx][lz],s,
                            geoLat[lx][lz],
                            geoLon[lx][lz],roads,
                            bridges);}}}

    if(config.buildings){
        if(!isChunkValid(chunk))return false;
        for(int lx=0;lx<16;lx++)
            for(int lz=0;lz<16;lz++){
                if(!validCell[lx][lz])continue;
                boolean underBridge=
                    isUnderBridgeZone(
                        geoLat[lx][lz],
                        geoLon[lx][lz],bridges);
                for(OsmStructureIndex.OsmStructure
                        s:buildingParts){
                    if(underBridge
                            &&!isTransportationBuilding(s))
                        continue;
                    placeBuildingPart(chunk,pos,
                        cx+lx,cz+lz,
                        solidYs[lx][lz],s,
                        geoLat[lx][lz],
                        geoLon[lx][lz],roads,
                        bridges);}}}

    // ── ROADS ─────────────────────────────────────────────
    if(config.roads){
        if(!isChunkValid(chunk))return false;
        for(int lx=0;lx<16;lx++)
            for(int lz=0;lz<16;lz++){
                if(!validCell[lx][lz])continue;
                placeRoadNearest(chunk,pos,
                    cx+lx,cz+lz,
                    solidYs[lx][lz],roads,bridges,
                    geoLat[lx][lz],
                    geoLon[lx][lz]);}}

    // ── RAILWAYS ──────────────────────────────────────────
    if(config.railways){
        if(!isChunkValid(chunk))return false;
        for(int lx=0;lx<16;lx++)
            for(int lz=0;lz<16;lz++){
                if(!validCell[lx][lz])continue;
                for(OsmStructureIndex.OsmStructure
                        s:railways)
                    placeRailway(chunk,pos,
                        cx+lx,cz+lz,
                        solidYs[lx][lz],s,bridges,
                        geoLat[lx][lz],
                        geoLon[lx][lz]);}}

    // ── OTHER FEATURES ────────────────────────────────────
    if(!isChunkValid(chunk))return false;
    for(int lx=0;lx<16;lx++)
        for(int lz=0;lz<16;lz++){
            if(!validCell[lx][lz])continue;
            for(OsmStructureIndex.OsmStructure s:
                    structures)
                switch(s.type){
                    case BUILDING,BUILDING_PART,
                         BRIDGE,ROAD,FOOTWAY,
                         CYCLEWAY,RAILWAY,PIER->{}
                    default->processNonBuilding(
                        chunk,pos,
                        cx+lx,cz+lz,
                        solidYs[lx][lz],s,
                        geoLat[lx][lz],
                        geoLon[lx][lz],
                        roads,bridges,railways);}
        }

    if(!isChunkValid(chunk))return false;
    return true;
}

    // ================================================================
    // TERRAIN HELPERS
    // ================================================================

    private int findSolidGround(ChunkAccess chunk,
            BlockPos.MutableBlockPos pos,
            int bx,int bz,int surfaceY){
        int minY=chunk.getMinBuildHeight();
        int maxY=chunk.getMaxBuildHeight()-2;
        int y=Math.max(minY,Math.min(maxY,surfaceY));
        pos.set(bx,y,bz);
        BlockState state=chunk.getBlockState(pos);
        if(!state.isAir()&&!isWaterBlock(state)){
            while(y+1<=maxY){
                pos.set(bx,y+1,bz);
                BlockState above=chunk.getBlockState(pos);
                if(above.isAir()||isWaterBlock(above))
                    break;
                // Never climb a PLACED structure. This also
                // runs on already-built chunks (the async
                // placement road, placeSingleBlock): there a
                // finished building plus the metro deck above
                // it are one unbroken solid stack, and climbing
                // it made "ground" the roof — the same building
                // was built again on the deck and the rail was
                // stamped on the copy's roof (owner's world
                // newworld1: stone tower at Secunderabad,
                // copies at y 599..628 and y 631..661, rail
                // at y 663). Terrain tops are natural blocks;
                // climbing them is unchanged.
                if(isBuildingBlock(above)
                    ||isRoadBlock(above)
                    ||isBridgeDeckBlock(above))
                    break;
                y++;}
            return y;}
        while(y>minY){
            y--;pos.set(bx,y,bz);
            state=chunk.getBlockState(pos);
            if(!state.isAir()&&!isWaterBlock(state))
                return y;}
        return surfaceY;}

    private int findWaterSurface(ChunkAccess chunk,
            BlockPos.MutableBlockPos pos,
            int bx,int bz,int surfaceY){
        int maxY=chunk.getMaxBuildHeight()-2;
        int minY=chunk.getMinBuildHeight();
        int waterTop=surfaceY;
        for(int y=surfaceY;
                y<=Math.min(surfaceY+30,maxY);y++){
            pos.set(bx,y,bz);
            if(isWaterBlock(chunk.getBlockState(pos)))
                waterTop=y;
            else break;}
        for(int y=surfaceY;
                y>=Math.max(surfaceY-30,minY);y--){
            pos.set(bx,y,bz);
            if(isWaterBlock(chunk.getBlockState(pos))){
                waterTop=Math.max(waterTop,y);break;}}
        return waterTop;}

    private boolean isWaterBlock(BlockState s){
        return s.is(Blocks.WATER)
            ||s.is(Blocks.KELP)
            ||s.is(Blocks.KELP_PLANT)
            ||s.is(Blocks.SEAGRASS)
            ||s.is(Blocks.TALL_SEAGRASS);}

    private boolean isWaterOrAir(BlockState s){
        return s.isAir()||isWaterBlock(s);}

    // ================================================================
    // COLOUR
    // ================================================================

    public static BlockState colourToBlock(
            String colour,boolean isRoof){
        if(colour==null||colour.isEmpty())
            return isRoof?ROOF_STONE_BRICK:BRICK;
        int[] rgb=parseColour(colour);
        if(rgb==null)
            return isRoof?ROOF_STONE_BRICK:BRICK;
        int r=rgb[0],g=rgb[1],b=rgb[2];
        double l=(r+g+b)/3.0;
        int mx=Math.max(r,Math.max(g,b));
        int df=mx-Math.min(r,Math.min(g,b));
        if(df<30){
            if(l>200)return WHITE_CONCRETE;
            if(l>160)return LIGHT_GRAY_CONCRETE;
            if(l>100)return GRAY_CONCRETE;
            return BLACK_CONCRETE;}
        if(r==mx){
            if(g>100&&b<80)return ORANGE_CONCRETE;
            if(g<80)return RED_CONCRETE;
            return LIGHT_GRAY_CONCRETE;}
        if(g==mx){
            if(r>150)return YELLOW_CONCRETE;
            return LIME_CONCRETE;}
        if(r>100&&g>100)return CYAN_CONCRETE;
        return LIGHT_GRAY_CONCRETE;}

    public static BlockState colourToTerracotta(
            String colour){
        if(colour==null||colour.isEmpty())
            return BROWN_TERRACOTTA;
        int[] rgb=parseColour(colour);
        if(rgb==null)return BROWN_TERRACOTTA;
        int r=rgb[0],g=rgb[1],b=rgb[2];
        double l=(r+g+b)/3.0;
        int mx=Math.max(r,Math.max(g,b));
        int df=mx-Math.min(r,Math.min(g,b));
        if(df<30){
            if(l>180)return WHITE_TERRACOTTA;
            if(l>120)return LIGHT_GRAY_TERRACOTTA;
            return BROWN_TERRACOTTA;}
        if(r==mx){
            if(g>100)return ORANGE_TERRACOTTA;
            return RED_TERRACOTTA;}
        if(g==mx&&r>150)return YELLOW_TERRACOTTA;
        return CYAN_TERRACOTTA;}

    private static int[] parseColour(String s){
        if(s==null)return null;
        s=s.trim().toLowerCase();
        return switch(s){
            case "white"->new int[]{255,255,255};
            case "black"->new int[]{0,0,0};
            case "red"->new int[]{255,0,0};
            case "orange"->new int[]{255,165,0};
            case "yellow"->new int[]{255,255,0};
            case "green"->new int[]{0,128,0};
            case "blue"->new int[]{0,0,255};
            case "brown"->new int[]{139,69,19};
            case "gray","grey"->new int[]{128,128,128};
            case "silver"->new int[]{192,192,192};
            case "beige"->new int[]{245,245,220};
            case "tan"->new int[]{210,180,140};
            case "brick"->new int[]{178,90,62};
            default->parseHexOrRgb(s);
        };}

    private static int[] parseHexOrRgb(String s){
        try{
            if(s.startsWith("#")){
                String h=s.substring(1);
                if(h.length()==3)
                    h=""+h.charAt(0)+h.charAt(0)
                    +h.charAt(1)+h.charAt(1)
                    +h.charAt(2)+h.charAt(2);
                if(h.length()==6){
                    int v=Integer.parseInt(h,16);
                    return new int[]{
                        (v>>16)&0xFF,
                        (v>>8)&0xFF,
                        v&0xFF};}}
            if(s.startsWith("rgb(")){
                String[] p=s.substring(4,
                    s.length()-1).split(",");
                return new int[]{
                    Integer.parseInt(p[0].trim()),
                    Integer.parseInt(p[1].trim()),
                    Integer.parseInt(p[2].trim())};}}
        catch(Exception ignored){}
        return null;}

    // ================================================================
    // BUILDING PLACEMENT
    // ================================================================

    private void placeBuilding(ChunkAccess chunk,
            BlockPos.MutableBlockPos pos,
            int bx,int bz,int surfaceY,
            OsmStructureIndex.OsmStructure s,
            double lat,double lon,
            List<OsmStructureIndex.OsmStructure> roads,
            List<OsmStructureIndex.OsmStructure> bridges){
        if(!s.containsPoint(lat,lon))return;
        int bh=hashOsmId(s.osmId);
        BuildingStyle style=
            (s.colour!=null&&!s.colour.isEmpty())
            ?colourStyle(s,bh)
            :determineBuildingStyle(s,bh);
        int layerOffset=elevatedBuildingOffset(s,bridges);
        if(s.layer<0)return;
        int baseY=surfaceY+layerOffset;
        int wallHeight=resolveWallHeight(s);
        int levels=estimateLevels(s);
        int maxY=chunk.getMaxBuildHeight()-2;
        int minBuild=chunk.getMinBuildHeight();

        // building=roof (361511089): a canopy — petrol station,
        // walkway or parking cover. Solid walls would make it a
        // sealed box; render a flat deck on corner posts instead.
        if("roof".equalsIgnoreCase(s.subType)){
            placeCanopy(chunk,pos,bx,bz,surfaceY,
                s,lat,lon,style,maxY);
            return;}

        if(layerOffset>0){
            double edgeD=edgeDistMeters(lat,lon,s);
            double cornerD=cornerDistMeters(lat,lon,s);
            for(int y=surfaceY;y<baseY;y++){
                if(y>maxY)break;
                pos.set(bx,y,bz);
                if(isRoadBlock(
                        chunk.getBlockState(pos)))
                    continue;
                setOwned(chunk, pos,
                    edgeD<WALL_THICKNESS_M
                    ?(cornerD<CORNER_PILLAR_M
                        ?style.cornerPillar():AIR)
                    :AIR,false);}}
        if(baseY-1>=minBuild){
            pos.set(bx,baseY-1,bz);
            setOwned(chunk, pos,
                style.foundation(),false);}

        double edgeDist=edgeDistMeters(lat,lon,s);
        double cornerDist=cornerDistMeters(lat,lon,s);
        boolean isCorner=cornerDist<CORNER_PILLAR_M
            &&edgeDist<WALL_THICKNESS_M;
        boolean isWall=!isCorner
            &&edgeDist<WALL_THICKNESS_M;
        boolean isInterior=
            edgeDist>=WALL_THICKNESS_M;
        int blockHash=
            TerraDensityFunctions.blockHash(bx,bz);
        int doorEdge=findDoorEdge(s,roads,bh);
        int stairEdge=findLongestEdge(s);

        placeWallColumn(chunk,pos,bx,bz,baseY,
            wallHeight,maxY,s,lat,lon,style,
            isCorner,isWall,isInterior,blockHash,
            doorEdge,stairEdge,levels);
        if(isInterior||isWall||isCorner)
            placeRoof(chunk,pos,bx,bz,baseY,
                wallHeight,maxY,s,lat,lon,style);
        for(int y=wallHeight+1;y<=wallHeight+5;y++){
            if(baseY+y>maxY)break;
            pos.set(bx,baseY+y,bz);
            if(!chunk.getBlockState(pos).isAir())
                setOwned(chunk, pos,AIR,false);}}

    // Flat covered deck for building=roof canopies: slab roof at
    // the tagged height (default one storey), corner posts only,
    // interior kept open so what it shelters stays visible.
    private void placeCanopy(ChunkAccess chunk,
            BlockPos.MutableBlockPos pos,
            int bx,int bz,int surfaceY,
            OsmStructureIndex.OsmStructure s,
            double lat,double lon,
            BuildingStyle style,int maxY){
        int h=s.resolvedHeightBlocks();
        if(h<=0)h=FLOOR_HEIGHT+1;   // typical canopy ~4-5m
        int roofY=surfaceY+h;
        if(roofY>maxY)return;
        double edgeD=edgeDistMeters(lat,lon,s);
        double cornerD=cornerDistMeters(lat,lon,s);
        // deck
        pos.set(bx,roofY,bz);
        setOwned(chunk, pos,style.roof(),false);
        // support posts at corners only
        if(cornerD<CORNER_PILLAR_M
                &&edgeD<WALL_THICKNESS_M)
            for(int y=surfaceY;y<roofY;y++){
                pos.set(bx,y,bz);
                if(chunk.getBlockState(pos).isAir())
                    setOwned(chunk, pos,
                        style.cornerPillar(),false);}
        // clear anything above the deck (trees etc.)
        for(int y=roofY+1;y<=roofY+3&&y<=maxY;y++){
            pos.set(bx,y,bz);
            if(!chunk.getBlockState(pos).isAir())
                setOwned(chunk, pos,AIR,false);}}

    private void placeBuildingPart(ChunkAccess chunk,
            BlockPos.MutableBlockPos pos,
            int bx,int bz,int surfaceY,
            OsmStructureIndex.OsmStructure s,
            double lat,double lon,
            List<OsmStructureIndex.OsmStructure> roads,
            List<OsmStructureIndex.OsmStructure> bridges){
        if(!s.containsPoint(lat,lon))return;
        int bh=hashOsmId(s.osmId);
        BuildingStyle style=
            (s.colour!=null&&!s.colour.isEmpty())
            ?colourStyle(s,bh)
            :determineBuildingStyle(s,bh);
        int layerOffset=elevatedBuildingOffset(s,bridges);
        if(s.layer<0)return;
        int minHeightBlocks=s.minHeightBlocks();
        int baseY=surfaceY+layerOffset
            +minHeightBlocks;
        int wallHeight=resolveWallHeight(s);
        int partHeight=Math.max(FLOOR_HEIGHT,
            wallHeight-minHeightBlocks);
        int levels=estimateLevels(s);
        int maxY=chunk.getMaxBuildHeight()-2;

        double edgeDist=edgeDistMeters(lat,lon,s);
        double cornerDist=cornerDistMeters(lat,lon,s);
        boolean isCorner=cornerDist<CORNER_PILLAR_M
            &&edgeDist<WALL_THICKNESS_M;
        boolean isWall=!isCorner
            &&edgeDist<WALL_THICKNESS_M;
        boolean isInterior=
            edgeDist>=WALL_THICKNESS_M;
        int blockHash=
            TerraDensityFunctions.blockHash(bx,bz);
        int doorEdge=findDoorEdge(s,roads,bh);
        int stairEdge=findLongestEdge(s);

        if(minHeightBlocks>0&&(isWall||isCorner))
            for(int y=surfaceY+layerOffset;
                    y<baseY;y++){
                if(y>maxY)break;
                pos.set(bx,y,bz);
                setOwned(chunk, pos,
                    style.wall(),false);}

        placeWallColumn(chunk,pos,bx,bz,baseY,
            partHeight,maxY,s,lat,lon,style,
            isCorner,isWall,isInterior,blockHash,
            doorEdge,stairEdge,levels);
        if(isInterior||isWall||isCorner)
            placeRoof(chunk,pos,bx,bz,baseY,
                partHeight,maxY,s,lat,lon,style);}
    // ================================================================
    // WALL COLUMN & STAIRS
    // ================================================================

    private void placeWallColumn(ChunkAccess chunk,
            BlockPos.MutableBlockPos pos,
            int bx,int bz,int baseY,int wallHeight,
            int maxY,OsmStructureIndex.OsmStructure s,
            double lat,double lon,BuildingStyle style,
            boolean isCorner,boolean isWall,
            boolean isInterior,int blockHash,
            int doorEdge,int stairEdge,int levels){

        if(isCorner){
            for(int y=0;y<wallHeight
                    &&baseY+y<=maxY;y++){
                pos.set(bx,baseY+y,bz);
                setOwned(chunk, pos,
                    style.cornerPillar(),false);}
        }else if(isWall){
            int closestEdge=
                findClosestEdge(lat,lon,s);
            boolean isDoorBlock=
                (closestEdge==doorEdge)
                &&isDoorPosition(lat,lon,s,
                    closestEdge);
            boolean hasWindow=!isDoorBlock
                &&isWindowPosition(lat,lon,s,
                    edgeDistMeters(lat,lon,s),
                    blockHash);
            for(int y=0;y<wallHeight
                    &&baseY+y<=maxY;y++){
                pos.set(bx,baseY+y,bz);
                int fl=y/FLOOR_HEIGHT;
                int yf=y%FLOOR_HEIGHT;
                if(isDoorBlock&&fl==0){
                    if(yf==1){
                        setOwned(chunk, pos,
                            style.doorLower(),false);
                        continue;}
                    if(yf==2){
                        setOwned(chunk, pos,
                            style.doorUpper(),false);
                        continue;}}
                if(hasWindow&&yf==2){
                    setOwned(chunk, pos,
                        style.windowBlock(),false);
                    continue;}
                if(hasWindow&&yf==3){
                    setOwned(chunk, pos,
                        style.windowBlock(),false);
                    continue;}
                if(yf==FLOOR_HEIGHT-1
                        &&config.buildingDetail>=2
                        &&fl<levels-1){
                    setOwned(chunk, pos,
                        style.wallAccent(),false);
                    continue;}
                setOwned(chunk, pos,
                    style.wall(),false);}
        }else if(isInterior){
            double len=edgeLengthMeters(s,stairEdge);
            double midAlong=len/2.0;
            int nA=stairEdge;
            int nB=(stairEdge+1)%s.nodes.length;
            double dEdgeM=OsmStructureIndex
                .OsmStructure.ptSegDist(lat,lon,
                    s.nodes[nA].lat,s.nodes[nA].lon,
                    s.nodes[nB].lat,s.nodes[nB].lon)
                *111320.0;
            double dAlongM=edgeParameter(lat,lon,s,
                stairEdge)*len;
            boolean isStairZone=
                (levels>=MIN_LEVELS_FOR_STAIRS)
                &&(dAlongM>=midAlong-1.0
                    &&dAlongM<=midAlong+1.0)
                &&(dEdgeM>=WALL_THICKNESS_M
                    &&dEdgeM<=WALL_THICKNESS_M+4.5);

            if(isStairZone){
                int stepIndex=Math.max(1,Math.min(4,
                    (int)Math.floor(
                        dEdgeM-WALL_THICKNESS_M)+1));
                for(int y=0;y<wallHeight
                        &&baseY+y<=maxY;y++){
                    pos.set(bx,baseY+y,bz);
                    int fl=y/FLOOR_HEIGHT;
                    int yf=y%FLOOR_HEIGHT;
                    if(fl>=levels){
                        setOwned(chunk, pos,
                            AIR,false);continue;}
                    if(yf==0){
                        setOwned(chunk, pos,
                            fl==0?style.floor():AIR,
                            false);
                    }else if(yf==stepIndex
                            &&fl<levels-1){
                        setOwned(chunk, pos,
                            style.stepBlock(),false);
                    }else{
                        setOwned(chunk, pos,
                            AIR,false);}}
            }else{
                for(int y=0;y<wallHeight
                        &&baseY+y<=maxY;y++){
                    pos.set(bx,baseY+y,bz);
                    setOwned(chunk, pos,
                        (y%FLOOR_HEIGHT==0)
                        ?style.floor():AIR,false);}
                if(config.buildingDetail>=2
                        &&blockHash%12==0)
                    for(int floor=0;
                            floor<levels;floor++){
                        int lightY=baseY
                            +floor*FLOOR_HEIGHT
                            +FLOOR_HEIGHT-1;
                        if(lightY<baseY+wallHeight
                                &&lightY<=maxY){
                            pos.set(bx,lightY,bz);
                            if(chunk.getBlockState(pos)
                                    .isAir())
                                setOwned(chunk, pos,
                                    style.interiorLight(),
                                    false);}}}}}

    // ================================================================
    // ROOF
    // ================================================================

    private void placeRoof(ChunkAccess chunk,
            BlockPos.MutableBlockPos pos,
            int bx,int bz,int baseY,int wallHeight,
            int maxY,OsmStructureIndex.OsmStructure s,
            double lat,double lon,BuildingStyle style){
        if(!s.containsPoint(lat,lon))return;
        int roofBaseY=baseY+wallHeight;
        int roofH=s.roofHeightBlocks();
        String shape=s.roofShape!=null
            ?s.roofShape.toLowerCase():"flat";
        switch(shape){
            case "pyramidal"->placeRoofPyramidal(
                chunk,pos,bx,bz,roofBaseY,roofH,
                maxY,s,lat,lon,style);
            case "gabled"->placeRoofGabled(
                chunk,pos,bx,bz,roofBaseY,roofH,
                maxY,s,lat,lon,style);
            case "hipped"->placeRoofHipped(
                chunk,pos,bx,bz,roofBaseY,roofH,
                maxY,s,lat,lon,style);
            case "dome"->placeRoofDome(
                chunk,pos,bx,bz,roofBaseY,roofH,
                maxY,s,lat,lon,style);
            case "onion"->placeRoofOnion(
                chunk,pos,bx,bz,roofBaseY,roofH,
                maxY,s,lat,lon,style);
            case "mansard"->placeRoofMansard(
                chunk,pos,bx,bz,roofBaseY,roofH,
                maxY,s,lat,lon,style);
            default->{
                if(roofBaseY<=maxY){
                    pos.set(bx,roofBaseY,bz);
                    setOwned(chunk, pos,
                        style.roof(),false);}}}}

    private void placeRoofPyramidal(ChunkAccess c,BlockPos.MutableBlockPos p,int bx,int bz,int rb,int rh,int my,OsmStructureIndex.OsmStructure s,double lat,double lon,BuildingStyle st){double ed=edgeDistMeters(lat,lon,s);double mi=Math.max(1,maxInsetMeters(s));int rise=(int)Math.round(Math.min(1.0,ed/mi)*rh);int ry=rb+rise;if(ry<=my){p.set(bx,ry,bz);setOwned(c, p,st.roof(),false);}}
    private void placeRoofGabled(ChunkAccess c,BlockPos.MutableBlockPos p,int bx,int bz,int rb,int rh,int my,OsmStructureIndex.OsmStructure s,double lat,double lon,BuildingStyle st){double cl=(s.minLat+s.maxLat)/2.0;double ls=(s.maxLat-s.minLat)*111320.0;double ls2=(s.maxLon-s.minLon)*111320.0*Math.cos(Math.toRadians(cl));double hw,pd;if(ls>=ls2){hw=ls2/2.0;pd=Math.abs(lon-(s.minLon+s.maxLon)/2.0)*111320.0*Math.cos(Math.toRadians(lat));}else{hw=ls/2.0;pd=Math.abs(lat-(s.minLat+s.maxLat)/2.0)*111320.0;}hw=Math.max(1,hw);int rise=(int)Math.round((1.0-Math.min(1.0,pd/hw))*rh);int ry=rb+rise;if(ry<=my){p.set(bx,ry,bz);setOwned(c, p,st.roof(),false);}}
    private void placeRoofHipped(ChunkAccess c,BlockPos.MutableBlockPos p,int bx,int bz,int rb,int rh,int my,OsmStructureIndex.OsmStructure s,double lat,double lon,BuildingStyle st){double cl=(s.minLat+s.maxLat)/2.0,cln=(s.minLon+s.maxLon)/2.0;double hlt=Math.max(1,(s.maxLat-s.minLat)*111320.0/2.0);double hln=Math.max(1,(s.maxLon-s.minLon)*111320.0*Math.cos(Math.toRadians(cl))/2.0);double flt=Math.min(1,Math.abs(lat-cl)*111320.0/hlt);double fln=Math.min(1,Math.abs(lon-cln)*111320.0*Math.cos(Math.toRadians(lat))/hln);int rise=(int)Math.round((1.0-Math.min(flt,fln))*rh);int ry=rb+rise;if(ry<=my){p.set(bx,ry,bz);setOwned(c, p,st.roof(),false);}}
    private void placeRoofDome(ChunkAccess c,BlockPos.MutableBlockPos p,int bx,int bz,int rb,int rh,int my,OsmStructureIndex.OsmStructure s,double lat,double lon,BuildingStyle st){double cl=(s.minLat+s.maxLat)/2.0,cln=(s.minLon+s.maxLon)/2.0;double R=Math.max(1,Math.min((s.maxLat-s.minLat)*111320.0,(s.maxLon-s.minLon)*111320.0*Math.cos(Math.toRadians(cl)))/2.0);double dL=(lat-cl)*111320.0,dN=(lon-cln)*111320.0*Math.cos(Math.toRadians(lat));double r=Math.sqrt(dL*dL+dN*dN);if(r>R)return;int rise=(int)Math.round(Math.sqrt(Math.max(0,1.0-(r*r)/(R*R)))*rh);int ry=rb+rise;if(ry<=my){p.set(bx,ry,bz);setOwned(c, p,st.roof(),false);}}
    private void placeRoofOnion(ChunkAccess c,BlockPos.MutableBlockPos p,int bx,int bz,int rb,int rh,int my,OsmStructureIndex.OsmStructure s,double lat,double lon,BuildingStyle st){double cl=(s.minLat+s.maxLat)/2.0,cln=(s.minLon+s.maxLon)/2.0;double R=Math.max(1,Math.min((s.maxLat-s.minLat)*111320.0,(s.maxLon-s.minLon)*111320.0*Math.cos(Math.toRadians(cl)))/2.0);double dL=(lat-cl)*111320.0,dN=(lon-cln)*111320.0*Math.cos(Math.toRadians(lat));double r=Math.sqrt(dL*dL+dN*dN);if(r>R)return;int rise=(int)Math.round(Math.sin((r/R)*Math.PI)*rh);int ry=rb+rise;if(ry<=my){p.set(bx,ry,bz);setOwned(c, p,st.roof(),false);}}
    private void placeRoofMansard(ChunkAccess c,BlockPos.MutableBlockPos p,int bx,int bz,int rb,int rh,int my,OsmStructureIndex.OsmStructure s,double lat,double lon,BuildingStyle st){double ed=edgeDistMeters(lat,lon,s);double mi=Math.max(1,maxInsetMeters(s));double fr=ed/mi;int rise=fr>=0.4?rh:(int)Math.round((fr/0.4)*rh);int ry=rb+rise;if(ry<=my){p.set(bx,ry,bz);setOwned(c, p,st.roof(),false);}}

    // ================================================================
    // BRIDGE DISPATCHER
    // ================================================================

    private void placeBridge(ChunkAccess chunk,
            BlockPos.MutableBlockPos pos,
            int bx,int bz,int sy,
            OsmStructureIndex.OsmStructure s,
            double lat,double lon,
            List<OsmStructureIndex.OsmStructure> roads,
            List<OsmStructureIndex.OsmStructure>
            railways,
            List<OsmStructureIndex.OsmStructure>
            allBridges){
        double hw=(s.explicitWidth>0
            ?Math.min(s.explicitWidth/2.0,30.0)
            :roadHW(
                s.subType!=null?s.subType:"secondary",
                s.lanes))*config.roadWidthScale;
        double dist=s.distanceToLine(lat,lon);
        if(dist>hw+2.0)return;

        int maxY=chunk.getMaxBuildHeight()-2;
        double totalLen=bridgeTotalLength(s);
        double dfnStart=distAlongLine(lat,lon,s,true);
        double dfnEnd=distAlongLine(lat,lon,s,false);
        double dfn=Math.min(dfnStart,dfnEnd);

        dfn=getAdjustedDistFromEnd(dfn,s,allBridges);

        int maxRise=s.layer>0
            ?s.layer*BRIDGE_HEIGHT:BRIDGE_HEIGHT;
        int deckRise;
        // ── ENDPOINT ANCHORS (all bridges) ───────────────────
        // A way end sharing a node with another elevated bridge
        // is a CONTINUATION or JUNCTION, not a place to ramp
        // down. Three cases from real Hyderabad Metro data:
        //  - segmented viaduct (Line 3 = many ways, same layer):
        //    without anchors every segment boundary dipped the
        //    deck to the ground and short (<10 m) segments
        //    dropped to rise 0 entirely;
        //  - connector ramp 850869297 (no layer): interpolate
        //    between the two anchor decks (16b -> 32b);
        //  - genuine bridge end (no shared node): ramp to
        //    ground as always.
        int[] anchors=connectorAnchors(s,allBridges);
        boolean aS=anchors[0]>=0,aE=anchors[1]>=0;
        if(s.layer<=0&&(aS||aE)){
            // unlayered connector: deck interpolates between
            // anchor heights (unanchored end ramps from ground)
            int rStart=Math.max(0,anchors[0]);
            int rEnd=Math.max(0,anchors[1]);
            double t=totalLen>0
                ?Math.max(0,Math.min(1,
                    dfnStart/totalLen)):0;
            deckRise=(int)Math.round(
                rStart+(rEnd-rStart)*t);
            maxRise=Math.max(Math.max(rStart,rEnd),
                BRIDGE_HEIGHT);
            if(crossLogged.add(s.osmId*31))
                LOGGER.info("CONNECTOR BRIDGE: {} "
                    +"(no layer) anchored {}b -> {}b "
                    +"via shared nodes — deck "
                    +"interpolated between decks",
                    s.osmId,rStart,rEnd);
        }else if(aS&&aE){
            // continuation segment: full rise end to end,
            // even for tiny (<10 m) segments
            deckRise=maxRise;
            if(crossLogged.add(s.osmId*31))
                LOGGER.info("BRIDGE CONTINUATION: {} "
                    +"anchored at both ends — no "
                    +"mid-viaduct dip",s.osmId);
        }else{
            // ramp only at UNANCHORED ends
            double dS=aS?Double.MAX_VALUE:dfnStart;
            double dE=aE?Double.MAX_VALUE:dfnEnd;
            double dfnEff=Math.min(dS,dE);
            if(dfnEff==Double.MAX_VALUE)dfnEff=totalLen;
            dfnEff=getAdjustedDistFromEnd(
                dfnEff,s,allBridges);
            deckRise=computeDeckRise(
                dfnEff,Math.max(totalLen,10.5),maxRise);
            if((aS||aE)&&crossLogged.add(s.osmId*31))
                LOGGER.info("BRIDGE CONTINUATION: {} "
                    +"anchored at {} end — ramp only "
                    +"at the free end",s.osmId,
                    aS?"start":"end");
        }
        int deckY=sy+deckRise;
        int deckBase=deckY-1;
        if(deckY>maxY)return;

        // ── CROSSING PROTECTION ───────────────────────────────
        // The corridor pre-clear below removes "stale" building/
        // road blocks — but a CROSSING bridge's deck IS road+
        // smoothstone, so a layer-2 bridge was slicing the
        // layer-1 deck out of the overlap (and the lower bridge
        // was cutting the upper one's pillars). Compute the deck
        // bands of every other bridge crossing this column and
        // treat them as untouchable. Endpoint merges were already
        // handled (LAYER_END_SEPARATION_M); this covers mid-span
        // crossings like green-over-blue at Parade Ground.
        List<int[]> guard=new ArrayList<>();
        List<Integer> rises=new ArrayList<>();
        boolean higherCrossing=false;
        if(allBridges!=null)
            for(OsmStructureIndex.OsmStructure b:
                    allBridges){
                if(b.osmId==s.osmId)continue;
                double bHw=(b.explicitWidth>0
                    ?Math.min(b.explicitWidth/2.0,30.0)
                    :roadHW(b.subType!=null
                        ?b.subType:"secondary",
                        b.lanes))*config.roadWidthScale;
                if(b.distanceToLine(lat,lon)>bHw+1.5)
                    continue;
                int bRise;
                if(b.layer>0){
                    bRise=b.layer*BRIDGE_HEIGHT;
                }else{
                    // Unlayered crossing bridge may be a
                    // connector ramp — its deck here is the
                    // anchor interpolation, not the default 8.
                    int[] bAnch=connectorAnchors(b,allBridges);
                    if(bAnch[0]>=0||bAnch[1]>=0){
                        double bLen=bridgeTotalLength(b);
                        double bt=bLen>0
                            ?Math.max(0,Math.min(1,
                                distAlongLine(lat,lon,b,true)
                                    /bLen)):0;
                        bRise=(int)Math.round(
                            Math.max(0,bAnch[0])
                            +(Math.max(0,bAnch[1])
                                -Math.max(0,bAnch[0]))*bt);
                    }else{
                        bRise=BRIDGE_HEIGHT;
                    }
                }
                // deckBase-1 .. deck+rail/railing headroom
                guard.add(new int[]{
                    sy+bRise-2,sy+bRise+2});
                rises.add(bRise);
                if(bRise>deckRise)higherCrossing=true;
                if(crossLogged.add(
                        Math.min(s.osmId,b.osmId)*31
                        +Math.max(s.osmId,b.osmId)))
                    LOGGER.info("BRIDGE CROSSING: {} "
                        +"(layer={}) and {} (layer={}) "
                        +"share a column — decks "
                        +"mutually protected",
                        s.osmId,s.layer,
                        b.osmId,b.layer);
            }
        deckGuard.set(guard);
        crossingRises.set(rises);
        try{

        if(deckRise>0)
            for(int y=sy;y<=deckY+6;y++){
                if(y>maxY)break;
                if(inGuardBands(y,guard))continue;
                pos.set(bx,y,bz);
                BlockState existing=
                    chunk.getBlockState(pos);
                // A higher bridge crosses here: its stone-brick
                // pillars pass through our corridor — spare them.
                if(higherCrossing
                        &&existing.is(Blocks.STONE_BRICKS))
                    continue;
                if(isBuildingBlock(existing))
                    setOwned(chunk, pos,AIR,false);
                else if(isRoadBlock(existing))
                    setOwned(chunk, pos,AIR,false);}

        int towerH=dynamicTowerHeight(totalLen);
        int pillarSpacing=
            dynamicPillarSpacing(totalLen);
        double archSpan=dynamicArchSpan(totalLen);

        BridgeStructure bs=s.bridgeStructure;
        switch(bs){
            case SUSPENSION->{
                fillRampSolid(chunk,pos,bx,bz,sy,deckBase,
                    deckRise,maxRise);
                boolean used=BridgeGeometryTest.placeOsmWayColumn(
                    osmGeomWriter(chunk,pos,bx,bz),
                    BridgeGeometryTest.BridgeType.SUSPENSION,
                    bx,bz,
                    pillarFootingY(chunk,pos,bx,bz,sy),
                    sy,deckY,
                    s.nodes,lat,lon,hw,
                    // towers/cables: man_made=bridge outline only
                    "bridge".equals(s.subType),
                    !"bridge".equals(s.subType),
                    // null = outline pylon offset. For the
                    // outline, pin pylons to carriageway
                    // parapets so they are not in the lanes.
                    "bridge".equals(s.subType)
                        ?carriagewayParapet(lat,lon,s,allBridges)
                        :null);
                if(!used)
                    placeBridgeSuspension(chunk,pos,bx,bz,sy,
                        deckY,deckBase,dist,hw,totalLen,dfn,
                        maxRise,deckRise,maxY,s,lat,lon,roads,
                        railways,towerH,pillarSpacing);
                else if(geomLogged.add(s.osmId))
                    LOGGER.info("BRIDGE GEOM: {} '{}' "
                        +"SUSPENSION via BridgeGeometryTest "
                        +"nodes={} length={}m",
                        s.osmId,
                        s.name!=null?s.name:"?",
                        s.nodes.length,
                        (int)Math.round(totalLen));}
            case CABLE_STAYED->placeBridgeCableStayed(chunk,pos,bx,bz,sy,deckY,deckBase,dist,hw,totalLen,dfn,maxRise,deckRise,maxY,s,lat,lon,roads,railways,towerH,pillarSpacing);
            case ARCH->placeBridgeArch(chunk,pos,bx,bz,sy,deckY,deckBase,dist,hw,totalLen,dfn,maxRise,deckRise,maxY,s,lat,lon,roads,railways,archSpan,pillarSpacing);
            case TRUSS->placeBridgeTruss(chunk,pos,bx,bz,sy,deckY,deckBase,dist,hw,totalLen,dfn,maxRise,deckRise,maxY,s,lat,lon,roads,railways,pillarSpacing);
            case VIADUCT->placeBridgeViaduct(chunk,pos,bx,bz,sy,deckY,deckBase,dist,hw,totalLen,dfn,maxRise,deckRise,maxY,s,lat,lon,roads,railways,pillarSpacing);
            case PEDESTRIAN->placeBridgePedestrian(chunk,pos,bx,bz,sy,deckY,deckBase,dist,hw,totalLen,dfn,maxRise,deckRise,maxY,s,lat,lon);
            case ROPE->placeBridgeRope(chunk,pos,bx,bz,sy,deckY,deckBase,dist,hw,totalLen,dfn,maxRise,deckRise,maxY,s,lat,lon);
            case AQUEDUCT->placeBridgeAqueduct(chunk,pos,bx,bz,sy,deckY,deckBase,dist,hw,totalLen,dfn,maxRise,deckRise,maxY,s,lat,lon,pillarSpacing);
            case MOVABLE->placeBridgeMovable(chunk,pos,bx,bz,sy,deckY,deckBase,dist,hw,totalLen,dfn,maxRise,deckRise,maxY,s,lat,lon,roads,railways,towerH);
            case BOARDWALK->placeBridgeBoardwalk(chunk,pos,bx,bz,sy,deckY,deckBase,dist,hw,totalLen,dfn,maxRise,deckRise,maxY,s,lat,lon);
            case CANTILEVER->placeBridgeCantilever(chunk,pos,bx,bz,sy,deckY,deckBase,dist,hw,totalLen,dfn,maxRise,deckRise,maxY,s,lat,lon,roads,railways,pillarSpacing);
            case COVERED->placeBridgeCovered(chunk,pos,bx,bz,sy,deckY,deckBase,dist,hw,totalLen,dfn,maxRise,deckRise,maxY,s,lat,lon,roads,railways,pillarSpacing);
            case LOW_WATER_CROSSING->placeBridgeLowWaterCrossing(chunk,pos,bx,bz,sy,dist,hw,maxY,s,lat,lon);
            case TRESTLE->placeBridgeTrestle(chunk,pos,bx,bz,sy,deckY,deckBase,dist,hw,totalLen,dfn,maxRise,deckRise,maxY,s,lat,lon,roads,railways,pillarSpacing);
            case FLOATING->placeBridgeFloating(chunk,pos,bx,bz,sy,dist,hw,totalLen,dfn,maxY,s,lat,lon);
            case SIMPLE_SUSPENSION->placeBridgeSimpleSuspension(chunk,pos,bx,bz,sy,deckY,deckBase,dist,hw,totalLen,dfn,maxRise,deckRise,maxY,s,lat,lon,towerH);
            case HUMPBACK->placeBridgeHumpback(chunk,pos,bx,bz,sy,deckY,deckBase,dist,hw,totalLen,dfn,maxRise,deckRise,maxY,s,lat,lon,roads,railways,pillarSpacing);
            case CLAPPER->placeBridgeClapper(chunk,pos,bx,bz,sy,deckY,deckBase,dist,hw,totalLen,dfn,maxRise,deckRise,maxY,s,lat,lon);
            default->placeBridgeBeam(chunk,pos,bx,bz,sy,deckY,deckBase,dist,hw,totalLen,dfn,maxRise,deckRise,maxY,s,lat,lon,roads,railways,pillarSpacing,allBridges);}
        }finally{deckGuard.remove();crossingRises.remove();}}

    // ── CROSSING GUARD STATE ──────────────────────────────────
    // Deck bands (y ranges) of OTHER bridges crossing the column
    // currently being placed. Set by placeBridge, honoured by
    // clearAboveDeck so neither deck erases the other.
    private final ThreadLocal<List<int[]>> deckGuard =
        ThreadLocal.withInitial(ArrayList::new);
    /** Rises (blocks above ground) of bridges crossing the
     *  current column — lets pillar placement dodge lower decks. */
    private final ThreadLocal<List<Integer>> crossingRises =
        ThreadLocal.withInitial(ArrayList::new);
    private final Set<Long> crossLogged =
        java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final Set<Long> geomLogged =
        java.util.concurrent.ConcurrentHashMap.newKeySet();

    private BridgeGeometryTest.BlockWriter osmGeomWriter(
            ChunkAccess chunk,
            BlockPos.MutableBlockPos pos,
            int bx, int bz){
        return new BridgeGeometryTest.BlockWriter(){
            @Override public void set(int x,int y,int z,
                    BlockState state){
                if(x!=bx||z!=bz)return;
                if(y<chunk.getMinBuildHeight()
                        ||y>=chunk.getMaxBuildHeight())
                    return;
                pos.set(x,y,z);
                setOwned(chunk,pos,state,false);}
            @Override public int minY(){
                return chunk.getMinBuildHeight();}
            @Override public int maxY(){
                return chunk.getMaxBuildHeight();}};
    }

    private static boolean inGuardBands(
            int y,List<int[]> bands){
        for(int[] b:bands)
            if(y>=b[0]&&y<=b[1])return true;
        return false;}

    // ── CONNECTOR BRIDGES (interchange ramps) ─────────────────
    // Way 850869297: bridge, railway=subway, service=siding, NO
    // layer tag — but its first node sits ON Line 3 (layer=2) and
    // its last node ON Line 2 (layer=4). It is the ramp between
    // the two decks. Untagged it defaulted to rise 8: below both,
    // connected to neither. Evidence rule again — the deck height
    // comes from the bridges it TOUCHES: anchor each endpoint to
    // the layered bridge sharing that node, then interpolate the
    // deck linearly along the way (+16 at one end -> +32 at the
    // other). An endpoint with no anchor ramps from ground (0).
    private final java.util.concurrent.ConcurrentHashMap<Long,int[]>
        connectorAnchorCache =
            new java.util.concurrent.ConcurrentHashMap<>();

    private int[] connectorAnchors(
            OsmStructureIndex.OsmStructure s,
            List<OsmStructureIndex.OsmStructure> allBridges){
        int[] c=connectorAnchorCache.get(s.osmId);
        if(c!=null)return c;
        int[] r=new int[]{
            endpointAnchorRise(s.startNode(),s,allBridges),
            endpointAnchorRise(s.endNode(),s,allBridges)};
        // RUN-10 FIX: cache only COMPLETE results. An anchor can
        // be "missing" merely because the neighbour bridge is not
        // in this chunk's local list yet — 850869297 got 16b->0b
        // frozen because Line 2 (611277268) arrived two minutes
        // later. Absence is PENDING, never truth: incomplete
        // results are recomputed until both ends resolve.
        if(r[0]>=0&&r[1]>=0)
            connectorAnchorCache.put(s.osmId,r);
        return r;}

    /** Rise (blocks) of another bridge sharing this node's
     *  coordinates, or -1. The shared node may be ANYWHERE in the
     *  other way (junctions are usually mid-way). Unlayered
     *  neighbours anchor at the default deck height, so ordinary
     *  road bridges split into several ways stay level across
     *  segment boundaries too. */
    private int endpointAnchorRise(
            OsmStructureIndex.OsmNode n,
            OsmStructureIndex.OsmStructure s,
            List<OsmStructureIndex.OsmStructure> allBridges){
        if(n==null||allBridges==null)return -1;
        final double EPS=2e-6; // ~0.2 m: same OSM node
        int best=-1;
        for(OsmStructureIndex.OsmStructure b:allBridges){
            if(b.osmId==s.osmId)continue;
            if(n.lat<b.minLat-EPS||n.lat>b.maxLat+EPS
                ||n.lon<b.minLon-EPS||n.lon>b.maxLon+EPS)
                continue;
            for(OsmStructureIndex.OsmNode bn:b.nodes)
                if(Math.abs(bn.lat-n.lat)<EPS
                        &&Math.abs(bn.lon-n.lon)<EPS){
                    best=Math.max(best,
                        b.layer>0
                            ?b.layer*BRIDGE_HEIGHT
                            :BRIDGE_HEIGHT);
                    break;}
        }
        return best;}

    // ================================================================
    // BRIDGE COMMON HELPERS
    // ================================================================

    private void placeDeckSurface(ChunkAccess chunk,BlockPos.MutableBlockPos pos,int bx,int bz,int deckY,int maxY,OsmStructureIndex.OsmStructure s,double dist,double hw){if(deckY>maxY)return;pos.set(bx,deckY,bz);if(s.isRailBridge){setOwned(chunk, pos,ROAD_GRAVEL,false);if(dist<0.8&&deckY+1<=maxY){pos.set(bx,deckY+1,bz);setOwned(chunk, pos,RAIL,false);}}else{setOwned(chunk, pos,roadSurf(s.subType,s.surface),false);if(config.buildingDetail>=2&&isMain(s.subType)&&markingsExistNow()){if(dist<0.3){pos.set(bx,deckY,bz);setOwned(chunk, pos,ROAD_MARKING_YELLOW,false);}else if(Math.abs(dist-hw)<0.4){pos.set(bx,deckY,bz);setOwned(chunk, pos,ROAD_MARKING_WHITE,false);}}}}
    private void clearAboveDeck(ChunkAccess chunk,BlockPos.MutableBlockPos pos,int bx,int bz,int deckY,int maxY,boolean isRail,int clearHeight){
        // Headroom carve for THIS deck — but a crossing bridge's
        // deck band overhead is not "obstruction", it is the other
        // bridge. Skip guarded y-bands (see placeBridge).
        List<int[]> guard=deckGuard.get();
        int from=isRail?deckY+2:deckY+1;
        for(int h=from;h<=deckY+clearHeight;h++){
            if(h>maxY)break;
            if(inGuardBands(h,guard))continue;
            pos.set(bx,h,bz);
            if(!chunk.getBlockState(pos).isAir())
                setOwned(chunk, pos,AIR,false);}}
    private void fillRampSolid(ChunkAccess chunk,BlockPos.MutableBlockPos pos,int bx,int bz,int sy,int deckBase,int deckRise,int maxRise){if(deckRise>0&&deckRise<maxRise)for(int y=sy;y<deckBase;y++){pos.set(bx,y,bz);BlockState e=chunk.getBlockState(pos);if(e.isAir())setOwned(chunk, pos,y<=0?Blocks.DEEPSLATE.defaultBlockState():Blocks.STONE.defaultBlockState(),false);}}
    private void placeStandardRailing(ChunkAccess chunk,BlockPos.MutableBlockPos pos,int bx,int bz,int sy,int deckY,int deckBase,int maxY,int deckRise,BlockState railingBlock,BlockState deckBlock){if(deckRise>=2){if(deckBase>sy&&deckBase<=maxY){pos.set(bx,deckBase,bz);setOwned(chunk, pos,deckBlock,false);}if(deckY<=maxY){pos.set(bx,deckY,bz);setOwned(chunk, pos,deckBlock,false);}if(deckY+1<=maxY){pos.set(bx,deckY+1,bz);setOwned(chunk, pos,railingBlock,false);}}else{pos.set(bx,sy,bz);setOwned(chunk, pos,SIDEWALK,false);clearSafe(chunk,pos,bx,bz,sy,1);}}
    private boolean isPillarBlocked(double lat,double lon,OsmStructureIndex.OsmStructure s,List<OsmStructureIndex.OsmStructure> roads,List<OsmStructureIndex.OsmStructure> railways){
        for(OsmStructureIndex.OsmStructure road:roads){if(road.osmId==s.osmId)continue;if(road.distanceToLine(lat,lon)<=roadHW(road.subType,road.lanes)*config.roadWidthScale)return true;}
        for(OsmStructureIndex.OsmStructure rail:railways){if(rail.osmId==s.osmId)continue;if(rail.distanceToLine(lat,lon)<=2.5)return true;}
        // A pillar may not land on a LOWER crossing bridge's deck
        // (upper metro line's pillar punching through the lower
        // line at an interchange). Real engineering puts the pier
        // beside the lower deck; we simply skip that column — the
        // next spacing slot lands clear of the crossing.
        int ownRise=s.layer>0?s.layer*BRIDGE_HEIGHT:BRIDGE_HEIGHT;
        for(int r:crossingRises.get())
            if(r<ownRise)return true;
        return false;}
    private int pillarFootingY(ChunkAccess chunk,BlockPos.MutableBlockPos pos,int bx,int bz,int fromY){int minY=chunk.getMinBuildHeight();int y=fromY;while(y>minY){pos.set(bx,y-1,bz);if(!isWaterOrAir(chunk.getBlockState(pos)))break;y--;}return y;}
    private void placeThickPillar(ChunkAccess chunk,BlockPos.MutableBlockPos pos,int bx,int bz,int sy,int deckBase,int maxY,BlockState pillarBlock,boolean isMajor){int foot=pillarFootingY(chunk,pos,bx,bz,sy);for(int y=foot;y<=deckBase;y++){pos.set(bx,y,bz);if(isWaterOrAir(chunk.getBlockState(pos)))setOwned(chunk, pos,pillarBlock,false);}if(isMajor){int cx2=chunk.getPos().getMinBlockX(),cz2=chunk.getPos().getMinBlockZ();for(int dx=-1;dx<=0;dx++)for(int dz=-1;dz<=0;dz++){if(dx==0&&dz==0)continue;int nx=bx+dx,nz=bz+dz;int lx=nx-cx2,lz=nz-cz2;if(lx<0||lx>=16||lz<0||lz>=16)continue;int nFoot=pillarFootingY(chunk,pos,nx,nz,sy);for(int y=nFoot;y<=deckBase;y++){pos.set(nx,y,nz);if(isWaterOrAir(chunk.getBlockState(pos)))setOwned(chunk, pos,pillarBlock,false);}}}}
    private void placeStandardPillars(ChunkAccess chunk,BlockPos.MutableBlockPos pos,int bx,int bz,int sy,int deckBase,int maxY,double dist,double hw,double totalLen,int deckRise,int maxRise,OsmStructureIndex.OsmStructure s,double lat,double lon,List<OsmStructureIndex.OsmStructure> roads,List<OsmStructureIndex.OsmStructure> railways,int pillarSpacing,BlockState pillarBlock){if(deckRise!=maxRise||totalLen<=10.0)return;if(Math.abs(dist-hw)>1.2)return;double along=distAlongLine(lat,lon,s,true);if((along%pillarSpacing)>=1.5)return;if(isPillarBlocked(lat,lon,s,roads,railways))return;int pilH=deckBase-sy;if(pilH<dynamicMinPillarHeight(deckBase,sy))return;placeThickPillar(chunk,pos,bx,bz,sy,deckBase,maxY,pillarBlock,false);}
    /**
     * Default beam pier. Three parts, all below the road:
     *   footing  — cobble pad, slightly wider than the deck
     *   shaft    — stone-brick legs at the edges (and centre
     *              on wide decks), never in the driving lane
     *   cap      — polished-andesite hammerhead under the
     *              full deck width
     */
    private void placeBeamPier(
            ChunkAccess chunk,
            BlockPos.MutableBlockPos pos,
            int bx,int bz,int sy,int deckBase,int maxY,
            double dist,double hw,double totalLen,
            int deckRise,int maxRise,
            OsmStructureIndex.OsmStructure s,
            double lat,double lon,
            List<OsmStructureIndex.OsmStructure> roads,
            List<OsmStructureIndex.OsmStructure> railways,
            int pillarSpacing){
        if(deckRise!=maxRise||totalLen<=10.0)return;
        if(dist>hw+1.2)return;
        int span=Math.max(12,pillarSpacing);
        double along=distAlongLine(lat,lon,s,true);
        if((along%span)>=2.0)return;
        if(isPillarBlocked(lat,lon,s,roads,railways))return;
        int pilH=deckBase-sy;
        if(pilH<dynamicMinPillarHeight(deckBase,sy))return;
        int foot=pillarFootingY(chunk,pos,bx,bz,sy);
        int capTop=Math.min(maxY,deckBase);
        int capBot=Math.max(foot,deckBase-1);
        boolean onDeck=dist<=hw+0.05;
        boolean shaft=dist<0.9
            ||Math.abs(dist-hw)<1.2
            ||(hw>=5.0&&Math.abs(dist-hw*0.5)<0.9);
        if(dist<=hw+1.2){
            int fTop=Math.min(foot+1,capBot);
            for(int y=foot;y<=fTop;y++){
                pos.set(bx,y,bz);
                if(isWaterOrAir(chunk.getBlockState(pos)))
                    setOwned(chunk,pos,COBBLESTONE,false);}}
        if(shaft&&onDeck){
            int shaftTop=capBot-1;
            for(int y=foot+2;y<=shaftTop;y++){
                pos.set(bx,y,bz);
                if(isWaterOrAir(chunk.getBlockState(pos)))
                    setOwned(chunk,pos,BRIDGE_PILLAR,false);}}
        if(onDeck){
            for(int y=capBot;y<=capTop;y++){
                pos.set(bx,y,bz);
                setOwned(chunk,pos,POLISHED_ANDESITE,false);}}}

    // ================================================================
    // ALL BRIDGE TYPES
    // ================================================================

    /** True when a man_made=bridge outline (suspension / cable-
     *  stayed) already owns this column. Carriageway beam piers
     *  must not stand in the lake next to those pylons. */
    private boolean specialSpanOwnsSupports(
            double lat,double lon,
            OsmStructureIndex.OsmStructure s,
            List<OsmStructureIndex.OsmStructure> allBridges){
        if(allBridges==null)return false;
        for(OsmStructureIndex.OsmStructure b:allBridges){
            if(b.osmId==s.osmId)continue;
            if(!"bridge".equals(b.subType))continue;
            if(b.bridgeStructure!=BridgeStructure.SUSPENSION
                    &&b.bridgeStructure!=BridgeStructure.CABLE_STAYED)
                continue;
            double bHw=b.explicitWidth>0
                ?Math.min(b.explicitWidth/2.0,30.0)
                :20.0;
            if(b.distanceToLine(lat,lon)<=bHw+2.0)
                return true;}
        return false;}
    /**
     * True when this column is on a highway-bridge parapet
     * and not in the traffic interior. False = skip pylons.
     * null = no carriageway nearby, use outline offset.
     */
    private Boolean carriagewayParapet(
            double lat,double lon,
            OsmStructureIndex.OsmStructure outline,
            List<OsmStructureIndex.OsmStructure> allBridges){
        if(allBridges==null)return null;
        boolean saw=false, edge=false, interior=false;
        for(OsmStructureIndex.OsmStructure b:allBridges){
            if(b.osmId==outline.osmId)continue;
            if(b.type!=OsmStructureIndex.StructureType.BRIDGE)
                continue;
            if("bridge".equals(b.subType))continue;
            double bHw=(b.explicitWidth>0
                ?Math.min(b.explicitWidth/2.0,30.0)
                :roadHW(b.subType!=null?b.subType:"secondary",
                    b.lanes))*config.roadWidthScale;
            double d=b.distanceToLine(lat,lon);
            if(d>bHw+8.0)continue;
            saw=true;
            if(d<=bHw-1.0)interior=true;
            if(Math.abs(d-bHw)<1.8)edge=true;}
        if(!saw)return null;
        return !interior && edge;}

    private void placeBridgeBeam(ChunkAccess chunk,BlockPos.MutableBlockPos pos,int bx,int bz,int sy,int deckY,int deckBase,double dist,double hw,double totalLen,double dfn,int maxRise,int deckRise,int maxY,OsmStructureIndex.OsmStructure s,double lat,double lon,List<OsmStructureIndex.OsmStructure> roads,List<OsmStructureIndex.OsmStructure> railways,int pillarSpacing,List<OsmStructureIndex.OsmStructure> allBridges){
        if(dist<=hw){
            if(deckBase>sy&&deckBase<=maxY){pos.set(bx,deckBase,bz);setOwned(chunk,pos,BRIDGE_DECK,false);}
            if(Math.abs(dist-hw)<0.6&&deckBase>sy&&deckBase<=maxY){pos.set(bx,deckBase,bz);setOwned(chunk,pos,BRIDGE_STONE,false);}
            placeDeckSurface(chunk,pos,bx,bz,deckY,maxY,s,dist,hw);
            clearAboveDeck(chunk,pos,bx,bz,deckY,maxY,s.isRailBridge,5);
            fillRampSolid(chunk,pos,bx,bz,sy,deckBase,deckRise,maxRise);
            if(!specialSpanOwnsSupports(lat,lon,s,allBridges))
                placeBeamPier(chunk,pos,bx,bz,sy,deckBase,maxY,dist,hw,totalLen,deckRise,maxRise,s,lat,lon,roads,railways,pillarSpacing);
        }else if(dist<=hw+1.5)
            placeStandardRailing(chunk,pos,bx,bz,sy,deckY,deckBase,maxY,deckRise,BRIDGE_RAILING,BRIDGE_DECK);
    }

    private void placeBridgeArch(ChunkAccess chunk,BlockPos.MutableBlockPos pos,int bx,int bz,int sy,int deckY,int deckBase,double dist,double hw,double totalLen,double dfn,int maxRise,int deckRise,int maxY,OsmStructureIndex.OsmStructure s,double lat,double lon,List<OsmStructureIndex.OsmStructure> roads,List<OsmStructureIndex.OsmStructure> railways,double archSpan,int pillarSpacing){
        if(dist>hw+1.5)return;
        if(dist<=hw){
            if(deckBase>sy&&deckBase<=maxY){pos.set(bx,deckBase,bz);setOwned(chunk,pos,BRIDGE_DECK,false);}
            placeDeckSurface(chunk,pos,bx,bz,deckY,maxY,s,dist,hw);
            clearAboveDeck(chunk,pos,bx,bz,deckY,maxY,s.isRailBridge,5);
            fillRampSolid(chunk,pos,bx,bz,sy,deckBase,deckRise,maxRise);
        }else
            placeStandardRailing(chunk,pos,bx,bz,sy,deckY,deckBase,maxY,deckRise,BRIDGE_RAILING,BRIDGE_DECK);
        if(deckRise!=maxRise||deckBase<=sy)return;
        double along=distAlongLine(lat,lon,s,true);
        double posInSpan=along%archSpan;
        boolean abutment=posInSpan<2.0||posInSpan>archSpan-2.0;
        boolean atSide=Math.abs(dist-hw)<1.5;
        if(abutment){
            placeBeamPier(chunk,pos,bx,bz,sy,deckBase,maxY,dist,hw,totalLen,deckRise,maxRise,s,lat,lon,roads,railways,Math.max(4,(int)archSpan));
            return;
        }
        if(dist>hw)return;
        double t=posInSpan/archSpan;
        double archFrac=4.0*t*(1.0-t);
        int maxArchDepth=Math.min(deckBase-sy,8);
        int archRise=(int)Math.round(archFrac*maxArchDepth);
        int archY=deckBase-1-archRise;
        if(atSide){
            if(archY>=sy&&archY<=maxY){
                pos.set(bx,archY,bz);
                if(isWaterOrAir(chunk.getBlockState(pos)))
                    setOwned(chunk,pos,BRIDGE_ARCH,false);}
            for(int y=archY+1;y<deckBase;y++){
                if(y<sy||y>maxY)continue;
                pos.set(bx,y,bz);
                if(isWaterOrAir(chunk.getBlockState(pos)))
                    setOwned(chunk,pos,BRIDGE_STONE,false);}
        }
    }

    private void placeBridgeTruss(ChunkAccess chunk,BlockPos.MutableBlockPos pos,int bx,int bz,int sy,int deckY,int deckBase,double dist,double hw,double totalLen,double dfn,int maxRise,int deckRise,int maxY,OsmStructureIndex.OsmStructure s,double lat,double lon,List<OsmStructureIndex.OsmStructure> roads,List<OsmStructureIndex.OsmStructure> railways,int pillarSpacing){
        if(dist<=hw){
            if(deckBase>sy&&deckBase<=maxY){pos.set(bx,deckBase,bz);setOwned(chunk,pos,BRIDGE_DECK,false);}
            placeDeckSurface(chunk,pos,bx,bz,deckY,maxY,s,dist,hw);
            clearAboveDeck(chunk,pos,bx,bz,deckY,maxY,s.isRailBridge,7);
            fillRampSolid(chunk,pos,bx,bz,sy,deckBase,deckRise,maxRise);
            placeBeamPier(chunk,pos,bx,bz,sy,deckBase,maxY,dist,hw,totalLen,deckRise,maxRise,s,lat,lon,roads,railways,pillarSpacing);
            if(deckRise==maxRise&&Math.abs(dist-hw)<1.0){
                int trussH=5;
                double along=distAlongLine(lat,lon,s,true);
                int ab=(int)along;
                for(int ty=1;ty<=trussH;ty++){
                    int trY=deckY+ty;if(trY>maxY)break;
                    pos.set(bx,trY,bz);
                    if(ab%2==0){setOwned(chunk,pos,BRIDGE_TRUSS,false);continue;}
                    if(ty==((ab/2)%trussH)+1)setOwned(chunk,pos,BRIDGE_TRUSS,false);}
                int topY=deckY+trussH;
                if(topY<=maxY){pos.set(bx,topY,bz);setOwned(chunk,pos,BRIDGE_TRUSS,false);}
            }
        }else if(dist<=hw+1.5)
            placeStandardRailing(chunk,pos,bx,bz,sy,deckY,deckBase,maxY,deckRise,BRIDGE_TRUSS,BRIDGE_DECK);
    }

    private void placeBridgeSuspension(ChunkAccess chunk,BlockPos.MutableBlockPos pos,int bx,int bz,int sy,int deckY,int deckBase,double dist,double hw,double totalLen,double dfn,int maxRise,int deckRise,int maxY,OsmStructureIndex.OsmStructure s,double lat,double lon,List<OsmStructureIndex.OsmStructure> roads,List<OsmStructureIndex.OsmStructure> railways,int towerH,int pillarSpacing){
        boolean atEdge=Math.abs(dist-hw)<1.2;
        boolean onDeck=dist<=hw;
        if(onDeck){
            if(deckBase>sy&&deckBase<=maxY){pos.set(bx,deckBase,bz);setOwned(chunk,pos,BRIDGE_DECK,false);}
            placeDeckSurface(chunk,pos,bx,bz,deckY,maxY,s,dist,hw);
            clearAboveDeck(chunk,pos,bx,bz,deckY,maxY,s.isRailBridge,towerH+2);
            fillRampSolid(chunk,pos,bx,bz,sy,deckBase,deckRise,maxRise);
        }else if(dist<=hw+1.5)
            placeStandardRailing(chunk,pos,bx,bz,sy,deckY,deckBase,maxY,deckRise,BRIDGE_CABLE,BRIDGE_DECK);
        if(deckRise!=maxRise)return;
        double along=distAlongLine(lat,lon,s,true);
        double q1=totalLen*0.20,q3=totalLen*0.80;
        boolean atTower=Math.abs(along-q1)<3.0||Math.abs(along-q3)<3.0;
        // pylons on the parapet, never in the lane
        if(atTower&&atEdge){
            int foot=pillarFootingY(chunk,pos,bx,bz,sy);
            int top=Math.min(maxY,deckY+towerH);
            for(int y=foot;y<=top;y++){
                pos.set(bx,y,bz);setOwned(chunk,pos,BRIDGE_TOWER,false);}
            if(top+1<=maxY){pos.set(bx,top+1,bz);setOwned(chunk,pos,BRIDGE_STONE,false);}
        }
        if(!atEdge)return;
        double nt=Math.abs(along-q1)<Math.abs(along-q3)?q1:q3;
        double hs=Math.max(1,totalLen*0.25);
        double t=Math.min(1.0,Math.abs(along-nt)/hs);
        double sf=Math.cosh(2.0*t-1.0)/Math.cosh(1.0);
        int sag=(int)Math.round((1.0-sf)*(towerH-2));
        int cableY=Math.max(deckY+1,deckY+towerH-sag);
        if(cableY<=maxY){pos.set(bx,cableY,bz);setOwned(chunk,pos,BRIDGE_CABLE,false);}
        if(along>q1&&along<q3&&((int)along)%2==0)
            for(int hy=deckY+1;hy<cableY;hy++){
                if(hy>maxY)break;
                pos.set(bx,hy,bz);
                if(chunk.getBlockState(pos).isAir())
                    setOwned(chunk,pos,BRIDGE_CABLE,false);}
    }

    private void placeBridgeCableStayed(ChunkAccess chunk,BlockPos.MutableBlockPos pos,int bx,int bz,int sy,int deckY,int deckBase,double dist,double hw,double totalLen,double dfn,int maxRise,int deckRise,int maxY,OsmStructureIndex.OsmStructure s,double lat,double lon,List<OsmStructureIndex.OsmStructure> roads,List<OsmStructureIndex.OsmStructure> railways,int towerH,int pillarSpacing){
        boolean atEdge=Math.abs(dist-hw)<1.2;
        boolean outer=Math.abs(dist-(hw+1.8))<1.2;
        boolean onDeck=dist<=hw;
        if(onDeck){
            if(deckBase>sy&&deckBase<=maxY){pos.set(bx,deckBase,bz);setOwned(chunk,pos,BRIDGE_DECK,false);}
            placeDeckSurface(chunk,pos,bx,bz,deckY,maxY,s,dist,hw);
            clearAboveDeck(chunk,pos,bx,bz,deckY,maxY,s.isRailBridge,towerH+2);
            fillRampSolid(chunk,pos,bx,bz,sy,deckBase,deckRise,maxRise);
        }else if(dist<=hw+2.2)
            placeStandardRailing(chunk,pos,bx,bz,sy,deckY,deckBase,maxY,deckRise,BRIDGE_CABLE,BRIDGE_DECK);
        if(deckRise!=maxRise)return;
        double along=distAlongLine(lat,lon,s,true);
        double[] pp=totalLen>180?new double[]{totalLen*0.33,totalLen*0.67}:new double[]{totalLen*0.5};
        boolean atTower=false;
        for(double pylonPos:pp)if(Math.abs(along-pylonPos)<3.0)atTower=true;
        // pylons sit OUTSIDE the roadway
        if(atTower&&outer){
            int foot=pillarFootingY(chunk,pos,bx,bz,sy);
            int top=Math.min(maxY,deckY+towerH);
            for(int y=foot;y<=top;y++){
                pos.set(bx,y,bz);setOwned(chunk,pos,BRIDGE_TOWER,false);}
            if(top+1<=maxY){pos.set(bx,top+1,bz);setOwned(chunk,pos,BRIDGE_STONE,false);}
        }
        if(atTower&&onDeck){
            // crossbeam over the road at tower
            int top=Math.min(maxY,deckY+towerH);
            if(top<=maxY){pos.set(bx,top,bz);setOwned(chunk,pos,BRIDGE_TOWER,false);}
            if(deckBase>sy){pos.set(bx,deckBase,bz);setOwned(chunk,pos,BRIDGE_TOWER,false);}
        }
        if(!atEdge&&!outer)return;
        double np=pp[0];
        for(double p:pp)if(Math.abs(along-p)<Math.abs(along-np))np=p;
        double mcr=totalLen/(pp.length+1);
        double t=Math.min(1.0,Math.abs(along-np)/Math.max(1,mcr));
        int cableY=Math.max(deckY+1,Math.min(deckY+towerH,deckY+(int)Math.round((1.0-t)*(towerH-1))+1));
        if(cableY<=maxY){pos.set(bx,cableY,bz);setOwned(chunk,pos,BRIDGE_CABLE,false);}
        if(((int)along)%2==0)
            for(int hy=deckY+1;hy<cableY;hy++){
                if(hy>maxY)break;
                pos.set(bx,hy,bz);
                if(chunk.getBlockState(pos).isAir())
                    setOwned(chunk,pos,BRIDGE_CABLE,false);}
    }

    private void placeBridgeViaduct(ChunkAccess chunk,BlockPos.MutableBlockPos pos,int bx,int bz,int sy,int deckY,int deckBase,double dist,double hw,double totalLen,double dfn,int maxRise,int deckRise,int maxY,OsmStructureIndex.OsmStructure s,double lat,double lon,List<OsmStructureIndex.OsmStructure> roads,List<OsmStructureIndex.OsmStructure> railways,int pillarSpacing){
        double spanLen=Math.max(10,pillarSpacing);
        if(dist<=hw){
            if(deckBase>sy&&deckBase<=maxY){pos.set(bx,deckBase,bz);setOwned(chunk,pos,BRIDGE_STONE,false);}
            placeDeckSurface(chunk,pos,bx,bz,deckY,maxY,s,dist,hw);
            clearAboveDeck(chunk,pos,bx,bz,deckY,maxY,s.isRailBridge,5);
            fillRampSolid(chunk,pos,bx,bz,sy,deckBase,deckRise,maxRise);
        }else if(dist<=hw+1.5)
            placeStandardRailing(chunk,pos,bx,bz,sy,deckY,deckBase,maxY,deckRise,STONE_WALL,BRIDGE_STONE);
        if(deckRise!=maxRise||deckBase<=sy||dist>hw+1.2)return;
        double along=distAlongLine(lat,lon,s,true);
        double pis=along%spanLen;
        boolean pier=pis<2.0;
        if(pier){
            placeBeamPier(chunk,pos,bx,bz,sy,deckBase,maxY,dist,hw,totalLen,deckRise,maxRise,s,lat,lon,roads,railways,Math.max(4,(int)spanLen));
            return;
        }
        if(dist>hw)return;
        boolean atSide=Math.abs(dist-hw)<1.5;
        if(!atSide)return;
        double t=Math.sin(((pis-2.0)/Math.max(1,spanLen-4.0))*Math.PI);
        int ar=(int)Math.round(t*Math.min(4,deckBase-sy));
        for(int ay=0;ay<ar;ay++){
            int aY=deckBase-1-ay;
            if(aY<sy||aY>maxY)continue;
            pos.set(bx,aY,bz);
            if(isWaterOrAir(chunk.getBlockState(pos)))
                setOwned(chunk,pos,BRIDGE_ARCH,false);}
    }

    private void placeBridgePedestrian(ChunkAccess chunk,BlockPos.MutableBlockPos pos,int bx,int bz,int sy,int deckY,int deckBase,double dist,double hw,double totalLen,double dfn,int maxRise,int deckRise,int maxY,OsmStructureIndex.OsmStructure s,double lat,double lon){
        double phw=Math.max(hw,1.5);
        if(dist>phw+0.6)return;
        if(dist<=phw){
            if(deckBase>sy&&deckBase<=maxY){pos.set(bx,deckBase,bz);setOwned(chunk,pos,BRIDGE_WOOD,false);}
            if(deckY<=maxY){pos.set(bx,deckY,bz);setOwned(chunk,pos,BRIDGE_WOOD,false);}
            clearAboveDeck(chunk,pos,bx,bz,deckY,maxY,false,3);
            fillRampSolid(chunk,pos,bx,bz,sy,deckBase,deckRise,maxRise);
            if(deckRise==maxRise&&Math.abs(dist-phw)<0.8){
                double along=distAlongLine(lat,lon,s,true);
                if((int)along%4<1){
                    int foot=pillarFootingY(chunk,pos,bx,bz,sy);
                    for(int y=foot;y<deckBase;y++){
                        pos.set(bx,y,bz);
                        if(isWaterOrAir(chunk.getBlockState(pos)))
                            setOwned(chunk,pos,OAK_LOG,false);}}}
        }else{
            if(deckRise>=2){
                if(deckY<=maxY){pos.set(bx,deckY,bz);setOwned(chunk,pos,BRIDGE_WOOD,false);}
                if(deckY+1<=maxY){pos.set(bx,deckY+1,bz);setOwned(chunk,pos,OAK_FENCE,false);}}}
    }

    private void placeBridgeRope(ChunkAccess chunk,BlockPos.MutableBlockPos pos,int bx,int bz,int sy,int deckY,int deckBase,double dist,double hw,double totalLen,double dfn,int maxRise,int deckRise,int maxY,OsmStructureIndex.OsmStructure s,double lat,double lon){
        double rhw=Math.max(hw,1.0);
        if(dist>rhw+0.6)return;
        int sagOff=0;
        if(deckRise==maxRise&&totalLen>0){
            double along=distAlongLine(lat,lon,s,true);
            sagOff=-(int)Math.round(Math.sin((along/totalLen)*Math.PI)*2.0);}
        int sdY=Math.max(sy,deckY+sagOff);
        if(dist<=rhw){
            if(sdY<=maxY){pos.set(bx,sdY,bz);setOwned(chunk,pos,BRIDGE_WOOD,false);}
            clearAboveDeck(chunk,pos,bx,bz,sdY,maxY,false,3);
            if(deckRise==maxRise){
                double along=distAlongLine(lat,lon,s,true);
                if((along<3.0||along>totalLen-3.0)&&dist<0.8)
                    for(int ty=1;ty<=3;ty++){
                        int tY=sdY+ty;if(tY>maxY)break;
                        pos.set(bx,tY,bz);setOwned(chunk,pos,OAK_LOG,false);}}
        }else{
            if(sdY<=maxY){pos.set(bx,sdY,bz);setOwned(chunk,pos,BRIDGE_WOOD,false);}
            if(sdY+1<=maxY){pos.set(bx,sdY+1,bz);setOwned(chunk,pos,BRIDGE_ROPE,false);}}
    }

    private void placeBridgeAqueduct(ChunkAccess chunk,BlockPos.MutableBlockPos pos,int bx,int bz,int sy,int deckY,int deckBase,double dist,double hw,double totalLen,double dfn,int maxRise,int deckRise,int maxY,OsmStructureIndex.OsmStructure s,double lat,double lon,int pillarSpacing){
        if(dist>hw+1.0)return;
        if(dist<=hw){
            if(deckBase>sy&&deckBase<=maxY){pos.set(bx,deckBase,bz);setOwned(chunk,pos,BRIDGE_STONE,false);}
            if(deckY<=maxY){pos.set(bx,deckY,bz);setOwned(chunk,pos,BRIDGE_STONE,false);}
            if(deckY+1<=maxY){
                pos.set(bx,deckY+1,bz);
                if(dist<hw*0.6)setOwned(chunk,pos,BRIDGE_WATER,false);
                else if(Math.abs(dist-hw)<0.5)setOwned(chunk,pos,BRIDGE_STONE,false);}
            clearAboveDeck(chunk,pos,bx,bz,deckY+1,maxY,false,2);
            fillRampSolid(chunk,pos,bx,bz,sy,deckBase,deckRise,maxRise);
            placeBeamPier(chunk,pos,bx,bz,sy,deckBase,maxY,dist,hw,totalLen,deckRise,maxRise,s,lat,lon,List.of(),List.of(),pillarSpacing);
        }else{
            if(deckRise>=2){
                if(deckY<=maxY){pos.set(bx,deckY,bz);setOwned(chunk,pos,BRIDGE_STONE,false);}
                if(deckY+1<=maxY){pos.set(bx,deckY+1,bz);setOwned(chunk,pos,STONE_WALL,false);}}}
    }

    private void placeBridgeMovable(ChunkAccess chunk,BlockPos.MutableBlockPos pos,int bx,int bz,int sy,int deckY,int deckBase,double dist,double hw,double totalLen,double dfn,int maxRise,int deckRise,int maxY,OsmStructureIndex.OsmStructure s,double lat,double lon,List<OsmStructureIndex.OsmStructure> roads,List<OsmStructureIndex.OsmStructure> railways,int towerH){
        boolean onDeck=dist<=hw;
        boolean outer=Math.abs(dist-(hw+2.0))<1.2;
        boolean atEdge=Math.abs(dist-hw)<1.2;
        if(onDeck){
            if(deckBase>sy&&deckBase<=maxY){pos.set(bx,deckBase,bz);setOwned(chunk,pos,BRIDGE_DECK,false);}
            double along=distAlongLine(lat,lon,s,true);
            boolean isCentre=along>totalLen*0.35&&along<totalLen*0.65;
            if(deckY<=maxY){pos.set(bx,deckY,bz);setOwned(chunk,pos,isCentre?YELLOW_CONCRETE:roadSurf(s.subType,s.surface),false);}
            int mH=Math.min(towerH,10);
            clearAboveDeck(chunk,pos,bx,bz,deckY,maxY,s.isRailBridge,mH);
            fillRampSolid(chunk,pos,bx,bz,sy,deckBase,deckRise,maxRise);
            if(deckRise==maxRise&&isCentre&&dist<hw*0.3)
                for(int ty=1;ty<=3;ty++){
                    int tY=deckY+ty;if(tY>maxY)break;
                    pos.set(bx,tY,bz);setOwned(chunk,pos,GRAY_CONCRETE,false);}
        }else if(dist<=hw+1.5)
            placeStandardRailing(chunk,pos,bx,bz,sy,deckY,deckBase,maxY,deckRise,BRIDGE_RAILING,BRIDGE_DECK);
        if(deckRise!=maxRise)return;
        double along=distAlongLine(lat,lon,s,true);
        boolean nT1=Math.abs(along-totalLen*0.33)<4.0,nT2=Math.abs(along-totalLen*0.67)<4.0;
        int mH=Math.min(towerH,10);
        // lift towers outside the roadway
        if((nT1||nT2)&&outer){
            int foot=pillarFootingY(chunk,pos,bx,bz,sy);
            int top=Math.min(maxY,deckY+mH);
            for(int y=foot;y<=top;y++){
                pos.set(bx,y,bz);setOwned(chunk,pos,BRIDGE_STONE,false);}
            for(int arm=mH-1;arm<=mH;arm++){
                int aY=deckY+arm;if(aY>maxY)break;
                pos.set(bx,aY,bz);setOwned(chunk,pos,BRIDGE_TRUSS,false);}
        }
        if((nT1||nT2)&&onDeck){
            int top=Math.min(maxY,deckY+mH);
            if(top<=maxY){pos.set(bx,top,bz);setOwned(chunk,pos,BRIDGE_STONE,false);}
        }
    }

    private void placeBridgeBoardwalk(ChunkAccess chunk,BlockPos.MutableBlockPos pos,int bx,int bz,int sy,int deckY,int deckBase,double dist,double hw,double totalLen,double dfn,int maxRise,int deckRise,int maxY,OsmStructureIndex.OsmStructure s,double lat,double lon){
        double bwHw=Math.max(hw,1.5);
        if(dist>bwHw+0.3)return;
        int bwDY=sy+1;if(bwDY>maxY)return;
        if(dist<=bwHw){
            pos.set(bx,bwDY,bz);setOwned(chunk,pos,BRIDGE_WOOD,false);
            clearAboveDeck(chunk,pos,bx,bz,bwDY,maxY,false,3);
            double along=distAlongLine(lat,lon,s,true);
            if((int)along%4==0&&Math.abs(dist-bwHw)<0.8){
                int foot=pillarFootingY(chunk,pos,bx,bz,sy);
                for(int y=foot;y<bwDY;y++){
                    pos.set(bx,y,bz);
                    if(isWaterOrAir(chunk.getBlockState(pos)))
                        setOwned(chunk,pos,OAK_LOG,false);}}
        }
    }

    private void placeBridgeCantilever(ChunkAccess chunk,BlockPos.MutableBlockPos pos,int bx,int bz,int sy,int deckY,int deckBase,double dist,double hw,double totalLen,double dfn,int maxRise,int deckRise,int maxY,OsmStructureIndex.OsmStructure s,double lat,double lon,List<OsmStructureIndex.OsmStructure> roads,List<OsmStructureIndex.OsmStructure> railways,int pillarSpacing){
        boolean onDeck=dist<=hw;
        boolean outer=Math.abs(dist-(hw+1.8))<1.2;
        boolean atEdge=Math.abs(dist-hw)<1.2;
        if(onDeck){
            if(deckBase>sy&&deckBase<=maxY){pos.set(bx,deckBase,bz);setOwned(chunk,pos,BRIDGE_DECK,false);}
            placeDeckSurface(chunk,pos,bx,bz,deckY,maxY,s,dist,hw);
            clearAboveDeck(chunk,pos,bx,bz,deckY,maxY,s.isRailBridge,6);
            fillRampSolid(chunk,pos,bx,bz,sy,deckBase,deckRise,maxRise);
        }else if(dist<=hw+2.2)
            placeStandardRailing(chunk,pos,bx,bz,sy,deckY,deckBase,maxY,deckRise,BRIDGE_RAILING,BRIDGE_DECK);
        if(deckRise!=maxRise||deckBase<=sy)return;
        double along=distAlongLine(lat,lon,s,true);
        double p1=totalLen*0.25,p2=totalLen*0.75;
        double np=Math.abs(along-p1)<Math.abs(along-p2)?p1:p2;
        double dtp=Math.abs(along-np);
        double hs=totalLen*0.25;
        double taper=1.0-Math.min(1.0,dtp/hs);
        int sd=(int)Math.round(taper*Math.min(6,deckBase-sy));
        if(dtp<3.0&&outer){
            int foot=pillarFootingY(chunk,pos,bx,bz,sy);
            for(int y=foot;y<=deckBase;y++){
                pos.set(bx,y,bz);
                if(isWaterOrAir(chunk.getBlockState(pos)))
                    setOwned(chunk,pos,BRIDGE_PILLAR,false);}
        }
        if(dtp<3.0&&onDeck){
            pos.set(bx,deckBase,bz);setOwned(chunk,pos,POLISHED_ANDESITE,false);
        }
        if(atEdge&&sd>0)
            for(int dy=0;dy<sd;dy++){
                int uY=deckBase-1-dy;
                if(uY<sy||uY>maxY)continue;
                pos.set(bx,uY,bz);
                if(isWaterOrAir(chunk.getBlockState(pos)))
                    setOwned(chunk,pos,BRIDGE_TRUSS,false);}
    }

    private void placeBridgeCovered(ChunkAccess chunk,BlockPos.MutableBlockPos pos,int bx,int bz,int sy,int deckY,int deckBase,double dist,double hw,double totalLen,double dfn,int maxRise,int deckRise,int maxY,OsmStructureIndex.OsmStructure s,double lat,double lon,List<OsmStructureIndex.OsmStructure> roads,List<OsmStructureIndex.OsmStructure> railways,int pillarSpacing){
        if(dist>hw+1.0)return;
        if(dist<=hw){
            if(deckBase>sy&&deckBase<=maxY){pos.set(bx,deckBase,bz);setOwned(chunk,pos,BRIDGE_WOOD,false);}
            if(deckY<=maxY){pos.set(bx,deckY,bz);setOwned(chunk,pos,BRIDGE_WOOD,false);}
            fillRampSolid(chunk,pos,bx,bz,sy,deckBase,deckRise,maxRise);
            placeBeamPier(chunk,pos,bx,bz,sy,deckBase,maxY,dist,hw,totalLen,deckRise,maxRise,s,lat,lon,roads,railways,pillarSpacing);
            if(deckRise==maxRise){
                boolean isEdge=Math.abs(dist-hw)<1.0;
                double along=distAlongLine(lat,lon,s,true);
                int ab=(int)along;
                if(isEdge){
                    for(int wy=1;wy<=3;wy++){
                        int wY=deckY+wy;if(wY>maxY)break;
                        pos.set(bx,wY,bz);
                        if(wy==2&&ab%4>=1&&ab%4<=2)setOwned(chunk,pos,AIR,false);
                        else setOwned(chunk,pos,BRIDGE_WOOD,false);}
                }else{
                    for(int wy=1;wy<=4;wy++){
                        int aY=deckY+wy;if(aY>maxY)break;
                        pos.set(bx,aY,bz);setOwned(chunk,pos,AIR,false);}
                }
                int roofRise=(int)Math.round((1.0-dist/Math.max(0.5,hw))*2);
                int roofY=deckY+4+roofRise;
                if(roofY<=maxY){pos.set(bx,roofY,bz);setOwned(chunk,pos,DARK_OAK_PLANKS,false);}
            }
        }
    }

    private void placeBridgeLowWaterCrossing(ChunkAccess chunk,BlockPos.MutableBlockPos pos,int bx,int bz,int sy,double dist,double hw,int maxY,OsmStructureIndex.OsmStructure s,double lat,double lon){
        double lwcHw=Math.max(hw,2.0);
        if(dist>lwcHw)return;
        if(sy<=maxY){pos.set(bx,sy,bz);setOwned(chunk,pos,SMOOTH_STONE,false);}
        for(int cy=1;cy<=2;cy++){
            if(sy+cy>maxY)break;
            pos.set(bx,sy+cy,bz);
            BlockState e=chunk.getBlockState(pos);
            if(!e.isAir()&&!isWaterBlock(e))setOwned(chunk,pos,AIR,false);}
    }

    private void placeBridgeTrestle(ChunkAccess chunk,BlockPos.MutableBlockPos pos,int bx,int bz,int sy,int deckY,int deckBase,double dist,double hw,double totalLen,double dfn,int maxRise,int deckRise,int maxY,OsmStructureIndex.OsmStructure s,double lat,double lon,List<OsmStructureIndex.OsmStructure> roads,List<OsmStructureIndex.OsmStructure> railways,int pillarSpacing){
        if(dist<=hw){
            if(deckBase>sy&&deckBase<=maxY){pos.set(bx,deckBase,bz);setOwned(chunk,pos,BRIDGE_WOOD,false);}
            placeDeckSurface(chunk,pos,bx,bz,deckY,maxY,s,dist,hw);
            clearAboveDeck(chunk,pos,bx,bz,deckY,maxY,s.isRailBridge,4);
            fillRampSolid(chunk,pos,bx,bz,sy,deckBase,deckRise,maxRise);
            if(deckRise==maxRise&&deckBase>sy){
                double along=distAlongLine(lat,lon,s,true);
                int bs2=Math.max(5,pillarSpacing/3);
                double pis=along%bs2;
                if(pis<1.5){
                    boolean post=Math.abs(dist-hw)<1.0||dist<0.8;
                    if(post){
                        int foot=pillarFootingY(chunk,pos,bx,bz,sy);
                        for(int y=foot;y<deckBase;y++){
                            pos.set(bx,y,bz);
                            if(isWaterOrAir(chunk.getBlockState(pos)))
                                setOwned(chunk,pos,OAK_LOG,false);}}
                    pos.set(bx,deckBase,bz);setOwned(chunk,pos,STRIPPED_OAK_LOG,false);
                }
            }
        }else if(dist<=hw+1.5)
            placeStandardRailing(chunk,pos,bx,bz,sy,deckY,deckBase,maxY,deckRise,OAK_FENCE,BRIDGE_WOOD);
    }

    private void placeBridgeFloating(ChunkAccess chunk,BlockPos.MutableBlockPos pos,int bx,int bz,int sy,double dist,double hw,double totalLen,double dfn,int maxY,OsmStructureIndex.OsmStructure s,double lat,double lon){
        double fhw=Math.max(hw,2.0);
        if(dist>fhw+0.5)return;
        if(dist<=fhw){
            if(sy<=maxY){pos.set(bx,sy,bz);setOwned(chunk,pos,BRIDGE_WOOD,false);}
            double along=distAlongLine(lat,lon,s,true);
            if(((int)along)%5==0&&dist<fhw-0.5&&sy-1>=chunk.getMinBuildHeight()){
                pos.set(bx,sy-1,bz);
                if(isWaterBlock(chunk.getBlockState(pos))||chunk.getBlockState(pos).isAir())
                    setOwned(chunk,pos,BRIDGE_PONTOON,false);}
            clearAboveDeck(chunk,pos,bx,bz,sy,maxY,false,3);
        }else{
            if(sy<=maxY){pos.set(bx,sy,bz);setOwned(chunk,pos,BRIDGE_WOOD,false);}
            if(sy+1<=maxY){pos.set(bx,sy+1,bz);setOwned(chunk,pos,BRIDGE_ROPE,false);}}
    }

    private void placeBridgeSimpleSuspension(ChunkAccess chunk,BlockPos.MutableBlockPos pos,int bx,int bz,int sy,int deckY,int deckBase,double dist,double hw,double totalLen,double dfn,int maxRise,int deckRise,int maxY,OsmStructureIndex.OsmStructure s,double lat,double lon,int towerH){
        double rhw=Math.max(hw,1.5);
        if(dist>rhw+0.6)return;
        double along=distAlongLine(lat,lon,s,true);
        int sagOff=0;
        if(totalLen>0)
            sagOff=-(int)Math.round(Math.sin((along/totalLen)*Math.PI)*Math.min(4,towerH/4));
        int sdY=Math.max(sy,deckY+sagOff);
        if(dist<=rhw){
            if(sdY<=maxY){pos.set(bx,sdY,bz);setOwned(chunk,pos,BRIDGE_WOOD,false);}
            clearAboveDeck(chunk,pos,bx,bz,sdY,maxY,false,4);
            // end posts only, on the edges
            if((along<4.0||along>totalLen-4.0)&&Math.abs(dist-rhw)<0.8){
                int pH=Math.min(6,towerH/3);
                for(int ty=1;ty<=pH;ty++){
                    int tY=sdY+ty;if(tY>maxY)break;
                    pos.set(bx,tY,bz);setOwned(chunk,pos,OAK_LOG,false);}}
            if(Math.abs(dist-rhw)<0.6){
                int rY=sdY+2;
                if(rY<=maxY){pos.set(bx,rY,bz);setOwned(chunk,pos,BRIDGE_ROPE,false);}}
        }else{
            if(sdY<=maxY){pos.set(bx,sdY,bz);setOwned(chunk,pos,BRIDGE_WOOD,false);}
            if(sdY+1<=maxY){pos.set(bx,sdY+1,bz);setOwned(chunk,pos,BRIDGE_ROPE,false);}}
    }

    private void placeBridgeHumpback(ChunkAccess chunk,BlockPos.MutableBlockPos pos,int bx,int bz,int sy,int deckY,int deckBase,double dist,double hw,double totalLen,double dfn,int maxRise,int deckRise,int maxY,OsmStructureIndex.OsmStructure s,double lat,double lon,List<OsmStructureIndex.OsmStructure> roads,List<OsmStructureIndex.OsmStructure> railways,int pillarSpacing){
        if(dist>hw+1.5)return;
        double along=distAlongLine(lat,lon,s,true);
        double af=Math.sin((along/Math.max(1,totalLen))*Math.PI);
        int ar=(int)Math.round(af*Math.min(6,maxRise+2));
        int hdY=sy+ar;if(hdY>maxY)return;
        if(dist<=hw){
            pos.set(bx,hdY,bz);setOwned(chunk,pos,BRIDGE_STONE,false);
            boolean atSide=Math.abs(dist-hw)<1.2;
            for(int fy=sy;fy<hdY;fy++){
                if(fy>maxY)break;
                pos.set(bx,fy,bz);
                BlockState e=chunk.getBlockState(pos);
                if(isWaterOrAir(e)){
                    double ff=1.0-af;
                    if(atSide&&fy<sy+(int)(ff*(hdY-sy))+1)
                        setOwned(chunk,pos,BRIDGE_ARCH,false);}}
            clearAboveDeck(chunk,pos,bx,bz,hdY,maxY,s.isRailBridge,3);
        }else{
            if(hdY<=maxY){pos.set(bx,hdY,bz);setOwned(chunk,pos,BRIDGE_STONE,false);}
            if(hdY+1<=maxY){pos.set(bx,hdY+1,bz);setOwned(chunk,pos,STONE_WALL,false);}}
    }

    private void placeBridgeClapper(ChunkAccess chunk,BlockPos.MutableBlockPos pos,int bx,int bz,int sy,int deckY,int deckBase,double dist,double hw,double totalLen,double dfn,int maxRise,int deckRise,int maxY,OsmStructureIndex.OsmStructure s,double lat,double lon){
        double chw=Math.max(hw,2.0);
        if(dist>chw+0.3)return;
        if(dist<=chw){
            if(deckBase>sy&&deckBase<=maxY){pos.set(bx,deckBase,bz);setOwned(chunk,pos,SMOOTH_STONE,false);}
            if(deckY<=maxY){pos.set(bx,deckY,bz);setOwned(chunk,pos,SMOOTH_STONE,false);}
            clearAboveDeck(chunk,pos,bx,bz,deckY,maxY,false,3);
            fillRampSolid(chunk,pos,bx,bz,sy,deckBase,deckRise,maxRise);
            if(deckRise==maxRise&&deckBase>sy){
                double along=distAlongLine(lat,lon,s,true);
                if((int)along%5==0&&Math.abs(dist-chw)<1.0){
                    int foot=pillarFootingY(chunk,pos,bx,bz,sy);
                    for(int y=foot;y<deckBase;y++){
                        pos.set(bx,y,bz);
                        if(isWaterOrAir(chunk.getBlockState(pos)))
                            setOwned(chunk,pos,COBBLESTONE,false);}
                    pos.set(bx,deckBase,bz);setOwned(chunk,pos,SMOOTH_STONE,false);}
            }
        }
    }

    // ================================================================
    // BRIDGE INFLUENCE
    // ================================================================

    private enum BridgeInfluence{NONE,OWN_WAY,RAMP,UNDER}

    private BridgeInfluence getBridgeInfluence(
            OsmStructureIndex.OsmStructure bridge,
            double lat,double lon,long wayOsmId,
            double roadWidthScale){
        double bHw=roadHW(
            bridge.subType!=null
            ?bridge.subType:"secondary",
            bridge.lanes)*roadWidthScale;
        if(bridge.distanceToLine(lat,lon)>bHw+1.5)
            return BridgeInfluence.NONE;
        double totalLen=bridgeTotalLength(bridge);
        double dfn=Math.min(
            distAlongLine(lat,lon,bridge,true),
            distAlongLine(lat,lon,bridge,false));
        int maxRise=bridge.layer>0
            ?bridge.layer*BRIDGE_HEIGHT
            :BRIDGE_HEIGHT;
        int deckRise=computeDeckRise(
            dfn,totalLen,maxRise);
        if(deckRise==0)return BridgeInfluence.NONE;
        if(bridge.osmId!=0&&wayOsmId!=0
                &&bridge.osmId==wayOsmId)
            return BridgeInfluence.OWN_WAY;
        if(deckRise<maxRise)
            return BridgeInfluence.RAMP;
        return BridgeInfluence.UNDER;}

    // ================================================================
    // BLOCK CLASSIFICATION
    // ================================================================

    private boolean isBuildingBlock(BlockState s){
        if(s.isAir())return false;
        return s.is(Blocks.BRICKS)
            ||s.is(Blocks.STONE_BRICKS)
            ||s.is(Blocks.COBBLESTONE)
            ||s.is(Blocks.SMOOTH_STONE)
            ||s.is(Blocks.POLISHED_ANDESITE)
            ||s.is(Blocks.POLISHED_DIORITE)
            ||s.is(Blocks.WHITE_CONCRETE)
            ||s.is(Blocks.LIGHT_GRAY_CONCRETE)
            ||s.is(Blocks.GRAY_CONCRETE)
            ||s.is(Blocks.BROWN_CONCRETE)
            ||s.is(Blocks.CYAN_CONCRETE)
            ||s.is(Blocks.YELLOW_CONCRETE)
            ||s.is(Blocks.RED_CONCRETE)
            ||s.is(Blocks.ORANGE_CONCRETE)
            ||s.is(Blocks.LIME_CONCRETE)
            ||s.is(Blocks.BLACK_CONCRETE)
            ||s.is(Blocks.WHITE_TERRACOTTA)
            ||s.is(Blocks.LIGHT_GRAY_TERRACOTTA)
            ||s.is(Blocks.BROWN_TERRACOTTA)
            ||s.is(Blocks.RED_TERRACOTTA)
            ||s.is(Blocks.ORANGE_TERRACOTTA)
            ||s.is(Blocks.YELLOW_TERRACOTTA)
            ||s.is(Blocks.CYAN_TERRACOTTA)
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
            ||s.is(Blocks.LANTERN)
            ||s.is(Blocks.SEA_LANTERN)
            ||s.is(Blocks.GLOWSTONE)
            ||s.is(Blocks.IRON_BARS)
            ||s.is(Blocks.SANDSTONE)
            ||s.is(Blocks.RED_SANDSTONE);}

    private boolean isBridgeDeckBlock(BlockState s){
        return s.is(Blocks.SMOOTH_STONE)
            ||s.is(Blocks.STONE_BRICKS)
            ||s.is(Blocks.POLISHED_ANDESITE)
            ||s.is(Blocks.COBBLESTONE_WALL)
            ||s.is(Blocks.QUARTZ_PILLAR)
            ||s.is(Blocks.IRON_BARS)
            ||s.is(Blocks.CHAIN)
            ||s.is(Blocks.OAK_PLANKS);}

    private boolean isRoadBlock(BlockState s){
        return s.is(Blocks.BLACK_CONCRETE)
            ||s.is(Blocks.GRAY_CONCRETE)
            ||s.is(Blocks.SMOOTH_STONE)
            ||s.is(Blocks.GRAVEL)
            ||s.is(Blocks.DIRT_PATH)
            ||s.is(Blocks.WHITE_CONCRETE)
            ||s.is(Blocks.YELLOW_CONCRETE)
            ||s.is(Blocks.COBBLESTONE_WALL)
            ||s.is(Blocks.RAIL)
            ||s.is(Blocks.RED_CONCRETE);}

    private boolean hasBuildingAbove(ChunkAccess chunk,
            BlockPos.MutableBlockPos pos,
            int bx,int bz,int surfaceY){
        for(int y=0;y<=3;y++){
            pos.set(bx,surfaceY+y,bz);
            BlockState s=chunk.getBlockState(pos);
            if(isBuildingBlock(s)
                    &&!isBridgeDeckBlock(s))
                return true;}
        return false;}

    // ================================================================
    // BRIDGE HELPERS
    // ================================================================

    private double bridgeTotalLength(
            OsmStructureIndex.OsmStructure s){
        double total=0;
        for(int i=0;i<s.nodes.length-1;i++)
            total+=haversineMeters(
                s.nodes[i].lat,s.nodes[i].lon,
                s.nodes[i+1].lat,s.nodes[i+1].lon);
        return total;}

    private int computeDeckRise(double distFromNearest,
            double totalLength,int maxRise){
        if(totalLength<=10.0)return 0;
        double rampLengthM=Math.min(
            totalLength*0.3,35.0);
        double segM=rampLengthM/maxRise;
        if(distFromNearest>=rampLengthM)return maxRise;
        return Math.max(0,Math.min(maxRise-1,
            (int)(distFromNearest/segM)));}

    // ================================================================
    // ROAD / RAILWAY / TUNNEL
    // ================================================================

    private void placeRoadNearest(ChunkAccess chunk,
            BlockPos.MutableBlockPos pos,
            int bx,int bz,int sy,
            List<OsmStructureIndex.OsmStructure> roads,
            List<OsmStructureIndex.OsmStructure> bridges,
            double lat,double lon){
        if(roads.isEmpty())return;
        if(hasBuildingAbove(chunk,pos,bx,bz,sy))return;
        OsmStructureIndex.OsmStructure nearest=null;
        double nearestDist=Double.MAX_VALUE;
        for(OsmStructureIndex.OsmStructure r:roads){
            double d=r.distanceToLine(lat,lon);
            if(d<nearestDist){
                nearestDist=d;nearest=r;}}
        if(nearest==null)return;
        boolean underBridge=false;
        for(OsmStructureIndex.OsmStructure bridge:
                bridges){
            BridgeInfluence inf=getBridgeInfluence(
                bridge,lat,lon,nearest.osmId,
                config.roadWidthScale);
            switch(inf){
                case OWN_WAY,RAMP->{return;}
                case UNDER->underBridge=true;
                case NONE->{}}}
        if(nearest.type==OsmStructureIndex
                .StructureType.FOOTWAY
            ||nearest.type==OsmStructureIndex
                .StructureType.CYCLEWAY){
            if(nearestDist<=1.0*config.roadWidthScale)
                placeFootway(chunk,pos,bx,bz,sy,
                    nearest,lat,lon);
            return;}
        double hw=roadHWFor(nearest)
            *config.roadWidthScale;
        if(nearestDist>hw+1.5)return;
        if(nearestDist<=hw){
            pos.set(bx,sy,bz);
            setOwned(chunk, pos,
                roadSurf(nearest.subType,
                    nearest.surface),false);
            clearSafe(chunk,pos,bx,bz,sy,1);
            if(config.buildingDetail>=2
                    &&isMain(nearest.subType)
                    &&markingsExistNow()){
                if(nearestDist<0.3){
                    pos.set(bx,sy,bz);
                    setOwned(chunk, pos,
                        ROAD_MARKING_YELLOW,false);}
                else if(Math.abs(nearestDist-hw)<0.4){
                    pos.set(bx,sy,bz);
                    setOwned(chunk, pos,
                        ROAD_MARKING_WHITE,false);}}}
        else if(hasSW(nearest.subType)
                &&!underBridge){
            pos.set(bx,sy,bz);
            setOwned(chunk, pos,SIDEWALK,false);
            clearSafe(chunk,pos,bx,bz,sy,1);}}

    private void placeRailway(ChunkAccess chunk,
            BlockPos.MutableBlockPos pos,
            int bx,int bz,int sy,
            OsmStructureIndex.OsmStructure s,
            List<OsmStructureIndex.OsmStructure> bridges,
            double lat,double lon){
        if(s.tunnel||s.layer<0){
            placeTunnel(chunk,pos,bx,bz,sy,s,lat,lon);
            return;}
        double dist=s.distanceToLine(lat,lon);
        if(dist>2.5)return;
        if(hasBuildingAbove(chunk,pos,bx,bz,sy))return;
        for(OsmStructureIndex.OsmStructure bridge:
                bridges){
            BridgeInfluence inf=getBridgeInfluence(
                bridge,lat,lon,s.osmId,
                config.roadWidthScale);
            switch(inf){
                case OWN_WAY,RAMP->{return;}
                default->{}}}
        pos.set(bx,sy,bz);
        setOwned(chunk, pos,ROAD_GRAVEL,false);
        if(dist<0.8){
            pos.set(bx,sy+1,bz);
            setOwned(chunk, pos,RAIL,false);}
        else clearSafe(chunk,pos,bx,bz,sy,1);}

    private void placeTunnel(ChunkAccess chunk,
            BlockPos.MutableBlockPos pos,
            int bx,int bz,int sy,
            OsmStructureIndex.OsmStructure s,
            double lat,double lon){
        double dist=s.distanceToLine(lat,lon);
        if(dist>TUNNEL_HALF_WIDTH+1)return;
        int minBuild=chunk.getMinBuildHeight();
        int maxY=chunk.getMaxBuildHeight()-2;
        int tTopY=sy-TUNNEL_DEPTH;
        int tBotY=tTopY-TUNNEL_HEIGHT;
        if(tTopY>maxY||tBotY<minBuild)return;
        if(dist>TUNNEL_HALF_WIDTH-0.5){
            for(int y=tBotY;y<=tTopY;y++){
                if(y<minBuild||y>maxY)continue;
                pos.set(bx,y,bz);
                setOwned(chunk, pos,
                    TUNNEL_WALL,false);}
        }else{
            if(tTopY>=minBuild&&tTopY<=maxY){
                pos.set(bx,tTopY,bz);
                setOwned(chunk, pos,
                    TUNNEL_CEILING,false);}
            if(tBotY>=minBuild&&tBotY<=maxY){
                pos.set(bx,tBotY,bz);
                setOwned(chunk, pos,
                    TUNNEL_FLOOR,false);}
            for(int y=tBotY+1;y<tTopY;y++){
                if(y<minBuild||y>maxY)continue;
                pos.set(bx,y,bz);
                setOwned(chunk, pos,AIR,false);}
            if(dist<0.8){
                int rY=tBotY+1;
                if(rY>=minBuild&&rY<=maxY){
                    pos.set(bx,rY,bz);
                    setOwned(chunk, pos,
                        ROAD_GRAVEL,false);rY++;}
                if(rY<=maxY){
                    pos.set(bx,rY,bz);
                    setOwned(chunk, pos,
                        RAIL,false);}}
            int bh=TerraDensityFunctions
                .blockHash(bx,bz);
            if(bh%8==0){
                int lY=tTopY-1;
                if(lY>=minBuild&&lY<=maxY){
                    pos.set(bx,lY,bz);
                    if(chunk.getBlockState(pos).isAir())
                        setOwned(chunk, pos,
                            TUNNEL_LIGHT,false);}}
            if(bh%6==0&&dist>0.8
                    &&dist<TUNNEL_HALF_WIDTH-0.5)
                for(int y=tBotY+1;y<tTopY;y++){
                    if(y<minBuild||y>maxY)continue;
                    pos.set(bx,y,bz);
                    setOwned(chunk, pos,
                        TUNNEL_SUPPORT,false);}}}

    // ================================================================
    // SINGLE BLOCK API
    // ================================================================

    public void placeSingleBlock(LevelChunk chunk,
            BlockPos.MutableBlockPos pos,
            int bx,int bz,int surfaceY,
            OsmStructureIndex.OsmStructure s,
            double lat,double lon,
            List<OsmStructureIndex.OsmStructure>
            allStructures){
        int groundY=findSolidGround(chunk,pos,
            bx,bz,surfaceY);
        int bridgeY=findWaterSurface(chunk,pos,
            bx,bz,Math.max(surfaceY,groundY));
        List<OsmStructureIndex.OsmStructure>
            roads=new ArrayList<>(),
            bridges=new ArrayList<>(),
            railways=new ArrayList<>();
        for(OsmStructureIndex.OsmStructure r:
                allStructures)
            switch(r.type){
                case ROAD,FOOTWAY,CYCLEWAY->
                    roads.add(r);
                case BRIDGE->bridges.add(r);
                case RAILWAY->railways.add(r);
                default->{}
            }
        switch(s.type){
            case BUILDING->{
                boolean underBridge=
                    isUnderBridgeZone(
                        lat,lon,bridges);
                // This road re-runs on chunks the generator
                // already built. If a building already stands
                // on this column's terrain ground, never place
                // another one here — not even a different
                // source's copy, and not an elevated/station
                // copy floated to deck height. (Floating-copy
                // bug, newworld1: same tower placed at ground
                // and again at deck+32 with rail on its roof.)
                if(config.buildings
                        &&(!underBridge
                        ||isTransportationBuilding(s))
                        &&!hasBuildingAbove(chunk,pos,
                            bx,bz,groundY))
                    placeBuilding(chunk,pos,bx,bz,
                        groundY,s,lat,lon,roads,
                        bridges);}
            case BUILDING_PART->{
                // No already-built guard here: a part is
                // MEANT to stack on its base building, so a
                // building below is not proof of duplication.
                boolean underBridge=
                    isUnderBridgeZone(
                        lat,lon,bridges);
                if(config.buildings
                        &&(!underBridge
                        ||isTransportationBuilding(s)))
                    placeBuildingPart(chunk,pos,bx,bz,
                        groundY,s,lat,lon,roads,
                        bridges);}
            case BRIDGE->{
                if(config.bridges)
                    placeBridge(chunk,pos,bx,bz,
                        bridgeY,s,lat,lon,roads,
                        railways,bridges);}
            case ROAD->{
                if(config.roads)
                    placeRoadNearest(chunk,pos,bx,bz,
                        groundY,roads,bridges,
                        lat,lon);}
            case FOOTWAY,CYCLEWAY->{
                if(config.roads)
                    placeFootway(chunk,pos,bx,bz,
                        groundY,s,lat,lon);}
            case RAILWAY->{
                if(config.railways)
                    placeRailway(chunk,pos,bx,bz,
                        groundY,s,bridges,lat,lon);}
            case WALL->{
                if(config.walls)
                    placeLinearBarrier(chunk,pos,bx,bz,
                        groundY,s,lat,lon,
                        STONE_WALL,2);}
            case FENCE,BARRIER->{
                if(config.walls)
                    placeLinearBarrier(chunk,pos,bx,bz,
                        groundY,s,lat,lon,
                        OAK_FENCE,1);}
            case HEDGE->{
                if(config.walls)
                    placeLinearBarrier(chunk,pos,bx,bz,
                        groundY,s,lat,lon,
                        OAK_LEAVES,2);}
            case GARDEN,PARK->{
                if(config.gardens
                        &&s.containsPoint(lat,lon))
                    placePark(chunk,pos,bx,bz,groundY,
                        lat,lon,roads,bridges);}
            case FARMLAND->{
                if(config.landuse
                        &&s.containsPoint(lat,lon))
                    placeFarmland(chunk,pos,bx,bz,
                        groundY);}
            case FOREST->{
                if(config.landuse
                        &&s.containsPoint(lat,lon))
                    placeForest(chunk,pos,bx,bz,groundY,
                        lat,lon,roads,bridges);}
            case MEADOW->{
                if(config.landuse
                        &&s.containsPoint(lat,lon))
                    placeMeadow(chunk,pos,bx,bz,
                        groundY);}
            case PARKING->{
                if(config.landuse
                        &&s.containsPoint(lat,lon))
                    placeParking(chunk,pos,bx,bz,
                        groundY);}
            case STREET_LAMP->{
                if(config.streetLights)
                    placeStreetLamp(chunk,pos,bx,bz,
                        groundY,s,lat,lon);}
            case SAND->{
                if(config.landuse
                        &&s.containsPoint(lat,lon))
                    placeSand(chunk,pos,bx,bz,
                        groundY);}
            case ROCK->{
                if(config.landuse
                        &&s.containsPoint(lat,lon))
                    placeRock(chunk,pos,bx,bz,
                        groundY);}
            case TREE_ROW->{
                if(config.landuse)
                    placeTreeRow(chunk,pos,bx,bz,
                        groundY,s,lat,lon,
                        roads,bridges);}
            case TREE->{
                if(config.landuse)
                    placeSingleTree(chunk,pos,bx,bz,
                        groundY,s,lat,lon,
                        roads,bridges);}
            case PIER->{
                if(config.bridges)
                    placePier(chunk,pos,bx,bz,
                        groundY,bridgeY,s,lat,lon);}
            case TOWER->{
                if(config.buildings)
                    placeTower(chunk,pos,bx,bz,
                        groundY,s,lat,lon);}
            case PYLON->{
                if(config.streetLights)
                    placePylon(chunk,pos,bx,bz,
                        groundY,s,lat,lon);}
            case RUNWAY->{
                if(config.landuse)
                    placeRunway(chunk,pos,bx,bz,
                        groundY,s,lat,lon);}
            case APRON->{
                if(config.landuse
                        &&s.containsPoint(lat,lon))
                    placeApron(chunk,pos,bx,bz,
                        groundY);}
            case POOL->{
                if(config.gardens)
                    placePool(chunk,pos,bx,bz,
                        groundY,s,lat,lon);}
            case PLATFORM->{
                if(config.railways)
                    placePlatform(chunk,pos,bx,bz,
                        groundY,s,lat,lon);}
        }}

    // ================================================================
    // NON-BUILDING
    // ================================================================

    private void processNonBuilding(ChunkAccess chunk,
            BlockPos.MutableBlockPos pos,
            int bx,int bz,int sy,
            OsmStructureIndex.OsmStructure s,
            double lat,double lon,
            List<OsmStructureIndex.OsmStructure> roads,
            List<OsmStructureIndex.OsmStructure> bridges,
            List<OsmStructureIndex.OsmStructure>
            railways){
        switch(s.type){
            case WALL->{
                if(config.walls)
                    placeLinearBarrier(chunk,pos,bx,bz,
                        sy,s,lat,lon,STONE_WALL,2);}
            case FENCE,BARRIER->{
                if(config.walls)
                    placeLinearBarrier(chunk,pos,bx,bz,
                        sy,s,lat,lon,OAK_FENCE,1);}
            case HEDGE->{
                if(config.walls)
                    placeLinearBarrier(chunk,pos,bx,bz,
                        sy,s,lat,lon,OAK_LEAVES,2);}
            case GARDEN,PARK->{
                if(config.gardens
                        &&s.containsPoint(lat,lon))
                    placePark(chunk,pos,bx,bz,sy,
                        lat,lon,roads,bridges);}
            case FARMLAND->{
                if(config.landuse
                        &&s.containsPoint(lat,lon))
                    placeFarmland(chunk,pos,bx,bz,sy);}
            case FOREST->{
                if(config.landuse
                        &&s.containsPoint(lat,lon))
                    placeForest(chunk,pos,bx,bz,sy,
                        lat,lon,roads,bridges);}
            case MEADOW->{
                if(config.landuse
                        &&s.containsPoint(lat,lon))
                    placeMeadow(chunk,pos,bx,bz,sy);}
            case PARKING->{
                if(config.landuse
                        &&s.containsPoint(lat,lon))
                    placeParking(chunk,pos,bx,bz,sy);}
            case STREET_LAMP->{
                if(config.streetLights)
                    placeStreetLamp(chunk,pos,bx,bz,sy,
                        s,lat,lon);}
            case SAND->{
                if(config.landuse
                        &&s.containsPoint(lat,lon))
                    placeSand(chunk,pos,bx,bz,sy);}
            case ROCK->{
                if(config.landuse
                        &&s.containsPoint(lat,lon))
                    placeRock(chunk,pos,bx,bz,sy);}
            case TREE_ROW->{
                if(config.landuse)
                    placeTreeRow(chunk,pos,bx,bz,sy,
                        s,lat,lon,roads,bridges);}
            case TREE->{
                if(config.landuse)
                    placeSingleTree(chunk,pos,bx,bz,sy,
                        s,lat,lon,roads,bridges);}
            case PIER->{
                if(config.bridges)
                    placePier(chunk,pos,bx,bz,sy,
                        findWaterSurface(chunk,pos,
                            bx,bz,sy),
                        s,lat,lon);}
            case TOWER->{
                if(config.buildings)
                    placeTower(chunk,pos,bx,bz,sy,
                        s,lat,lon);}
            case PYLON->{
                if(config.streetLights)
                    placePylon(chunk,pos,bx,bz,sy,
                        s,lat,lon);}
            case RUNWAY->{
                if(config.landuse)
                    placeRunway(chunk,pos,bx,bz,sy,
                        s,lat,lon);}
            case APRON->{
                if(config.landuse
                        &&s.containsPoint(lat,lon))
                    placeApron(chunk,pos,bx,bz,sy);}
            case POOL->{
                if(config.gardens)
                    placePool(chunk,pos,bx,bz,sy,
                        s,lat,lon);}
            case PLATFORM->{
                if(config.railways)
                    placePlatform(chunk,pos,bx,bz,sy,
                        s,lat,lon);}
            default->{}
        }}

    // ================================================================
    // GEOMETRY HELPERS
    // ================================================================

    private double edgeDistMeters(double lat,double lon,
            OsmStructureIndex.OsmStructure s){
        double min=Double.MAX_VALUE;
        for(int i=0;i<s.nodes.length;i++){
            int j=(i+1)%s.nodes.length;
            double d=OsmStructureIndex.OsmStructure
                .ptSegDist(lat,lon,
                    s.nodes[i].lat,s.nodes[i].lon,
                    s.nodes[j].lat,s.nodes[j].lon)
                *111320.0;
            if(d<min)min=d;}
        return min;}

    private double cornerDistMeters(double lat,
            double lon,
            OsmStructureIndex.OsmStructure s){
        double min=Double.MAX_VALUE;
        for(OsmStructureIndex.OsmNode n:s.nodes){
            double dL=(lat-n.lat)*111320.0;
            double dN=(lon-n.lon)*111320.0
                *Math.cos(Math.toRadians(lat));
            double d=Math.sqrt(dL*dL+dN*dN);
            if(d<min)min=d;}
        return min;}

    private boolean isWindowPosition(double lat,
            double lon,
            OsmStructureIndex.OsmStructure s,
            double edgeDist,int bh){
        if(edgeDist>WALL_THICKNESS_M)return false;
        int ce=findClosestEdge(lat,lon,s);
        if(ce<0)return false;
        double t=edgeParameter(lat,lon,s,ce);
        double len=edgeLengthMeters(s,ce);
        double posM=t*len;
        if(posM<1.0||posM>len-1.0)return false;
        return(posM%WINDOW_SPACING_M)>1.0
            &&(posM%WINDOW_SPACING_M)<2.5;}

    private int findClosestEdge(double lat,double lon,
            OsmStructureIndex.OsmStructure s){
        int c=-1;double min=Double.MAX_VALUE;
        for(int i=0;i<s.nodes.length;i++){
            int j=(i+1)%s.nodes.length;
            double d=OsmStructureIndex.OsmStructure
                .ptSegDist(lat,lon,
                    s.nodes[i].lat,s.nodes[i].lon,
                    s.nodes[j].lat,s.nodes[j].lon);
            if(d<min){min=d;c=i;}}
        return c;}

    private int findLongestEdge(
            OsmStructureIndex.OsmStructure s){
        int b=0;double ml=0;
        for(int i=0;i<s.nodes.length;i++){
            double l=edgeLengthMeters(s,i);
            if(l>ml){ml=l;b=i;}}
        return b;}

    private double edgeParameter(double lat,double lon,
            OsmStructureIndex.OsmStructure s,int ei){
        int j=(ei+1)%s.nodes.length;
        double dL=s.nodes[j].lat-s.nodes[ei].lat;
        double dN=s.nodes[j].lon-s.nodes[ei].lon;
        double l2=dL*dL+dN*dN;
        if(l2<1e-14)return 0.5;
        return Math.max(0,Math.min(1,
            ((lat-s.nodes[ei].lat)*dL
            +(lon-s.nodes[ei].lon)*dN)/l2));}

    private double edgeLengthMeters(
            OsmStructureIndex.OsmStructure s,int ei){
        int j=(ei+1)%s.nodes.length;
        double dL=(s.nodes[j].lat-s.nodes[ei].lat)
            *111320.0;
        double dN=(s.nodes[j].lon-s.nodes[ei].lon)
            *111320.0
            *Math.cos(Math.toRadians(s.nodes[ei].lat));
        return Math.sqrt(dL*dL+dN*dN);}

    private int findDoorEdge(
            OsmStructureIndex.OsmStructure building,
            List<OsmStructureIndex.OsmStructure> roads,
            int bh){
        if(building.nodes.length<3)return 0;
        int bestEdge=-1;
        double bestDist=Double.MAX_VALUE;
        for(int i=0;i<building.nodes.length;i++){
            int j=(i+1)%building.nodes.length;
            double midLat=
                (building.nodes[i].lat
                +building.nodes[j].lat)/2.0;
            double midLon=
                (building.nodes[i].lon
                +building.nodes[j].lon)/2.0;
            for(OsmStructureIndex.OsmStructure road:
                    roads){
                double dist=road.distanceToLine(
                    midLat,midLon);
                if(dist<bestDist){
                    bestDist=dist;bestEdge=i;}}}
        if(bestEdge>=0&&bestDist<50.0)return bestEdge;
        return findLongestEdge(building);}

    private boolean isDoorPosition(double lat,
            double lon,
            OsmStructureIndex.OsmStructure s,
            int edgeIdx){
        return Math.abs(edgeParameter(lat,lon,s,edgeIdx)
            -0.5)<0.6/Math.max(1.0,
                edgeLengthMeters(s,edgeIdx));}

    private int hashOsmId(long id){
        int h=(int)(id^(id>>>32));
        h=((h>>16)^h)*0x45d9f3b;
        h=((h>>16)^h)*0x45d9f3b;
        return(h^(h>>16))&0x7FFFFFFF;}

    private double distAlongLine(double lat,double lon,
            OsmStructureIndex.OsmStructure s,
            boolean fromStart){
        if(s.nodes.length<2)return 0;
        int nc=s.nodes.length;double td=0;
        if(fromStart){
            for(int i=0;i<nc-1;i++){
                double sl=haversineMeters(
                    s.nodes[i].lat,s.nodes[i].lon,
                    s.nodes[i+1].lat,s.nodes[i+1].lon);
                double ds=OsmStructureIndex.OsmStructure
                    .ptSegDist(lat,lon,
                        s.nodes[i].lat,s.nodes[i].lon,
                        s.nodes[i+1].lat,
                        s.nodes[i+1].lon)*111320.0;
                if(ds<20.0)return td
                    +projectOntoSegment(lat,lon,
                        s.nodes[i].lat,s.nodes[i].lon,
                        s.nodes[i+1].lat,
                        s.nodes[i+1].lon)*sl;
                td+=sl;}
        }else{
            for(int i=nc-1;i>0;i--){
                double sl=haversineMeters(
                    s.nodes[i].lat,s.nodes[i].lon,
                    s.nodes[i-1].lat,s.nodes[i-1].lon);
                double ds=OsmStructureIndex.OsmStructure
                    .ptSegDist(lat,lon,
                        s.nodes[i].lat,s.nodes[i].lon,
                        s.nodes[i-1].lat,
                        s.nodes[i-1].lon)*111320.0;
                if(ds<20.0)return td
                    +projectOntoSegment(lat,lon,
                        s.nodes[i].lat,s.nodes[i].lon,
                        s.nodes[i-1].lat,
                        s.nodes[i-1].lon)*sl;
                td+=sl;}}
        return td;}

    private double projectOntoSegment(double px,
            double py,double ax,double ay,
            double bx2,double by){
        double dx=bx2-ax,dy=by-ay;
        double l2=dx*dx+dy*dy;
        return l2<1e-14?0:Math.max(0,Math.min(1,
            ((px-ax)*dx+(py-ay)*dy)/l2));}

    private double haversineMeters(double lat1,
            double lon1,double lat2,double lon2){
        double dLat=Math.toRadians(lat2-lat1);
        double dLon=Math.toRadians(lon2-lon1);
        double a=Math.sin(dLat/2)*Math.sin(dLat/2)
            +Math.cos(Math.toRadians(lat1))
            *Math.cos(Math.toRadians(lat2))
            *Math.sin(dLon/2)*Math.sin(dLon/2);
        return 6371000.0*2*Math.atan2(
            Math.sqrt(a),Math.sqrt(1-a));}

    // ================================================================
    // ROAD HELPERS
    // ================================================================

    private double roadHW(String s,int l){
        double w=switch(s!=null?s:""){
            case "motorway","trunk"->7.0;
            case "motorway_link","trunk_link"->5.0;
            case "primary"->5.0;
            case "primary_link"->4.0;
            case "secondary"->4.5;
            case "tertiary"->3.5;
            case "residential","living_street"->3.0;
            case "service"->2.5;
            case "unclassified"->2.5;
            case "track"->2.0;
            default->3.0;};
        if(l>2)w=Math.max(w,l*1.8);
        return w;}

    /**
     * Half-width for a structure, honouring an explicit width=* tag
     * when the mapper provided one; falls back to the per-type guess.
     */
    private double roadHWFor(
            OsmStructureIndex.OsmStructure s){
        if(s.explicitWidth>0)
            return Math.min(s.explicitWidth/2.0,30.0);
        return roadHW(s.subType,s.lanes);}

    private boolean isMain(String s){
        return s!=null&&(s.equals("motorway")
            ||s.equals("trunk")
            ||s.equals("primary")
            ||s.equals("secondary"));}

    private boolean hasSW(String s){
        return s!=null&&(s.equals("residential")
            ||s.equals("living_street")
            ||s.equals("primary")
            ||s.equals("secondary")
            ||s.equals("tertiary"));}

    private void placeFootway(ChunkAccess chunk,
            BlockPos.MutableBlockPos pos,
            int bx,int bz,int sy,
            OsmStructureIndex.OsmStructure s,
            double lat,double lon){
        if(s.distanceToLine(lat,lon)
                >1.0*config.roadWidthScale)return;
        if(hasBuildingAbove(chunk,pos,bx,bz,sy))return;
        BlockState sf;
        if(s.type==OsmStructureIndex
                .StructureType.CYCLEWAY)
            sf=RED_CONCRETE;
        else if(s.surface!=null)
            sf=switch(s.surface.toLowerCase()){
                case "asphalt"->ROAD_SMOOTH;
                case "paving_stones"->STONE_BRICK;
                case "gravel"->ROAD_GRAVEL;
                default->ROAD_DIRT;};
        else sf=ROAD_DIRT;
        pos.set(bx,sy,bz);
        setOwned(chunk, pos,sf,false);
        clearSafe(chunk,pos,bx,bz,sy,1);}

    private void placeLinearBarrier(ChunkAccess chunk,
            BlockPos.MutableBlockPos pos,
            int bx,int bz,int sy,
            OsmStructureIndex.OsmStructure s,
            double lat,double lon,
            BlockState blk,int h){
        if(s.distanceToLine(lat,lon)>0.8)return;
        if(hasBuildingAbove(chunk,pos,bx,bz,sy))return;
        for(int y=1;y<=h;y++){
            pos.set(bx,sy+y,bz);
            setOwned(chunk, pos,blk,false);}}

    // ================================================================
    // LAND USE + TREE CORRIDOR PROTECTION
    // ================================================================

    private boolean countsForTreeTransportCheck(
            OsmStructureIndex.OsmStructure s){
        if(s==null)return false;
        return s.type==OsmStructureIndex
                .StructureType.ROAD
            ||s.type==OsmStructureIndex
                .StructureType.BRIDGE;}

    private double transportHalfWidthForTrees(
            OsmStructureIndex.OsmStructure s){
        if(s==null)return 0.0;
        if(s.type==OsmStructureIndex
                .StructureType.BRIDGE){
            if(s.isRailBridge)return 2.5;
            return roadHW(
                s.subType!=null
                ?s.subType:"secondary",s.lanes)
                *config.roadWidthScale;}
        if(s.type==OsmStructureIndex
                .StructureType.ROAD)
            return roadHW(s.subType,s.lanes)
                *config.roadWidthScale;
        return 0.0;}

    private double nearestSegmentAngleDeg(
            OsmStructureIndex.OsmStructure s,
            double lat,double lon){
        if(s==null||s.nodes==null
                ||s.nodes.length<2)return 0.0;
        int best=0;double min=Double.MAX_VALUE;
        for(int i=0;i<s.nodes.length-1;i++){
            double d=OsmStructureIndex.OsmStructure
                .ptSegDist(lat,lon,
                    s.nodes[i].lat,s.nodes[i].lon,
                    s.nodes[i+1].lat,s.nodes[i+1].lon)
                *111320.0;
            if(d<min){min=d;best=i;}}
        OsmStructureIndex.OsmNode a=s.nodes[best];
        OsmStructureIndex.OsmNode b=s.nodes[best+1];
        double meanLat=(a.lat+b.lat)*0.5;
        double dx=(b.lon-a.lon)*111320.0
            *Math.cos(Math.toRadians(meanLat));
        double dz=(b.lat-a.lat)*111320.0;
        double ang=Math.toDegrees(Math.atan2(dz,dx));
        while(ang<0.0)ang+=180.0;
        while(ang>=180.0)ang-=180.0;
        return ang;}

    private double signedDistanceToNearestSegmentMeters(
            OsmStructureIndex.OsmStructure s,
            double lat,double lon){
        if(s==null||s.nodes==null
                ||s.nodes.length<2)
            return Double.MAX_VALUE;
        int best=0;double min=Double.MAX_VALUE;
        for(int i=0;i<s.nodes.length-1;i++){
            double d=OsmStructureIndex.OsmStructure
                .ptSegDist(lat,lon,
                    s.nodes[i].lat,s.nodes[i].lon,
                    s.nodes[i+1].lat,s.nodes[i+1].lon)
                *111320.0;
            if(d<min){min=d;best=i;}}
        OsmStructureIndex.OsmNode a=s.nodes[best];
        OsmStructureIndex.OsmNode b=s.nodes[best+1];
        double meanLat=(a.lat+b.lat)*0.5;
        double vx=(b.lon-a.lon)*111320.0
            *Math.cos(Math.toRadians(meanLat));
        double vz=(b.lat-a.lat)*111320.0;
        double px=(lon-a.lon)*111320.0
            *Math.cos(Math.toRadians(meanLat));
        double pz=(lat-a.lat)*111320.0;
        double len=Math.sqrt(vx*vx+vz*vz);
        if(len<1e-9)return 0.0;
        return(vx*pz-vz*px)/len;}

    private boolean isTooCloseToTransportForTree(
            double lat,double lon,
            List<OsmStructureIndex.OsmStructure> roads,
            List<OsmStructureIndex.OsmStructure>
            bridges){
        for(OsmStructureIndex.OsmStructure r:roads){
            if(!countsForTreeTransportCheck(r))continue;
            double hw=transportHalfWidthForTrees(r);
            if(r.distanceToLine(lat,lon)
                    <=hw+TREE_TRANSPORT_CLEARANCE_M)
                return true;}
        for(OsmStructureIndex.OsmStructure b:bridges){
            if(!countsForTreeTransportCheck(b))continue;
            double hw=transportHalfWidthForTrees(b);
            if(b.distanceToLine(lat,lon)
                    <=hw+TREE_TRANSPORT_CLEARANCE_M)
                return true;}
        return false;}

    private boolean isBetweenParallelTransportWays(
            double lat,double lon,
            List<OsmStructureIndex.OsmStructure> roads,
            List<OsmStructureIndex.OsmStructure>
            bridges){
        List<OsmStructureIndex.OsmStructure>
            nearby=new ArrayList<>();
        for(OsmStructureIndex.OsmStructure r:roads){
            if(!countsForTreeTransportCheck(r))continue;
            double hw=transportHalfWidthForTrees(r);
            if(r.distanceToLine(lat,lon)
                    <=hw+TREE_CORRIDOR_BUFFER_M)
                nearby.add(r);}
        for(OsmStructureIndex.OsmStructure b:bridges){
            if(!countsForTreeTransportCheck(b))continue;
            double hw=transportHalfWidthForTrees(b);
            if(b.distanceToLine(lat,lon)
                    <=hw+TREE_CORRIDOR_BUFFER_M)
                nearby.add(b);}
        if(nearby.size()<2)return false;
        for(int i=0;i<nearby.size();i++){
            OsmStructureIndex.OsmStructure a=
                nearby.get(i);
            double angA=nearestSegmentAngleDeg(
                a,lat,lon);
            double sdA=
                signedDistanceToNearestSegmentMeters(
                    a,lat,lon);
            for(int j=i+1;j<nearby.size();j++){
                OsmStructureIndex.OsmStructure b=
                    nearby.get(j);
                if(a.osmId!=0&&b.osmId!=0
                        &&a.osmId==b.osmId)continue;
                double angB=nearestSegmentAngleDeg(
                    b,lat,lon);
                double rawDiff=Math.abs(angA-angB);
                if(rawDiff>180.0)rawDiff=360.0-rawDiff;
                double parallelDiff=Math.min(
                    rawDiff,180.0-rawDiff);
                if(parallelDiff>
                        PARALLEL_ANGLE_TOLERANCE_DEG)
                    continue;
                double sdB=
                    signedDistanceToNearestSegmentMeters(
                        b,lat,lon);
                if(rawDiff>90.0)sdB=-sdB;
                boolean between=
                    (sdA<0.0&&sdB>0.0)
                    ||(sdA>0.0&&sdB<0.0)
                    ||Math.abs(sdA)<0.75
                    ||Math.abs(sdB)<0.75;
                if(between)return true;}}
        return false;}

    private boolean shouldBlockTreeAt(double lat,
            double lon,
            List<OsmStructureIndex.OsmStructure> roads,
            List<OsmStructureIndex.OsmStructure>
            bridges){
        return isTooCloseToTransportForTree(
                lat,lon,roads,bridges)
            ||isBetweenParallelTransportWays(
                lat,lon,roads,bridges);}

    private void placePark(ChunkAccess c,
            BlockPos.MutableBlockPos p,
            int bx,int bz,int sy,
            double lat,double lon,
            List<OsmStructureIndex.OsmStructure> roads,
            List<OsmStructureIndex.OsmStructure>
            bridges){
        if(hasBuildingAbove(c,p,bx,bz,sy))return;
        int h=TerraDensityFunctions.blockHash(bx,bz);
        p.set(bx,sy,bz);
        setOwned(c, p,GRASS_BLOCK,false);
        int pk=h%40;
        if(pk<3){
            p.set(bx,sy+1,bz);
            setOwned(c, p,pickF(h),false);}
        else if(pk<8){
            p.set(bx,sy+1,bz);
            setOwned(c, p,SHORT_GRASS,false);}
        else if(pk==8)
            placeTree(c,p,bx,bz,sy,h,lat,lon,
                roads,bridges);}

    private void placeFarmland(ChunkAccess c,
            BlockPos.MutableBlockPos p,
            int bx,int bz,int sy){
        if(hasBuildingAbove(c,p,bx,bz,sy))return;
        int h=TerraDensityFunctions.blockHash(bx,bz);
        p.set(bx,sy,bz);
        setOwned(c, p,FARMLAND_BLOCK,false);
        if(h%4!=0){
            p.set(bx,sy+1,bz);
            setOwned(c, p,WHEAT_BLOCK,false);}}

    private void placeForest(ChunkAccess c,
            BlockPos.MutableBlockPos p,
            int bx,int bz,int sy,
            double lat,double lon,
            List<OsmStructureIndex.OsmStructure> roads,
            List<OsmStructureIndex.OsmStructure>
            bridges){
        if(hasBuildingAbove(c,p,bx,bz,sy))return;
        int h=TerraDensityFunctions.blockHash(bx,bz);
        p.set(bx,sy,bz);
        setOwned(c, p,GRASS_BLOCK,false);
        if(h%7==0)placeTree(c,p,bx,bz,sy,h,lat,lon,
            roads,bridges);
        else if(h%4==0){
            p.set(bx,sy+1,bz);
            setOwned(c, p,SHORT_GRASS,false);}}

    private void placeMeadow(ChunkAccess c,
            BlockPos.MutableBlockPos p,
            int bx,int bz,int sy){
        if(hasBuildingAbove(c,p,bx,bz,sy))return;
        int h=TerraDensityFunctions.blockHash(bx,bz);
        p.set(bx,sy,bz);
        setOwned(c, p,GRASS_BLOCK,false);
        if(h%5==0){
            p.set(bx,sy+1,bz);
            setOwned(c, p,SHORT_GRASS,false);}
        else if(h%12==0){
            p.set(bx,sy+1,bz);
            setOwned(c, p,pickF(h),false);}}

    private void placeParking(ChunkAccess c,
            BlockPos.MutableBlockPos p,
            int bx,int bz,int sy){
        if(hasBuildingAbove(c,p,bx,bz,sy))return;
        int h=TerraDensityFunctions.blockHash(bx,bz);
        p.set(bx,sy,bz);
        setOwned(c, p,PARKING_BLOCK,false);
        clearSafe(c,p,bx,bz,sy,1);
        if(h%8==0&&markingsExistNow()){
            p.set(bx,sy,bz);
            setOwned(c, p,
                ROAD_MARKING_WHITE,false);}}

    private void placeStreetLamp(ChunkAccess c,
            BlockPos.MutableBlockPos p,
            int bx,int bz,int sy,
            OsmStructureIndex.OsmStructure s,
            double lat,double lon){
        if(s.nodes.length==0)return;
        double dL=(lat-s.nodes[0].lat)*111320.0;
        double dN=(lon-s.nodes[0].lon)*111320.0
            *Math.cos(Math.toRadians(lat));
        if(Math.sqrt(dL*dL+dN*dN)>1.0)return;
        if(hasBuildingAbove(c,p,bx,bz,sy))return;
        int my=c.getMaxBuildHeight()-2;
        // Era-aware street lighting:
        //   before 1800 — no street lamps at all
        //   1800-1880   — gas lamps: short oak post + lantern
        //   after 1880  — electric: iron pole + sea lantern
        int lampYear=net.buildtheearth.terraplusplus
            .generator.historical.TerraTimeManager
            .isTimeEnabled()
            ?net.buildtheearth.terraplusplus.generator
                .historical.TerraTimeManager
                .getCurrentYear()
            :9999;
        if(lampYear<1800)return;
        if(lampYear<1880){
            for(int y=1;y<=2&&sy+y<=my;y++){
                p.set(bx,sy+y,bz);
                setOwned(c, p,OAK_FENCE,false);}
            if(sy+3<=my){
                p.set(bx,sy+3,bz);
                setOwned(c, p,LANTERN,false);}
            return;}
        for(int y=1;y<=4&&sy+y<=my;y++){
            p.set(bx,sy+y,bz);
            setOwned(c, p,IRON_BARS,false);}
        if(sy+5<=my){
            p.set(bx,sy+5,bz);
            setOwned(c, p,SEA_LANTERN,false);}}

    // ================================================================
    // EXTENDED COVERAGE PLACEMENT
    // (natural / man_made / aeroway / power / leisure extras)
    // ================================================================

    /** natural=beach|sand — sand surface. */
    private void placeSand(ChunkAccess c,
            BlockPos.MutableBlockPos p,
            int bx,int bz,int sy){
        if(hasBuildingAbove(c,p,bx,bz,sy))return;
        p.set(bx,sy,bz);
        setOwned(c, p,SAND_BLOCK,false);}

    /** natural=bare_rock|scree|shingle, landuse=quarry. */
    private void placeRock(ChunkAccess c,
            BlockPos.MutableBlockPos p,
            int bx,int bz,int sy){
        if(hasBuildingAbove(c,p,bx,bz,sy))return;
        int h=TerraDensityFunctions.blockHash(bx,bz);
        p.set(bx,sy,bz);
        setOwned(c, p,
            h%3==0?COBBLESTONE:STONE_BLOCK,false);}

    /** natural=tree_row — trees along a line. */
    private void placeTreeRow(ChunkAccess c,
            BlockPos.MutableBlockPos p,
            int bx,int bz,int sy,
            OsmStructureIndex.OsmStructure s,
            double lat,double lon,
            List<OsmStructureIndex.OsmStructure> roads,
            List<OsmStructureIndex.OsmStructure>
            bridges){
        if(s.distanceToLine(lat,lon)>1.5)return;
        int h=TerraDensityFunctions.blockHash(bx,bz);
        // one tree roughly every 5th column on the line
        if(h%5!=0)return;
        if(hasBuildingAbove(c,p,bx,bz,sy))return;
        placeTree(c,p,bx,bz,sy,h,lat,lon,
            roads,bridges);}

    /** natural=tree — a single mapped tree node. */
    private void placeSingleTree(ChunkAccess c,
            BlockPos.MutableBlockPos p,
            int bx,int bz,int sy,
            OsmStructureIndex.OsmStructure s,
            double lat,double lon,
            List<OsmStructureIndex.OsmStructure> roads,
            List<OsmStructureIndex.OsmStructure>
            bridges){
        if(s.nodes.length==0)return;
        double cosLat=Math.cos(Math.toRadians(lat));
        double dL=(lat-s.nodes[0].lat)*111320.0;
        double dN=(lon-s.nodes[0].lon)*111320.0*cosLat;
        if(Math.sqrt(dL*dL+dN*dN)>1.0)return;
        if(hasBuildingAbove(c,p,bx,bz,sy))return;
        placeTree(c,p,bx,bz,sy,
            TerraDensityFunctions.blockHash(bx,bz),
            lat,lon,roads,bridges);}

    /** man_made=pier|breakwater|groyne|embankment|dyke. */
    private void placePier(ChunkAccess c,
            BlockPos.MutableBlockPos p,
            int bx,int bz,int sy,int waterY,
            OsmStructureIndex.OsmStructure s,
            double lat,double lon){
        boolean stone=s.subType!=null
            &&!s.subType.equals("pier");
        double hw=s.explicitWidth>0
            ?Math.min(s.explicitWidth/2.0,10.0)
            :(stone?2.0:1.5);
        boolean inside=s.isArea
            ?s.containsPoint(lat,lon)
            :s.distanceToLine(lat,lon)<=hw;
        if(!inside)return;
        int my=c.getMaxBuildHeight()-2;
        // deck sits just above the water surface (or ground on land)
        int deckY=Math.min(my,
            Math.max(sy,waterY)+1);
        p.set(bx,deckY,bz);
        setOwned(c, p,
            stone?STONE_BRICK:PIER_DECK,false);
        if(stone){
            // solid fill down to the sea/river bed
            for(int y=deckY-1;y>=sy&&y>=c
                    .getMinBuildHeight();y--){
                p.set(bx,y,bz);
                setOwned(c, p,COBBLESTONE,false);}
        }else{
            // wooden piles on a sparse grid
            int h=TerraDensityFunctions
                .blockHash(bx,bz);
            if(h%9==0)
                for(int y=deckY-1;y>=sy&&y>=c
                        .getMinBuildHeight();y--){
                    p.set(bx,y,bz);
                    setOwned(c, p,OAK_LOG,false);}
        }
        // keep the deck walkable
        for(int y=deckY+1;y<=deckY+2&&y<=my;y++){
            p.set(bx,y,bz);
            if(!c.getBlockState(p).isAir())
                setOwned(c, p,AIR,false);}}

    /** man_made towers, tanks, chimneys, masts (node or small way). */
    private void placeTower(ChunkAccess c,
            BlockPos.MutableBlockPos p,
            int bx,int bz,int sy,
            OsmStructureIndex.OsmStructure s,
            double lat,double lon){
        // Areas (e.g. storage tanks mapped as ways) fill their
        // polygon; node features build at the node position.
        double r;
        boolean inside;
        if(s.isArea&&s.nodes.length>=3){
            inside=s.containsPoint(lat,lon);
            r=-1;
        }else{
            if(s.nodes.length==0)return;
            double cosLat=Math.cos(
                Math.toRadians(lat));
            double dL=(lat-s.nodes[0].lat)*111320.0;
            double dN=(lon-s.nodes[0].lon)
                *111320.0*cosLat;
            r=Math.sqrt(dL*dL+dN*dN);
            String st=s.subType!=null?s.subType:"";
            double rad=switch(st){
                case "storage_tank","silo"->4.0;
                case "water_tower","tower",
                     "communications_tower",
                     "lighthouse","chimney"->2.0;
                default->0.6;};       // mast/flagpole: single column
            inside=r<=rad;
        }
        if(!inside)return;
        if(hasBuildingAbove(c,p,bx,bz,sy))return;
        String st=s.subType!=null?s.subType:"";
        int hgt=s.resolvedHeightBlocks();
        if(hgt<=0)hgt=switch(st){
            case "communications_tower"->40;
            case "chimney"->25;
            case "tower","lighthouse"->18;
            case "water_tower"->14;
            case "mast"->16;
            case "flagpole"->8;
            case "storage_tank","silo"->10;
            default->12;};
        int my=c.getMaxBuildHeight()-2;
        BlockState wall=switch(st){
            case "storage_tank","silo"->TANK_WALL;
            case "mast","flagpole",
                 "communications_tower"->MAST_FRAME;
            case "chimney"->BRICK;
            case "lighthouse"->WHITE_CONCRETE;
            default->TOWER_WALL;};
        for(int y=1;y<=hgt&&sy+y<=my;y++){
            p.set(bx,sy+y,bz);
            setOwned(c, p,wall,false);}
        // beacon on lighthouses and comms towers
        if((st.equals("lighthouse")
                ||st.equals("communications_tower"))
                &&sy+hgt+1<=my){
            p.set(bx,sy+hgt+1,bz);
            setOwned(c, p,SEA_LANTERN,false);}}

    /** power=tower|pole — pylon lattice column. */
    private void placePylon(ChunkAccess c,
            BlockPos.MutableBlockPos p,
            int bx,int bz,int sy,
            OsmStructureIndex.OsmStructure s,
            double lat,double lon){
        if(s.nodes.length==0)return;
        double cosLat=Math.cos(Math.toRadians(lat));
        double dL=(lat-s.nodes[0].lat)*111320.0;
        double dN=(lon-s.nodes[0].lon)*111320.0*cosLat;
        if(Math.sqrt(dL*dL+dN*dN)>1.0)return;
        if(hasBuildingAbove(c,p,bx,bz,sy))return;
        int hgt="pole".equals(s.subType)?8:22;
        int my=c.getMaxBuildHeight()-2;
        for(int y=1;y<=hgt&&sy+y<=my;y++){
            p.set(bx,sy+y,bz);
            setOwned(c, p,MAST_FRAME,false);}}

    /** aeroway=runway|taxiway — wide paved strip. */
    private void placeRunway(ChunkAccess c,
            BlockPos.MutableBlockPos p,
            int bx,int bz,int sy,
            OsmStructureIndex.OsmStructure s,
            double lat,double lon){
        boolean runway=
            "runway".equals(s.subType);
        double hw=s.explicitWidth>0
            ?Math.min(s.explicitWidth/2.0,40.0)
            :(runway?22.0:11.0);
        hw*=config.roadWidthScale;
        double dist=s.distanceToLine(lat,lon);
        if(dist>hw)return;
        if(hasBuildingAbove(c,p,bx,bz,sy))return;
        p.set(bx,sy,bz);
        setOwned(c, p,RUNWAY_SURFACE,false);
        clearSafe(c,p,bx,bz,sy,2);
        // centreline markings on runways
        if(runway&&dist<0.6){
            int h=TerraDensityFunctions
                .blockHash(bx,bz);
            if(h%2==0){
                p.set(bx,sy,bz);
                setOwned(c, p,
                    ROAD_MARKING_WHITE,false);}}}

    /** aeroway=apron|helipad — paved area. */
    private void placeApron(ChunkAccess c,
            BlockPos.MutableBlockPos p,
            int bx,int bz,int sy){
        if(hasBuildingAbove(c,p,bx,bz,sy))return;
        p.set(bx,sy,bz);
        setOwned(c, p,APRON_SURFACE,false);
        clearSafe(c,p,bx,bz,sy,1);}

    /** leisure=swimming_pool — water with a light rim. */
    private void placePool(ChunkAccess c,
            BlockPos.MutableBlockPos p,
            int bx,int bz,int sy,
            OsmStructureIndex.OsmStructure s,
            double lat,double lon){
        if(!s.containsPoint(lat,lon))return;
        if(hasBuildingAbove(c,p,bx,bz,sy))return;
        double ed=edgeDistMeters(lat,lon,s);
        p.set(bx,sy,bz);
        if(ed<1.0)
            setOwned(c, p,POOL_RIM,false);
        else{
            setOwned(c, p,WATER_BLOCK,false);
            p.set(bx,sy-1,bz);
            setOwned(c, p,POOL_RIM,false);}}

    /** railway=platform — raised platform strip/area. */
    private void placePlatform(ChunkAccess c,
            BlockPos.MutableBlockPos p,
            int bx,int bz,int sy,
            OsmStructureIndex.OsmStructure s,
            double lat,double lon){
        boolean inside=s.isArea&&s.nodes.length>=3
            ?s.containsPoint(lat,lon)
            :s.distanceToLine(lat,lon)
                <=(s.explicitWidth>0
                    ?Math.min(s.explicitWidth/2.0,6.0)
                    :2.0);
        if(!inside)return;
        if(hasBuildingAbove(c,p,bx,bz,sy))return;
        int my=c.getMaxBuildHeight()-2;
        if(sy+1<=my){
            p.set(bx,sy+1,bz);
            setOwned(c, p,PLATFORM_SURFACE,false);
            clearSafe(c,p,bx,bz,sy+1,1);}}

    // ================================================================
    // HEIGHT / LEVEL
    // ================================================================

    private int resolveWallHeight(
            OsmStructureIndex.OsmStructure s){
        if(s.height>0){
            int h=(int)Math.ceil(s.height);
            if(s.roofHeight>0)
                h=Math.max(FLOOR_HEIGHT,
                    h-(int)Math.ceil(s.roofHeight));
            return Math.min(h,512);}
        if(s.levels>0)
            return Math.min(s.levels*FLOOR_HEIGHT,512);
        return estimateLevels(s)*FLOOR_HEIGHT;}

    private int estimateLevels(
            OsmStructureIndex.OsmStructure s){
        if(s.levels>0)return s.levels;
        if(s.nodes==null||s.nodes.length<3
                ||Double.isNaN(s.minLat)
                ||Double.isNaN(s.maxLat))return 1;
        double cl=(s.minLat+s.maxLat)/2.0;
        double am=(s.maxLat-s.minLat)*111320.0
            *(s.maxLon-s.minLon)*111320.0
            *Math.cos(Math.toRadians(cl));
        if(am<50)return 1;
        if(am<150)return 2;
        if(am<500)return 3;
        if(am<2000)return 4;
        return 5;}

    private double maxInsetMeters(
            OsmStructureIndex.OsmStructure s){
        double cl=(s.minLat+s.maxLat)/2.0;
        return Math.min(
            (s.maxLat-s.minLat)*111320.0,
            (s.maxLon-s.minLon)*111320.0
            *Math.cos(Math.toRadians(cl)))/2.0;}

    private RoofType resolveRoofType(String roofShape,
            int hash,int levels){
        if(roofShape!=null)
            return switch(roofShape.toLowerCase()){
                case "gabled"->RoofType.GABLED;
                case "hipped"->RoofType.HIPPED;
                case "pyramidal"->RoofType.PYRAMIDAL;
                case "dome"->RoofType.DOME;
                case "onion"->RoofType.ONION;
                case "mansard"->RoofType.MANSARD;
                default->RoofType.FLAT;};
        if(levels>=4)return RoofType.FLAT;
        return(hash%3==0)?RoofType.GABLED
            :RoofType.FLAT;}

    // ================================================================
    // BUILDING STYLES
    // ================================================================

    private BuildingStyle colourStyle(
            OsmStructureIndex.OsmStructure s,int h){
        BlockState w=colourToBlock(s.colour,false);
        BlockState r=s.roofColour!=null
            ?colourToBlock(s.roofColour,true)
            :selRoof(s.roofMaterial,h);
        return new BuildingStyle(w,darken(w,h),
            pillar(w),FLOOR_STONE,ROOF_SMOOTH_STONE,r,
            GLASS_PANE,OAK_DOOR_L,OAK_DOOR_U,
            COBBLESTONE,LANTERN,selStepBlock(w,h),
            resolveRoofType(s.roofShape,h,
                estimateLevels(s)));}

    private BuildingStyle determineBuildingStyle(
            OsmStructureIndex.OsmStructure s,int hash){
        String mat=s.material!=null
            ?s.material.toLowerCase():"";
        String sub=s.subType!=null
            ?s.subType.toLowerCase():"yes";
        int levels=estimateLevels(s);
        RoofType rt=resolveRoofType(
            s.roofShape,hash,levels);
        if(!mat.isEmpty())return matStyle(mat,
            s.roofMaterial!=null
            ?s.roofMaterial.toLowerCase():"",
            rt,hash);
        return switch(sub){
            case "apartments","residential",
                 "house","detached",
                 "semidetached_house","terrace"
                ->resStyle(hash,rt,levels);
            case "commercial","office",
                 "retail","shop"
                ->comStyle(hash,rt,levels);
            case "industrial","warehouse",
                 "hangar","factory"
                ->indStyle(hash,rt);
            case "church","cathedral","chapel",
                 "temple","mosque","synagogue"
                ->relStyle(hash,rt);
            case "school","university","college",
                 "kindergarten"
                ->eduStyle(hash,rt,levels);
            case "hospital","clinic"
                ->hosStyle(hash);
            case "hotel","motel"
                ->hotStyle(hash,rt,levels);
            case "garage","shed","cabin","hut"
                ->smlStyle(hash);
            case "barn","farm","farm_auxiliary"
                ->frmStyle(hash);
            case "train_station","station",
                 "transportation","transport",
                 "public_transport","bus_station",
                 "terminal","railway_station",
                 "tram_station","subway_station",
                 "monorail_station"
                ->staStyle(hash,rt,levels);
            default->defStyle(hash,rt,levels);
        };}

    private BuildingStyle matStyle(String m,String rm,
            RoofType rt,int h){
        BlockState w=switch(m){
            case "brick"->BRICK;
            case "stone"->STONE_BRICK;
            case "concrete"->selCon(h);
            case "wood","timber"->selWood(h);
            case "glass"->LIGHT_BLUE_GLASS;
            case "metal","steel"->GRAY_CONCRETE;
            case "plaster","render"->WHITE_CONCRETE;
            default->BRICK;};
        return new BuildingStyle(w,darken(w,h),
            pillar(w),FLOOR_STONE,ROOF_SMOOTH_STONE,
            selRoof(rm,h),GLASS_PANE,
            OAK_DOOR_L,OAK_DOOR_U,COBBLESTONE,LANTERN,
            selStepBlock(w,h),rt);}

    private BuildingStyle resStyle(int h,RoofType rt,
            int l){
        int v=h%8;
        BlockState w=switch(v){
            case 0->BRICK;
            case 1->WHITE_TERRACOTTA;
            case 2->LIGHT_GRAY_TERRACOTTA;
            case 3->BROWN_TERRACOTTA;
            case 4->WHITE_CONCRETE;
            case 5->ORANGE_TERRACOTTA;
            case 6->YELLOW_TERRACOTTA;
            default->LIGHT_GRAY_CONCRETE;};
        if(l<=2&&rt==RoofType.FLAT)
            rt=RoofType.GABLED;
        return new BuildingStyle(w,darken(w,h),
            (v<=3)?STONE_BRICK:POLISHED_ANDESITE,
            (h%2==0)?FLOOR_OAK:FLOOR_SPRUCE,
            selRoof("",h),selRoof("",h),GLASS_PANE,
            OAK_DOOR_L,OAK_DOOR_U,COBBLESTONE,LANTERN,
            selStepBlock(w,h),rt);}

    private BuildingStyle comStyle(int h,RoofType rt,
            int l){
        BlockState w=switch(h%6){
            case 0->LIGHT_GRAY_CONCRETE;
            case 1->WHITE_CONCRETE;
            case 2->GRAY_CONCRETE;
            case 3->QUARTZ;
            case 4->SMOOTH_QUARTZ;
            default->POLISHED_DIORITE;};
        return new BuildingStyle(w,GRAY_CONCRETE,
            QUARTZ_PILLAR,FLOOR_POLISHED,
            ROOF_SMOOTH_STONE,ROOF_SMOOTH_STONE,
            (l>=3)?LIGHT_BLUE_GLASS:GLASS_PANE,
            IRON_DOOR_L,IRON_DOOR_U,SMOOTH_STONE,
            SEA_LANTERN,selStepBlock(w,h),
            (l>=4)?RoofType.FLAT:rt);}

    private BuildingStyle indStyle(int h,RoofType rt){
        BlockState w=switch(h%3){
            case 0->GRAY_CONCRETE;
            case 1->LIGHT_GRAY_CONCRETE;
            default->COBBLESTONE;};
        return new BuildingStyle(w,GRAY_CONCRETE,
            GRAY_CONCRETE,FLOOR_STONE,ROOF_COBBLE,
            ROOF_COBBLE,GLASS_PANE,
            IRON_DOOR_L,IRON_DOOR_U,COBBLESTONE,
            GLOWSTONE,selStepBlock(w,h),RoofType.FLAT);}

    private BuildingStyle relStyle(int h,RoofType rt){
        return new BuildingStyle(STONE_BRICK,
            POLISHED_ANDESITE,QUARTZ_PILLAR,
            FLOOR_STONE,ROOF_STONE_BRICK,
            ROOF_STONE_BRICK,GLASS_PANE,
            OAK_DOOR_L,OAK_DOOR_U,STONE_BRICK,LANTERN,
            STONE_BRICK,RoofType.PYRAMIDAL);}

    private BuildingStyle eduStyle(int h,RoofType rt,
            int l){
        BlockState w=(h%2==0)?BRICK:RED_TERRACOTTA;
        return new BuildingStyle(w,STONE_BRICK,
            STONE_BRICK,FLOOR_STONE,ROOF_BRICK,
            ROOF_BRICK,GLASS_PANE,
            IRON_DOOR_L,IRON_DOOR_U,STONE_BRICK,LANTERN,
            selStepBlock(w,h),
            (l>=3)?RoofType.FLAT:RoofType.GABLED);}

    private BuildingStyle hosStyle(int h){
        return new BuildingStyle(WHITE_CONCRETE,
            LIGHT_GRAY_CONCRETE,WHITE_CONCRETE,
            FLOOR_STONE,ROOF_SMOOTH_STONE,
            ROOF_SMOOTH_STONE,GLASS_PANE,
            IRON_DOOR_L,IRON_DOOR_U,SMOOTH_STONE,
            SEA_LANTERN,SMOOTH_QUARTZ,RoofType.FLAT);}

    private BuildingStyle hotStyle(int h,RoofType rt,
            int l){
        BlockState w=switch(h%3){
            case 0->QUARTZ;
            case 1->WHITE_CONCRETE;
            default->SMOOTH_QUARTZ;};
        return new BuildingStyle(w,GRAY_CONCRETE,
            QUARTZ_PILLAR,FLOOR_POLISHED,
            ROOF_SMOOTH_STONE,ROOF_SMOOTH_STONE,
            LIGHT_BLUE_GLASS,IRON_DOOR_L,IRON_DOOR_U,
            SMOOTH_QUARTZ,SEA_LANTERN,selStepBlock(w,h),
            (l>=4)?RoofType.FLAT:rt);}

    private BuildingStyle smlStyle(int h){
        BlockState w=switch(h%3){
            case 0->OAK_PLANKS;
            case 1->COBBLESTONE;
            default->SPRUCE_PLANKS;};
        return new BuildingStyle(w,w,OAK_LOG,
            FLOOR_OAK,ROOF_DARK_OAK,ROOF_DARK_OAK,
            GLASS_PANE,OAK_DOOR_L,OAK_DOOR_U,
            COBBLESTONE,LANTERN,selStepBlock(w,h),
            RoofType.GABLED);}

    private BuildingStyle frmStyle(int h){
        BlockState w=(h%2==0)?OAK_PLANKS:SPRUCE_PLANKS;
        return new BuildingStyle(w,STRIPPED_OAK_LOG,
            OAK_LOG,FLOOR_OAK,ROOF_SPRUCE,ROOF_SPRUCE,
            GLASS_PANE,SPRUCE_DOOR_L,SPRUCE_DOOR_U,
            COBBLESTONE,LANTERN,selStepBlock(w,h),
            RoofType.GABLED);}

    private BuildingStyle staStyle(int h,RoofType rt,
            int l){
        BlockState w=switch(h%3){
            case 0->STONE_BRICK;
            case 1->SMOOTH_STONE;
            default->POLISHED_ANDESITE;};
        return new BuildingStyle(w,POLISHED_ANDESITE,
            QUARTZ_PILLAR,FLOOR_STONE,ROOF_STONE_BRICK,
            ROOF_STONE_BRICK,GLASS_PANE,
            IRON_DOOR_L,IRON_DOOR_U,STONE_BRICK,
            SEA_LANTERN,selStepBlock(w,h),
            (l>=2)?RoofType.FLAT:RoofType.HIPPED);}

    private BuildingStyle defStyle(int h,RoofType rt,
            int l){
        BlockState w=switch(h%12){
            case 0->BRICK;
            case 1->STONE_BRICK;
            case 2->WHITE_CONCRETE;
            case 3->LIGHT_GRAY_CONCRETE;
            case 4->WHITE_TERRACOTTA;
            case 5->COBBLESTONE;
            case 6->BROWN_TERRACOTTA;
            case 7->LIGHT_GRAY_TERRACOTTA;
            case 8->ORANGE_TERRACOTTA;
            case 9->CYAN_TERRACOTTA;
            case 10->OAK_PLANKS;
            default->GRAY_CONCRETE;};
        return new BuildingStyle(w,darken(w,h),
            pillar(w),(h%2==0)?FLOOR_OAK:FLOOR_STONE,
            selRoof("",h),selRoof("",h),GLASS_PANE,
            OAK_DOOR_L,OAK_DOOR_U,COBBLESTONE,LANTERN,
            selStepBlock(w,h),rt);}

    // ================================================================
    // MATERIAL SELECTORS
    // ================================================================

    private BlockState selCon(int h){
        return switch(h%8){
            case 0->WHITE_CONCRETE;
            case 1->LIGHT_GRAY_CONCRETE;
            case 2->GRAY_CONCRETE;
            case 3->BROWN_CONCRETE;
            case 4->CYAN_CONCRETE;
            case 5->YELLOW_CONCRETE;
            case 6->ORANGE_CONCRETE;
            default->LIME_CONCRETE;};}

    private BlockState selWood(int h){
        return switch(h%4){
            case 0->OAK_PLANKS;
            case 1->SPRUCE_PLANKS;
            case 2->BIRCH_PLANKS;
            default->DARK_OAK_PLANKS;};}

    private BlockState selRoof(String m,int h){
        if(m!=null&&!m.isEmpty())
            return switch(m.toLowerCase()){
                case "tile","roof_tiles"->ROOF_BRICK;
                case "metal","steel","tin"
                    ->ROOF_STONE_BRICK;
                case "wood","thatch"->ROOF_DARK_OAK;
                case "slate"->ROOF_COBBLE;
                default->ROOF_STONE_BRICK;};
        return switch(h%5){
            case 0->ROOF_BRICK;
            case 1->ROOF_DARK_OAK;
            case 2->ROOF_SPRUCE;
            case 3->ROOF_COBBLE;
            default->ROOF_STONE_BRICK;};}

    private BlockState darken(BlockState w,int h){
        if(w==BRICK)return STONE_BRICK;
        if(w==WHITE_CONCRETE)return LIGHT_GRAY_CONCRETE;
        if(w==LIGHT_GRAY_CONCRETE)return GRAY_CONCRETE;
        if(w==WHITE_TERRACOTTA)
            return LIGHT_GRAY_TERRACOTTA;
        if(w==OAK_PLANKS)return STRIPPED_OAK_LOG;
        if(w==QUARTZ)return SMOOTH_QUARTZ;
        return w;}

    private BlockState pillar(BlockState w){
        if(w==BRICK||w==STONE_BRICK)
            return POLISHED_ANDESITE;
        if(w==OAK_PLANKS||w==SPRUCE_PLANKS)
            return OAK_LOG;
        if(w==QUARTZ||w==SMOOTH_QUARTZ)
            return QUARTZ_PILLAR;
        return POLISHED_ANDESITE;}

    private BlockState selStepBlock(BlockState wall,
            int h){
        if(wall==OAK_PLANKS)return OAK_PLANKS;
        if(wall==SPRUCE_PLANKS)return SPRUCE_PLANKS;
        if(wall==BIRCH_PLANKS)return BIRCH_PLANKS;
        if(wall==DARK_OAK_PLANKS)return DARK_OAK_PLANKS;
        if(wall==BRICK||wall==STONE_BRICK)
            return STONE_BRICK;
        if(wall==COBBLESTONE)return COBBLESTONE;
        if(wall==POLISHED_ANDESITE
                ||wall==POLISHED_DIORITE)
            return POLISHED_ANDESITE;
        if(wall==QUARTZ||wall==SMOOTH_QUARTZ)
            return SMOOTH_QUARTZ;
        if(wall==SANDSTONE||wall==RED_SANDSTONE)
            return SANDSTONE;
        return SMOOTH_STONE;}

    // ================================================================
    // UTILITY
    // ================================================================

    private void clearSafe(ChunkAccess c,
            BlockPos.MutableBlockPos p,
            int bx,int bz,int sy,int cnt){
        int my=c.getMaxBuildHeight()-1;
        for(int i=1;i<=cnt;i++){
            if(sy+i>my)break;
            p.set(bx,sy+i,bz);
            BlockState e=c.getBlockState(p);
            if(!e.isAir()&&!isBuildingBlock(e))
                setOwned(c, p,AIR,false);}}

    private BlockState pickF(int h){
        return switch(h%5){
            case 0->FLOWER_DANDELION;
            case 1->FLOWER_POPPY;
            case 2->FLOWER_CORNFLOWER;
            case 3->FLOWER_ALLIUM;
            default->FLOWER_AZURE;};}

    private void placeTree(ChunkAccess c,
            BlockPos.MutableBlockPos p,
            int bx,int bz,int sy,int h,
            double lat,double lon,
            List<OsmStructureIndex.OsmStructure> roads,
            List<OsmStructureIndex.OsmStructure>
            bridges){
        if(shouldBlockTreeAt(lat,lon,roads,bridges))
            return;
        int th=4+(h%3);
        int my=c.getMaxBuildHeight()-1;
        BlockState lf=switch(h%3){
            case 0->OAK_LEAVES;
            case 1->BIRCH_LEAVES;
            default->SPRUCE_LEAVES;};
        for(int y=1;y<=th&&sy+y<=my;y++){
            p.set(bx,sy+y,bz);
            setOwned(c, p,OAK_LOG,false);}
        int cb=sy+th-1,ct=sy+th+2;
        for(int cy=cb;cy<=ct&&cy<=my;cy++){
            int r=(cy==ct)?1:2;
            for(int dx=-r;dx<=r;dx++)
                for(int dz=-r;dz<=r;dz++){
                    if(dx==0&&dz==0&&cy<ct)continue;
                    if(Math.abs(dx)==r
                            &&Math.abs(dz)==r
                            &&(h+cy)%3==0)continue;
                    int tx=bx+dx,tz=bz+dz;
                    int lx=tx-c.getPos()
                        .getMinBlockX();
                    int lz=tz-c.getPos()
                        .getMinBlockZ();
                    if(lx<0||lx>=16||lz<0||lz>=16)
                        continue;
                    p.set(tx,cy,tz);
                    if(c.getBlockState(p).isAir())
                        setOwned(c, p,lf,false);}}}
} 