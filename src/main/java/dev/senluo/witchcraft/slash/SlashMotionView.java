package dev.senluo.witchcraft.slash;

import net.minecraft.world.phys.Vec3;

/** Client presentation of native movement/data packets. Never predicts travel or changes hit geometry. */
final class SlashMotionView {
    static final int INTERPOLATION_TICKS = 2;
    private record Pose(Vec3 position, Vec3 blade, Vec3 forward, double distance) {
        Pose toward(Pose other, double t) {
            return new Pose(position.lerp(other.position, t), direction(blade, other.blade, t),
                    direction(forward, other.forward, t), distance + (other.distance - distance) * t);
        }
    }
    private Pose previous, displayed, target;
    private Vec3 pendingPosition;
    private int remaining;

    void position(Vec3 value) {
        pendingPosition = value;
        if (target != null) target = new Pose(value, target.blade, target.forward, target.distance);
        remaining = INTERPOLATION_TICKS;
    }

    /** Called after the whole metadata packet, not while its individual fields are being assigned. */
    void snapshot(Vec3 position, Vec3 blade, Vec3 forward, double distance) {
        if (displayed == null) previous = displayed = new Pose(position, blade, forward, distance);
        target = new Pose(pendingPosition == null ? position : pendingPosition, blade, forward, distance);
        remaining = INTERPOLATION_TICKS;
    }

    Vec3 tick(Vec3 fallback) {
        if (displayed == null) return fallback;
        previous = displayed;
        if (remaining > 0) displayed = displayed.toward(target, 1.0 / remaining--);
        return displayed.position;
    }

    Vec3 targetPosition(Vec3 fallback) { return pendingPosition == null ? fallback : pendingPosition; }
    Vec3 blade(float partial, Vec3 fallback) {
        return displayed == null ? fallback : direction(previous.blade, displayed.blade, Math.clamp(partial, 0, 1));
    }
    Vec3 forward(float partial, Vec3 fallback) {
        return displayed == null ? fallback : direction(previous.forward, displayed.forward, Math.clamp(partial, 0, 1));
    }
    double distance(float partial, double fallback) {
        return displayed == null ? fallback
                : previous.distance + (displayed.distance - previous.distance) * Math.clamp(partial, 0, 1);
    }

    private static Vec3 direction(Vec3 from, Vec3 to, double t) {
        if (t <= 0) return from;
        if (t >= 1) return to;
        double dot = Math.clamp(from.dot(to), -1, 1);
        if (dot > .9995) return from.lerp(to, t).normalize();
        Vec3 tangent = to.subtract(from.scale(dot));
        // A late packet may span opposite directions. Never normalize the zero midpoint.
        if (tangent.lengthSqr() < 1.0E-10)
            tangent = from.cross(Math.abs(from.y) > .9 ? new Vec3(1, 0, 0) : new Vec3(0, 1, 0));
        double angle = Math.acos(dot) * t;
        return from.scale(Math.cos(angle)).add(tangent.normalize().scale(Math.sin(angle))).normalize();
    }
}
