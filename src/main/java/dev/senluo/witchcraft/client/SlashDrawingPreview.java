package dev.senluo.witchcraft.client;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import dev.senluo.witchcraft.slash.SlashContent;
import dev.senluo.witchcraft.slash.drawing.StrokeSphere;
import dev.senluo.witchcraft.slash.drawing.StrokeTrace;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;

/** World-anchored drawing aid, projected once into the native HUD layer below other GUI contents. */
final class SlashDrawingPreview {
    private static final RenderType MATERIAL = Material.make();
    private static final Matrix4f LOCAL_TO_CLIP = new Matrix4f();
    private static final StrokeProjection PROJECTION = new StrokeProjection();
    private static Object owner;
    private static int viewportWidth, viewportHeight;
    private static double guiScale;
    private static float width, height;
    private static Vec3 liveTip;

    private static final class Material extends RenderType {
        private Material() { super("stroke_preview_factory", DefaultVertexFormat.POSITION_TEX_COLOR,
                VertexFormat.Mode.TRIANGLES, 1024, false, false, () -> {}, () -> {}); }
        static RenderType make() {
            return create("witchcraft_stroke_preview", DefaultVertexFormat.POSITION_TEX_COLOR,
                    VertexFormat.Mode.TRIANGLES, 65536, false, false,
                    CompositeState.builder().setShaderState(new ShaderStateShard(GameRenderer::getPositionTexColorShader))
                            .setTextureState(new TextureStateShard(SlashContent.id("textures/effect/stroke_guide.png"), true, false))
                            .setTransparencyState(TRANSLUCENT_TRANSPARENCY).setCullState(NO_CULL)
                            .setDepthTestState(NO_DEPTH_TEST).setWriteMaskState(COLOR_WRITE).createCompositeState(false));
        }
    }

    static void clear() { owner = null; }

    static void capture(RenderLevelStageEvent event, Object session, Vec3 origin, Vec3 tip) {
        var window = Minecraft.getInstance().getWindow();
        Vec3 relative = origin.subtract(event.getCamera().getPosition());
        // Use this frame's world matrices, before the hand pass installs a different FOV.
        LOCAL_TO_CLIP.set(event.getProjectionMatrix()).mul(event.getModelViewMatrix())
                .translate((float) relative.x, (float) relative.y, (float) relative.z);
        viewportWidth = window.getWidth(); viewportHeight = window.getHeight(); guiScale = window.getGuiScale();
        width = (float) (viewportWidth / guiScale); height = (float) (viewportHeight / guiScale);
        liveTip = tip;
        owner = session;
    }

    static void render(GuiGraphics gui, Object session, StrokeSphere sphere, StrokeTrace trace) {
        if (owner != session) return;
        owner = null; // A captured frame can be consumed only once, never replayed after world rendering stops.
        Minecraft mc = Minecraft.getInstance(); var window = mc.getWindow();
        if (viewportWidth != window.getWidth() || viewportHeight != window.getHeight()
                || guiScale != window.getGuiScale() || width <= 0 || height <= 0) return;
        var buffers = gui.bufferSource();
        VertexConsumer out = buffers.getBuffer(MATERIAL);
        try {
            var pose = gui.pose().last();
            StrokeGuideMesh.draw(sphere, trace, liveTip, (a, b, c, d, alpha, red, green, blue) -> {
                if (!PROJECTION.quad(LOCAL_TO_CLIP, a, b, c, d)) return;
                for (int i = 1; i < PROJECTION.size() - 1; i++) {
                    vertex(out, pose, 0, alpha, red, green, blue);
                    vertex(out, pose, i, alpha, red, green, blue);
                    vertex(out, pose, i + 1, alpha, red, green, blue);
                }
            });
        } finally {
            // Flush only our dedicated type. Do not flush or bind a different world/GUI render target.
            buffers.endBatch(MATERIAL);
        }
    }

    private static void vertex(VertexConsumer out, PoseStack.Pose pose, int index, int alpha, int red, int green, int blue) {
        out.addVertex(pose, PROJECTION.x(index, width), PROJECTION.y(index, height), 0)
                .setUv(PROJECTION.u(index), PROJECTION.v(index)).setColor(red, green, blue, alpha);
    }

    private SlashDrawingPreview() {}
}
