package dev.senluo.witchcraft.mixin.ritual;

import dev.senluo.witchcraft.region.RegionProtection;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Level.class)
public abstract class RegionalDestroyMixin {
    @Inject(method = "destroyBlock(Lnet/minecraft/core/BlockPos;ZLnet/minecraft/world/entity/Entity;I)Z", at = @At("HEAD"), cancellable = true)
    private void witchcraft$region(BlockPos pos, boolean drops, Entity actor, int recursion, CallbackInfoReturnable<Boolean> cir) {
        if (RegionProtection.mobEdit((Level)(Object)this, pos, actor)) cir.setReturnValue(false);
    }
}
