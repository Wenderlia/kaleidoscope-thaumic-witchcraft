package dev.senluo.witchcraft.mixin.ritual;

import dev.senluo.witchcraft.region.RegionProtection;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.behavior.HarvestFarmland;
import net.minecraft.world.entity.npc.Villager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Veto the whole harvest/plant attempt before drops or inventory payment, not just its final write. */
@Mixin(HarvestFarmland.class)
public abstract class RegionalVillagerFarmMixin {
    @Shadow private BlockPos aboveFarmlandPos;
    @Inject(method = "tick(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/entity/npc/Villager;J)V", at = @At("HEAD"), cancellable = true)
    private void witchcraft$farm(ServerLevel level, Villager actor, long time, CallbackInfo ci) {
        if (aboveFarmlandPos != null && RegionProtection.mobEdit(level, aboveFarmlandPos, actor)) ci.cancel();
    }
}
