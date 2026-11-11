package dev.senluo.witchcraft.motion;

/** Directional length in a fixed band: each halving of the gap doubles the distance scale. */
public final class ZenoMetric {
    public static final double PRECISION_GAP = 1.0E-6;
    private ZenoMetric() {}

    public static double advance(double gap, double work, double width) {
        if (!(work > 0) || !(gap > PRECISION_GAP) || !(width > 0)
                || !Double.isFinite(gap) || !Double.isFinite(work) || !Double.isFinite(width)) return 0;
        double free = Math.max(0, gap - width);
        if (work <= free) return work;
        double inside = Math.min(gap, width);
        double coordinate = coordinate(inside, width) + work - free;
        double band = width * .5;
        double layer = Math.floor(coordinate / band);
        // Below the positional precision floor work still advances, never the boundary itself.
        double remaining = layer > 1022 ? 0 : Math.scalb(width - (coordinate - layer * band), -(int) layer);
        return Math.min(work, free + Math.max(0, inside - Math.max(PRECISION_GAP, remaining)));
    }

    /** Intrinsic length of a real prefix. Callers retain the original quote at the precision floor. */
    public static double measure(double gap, double distance, double width) {
        if (!(distance > 0)) return 0;
        double free = Math.max(0, gap - width);
        if (distance <= free) return distance;
        double inside = Math.min(gap, width), remaining = gap - distance;
        if (!(remaining > 0)) return Double.POSITIVE_INFINITY;
        return free + Math.max(0, coordinate(remaining, width) - coordinate(inside, width));
    }

    /** Split geometry only at the next change of scale; no iteration over an infinite hierarchy. */
    public static double nextBoundary(double gap, double width) {
        if (gap > width) return gap - width * .5; // The free region and first layer both have scale one.
        if (gap <= PRECISION_GAP) return Double.POSITIVE_INFINITY;
        int n = layer(gap, width);
        double distance = gap - Math.scalb(width, -n - 1);
        return distance > 1.0E-10 ? distance : Double.POSITIVE_INFINITY;
    }

    private static double coordinate(double gap, double width) {
        int n = layer(gap, width);
        return n * (width * .5) + width - Math.scalb(gap, n);
    }

    private static int layer(double gap, double width) {
        int n = Math.max(0, Math.getExponent(width) - Math.getExponent(gap));
        double upper = Math.scalb(width, -n);
        if (gap > upper && n > 0) n--;
        else if (gap <= upper * .5) n++;
        return n;
    }
}
