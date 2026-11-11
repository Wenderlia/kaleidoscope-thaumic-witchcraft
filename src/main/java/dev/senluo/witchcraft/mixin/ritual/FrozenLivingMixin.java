package dev.senluo.witchcraft.mixin.ritual;

import dev.senluo.witchcraft.ritual.OwnershipRitual;
import dev.senluo.witchcraft.ritual.SustainedRitual;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = LivingEntity.class, priority = 2000)
public abstract class FrozenLivingMixin {
    private boolean witchcraft$locked() {
        LivingEntity self = (LivingEntity)(Object)this;
        return !self.level().isClientSide && OwnershipRitual.frozen(self);
    }
    @Inject(method = "isPushable", at = @At("HEAD"), cancellable = true)
    private void witchcraft$holdCollision(CallbackInfoReturnable<Boolean> ci) {
        LivingEntity self = (LivingEntity)(Object)this;
        // Sustained subjects remain collision candidates; push intercepts the contact without moving them.
        if (self instanceof Player && OwnershipRitual.frozen(self) && !SustainedRitual.active(self)) ci.setReturnValue(false);
    }
    @Inject(method = "hurt", at = @At("HEAD"), cancellable = true)
    private void witchcraft$holdDamage(DamageSource source, float amount, CallbackInfoReturnable<Boolean> ci) {
        LivingEntity self = (LivingEntity)(Object)this;
        if (amount > 0) SustainedRitual.attack(self, source);
        if (OwnershipRitual.protectedFromDamage(self)) ci.setReturnValue(false);
    }
    @Inject(method = "setHealth", at = @At("HEAD"), cancellable = true)
    private void witchcraft$holdDirectHealing(float health, CallbackInfo ci) {
        LivingEntity self = (LivingEntity)(Object)this;
        if (witchcraft$locked() && health > self.getHealth()) ci.cancel();
    }
    // Per-effect cancellation in vanilla's loop can still return true; retain the bulk result contract.
    @Inject(method = "removeAllEffects", at = @At("HEAD"), cancellable = true)
    private void witchcraft$holdCures(CallbackInfoReturnable<Boolean> ci) {
        if (witchcraft$locked() && !OwnershipRitual.syncingDisplay()) ci.setReturnValue(false);
    }
}
