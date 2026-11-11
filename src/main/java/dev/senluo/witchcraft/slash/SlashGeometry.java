package dev.senluo.witchcraft.slash;

import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Finite growing blade. Gameplay and rendering share the same distance/length law. */
public final class SlashGeometry {
    public static final double RANGE = 24;
    public static final double INITIAL_LENGTH = 1.5;
    public static final double FINAL_LENGTH = 8;
    public static final double HALF_THICKNESS = .06;

    private SlashGeometry() {}

    public static double halfLength(double distance) {
        return (INITIAL_LENGTH + (FINAL_LENGTH - INITIAL_LENGTH)
                * Math.clamp(distance / RANGE, 0, 1)) * .5;
    }

    public static Vec3 blade(Vec3 forward, float roll) {
        Vec3 reference = Math.abs(forward.y) > .98 ? new Vec3(1, 0, 0) : new Vec3(0, 1, 0);
        Vec3 right = forward.cross(reference).normalize();
        Vec3 up = right.cross(forward).normalize();
        return right.scale(Math.cos(roll)).add(up.scale(Math.sin(roll)));
    }

    public record Contact(double fraction, Vec3 point) {}

    public record Sweep(Vec3 from, Vec3 to, Vec3 blade, double halfBefore, double halfAfter, double padding) {
        public Sweep(Vec3 from, Vec3 to, Vec3 blade, double halfBefore, double halfAfter) {
            this(from, to, blade, halfBefore, halfAfter, HALF_THICKNESS);
        }
        public AABB bounds() {
            Vec3 a = from.add(blade.scale(halfBefore)), b = from.subtract(blade.scale(halfBefore));
            Vec3 c = to.add(blade.scale(halfAfter)), d = to.subtract(blade.scale(halfAfter));
            return new AABB(Math.min(Math.min(a.x, b.x), Math.min(c.x, d.x)),
                    Math.min(Math.min(a.y, b.y), Math.min(c.y, d.y)),
                    Math.min(Math.min(a.z, b.z), Math.min(c.z, d.z)),
                    Math.max(Math.max(a.x, b.x), Math.max(c.x, d.x)),
                    Math.max(Math.max(a.y, b.y), Math.max(c.y, d.y)),
                    Math.max(Math.max(a.z, b.z), Math.max(c.z, d.z))).inflate(padding);
        }

        /** Clip the swept trapezoid in (travel, blade-offset) coordinates against six box planes.
         * No ray sampling: a thin target between tick positions cannot fall between samples. */
        public Contact contact(AABB original) {
            AABB box = original.inflate(padding);
            Vec3 delta = to.subtract(from);
            double[] a = {0, -halfBefore, 1, -halfAfter, 1, halfAfter, 0, halfBefore};
            int count = 4;
            double[] limits = {box.maxX - from.x, from.x - box.minX,
                    box.maxY - from.y, from.y - box.minY, box.maxZ - from.z, from.z - box.minZ};
            double[] travel = {delta.x, -delta.x, delta.y, -delta.y, delta.z, -delta.z};
            double[] lateral = {blade.x, -blade.x, blade.y, -blade.y, blade.z, -blade.z};
            for (int plane = 0; plane < 6 && count > 0; plane++) {
                // Clipping a convex polygon by a plane adds at most one vertex.
                double[] next = new double[(count + 1) * 2];
                int n = 0;
                for (int i = 0, j = count - 1; i < count; j = i++) {
                    double x0 = a[j * 2], y0 = a[j * 2 + 1], x1 = a[i * 2], y1 = a[i * 2 + 1];
                    double v0 = travel[plane] * x0 + lateral[plane] * y0 - limits[plane];
                    double v1 = travel[plane] * x1 + lateral[plane] * y1 - limits[plane];
                    boolean inside0 = v0 <= 0, inside1 = v1 <= 0;
                    if (inside0 != inside1) {
                        double t = v0 / (v0 - v1);
                        next[n++] = x0 + (x1 - x0) * t;
                        next[n++] = y0 + (y1 - y0) * t;
                    }
                    if (inside1) { next[n++] = x1; next[n++] = y1; }
                }
                count = n / 2;
                a = next;
            }
            if (count == 0) return null;
            int first = 0;
            for (int i = 1; i < count; i++) if (a[i * 2] < a[first * 2]) first = i;
            double t = Math.clamp(a[first * 2], 0, 1);
            return new Contact(t, from.add(delta.scale(t)).add(blade.scale(a[first * 2 + 1])));
        }
    }
}
