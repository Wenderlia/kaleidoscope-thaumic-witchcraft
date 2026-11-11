package dev.senluo.witchcraft.time;

/** First-tree balance, in milli-TU and development ticks; never wall-clock seconds. */
public final class TreeBalance {
    public static final int ENTRY_TICKS = 30 * 60 * 20;
    public static final long CAPACITY_BONUS = 14_976_000 * Tissue.TU;
    public static final long GIANT_CAPACITY_BONUS = 46_080_000 * Tissue.TU;
    public static final int GIANT_TICKS = 90 * 60 * 20;
    public static final long LOG_REPAIR = 32 * Tissue.TU;
    public static final long LEAF_REPAIR = 4 * Tissue.TU;
    public static final int MAX_FRUIT_GRADE = 5;
    public static long nodeFruitFee(int grade) {
        long total = 0; for (int i=1; i<=grade; i++) total += FRUIT_FEE[i]; return total;
    }
    public static int nodeFruitTicks(int grade) {
        int total = 0; for (int i=1; i<=grade; i++) total += FRUIT_TICKS[i]; return total;
    }
    // Incremental quality fees and work durations.
    public static final long[] FRUIT_FEE = {0, 2000*Tissue.TU, 4000*Tissue.TU, 8000*Tissue.TU, 16000*Tissue.TU, 32000*Tissue.TU};
    public static final int[] FRUIT_TICKS = {0, 2400, 3600, 4800, 6000, 7200};

    private TreeBalance() {}

    public static int remaining(Tissue tissue) {
        if (tissue.giantGrowing && !tissue.giant) return Math.max(0, GIANT_TICKS - tissue.giantTicks);
        if (tissue.treeGrowing && !tissue.tree) return Math.max(0, ENTRY_TICKS - tissue.treeTicks);
        return 0;
    }

}
