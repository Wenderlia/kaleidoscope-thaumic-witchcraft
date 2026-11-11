package dev.senluo.witchcraft.client;

import com.mojang.blaze3d.vertex.*;
import dev.senluo.witchcraft.KaleidoscopeWitchcraft;
import dev.senluo.witchcraft.slash.*;
import net.minecraft.client.renderer.*;
import net.minecraft.client.renderer.entity.*;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;

/** Cast-local visual styles share native alpha/depth and the existing white texture sample. */
@EventBusSubscriber(modid = KaleidoscopeWitchcraft.MOD_ID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class SlashRenderer extends EntityRenderer<SlashEntity> {
    private static final ResourceLocation TEXTURE = SlashContent.id("textures/effect/slash_edge.png");
    private static final RenderType MATERIAL = Material.make();
    private final java.util.Map<SlashEntity, SlashRenderView> views = new java.util.WeakHashMap<>();

    private static final class Material extends RenderType {
        private Material() { super("slash_factory", DefaultVertexFormat.NEW_ENTITY, VertexFormat.Mode.QUADS, 1024, false, true, () -> {}, () -> {}); }
        static RenderType make() {
            return create("witchcraft_slash", DefaultVertexFormat.NEW_ENTITY, VertexFormat.Mode.QUADS, 2048, false, true,
                    CompositeState.builder().setShaderState(RENDERTYPE_EYES_SHADER)
                            .setTextureState(new TextureStateShard(TEXTURE, true, false))
                            .setTransparencyState(TRANSLUCENT_TRANSPARENCY).setCullState(NO_CULL)
                            .setDepthTestState(LEQUAL_DEPTH_TEST).setWriteMaskState(COLOR_WRITE).createCompositeState(false));
        }
    }
    public SlashRenderer(EntityRendererProvider.Context context) { super(context); shadowRadius = 0; }
    @SubscribeEvent public static void register(EntityRenderersEvent.RegisterRenderers event) { event.registerEntityRenderer(SlashContent.SLASH.get(), SlashRenderer::new); }
    @SubscribeEvent public static void registerGui(RegisterGuiLayersEvent event) {
        event.registerBelowAll(SlashContent.id("slash_drawing"), SlashDrawingClient::renderGui);
    }
    @Override public ResourceLocation getTextureLocation(SlashEntity entity) { return TEXTURE; }

    private static boolean visibleToViewer(SlashEntity slash) {
        var viewer = net.minecraft.client.Minecraft.getInstance().player;
        return slash.visibleTo(viewer == null ? null : viewer.getUUID());
    }

    @Override public boolean shouldRender(SlashEntity slash, net.minecraft.client.renderer.culling.Frustum frustum,
                                          double x, double y, double z) {
        return visibleToViewer(slash) && super.shouldRender(slash, frustum, x, y, z);
    }

    @Override public void render(SlashEntity slash, float yaw, float partial, PoseStack poses, MultiBufferSource buffers, int light) {
        if (!visibleToViewer(slash)) return;
        if (slash.hasFragments() && slash.fragments() == null) return;
        if (!slash.hasFragments() && slash.hasRadialGeometry() && slash.radial() == null) return;
        float alpha = slash.fade(partial) * (.6F + .4F * (float) Math.sqrt(slash.strength()));
        if (alpha <= 0) return;
        SlashRenderView state = views.computeIfAbsent(slash, SlashRenderView::new);
        int segments = slash.fragments() != null ? slash.fragments().size() : slash.radial() != null ? slash.radial().outline().edges().size()
                : 1;
        state.segments(segments + 1); // One independent basis for contact flashes.
        Vec3 blade = slash.renderBlade(partial);
        Vec3 forward = slash.renderForward(partial);
        Vec3 view = entityRenderDispatcher.camera.getPosition().subtract(slash.getPosition(partial)).normalize();
        Vec3 across = null;
        VertexConsumer out = buffers.getBuffer(MATERIAL);
        var pose = poses.last();
        Vec3 launchOffset = state.launchOffset(slash, partial);
        if (slash.fragments() != null) {
            var fragments = slash.fragments();
            for (int i = 0; i < fragments.size(); i++) {
                float opacity = fragments.opacity(i, partial) * slash.fade(partial);
                if (opacity <= 0) continue;
                Vec3 world = fragments.renderPosition(i, partial), center = world.subtract(slash.getPosition(partial));
                Vec3 towardCamera = entityRenderDispatcher.camera.getPosition().subtract(world).normalize();
                Vec3 axis = fragments.blade(i), direction = fragments.direction(i);
                Vec3 normal = state.across(i, axis, towardCamera, direction);
                if (slash.visual() == SlashVisual.BLACK_RED)
                    blackRed(slash, out, pose, center, axis, normal, towardCamera, direction,
                            SlashFragments.HALF_LENGTH, SlashFragments.HALF_LENGTH * 2, opacity, partial, true);
                else bands(out, pose, center, axis, normal, towardCamera, SlashFragments.HALF_LENGTH,
                        SlashFragments.HALF_LENGTH * 2, opacity);
            }
        } else if (slash.radial() != null) {
            var radial = slash.radial(); var edges = radial.outline().edges();
            for (int i = 0; i < edges.size(); i++) {
                float opacity = radial.opacity(i, partial) * alpha;
                if (opacity <= 0) continue;
                var edge = edges.get(i); double radius = radial.renderRadius(i, partial);
                Vec3 center = edge.middle().scale(radius);
                Vec3 towardCamera = entityRenderDispatcher.camera.getPosition().subtract(slash.getPosition(partial).add(center)).normalize();
                Vec3 normal = state.across(i, edge.axis(), towardCamera, edge.middle());
                if (slash.visual() == SlashVisual.BLACK_RED) {
                    blackRed(slash, out, pose, center, edge.axis(), normal, towardCamera,
                            edge.middle().normalize(), edge.half()*radius, radius*2, opacity, partial, true);
                    continue;
                }
                int layer = 0;
                double widthScale = SlashAppearance.widthScale(radius * 2);
                for (var band : SlashAppearance.BANDS) {
                    ribbonGradient(out, pose, center.add(normal.scale(band.offset()*widthScale)).add(towardCamera.scale(layer++ * .001)),
                            edge.axis(), normal, edge.half() * radius, band.width()*widthScale, band.red(), band.green(), band.blue(),
                            opacity * band.lowOpacity(), opacity * band.highOpacity(), .4F, .6F);
                }
            }
        } else {
            across = state.across(0, blade, view, forward);
            if (slash.visual() == SlashVisual.BLACK_RED)
                blackRed(slash, out, pose, launchOffset, blade, across, view, forward,
                        slash.renderHalfLength(partial), slash.renderHalfLength(partial)*2, alpha, partial, false);
            else bands(out, pose, launchOffset, blade, across, view, slash.renderHalfLength(partial),
                    slash.renderHalfLength(partial)*2, alpha);
        }
        float flash = slash.contactFlash(partial);
        if (flash > 0) {
            // Radial edges do not share their edge basis with this independent flash.
            if (across == null) across = state.across(segments, blade, view, forward);
            Vec3 point = slash.contactPoint().subtract(slash.getPosition(partial));
            boolean red = slash.visual() == SlashVisual.BLACK_RED;
            ribbon(out, pose, point, blade, across, .6, .035, red ? 255 : 233, red ? 67 : 252, red ? 78 : 250, flash * .85F);
            ribbon(out, pose, point, across, blade, .2, .008, red ? 195 : 150, red ? 18 : 215, red ? 40 : 223, flash * .5F);
            // Representative blades, not one primitive/entity per logical hit. Same audience,
            // native depth test and straight cut silhouette as the parent.
            int rays = slash.fissionRays();
            for (int i = 0; i < rays; i++) {
                double angle = Math.PI * (i + .25) / rays;
                Vec3 axis = blade.scale(Math.cos(angle)).add(across.scale(Math.sin(angle)));
                Vec3 side = across.scale(Math.cos(angle)).subtract(blade.scale(Math.sin(angle)));
                ribbon(out, pose, point.add(view.scale(.002 * i)), axis, side, .8 + .25 * flash,
                        .012, red ? 255 : 220, red ? 48 : 248, red ? 62 : 255, flash * .7F);
            }
        }
    }

    private static void blackRed(SlashEntity slash, VertexConsumer out, PoseStack.Pose pose, Vec3 center, Vec3 axis,
                                 Vec3 cross, Vec3 view, Vec3 forward, double half, double widthLength,
                                 float opacity, float partial, boolean joined) {
        BlackRedSlashMesh.emit(half, widthLength, slash.tickCount+partial, slash.getUUID().hashCode(), joined, opacity,
                (along, across, lag, layer, rgb, alpha) -> {
                    // Exact texel center: neutral white, no repeated tip alpha on tessellated segments.
                    out.addVertex(pose,
                            (float)(center.x+axis.x*along+cross.x*across-forward.x*lag+view.x*layer),
                            (float)(center.y+axis.y*along+cross.y*across-forward.y*lag+view.y*layer),
                            (float)(center.z+axis.z*along+cross.z*across-forward.z*lag+view.z*layer))
                            .setColor((rgb>>16)&255, (rgb>>8)&255, rgb&255, Math.clamp((int)(alpha*255), 0, 255))
                            .setUv(64.5F/128, 8.5F/16).setOverlay(OverlayTexture.NO_OVERLAY)
                            .setLight(LightTexture.FULL_BRIGHT).setNormal(pose, 0, 1, 0);
                });
    }

    private static void bands(VertexConsumer out, PoseStack.Pose pose, Vec3 center, Vec3 blade, Vec3 across, Vec3 view,
                              double half, double widthLength, float alpha) {
        int layer = 0;
        double scale = SlashAppearance.widthScale(widthLength);
        for (var band : SlashAppearance.BANDS) {
            ribbonGradient(out, pose, center.add(across.scale(band.offset()*scale)).add(view.scale(layer++ * .001)), blade, across,
                    half * band.length(), band.width()*scale, band.red(), band.green(), band.blue(),
                    alpha * band.lowOpacity(), alpha * band.highOpacity(), 0, 1);
        }
    }

    static void ribbon(VertexConsumer out, PoseStack.Pose pose, Vec3 center, Vec3 axis, Vec3 cross,
                               double half, double width, int r, int g, int b, float alpha) {
        ribbon(out, pose, center, axis, cross, half, width, r, g, b, alpha, 0, 1);
    }
    private static void ribbon(VertexConsumer out, PoseStack.Pose pose, Vec3 center, Vec3 axis, Vec3 cross,
                               double half, double width, int r, int g, int b, float alpha, float u0, float u1) {
        ribbonGradient(out, pose, center, axis, cross, half, width, r, g, b, alpha, alpha, u0, u1);
    }
    private static void ribbonGradient(VertexConsumer out, PoseStack.Pose pose, Vec3 center, Vec3 axis, Vec3 cross,
                                       double half, double width, int r, int g, int b,
                                       float lowAlpha, float highAlpha, float u0, float u1) {
        int low = Math.clamp((int) (lowAlpha * 255), 0, 255);
        int high = Math.clamp((int) (highAlpha * 255), 0, 255);
        Vec3 along = axis.scale(half), across = cross.scale(width);
        vertex(out, pose, center.subtract(along).subtract(across), u0, 1, r, g, b, low);
        vertex(out, pose, center.add(along).subtract(across), u1, 1, r, g, b, low);
        vertex(out, pose, center.add(along).add(across), u1, 0, r, g, b, high);
        vertex(out, pose, center.subtract(along).add(across), u0, 0, r, g, b, high);
    }
    private static void vertex(VertexConsumer out, PoseStack.Pose pose, Vec3 p, float u, float v, int r, int g, int b, int a) {
        out.addVertex(pose, (float) p.x, (float) p.y, (float) p.z).setColor(r, g, b, a).setUv(u, v)
                .setOverlay(OverlayTexture.NO_OVERLAY).setLight(LightTexture.FULL_BRIGHT).setNormal(pose, 0, 1, 0);
    }
}
