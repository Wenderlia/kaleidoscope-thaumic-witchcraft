package dev.senluo.witchcraft.slash;

import dev.senluo.witchcraft.motion.MotionPath;
import net.minecraft.world.phys.Vec3;

/** Immutable blade rotation, independent of trajectory and world/render scheduling. */
record SlashRotation(Vec3 axis, Vec3 initialBlade, double radiansPerTick) {
    static final double SLOW_RATE = Math.toRadians(45) / 20;

    SlashRotation {
        if (!MotionPath.finite(axis) || !MotionPath.finite(initialBlade)
                || Math.abs(axis.lengthSqr() - 1) > .0001 || Math.abs(initialBlade.lengthSqr() - 1) > .0001
                || Math.abs(axis.dot(initialBlade)) > .0001 || !Double.isFinite(radiansPerTick)
                || Math.abs(radiansPerTick) > SLOW_RATE + 1.0E-9)
            throw new IllegalArgumentException("Invalid blade rotation");
    }

    Vec3 at(double ticks) {
        double angle = radiansPerTick * ticks;
        return initialBlade.scale(Math.cos(angle)).add(axis.cross(initialBlade).scale(Math.sin(angle))).normalize();
    }

    SlashGeometry.Sweep sweep(Vec3 from, Vec3 to, double halfBefore, double halfAfter, double before, double after) {
        double halfAngle = Math.abs(radiansPerTick * (after - before)) * .5;
        // A midpoint blade plus its maximum tip displacement encloses the whole turning strip.
        // Keep one bounded collision query; do not add a scan for every visual angle.
        double padding = SlashGeometry.HALF_THICKNESS
                + 2 * Math.max(halfBefore, halfAfter) * Math.sin(halfAngle * .5);
        return new SlashGeometry.Sweep(from, to, at((before + after) * .5), halfBefore, halfAfter, padding);
    }
}
