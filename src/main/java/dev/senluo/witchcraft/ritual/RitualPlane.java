package dev.senluo.witchcraft.ritual;

import java.util.Optional;
import java.util.function.Function;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/** A single marked-block plane. Height selection is independent of center type and ring allocation. */
public record RitualPlane(BlockPos anchor, RitualRings.Layout rings) {
    public static final int HEIGHT_RADIUS = 7;

    public RitualPlane {
        anchor = anchor.immutable();
        if (rings == null || rings.count() == 0) throw new IllegalArgumentException("No complete ritual ring");
    }

    public int outermostRing() { return rings.outermostRing(); }

    public boolean containsCenter(BlockPos pos) {
        return pos.getX() == anchor.getX() && pos.getZ() == anchor.getZ()
                && Math.abs((long) pos.getY() - anchor.getY()) <= HEIGHT_RADIUS;
    }

    /** The entire center column, including the plane itself; nearest first, above on ties. */
    public static java.util.stream.Stream<BlockPos> centerPositions(BlockPos anchor) {
        return java.util.stream.IntStream.rangeClosed(0, HEIGHT_RADIUS * 2).mapToObj(index ->
                anchor.offset(0, index == 0 ? 0 : (index % 2 == 1 ? (index + 1) / 2 : -index / 2), 0));
    }

    public static Optional<RitualPlane> find(ServerLevel level, BlockPos axis) {
        return select(level, axis, anchor -> at(level, anchor));
    }

    /** Custom templates share the height policy without depending on the three standard ring sizes. */
    public static Optional<BlockPos> match(ServerLevel level, BlockPos axis, RitualShape shape) {
        return select(level, axis, anchor -> shape.match(level, anchor) == RitualShape.Match.VALID
                ? Optional.of(anchor.immutable()) : Optional.empty());
    }

    /** Nearest complete plane first; equal distances prefer below. Rings on separate floors never combine. */
    private static <T> Optional<T> select(ServerLevel level, BlockPos axis, Function<BlockPos, Optional<T>> probe) {
        for (int index = 0; index <= HEIGHT_RADIUS * 2; index++) {
            int offset = index == 0 ? 0 : (index % 2 == 1 ? -(index + 1) / 2 : index / 2);
            BlockPos anchor = axis.offset(0, offset, 0);
            if (level.isOutsideBuildHeight(anchor)) continue;
            var result = probe.apply(anchor);
            if (result.isPresent()) return result;
        }
        return Optional.empty();
    }

    public static Optional<RitualPlane> at(ServerLevel level, BlockPos anchor) {
        var rings = new RitualRings.Layout(RitualShape.SMALL.match(level, anchor),
                RitualShape.MEDIUM.match(level, anchor), RitualShape.LARGE.match(level, anchor));
        return rings.count() == 0 ? Optional.empty() : Optional.of(new RitualPlane(anchor, rings));
    }

    /** Explicit activation must not reuse a geometry result from before external callbacks. */
    public static void invalidate(ServerLevel level, BlockPos axis) {
        RitualShapeCache.invalidateColumn(level, axis, HEIGHT_RADIUS);
    }
}
