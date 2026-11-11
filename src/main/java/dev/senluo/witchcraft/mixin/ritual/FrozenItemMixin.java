package dev.senluo.witchcraft.mixin.ritual;

import dev.senluo.witchcraft.ritual.OwnershipRitual;
import dev.senluo.witchcraft.ritual.SustainedRitual;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ItemEntity.class)
public abstract class FrozenItemMixin {
    @Inject(method = "playerTouch", at = @At("HEAD"), cancellable = true)
    private void witchcraft$holdPickup(Player player, CallbackInfo ci) {
        ItemEntity self = (ItemEntity)(Object)this;
        if (OwnershipRitual.frozen(self)) {
            SustainedRitual.contact(self, player, SustainedRitual.Contact.COLLISION);
            ci.cancel();
        }
    }
    @Inject(method = "isMergable", at = @At("HEAD"), cancellable = true)
    private void witchcraft$holdMerge(CallbackInfoReturnable<Boolean> ci) {
        if (OwnershipRitual.frozen((ItemEntity)(Object)this)) ci.setReturnValue(false);
    }
    @Inject(method = "hurt", at = @At("HEAD"), cancellable = true)
    private void witchcraft$holdDamage(DamageSource source, float amount, CallbackInfoReturnable<Boolean> ci) {
        ItemEntity self = (ItemEntity)(Object)this;
        if (amount > 0) SustainedRitual.attack(self, source);
        if (OwnershipRitual.protectedFromDamage(self)) ci.setReturnValue(false);
    }
}
