package dev.senluo.witchcraft.combat;

import java.util.function.DoubleSupplier;

/** One authoritative random decision. Callers own payment, snapshots and the critical effect. */
public final class CriticalRoll {
    private CriticalRoll() {}
    public static boolean roll(double chance, DoubleSupplier random) {
        if (!Double.isFinite(chance) || chance < 0 || chance > 1)
            throw new IllegalArgumentException("Invalid critical chance");
        if (chance == 0) return false;
        if (chance == 1) return true;
        double value = random.getAsDouble();
        if (!Double.isFinite(value) || value < 0 || value >= 1)
            throw new IllegalArgumentException("Invalid critical random sample");
        return value < chance;
    }
}
