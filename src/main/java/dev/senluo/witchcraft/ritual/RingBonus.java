package dev.senluo.witchcraft.ritual;

import java.util.function.DoubleBinaryOperator;

/** Evaluate the observed rings in size order, or return the independent complete-set value. */
public final class RingBonus {
    private final double small, medium, large, full;
    private final DoubleBinaryOperator combine;

    private RingBonus(double small, double medium, double large, double full, DoubleBinaryOperator combine) {
        this.small = small;
        this.medium = medium;
        this.large = large;
        this.full = full;
        this.combine = combine;
    }

    public static RingBonus additive(double small, double medium, double large, double full) {
        return new RingBonus(small, medium, large, full, Double::sum);
    }

    public static RingBonus multiplicative(double small, double medium, double large, double full) {
        return new RingBonus(small, medium, large, full, (value, factor) -> value * factor);
    }

    public double resolve(double base, int completeMask) {
        if (completeMask == 7) return full;
        if ((completeMask & 1) != 0) base = combine.applyAsDouble(base, small);
        if ((completeMask & 2) != 0) base = combine.applyAsDouble(base, medium);
        if ((completeMask & 4) != 0) base = combine.applyAsDouble(base, large);
        return base;
    }
}
