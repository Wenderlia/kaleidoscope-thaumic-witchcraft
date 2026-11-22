package dev.senluo.witchcraft.client;

import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.senluo.witchcraft.KaleidoscopeWitchcraft;
import dev.senluo.witchcraft.slash.SlashContent;
import dev.senluo.witchcraft.slash.SlashHitParticle;
import java.util.Optional;
import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.client.particle.SpriteSet;
import net.minecraft.client.particle.TextureSheetParticle;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.core.particles.ParticleGroup;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterParticleProvidersEvent;

@EventBusSubscriber(modid = KaleidoscopeWitchcraft.MOD_ID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class SlashHitParticles {
    private static final Optional<ParticleGroup> CHIPS = Optional.of(new ParticleGroup(48));
    private static final Optional<ParticleGroup> DISCHARGES = Optional.of(new ParticleGroup(20));

    @SubscribeEvent public static void register(RegisterParticleProvidersEvent event) {
        event.registerSpriteSet(SlashContent.HIT.get(), sprites -> (hit, level, x, y, z, dx, dy, dz) ->
                new Burst(level, new Vec3(x, y, z), hit, new Vec3(dx, dy, dz), sprites));
    }

    private static final class Burst extends TextureSheetParticle {
        private final Vec3 origin;
        private final boolean blackRed;
        private final SlashHitGeometry shape;
        private final AABB bounds;
        private final float u0, v0, uSpan, vSpan;

        Burst(ClientLevel level, Vec3 origin, SlashHitParticle hit, Vec3 tangent, SpriteSet sprites) {
            super(level, origin.x, origin.y, origin.z);
            this.origin = origin;
            blackRed = hit.blackRed();
            SlashHitGeometry.Frame frame = SlashHitGeometry.Frame.of(tangent, new Vec3(hit.travel()));
            shape = blackRed ? new BlackFlashImpact(frame, hit.seed()) : new SlashShardImpact(frame, hit.seed());
            lifetime = shape.ticks();
            hasPhysics = false;
            setSprite(sprites.get(blackRed ? 1 : 0, 1));
            int width = sprite.contents().width(), height = sprite.contents().height();
            if (blackRed) {
                float insetU = .5F / width, insetV = .5F / height;
                u0 = sprite.getU(insetU); v0 = sprite.getV(insetV);
                uSpan = sprite.getU(1 - insetU) - u0; vSpan = sprite.getV(1 - insetV) - v0;
            } else {
                // generic_0 has a single white texel at (4,4) on its 8x8 sheet.
                // Sampling its centre also holds with linear filtering.
                u0 = sprite.getU((width / 2 + .5F) / width);
                v0 = sprite.getV((height / 2 + .5F) / height);
                uSpan = vSpan = 0;
            }
            bounds = new AABB(origin, origin).inflate(shape.radius());
        }

        @Override public void tick() { if (++age >= lifetime) remove(); }
        @Override public Optional<ParticleGroup> getParticleGroup() { return blackRed ? DISCHARGES : CHIPS; }
        @Override public AABB getRenderBoundingBox(float partial) { return bounds; }
        @Override public ParticleRenderType getRenderType() { return ParticleRenderType.PARTICLE_SHEET_TRANSLUCENT; }

        @Override public void render(VertexConsumer buffer, Camera camera, float partial) {
            Vec3 eye = camera.getPosition().subtract(origin);
            int ambient = blackRed ? LightTexture.FULL_BRIGHT : super.getLightColor(partial);
            int light = LightTexture.pack(Math.max(9, LightTexture.block(ambient)), LightTexture.sky(ambient));
            shape.draw((age + partial) / lifetime, eye, (point, u, v, color, alpha) ->
                    buffer.addVertex((float)(point.x - eye.x), (float)(point.y - eye.y), (float)(point.z - eye.z))
                            .setUv(u0 + u * uSpan, v0 + v * vSpan)
                            .setColor(color >> 16 & 255, color >> 8 & 255, color & 255, Math.clamp((int)(alpha * 255), 0, 255))
                            .setLight(light));
        }
    }

    private SlashHitParticles() {}
}
