package dev.senluo.witchcraft.brew;

import dev.senluo.witchcraft.KaleidoscopeWitchcraft;
import net.minecraft.core.particles.ParticleType;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.core.registries.Registries;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class StrideParticles {
    private static final DeferredRegister<ParticleType<?>> TYPES=DeferredRegister.create(Registries.PARTICLE_TYPE,KaleidoscopeWitchcraft.MOD_ID);
    public static final DeferredHolder<ParticleType<?>,SimpleParticleType> RUNE=TYPES.register("stride_rune",()->new SimpleParticleType(false));
    public static void register(IEventBus bus) {TYPES.register(bus);}
    private StrideParticles() {}
}
