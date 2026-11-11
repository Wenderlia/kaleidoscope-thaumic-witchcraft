package dev.senluo.witchcraft.mixin.ritual.client;

import dev.senluo.witchcraft.ritual.RitualView;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = AbstractClientPlayer.class, priority = 2000)
public abstract class FrozenClientPlayerMixin {
    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void witchcraft$holdAnimation(CallbackInfo ci) {
        AbstractClientPlayer player = (AbstractClientPlayer)(Object)this;
        RitualView.State view = RitualView.get(player);
        if (view == null) return;
        player.setPos(view.anchor());
        view.pose().apply(player);
        player.setOldPosAndRot();
        player.setDeltaMovement(Vec3.ZERO);
        if (view.escaping() > 0 && player.hurtTime > 0) player.hurtTime--;
        player.tickCount--;
        ci.cancel();
    }
}
