package dev.senluo.witchcraft.slash.drawing;

import net.minecraft.world.phys.Vec3;

/** Body-relative spherical drawing coordinates. No yaw seam, camera FOV or screen-size pricing. */
public record StrokeSphere(Vec3 forward, Vec3 right, Vec3 up) {
    public static final double RADIUS = 3;
    public static final Vec3 START = new Vec3(0, 0, RADIUS);

    public static StrokeSphere facing(Vec3 look) {
        Vec3 forward = look.normalize();
        Vec3 right = forward.cross(Math.abs(forward.y) > .98 ? new Vec3(0, 0, 1) : new Vec3(0, 1, 0)).normalize();
        return new StrokeSphere(forward, right, right.cross(forward).normalize());
    }

    public Vec3 project(Vec3 look) {
        if (!Double.isFinite(look.lengthSqr()) || look.lengthSqr() < .5) return null;
        Vec3 unit = look.normalize();
        return new Vec3(unit.dot(right), unit.dot(up), unit.dot(forward)).scale(RADIUS);
    }

    public Vec3 offset(Vec3 point) { return right.scale(point.x).add(up.scale(point.y)).add(forward.scale(point.z)); }
    public Vec3 origin(Vec3 eye) { return eye; }
    public static double arc(Vec3 a, Vec3 b) {
        return RADIUS * Math.acos(Math.clamp(a.dot(b) / (RADIUS * RADIUS), -1, 1));
    }
    public static Vec3 between(Vec3 a, Vec3 b, double t) {
        a = a.normalize(); b = b.normalize();
        double dot = Math.clamp(a.dot(b), -1, 1), angle = Math.acos(dot);
        if (angle < 1.0E-6) return a;
        Vec3 tangent = b.subtract(a.scale(dot));
        if (tangent.lengthSqr() < 1.0E-10)
            tangent = a.cross(Math.abs(a.y) > .9 ? new Vec3(1, 0, 0) : new Vec3(0, 1, 0));
        return a.scale(Math.cos(angle * t)).add(tangent.normalize().scale(Math.sin(angle * t))).normalize();
    }
}
