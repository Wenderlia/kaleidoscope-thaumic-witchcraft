package dev.senluo.witchcraft.motion;

/** Bounded recent look gesture. Inputs are orientation degrees and monotonic simulation ticks.
 * No world, player, rendering or networking state belongs to this estimator. */
public final class ViewTurn {
    public static final int WINDOW = 6;
    private static final int HOLD_TICKS = 6, PAUSE_TICKS = 2;
    private static final double DEAD_STEP = .35, MIN_TURN = 12, MIN_COHERENCE = .85;
    private final long[] ticks = new long[WINDOW + 1];
    private final double[] yaw = new double[WINDOW + 1], pitch = new double[WINDOW + 1];
    private int size;
    private Gesture recent = Gesture.NONE;
    private long recentTick = Long.MIN_VALUE;

    /** Camera-local motion: positive horizontal is right, positive vertical is up. */
    public record Gesture(double horizontal, double vertical, double strength) {
        public static final Gesture NONE = new Gesture(0, 0, 0);
    }

    public void sample(long tick, double yawDegrees, double pitchDegrees) {
        if (!Double.isFinite(yawDegrees) || !Double.isFinite(pitchDegrees) || Math.abs(pitchDegrees) > 90) {
            reset();
            return;
        }
        if (size > 0 && (tick < ticks[size - 1] || tick - ticks[size - 1] > WINDOW
                || Math.hypot(wrap(yawDegrees - yaw[size - 1]), pitchDegrees - pitch[size - 1]) > 120)) reset();
        if (size > 0 && tick == ticks[size - 1]) {
            yaw[size - 1] = yawDegrees;
            pitch[size - 1] = pitchDegrees;
            remember(tick);
            return;
        }
        if (size == ticks.length) {
            System.arraycopy(ticks, 1, ticks, 0, size - 1);
            System.arraycopy(yaw, 1, yaw, 0, size - 1);
            System.arraycopy(pitch, 1, pitch, 0, size - 1);
            size--;
        }
        ticks[size] = tick;
        yaw[size] = yawDegrees;
        pitch[size++] = pitchDegrees;
        remember(tick);
    }

    public Gesture gesture(long now) {
        return recentTick != Long.MIN_VALUE && now >= recentTick && now - recentTick <= HOLD_TICKS
                ? recent : Gesture.NONE;
    }

    private void reset() { size = 0; recent = Gesture.NONE; recentTick = Long.MIN_VALUE; }

    private void remember(long now) {
        double horizontal = 0, vertical = 0, travelled = 0;
        long latest = Long.MIN_VALUE, previous = now;
        // Walk backwards to the latest coherent stroke, not a blend of unrelated recent turns.
        for (int i = size - 1; i >= 1; i--) {
            if (now - ticks[i - 1] > WINDOW) break;
            // Minecraft yaw increases to camera-right, while pitch increases downward.
            // Only pitch needs inversion. Yaw contributes less near the poles.
            double dx = wrap(yaw[i] - yaw[i - 1]) * Math.cos(Math.toRadians((pitch[i] + pitch[i - 1]) * .5));
            double dy = pitch[i - 1] - pitch[i];
            double length = Math.hypot(dx, dy);
            if (length < DEAD_STEP) continue;
            if (previous - ticks[i] > PAUSE_TICKS) break;
            if (latest == Long.MIN_VALUE) latest = ticks[i];
            double netBefore = Math.hypot(horizontal, vertical);
            if (netBefore > 0 && (dx * horizontal + dy * vertical) / (length * netBefore) < .5) break;
            horizontal += dx;
            vertical += dy;
            travelled += length;
            previous = ticks[i];
        }
        double net = Math.hypot(horizontal, vertical);
        if (net < MIN_TURN || travelled == 0 || net / travelled < MIN_COHERENCE) return;
        recent = new Gesture(horizontal / net, vertical / net, 1);
        // Stationary samples do not renew the latch; small jitter cannot prolong it indefinitely.
        recentTick = latest;
    }

    private static double wrap(double degrees) { return degrees - 360 * Math.floor((degrees + 180) / 360); }
}
