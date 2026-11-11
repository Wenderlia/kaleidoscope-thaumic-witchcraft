package dev.senluo.witchcraft.mixin.ritual.client;

import dev.senluo.witchcraft.client.RitualClient;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
public abstract class RitualScreenMixin {
    @Inject(method = "render", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/RenderSystem;clear(IZ)V", ordinal = 0))
    private void witchcraft$grayView(DeltaTracker delta, boolean renderLevel, CallbackInfo ci) {
        if (renderLevel) RitualClient.render(delta.getGameTimeDeltaTicks());
    }
}
