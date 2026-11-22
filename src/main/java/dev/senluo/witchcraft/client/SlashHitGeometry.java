package dev.senluo.witchcraft.client;

import net.minecraft.world.phys.Vec3;

/** Positions are relative to one committed world contact. The same geometry can be sampled offline. */
interface SlashHitGeometry {
    void draw(double phase, Vec3 eye, Vertex out);
    int ticks();
    double radius();

    @FunctionalInterface
    interface Vertex {
        void put(Vec3 offset, float u, float v, int rgb, float alpha);
    }

    record Frame(Vec3 cut, Vec3 across, Vec3 travel) {
        static Frame of(Vec3 tangent, Vec3 travel) {
            Vec3 cut = unit(tangent, new Vec3(1, 0, 0));
            Vec3 guide = Math.abs(cut.z) < .8 ? new Vec3(0, 0, 1) : new Vec3(0, 1, 0);
            Vec3 depth = unit(travel.subtract(cut.scale(travel.dot(cut))),
                    guide.subtract(cut.scale(guide.dot(cut))).normalize());
            return new Frame(cut, cut.cross(depth), depth);
        }

        Vec3 position(double x, double y, double z) {
            return cut.scale(x).add(across.scale(y)).add(travel.scale(z));
        }
    }

    static Vec3 unit(Vec3 v, Vec3 fallback) {
        double length = v.lengthSqr();
        return Double.isFinite(length) && length > 1e-10 ? v.scale(1 / Math.sqrt(length)) : fallback;
    }

    static double ramp(double t) {
        t = Math.clamp(t, 0, 1);
        return t * t * (3 - 2 * t);
    }
}
