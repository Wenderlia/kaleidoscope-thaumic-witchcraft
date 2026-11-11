package dev.senluo.witchcraft.time;

/** Only completed quality grants a return; unfinished work is consumed by early harvesting. */
public final class FruitTime {
    private static final double[] PERSONAL_SECONDS = {0,60,150,360,900,2160};
    private FruitTime() {}

    public static long plantAmount(int grade) { return plantAmount(grade, 2); }
    public static long plantAmount(int grade, int version) {
        if (version != 2) return 0;
        long invested = 0;
        long[] fees = TreeBalance.FRUIT_FEE;
        for (int current = 1; current <= Math.clamp(grade,1,TreeBalance.MAX_FRUIT_GRADE); current++)
            invested += fees[current];
        return invested / 5;
    }

    public static double personalTicks(int grade) { return personalTicks(grade, 2, 1); }
    public static double personalTicks(int grade, int version, int bodyGrade) {
        if (version != 2) return 0;
        int quality = Math.clamp(grade,1,TreeBalance.MAX_FRUIT_GRADE);
        return PERSONAL_SECONDS[quality] * 20;
    }
}
