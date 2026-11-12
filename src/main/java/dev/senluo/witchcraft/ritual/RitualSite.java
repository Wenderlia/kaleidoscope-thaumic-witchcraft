package dev.senluo.witchcraft.ritual;

import net.minecraft.core.BlockPos;

/** Registered execution context and fixed spatial snapshot; saving members never replace this anchor. */
public record RitualSite(RitualCenters.Center center, Footprint footprint, java.util.UUID session) {
    /** Captures the chosen geometry once; this snapshot cannot answer current structural validity. */
    public RitualSite(RitualCenters.Center center, RitualPlane plane) {
        this(center, plane == null ? null : new Footprint(plane.anchor(), plane.rings().completeMask()), null);
    }
    public RitualSite {
        java.util.Objects.requireNonNull(center);
    }
    public RitualCenters.Reference reference() { return center.reference(); }
    public BlockPos position() { return reference().position(); }
    public BlockPos axis() { return center.axis(); }

    /** Saved spatial shape, not a world observation or a source of ritual points. */
    public record Footprint(BlockPos anchor, int ringMask) {
        public Footprint { anchor = anchor.immutable(); }
        public int outermostRing() {
            return (ringMask & 4) != 0 ? 3 : (ringMask & 2) != 0 ? 2 : (ringMask & 1) != 0 ? 1 : 0;
        }
        public boolean containsCenter(BlockPos pos) { return RitualPlane.containsCenter(anchor, pos); }
    }
}
