package dev.senluo.witchcraft.time;

import java.math.BigInteger;

/** Bottle duration and stored TU have different dimensions; this is their exchange price. */
public final class BottleFeedingRates {
    public static final long REFERENCE_STOCK = 1200 * Tissue.TU;
    public static final int HOLD_LIMIT_TU_PER_SECOND = 1_000_000;

    private BottleFeedingRates() {}

    /** Integral of 9 - 6*Q/1200 bottle seconds per TU, with a floor of 3 seconds. */
    private static BigInteger accumulatedTicks(long milliTu, int ticksPerBottleSecond) {
        long q = Math.max(0, milliTu);
        long curved = Math.min(q, REFERENCE_STOCK);
        BigInteger k = BigInteger.valueOf(REFERENCE_STOCK);
        BigInteger x = BigInteger.valueOf(curved);
        BigInteger numerator = x.multiply(k).multiply(BigInteger.valueOf(9))
                .subtract(x.multiply(x).multiply(BigInteger.valueOf(3)))
                .add(BigInteger.valueOf(q - curved).multiply(k).multiply(BigInteger.valueOf(3)));
        return numerator.multiply(BigInteger.valueOf(ticksPerBottleSecond))
                .divide(k.multiply(BigInteger.valueOf(Tissue.TU)));
    }

    public static long costTicks(long currentMilliTu, int wholeTu, int ticksPerBottleSecond) {
        return costMilliTuTicks(currentMilliTu, Math.multiplyExact((long) wholeTu, Tissue.TU), ticksPerBottleSecond);
    }

    /** Fractional final payments use the same integral, without rounding fruit fees up to whole TU. */
    public static long costMilliTuTicks(long currentMilliTu, long milliTu, int ticksPerBottleSecond) {
        if (currentMilliTu < 0 || milliTu < 0 || ticksPerBottleSecond <= 0) throw new IllegalArgumentException("Invalid bottle quote");
        long end = Math.addExact(currentMilliTu, milliTu);
        // Differences of the same integral preserve the cost when a transfer is split into clicks.
        return accumulatedTicks(end, ticksPerBottleSecond)
                .subtract(accumulatedTicks(currentMilliTu, ticksPerBottleSecond)).longValueExact();
    }

    /** Monotone integral quote: large transfers must not search one TU at a time. */
    public static int affordableTu(long stock, int requested, int bottleUnit, long budget) {
        int low=0, high=Math.max(0,requested);
        while(low<high) {
            int middle=low+(int)(((long)high-low+1)/2);
            if(costTicks(stock,middle,bottleUnit)<=budget)low=middle;
            else high=middle-1;
        }
        return low;
    }

    public static long holdMilliTuPerTick(int heldTicks) {
        if (heldTicks <= 20) return 0;
        double rate = Math.min(HOLD_LIMIT_TU_PER_SECOND, Math.pow(2, Math.min(30, (heldTicks - 20) / 20.0)));
        return Math.round(rate * Tissue.TU / 20);
    }
}
