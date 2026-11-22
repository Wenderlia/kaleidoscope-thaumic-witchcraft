package dev.senluo.witchcraft.ritual;

import java.util.List;
import java.util.Objects;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/**
 * Derived coverage at one block. A ring plane supplies a center column; the
 * domain is the set of valid planes whose columns cover the queried position.
 * It does not own points, ritual records, or effect state.
 */
public record RitualCenterDomain(BlockPos position, List<RitualPlane> planes) {
    public RitualCenterDomain {
        position = position.immutable();
        planes = List.copyOf(Objects.requireNonNull(planes));
    }

    public boolean empty() { return planes.isEmpty(); }

    /** A center is affected when it lies in at least one member plane column. */
    public boolean covers(BlockPos center) {
        return planes.stream().anyMatch(plane -> plane.containsCenter(center));
    }

    /** Compute the domain from the block's own coordinate; no relay through storage centers. */
    public static RitualCenterDomain at(ServerLevel level, BlockPos position) {
        return new RitualCenterDomain(position, coveringPlanes(level, position));
    }

    /** Find every valid ring plane whose center column covers the queried block. */
    public static List<RitualPlane> coveringPlanes(ServerLevel level, BlockPos position) {
        var result = new java.util.ArrayList<RitualPlane>();
        for (int index = 0; index <= RitualPlane.HEIGHT_RADIUS * 2; index++) {
            int offset = index == 0 ? 0 : (index % 2 == 1 ? -(index + 1) / 2 : index / 2);
            BlockPos anchor = position.offset(0, offset, 0);
            if (!level.isOutsideBuildHeight(anchor)) RitualPlane.at(level, anchor)
                    .filter(plane -> plane.containsCenter(position)).ifPresent(result::add);
        }
        return List.copyOf(result);
    }

    /** All candidate column anchors, including empty/invalid ones for loss tracking. */
    public static java.util.stream.Stream<BlockPos> candidateAnchors(BlockPos position) {
        return RitualPlane.supplyPositions(position);
    }
}
