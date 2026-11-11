package dev.senluo.witchcraft.slash;

import dev.senluo.witchcraft.motion.BezierSpeed;

/** Slash policy; the shared critical decision does not know potential or visual styles. */
public final class SlashCritical {
    public static final double CHANCE = .01;
    public static final double EXPONENT = 2.5;
    public static final double CURVED_SPIN_CHANCE = .01;
    public static final double MAX_BASE = 1_000_000;
    public static final double MAX_POTENTIAL = 1_000_000_000_000_000.0;
    public static final double SPEED_SWITCH = 40;
    private SlashCritical() {}

    /** The same saved clock on both curves, including fractional switch crossings.
     * Also consumed by the offline visual preview; no separate preview speed law. */
    public static double travel(BezierSpeed initial, BezierSpeed enhanced, double from, double to) {
        if (initial == null || from >= SPEED_SWITCH) return enhanced.travel(from, to);
        if (to <= SPEED_SWITCH) return initial.travel(from, to);
        return initial.travel(from, SPEED_SWITCH) + enhanced.travel(SPEED_SWITCH, to);
    }

    public static double potential(double base) {
        if (!Double.isFinite(base) || base <= 0 || base > MAX_BASE)
            throw new IllegalArgumentException("Invalid pre-critical slash potential");
        double result = StrictMath.pow(base, EXPONENT);
        if (!Double.isFinite(result) || result <= 0 || result > MAX_POTENTIAL)
            throw new IllegalArgumentException("Unrepresentable critical slash potential");
        return result;
    }

    /** Zero base means an ordinary cast. Old saves cannot smuggle a larger unqualified attack. */
    public static boolean validSnapshot(double base, double initial) {
        if (!Double.isFinite(initial) || initial <= 0 || initial > MAX_POTENTIAL) return false;
        if (base == 0) return initial <= MAX_BASE;
        if (!Double.isFinite(base) || base <= 0 || base > MAX_BASE) return false;
        double expected = StrictMath.pow(base, EXPONENT);
        return Double.isFinite(expected) && expected > 0 && Math.abs(initial-expected) <= expected*1.0E-12;
    }
}
