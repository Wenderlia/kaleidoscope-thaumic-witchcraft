package dev.senluo.witchcraft.time;

/** Recursion guard only. Extra work is priced normally; provenance owns same-owner exemptions. */
public final class ExtraTicks {
    private static final ThreadLocal<Boolean> ACTIVE = ThreadLocal.withInitial(() -> false);
    private ExtraTicks() {}
    public static boolean active() { return ACTIVE.get(); }
    public static void run(Runnable action) {
        boolean previous = ACTIVE.get();
        ACTIVE.set(true);
        try { action.run(); }
        finally { if (previous) ACTIVE.set(true); else ACTIVE.remove(); }
    }
}
