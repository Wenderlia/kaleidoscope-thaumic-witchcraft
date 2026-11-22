package dev.senluo.witchcraft.slash;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleType;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.ExtraCodecs;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

/** A settled living hit. The native single-particle packet carries the blade tangent in its velocity. */
public record SlashHitParticle(boolean blackRed, int seed, Vector3f travel) implements ParticleOptions {
    private static final MapCodec<SlashHitParticle> DATA = RecordCodecBuilder.mapCodec(instance -> instance.group(
            Codec.BOOL.fieldOf("black_red").forGetter(SlashHitParticle::blackRed),
            Codec.INT.fieldOf("seed").forGetter(SlashHitParticle::seed),
            ExtraCodecs.VECTOR3F.fieldOf("travel").forGetter(SlashHitParticle::travel)
    ).apply(instance, SlashHitParticle::new));
    private static final StreamCodec<RegistryFriendlyByteBuf, SlashHitParticle> STREAM = StreamCodec.composite(
            ByteBufCodecs.BOOL, SlashHitParticle::blackRed, ByteBufCodecs.INT, SlashHitParticle::seed,
            ByteBufCodecs.VECTOR3F, SlashHitParticle::travel, SlashHitParticle::new);

    static ParticleType<SlashHitParticle> type() {
        return new ParticleType<>(false) {
            @Override public MapCodec<SlashHitParticle> codec() { return DATA; }
            @Override public StreamCodec<RegistryFriendlyByteBuf, SlashHitParticle> streamCodec() { return STREAM; }
        };
    }

    @Override public ParticleType<SlashHitParticle> getType() { return SlashContent.HIT.get(); }

    static void emit(SlashEntity slash, ServerPlayer caster, int targetId, Vec3 point, SlashGeometry.Sweep sweep) {
        SlashVisibility audience = slash.visibility();
        if (audience == SlashVisibility.HIDDEN) return;
        Vec3 direction = sweep.to().subtract(sweep.from()).normalize();
        if (direction.lengthSqr() < .5) direction = slash.forward();
        int seed = 31 * (31 * slash.getUUID().hashCode() + targetId) + slash.tickCount;
        var effect = new SlashHitParticle(slash.visual() == SlashVisual.BLACK_RED, seed, direction.toVector3f());
        Vec3 tangent = sweep.blade();
        var packet = new ClientboundLevelParticlesPacket(effect, false, point.x, point.y, point.z,
                (float)tangent.x, (float)tangent.y, (float)tangent.z, 1, 0);
        if (audience == SlashVisibility.CASTER_ONLY) caster.connection.send(packet);
        else caster.serverLevel().getChunkSource().broadcast(slash, packet);
    }
}
