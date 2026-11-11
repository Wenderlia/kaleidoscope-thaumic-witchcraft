package dev.senluo.witchcraft.client;

import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.senluo.witchcraft.KaleidoscopeWitchcraft;
import dev.senluo.witchcraft.accessory.AccessoryContent;
import dev.senluo.witchcraft.accessory.MotionConstraints;
import java.util.Optional;
import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.client.particle.SpriteSet;
import net.minecraft.client.particle.TextureSheetParticle;
import net.minecraft.core.particles.ParticleGroup;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterParticleProvidersEvent;
import org.joml.Quaternionf;

@EventBusSubscriber(modid = KaleidoscopeWitchcraft.MOD_ID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class ConvergingSonicParticles {
    private static final Optional<ParticleGroup> GROUP = Optional.of(new ParticleGroup(64));
    @SubscribeEvent
    public static void register(RegisterParticleProvidersEvent event) {
        event.registerSpriteSet(AccessoryContent.CONVERGING_SONIC.get(), sprites ->
                (type, level, x, y, z, dx, dy, dz) -> new Wave(level, x, y, z, dx, dy, dz, sprites));
    }

    private static final class Wave extends TextureSheetParticle {
        private final Vec3 start, limit, direction;
        private final Quaternionf front, back;
        private final double width, virtualSpeed;
        private final SpriteSet sprites;
        Wave(ClientLevel level, double x, double y, double z, double dx, double dy, double dz, SpriteSet sprites) {
            super(level, x, y, z);
            this.sprites = sprites; start = new Vec3(x, y, z);
            Vec3 travel = new Vec3(dx, dy, dz);
            // These packet velocities describe a short path, not an unbounded physical impulse.
            if (!Double.isFinite(travel.lengthSqr())) travel = Vec3.ZERO;
            if (travel.lengthSqr() > 9) travel = travel.normalize().scale(3);
            limit = start.add(travel);
            width = travel.length();
            direction = width > 1.0e-6 ? travel.scale(1 / width) : new Vec3(0, 0, 1);
            front = new Quaternionf().rotationTo(0, 0, 1, (float)direction.x, (float)direction.y, (float)direction.z);
            back = new Quaternionf(front).rotateY((float)Math.PI);
            virtualSpeed = width / 8; // Each discrete layer costs width/2: one half-gap every four ticks.
            hasPhysics = false; gravity = 0; lifetime = 32; quadSize = 1.15F;
            alpha = 0;
            setSpriteFromAge(sprites);
        }

        @Override public void tick() {
            xo = x; yo = y; zo = z;
            if (++age >= lifetime) { remove(); return; }
            // The wave spends virtual travel through the same mapper as living entities and arrows.
            // Its chosen travel speed preserves the existing four-tick half-gap visual rhythm.
            Vec3 position = MotionConstraints.approachByTravel(start, limit, age * virtualSpeed, width);
            setPos(position.x, position.y, position.z);
            setSpriteFromAge(sprites);
        }
        @Override public void render(VertexConsumer vertices, Camera camera, float partialTick) {
            float elapsed = age + partialTick, fade = ParticleAppearance.smooth(elapsed / lifetime);
            alpha = ParticleAppearance.waveAlpha(elapsed, lifetime);
            setColor(.76F - .30F * fade, .91F - .19F * fade, 1);
            Vec3 eye = camera.getPosition();
            double facing = (eye.x - x) * direction.x + (eye.y - y) * direction.y + (eye.z - z) * direction.z;
            // The wave is perpendicular to its actual path, not a card turning with the camera.
            renderRotatedQuad(vertices, camera, facing >= 0 ? front : back, partialTick);
        }
        @Override public float getQuadSize(float partialTick) {return quadSize * ParticleAppearance.waveScale(age + partialTick, lifetime);}
        @Override public Optional<ParticleGroup> getParticleGroup() {return GROUP;}
        @Override protected int getLightColor(float partialTick) { return 0xF000F0; }
        @Override public ParticleRenderType getRenderType() { return ParticleRenderType.PARTICLE_SHEET_TRANSLUCENT; }
    }
    private ConvergingSonicParticles() {}
}
