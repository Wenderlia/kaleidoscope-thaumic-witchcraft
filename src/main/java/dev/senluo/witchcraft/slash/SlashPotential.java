package dev.senluo.witchcraft.slash;

/** Potential is a cast-local budget, not a second Vis account. No world or rendering dependencies. */
public record SlashPotential(double initial, double remaining) {
    public static final double PER_CHAOS_VIS = 1.5;
    public static final double ENTITY_COST = .1;
    public static final double BLOCK_COST = .2;
    public SlashPotential {
        if (!Double.isFinite(initial) || !Double.isFinite(remaining) || initial <= 0 || initial > SlashCritical.MAX_POTENTIAL
                || remaining < 0 || remaining > initial)
            throw new IllegalArgumentException("Invalid slash potential");
    }
    public double damage() { return remaining * 5; }
    public boolean cuts(double hardness) { return Double.isFinite(hardness) && hardness >= 0 && hardness <= remaining * 3; }
    public static double blockCost(boolean destructive) { return destructive ? BLOCK_COST / 5 : BLOCK_COST; }
    public SlashPotential afterBlock() { return spend(BLOCK_COST); }
    public SlashPotential afterBlock(double cost) {
        if (!Double.isFinite(cost) || cost < 0) throw new IllegalArgumentException("Invalid block cost");
        return spend(cost);
    }
    public SlashPotential afterEntity() { return spend(ENTITY_COST); }
    private SlashPotential spend(double amount) { return new SlashPotential(initial, Math.max(0, remaining - amount)); }
}
