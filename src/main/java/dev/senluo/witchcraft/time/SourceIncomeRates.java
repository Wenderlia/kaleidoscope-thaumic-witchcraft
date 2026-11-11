package dev.senluo.witchcraft.time;

/** Net prices in mTU. Callers pass captured quantities, never apply absorption twice. */
public final class SourceIncomeRates {
    public static final long CROP = 6 * Tissue.TU;
    public static final double WORK = 2 * Tissue.TU;
    public static final double ENTITY_SECOND = 8 * Tissue.TU;
    public static final double ENTITY_BLOCK = 16 * Tissue.TU;
    public static final double SCHEDULED_OPPORTUNITY = 4 * Tissue.TU;
    public static final double WATER = 16 * Tissue.TU;
    public static final double LAVA = 48 * Tissue.TU;

    private SourceIncomeRates() {}

    public static double motion(double volume, double capturedDistance) {
        return volume * capturedDistance * ENTITY_BLOCK;
    }

    public static double clock(double volume, double capturedSeconds) {
        return volume * capturedSeconds * ENTITY_SECOND;
    }
}
