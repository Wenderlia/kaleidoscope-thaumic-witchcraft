package dev.senluo.witchcraft.time;

/** Version 2 policies spend existing stock; natural structure never uses this multiplier. */
public final class SupplyMode {
    public static final int PAUSED = 0;
    public static final int NORMAL = 1;
    public static final int CONCENTRATED = 2;
    public static final int PRIORITY = CONCENTRATED;
    private SupplyMode() {}
    public static int next(int mode) { return (mode + 1) % 3; }
    public static int rate(int mode) { return mode == 0 ? 0 : mode == CONCENTRATED ? 2 : 1; }
    public static int share(int mode) { return mode == 0 ? 0 : 100; }
    public static int developmentRate(int mode) { return Math.max(1, rate(mode)); }
}
