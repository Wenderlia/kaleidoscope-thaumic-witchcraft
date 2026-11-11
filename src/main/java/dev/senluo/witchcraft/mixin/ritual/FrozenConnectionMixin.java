package dev.senluo.witchcraft.mixin.ritual;

import dev.senluo.witchcraft.ritual.OwnershipRitual;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = ServerGamePacketListenerImpl.class, priority = 2000)
public abstract class FrozenConnectionMixin {
    @Shadow public ServerPlayer player;
    @Shadow private boolean clientIsFloating;
    @Shadow private int aboveGroundTickCount;
    @Unique private int witchcraft$lastInventoryCorrection = Integer.MIN_VALUE;

    @Inject(method = "tick", at = @At("HEAD"))
    private void witchcraft$keepConnection(CallbackInfo ci) {
        if (OwnershipRitual.frozen(player)) { clientIsFloating = false; aboveGroundTickCount = 0; }
    }
    // The thread hand-off runs first. Only gameplay commands are blocked, never keepalive or chat.
    @Inject(method = {"handleMovePlayer", "handlePlayerInput", "handlePlayerCommand",
            "handleUseItemOn", "handleUseItem", "handleInteract", "handlePlayerAbilities", "handleAnimate"},
            at = @At(value = "INVOKE", target = "Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/server/level/ServerLevel;)V", shift = At.Shift.AFTER), cancellable = true)
    private void witchcraft$holdActions(CallbackInfo ci) {
        if (OwnershipRitual.frozen(player)) ci.cancel();
    }
    @Inject(method = {"handlePlayerAction", "handleContainerClick", "handleSetCreativeModeSlot",
            "handleSetCarriedItem", "handleContainerButtonClick", "handlePlaceRecipe"},
            at = @At(value = "INVOKE", target = "Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/server/level/ServerLevel;)V", shift = At.Shift.AFTER), cancellable = true)
    private void witchcraft$holdInventory(CallbackInfo ci) {
        if (!OwnershipRitual.frozen(player)) return;
        // Also undo prediction from an input already in flight when the freeze began.
        if (witchcraft$lastInventoryCorrection != player.server.getTickCount()) {
            witchcraft$lastInventoryCorrection = player.server.getTickCount();
            OwnershipRitual.syncInventory(player);
        }
        ci.cancel();
    }
}
