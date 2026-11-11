package dev.senluo.witchcraft.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.logging.LogUtils;
import dev.senluo.witchcraft.KaleidoscopeWitchcraft;
import dev.senluo.witchcraft.slash.SlashContent;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent;

/** Original, bounded vertex animation. The caller owns material, light, pose and submission. */
@EventBusSubscriber(modid = KaleidoscopeWitchcraft.MOD_ID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class SlashFocusMesh {
    public static final ResourceLocation TEXTURE = SlashContent.id("textures/item/slash_focus_depth.png");
    private static final ResourceLocation MODEL = SlashContent.id("models/effect/slash_focus.sfm");
    private static final int MAX_BYTES = 8 * 1024 * 1024;
    // Normalized WandModel focus coordinates: cube [-1,1], positive Y towards its tip.
    // Keep the entire mesh above even the enlarged sceptre cap, with a small shell gap.
    private static final float INTERIOR_EDGE = .94F;
    private static final float INTERIOR_MIN_Y = -.10F;
    // The normalized-to-native basis reflects Y. Reverse winding to preserve
    // the outward front faces of the authored closed solids after that mirror.
    private static final int[] NATIVE_TRIANGLE = {0, 2, 1, 1};
    private static volatile Animation animation;

    private SlashFocusMesh() {}

    public static boolean ready() { return animation != null; }

    @SubscribeEvent
    public static void setup(net.neoforged.fml.event.lifecycle.FMLClientSetupEvent event) {
        event.enqueueWork(() -> {
            if (net.neoforged.fml.ModList.get().isLoaded("thaumcraft"))
                dev.senluo.witchcraft.compat.thaumcraft.client.SlashFocusRenderer.register();
        });
    }

    @SubscribeEvent
    public static void reload(RegisterClientReloadListenersEvent event) {
        event.registerReloadListener((ResourceManagerReloadListener) SlashFocusMesh::load);
    }

    private static void load(ResourceManager resources) {
        try (var input = resources.getResourceOrThrow(MODEL).open()) {
            byte[] bytes = input.readNBytes(MAX_BYTES + 1);
            if (bytes.length > MAX_BYTES) throw new IOException("Focus mesh exceeds 8 MiB");
            animation = Animation.read(bytes);
        } catch (IOException ex) {
            animation = null;
            // Once per resource reload; never retry resource parsing in the render loop.
            LogUtils.getLogger().warn("Cannot load {}; using static focus shell fallback", MODEL, ex);
        }
    }

    /** Returns false on missing/invalid resources; the caller still draws its static shell. */
    public static boolean render(PoseStack poses, VertexConsumer out, boolean staff, double tick, int light, int overlay) {
        Animation model = animation;
        if (model == null || !Double.isFinite(tick)) return false;
        model.sample(tick);
        poses.pushPose();
        try {
            // Match the native shell transform, not the old textured inner cube's approximation.
            if (staff) {
                poses.translate(0, -.0475F, 0);
                poses.scale(.525F, .5525F, .525F);
            } else {
                poses.scale(.5F, .5F, .5F);
            }
            poses.translate(0, -3F / 16F, 0);
            poses.scale(3F / 16F, -3F / 16F, 3F / 16F);
            var pose = poses.last();
            // TC's entityTranslucent texture contains this white material texel. No new batch/shader.
            float uv = .5F / 64F;
            for (int triangle = 0; triangle < model.colors.length; triangle++) {
                int start = triangle * 12, color = model.colors[triangle];
                float nx = model.sampled[start + 9], ny = model.sampled[start + 10], nz = model.sampled[start + 11];
                // The native buffer is QUADS: repeat the last vertex to encode a triangle.
                for (int vertex = 0; vertex < 4; vertex++) {
                    int at = start + NATIVE_TRIANGLE[vertex] * 3;
                    out.addVertex(pose, model.sampled[at], model.sampled[at + 1], model.sampled[at + 2])
                            .setColor(color).setUv(uv, uv).setOverlay(overlay).setLight(light)
                            .setNormal(pose, nx, ny, nz);
                }
            }
        } finally {
            poses.popPose();
        }
        return true;
    }

    private static final class Animation {
        final float period;
        final int[] colors;
        final float[][] frames;
        final float[] sampled;
        double lastTick = Double.NaN;

        Animation(float period, int[] colors, float[][] frames) {
            this.period = period;
            this.colors = colors;
            this.frames = frames;
            this.sampled = new float[colors.length * 12];
        }

        static Animation read(byte[] bytes) throws IOException {
            try (var in = new DataInputStream(new ByteArrayInputStream(bytes))) {
                if (in.readInt() != 0x53464D31) throw new IOException("Unsupported focus mesh format");
                int frameCount = in.readUnsignedShort(), triangles = in.readUnsignedShort();
                float period = in.readFloat();
                if (frameCount < 2 || frameCount > 128 || triangles < 1 || triangles > 1024
                        || !Float.isFinite(period) || period < 20 || period > 1200) {
                    throw new IOException("Invalid focus mesh dimensions or duration");
                }
                int expected = 12 + triangles * 4 + frameCount * triangles * 48;
                if (bytes.length != expected) throw new IOException("Focus mesh length mismatch");
                int[] colors = new int[triangles];
                for (int i = 0; i < triangles; i++) {
                    colors[i] = in.readInt();
                    if ((colors[i] >>> 24) != 255) throw new IOException("Interior faces must be opaque");
                }
                float[][] frames = new float[frameCount][triangles * 12];
                for (float[] frame : frames) {
                    for (int i = 0; i < frame.length; i++) {
                        float value = in.readFloat();
                        int component = i % 12;
                        float minimum = component < 9 && component % 3 == 1 ? INTERIOR_MIN_Y
                                : component < 9 ? -INTERIOR_EDGE : -1.001F;
                        float maximum = component < 9 ? INTERIOR_EDGE : 1.001F;
                        if (!Float.isFinite(value) || value < minimum - 1E-6F || value > maximum + 1E-6F) {
                            throw new IOException("Invalid focus vertex or normal");
                        }
                        frame[i] = value;
                    }
                }
                return new Animation(period, colors, frames);
            }
        }

        // Called on the render thread. Multiple views at the same animation time reuse interpolation.
        void sample(double tick) {
            if (tick == lastTick) return;
            double phase = (tick % period + period) % period * frames.length / period;
            int from = (int) phase;
            float fraction = (float) (phase - from);
            float[] a = frames[from], b = frames[(from + 1) % frames.length];
            for (int i = 0; i < sampled.length; i++) sampled[i] = a[i] + fraction * (b[i] - a[i]);
            for (int at = 9; at < sampled.length; at += 12) {
                float x = sampled[at], y = sampled[at + 1], z = sampled[at + 2];
                float length = (float) Math.sqrt(x*x + y*y + z*z);
                if (length > 1E-6F) {
                    sampled[at] /= length; sampled[at + 1] /= length; sampled[at + 2] /= length;
                }
            }
            lastTick = tick;
        }
    }
}
