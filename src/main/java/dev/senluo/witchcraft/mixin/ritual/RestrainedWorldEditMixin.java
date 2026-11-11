package dev.senluo.witchcraft.mixin.ritual;

import dev.senluo.witchcraft.ritual.WorldRestraint;
import dev.senluo.witchcraft.ritual.TntOrigins;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Level.class)
public abstract class RestrainedWorldEditMixin {
    // Only inside a traced attack callback, never normal mining/placement/flint and steel.
    @WrapMethod(method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z")
    private boolean witchcraft$veto(BlockPos pos, BlockState state, int flags, int recursion, Operation<Boolean> original) {
        Level level = (Level)(Object)this;
        if (WorldRestraint.denyWrite(level, pos, state)) return false;
        return original.call(pos, state, flags, recursion);
    }
    // Reuse the native write's returned old state instead of re-reading every edited block.
    @WrapOperation(method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/chunk/LevelChunk;setBlockState(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Z)Lnet/minecraft/world/level/block/state/BlockState;"))
    private BlockState witchcraft$forgetRemoved(LevelChunk chunk, BlockPos pos, BlockState state, boolean moving,
            Operation<BlockState> original) {
        BlockState previous = original.call(chunk, pos, state, moving);
        Level level = (Level)(Object)this;
        if (!level.isClientSide && previous != null && TntOrigins.carries(previous)
                && !TntOrigins.carries(chunk.getBlockState(pos))) TntOrigins.put(level, pos, null);
        return previous;
    }
    @Inject(method = "destroyBlock(Lnet/minecraft/core/BlockPos;ZLnet/minecraft/world/entity/Entity;I)Z", at = @At("HEAD"), cancellable = true)
    private void witchcraft$beforeDrops(BlockPos pos, boolean drops, Entity actor, int recursion, CallbackInfoReturnable<Boolean> cir) {
        if (WorldRestraint.denyDestructiveChange((Level)(Object)this)) cir.setReturnValue(false);
    }
}
