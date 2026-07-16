package io.github.opencubicchunks.cubicchunks.mixin.core;

import io.github.opencubicchunks.cubicchunks.world.level.CubicLevelHeightAccessor;
import io.github.opencubicchunks.cubicchunks.world.level.cube.Cube;
import io.github.opencubicchunks.cubicchunks.world.level.cube.CubePos;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LevelChunk.class)
public abstract class MixinLevelChunk {
    
    // Compile-safe target for the Forge 1.20.1 official-mapping workspace.
    // The embedded cubic layer is experimental, so do not fail the build if
    // this target is absent/remapped differently in a particular environment.
    @Inject(method = "getBlockState",
            at = @At("HEAD"),
            cancellable = true,
            require = 0,
            remap = false)
    private void cc$getBlockState(BlockPos pos, CallbackInfoReturnable<BlockState> cir) {
        LevelChunk self = (LevelChunk)(Object) this;
        Level level = self.getLevel();
        
        if (level instanceof CubicLevelHeightAccessor cubic && cubic.isCubic()) {
            CubePos cubePos = CubePos.fromBlockPos(pos);
            Cube cube = cubic.getCube(cubePos);
            if (cube != null) {
                cir.setReturnValue(cube.getBlockState(pos));
            }
        }
    }
}