package dev.senluo.witchcraft.motion;

/** Cubic Bezier velocity over simulation time, integrated exactly into travel budgets. */
public record BezierSpeed(double start, double first, double second, double end, double duration) {
    public BezierSpeed {
        if (!Double.isFinite(start) || !Double.isFinite(first) || !Double.isFinite(second)
                || !Double.isFinite(end) || !Double.isFinite(duration)
                || start < 0 || first < 0 || second < 0 || end < 0 || duration <= 0)
            throw new IllegalArgumentException("Invalid Bezier speed");
    }

    public double at(double time) {
        double t = Math.clamp(time / duration, 0, 1), u = 1 - t;
        return u * u * u * start + 3 * u * u * t * first + 3 * u * t * t * second + t * t * t * end;
    }

    /** Velocities are distance per simulation tick; time is not client frame time. */
    public double travel(double from, double to) {
        if (!Double.isFinite(from) || !Double.isFinite(to) || from < 0 || to < from)
            throw new IllegalArgumentException("Invalid motion interval");
        return Math.max(0, integral(to) - integral(from));
    }

    private double integral(double time) {
        double t = Math.min(time / duration, 1);
        double a = -start + 3 * first - 3 * second + end;
        double b = 3 * start - 6 * first + 3 * second;
        double c = -3 * start + 3 * first;
        return duration * t * (start + t * (c / 2 + t * (b / 3 + t * a / 4)))
                + Math.max(0, time - duration) * end;
    }
}
