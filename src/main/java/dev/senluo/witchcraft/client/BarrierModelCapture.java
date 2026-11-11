package dev.senluo.witchcraft.client;

import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

/** A tee on the original body's vertex stream, not another model render or GPU readback. */
public final class BarrierModelCapture implements VertexConsumer {
    private final BarrierOutline outline = new BarrierOutline();
    private final BarrierOutline fallback = new BarrierOutline();
    private VertexConsumer original;
    private double x, y, z;
    private Vec3 across, up, normal;
    private long frame = -1;
    private boolean complete;
    private int vertices;
    private boolean withinBudget;

    public static BarrierModelCapture begin(LivingEntity entity, VertexConsumer original) {
        BarrierEffects.Effect effect = BarrierEffects.captureTarget(entity);
        if (effect == null) return null;
        BarrierModelCapture capture = effect.model;
        if (capture.original != null) return null;
        capture.frame = BarrierEffects.captureFrame();
        capture.complete = false;
        capture.vertices = 0;
        capture.withinBudget = true;
        capture.original = original;
        capture.across = effect.across;
        capture.up = effect.up;
        capture.normal = effect.normal;
        Vec3 center = effect.bodyCenter.subtract(Minecraft.getInstance().gameRenderer.getMainCamera().getPosition());
        capture.x = center.x;
        capture.y = center.y;
        capture.z = center.z;
        capture.outline.begin();
        return capture;
    }

    public void end(boolean successful) {
        original = null;
        complete = successful && withinBudget && outline.finish(.10F);
    }

    long frame() { return frame; }

    BarrierOutline shape(BarrierEffects.Effect effect) {
        // No reuse across a changed axis or missed render; fallback remains correctly sized.
        if (complete && frame == BarrierEffects.captureFrame() && normal.dot(effect.normal) > .9999) return outline;
        Vec3 h = effect.halfBody;
        fallback.begin();
        // Six posed-box faces. Only this fallback uses the hitbox; actual model streams take precedence.
        for (int axis = 0; axis < 3; axis++) {
            for (int sign = -1; sign <= 1; sign += 2) {
                for (int corner = 0; corner < 4; corner++) {
                    double a = (corner == 0 || corner == 3) ? -1 : 1;
                    double b = corner < 2 ? -1 : 1;
                    double px = (axis == 0 ? sign : a) * h.x;
                    double py = (axis == 1 ? sign : axis == 0 ? a : b) * h.y;
                    double pz = (axis == 2 ? sign : b) * h.z;
                    fallback.vertex((float)(px * effect.across.x + py * effect.across.y + pz * effect.across.z),
                            (float)(px * effect.up.x + py * effect.up.y + pz * effect.up.z),
                            (float)(px * effect.normal.x + py * effect.normal.y + pz * effect.normal.z));
                }
            }
        }
        return fallback.finish(.10F) ? fallback : null;
    }

    private void observe(float px, float py, float pz) {
        if (!withinBudget) return;
        withinBudget = ++vertices <= BarrierOutline.MAX_VERTICES && BarrierEffects.captureVertex();
        if (!withinBudget) return;
        double dx = px - x, dy = py - y, dz = pz - z;
        outline.vertex((float)(dx * across.x + dy * across.y + dz * across.z),
                (float)(dx * up.x + dy * up.y + dz * up.z),
                (float)(dx * normal.x + dy * normal.y + dz * normal.z));
    }

    @Override public VertexConsumer addVertex(float x, float y, float z) {
        original.addVertex(x, y, z);
        observe(x, y, z);
        return this;
    }

    @Override public void addVertex(float x, float y, float z, int color, float u, float v,
            int overlay, int light, float nx, float ny, float nz) {
        original.addVertex(x, y, z, color, u, v, overlay, light, nx, ny, nz);
        observe(x, y, z);
    }

    @Override public VertexConsumer setColor(int r, int g, int b, int a) { original.setColor(r, g, b, a); return this; }
    @Override public VertexConsumer setUv(float u, float v) { original.setUv(u, v); return this; }
    @Override public VertexConsumer setUv1(int u, int v) { original.setUv1(u, v); return this; }
    @Override public VertexConsumer setUv2(int u, int v) { original.setUv2(u, v); return this; }
    @Override public VertexConsumer setNormal(float x, float y, float z) { original.setNormal(x, y, z); return this; }
}
