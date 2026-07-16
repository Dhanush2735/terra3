package io.github.opencubicchunks.cubicworldgen.worldgen;


import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.SurfaceRules;
import net.minecraft.world.level.levelgen.VerticalAnchor;
import net.minecraft.world.level.levelgen.placement.CaveSurface;

/**
 * Surface rule utilities for cubic worlds.
 * Standard surface rules often assume fixed Y bounds - these don't.
 */
public class CubicSurfaceRules {
    
    /**
     * Create a surface rule that works with infinite Y.
     * Uses relative positioning instead of absolute Y checks.
     */
    public static SurfaceRules.RuleSource createInfiniteSurfaceRule() {
        // Surface rules that work relative to terrain surface
        return SurfaceRules.sequence(
            // Top layer - grass/sand based on biome
            SurfaceRules.ifTrue(
                SurfaceRules.ON_FLOOR,
                SurfaceRules.sequence(
                    // Water surfaces get sand
                    SurfaceRules.ifTrue(
                        SurfaceRules.waterBlockCheck(-1, 0),
                        SurfaceRules.state(Blocks.GRASS_BLOCK.defaultBlockState())
                    ),
                    // Underwater gets sand/gravel
                    SurfaceRules.state(Blocks.SAND.defaultBlockState())
                )
            ),
            
            // Sub-surface layers
            SurfaceRules.ifTrue(
                SurfaceRules.UNDER_FLOOR,
                SurfaceRules.sequence(
                    // 3 blocks of dirt
                    SurfaceRules.ifTrue(
                        SurfaceRules.stoneDepthCheck(0, false,CaveSurface.FLOOR),
                        SurfaceRules.state(Blocks.DIRT.defaultBlockState())
                    )
                )
            ),
            
            // Deep stone - no bedrock in infinite worlds
            SurfaceRules.state(Blocks.STONE.defaultBlockState())
        );
    }
    
    /**
     * Create surface rules for a specific Y range.
     * Useful when generating finite sections of cubic worlds.
     */
    public static SurfaceRules.RuleSource createBoundedSurfaceRule(int minY, int maxY) {
        return SurfaceRules.sequence(
            // Only apply within bounds
            SurfaceRules.ifTrue(
                SurfaceRules.yBlockCheck(VerticalAnchor.absolute(minY), 0),
                SurfaceRules.ifTrue(
                    SurfaceRules.not(SurfaceRules.yBlockCheck(VerticalAnchor.absolute(maxY), 0)),
                    createInfiniteSurfaceRule()
                )
            ),
            
            // Outside bounds - air
            SurfaceRules.state(Blocks.AIR.defaultBlockState())
        );
    }
}
