package dev.senluo.witchcraft.mixin.ritual.client;

import dev.senluo.witchcraft.ritual.RitualView;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Minecraft.class)
public abstract class FrozenInputMixin {
    private boolean witchcraft$frozen() {
        var player = ((Minecraft)(Object)this).player;
        return player != null && RitualView.get(player) != null;
    }
    @Inject(method = "handleKeybinds", at = @At("HEAD"))
    private void witchcraft$holdInventoryKeys(CallbackInfo ci) {
        if (!witchcraft$frozen()) return;
        var options = ((Minecraft)(Object)this).options;
        // Drop removes items locally before the server can reject it. Drain only gameplay clicks;
        // chat, perspective, pause and the raw sneak key remain available.
        while (options.keyDrop.consumeClick()) {}
        while (options.keySwapOffhand.consumeClick()) {}
        while (options.keyPickItem.consumeClick()) {}
        while (options.keyInventory.consumeClick()) {}
        for (var key : options.keyHotbarSlots) while (key.consumeClick()) {}
    }
    @Inject(method = "startAttack", at = @At("HEAD"), cancellable = true)
    private void witchcraft$holdAttack(CallbackInfoReturnable<Boolean> ci) {
        if (witchcraft$frozen()) ci.setReturnValue(false);
    }
    @Inject(method = {"startUseItem", "continueAttack"}, at = @At("HEAD"), cancellable = true)
    private void witchcraft$holdUse(CallbackInfo ci) {
        if (witchcraft$frozen()) ci.cancel();
    }
}
