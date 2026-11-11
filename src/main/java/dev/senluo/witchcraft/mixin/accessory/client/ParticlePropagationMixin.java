package dev.senluo.witchcraft.mixin.accessory.client;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.senluo.witchcraft.client.PropagationParticles;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleEngine;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(ParticleEngine.class)
public abstract class ParticlePropagationMixin {
    @WrapMethod(method = "createParticle")
    private Particle witchcraft$source(ParticleOptions type, double x, double y, double z,
            double dx, double dy, double dz, Operation<Particle> original) {
        if (!PropagationParticles.capturing()) return original.call(type, x, y, z, dx, dy, dz);
        return PropagationParticles.create(new Vec3(x, y, z), () -> original.call(type, x, y, z, dx, dy, dz));
    }

    @WrapOperation(method = "tickParticle", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/particle/Particle;tick()V"))
    private void witchcraft$advance(Particle particle, Operation<Void> original) {
        if (!PropagationParticles.tracks(particle)) original.call(particle);
        else PropagationParticles.tick(particle, () -> original.call(particle));
    }
}
