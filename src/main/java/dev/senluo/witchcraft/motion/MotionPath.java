package dev.senluo.witchcraft.motion;

import java.util.Arrays;
import net.minecraft.world.phys.Vec3;

/** Immutable arc-length path. Geometry, arrival and continuation have no world or clock dependency. */
public final class MotionPath {
    public static final int MAX_SEGMENTS = 256;
    public static final double MAX_CONTROL_DISTANCE = 128;
    private static final double MAX_ERROR = 1.0 / 128;
    private final Vec3 first, second, arrival, tail;
    private final Vec3[] points;
    private final double[] lengths;

    private MotionPath(Vec3 direction) {
        if (!finite(direction) || direction.lengthSqr() < 1.0E-12)
            throw new IllegalArgumentException("Invalid path direction");
        first = second = arrival = Vec3.ZERO;
        tail = direction.scale(1 / direction.length()); points = new Vec3[]{Vec3.ZERO}; lengths = new double[]{0};
    }

    private MotionPath(Vec3 first, Vec3 second, Vec3 arrival) {
        for (Vec3 v : new Vec3[]{first, second, arrival})
            if (!finite(v) || v.lengthSqr() > MAX_CONTROL_DISTANCE * MAX_CONTROL_DISTANCE)
                throw new IllegalArgumentException("Invalid Bezier control point");
        if (arrival.lengthSqr() < 1.0E-8 || arrival.distanceToSqr(second) < 1.0E-8)
            throw new IllegalArgumentException("Degenerate arrival tangent");
        this.first = first; this.second = second; this.arrival = arrival;
        tail = arrival.subtract(second).normalize();
        // Linear interpolation error <= max|B''| * dt^2 / 8. The baked polyline is also
        // the authoritative collision route, so consumers cannot skip its corner vertices.
        double curvature = 6 * Math.max(second.subtract(first.scale(2)).length(),
                arrival.subtract(second.scale(2)).add(first).length());
        int count = Math.max(8, (int) Math.ceil(Math.sqrt(curvature / (8 * MAX_ERROR))));
        if (count > MAX_SEGMENTS) throw new IllegalArgumentException("Bezier subdivision limit exceeded");
        points = new Vec3[count + 1]; lengths = new double[count + 1];
        points[0] = Vec3.ZERO;
        for (int i = 1; i <= count; i++) {
            double t = (double) i / count, u = 1 - t;
            points[i] = first.scale(3 * u * u * t).add(second.scale(3 * u * t * t)).add(arrival.scale(t * t * t));
            lengths[i] = lengths[i - 1] + points[i].distanceTo(points[i - 1]);
            if (!(lengths[i] > lengths[i - 1])) throw new IllegalArgumentException("Degenerate path segment");
        }
    }

    public static MotionPath line(Vec3 direction) { return new MotionPath(direction); }
    public static MotionPath bezier(Vec3 first, Vec3 second, Vec3 arrival) { return new MotionPath(first, second, arrival); }
    public boolean curved() { return points.length > 1; }
    public Vec3 first() { return first; }
    public Vec3 second() { return second; }
    public Vec3 arrival() { return arrival; }
    public Vec3 tail() { return tail; }
    public double arrivalDistance() { return lengths[lengths.length - 1]; }

    public Vec3 at(double distance) {
        int next = next(distance);
        if (next == points.length) return arrival.add(tail.scale(Math.max(0, distance - arrivalDistance())));
        double fraction = (distance - lengths[next - 1]) / (lengths[next] - lengths[next - 1]);
        return points[next - 1].lerp(points[next], Math.clamp(fraction, 0, 1));
    }

    public Vec3 direction(double distance) {
        int next = next(distance);
        if (next == points.length) return tail;
        Vec3 piece = points[next].subtract(points[next - 1]);
        return piece.scale(1 / piece.length());
    }

    /** End of one straight piece only; callers must process every piece before arrival. */
    public double nextDistance(double distance, double budget) {
        int next = next(distance);
        return Math.min(distance + budget, next == lengths.length ? Double.POSITIVE_INFINITY : lengths[next]);
    }

    private int next(double distance) {
        if (!Double.isFinite(distance) || distance < 0) throw new IllegalArgumentException("Invalid path progress");
        int found = Arrays.binarySearch(lengths, distance);
        return found >= 0 ? found + 1 : -found - 1;
    }

    public static boolean finite(Vec3 vector) {
        return Double.isFinite(vector.x) && Double.isFinite(vector.y) && Double.isFinite(vector.z);
    }
}
