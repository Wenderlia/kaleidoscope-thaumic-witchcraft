package dev.senluo.witchcraft.slash;

/** Selects a pre-baked attack envelope; the impact body and tail do not change. */
final class SlashImpactCurve {
    static final double STONE_HARDNESS = 1.5;
    static final double OBSIDIAN_HARDNESS = 50.0;
    static final double IRON_HARDNESS = 5.0;
    static final int STEPS = 16;
    static final int IRON_PROFILE = STEPS + 1;
    static final int PROFILE_COUNT = STEPS + 2;
    private static final double IRON_STRENGTH = strength(IRON_HARDNESS);

    private SlashImpactCurve() {}

    private static double strength(double hardness) {
        double u = Math.clamp(Math.log(Math.max(hardness, STONE_HARDNESS) / STONE_HARDNESS)
                / Math.log(OBSIDIAN_HARDNESS / STONE_HARDNESS), 0.0, 1.0);
        return u * u * (3.0 - 2.0 * u);
    }

    static int profileIndex(float hardness) {
        if (!(hardness >= STONE_HARDNESS)) return 0;
        if (hardness >= OBSIDIAN_HARDNESS) return STEPS;
        double strength = strength(hardness);
        int nearest = (int) Math.round(strength * STEPS);
        // Keep all three approved material anchors exact, including iron between baked steps.
        return Math.abs(strength - IRON_STRENGTH) < Math.abs(strength - (double) nearest / STEPS)
                ? IRON_PROFILE : nearest;
    }

    static String soundName(int profile) {
        if (profile == 0) return "blocked_stone";
        if (profile == STEPS) return "blocked_obsidian";
        if (profile == IRON_PROFILE) return "blocked_iron";
        return "blocked_hardness_" + profile;
    }
}
