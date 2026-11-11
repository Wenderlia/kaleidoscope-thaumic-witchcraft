package dev.senluo.witchcraft.slash.drawing;

import java.util.*;
import net.minecraft.world.phys.Vec3;

/** Bounded drawing arithmetic shared by prediction and authority; no packets, clocks or world queries. */
public final class StrokeTrace {
    public static final int MAX_TICKS = 320, MAX_SEGMENTS = 64;
    public static final double MAX_MULTIPLIER = 32;
    private static final double STEP = .04, RETRACE_WIDTH = .06;
    private final List<Vec3> raw = new ArrayList<>();
    private List<Vec3> fitted = List.of(StrokeSphere.START);
    private int ticks;
    private double uniqueLength;

    public StrokeTrace() { raw.add(StrokeSphere.START); }

    public void sample(Vec3 point) { sample(point, false); }
    /** Keep the actual release endpoint without granting another simulated tick or snapping the loop shut. */
    public void finish(Vec3 point) { sample(point, true); }

    private void sample(Vec3 point, boolean finishing) {
        if (ticks >= MAX_TICKS) return;
        if (!finishing) ticks++;
        if (point == null || !Double.isFinite(point.lengthSqr())
                || Math.abs(point.length() - StrokeSphere.RADIUS) > .001) {
            return;
        }
        Vec3 previous = raw.getLast();
        if (previous.distanceToSqr(point) < (finishing ? 1.0E-8 : STEP * STEP)) return;
        double oldLength = uniqueLength;
        double fresh = freshLength(previous, point);
        if (fresh > STEP * .5) {
            uniqueLength += fresh;
        }
        raw.add(point);
        // Simplification is cached on accepted samples, never repeated per rendered frame.
        double tolerance = .012; List<Vec3> candidate = fitted;
        for (int attempt = 0; attempt < 16; attempt++) {
            candidate = simplify(raw, tolerance);
            if (candidate.size() <= MAX_SEGMENTS + 1) break;
            tolerance *= 1.5;
        }
        if (candidate.size() > MAX_SEGMENTS + 1) {
            raw.removeLast(); uniqueLength = oldLength;
        } else fitted = candidate;
    }

    public List<Vec3> raw() { return Collections.unmodifiableList(raw); }
    public List<Vec3> fitted() { return fitted; }
    /** Re-express a local preview after the authoritative frame arrives; preserve its world directions and clock. */
    public void rebase(StrokeSphere from, StrokeSphere to) {
        raw.replaceAll(point -> to.project(from.offset(point)));
        fitted = fitted.stream().map(point -> to.project(from.offset(point))).toList();
    }
    public int ticks() { return Math.min(ticks, MAX_TICKS); }
    public double uniqueLength() { return uniqueLength; }
    public double multiplier(double normalLength) {
        if (!Double.isFinite(normalLength) || normalLength <= 0 || fitted.size() < 2) return 0;
        // Only the distance represented by the fitted blade can earn power; discarded noise cannot.
        // Holding the drawing action accrues time even while the hand is still; length remains deduplicated.
        return Math.min(MAX_MULTIPLIER, Math.min(uniqueLength, arcLength(fitted)) / normalLength * ticks() / 20.0);
    }
    public static double arcLength(List<Vec3> points) {
        double sum = 0;
        for (int i = 1; i < points.size(); i++) sum += StrokeSphere.arc(points.get(i - 1), points.get(i));
        return sum;
    }
    public static double length(List<Vec3> points) {
        double sum = 0;
        for (int i = 1; i < points.size(); i++) sum += points.get(i - 1).distanceTo(points.get(i));
        return sum;
    }

    private double freshLength(Vec3 from, Vec3 to) {
        Vec3 delta = to.subtract(from);
        double length = delta.length();
        Vec3 axis = delta.scale(1 / length);
        List<double[]> covered = new ArrayList<>();
        for (int i = 1; i < raw.size(); i++) {
            Vec3 a = raw.get(i - 1), b = raw.get(i), old = b.subtract(a);
            if (Math.abs(old.normalize().dot(axis)) < .985) continue;
            // Collinear strips cover intervals; crossings at different angles remain distinct strokes.
            double start = a.subtract(from).dot(axis), end = b.subtract(from).dot(axis);
            if (a.subtract(from).subtract(axis.scale(start)).length() > RETRACE_WIDTH
                    || b.subtract(from).subtract(axis.scale(end)).length() > RETRACE_WIDTH) continue;
            double lo = Math.max(0, Math.min(start, end)), hi = Math.min(length, Math.max(start, end));
            if (hi > lo) covered.add(new double[]{lo, hi});
        }
        covered.sort(Comparator.comparingDouble(interval -> interval[0]));
        double amount = 0, end = 0;
        for (double[] interval : covered) {
            amount += Math.max(0, interval[1] - Math.max(end, interval[0]));
            end = Math.max(end, interval[1]);
        }
        return Math.max(0, length - amount) / length * StrokeSphere.arc(from, to);
    }

    private static List<Vec3> simplify(List<Vec3> points, double tolerance) {
        boolean[] keep = new boolean[points.size()]; keep[0] = keep[keep.length - 1] = true;
        simplify(points, 0, points.size() - 1, tolerance * tolerance, keep);
        List<Vec3> result = new ArrayList<>();
        for (int i = 0; i < points.size(); i++) if (keep[i]) {
            Vec3 p = points.get(i);
            if (result.isEmpty() || p.distanceToSqr(result.getLast()) > 1.0E-8) result.add(p);
        }
        return List.copyOf(result);
    }
    private static void simplify(List<Vec3> points, int first, int last, double tolerance, boolean[] keep) {
        if (last <= first + 1) return;
        Vec3 a = points.get(first), delta = points.get(last).subtract(a);
        double distance = tolerance; int selected = -1;
        for (int i = first + 1; i < last; i++) {
            Vec3 p = points.get(i);
            double t = delta.lengthSqr() < 1.0E-10 ? 0 : Math.clamp(p.subtract(a).dot(delta) / delta.lengthSqr(), 0, 1);
            double error = p.distanceToSqr(a.add(delta.scale(t)));
            if (error > distance) { distance = error; selected = i; }
        }
        // A long spherical arc must not collapse into a chord through the caster, even at poles or yaw wrap.
        if (selected < 0 && a.normalize().dot(points.get(last).normalize()) < .9238795) selected = (first + last) / 2;
        if (selected >= 0) {
            keep[selected] = true;
            simplify(points, first, selected, tolerance, keep); simplify(points, selected, last, tolerance, keep);
        }
    }
}
