// ============================================================
// FILE: BridgeGeometryTest.java
// Pure geometry test — places all bridge types at spawn
// UPDATED — 100 block span, all fixes applied
// ============================================================
package net.buildtheearth.terraplusplus.generator.osm;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class BridgeGeometryTest {

    private static final Logger LOGGER =
            LogManager.getLogger("Terra++BridgeTest");

    // ── dimensions ────────────────────────────────────────────────
    private static final int SEA_LEVEL     = 63;
    private static final int GROUND_Y      = SEA_LEVEL;
    private static final int WATER_TOP     = SEA_LEVEL - 1;
    private static final int WATER_BOTTOM  = SEA_LEVEL - 8;
    private static final int DECK_Y        = SEA_LEVEL + 7;
    private static final int BRIDGE_LENGTH = 100;
    private static final int BRIDGE_WIDTH  = 9;
    private static final int X_SPACING     = 40;

    // ── blocks ────────────────────────────────────────────────────
    private static final BlockState AIR             = Blocks.AIR.defaultBlockState();
    private static final BlockState WATER           = Blocks.WATER.defaultBlockState();
    private static final BlockState GRASS           = Blocks.GRASS_BLOCK.defaultBlockState();
    private static final BlockState DIRT            = Blocks.DIRT.defaultBlockState();
    private static final BlockState STONE           = Blocks.STONE.defaultBlockState();
    private static final BlockState SMOOTH_STONE    = Blocks.SMOOTH_STONE.defaultBlockState();
    private static final BlockState STONE_BRICK     = Blocks.STONE_BRICKS.defaultBlockState();
    private static final BlockState POLISHED_AND    = Blocks.POLISHED_ANDESITE.defaultBlockState();
    private static final BlockState COBBLESTONE     = Blocks.COBBLESTONE.defaultBlockState();
    private static final BlockState MOSSY_COBBLE    = Blocks.MOSSY_COBBLESTONE.defaultBlockState();
    private static final BlockState IRON_BLOCK      = Blocks.IRON_BLOCK.defaultBlockState();
    private static final BlockState IRON_BARS       = Blocks.IRON_BARS.defaultBlockState();
    private static final BlockState CHAIN           = Blocks.CHAIN.defaultBlockState();
    private static final BlockState COBBLE_WALL     = Blocks.COBBLESTONE_WALL.defaultBlockState();
    private static final BlockState STONE_WALL      = Blocks.STONE_BRICK_WALL.defaultBlockState();
    private static final BlockState OAK_LOG         = Blocks.OAK_LOG.defaultBlockState();
    private static final BlockState OAK_PLANKS      = Blocks.OAK_PLANKS.defaultBlockState();
    private static final BlockState OAK_FENCE       = Blocks.OAK_FENCE.defaultBlockState();
    private static final BlockState OAK_SLAB        = Blocks.OAK_SLAB.defaultBlockState();
    private static final BlockState SPRUCE_PLANKS   = Blocks.SPRUCE_PLANKS.defaultBlockState();
    private static final BlockState SPRUCE_LOG      = Blocks.SPRUCE_LOG.defaultBlockState();
    private static final BlockState SPRUCE_SLAB     = Blocks.SPRUCE_SLAB.defaultBlockState();
    private static final BlockState DARK_OAK_PLANKS = Blocks.DARK_OAK_PLANKS.defaultBlockState();
    private static final BlockState DARK_OAK_LOG    = Blocks.DARK_OAK_LOG.defaultBlockState();
    private static final BlockState STRIPPED_OAK    = Blocks.STRIPPED_OAK_LOG.defaultBlockState();
    private static final BlockState GRAY_CONCRETE   = Blocks.GRAY_CONCRETE.defaultBlockState();
    private static final BlockState L_GRAY_CONCRETE = Blocks.LIGHT_GRAY_CONCRETE.defaultBlockState();
    private static final BlockState WHITE_CONCRETE  = Blocks.WHITE_CONCRETE.defaultBlockState();
    private static final BlockState BLACK_CONCRETE  = Blocks.BLACK_CONCRETE.defaultBlockState();
    private static final BlockState YELLOW_CONCRETE = Blocks.YELLOW_CONCRETE.defaultBlockState();
    private static final BlockState SEA_LANTERN     = Blocks.SEA_LANTERN.defaultBlockState();
    private static final BlockState GRAVEL          = Blocks.GRAVEL.defaultBlockState();
    private static final BlockState RAIL            = Blocks.RAIL.defaultBlockState();
    private static final BlockState GLASS_PANE      = Blocks.GLASS_PANE.defaultBlockState();
    private static final BlockState BARREL          = Blocks.BARREL.defaultBlockState();
    private static final BlockState LANTERN         = Blocks.LANTERN.defaultBlockState();

    // labels
    private static final BlockState BLUE_WOOL       = Blocks.BLUE_WOOL.defaultBlockState();
    private static final BlockState GREEN_WOOL      = Blocks.GREEN_WOOL.defaultBlockState();
    private static final BlockState ORANGE_WOOL     = Blocks.ORANGE_WOOL.defaultBlockState();
    private static final BlockState PURPLE_WOOL     = Blocks.PURPLE_WOOL.defaultBlockState();
    private static final BlockState CYAN_WOOL       = Blocks.CYAN_WOOL.defaultBlockState();
    private static final BlockState BROWN_WOOL      = Blocks.BROWN_WOOL.defaultBlockState();
    private static final BlockState RED_WOOL        = Blocks.RED_WOOL.defaultBlockState();
    private static final BlockState YELLOW_WOOL     = Blocks.YELLOW_WOOL.defaultBlockState();
    private static final BlockState WHITE_WOOL      = Blocks.WHITE_WOOL.defaultBlockState();
    private static final BlockState LIGHT_BLUE_WOOL = Blocks.LIGHT_BLUE_WOOL.defaultBlockState();
    private static final BlockState LIME_WOOL       = Blocks.LIME_WOOL.defaultBlockState();
    private static final BlockState MAGENTA_WOOL    = Blocks.MAGENTA_WOOL.defaultBlockState();
    private static final BlockState PINK_WOOL       = Blocks.PINK_WOOL.defaultBlockState();
    private static final BlockState GRAY_WOOL       = Blocks.GRAY_WOOL.defaultBlockState();
    private static final BlockState BLACK_WOOL      = Blocks.BLACK_WOOL.defaultBlockState();

    public enum BridgeType {
        BEAM, ARCH, SUSPENSION, CABLE_STAYED, TRUSS,
        VIADUCT, TRESTLE, CANTILEVER, HUMPBACK, CLAPPER,
        BOARDWALK, COVERED, FLOATING, SIMPLE_SUSPENSION, MOVABLE
    }

    // ================================================================
    // MAIN ENTRY
    // ================================================================

    public static void tryGenerate(ChunkAccess chunk) {
        int cx = chunk.getPos().getMinBlockX();
        int cz = chunk.getPos().getMinBlockZ();
        int totalBridges = BridgeType.values().length;
        int maxX = totalBridges * X_SPACING + 20;
        int maxZ = BRIDGE_LENGTH + 50;
        if (cx > maxX || cx + 16 < -20) return;
        if (cz > maxZ || cz + 16 < -30) return;
        generateAllBridges(chunk, cx, cz);
    }

    private static void generateAllBridges(ChunkAccess chunk,
                                            int cx, int cz) {
        BridgeType[] types = BridgeType.values();
        BlockState[] labels = {
                BLUE_WOOL, GREEN_WOOL, ORANGE_WOOL, PURPLE_WOOL,
                CYAN_WOOL, BROWN_WOOL, RED_WOOL, YELLOW_WOOL,
                WHITE_WOOL, LIGHT_BLUE_WOOL, LIME_WOOL,
                MAGENTA_WOOL, PINK_WOOL, GRAY_WOOL, BLACK_WOOL
        };
        for (int i = 0; i < types.length; i++) {
            int startX = i * X_SPACING;
            int startZ = 0;
            buildTerrain(chunk, cx, cz, startX, startZ, types[i]);
            buildLabelTower(chunk, cx, cz, startX, startZ,
                    labels[i % labels.length], i);
            switch (types[i]) {
                case BEAM              -> buildBeamBridge(chunk, cx, cz, startX, startZ);
                case ARCH              -> buildArchBridge(chunk, cx, cz, startX, startZ);
                case SUSPENSION        -> buildSuspensionBridge(chunk, cx, cz, startX, startZ);
                case CABLE_STAYED      -> buildCableStayedBridge(chunk, cx, cz, startX, startZ);
                case TRUSS             -> buildTrussBridge(chunk, cx, cz, startX, startZ);
                case VIADUCT           -> buildViaduct(chunk, cx, cz, startX, startZ);
                case TRESTLE           -> buildTrestleBridge(chunk, cx, cz, startX, startZ);
                case CANTILEVER        -> buildCantileverBridge(chunk, cx, cz, startX, startZ);
                case HUMPBACK          -> buildHumpbackBridge(chunk, cx, cz, startX, startZ);
                case CLAPPER           -> buildClapperBridge(chunk, cx, cz, startX, startZ);
                case BOARDWALK         -> buildBoardwalk(chunk, cx, cz, startX, startZ);
                case COVERED           -> buildCoveredBridge(chunk, cx, cz, startX, startZ);
                case FLOATING          -> buildFloatingBridge(chunk, cx, cz, startX, startZ);
                case SIMPLE_SUSPENSION -> buildSimpleSuspension(chunk, cx, cz, startX, startZ);
                case MOVABLE           -> buildMovableBridge(chunk, cx, cz, startX, startZ);
            }
        }
    }

    // ================================================================
    // LABEL TOWER
    // ================================================================

    private static void buildLabelTower(ChunkAccess chunk,
                                         int cx, int cz,
                                         int startX, int startZ,
                                         BlockState label, int idx) {
        int tz = startZ - 5;
        for (int dx = -1; dx <= 1; dx++)
            for (int dz = -1; dz <= 1; dz++) {
                int bx = startX + dx, bz = tz + dz;
                if (!inChunk(bx, bz, cx, cz)) continue;
                for (int y = GROUND_Y + 1; y <= GROUND_Y + 4; y++)
                    setBlock(chunk, bx, y, bz, label);
            }
        if (inChunk(startX, tz, cx, cz))
            setBlock(chunk, startX, GROUND_Y + 5, tz, SEA_LANTERN);
        for (int y = 0; y <= Math.min(idx, 14); y++) {
            int bx = startX + 3;
            if (inChunk(bx, tz, cx, cz))
                setBlock(chunk, bx, GROUND_Y + 1 + y, tz, label);
        }
    }

    // ================================================================
    // TERRAIN — clean channel, no moss for boardwalk/floating
    // ================================================================

    private static void buildTerrain(ChunkAccess chunk,
                                      int cx, int cz,
                                      int startX, int startZ,
                                      BridgeType type) {
        int halfW = BRIDGE_WIDTH / 2;
        int areaMinX = startX - halfW - 5;
        int areaMaxX = startX + halfW + 5;
        int areaMinZ = startZ - 20;
        int areaMaxZ = startZ + BRIDGE_LENGTH + 20;
        int channelMinZ = startZ + 15;
        int channelMaxZ = startZ + BRIDGE_LENGTH - 15;

        boolean shallow = (type == BridgeType.BOARDWALK
                || type == BridgeType.FLOATING);
        boolean narrow = (type == BridgeType.CLAPPER
                || type == BridgeType.HUMPBACK);

        for (int bx = areaMinX; bx <= areaMaxX; bx++) {
            for (int bz = areaMinZ; bz <= areaMaxZ; bz++) {
                if (!inChunk(bx, bz, cx, cz)) continue;
                boolean inCh = bz >= channelMinZ && bz <= channelMaxZ;
                if (narrow) {
                    int dx = Math.abs(bx - startX);
                    inCh = inCh && dx <= halfW + 2;
                }
                int localBot = shallow ? WATER_TOP - 2 : WATER_BOTTOM;
                for (int y = localBot - 3; y <= GROUND_Y; y++) {
                    if (inCh && y >= localBot && y <= WATER_TOP) {
                        setBlock(chunk, bx, y, bz, WATER);
                    } else if (y == GROUND_Y) {
                        setBlock(chunk, bx, y, bz,
                                inCh ? WATER : GRASS);
                    } else if (y >= GROUND_Y - 2) {
                        setBlock(chunk, bx, y, bz, DIRT);
                    } else {
                        setBlock(chunk, bx, y, bz, STONE);
                    }
                }
                if (inCh) {
                    setBlock(chunk, bx, localBot - 1, bz, GRAVEL);
                    setBlock(chunk, bx, localBot - 2, bz, STONE);
                }
                for (int y = GROUND_Y + 1; y <= DECK_Y + 35; y++)
                    setBlock(chunk, bx, y, bz, AIR);
            }
        }
    }

    // ================================================================
    // 0: BEAM — unchanged, already perfect
    // ================================================================

    private static void buildBeamBridge(ChunkAccess chunk,
                                         int cx, int cz,
                                         int startX, int startZ) {
        int halfW = BRIDGE_WIDTH / 2;
        for (int bz = startZ; bz < startZ + BRIDGE_LENGTH; bz++) {
            for (int dx = -halfW; dx <= halfW; dx++) {
                int bx = startX + dx;
                if (!inChunk(bx, bz, cx, cz)) continue;
                setBlock(chunk, bx, DECK_Y - 2, bz, GRAY_CONCRETE);
                setBlock(chunk, bx, DECK_Y - 1, bz, STONE_BRICK);
                if (Math.abs(dx) == halfW) {
                    setBlock(chunk, bx, DECK_Y, bz, SMOOTH_STONE);
                    setBlock(chunk, bx, DECK_Y + 1, bz, COBBLE_WALL);
                    if (bz % 10 == 0)
                        setBlock(chunk, bx, DECK_Y + 2, bz, LANTERN);
                } else if (Math.abs(dx) == halfW - 1) {
                    setBlock(chunk, bx, DECK_Y, bz, SMOOTH_STONE);
                } else {
                    setBlock(chunk, bx, DECK_Y, bz, BLACK_CONCRETE);
                }
                if (dx == 0 && bz % 4 < 2)
                    setBlock(chunk, bx, DECK_Y, bz, YELLOW_CONCRETE);
                if (Math.abs(dx) == halfW - 2)
                    setBlock(chunk, bx, DECK_Y, bz, WHITE_CONCRETE);
            }
            if (bz % 15 == 0) {
                for (int dx : new int[]{-halfW + 1, 0, halfW - 1}) {
                    int bx = startX + dx;
                    if (!inChunk(bx, bz, cx, cz)) continue;
                    setBlock(chunk, bx, DECK_Y - 3, bz, POLISHED_AND);
                    for (int y = WATER_BOTTOM - 2; y < DECK_Y - 2; y++)
                        setBlock(chunk, bx, y, bz, STONE_BRICK);
                    for (int fdx = -1; fdx <= 1; fdx++) {
                        int fx = bx + fdx;
                        if (!inChunk(fx, bz, cx, cz)) continue;
                        setBlock(chunk, fx, WATER_BOTTOM - 2, bz, STONE_BRICK);
                        setBlock(chunk, fx, WATER_BOTTOM - 3, bz, STONE);
                    }
                }
            }
        }
        buildRamp(chunk, cx, cz, startX, startZ - 1, -1, halfW);
        buildRamp(chunk, cx, cz, startX, startZ + BRIDGE_LENGTH, 1, halfW);
    }

    // ================================================================
    // 1: ARCH — FIXED: single large open arch
    // ================================================================

    private static void buildArchBridge(ChunkAccess chunk,
                                         int cx, int cz,
                                         int startX, int startZ) {
        int halfW = BRIDGE_WIDTH / 2;

        // layout: 20 abutment | 60 arch span | 20 abutment
        int abutLen   = 20;
        int archStart = startZ + abutLen;
        int archEnd   = startZ + BRIDGE_LENGTH - abutLen;
        int archSpan  = archEnd - archStart; // 60
        int archMid   = (archStart + archEnd) / 2;

        // arch geometry
        int archRise = DECK_Y - WATER_BOTTOM - 3; // max height of arch

        for (int bz = startZ; bz < startZ + BRIDGE_LENGTH; bz++) {
            boolean inArch = bz >= archStart && bz < archEnd;

            for (int dx = -halfW; dx <= halfW; dx++) {
                int bx = startX + dx;
                if (!inChunk(bx, bz, cx, cz)) continue;

                // deck always present
                setBlock(chunk, bx, DECK_Y - 1, bz, STONE_BRICK);

                if (Math.abs(dx) == halfW) {
                    setBlock(chunk, bx, DECK_Y, bz, POLISHED_AND);
                    setBlock(chunk, bx, DECK_Y + 1, bz, STONE_WALL);
                    if (bz % 8 == 0)
                        setBlock(chunk, bx, DECK_Y + 2, bz, LANTERN);
                } else {
                    setBlock(chunk, bx, DECK_Y, bz, GRAY_CONCRETE);
                }

                // abutments — solid fill
                if (!inArch) {
                    for (int y = WATER_BOTTOM - 2; y < DECK_Y - 1; y++)
                        setBlock(chunk, bx, y, bz, STONE_BRICK);
                }
            }

            // arch ring and interior
            if (inArch) {
                double t = (double)(bz - archMid) / (archSpan / 2.0);
                int archY = WATER_BOTTOM
                        + (int) Math.round((1.0 - t * t) * archRise);

                for (int dx = -halfW; dx <= halfW; dx++) {
                    int bx = startX + dx;
                    if (!inChunk(bx, bz, cx, cz)) continue;
                    boolean isSide = Math.abs(dx) >= halfW - 1;

                    if (isSide) {
                        // arch ring on sides
                        if (archY >= WATER_BOTTOM && archY < DECK_Y - 1)
                            setBlock(chunk, bx, archY, bz, POLISHED_AND);

                        // spandrel fill above arch to deck
                        for (int y = archY + 1; y < DECK_Y - 1; y++)
                            setBlock(chunk, bx, y, bz, STONE_BRICK);

                        // clear below arch
                        for (int y = WATER_BOTTOM; y < archY; y++) {
                            BlockState existing = getBlock(chunk, bx, y, bz);
                            if (existing != WATER)
                                setBlock(chunk, bx, y, bz, AIR);
                        }
                    } else {
                        // interior — clear everything below deck
                        for (int y = WATER_BOTTOM; y < DECK_Y - 1; y++) {
                            BlockState existing = getBlock(chunk, bx, y, bz);
                            if (existing != WATER)
                                setBlock(chunk, bx, y, bz, AIR);
                        }
                    }
                }

                // keystone accent at crown
                if (bz == archMid) {
                    for (int dx = -halfW; dx <= halfW; dx++) {
                        int bx = startX + dx;
                        if (!inChunk(bx, bz, cx, cz)) continue;
                        setBlock(chunk, bx, DECK_Y - 2, bz, POLISHED_AND);
                    }
                }
            }
        }

        buildRamp(chunk, cx, cz, startX, startZ - 1, -1, halfW);
        buildRamp(chunk, cx, cz, startX, startZ + BRIDGE_LENGTH, 1, halfW);
    }

    // ================================================================
    // 2: SUSPENSION — FIXED: cables anchored at both ends
    // ================================================================

    private static void buildSuspensionBridge(ChunkAccess chunk,
                                               int cx, int cz,
                                               int startX,
                                               int startZ) {
        int halfW       = BRIDGE_WIDTH / 2;
        int towerHeight = 25;
        int towerTopY   = DECK_Y + towerHeight;
        int tower1Z     = startZ + 20;
        int tower2Z     = startZ + BRIDGE_LENGTH - 20;
        int anchor1Z    = startZ + 3;
        int anchor2Z    = startZ + BRIDGE_LENGTH - 4;

        for (int bz = startZ; bz < startZ + BRIDGE_LENGTH; bz++) {
            // deck
            for (int dx = -halfW; dx <= halfW; dx++) {
                int bx = startX + dx;
                if (!inChunk(bx, bz, cx, cz)) continue;
                setBlock(chunk, bx, DECK_Y - 2, bz, IRON_BLOCK);
                setBlock(chunk, bx, DECK_Y - 1, bz, GRAY_CONCRETE);
                if (Math.abs(dx) == halfW) {
                    setBlock(chunk, bx, DECK_Y, bz, GRAY_CONCRETE);
                    setBlock(chunk, bx, DECK_Y + 1, bz, IRON_BARS);
                } else if (Math.abs(dx) == halfW - 1) {
                    setBlock(chunk, bx, DECK_Y, bz, SMOOTH_STONE);
                } else {
                    setBlock(chunk, bx, DECK_Y, bz, BLACK_CONCRETE);
                }
                if (dx == 0 && bz % 4 < 2)
                    setBlock(chunk, bx, DECK_Y, bz, YELLOW_CONCRETE);
                if (Math.abs(dx) == halfW - 2)
                    setBlock(chunk, bx, DECK_Y, bz, WHITE_CONCRETE);
            }

            // towers
            for (int tZ : new int[]{tower1Z, tower2Z}) {
                if (Math.abs(bz - tZ) <= 1) {
                    for (int dx : new int[]{-halfW, halfW}) {
                        int bx = startX + dx;
                        if (!inChunk(bx, bz, cx, cz)) continue;
                        for (int y = WATER_BOTTOM - 3; y <= towerTopY; y++)
                            setBlock(chunk, bx, y, bz, IRON_BLOCK);
                        setBlock(chunk, bx, towerTopY + 1, bz, IRON_BLOCK);
                        setBlock(chunk, bx, towerTopY + 2, bz, SEA_LANTERN);
                    }
                    if (bz == tZ) {
                        for (int dx = -halfW; dx <= halfW; dx++) {
                            int bx = startX + dx;
                            if (!inChunk(bx, bz, cx, cz)) continue;
                            setBlock(chunk, bx, towerTopY, bz, IRON_BLOCK);
                            setBlock(chunk, bx, towerTopY - 5, bz, IRON_BLOCK);
                            setBlock(chunk, bx, towerTopY - 10, bz, IRON_BLOCK);
                        }
                    }
                }
            }

            // anchorages
            for (int aZ : new int[]{anchor1Z, anchor2Z}) {
                if (Math.abs(bz - aZ) <= 2) {
                    for (int dx : new int[]{-halfW - 1, halfW + 1}) {
                        int bx = startX + dx;
                        if (!inChunk(bx, bz, cx, cz)) continue;
                        for (int y = GROUND_Y; y <= GROUND_Y + 3; y++)
                            setBlock(chunk, bx, y, bz, STONE_BRICK);
                    }
                }
            }

            // main cables — full continuous profile
            for (int dx : new int[]{-halfW, halfW}) {
                int bx = startX + dx;
                if (!inChunk(bx, bz, cx, cz)) continue;

                int cableY = -1;

                if (bz >= anchor1Z && bz <= tower1Z) {
                    // backstay: anchor to tower1
                    double frac = (double)(bz - anchor1Z)
                            / (tower1Z - anchor1Z);
                    cableY = GROUND_Y + 3
                            + (int)(frac * (towerTopY - GROUND_Y - 3));
                } else if (bz >= tower1Z && bz <= tower2Z) {
                    // main span catenary
                    double span = tower2Z - tower1Z;
                    double mid  = (tower1Z + tower2Z) / 2.0;
                    double t    = (bz - mid) / (span / 2.0);
                    double sag  = towerHeight * 0.7;
                    cableY = towerTopY
                            - (int) Math.round(sag * (1.0 - t * t));
                } else if (bz >= tower2Z && bz <= anchor2Z) {
                    // backstay: tower2 to anchor
                    double frac = (double)(bz - tower2Z)
                            / (anchor2Z - tower2Z);
                    cableY = towerTopY
                            - (int)(frac * (towerTopY - GROUND_Y - 3));
                }

                if (cableY >= 0)
                    setBlock(chunk, bx, cableY, bz, CHAIN);

                // suspender cables every 2 blocks in main span
                if (bz > tower1Z && bz < tower2Z && bz % 2 == 0
                        && cableY > DECK_Y + 1) {
                    for (int y = DECK_Y + 2; y < cableY; y++)
                        setBlock(chunk, bx, y, bz, CHAIN);
                }
            }
        }

        buildRamp(chunk, cx, cz, startX, startZ - 1, -1, halfW);
        buildRamp(chunk, cx, cz, startX, startZ + BRIDGE_LENGTH, 1, halfW);
    }

    // ================================================================
    // 3: CABLE-STAYED — FIXED: proper diagonal fan stays
    // ================================================================

    private static void buildCableStayedBridge(ChunkAccess chunk,
                                            int cx, int cz,
                                            int startX,
                                            int startZ) {
    int halfW       = BRIDGE_WIDTH / 2;
    int outerW      = halfW + 2; // structure outside road
    int towerHeight = 28;
    int tower1Z     = startZ + BRIDGE_LENGTH / 3;
    int tower2Z     = startZ + 2 * BRIDGE_LENGTH / 3;
    int towerTopY   = DECK_Y + towerHeight;

    for (int bz = startZ; bz < startZ + BRIDGE_LENGTH; bz++) {
        // deck — always fully clear road
        for (int dx = -halfW; dx <= halfW; dx++) {
            int bx = startX + dx;
            if (!inChunk(bx, bz, cx, cz)) continue;
            setBlock(chunk, bx, DECK_Y - 2, bz, GRAY_CONCRETE);
            setBlock(chunk, bx, DECK_Y - 1, bz, L_GRAY_CONCRETE);
            if (Math.abs(dx) == halfW) {
                setBlock(chunk, bx, DECK_Y, bz, L_GRAY_CONCRETE);
                setBlock(chunk, bx, DECK_Y + 1, bz, IRON_BARS);
                if (bz % 12 == 0)
                    setBlock(chunk, bx, DECK_Y + 2, bz, LANTERN);
            } else {
                setBlock(chunk, bx, DECK_Y, bz, BLACK_CONCRETE);
            }
            if (dx == 0 && bz % 4 < 2)
                setBlock(chunk, bx, DECK_Y, bz, YELLOW_CONCRETE);
            if (Math.abs(dx) == halfW - 1)
                setBlock(chunk, bx, DECK_Y, bz, WHITE_CONCRETE);
        }

        // towers — OUTSIDE the road width
        for (int tZ : new int[]{tower1Z, tower2Z}) {
            if (Math.abs(bz - tZ) <= 1) {
                // outer pylons from riverbed to top
                for (int dx : new int[]{-outerW, outerW}) {
                    int bx = startX + dx;
                    if (!inChunk(bx, bz, cx, cz)) continue;
                    for (int y = WATER_BOTTOM - 3;
                         y <= towerTopY; y++)
                        setBlock(chunk, bx, y, bz,
                                WHITE_CONCRETE);
                    setBlock(chunk, bx, towerTopY + 1, bz,
                            SEA_LANTERN);
                }

                // crossbeams spanning over road at key heights
                if (bz == tZ) {
                    // top crossbeam
                    for (int dx = -outerW; dx <= outerW; dx++) {
                        int bx = startX + dx;
                        if (!inChunk(bx, bz, cx, cz)) continue;
                        setBlock(chunk, bx, towerTopY, bz,
                                WHITE_CONCRETE);
                    }
                    // deck-level crossbeam
                    for (int dx = -outerW; dx <= outerW; dx++) {
                        int bx = startX + dx;
                        if (!inChunk(bx, bz, cx, cz)) continue;
                        setBlock(chunk, bx, DECK_Y - 2, bz,
                                WHITE_CONCRETE);
                    }
                    // mid crossbeam
                    int midCrossY = DECK_Y + towerHeight / 2;
                    for (int dx = -outerW; dx <= outerW; dx++) {
                        int bx = startX + dx;
                        if (!inChunk(bx, bz, cx, cz)) continue;
                        setBlock(chunk, bx, midCrossY, bz,
                                WHITE_CONCRETE);
                    }
                }
            }
        }

        // fan cables — diagonal stays on BOTH outer sides
        for (int tZ : new int[]{tower1Z, tower2Z}) {
            int dz    = bz - tZ;
            int absDz = Math.abs(dz);
            if (absDz < 4 || absDz > 30) continue;
            if (absDz % 4 != 0) continue;

            // tower attachment — spaced down from top
            int stayIndex   = absDz / 4;     // 1..7
            int totalStays  = 7;
            int attachRange = towerTopY - (DECK_Y + 4);
            int towerAttachY = towerTopY
                    - (stayIndex * attachRange / totalStays);
            if (towerAttachY <= DECK_Y + 2) continue;

            // draw one diagonal cable per side
            for (int dx : new int[]{-outerW, outerW}) {
                int bx = startX + dx;

                // single chain block per Z at interpolated Y
                int deckAnchorY = DECK_Y + 1;
                int dyTotal     = towerAttachY - deckAnchorY;

                for (int step = 0; step <= absDz; step++) {
                    int cableZ = tZ + (dz > 0 ? step : -step);
                    double frac = (double) step / absDz;
                    int cableY = towerAttachY
                            - (int) Math.round(frac * dyTotal);

                    if (!inChunk(bx, cableZ, cx, cz)) continue;
                    setBlock(chunk, bx, cableY, cableZ, CHAIN);
                }
            }
        }

        // intermediate piers — OUTSIDE road only
        if (bz == startZ + 8
                || bz == startZ + BRIDGE_LENGTH - 8) {
            for (int dx : new int[]{-outerW, outerW}) {
                int bx = startX + dx;
                if (!inChunk(bx, bz, cx, cz)) continue;
                for (int y = WATER_BOTTOM - 2;
                     y < DECK_Y - 2; y++)
                    setBlock(chunk, bx, y, bz, GRAY_CONCRETE);
            }
            // pier cap spanning to deck edge
            for (int dx = -outerW; dx <= outerW; dx++) {
                int bx = startX + dx;
                if (!inChunk(bx, bz, cx, cz)) continue;
                setBlock(chunk, bx, DECK_Y - 3, bz,
                        POLISHED_AND);
            }
        }
    }

    buildRamp(chunk, cx, cz, startX, startZ - 1, -1, halfW);
    buildRamp(chunk, cx, cz, startX,
            startZ + BRIDGE_LENGTH, 1, halfW);
}

    // ================================================================
    // 4: TRUSS — unchanged, already perfect
    // ================================================================

    private static void buildTrussBridge(ChunkAccess chunk,
                                          int cx, int cz,
                                          int startX, int startZ) {
        int halfW    = BRIDGE_WIDTH / 2;
        int trussH   = 8;
        int panelLen = 5;
        for (int bz = startZ; bz < startZ + BRIDGE_LENGTH; bz++) {
            for (int dx = -halfW; dx <= halfW; dx++) {
                int bx = startX + dx;
                if (!inChunk(bx, bz, cx, cz)) continue;
                setBlock(chunk, bx, DECK_Y - 1, bz, IRON_BLOCK);
                setBlock(chunk, bx, DECK_Y, bz, GRAVEL);
                if (Math.abs(dx) == 2)
                    setBlock(chunk, bx, DECK_Y + 1, bz, RAIL);
            }
            for (int dx : new int[]{-halfW, halfW}) {
                int bx = startX + dx;
                if (!inChunk(bx, bz, cx, cz)) continue;
                setBlock(chunk, bx, DECK_Y, bz, IRON_BLOCK);
                setBlock(chunk, bx, DECK_Y + trussH, bz, IRON_BLOCK);
                int pip = bz % panelLen;
                if (pip == 0) {
                    for (int y = DECK_Y; y <= DECK_Y + trussH; y++)
                        setBlock(chunk, bx, y, bz, IRON_BLOCK);
                }
                if (pip > 0) {
                    double f = (double) pip / panelLen;
                    int u = DECK_Y + 1 + (int)(f * (trussH - 1));
                    int d = DECK_Y + trussH - 1 - (int)(f * (trussH - 1));
                    setBlock(chunk, bx, u, bz, IRON_BARS);
                    if (d != u) setBlock(chunk, bx, d, bz, IRON_BARS);
                }
            }
            if (bz % panelLen == 0) {
                for (int dx = -halfW; dx <= halfW; dx++) {
                    int bx = startX + dx;
                    if (!inChunk(bx, bz, cx, cz)) continue;
                    setBlock(chunk, bx, DECK_Y + trussH, bz, IRON_BLOCK);
                }
                for (int dx = -halfW + 1; dx <= halfW - 1; dx++) {
                    int bx = startX + dx;
                    if (!inChunk(bx, bz, cx, cz)) continue;
                    setBlock(chunk, bx, DECK_Y + trussH - 1, bz, IRON_BARS);
                }
            }
            if (bz % 20 == 0) {
                for (int dx : new int[]{-halfW + 1, halfW - 1}) {
                    int bx = startX + dx;
                    if (!inChunk(bx, bz, cx, cz)) continue;
                    for (int y = WATER_BOTTOM - 2; y < DECK_Y - 1; y++)
                        setBlock(chunk, bx, y, bz, STONE_BRICK);
                }
                for (int dx = -halfW; dx <= halfW; dx++) {
                    int bx = startX + dx;
                    if (!inChunk(bx, bz, cx, cz)) continue;
                    setBlock(chunk, bx, DECK_Y - 2, bz, POLISHED_AND);
                }
            }
        }
        buildRamp(chunk, cx, cz, startX, startZ - 1, -1, halfW);
        buildRamp(chunk, cx, cz, startX, startZ + BRIDGE_LENGTH, 1, halfW);
    }

    // ================================================================
    // 5: VIADUCT — FIXED: repeated clear open arches
    // ================================================================

    private static void buildViaduct(ChunkAccess chunk,
                                      int cx, int cz,
                                      int startX, int startZ) {
        int halfW     = BRIDGE_WIDTH / 2;
        int pierWidth = 4;
        int archClear = 12;
        int bayLen    = pierWidth + archClear; // 16
        int abutLen   = 10;

        for (int bz = startZ; bz < startZ + BRIDGE_LENGTH; bz++) {
            // deck
            for (int dx = -halfW; dx <= halfW; dx++) {
                int bx = startX + dx;
                if (!inChunk(bx, bz, cx, cz)) continue;
                setBlock(chunk, bx, DECK_Y - 1, bz, STONE_BRICK);
                setBlock(chunk, bx, DECK_Y, bz, GRAVEL);
                if (Math.abs(dx) == 2)
                    setBlock(chunk, bx, DECK_Y + 1, bz, RAIL);
                if (Math.abs(dx) == halfW)
                    setBlock(chunk, bx, DECK_Y + 1, bz, COBBLE_WALL);
            }

            // abutments at ends
            if (bz < startZ + abutLen
                    || bz >= startZ + BRIDGE_LENGTH - abutLen) {
                for (int dx = -halfW; dx <= halfW; dx++) {
                    int bx = startX + dx;
                    if (!inChunk(bx, bz, cx, cz)) continue;
                    for (int y = WATER_BOTTOM - 3; y < DECK_Y - 1; y++)
                        setBlock(chunk, bx, y, bz, STONE_BRICK);
                }
                continue;
            }

            // repeating arch bays
            int archZone = bz - (startZ + abutLen);
            int posInBay = archZone % bayLen;

            if (posInBay < pierWidth) {
                // pier — solid column
                for (int dx = -halfW; dx <= halfW; dx++) {
                    int bx = startX + dx;
                    if (!inChunk(bx, bz, cx, cz)) continue;
                    for (int y = WATER_BOTTOM - 3; y < DECK_Y - 1; y++)
                        setBlock(chunk, bx, y, bz, STONE_BRICK);
                }
                // cornice
                for (int dx = -halfW - 1; dx <= halfW + 1; dx++) {
                    int bx = startX + dx;
                    if (!inChunk(bx, bz, cx, cz)) continue;
                    setBlock(chunk, bx, DECK_Y - 1, bz, POLISHED_AND);
                }
            } else {
                // arch opening
                int posInArch   = posInBay - pierWidth;
                int archCenter  = archClear / 2;
                int dzFromCenter = posInArch - archCenter;
                double t        = (double) dzFromCenter / (archClear / 2.0);
                int archRise    = DECK_Y - WATER_BOTTOM - 4;
                int archY       = WATER_BOTTOM
                        + (int) Math.round((1.0 - t * t) * archRise);

                for (int dx = -halfW; dx <= halfW; dx++) {
                    int bx = startX + dx;
                    if (!inChunk(bx, bz, cx, cz)) continue;
                    boolean isSide = Math.abs(dx) >= halfW - 1;

                    if (isSide) {
                        // arch ring
                        if (archY >= WATER_BOTTOM && archY < DECK_Y - 1)
                            setBlock(chunk, bx, archY, bz, POLISHED_AND);
                        // spandrel above arch
                        for (int y = Math.max(archY + 1, WATER_BOTTOM);
                             y < DECK_Y - 1; y++)
                            setBlock(chunk, bx, y, bz, STONE_BRICK);
                        // clear below arch
                        for (int y = WATER_BOTTOM; y < archY; y++) {
                            BlockState ex = getBlock(chunk, bx, y, bz);
                            if (ex != WATER)
                                setBlock(chunk, bx, y, bz, AIR);
                        }
                    } else {
                        // interior — clear below deck
                        for (int y = WATER_BOTTOM; y < DECK_Y - 1; y++) {
                            BlockState ex = getBlock(chunk, bx, y, bz);
                            if (ex != WATER)
                                setBlock(chunk, bx, y, bz, AIR);
                        }
                    }
                }
            }
        }

        buildRamp(chunk, cx, cz, startX, startZ - 1, -1, halfW);
        buildRamp(chunk, cx, cz, startX, startZ + BRIDGE_LENGTH, 1, halfW);
    }

    // ================================================================
    // 6: TRESTLE — unchanged, already perfect
    // ================================================================

    private static void buildTrestleBridge(ChunkAccess chunk,
                                            int cx, int cz,
                                            int startX, int startZ) {
        int halfW = BRIDGE_WIDTH / 2;
        int bentSpan = 6;
        for (int bz = startZ; bz < startZ + BRIDGE_LENGTH; bz++) {
            for (int dx = -halfW; dx <= halfW; dx++) {
                int bx = startX + dx;
                if (!inChunk(bx, bz, cx, cz)) continue;
                setBlock(chunk, bx, DECK_Y - 1, bz, STRIPPED_OAK);
                setBlock(chunk, bx, DECK_Y, bz, OAK_PLANKS);
                if (Math.abs(dx) == halfW)
                    setBlock(chunk, bx, DECK_Y + 1, bz, OAK_FENCE);
            }
            if (bz % bentSpan == 0) {
                for (int dx : new int[]{-halfW, -halfW + 2, halfW - 2, halfW}) {
                    int bx = startX + dx;
                    if (!inChunk(bx, bz, cx, cz)) continue;
                    for (int y = WATER_BOTTOM - 1; y < DECK_Y - 1; y++)
                        setBlock(chunk, bx, y, bz, OAK_LOG);
                }
                int midY = (WATER_BOTTOM + DECK_Y) / 2;
                for (int dx = -halfW + 1; dx <= halfW - 1; dx++) {
                    int bx = startX + dx;
                    if (!inChunk(bx, bz, cx, cz)) continue;
                    setBlock(chunk, bx, midY, bz, OAK_FENCE);
                    if (midY - 3 >= WATER_BOTTOM)
                        setBlock(chunk, bx, midY - 3, bz, OAK_FENCE);
                }
                for (int dx = -halfW; dx <= halfW; dx++) {
                    int bx = startX + dx;
                    if (!inChunk(bx, bz, cx, cz)) continue;
                    setBlock(chunk, bx, DECK_Y - 2, bz, STRIPPED_OAK);
                }
            }
            if (bz % bentSpan != 0) {
                for (int dx : new int[]{-halfW + 1, 0, halfW - 1}) {
                    int bx = startX + dx;
                    if (!inChunk(bx, bz, cx, cz)) continue;
                    setBlock(chunk, bx, DECK_Y - 2, bz, STRIPPED_OAK);
                }
            }
        }
        buildRamp(chunk, cx, cz, startX, startZ - 1, -1, halfW);
        buildRamp(chunk, cx, cz, startX, startZ + BRIDGE_LENGTH, 1, halfW);
    }

    // ================================================================
    // 7: CANTILEVER — FIXED: supports outside road
    // ================================================================

    private static void buildCantileverBridge(ChunkAccess chunk,
                                               int cx, int cz,
                                               int startX,
                                               int startZ) {
        int halfW       = BRIDGE_WIDTH / 2;
        int outerW      = halfW + 2; // structure outside road
        int towerHeight = 14;
        int tower1Z     = startZ + 25;
        int tower2Z     = startZ + BRIDGE_LENGTH - 25;
        int towerTopY   = DECK_Y + towerHeight;

        for (int bz = startZ; bz < startZ + BRIDGE_LENGTH; bz++) {
            // road deck — always fully clear
            for (int dx = -halfW; dx <= halfW; dx++) {
                int bx = startX + dx;
                if (!inChunk(bx, bz, cx, cz)) continue;
                setBlock(chunk, bx, DECK_Y - 1, bz, GRAY_CONCRETE);
                if (Math.abs(dx) == halfW) {
                    setBlock(chunk, bx, DECK_Y, bz, SMOOTH_STONE);
                    setBlock(chunk, bx, DECK_Y + 1, bz, IRON_BARS);
                } else {
                    setBlock(chunk, bx, DECK_Y, bz, BLACK_CONCRETE);
                }
                if (dx == 0 && bz % 4 < 2)
                    setBlock(chunk, bx, DECK_Y, bz, YELLOW_CONCRETE);
            }

            // cantilever towers — OUTSIDE the road width
            for (int tZ : new int[]{tower1Z, tower2Z}) {
                if (Math.abs(bz - tZ) <= 2) {
                    // outer piers
                    for (int dx : new int[]{-outerW, outerW}) {
                        int bx = startX + dx;
                        if (!inChunk(bx, bz, cx, cz)) continue;
                        for (int y = WATER_BOTTOM - 3; y <= towerTopY; y++)
                            setBlock(chunk, bx, y, bz, IRON_BLOCK);
                    }
                    // top crossbeam spanning over road
                    if (bz == tZ) {
                        for (int dx = -outerW; dx <= outerW; dx++) {
                            int bx = startX + dx;
                            if (!inChunk(bx, bz, cx, cz)) continue;
                            setBlock(chunk, bx, towerTopY, bz, IRON_BLOCK);
                        }
                        // lower crossbeam at deck level
                        for (int dx = -outerW; dx <= outerW; dx++) {
                            int bx = startX + dx;
                            if (!inChunk(bx, bz, cx, cz)) continue;
                            setBlock(chunk, bx, DECK_Y - 2, bz, IRON_BLOCK);
                        }
                    }
                }
            }

            // cantilever arms — outer structure only
            for (int tZ : new int[]{tower1Z, tower2Z}) {
                int dz    = bz - tZ;
                int absDz = Math.abs(dz);
                if (absDz <= 2 || absDz > 22) continue;

                // upper chord slopes down from tower top
                int upperY = towerTopY - (absDz - 2) * 2 / 3;
                if (upperY > DECK_Y + 2) {
                    for (int dx : new int[]{-outerW, outerW}) {
                        int bx = startX + dx;
                        if (!inChunk(bx, bz, cx, cz)) continue;
                        setBlock(chunk, bx, upperY, bz, IRON_BLOCK);
                    }
                    // upper crossbeam every 4 blocks
                    if (absDz % 4 == 0) {
                        for (int dx = -outerW; dx <= outerW; dx++) {
                            int bx = startX + dx;
                            if (!inChunk(bx, bz, cx, cz)) continue;
                            setBlock(chunk, bx, upperY, bz, IRON_BLOCK);
                        }
                    }
                    // verticals every 3 blocks
                    if (absDz % 3 == 0) {
                        for (int dx : new int[]{-outerW, outerW}) {
                            int bx = startX + dx;
                            if (!inChunk(bx, bz, cx, cz)) continue;
                            for (int y = DECK_Y + 1; y < upperY; y++)
                                setBlock(chunk, bx, y, bz, IRON_BARS);
                        }
                    }
                }

                // lower chord below deck — outer only
                int lowerY = DECK_Y - 3 - (absDz - 2) / 2;
                if (lowerY >= WATER_BOTTOM) {
                    for (int dx : new int[]{-outerW, outerW}) {
                        int bx = startX + dx;
                        if (!inChunk(bx, bz, cx, cz)) continue;
                        setBlock(chunk, bx, lowerY, bz, IRON_BLOCK);
                    }
                    if (absDz % 3 == 0) {
                        for (int dx : new int[]{-outerW, outerW}) {
                            int bx = startX + dx;
                            if (!inChunk(bx, bz, cx, cz)) continue;
                            for (int y = lowerY + 1; y < DECK_Y - 1; y++)
                                setBlock(chunk, bx, y, bz, IRON_BARS);
                        }
                    }
                }
            }

            // suspended center span marker
            int centerZ = (tower1Z + tower2Z) / 2;
            if (Math.abs(bz - centerZ) <= 4) {
                for (int dx = -halfW; dx <= halfW; dx++) {
                    int bx = startX + dx;
                    if (!inChunk(bx, bz, cx, cz)) continue;
                    setBlock(chunk, bx, DECK_Y - 1, bz, L_GRAY_CONCRETE);
                }
            }
        }

        buildRamp(chunk, cx, cz, startX, startZ - 1, -1, halfW);
        buildRamp(chunk, cx, cz, startX, startZ + BRIDGE_LENGTH, 1, halfW);
    }

    // ================================================================
    // 8: HUMPBACK — FIXED: visible underside arch opening
    // ================================================================

    private static void buildHumpbackBridge(ChunkAccess chunk,
                                             int cx, int cz,
                                             int startX,
                                             int startZ) {
        int halfW      = BRIDGE_WIDTH / 2;
        int humpHeight = 10;
        int center     = startZ + BRIDGE_LENGTH / 2;

        // arch opening zone
        int archStart = startZ + 25;
        int archEnd   = startZ + BRIDGE_LENGTH - 25;
        int archSpan  = archEnd - archStart; // 50

        for (int bz = startZ; bz < startZ + BRIDGE_LENGTH; bz++) {
            double t  = (double)(bz - center) / (BRIDGE_LENGTH / 2.0);
            int riseY = (int) Math.round((1.0 - t * t) * humpHeight);
            int surfY = GROUND_Y + riseY;

            boolean inArchZone = bz >= archStart && bz < archEnd;

            // underside arch curve
            int archY = WATER_BOTTOM;
            if (inArchZone) {
                double at = (double)(bz - center) / (archSpan / 2.0);
                at = Math.max(-1.0, Math.min(1.0, at));
                int archRise = surfY - WATER_BOTTOM - 2;
                archY = WATER_BOTTOM
                        + (int) Math.round((1.0 - at * at) * archRise);
            }

            for (int dx = -halfW; dx <= halfW; dx++) {
                int bx = startX + dx;
                if (!inChunk(bx, bz, cx, cz)) continue;
                boolean isSide = Math.abs(dx) >= halfW - 1;

                // deck surface
                setBlock(chunk, bx, surfY, bz,
                        Math.abs(dx) == halfW ? COBBLESTONE : MOSSY_COBBLE);
                setBlock(chunk, bx, surfY - 1, bz, STONE_BRICK);

                // parapets
                if (Math.abs(dx) == halfW) {
                    setBlock(chunk, bx, surfY + 1, bz, STONE_WALL);
                    if (bz % 8 == 0)
                        setBlock(chunk, bx, surfY + 2, bz, COBBLESTONE);
                }

                if (inArchZone) {
                    if (isSide) {
                        // arch ring on sides
                        if (archY >= WATER_BOTTOM && archY < surfY - 1)
                            setBlock(chunk, bx, archY, bz, POLISHED_AND);
                        // spandrel above arch
                        for (int y = archY + 1; y < surfY - 1; y++)
                            setBlock(chunk, bx, y, bz, STONE_BRICK);
                        // clear below arch
                        for (int y = WATER_BOTTOM; y < archY; y++) {
                            BlockState ex = getBlock(chunk, bx, y, bz);
                            if (ex != WATER)
                                setBlock(chunk, bx, y, bz, AIR);
                        }
                    } else {
                        // interior — open below deck
                        for (int y = WATER_BOTTOM; y < surfY - 1; y++) {
                            BlockState ex = getBlock(chunk, bx, y, bz);
                            if (ex != WATER)
                                setBlock(chunk, bx, y, bz, AIR);
                        }
                    }
                } else {
                    // abutment zone — solid fill
                    for (int y = WATER_BOTTOM - 2; y < surfY - 1; y++)
                        setBlock(chunk, bx, y, bz, STONE_BRICK);
                }

                // clear above
                for (int y = surfY + 3; y <= DECK_Y + 20; y++)
                    setBlock(chunk, bx, y, bz, AIR);
            }
        }
    }

    // ================================================================
    // 9: CLAPPER — unchanged, already perfect
    // ================================================================

    private static void buildClapperBridge(ChunkAccess chunk,
                                            int cx, int cz,
                                            int startX, int startZ) {
        int halfW = 2;
        int pierSpan = 5;
        int deckY = GROUND_Y + 1;
        for (int bz = startZ; bz < startZ + BRIDGE_LENGTH; bz++) {
            for (int dx = -halfW; dx <= halfW; dx++) {
                int bx = startX + dx;
                if (!inChunk(bx, bz, cx, cz)) continue;
                setBlock(chunk, bx, deckY, bz,
                        Math.abs(dx) == halfW ? COBBLESTONE : SMOOTH_STONE);
            }
            if (bz % pierSpan == 0) {
                for (int dx = -halfW; dx <= halfW; dx++) {
                    int bx = startX + dx;
                    if (!inChunk(bx, bz, cx, cz)) continue;
                    for (int y = WATER_BOTTOM - 1; y <= deckY; y++)
                        setBlock(chunk, bx, y, bz, COBBLESTONE);
                }
                for (int dx : new int[]{-halfW - 1, halfW + 1}) {
                    int bx = startX + dx;
                    if (!inChunk(bx, bz, cx, cz)) continue;
                    setBlock(chunk, bx, WATER_BOTTOM - 1, bz, COBBLESTONE);
                    setBlock(chunk, bx, WATER_BOTTOM, bz, COBBLESTONE);
                }
                for (int dx : new int[]{-halfW, halfW}) {
                    int bx = startX + dx;
                    int cw = bz - 1;
                    if (!inChunk(bx, cw, cx, cz)) continue;
                    setBlock(chunk, bx, WATER_BOTTOM, cw, COBBLESTONE);
                }
            }
            for (int y = deckY + 1; y <= deckY + 4; y++)
                for (int dx = -halfW; dx <= halfW; dx++) {
                    int bx = startX + dx;
                    if (!inChunk(bx, bz, cx, cz)) continue;
                    setBlock(chunk, bx, y, bz, AIR);
                }
        }
    }

    // ================================================================
    // 10: BOARDWALK — FIXED: no moss, clean timber
    // ================================================================

    private static void buildBoardwalk(ChunkAccess chunk,
                                        int cx, int cz,
                                        int startX, int startZ) {
        int halfW = 2;
        int deckY = GROUND_Y + 1;
        for (int bz = startZ; bz < startZ + BRIDGE_LENGTH; bz++) {
            for (int dx = -halfW; dx <= halfW; dx++) {
                int bx = startX + dx;
                if (!inChunk(bx, bz, cx, cz)) continue;
                setBlock(chunk, bx, deckY, bz, OAK_PLANKS);
                if (Math.abs(dx) == halfW)
                    setBlock(chunk, bx, deckY + 1, bz, OAK_FENCE);
            }
            if (bz % 4 == 0) {
                for (int dx : new int[]{-halfW, 0, halfW}) {
                    int bx = startX + dx;
                    if (!inChunk(bx, bz, cx, cz)) continue;
                    for (int y = WATER_BOTTOM; y < deckY; y++)
                        setBlock(chunk, bx, y, bz, OAK_LOG);
                }
                for (int dx = -halfW; dx <= halfW; dx++) {
                    int bx = startX + dx;
                    if (!inChunk(bx, bz, cx, cz)) continue;
                    setBlock(chunk, bx, deckY - 1, bz, STRIPPED_OAK);
                }
            }
            for (int dx : new int[]{-halfW, halfW}) {
                int bx = startX + dx;
                if (!inChunk(bx, bz, cx, cz)) continue;
                setBlock(chunk, bx, deckY - 1, bz, STRIPPED_OAK);
            }
            if (bz % 20 == 10) {
                for (int dx = -halfW - 2; dx <= halfW + 2; dx++) {
                    int bx = startX + dx;
                    if (!inChunk(bx, bz, cx, cz)) continue;
                    setBlock(chunk, bx, deckY, bz, OAK_PLANKS);
                    if (Math.abs(dx) == halfW + 2)
                        setBlock(chunk, bx, deckY + 1, bz, OAK_FENCE);
                }
            }
        }
    }

    // ================================================================
    // 11: COVERED — unchanged, already perfect
    // ================================================================

    private static void buildCoveredBridge(ChunkAccess chunk,
                                            int cx, int cz,
                                            int startX, int startZ) {
        int halfW = BRIDGE_WIDTH / 2;
        int roofH = 6;
        int roofTopY = DECK_Y + roofH;
        for (int bz = startZ; bz < startZ + BRIDGE_LENGTH; bz++) {
            for (int dx = -halfW; dx <= halfW; dx++) {
                int bx = startX + dx;
                if (!inChunk(bx, bz, cx, cz)) continue;
                setBlock(chunk, bx, DECK_Y - 1, bz, SPRUCE_LOG);
                setBlock(chunk, bx, DECK_Y, bz, SPRUCE_PLANKS);
                boolean isEdge = Math.abs(dx) == halfW;
                if (isEdge) {
                    for (int y = DECK_Y + 1; y <= roofTopY; y++) {
                        int relY = y - DECK_Y;
                        if (relY <= 2) {
                            setBlock(chunk, bx, y, bz, DARK_OAK_PLANKS);
                        } else {
                            if (bz % 5 == 1 || bz % 5 == 2 || bz % 5 == 3)
                                setBlock(chunk, bx, y, bz, AIR);
                            else
                                setBlock(chunk, bx, y, bz, DARK_OAK_PLANKS);
                        }
                    }
                } else {
                    for (int y = DECK_Y + 1; y <= roofTopY + halfW; y++)
                        setBlock(chunk, bx, y, bz, AIR);
                }
                int absDx = Math.abs(dx);
                int roofRise = halfW - absDx;
                int roofY = roofTopY + roofRise;
                setBlock(chunk, bx, roofY, bz, SPRUCE_SLAB);
                if (isEdge)
                    for (int y = roofTopY + 1; y < roofY; y++)
                        setBlock(chunk, bx, y, bz, DARK_OAK_PLANKS);
                if (dx == 0)
                    setBlock(chunk, bx, roofTopY + halfW, bz, DARK_OAK_LOG);
            }
            if (bz % 8 == 0) {
                for (int dx = -halfW; dx <= halfW; dx++) {
                    int bx = startX + dx;
                    if (!inChunk(bx, bz, cx, cz)) continue;
                    if (Math.abs(dx) == halfW)
                        for (int y = DECK_Y; y <= roofTopY; y++)
                            setBlock(chunk, bx, y, bz, DARK_OAK_LOG);
                    setBlock(chunk, bx, roofTopY, bz, DARK_OAK_LOG);
                }
                if (inChunk(startX, bz, cx, cz))
                    setBlock(chunk, startX, roofTopY - 1, bz, LANTERN);
            }
            if (bz % 12 == 0) {
                for (int dx : new int[]{-halfW, halfW}) {
                    int bx = startX + dx;
                    if (!inChunk(bx, bz, cx, cz)) continue;
                    for (int y = WATER_BOTTOM - 1; y < DECK_Y - 1; y++)
                        setBlock(chunk, bx, y, bz, STONE_BRICK);
                }
            }
        }
        buildRamp(chunk, cx, cz, startX, startZ - 1, -1, halfW);
        buildRamp(chunk, cx, cz, startX, startZ + BRIDGE_LENGTH, 1, halfW);
    }

    // ================================================================
    // 12: FLOATING — FIXED: no midspan pillars
    // ================================================================

    private static void buildFloatingBridge(ChunkAccess chunk,
                                             int cx, int cz,
                                             int startX, int startZ) {
        int halfW = 3;
        int deckY = WATER_TOP + 1;

        for (int bz = startZ; bz < startZ + BRIDGE_LENGTH; bz++) {
            boolean isPontoon = bz % 5 == 0;

            for (int dx = -halfW; dx <= halfW; dx++) {
                int bx = startX + dx;
                if (!inChunk(bx, bz, cx, cz)) continue;

                // deck
                setBlock(chunk, bx, deckY, bz, OAK_PLANKS);

                // railings
                if (Math.abs(dx) == halfW)
                    setBlock(chunk, bx, deckY + 1, bz, OAK_FENCE);

                // pontoon floats — NO fixed supports
                if (isPontoon && Math.abs(dx) <= halfW - 1) {
                    setBlock(chunk, bx, WATER_TOP, bz, BARREL);
                    setBlock(chunk, bx, WATER_TOP - 1, bz, OAK_PLANKS);
                }

                // connecting beams between pontoons
                if (Math.abs(dx) == halfW - 1)
                    setBlock(chunk, bx, deckY - 1, bz, STRIPPED_OAK);
            }

            // shore mooring ONLY at ends — first/last 3 blocks
            boolean isShore = bz <= startZ + 2
                    || bz >= startZ + BRIDGE_LENGTH - 3;
            if (isShore) {
                for (int dx : new int[]{-halfW, halfW}) {
                    int bx = startX + dx;
                    if (!inChunk(bx, bz, cx, cz)) continue;
                    for (int y = WATER_BOTTOM; y <= deckY + 2; y++)
                        setBlock(chunk, bx, y, bz, OAK_LOG);
                }
                if (bz == startZ || bz == startZ + BRIDGE_LENGTH - 1) {
                    for (int dx : new int[]{-halfW, halfW}) {
                        int bx = startX + dx;
                        if (!inChunk(bx, bz, cx, cz)) continue;
                        setBlock(chunk, bx, deckY + 3, bz, LANTERN);
                    }
                }
            }
        }
    }

    // ================================================================
    // 13: SIMPLE SUSPENSION — FIXED: continuous, no gaps
    // ================================================================

    private static void buildSimpleSuspension(ChunkAccess chunk,
                                               int cx, int cz,
                                               int startX,
                                               int startZ) {
        int halfW      = 1;
        int postHeight = 6;
        int sagAmount  = 8;
        int center     = startZ + BRIDGE_LENGTH / 2;

        // landing platforms at both ends
        for (int end = 0; end < 2; end++) {
            int platStart = (end == 0) ? startZ : startZ + BRIDGE_LENGTH - 4;
            int platEnd   = (end == 0) ? startZ + 4 : startZ + BRIDGE_LENGTH;
            for (int bz = platStart; bz < platEnd; bz++) {
                for (int dx = -halfW - 1; dx <= halfW + 1; dx++) {
                    int bx = startX + dx;
                    if (!inChunk(bx, bz, cx, cz)) continue;
                    setBlock(chunk, bx, GROUND_Y + 1, bz, OAK_PLANKS);
                    setBlock(chunk, bx, GROUND_Y, bz, STONE);
                }
            }
        }

        // anchor posts
        for (int end = 0; end < 2; end++) {
            int postZ = (end == 0) ? startZ + 2 : startZ + BRIDGE_LENGTH - 3;
            for (int dx : new int[]{-halfW - 1, halfW + 1}) {
                int bx = startX + dx;
                if (!inChunk(bx, postZ, cx, cz)) continue;
                for (int y = GROUND_Y; y <= GROUND_Y + postHeight + 3; y++)
                    setBlock(chunk, bx, y, postZ, OAK_LOG);
                // A-frame brace
                int braceZ = postZ + (end == 0 ? -1 : 1);
                if (inChunk(bx, braceZ, cx, cz))
                    for (int y = GROUND_Y; y <= GROUND_Y + postHeight; y++)
                        setBlock(chunk, bx, y, braceZ, OAK_LOG);
            }
        }

        // continuous hanging walkway — NO gaps
        for (int bz = startZ; bz < startZ + BRIDGE_LENGTH; bz++) {
            double t   = (double)(bz - center) / (BRIDGE_LENGTH / 2.0);
            int sagY   = (int) Math.round(sagAmount * (1.0 - t * t));
            int walkY  = GROUND_Y + postHeight - sagY;

            // smooth transition at ends
            if (bz < startZ + 4)
                walkY = Math.max(walkY, GROUND_Y + 1);
            if (bz >= startZ + BRIDGE_LENGTH - 4)
                walkY = Math.max(walkY, GROUND_Y + 1);

            // plank walkway — CONTINUOUS
            for (int dx = -halfW; dx <= halfW; dx++) {
                int bx = startX + dx;
                if (!inChunk(bx, bz, cx, cz)) continue;
                setBlock(chunk, bx, walkY, bz, OAK_PLANKS);
            }

            // hand ropes
            int ropeY = walkY + 3;
            for (int dx : new int[]{-halfW - 1, halfW + 1}) {
                int bx = startX + dx;
                if (!inChunk(bx, bz, cx, cz)) continue;
                setBlock(chunk, bx, ropeY, bz, CHAIN);
            }

            // vertical ties every 2 blocks
            if (bz % 2 == 0) {
                for (int dx : new int[]{-halfW - 1, halfW + 1}) {
                    int bx = startX + dx;
                    if (!inChunk(bx, bz, cx, cz)) continue;
                    for (int y = walkY + 1; y < ropeY; y++)
                        setBlock(chunk, bx, y, bz, CHAIN);
                }
            }

            // clear above
            for (int dx = -halfW - 2; dx <= halfW + 2; dx++) {
                int bx = startX + dx;
                if (!inChunk(bx, bz, cx, cz)) continue;
                for (int y = ropeY + 1; y <= GROUND_Y + postHeight + 8; y++)
                    setBlock(chunk, bx, y, bz, AIR);
            }
        }
    }

    // ================================================================
    // 14: MOVABLE — unchanged, already perfect
    // ================================================================

    private static void buildMovableBridge(ChunkAccess chunk,
                                            int cx, int cz,
                                            int startX, int startZ) {
        int halfW    = BRIDGE_WIDTH / 2;
        int gapSize  = 14;
        int gapStart = startZ + BRIDGE_LENGTH / 2 - gapSize / 2;
        int gapEnd   = gapStart + gapSize;
        int towerH   = 12;
        int towerTopY = DECK_Y + towerH;
        for (int bz = startZ; bz < startZ + BRIDGE_LENGTH; bz++) {
            boolean inGap = bz >= gapStart && bz < gapEnd;
            for (int dx = -halfW; dx <= halfW; dx++) {
                int bx = startX + dx;
                if (!inChunk(bx, bz, cx, cz)) continue;
                if (inGap) {
                    int distFromEdge;
                    boolean leftLeaf = (bz - gapStart) < gapSize / 2;
                    if (leftLeaf) distFromEdge = bz - gapStart;
                    else distFromEdge = gapEnd - 1 - bz;
                    int raiseY = DECK_Y + 1 + distFromEdge;
                    if (distFromEdge == 0) {
                        setBlock(chunk, bx, DECK_Y, bz, IRON_BLOCK);
                        setBlock(chunk, bx, DECK_Y - 1, bz, GRAY_CONCRETE);
                    }
                    setBlock(chunk, bx, raiseY, bz, GRAY_CONCRETE);
                    if (Math.abs(dx) == halfW)
                        setBlock(chunk, bx, raiseY + 1, bz, IRON_BARS);
                } else {
                    setBlock(chunk, bx, DECK_Y - 1, bz, GRAY_CONCRETE);
                    if (Math.abs(dx) == halfW) {
                        setBlock(chunk, bx, DECK_Y, bz, SMOOTH_STONE);
                        setBlock(chunk, bx, DECK_Y + 1, bz, IRON_BARS);
                    } else {
                        setBlock(chunk, bx, DECK_Y, bz, BLACK_CONCRETE);
                    }
                    if (dx == 0 && bz % 4 < 2)
                        setBlock(chunk, bx, DECK_Y, bz, YELLOW_CONCRETE);
                }
            }
            for (int tZ : new int[]{gapStart - 1, gapEnd}) {
                if (bz == tZ) {
                    for (int dx : new int[]{-halfW - 2, halfW + 2}) {
                        int bx = startX + dx;
                        if (!inChunk(bx, bz, cx, cz)) continue;
                        for (int y = GROUND_Y; y <= towerTopY; y++)
                            setBlock(chunk, bx, y, bz, STONE_BRICK);
                        setBlock(chunk, bx, towerTopY - 2, bz, GLASS_PANE);
                        setBlock(chunk, bx, towerTopY - 1, bz, GLASS_PANE);
                        setBlock(chunk, bx, towerTopY, bz, STONE_BRICK);
                        setBlock(chunk, bx, towerTopY + 1, bz, SEA_LANTERN);
                    }
                    for (int dx = -halfW - 1; dx <= halfW + 1; dx++) {
                        int bx = startX + dx;
                        if (!inChunk(bx, bz, cx, cz)) continue;
                        setBlock(chunk, bx, towerTopY, bz, STONE_BRICK);
                    }
                    for (int dx : new int[]{-halfW - 2, halfW + 2}) {
                        int bx = startX + dx;
                        int cwZ = bz + (bz < gapStart ? -1 : 1);
                        if (!inChunk(bx, cwZ, cx, cz)) continue;
                        setBlock(chunk, bx, towerTopY - 3, cwZ, IRON_BLOCK);
                        setBlock(chunk, bx, towerTopY - 4, cwZ, IRON_BLOCK);
                    }
                    for (int dx : new int[]{-halfW, halfW}) {
                        int bx = startX + dx;
                        if (!inChunk(bx, bz, cx, cz)) continue;
                        for (int y = DECK_Y + 2; y < towerTopY - 2; y++)
                            setBlock(chunk, bx, y, bz, CHAIN);
                    }
                }
            }
            if (!inGap && bz % 15 == 0
                    && Math.abs(bz - gapStart) > 3
                    && Math.abs(bz - gapEnd) > 3) {
                for (int dx : new int[]{-halfW + 1, halfW - 1}) {
                    int bx = startX + dx;
                    if (!inChunk(bx, bz, cx, cz)) continue;
                    for (int y = WATER_BOTTOM - 2; y < DECK_Y - 1; y++)
                        setBlock(chunk, bx, y, bz, STONE_BRICK);
                }
            }
        }
        buildRamp(chunk, cx, cz, startX, startZ - 1, -1, halfW);
        buildRamp(chunk, cx, cz, startX, startZ + BRIDGE_LENGTH, 1, halfW);
    }

    // ================================================================
    // RAMP
    // ================================================================

    private static void buildRamp(ChunkAccess chunk,
                                    int cx, int cz,
                                    int startX, int startZEdge,
                                    int zDir, int halfW) {
        int riseBlocks = DECK_Y - GROUND_Y;
        int rampLength = riseBlocks * 2 + 2;
        for (int step = 0; step < rampLength; step++) {
            int bz    = startZEdge + step * zDir;
            int rampY = DECK_Y - (int) Math.round(
                    (double) step / rampLength * riseBlocks);
            for (int dx = -halfW; dx <= halfW; dx++) {
                int bx = startX + dx;
                if (!inChunk(bx, bz, cx, cz)) continue;
                for (int y = GROUND_Y; y < rampY; y++)
                    setBlock(chunk, bx, y, bz, STONE);
                if (Math.abs(dx) == halfW) {
                    setBlock(chunk, bx, rampY, bz, SMOOTH_STONE);
                    setBlock(chunk, bx, rampY + 1, bz, COBBLE_WALL);
                } else {
                    setBlock(chunk, bx, rampY, bz, BLACK_CONCRETE);
                }
                for (int y = rampY + 2; y <= DECK_Y + 8; y++)
                    setBlock(chunk, bx, y, bz, AIR);
            }
        }
    }

    // ================================================================
    // UTILITY
    // ================================================================

    private static boolean inChunk(int bx, int bz, int cx, int cz) {
        return bx >= cx && bx < cx + 16
                && bz >= cz && bz < cz + 16;
    }

    private static void setBlock(ChunkAccess chunk,
                                  int bx, int by, int bz,
                                  BlockState state) {
        if (by < chunk.getMinBuildHeight()
                || by >= chunk.getMaxBuildHeight()) return;
        chunk.setBlockState(new BlockPos(bx, by, bz), state, false);
    }

    private static BlockState getBlock(ChunkAccess chunk,
                                        int bx, int by, int bz) {
        if (by < chunk.getMinBuildHeight()
                || by >= chunk.getMaxBuildHeight()) return AIR;
        return chunk.getBlockState(new BlockPos(bx, by, bz));
    }
}