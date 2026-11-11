package dev.senluo.witchcraft.mixin.ritual;

import dev.senluo.witchcraft.ritual.OwnershipRitual;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = Player.class, priority = 2000)
public abstract class FrozenPlayerMixin {
    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void witchcraft$holdSimulation(CallbackInfo ci) {
        if (OwnershipRitual.frozen((Player)(Object)this)) ci.cancel();
    }
}
