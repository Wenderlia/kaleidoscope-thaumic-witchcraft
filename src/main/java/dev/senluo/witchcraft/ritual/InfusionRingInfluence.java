package dev.senluo.witchcraft.ritual;

/** Pure policy: each complete eligible ring contributes independently. */
public record InfusionRingInfluence(int symmetricBlocks, int bonusTwentieths) {
    public static final InfusionRingInfluence NONE = new InfusionRingInfluence(0, 0);
    private static final RingBonus BLOCKS = RingBonus.additive(4, 8, 16, 28);
    private static final RingBonus SPEED = RingBonus.additive(1, 1, 1, 3);
    public static InfusionRingInfluence of(RitualRings.Layout rings) {
        int complete = rings.completeMask();
        return new InfusionRingInfluence((int) BLOCKS.resolve(0, complete), (int) SPEED.resolve(0, complete));
    }
    public float stability(float unpaired, float mirrored) {
        return symmetricBlocks * (unpaired - mirrored);
    }
}
