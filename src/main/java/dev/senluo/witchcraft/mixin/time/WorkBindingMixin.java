package dev.senluo.witchcraft.mixin.time;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import dev.senluo.witchcraft.time.WorkTicks;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.TickingBlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Bind once. The chunk's rebindable ticker stays available to Lithium and external callers. */
@Mixin(LevelChunk.class)
public abstract class WorkBindingMixin {
    @Shadow @Final private Map<BlockPos, ?> tickersInLevel;
    @Shadow @Final private Level level;

    @WrapOperation(method = "lambda$updateBlockEntityTicker$6", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/Level;addBlockEntityTicker(Lnet/minecraft/world/level/block/entity/TickingBlockEntity;)V"))
    private void senluo$bind(Level level, TickingBlockEntity ticker, Operation<Void> original,
                             @Local(argsOnly = true) BlockEntity entity) {
        original.call(level, WorkTicks.bind(level, ticker, entity));
    }
    @Inject(method = "updateBlockEntityTicker", at = @At("RETURN"))
    private void senluo$rebound(BlockEntity entity, CallbackInfo ci) {
        WorkTicks.rebound((TickingBlockEntity) tickersInLevel.get(entity.getBlockPos()), entity);
        if (entity.getLevel() instanceof net.minecraft.server.level.ServerLevel level)
            dev.senluo.witchcraft.ritual.RitualCenters.changed(level, entity.getBlockPos());
    }
    @Inject(method = "removeBlockEntity", at = @At("RETURN"))
    private void senluo$removed(BlockPos pos, CallbackInfo ci) {
        if (level instanceof net.minecraft.server.level.ServerLevel server)
            dev.senluo.witchcraft.ritual.RitualCenters.changed(server, pos);
    }
}
