package dev.senluo.witchcraft.mixin.ritual;

import dev.senluo.witchcraft.ritual.OwnershipRitual;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = ServerPlayer.class, priority = 2000)
public abstract class FrozenServerPlayerMixin {
    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void witchcraft$holdServerClock(CallbackInfo ci) {
        ServerPlayer player = (ServerPlayer)(Object)this;
        if (!OwnershipRitual.frozen(player)) return;
        player.tickCount--; // ServerLevel advanced it before invoking tick.
        player.containerMenu.broadcastChanges();
        ci.cancel();
    }
    @Inject(method = "hurt", at = @At("HEAD"), cancellable = true)
    private void witchcraft$ritualProtection(DamageSource source, float amount, CallbackInfoReturnable<Boolean> ci) {
        if (amount > 0) dev.senluo.witchcraft.ritual.SustainedRitual.attack((ServerPlayer)(Object)this, source);
        if (OwnershipRitual.protectedFromDamage((ServerPlayer)(Object)this)) ci.setReturnValue(false);
    }
}
