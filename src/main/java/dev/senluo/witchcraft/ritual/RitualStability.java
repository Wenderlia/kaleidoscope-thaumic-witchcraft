package dev.senluo.witchcraft.ritual;

/** Add complete-ring contributions to the upstream baseline, then apply placed stabilizers. */
public final class RitualStability {
    private static final RingBonus CAPACITY_QUARTERS = RingBonus.additive(2, 3, 4, 13);
    private static final RingBonus DECAY_INTERVALS = RingBonus.additive(1, 2, 4, 8);
    private static final RingBonus WATER_QUARTERS = RingBonus.additive(-1, -1, -1, 1);
    private RitualStability() {}

    /** Shared by admission, process hooks, evaporation and Jade. Never resolves the world. */
    public record Profile(RitualRings.Layout rings, int points) {
        public Profile {
            java.util.Objects.requireNonNull(rings);
            if (!rings.fullyKnown() || points < 0 || rings.count() == 0 && points != 0)
                throw new IllegalArgumentException("Unknown rings or invalid stability score");
        }
        private int capacityQuarters() {
            return (int) CAPACITY_QUARTERS.resolve(4, rings.completeMask());
        }
        private int decayIntervals() {
            return (int) DECAY_INTERVALS.resolve(1, rings.completeMask());
        }
        private int waterQuarters() {
            return (int) WATER_QUARTERS.resolve(4, rings.completeMask());
        }
        public int capacity(int upstreamCapacity) {
            if (upstreamCapacity < 0) throw new IllegalArgumentException("Negative upstream capacity");
            long multiplier = capacityQuarters() * (100L + points);
            if (upstreamCapacity > Integer.MAX_VALUE * 400L / multiplier) return Integer.MAX_VALUE;
            return (int) (upstreamCapacity * multiplier / 400);
        }
        public double decayMultiplier() { return decayIntervals() * (100L + points) / 100.0; }
        public double waterMultiplier() { return waterQuarters() / 4.0; }
        /** Native scheduling is in whole ticks; round up, never overflow or schedule an immediate loop. */
        public int nextDelay(int upstreamDelay) {
            if (upstreamDelay < 1) throw new IllegalArgumentException("Invalid decomposition delay");
            long multiplier = decayIntervals() * (100L + points);
            if (upstreamDelay > Integer.MAX_VALUE * 100L / multiplier) return Integer.MAX_VALUE;
            return (int) ((upstreamDelay * multiplier + 99) / 100);
        }
        /** Paid fractional water stays with the vessel when its ring combination changes. */
        public WaterQuote water(int upstreamAmount, int paidCredit) {
            if (upstreamAmount < 0 || paidCredit < 0 || paidCredit > 3)
                throw new IllegalArgumentException("Invalid water ledger");
            long quarters = (long) upstreamAmount * waterQuarters() - paidCredit;
            long charge = quarters <= 0 ? 0 : (quarters + 3) / 4;
            return new WaterQuote(charge, paidCredit, (int) (charge * 4 - quarters));
        }
    }

    /** Credit is previously paid fractional water, in quarters of one mB. Persist it with the center. */
    public record WaterQuote(long millibuckets, int previousCredit, int nextCredit) {}

    /** Range has no free base. Unused aspect pairs beyond 32 remain in the vessel. */
    public static int steamRadius(long vacuos, long ordo) {
        if (vacuos < 0 || ordo < 0) throw new IllegalArgumentException("Negative aspect balance");
        return (int) Math.min(32, Math.min(vacuos / 4, ordo / 4));
    }
}
