package dev.senluo.witchcraft.accessory;

import java.util.List;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Pure spatial constraints; equipment, clocks, packets and native collision stay outside this layer. */
public final class MotionConstraints {
    public static final double EPSILON = 1.0E-6;
    /** Box plus an isotropic margin. A point box is a sphere; no circumscribed sphere is needed. */
    public record Boundary(AABB box, double padding, double width, int sourceId) {
        public Boundary(AABB box, double padding, double width) {
            this(box, padding, width, -1);
        }
        public Boundary(Vec3 center, double inner) {
            this(new AABB(center, center), inner, inner);
        }
        public Boundary(Vec3 center, double inner, int sourceId) {
            this(new AABB(center, center), inner, inner, sourceId);
        }

        Vec3 nearest(Vec3 point) {
            return new Vec3(Math.clamp(point.x, box.minX, box.maxX),
                    Math.clamp(point.y, box.minY, box.maxY), Math.clamp(point.z, box.minZ, box.maxZ));
        }

        double entry(Vec3 start, Vec3 movement) {
            return roundedBoxEntry(start, movement, box, padding + EPSILON);
        }
    }

    /** Optional diagnostic output for a real move; the normal quote remains allocation-free. */
    public static final class Contact {
        private Boundary boundary;
        public Boundary boundary() { return boundary; }
    }

    /**
     * Spend virtual travel against a stretched path. Gap and width are world lengths.
     * Outside the mapping band travel is unchanged; inside, equal virtual travel halves
     * the remaining gap repeatedly. No substeps or unbounded distance accumulator.
     */
    public static double mapTravel(double gap, double travel, double width) {
        return dev.senluo.witchcraft.motion.ZenoMetric.advance(gap, travel, width);
    }

    /** Map a straight requested path without steering, teleporting or changing its incoming orientation. */
    public static Vec3 approachByTravel(Vec3 start, Vec3 limit, double travel, double width) {
        Vec3 path = limit.subtract(start);
        double length = path.length();
        if (length <= EPSILON) return start;
        return start.add(path.scale(mapTravel(length, travel, width) / length));
    }

    public static Vec3 apply(Vec3 start, Vec3 requested, List<Boundary> boundaries, boolean approachSlowdown) {
        return apply(start, requested, boundaries, approachSlowdown, null);
    }

    public static Vec3 apply(Vec3 start, Vec3 requested, List<Boundary> boundaries,
            boolean approachSlowdown, Contact contact) {
        if (contact != null) contact.boundary = null;
        Vec3 best = requested;
        double shortest = requested.lengthSqr();
        for (Boundary boundary : boundaries) {
            Vec3 toward = boundary.nearest(start).subtract(start);
            double distance = toward.length();
            if (distance <= boundary.padding + EPSILON) {
                if (contact != null) contact.boundary = boundary;
                return Vec3.ZERO;
            }
            Vec3 candidate = requested;
            if (approachSlowdown) {
                Vec3 direction = toward.scale(1 / distance);
                double approach = requested.dot(direction);
                if (approach > 0)
                    candidate = requested.subtract(direction.scale(approach
                            - mapTravel(distance - boundary.padding, approach, boundary.width)));
            }
            double entry = boundary.entry(start, candidate);
            if (Double.isFinite(entry)) candidate = candidate.scale(beforeContact(entry, candidate));
            if (candidate.lengthSqr() < shortest) {
                best = candidate;
                shortest = candidate.lengthSqr();
                if (contact != null) contact.boundary = boundary;
            }
        }
        double allowed = 1;
        for (Boundary boundary : boundaries) {
            double entry = boundary.entry(start, best);
            if (Double.isFinite(entry)) {
                double limit = beforeContact(entry, best);
                if (limit < allowed) {
                    allowed = limit;
                    if (contact != null) contact.boundary = boundary;
                }
            }
        }
        return allowed < 1 ? best.scale(allowed) : best;
    }

    private static double beforeContact(double entry, Vec3 movement) {
        double length = movement.length();
        return length > EPSILON ? Math.max(0, entry - EPSILON / length) : 0;
    }

    /** First contact of a moving, linearly growing segment with a sphere. Its nearest feature
     * changes at most twice (two endpoints and the interior), giving at most three quadratic pieces. */
    public static double segmentEntry(Vec3 from, Vec3 to, Vec3 axis, double halfBefore, double halfAfter,
                                      Vec3 center, double radius) {
        Vec3 delta = to.subtract(from);
        double projection = center.subtract(from).dot(axis), along = delta.dot(axis), growth = halfAfter - halfBefore;
        double[] cuts = {0, 1, 1, 1};
        int count = 2;
        double firstDenominator = along + growth, secondDenominator = along - growth;
        if (firstDenominator != 0) {
            double t = (projection - halfBefore) / firstDenominator;
            if (t > 0 && t < 1) cuts[count++] = t;
        }
        if (secondDenominator != 0) {
            double t = (projection + halfBefore) / secondDenominator;
            if (t > 0 && t < 1) cuts[count++] = t;
        }
        java.util.Arrays.sort(cuts, 0, count);
        for (int i = 1; i < count; i++) {
            double a = cuts[i - 1], b = cuts[i], middle = (a + b) * .5;
            if (b <= a) continue;
            double p = projection - along * middle, half = halfBefore + growth * middle;
            double offset = p > half ? halfBefore : p < -half ? -halfBefore : projection;
            double velocity = p > half ? growth : p < -half ? -growth : -along;
            Vec3 start = from.add(axis.scale(offset)), motion = delta.add(axis.scale(velocity));
            double hit = entry(start.add(motion.scale(a)), motion.scale(b - a), center, radius);
            if (Double.isFinite(hit)) return a + (b - a) * hit;
        }
        return Double.POSITIVE_INFINITY;
    }

    /**
     * First contact with distance(point, box) <= radius. The nearest box feature only changes
     * when crossing one of six face planes, so at most seven quadratic pieces are needed.
     * This is a bounded segment calculation, not ray marching or a sampled collision test.
     */
    private static double roundedBoxEntry(Vec3 start, Vec3 movement, AABB box, double radius) {
        if (box.minX == box.maxX && box.minY == box.maxY && box.minZ == box.maxZ)
            return entry(start, movement, new Vec3(box.minX, box.minY, box.minZ), radius);
        if (box.distanceToSqr(start) <= radius * radius) return 0;
        // Cheap swept-box rejection. Local offsets also avoid squaring absolute world coordinates.
        double x0 = box.minX - start.x, x1 = box.maxX - start.x;
        double y0 = box.minY - start.y, y1 = box.maxY - start.y;
        double z0 = box.minZ - start.z, z1 = box.maxZ - start.z;
        if (separate(movement.x, x0 - radius, x1 + radius)
                || separate(movement.y, y0 - radius, y1 + radius)
                || separate(movement.z, z0 - radius, z1 + radius)) return Double.POSITIVE_INFINITY;
        double length = movement.length();
        if (length < 1.0E-8) return Double.POSITIVE_INFINITY;
        double tolerance = EPSILON / length, from = 0;
        for (int piece = 0; piece < 7; piece++) {
            double to = Math.min(nextPlane(movement.x, x0, x1, from),
                    Math.min(nextPlane(movement.y, y0, y1, from), nextPlane(movement.z, z0, z1, from)));
            double middle = (from + to) * .5;
            double ox = featureOffset(movement.x * middle, x0, x1);
            double oy = featureOffset(movement.y * middle, y0, y1);
            double oz = featureOffset(movement.z * middle, z0, z1);
            double vx = outside(movement.x * middle, x0, x1) ? movement.x : 0;
            double vy = outside(movement.y * middle, y0, y1) ? movement.y : 0;
            double vz = outside(movement.z * middle, z0, z1) ? movement.z : 0;
            double a = vx * vx + vy * vy + vz * vz;
            double b = ox * vx + oy * vy + oz * vz;
            double c = ox * ox + oy * oy + oz * oz - radius * radius;
            if (Math.fma(Math.fma(a, from, 2 * b), from, c) <= 0) return from;
            double discriminant = Math.fma(b, b, -a * c);
            if (a > 0 && discriminant >= 0) {
                double q = -b - Math.copySign(Math.sqrt(discriminant), b);
                double contact = q == 0 ? -b / a : Math.min(q / a, c / q);
                if (contact >= from - tolerance && contact <= to + tolerance)
                    return Math.clamp(contact, from, to);
            }
            if (to >= 1) break;
            from = to;
        }
        return Double.POSITIVE_INFINITY;
    }

    private static boolean separate(double movement, double min, double max) {
        return Math.max(0, movement) < min || Math.min(0, movement) > max;
    }

    private static double nextPlane(double movement, double min, double max, double after) {
        if (movement == 0) return 1;
        double first = min / movement, second = max / movement, next = 1;
        if (first > after && first < next) next = first;
        if (second > after && second < next) next = second;
        return next;
    }

    private static double featureOffset(double point, double min, double max) {
        return point < min ? -min : point > max ? -max : 0;
    }

    private static boolean outside(double point, double min, double max) {
        return point < min || point > max;
    }

    /** First intersection along the entire finite segment, including steps longer than the field. */
    public static double entry(Vec3 start, Vec3 movement, Vec3 center, double radius) {
        Vec3 offset = start.subtract(center);
        double c = offset.lengthSqr() - radius * radius;
        if (c <= EPSILON) return 0;
        double a = movement.lengthSqr(), b = offset.dot(movement);
        if (a < 1.0E-16 || b >= 0) return Double.POSITIVE_INFINITY;
        double discriminant = b * b - a * c;
        if (discriminant < 0) return Double.POSITIVE_INFINITY;
        double t = (-b - Math.sqrt(discriminant)) / a;
        if (t >= 0 && t <= 1) return t;
        // World-coordinate subtraction can put an endpoint a few ulps outside its segment.
        // Bound the tolerance in blocks, not as a fraction of a fast projectile's whole step.
        double outside = t < 0 ? -t : t - 1;
        return outside * outside * a <= EPSILON * EPSILON
                ? Math.clamp(t, 0, 1) : Double.POSITIVE_INFINITY;
    }

    private MotionConstraints() {}
}
