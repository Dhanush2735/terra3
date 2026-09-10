// ============================================================
// LOADING-SCREEN FIX (run 14): OCEAN FASTPATH removed all
// data-source cost from spawn chunks, yet the loading screen
// stayed 27s — because vanilla's prepareLevels refuses to open
// the world until 441 spawn chunks (21x21) finish TERRAIN
// generation, and this world's spawn is open Atlantic with a
// 4064-block build height. The player always /tplls away
// immediately, so preparing 441 ocean chunks is pure waste.
// This mixin shrinks the start region to 1 chunk (ticket
// radius 11 -> 1, wait target 441 -> 1). Remaining spawn
// chunks still generate lazily in the background if ever
// needed. require = 0: if the mixin fails to apply (mapping
// mismatch), the game boots unchanged — evidence either way
// in the next run's "Time elapsed" line.
// ============================================================
package net.buildtheearth.terraplusplus.mixin;

import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

@Mixin(MinecraftServer.class)
public abstract class MixinSpawnPrep {

    /** START ticket radius: 11 -> 1 (1 ticking chunk instead
     *  of 441). Target must be exactly (2r-1)^2 so the vanilla
     *  `!= target` wait loop can terminate: r=1 -> 1. */
    @ModifyConstant(
        method = {"prepareLevels", "m_129940_"},
        constant = @Constant(intValue = 11),
        require = 0)
    private int terra$startRegionRadius(int original) {
        return 1;
    }

    /** Wait-loop target: 441 -> 1 (matches radius 1 exactly —
     *  the counter cannot skip past 1 when the max is 1). */
    @ModifyConstant(
        method = {"prepareLevels", "m_129940_"},
        constant = @Constant(intValue = 441),
        require = 0)
    private int terra$startRegionTarget(int original) {
        return 1;
    }
}
