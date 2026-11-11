package dev.senluo.witchcraft.ritual;

import net.minecraft.core.BlockPos;

/** Receipt anchor and ritual geometry; effects select eligible objects in the center column. */
public record RitualSite(RitualCenters.Center center, RitualPlane plane) {
    public RitualSite {
        java.util.Objects.requireNonNull(center);
    }
    public RitualCenters.Reference reference() { return center.reference(); }
    public BlockPos position() { return reference().position(); }
    public BlockPos axis() { return center.axis(); }
}
