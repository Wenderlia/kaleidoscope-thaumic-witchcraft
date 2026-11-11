package dev.senluo.witchcraft.mixin.time;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.senluo.witchcraft.time.WorkTicks;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.TickingBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(Level.class)
public abstract class WorkTickMixin {
    @WrapOperation(method = "tickBlockEntities", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/block/entity/TickingBlockEntity;tick()V"), require = 1, allow = 1)
    private void senluo$interceptScheduledUpdate(TickingBlockEntity ticker, Operation<Void> original) {
        if (!((Object) this instanceof ServerLevel level)) { original.call(ticker); return; }
        WorkTicks.scheduled(level, ticker, () -> original.call(ticker));
    }
}
