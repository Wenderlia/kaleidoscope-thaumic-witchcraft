package dev.senluo.witchcraft.ritual;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;

/** One activation's spatial input. Looking for a recipe never creates a persistent center identity. */
public record RitualCandidate(BlockEntity entity, BlockPos position, BlockPos axis, RitualSite.Footprint footprint) {
    public RitualCandidate(BlockEntity entity, RitualPlane plane) {
        this(entity, entity.getBlockPos(), RitualCenters.axis(entity),
                new RitualSite.Footprint(plane.anchor(), plane.rings().completeMask()));
    }
    public RitualCandidate {
        java.util.Objects.requireNonNull(entity); java.util.Objects.requireNonNull(footprint);
        position = position.immutable(); axis = axis.immutable();
    }
    public boolean current(ServerLevel level) {
        return entity.getLevel() == level && !entity.isRemoved() && entity.getBlockPos().equals(position)
                && level.hasChunkAt(position) && level.shouldTickBlocksAt(position)
                && level.getBlockEntity(position) == entity && RitualCenters.axis(entity).equals(axis)
                && RitualCenters.mayPrepare(entity) && footprint.containsCenter(axis)
                && RitualPlane.at(level, footprint.anchor()).isPresent();
    }
}
