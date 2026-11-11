package dev.senluo.witchcraft.motion;

import net.minecraft.world.phys.Vec3;

/** Immutable movement quote. Only a committed prefix may become a range/lifetime receipt. */
public record TravelStep(Vec3 from, Vec3 direction, double distance, double work, double gap, double width) {
    public TravelStep {
        if (!MotionPath.finite(from) || !MotionPath.finite(direction) || Math.abs(direction.lengthSqr() - 1) > 1.0E-6
                || !Double.isFinite(distance) || !Double.isFinite(work) || distance < 0 || work < distance - 1.0E-7
                || !(width >= 0) || !Double.isFinite(width)
                || width > 0 && (!(gap >= 0) || !Double.isFinite(gap)))
            throw new IllegalArgumentException("Invalid travel quote");
    }

    public static TravelStep quote(Vec3 from, Vec3 direction, double budget, double maximum, double gap, double width) {
        if (!Double.isFinite(budget) || budget < 0 || !(maximum > 0) || !Double.isFinite(maximum))
            throw new IllegalArgumentException("Invalid travel budget");
        if (!Double.isFinite(gap)) {
            double length = Math.min(budget, maximum);
            return new TravelStep(from, direction, length, length, 0, 0);
        }
        double mapped = ZenoMetric.advance(gap, budget, width);
        if (mapped < 1.0E-8) return new TravelStep(from, direction, 0, budget, gap, width);
        double cap = Math.min(maximum, ZenoMetric.nextBoundary(gap, width));
        double distance = Math.min(mapped, cap);
        boolean precisionStop = gap - distance <= ZenoMetric.PRECISION_GAP * 1.001;
        double spent = distance < mapped || precisionStop ? Math.min(budget, ZenoMetric.measure(gap, distance, width)) : budget;
        return new TravelStep(from, direction, distance, spent, gap, width);
    }

    public Vec3 movement() { return direction.scale(distance); }
    public Vec3 to() { return from.add(movement()); }
    public boolean stretched() { return width > 0 && work > distance + 1.0E-9; }

    public double spentAt(double fraction) {
        if (!Double.isFinite(fraction) || fraction < 0 || fraction > 1)
            throw new IllegalArgumentException("Invalid travel prefix");
        if (fraction == 1) return work;
        if (fraction == 0) return 0;
        if (distance <= 1.0E-12) return work * fraction;
        return width == 0 ? distance * fraction
                : Math.min(work, ZenoMetric.measure(gap, distance * fraction, width));
    }

    public Receipt prefix(double fraction) {
        return new Receipt(from.add(direction.scale(distance * fraction)), distance * fraction, spentAt(fraction), fraction == 1);
    }

    public record Receipt(Vec3 position, double distance, double work, boolean complete) {}
}
