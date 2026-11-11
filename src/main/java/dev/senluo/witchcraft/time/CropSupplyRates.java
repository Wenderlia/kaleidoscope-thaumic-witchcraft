package dev.senluo.witchcraft.time;

/** TU cost per executed crop attempt; source income prices are independent. */
public final class CropSupplyRates {
    // Vanilla's default 3 samples / 4096 positions per game tick, fixed for pricing.
    // This equates expected native time, not guaranteed growth or a universal TU/second rate.
    public static final int RANDOM_WORK = 4096;
    public static final int TICK_WORK = 3;
    public static final int BASE_RATE = 4;
    private static final int[] ROOT_COST = {0, 0, 0, 0, 3};
    private static final int[] AMBER_COST = {4, 3, 2, 1};

    private CropSupplyRates() {}

    public static long price(LivingEntity source) {
        if (source.amber()) return AMBER_COST[source.tissue.amberStage] * Tissue.TU;
        return (source.tree() ? 2 : ROOT_COST[source.tissue.rootStage]) * Tissue.TU;
    }

    /** Prepay the next fractional mTU; persisted credit prevents free work on every reload. */
    public static long charge(long price, int work, int prepaid) {
        return Math.max(0, Math.floorDiv(price * work - prepaid + RANDOM_WORK - 1, RANDOM_WORK));
    }

    public static int credit(long price, int work, int prepaid, long charge) {
        return Math.toIntExact(prepaid + charge * RANDOM_WORK - price * work);
    }
}
