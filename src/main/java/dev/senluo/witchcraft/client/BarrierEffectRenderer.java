package dev.senluo.witchcraft.client;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import dev.senluo.witchcraft.KaleidoscopeWitchcraft;
import java.util.List;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

/** Draws contact geometry into the membrane's own premultiplied layer. */
final class BarrierEffectRenderer {
    private static final RenderType MATERIAL = ContactMaterial.createContact();
    private static final WorldEffectPass PASS = new WorldEffectPass("barrier contact layer",
            WorldEffectPass.Composite.PREMULTIPLIED_ALPHA, MATERIAL);
    private static final Emitter EMITTER = new Emitter();

    /** Vanilla eyes shader passes vertex colour unchanged. Unlike the entity
     * emissive shader it has no directional diffuse lighting. Use ordinary alpha
     * blending, not the default eyes additive blend, and keep scene depth.
     */
    private static final class ContactMaterial extends RenderType {
        private ContactMaterial() {
            super("horizon_contact_factory", DefaultVertexFormat.NEW_ENTITY, VertexFormat.Mode.QUADS,
                    1536, false, true, () -> {}, () -> {});
        }

        static RenderType createContact() {
            return create("horizon_contact", DefaultVertexFormat.NEW_ENTITY, VertexFormat.Mode.QUADS,
                    1536, false, true, CompositeState.builder()
                            .setShaderState(RENDERTYPE_EYES_SHADER)
                            .setTextureState(new TextureStateShard(ResourceLocation.fromNamespaceAndPath(
                                    KaleidoscopeWitchcraft.MOD_ID, "textures/effect/barrier_membrane.png"), true, false))
                            .setTransparencyState(TRANSLUCENT_TRANSPARENCY)
                            .setCullState(NO_CULL)
                            .setDepthTestState(LEQUAL_DEPTH_TEST)
                            .setWriteMaskState(COLOR_WRITE)
                            .createCompositeState(false));
        }
    }

    static void render(RenderLevelStageEvent event, List<BarrierEffects.Effect> effects, int count) {
        if (count == 0) return;
        PASS.render(event, buffers -> {
            VertexConsumer vertices = buffers.getBuffer(MATERIAL);
            Vec3 camera = event.getCamera().getPosition();
            for (int i = 0; i < count; i++) geometry(vertices, effects.get(i), camera);
        });
    }

    private static void geometry(VertexConsumer vertices, BarrierEffects.Effect effect, Vec3 camera) {
        EMITTER.bind(vertices, effect, camera);
        try {
            Vec3 view = camera.subtract(effect.point).normalize();
            BarrierGeometry.emit(EMITTER, effect.radius, effect.aspect, effect.opacity,
                    effect.flow, effect.compression, (float)view.dot(effect.across),
                    (float)view.dot(effect.up), (float)view.dot(effect.normal),
                    effect.point.distanceToSqr(camera) > 12 * 12, effect.outline);
        } finally {
            EMITTER.clear();
        }
    }

    /** Reused adapter; local geometry stays independent of Minecraft's buffers and world. */
    private static final class Emitter implements BarrierGeometry.Sink {
        private VertexConsumer vertices;
        private BarrierEffects.Effect effect;
        private double x, y, z;

        void bind(VertexConsumer vertices, BarrierEffects.Effect effect, Vec3 camera) {
            this.vertices = vertices;
            this.effect = effect;
            x = effect.point.x - camera.x;
            y = effect.point.y - camera.y;
            z = effect.point.z - camera.z;
        }

        void clear() { vertices = null; effect = null; }

        @Override public void vertex(float across, float up, float outward, float u, float v,
                float red, float green, float blue, float alpha) {
            Vec3 normal = effect.normal, horizontal = effect.across, vertical = effect.up;
            vertices.addVertex((float)(x + horizontal.x * across + vertical.x * up + normal.x * outward),
                            (float)(y + horizontal.y * across + vertical.y * up + normal.y * outward),
                            (float)(z + horizontal.z * across + vertical.z * up + normal.z * outward))
                    .setColor(red, green, blue, alpha).setUv(u, v)
                    .setOverlay(OverlayTexture.NO_OVERLAY).setLight(LightTexture.FULL_BRIGHT)
                    .setNormal((float)normal.x, (float)normal.y, (float)normal.z);
        }
    }

    static void release() {
        PASS.release();
    }

    private BarrierEffectRenderer() {}
}
