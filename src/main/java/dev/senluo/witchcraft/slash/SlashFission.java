package dev.senluo.witchcraft.slash;

import java.math.BigDecimal;
import java.util.function.BooleanSupplier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/** Partition a committed contact into traveling fragments, without allocating an entity per blade. */
public final class SlashFission {
    private SlashFission() {}
    private record Hit(DamageSource source, long blades) {}
    private static final ThreadLocal<Hit> CURRENT = new ThreadLocal<>();

    /** An opt-in listener can read the exact logical multiplicity during this native damage call.
     * Generic native callbacks still run once per batch, never once per astronomical blade count. */
    public static long hitMultiplicity(DamageSource source) {
        Hit hit = CURRENT.get();
        return hit != null && hit.source == source ? hit.blades : 1;
    }
    public static boolean bypassesCooldown(DamageSource source) {
        Hit hit = CURRENT.get();
        return hit != null && hit.source == source;
    }
    static boolean during(DamageSource source, long blades, BooleanSupplier action) {
        Hit previous = CURRENT.get();
        CURRENT.set(new Hit(source, blades));
        try { return action.getAsBoolean(); }
        finally { if (previous == null) CURRENT.remove(); else CURRENT.set(previous); }
    }

    /** First committed contact only. The initial hit has already paid its own debit.
     * Remaining blades stay live, including those a dead target can no longer absorb. */
    static void impact(SlashEntity slash, ServerPlayer player, Entity target, Vec3 center, double debit) {
        if (!slash.fission() || slash.fragments() != null || debit <= 0 || slash.potential().remaining() <= 0) return;
        Partition pieces = partition(slash.potential().remaining(), debit);
        slash.split(new SlashFragments(center, slash.forward(), slash.blade(), pieces,
                slash.potential().remaining() + debit, slash.fragmentRange(), slash.fragmentSpeed()));
    }

    record Partition(double total, double cost, long fullBlades, double tail) {
        long count() { return fullBlades + (tail > 0 ? 1 : 0); }
        double potentialAt(long index) {
            if (index < 0 || index >= count()) throw new IndexOutOfBoundsException("Fission blade index");
            return index < fullBlades ? cost : tail;
        }
    }

    static Partition partition(double remaining, double actualCost) {
        if (!Double.isFinite(remaining) || remaining < 0 || remaining > SlashCritical.MAX_POTENTIAL
                || !Double.isFinite(actualCost) || actualCost <= 0)
            throw new IllegalArgumentException("Fission requires a finite budget and positive target debit");
        // Decimal division avoids phantom tails for ordinary 0.1 / 0.2 / 0.04 debits, while
        // retaining an actually saved fractional budget. One operation, even for huge criticals.
        BigDecimal[] parts = BigDecimal.valueOf(remaining).divideAndRemainder(BigDecimal.valueOf(actualCost));
        long full = parts[0].longValueExact();
        double tail = parts[1].doubleValue();
        // A prior binary subtraction (e.g. .8 - .1) can leave a one-ULP phantom
        // blade. Only fold numerical boundary noise; keep meaningful saved fractions.
        double epsilon = Math.min(actualCost * 1.0E-8, Math.ulp(remaining) * 2);
        if (full > 0 && tail <= epsilon) tail = 0;
        else if (tail > 0 && actualCost - tail <= epsilon) { full = Math.addExact(full, 1); tail = 0; }
        if (tail > 0 && full == Long.MAX_VALUE) throw new IllegalArgumentException("Fission count overflow");
        return new Partition(remaining, actualCost, full, tail);
    }
}
