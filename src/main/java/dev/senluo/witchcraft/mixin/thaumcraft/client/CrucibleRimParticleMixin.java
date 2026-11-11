package dev.senluo.witchcraft.mixin.thaumcraft.client;

import dev.senluo.witchcraft.compat.thaumcraft.client.CrucibleRim;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ParticleEngine.class)
public abstract class CrucibleRimParticleMixin {
    @Inject(method = "add(Lnet/minecraft/client/particle/Particle;)V", at = @At("HEAD"), cancellable = true)
    private void witchcraft$rim(Particle particle, CallbackInfo ci) {
        if (CrucibleRim.suppress(particle)) ci.cancel();
    }
}
