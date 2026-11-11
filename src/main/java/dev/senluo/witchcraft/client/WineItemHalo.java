package dev.senluo.witchcraft.client;

import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.math.Axis;
import dev.senluo.witchcraft.KaleidoscopeWitchcraft;
import dev.senluo.witchcraft.time.TemporalContent;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterItemDecorationsEvent;
import org.joml.Matrix4f;

/** A GUI-only outward glow using the host's depth-tested, color-only emissive material. */
@EventBusSubscriber(modid = KaleidoscopeWitchcraft.MOD_ID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class WineItemHalo {
    private static final ResourceLocation TEXTURE = ResourceLocation.fromNamespaceAndPath(
            KaleidoscopeWitchcraft.MOD_ID, "textures/gui/item/gilded_wine_halo.png");
    private static final RenderType HALO = HaloMaterial.createHalo();
    private static final int COLOR = 0xFFE6AD;
    private static final int HAZE_ALPHA = 125;
    private static final int HAZE_COUNT = 2;
    private static final long LIFETIME_MILLIS = 3200L;
    private static final float HAZE_START_SIZE = 10.0F;
    private static final float HAZE_END_SIZE = 25.0F;
    // Fixed per-layer orientation varies the textured haze without turning its outward motion into a swirl.
    private static final float HAZE_LAYER_ANGLE = 2.399963F;
    // Native item geometry is around z=150, stack labels around z=200.
    private static final float DEPTH = 100.0F;

    private WineItemHalo() {}

    private static final class HaloMaterial extends RenderType {
        private HaloMaterial() {
            super("wine_halo_factory", DefaultVertexFormat.NEW_ENTITY, VertexFormat.Mode.QUADS,
                    1536, false, true, () -> {}, () -> {});
        }

        static RenderType createHalo() {
            // Vanilla eyes preserves the entire alpha tail; entityTranslucentEmissive cuts it at 0.1.
            return create("wine_halo", DefaultVertexFormat.NEW_ENTITY, VertexFormat.Mode.QUADS,
                    1536, false, true, CompositeState.builder()
                            .setShaderState(RENDERTYPE_EYES_SHADER)
                            .setTextureState(new TextureStateShard(TEXTURE, true, false))
                            .setTransparencyState(TRANSLUCENT_TRANSPARENCY)
                            .setCullState(NO_CULL)
                            .setDepthTestState(LEQUAL_DEPTH_TEST)
                            .setWriteMaskState(COLOR_WRITE)
                            .createCompositeState(false));
        }
    }

    @SubscribeEvent
    public static void register(RegisterItemDecorationsEvent event) {
        event.register(TemporalContent.LIUJIN_VODKA.get(), WineItemHalo::render);
    }

    private static boolean render(GuiGraphics graphics, Font font, ItemStack stack, int x, int y) {
        if (!ItemEffectEligibility.isGildedWine(stack)) return false;
        var player = Minecraft.getInstance().player;
        // These overlays do not write depth. Leave them visually authoritative if another provider adds one.
        if (stack.isBarVisible() || (player != null && player.getCooldowns().isOnCooldown(stack.getItem()))) return false;

        long time = Util.getMillis() % LIFETIME_MILLIS;
        VertexConsumer vertices = graphics.bufferSource().getBuffer(HALO);
        for (int i = 0; i < HAZE_COUNT; i++) {
            float age = phase(time, i, HAZE_COUNT);
            float size = HAZE_START_SIZE + (HAZE_END_SIZE - HAZE_START_SIZE) * age;
            int alpha = Math.round(HAZE_ALPHA * (float) Math.sin(Math.PI * age));
            quad(graphics, vertices, x + 8.0F, y + 8.0F, size, size, i * HAZE_LAYER_ANGLE, alpha);
        }
        // Submit both haze layers together; the texture carries their detail without extra geometry.
        graphics.bufferSource().endBatch(HALO);
        return true;
    }

    private static float phase(long time, int index, int count) {
        return ((time + index * LIFETIME_MILLIS / count) % LIFETIME_MILLIS) / (float) LIFETIME_MILLIS;
    }

    private static void quad(GuiGraphics graphics, VertexConsumer vertices, float x, float y,
                             float width, float height, float angle, int alpha) {
        if (alpha <= 0) return;
        graphics.pose().pushPose();
        try {
            graphics.pose().translate(x, y, DEPTH);
            graphics.pose().mulPose(Axis.ZP.rotation(angle));
            Matrix4f pose = graphics.pose().last().pose();
            float halfWidth = width / 2, halfHeight = height / 2;
            vertex(vertices, pose, -halfWidth, halfHeight, 0.0F, 1.0F, alpha);
            vertex(vertices, pose, halfWidth, halfHeight, 1.0F, 1.0F, alpha);
            vertex(vertices, pose, halfWidth, -halfHeight, 1.0F, 0.0F, alpha);
            vertex(vertices, pose, -halfWidth, -halfHeight, 0.0F, 0.0F, alpha);
        } finally {
            graphics.pose().popPose();
        }
    }

    private static void vertex(VertexConsumer vertices, Matrix4f pose, float x, float y, float u, float v, int alpha) {
        vertices.addVertex(pose, x, y, 0.0F)
                .setColor(COLOR >> 16 & 255, COLOR >> 8 & 255, COLOR & 255, alpha)
                .setUv(u, v).setOverlay(OverlayTexture.NO_OVERLAY).setLight(LightTexture.FULL_BRIGHT)
                .setNormal(0.0F, 0.0F, 1.0F);
    }
}
