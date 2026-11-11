package dev.senluo.witchcraft.mixin.time;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.senluo.witchcraft.time.NativeTicks;
import dev.senluo.witchcraft.time.TemporalWorld;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(ServerLevel.class)
public abstract class RandomTickMixin {
    @WrapMethod(method = "tickChunk")
    private void senluo$nativeSamples(LevelChunk chunk, int speed, Operation<Void> original) {
        ServerLevel level = (ServerLevel) (Object) this;
        NativeTicks.Sample previous = NativeTicks.SAMPLE.get();
        NativeTicks.SAMPLE.set(new NativeTicks.Sample(level, TemporalWorld.get(level).beginChunk(chunk.getPos().toLong())));
        try { original.call(chunk, speed); }
        finally { if (previous == null) NativeTicks.SAMPLE.remove(); else NativeTicks.SAMPLE.set(previous); }
    }
    @WrapOperation(method = "tickChunk", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/level/ServerLevel;getBlockRandomPos(IIII)Lnet/minecraft/core/BlockPos;"), require = 1)
    private BlockPos senluo$samplePosition(ServerLevel level, int x, int y, int z, int mask, Operation<BlockPos> original) {
        BlockPos result = original.call(level, x, y, z, mask);
        NativeTicks.Sample scope = NativeTicks.SAMPLE.get();
        if (scope != null) scope.position = result;
        return result;
    }
    @WrapOperation(method = "tickChunk", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/chunk/LevelChunkSection;getBlockState(III)Lnet/minecraft/world/level/block/state/BlockState;"), require = 1, allow = 1)
    private BlockState senluo$absorbSample(LevelChunkSection section, int x, int y, int z, Operation<BlockState> original) {
        BlockState state = original.call(section, x, y, z);
        NativeTicks.Sample scope = NativeTicks.SAMPLE.get();
        if (scope != null && scope.nativePass && scope.position != null
                && !state.isRandomlyTicking() && !state.getFluidState().isRandomlyTicking())
            TemporalWorld.get(scope.level).absorbRandomSample(scope.position);
        return state;
    }
}
