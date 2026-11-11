package dev.senluo.witchcraft.ritual;

/** Whole-unit admission, independent of items, aspects and any particular vessel. */
public final class RitualCapacity {
    public record Admission(int accepted, int rejected, long resultingAmount) {}
    private RitualCapacity() {}

    /** Quotes only; the host rechecks its actual resource before committing a transfer. */
    public static Admission quote(long stored, long capacity, long perUnit, int offered) {
        if (stored < 0 || capacity < 0 || perUnit <= 0 || offered < 0)
            throw new IllegalArgumentException("Invalid capacity admission inputs");
        long room = stored >= capacity ? 0 : capacity - stored;
        int accepted = (int) Math.min(offered, room / perUnit);
        // The quotient guarantees that the product and sum fit, even near Long.MAX_VALUE.
        return new Admission(accepted, offered - accepted, stored + accepted * perUnit);
    }
}
