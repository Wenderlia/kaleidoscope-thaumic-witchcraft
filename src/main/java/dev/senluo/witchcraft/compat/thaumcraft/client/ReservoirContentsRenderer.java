package dev.senluo.witchcraft.compat.thaumcraft.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.tc4port.thaumcraft.api.aspect.AspectApi;
import dev.tc4port.thaumcraft.api.aspect.AspectDefinition;
import dev.tc4port.thaumcraft.api.aspect.AspectId;
import java.util.List;
import java.util.Objects;
import javax.annotation.Nullable;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.world.inventory.InventoryMenu;

/**
 * Client-only contents layer for the portable reservoir art. The host supplies an immutable,
 * already-synchronized snapshot and the same block-unit pose used by its shell. Draw before glass.
 * Does not discover an inventory, transfer essentia, mutate stacks or own/flush the host's buffers.
 * Optional TC compatibility: only call from a TC-present client entry point.
 */
public final class ReservoirContentsRenderer {

    /** An assigned label can remain on an empty chamber; it is not evidence of stored essentia. */
    public record Chamber(@Nullable AspectId contentsAspect, long amount, long capacity,
                          @Nullable AspectId labelAspect) {
        public Chamber {
            if (amount < 0 || capacity < 0) throw new IllegalArgumentException("Negative reservoir quantity");
        }
        public double fill() {
            return capacity == 0 ? 0 : Math.min(1D, (double) amount / capacity);
        }
    }

    /** Slots are stable left / center / right, never re-sorted by amount or registry iteration. */
    public record Snapshot(List<Chamber> chambers, boolean silverwood) {
        public Snapshot {
            chambers = List.copyOf(chambers);
            if (chambers.size() != (silverwood?SilverwoodReservoirModelLayout.CELLS:ReservoirModelLayout.CELLS).length)
                throw new IllegalArgumentException("Chamber count does not match the host model");
        }
    }

    private ReservoirContentsRenderer() {}

    public static void render(Snapshot snapshot, PoseStack poses, MultiBufferSource buffers, int light) {
        Objects.requireNonNull(snapshot);
        var registry = AspectApi.registry();
        var pose = poses.last();
        // Resolve current definitions and sprite each draw: no stale colors or texture handles after reload.
        TextureAtlasSprite sprite = Minecraft.getInstance().getTextureAtlas(InventoryMenu.BLOCK_ATLAS).apply(ReservoirDisplay.LIQUID);
        for (int slot = 0; slot < snapshot.chambers().size(); slot++) {
            Chamber chamber = snapshot.chambers().get(slot);
            var layout = (snapshot.silverwood()?SilverwoodReservoirModelLayout.CELLS:ReservoirModelLayout.CELLS)[slot];
            AspectDefinition contents = chamber.contentsAspect() == null ? null
                    : registry.get(chamber.contentsAspect()).orElse(null);
            if (contents != null && chamber.fill() > 0) {
                // Opaque contents write depth; the separately submitted translucent glass overlays them.
                liquid(buffers.getBuffer(RenderType.entitySolid(InventoryMenu.BLOCK_ATLAS)), pose,
                        layout, (float) chamber.fill(), sprite, contents.color(), Math.max(200, light));
            }
            AspectDefinition label = chamber.labelAspect() == null ? null
                    : registry.get(chamber.labelAspect()).orElse(null);
            if (label != null) {
                // Match TC jar labels: the current registered glyph as dark ink on separate parchment.
                VertexConsumer v = buffers.getBuffer(RenderType.entityTranslucent(label.texture()));
                vertex(v, pose, layout.iconLeft(), layout.iconBottom(), layout.iconZ(), 0, 1, 0x191919, 204, light, 0, 0, -1);
                vertex(v, pose, layout.iconLeft(), layout.iconTop(), layout.iconZ(), 0, 0, 0x191919, 204, light, 0, 0, -1);
                vertex(v, pose, layout.iconRight(), layout.iconTop(), layout.iconZ(), 1, 0, 0x191919, 204, light, 0, 0, -1);
                vertex(v, pose, layout.iconRight(), layout.iconBottom(), layout.iconZ(), 1, 1, 0x191919, 204, light, 0, 0, -1);
            }
        }
    }

    private static void liquid(VertexConsumer v, PoseStack.Pose pose, ReservoirModelLayout.Cell cell,
                               float fill, TextureAtlasSprite sprite, int color, int light) {
        float bottom = cell.bottom(), top = bottom + fill * (cell.top() - bottom);
        float[][] polygon = cell.footprint();
        float cx = 0, cz = 0;
        for (float[] p : polygon) { cx += p[0]; cz += p[1]; }
        cx /= polygon.length; cz /= polygon.length;
        float halfWidth = 0, halfDepth = 0;
        for (float[] p : polygon) {
            halfWidth = Math.max(halfWidth, Math.abs(p[0]-cx));
            halfDepth = Math.max(halfDepth, Math.abs(p[1]-cz));
        }
        for (int i = 0; i < polygon.length; i++) {
            float[] a = polygon[i], b = polygon[(i + 1) % polygon.length];
            float dx = b[0] - a[0], dz = b[1] - a[1];
            float length = (float) Math.sqrt(dx * dx + dz * dz);
            float nx = dz / length, nz = -dx / length;
            liquidVertex(v, pose, a[0], bottom, a[1], 0, 1, sprite, color, light, nx, 0, nz);
            liquidVertex(v, pose, a[0], top, a[1], 0, 1-fill, sprite, color, light, nx, 0, nz);
            liquidVertex(v, pose, b[0], top, b[1], 1, 1-fill, sprite, color, light, nx, 0, nz);
            liquidVertex(v, pose, b[0], bottom, b[1], 1, 1, sprite, color, light, nx, 0, nz);
            cap(v, pose, cx, top, cz, b, a, halfWidth, halfDepth, sprite, color, light, 1);
            cap(v, pose, cx, bottom, cz, a, b, halfWidth, halfDepth, sprite, color, light, -1);
        }
    }

    private static void cap(VertexConsumer v, PoseStack.Pose pose, float cx, float y, float cz,
                            float[] a, float[] b, float halfWidth, float halfDepth,
                            TextureAtlasSprite sprite, int color, int light, float ny) {
        // One triangle in a quad pipeline; duplicate the last vertex, do not invent a second surface.
        float[][] points = {{cx, cz}, a, b, b};
        for (float[] p : points)
            liquidVertex(v, pose, p[0], y, p[1], .5F + (p[0]-cx)/(2*halfWidth),
                    .5F + (p[1]-cz)/(2*halfDepth), sprite, color, light, 0, ny, 0);
    }

    private static void liquidVertex(VertexConsumer v, PoseStack.Pose pose, float x, float y, float z,
                                     float u, float w, TextureAtlasSprite sprite, int color, int light,
                                     float nx, float ny, float nz) {
        vertex(v, pose, x, y, z, sprite.getU(u), sprite.getV(w), color, 255, light, nx, ny, nz);
    }

    private static void vertex(VertexConsumer v, PoseStack.Pose pose, float x, float y, float z,
                               float u, float w, int color, int alpha, int light, float nx, float ny, float nz) {
        v.addVertex(pose, x, y, z).setColor(color >> 16 & 255, color >> 8 & 255, color & 255, alpha)
                .setUv(u, w).setOverlay(OverlayTexture.NO_OVERLAY).setLight(light).setNormal(pose, nx, ny, nz);
    }
}
