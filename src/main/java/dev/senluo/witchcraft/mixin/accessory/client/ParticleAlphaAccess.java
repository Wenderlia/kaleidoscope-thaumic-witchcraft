package dev.senluo.witchcraft.mixin.accessory.client;

import net.minecraft.client.particle.Particle;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(Particle.class)
public interface ParticleAlphaAccess {
    @Accessor("alpha") float witchcraft$alpha();
    @Accessor("alpha") void witchcraft$alpha(float value);
}
