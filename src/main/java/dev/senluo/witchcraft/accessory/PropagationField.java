package dev.senluo.witchcraft.accessory;

import java.util.List;
import net.minecraft.world.phys.Vec3;

/** Immutable geometry for one propagation operation. No world queries, owners, clocks or rendering. */
public final class PropagationField {
    public record Source(int id, Vec3 center, double radius) {}
    public record Hit(int sourceId, Vec3 point, Vec3 direction) {}
    public static final PropagationField EMPTY = new PropagationField(List.of());
    private final List<Source> sources;

    public PropagationField(List<Source> sources) { this.sources = List.copyOf(sources); }
    public boolean isEmpty() { return sources.isEmpty(); }

    /** Nearest boundary along one direction; overlapping domains select the strongest, never multiply. */
    public double gap(Vec3 from, Vec3 direction, double reach) {
        Vec3 ray = direction.scale(reach);
        double closest = Double.POSITIVE_INFINITY;
        for (Source source : sources)
            closest = Math.min(closest, entry(from, ray, source.center, source.radius));
        return closest * reach;
    }

    /** Finite blade/beam support. Returns +infinity for no intersection, not a movement fraction. */
    public double segmentEntry(Vec3 from, Vec3 to, Vec3 axis, double halfBefore, double halfAfter, double padding) {
        double first = Double.POSITIVE_INFINITY;
        for (Source source : sources) {
            double radius = source.radius + padding;
            double extent = Math.max(halfBefore, halfAfter);
            if (from.distanceToSqr(source.center) > Math.pow(radius + extent + from.distanceTo(to), 2)) continue;
            first = Math.min(first, MotionConstraints.segmentEntry(from, to, axis, halfBefore, halfAfter, source.center, radius));
        }
        return first;
    }

    /** Both endpoints inside a field are blocked too; zero-length contact is not an exemption. */
    public boolean blocks(Vec3 from, Vec3 to) {
        Vec3 delta = to.subtract(from);
        for (Source source : sources)
            if (Double.isFinite(entry(from, delta, source.center, source.radius))) return true;
        return false;
    }

    /** Optional presentation detail. Gameplay fast paths need only blocks(). */
    public Hit hit(Vec3 from, Vec3 to) {
        Vec3 delta = to.subtract(from);
        double first = Double.POSITIVE_INFINITY;
        Source closest = null;
        for (Source source : sources) {
            double t = entry(from, delta, source.center, source.radius);
            if (t < first) { first = t; closest = source; }
        }
        if (closest == null) return null;
        Vec3 direction = delta.normalize();
        if (direction.lengthSqr() < .5) direction = closest.center.subtract(from).normalize();
        if (direction.lengthSqr() < .5) direction = new Vec3(0, 1, 0);
        return new Hit(closest.id, from.add(delta.scale(first)), direction);
    }

    public static double entry(Vec3 from, Vec3 delta, Vec3 center, double radius) {
        if (!Double.isFinite(from.lengthSqr()) || !Double.isFinite(delta.lengthSqr())
                || !Double.isFinite(center.lengthSqr()) || !(radius > 0) || !Double.isFinite(radius))
            return Double.POSITIVE_INFINITY;
        if (Math.abs(from.x - center.x) > radius + Math.abs(delta.x)
                || Math.abs(from.y - center.y) > radius + Math.abs(delta.y)
                || Math.abs(from.z - center.z) > radius + Math.abs(delta.z)) return Double.POSITIVE_INFINITY;
        return MotionConstraints.entry(from, delta, center, radius);
    }
}
