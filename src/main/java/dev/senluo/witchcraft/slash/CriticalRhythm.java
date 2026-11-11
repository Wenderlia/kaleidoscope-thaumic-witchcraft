package dev.senluo.witchcraft.slash;

/** Bonus at the last aligned cast; reads use world time without advancing the cadence. */
public record CriticalRhythm(double bonus, long lastMatch, long lastCast, long period) {
    private static final double MATCH_GAIN = .01;
    private static final double MISS_LOSS = .02;
    private static final long GRACE = 60, DECAY = 400;
    public static final CriticalRhythm EMPTY = new CriticalRhythm(0, 0, -1, -1);

    public CriticalRhythm {
        if (!Double.isFinite(bonus) || bonus < 0 || bonus > 1 || lastMatch < 0 || lastCast < -1 || period < -1)
            throw new IllegalArgumentException("Invalid critical rhythm state");
    }

    private double fraction(long tick) {
        if (tick < lastMatch || tick < lastCast) return 0;
        long elapsed = tick - lastMatch;
        if (elapsed <= GRACE) return 1;
        return Math.max(0, 1 - (elapsed - GRACE) / (double)DECAY);
    }

    public double chance(double base, long tick) {
        return Math.min(1, base + bonus * fraction(tick));
    }

    /** Only a paid cast on beat starts a fresh grace period, including at the cap. */
    public CriticalRhythm committed(long tick, double base) {
        if (tick < lastCast || tick < lastMatch)
            return new CriticalRhythm(0, 0, tick, -1);
        double fraction = fraction(tick);
        double current = Math.min(1 - base, bonus * fraction);
        if (lastCast < 0) return new CriticalRhythm(bonus, lastMatch, tick, -1);
        long interval = tick - lastCast;
        if (period <= 0) return new CriticalRhythm(bonus, lastMatch, tick, interval > 0 ? interval : -1);
        boolean aligned = interval > 0 && Math.abs((double)interval - period) <= Math.max(2, period * .25);
        if (aligned) return new CriticalRhythm(Math.min(1 - base, current + MATCH_GAIN), tick, tick, period);
        // Reduce the remaining bonus on the same decay timeline: a miss cannot buy more time.
        double remaining = Math.max(0, current - MISS_LOSS);
        return new CriticalRhythm(remaining > 0 ? remaining / fraction : 0, lastMatch, tick,
                remaining == 0 && interval > 0 ? interval : period);
    }

    public CriticalRhythm reconnect(long tick) {
        return bonus * fraction(tick) > 0 ? new CriticalRhythm(bonus, lastMatch, -1, -1) : EMPTY;
    }
}
