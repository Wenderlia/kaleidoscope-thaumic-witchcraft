package dev.senluo.witchcraft.brew;

import dev.senluo.witchcraft.KaleidoscopeWitchcraft;
import net.minecraft.core.particles.ParticleType;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.core.registries.Registries;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class WineParticles {
    private static final DeferredRegister<ParticleType<?>> TYPES = DeferredRegister.create(Registries.PARTICLE_TYPE, KaleidoscopeWitchcraft.MOD_ID);
    public static final DeferredHolder<ParticleType<?>, SimpleParticleType> TAP = TYPES.register("liujin_tap", () -> new SimpleParticleType(false));
    public static final DeferredHolder<ParticleType<?>, SimpleParticleType> FALL = TYPES.register("liujin_fall", () -> new SimpleParticleType(false));
    public static final DeferredHolder<ParticleType<?>, SimpleParticleType> SPLASH = TYPES.register("liujin_splash", () -> new SimpleParticleType(false));
    public static final DeferredHolder<ParticleType<?>, SimpleParticleType> DUSK_WAVE = TYPES.register("dusk_ember_wave", () -> new SimpleParticleType(false));
    public static final DeferredHolder<ParticleType<?>, SimpleParticleType> SOLAR_WAVE = TYPES.register("solar_corona", () -> new SimpleParticleType(false));
    public static void register(IEventBus bus) { TYPES.register(bus); }
    private WineParticles() {}
}
