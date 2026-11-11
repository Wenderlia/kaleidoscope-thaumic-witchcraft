package dev.senluo.witchcraft.time;

/** Integer mTU prices for a bounded stage. Progress is work, never stored currency. */
public final class WorkCost {
    private WorkCost() {}

    /** Cumulative ceiling makes split payments equal the same single stage payment. */
    public static long cumulative(long total, int progress, int duration) {
        if (total < 0 || duration <= 0) throw new IllegalArgumentException("Invalid work price");
        long ticks = Math.clamp(progress, 0, duration);
        return total / duration * ticks + ((total % duration) * ticks + duration - 1) / duration;
    }

    /** Only purchased progress is charged; free progress creates neither debt nor stock. */
    public static long between(long total, int from, int to, int duration) {
        if (to < from) throw new IllegalArgumentException("Reversed work interval");
        return cumulative(total, to, duration) - cumulative(total, from, duration);
    }

    public static int affordableTicks(long total, long paid, int duration) {
        if (total < 0 || duration <= 0) throw new IllegalArgumentException("Invalid work price");
        if (total == 0) return duration;
        long amount = Math.clamp(paid, 0, total);
        if (amount <= Long.MAX_VALUE / duration) return (int) (amount * duration / total);
        return java.math.BigInteger.valueOf(amount).multiply(java.math.BigInteger.valueOf(duration))
                .divide(java.math.BigInteger.valueOf(total)).intValueExact();
    }
}
