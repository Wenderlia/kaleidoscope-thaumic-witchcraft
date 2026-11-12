package dev.senluo.witchcraft.ritual;

import java.util.Optional;
import java.util.function.Function;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/** World-observed ring geometry. Saved ritual footprints use RitualSite.Footprint instead. */
public record RitualPlane(BlockPos anchor, RitualRings.Layout rings) {
    public static final int HEIGHT_RADIUS = 7;

    public RitualPlane {
        anchor = anchor.immutable();
        java.util.Objects.requireNonNull(rings);
    }

    public int outermostRing() { return rings.outermostRing(); }

    public boolean containsCenter(BlockPos pos) {
        return containsCenter(anchor, pos);
    }

    public static boolean containsCenter(BlockPos anchor, BlockPos pos) {
        return pos.getX() == anchor.getX() && pos.getZ() == anchor.getZ()
                && Math.abs((long) pos.getY() - anchor.getY()) <= HEIGHT_RADIUS;
    }

    /** The entire center column, including the plane itself; nearest first, above on ties. */
    public static java.util.stream.Stream<BlockPos> centerPositions(BlockPos anchor) {
        return java.util.stream.IntStream.rangeClosed(0, HEIGHT_RADIUS * 2).mapToObj(index ->
                anchor.offset(0, index == 0 ? 0 : (index % 2 == 1 ? (index + 1) / 2 : -index / 2), 0));
    }

    /** Inverse of direct center coverage. Ascending height preserves the allocation order. */
    public static java.util.stream.Stream<BlockPos> supplyPositions(BlockPos recipient) {
        return java.util.stream.IntStream.rangeClosed(-HEIGHT_RADIUS, HEIGHT_RADIUS)
                .mapToObj(offset -> recipient.offset(0, offset, 0));
    }

    public static Optional<RitualPlane> find(ServerLevel level, BlockPos axis) {
        return RitualShapeCache.find(level, axis);
    }

    static Optional<RitualPlane> inspect(ServerLevel level, BlockPos axis) {
        return select(level, axis, anchor -> at(level, anchor));
    }

    /** Activation quotes every existing legal plane; a failed nearest candidate does not hide the others. */
    public static java.util.List<RitualPlane> candidates(ServerLevel level, BlockPos axis) {
        var result = new java.util.ArrayList<RitualPlane>();
        for (int index = 0; index <= HEIGHT_RADIUS * 2; index++) {
            int offset = index == 0 ? 0 : (index % 2 == 1 ? -(index + 1) / 2 : index / 2);
            BlockPos anchor = axis.offset(0, offset, 0);
            if (!level.isOutsideBuildHeight(anchor)) at(level, anchor).ifPresent(result::add);
        }
        return java.util.List.copyOf(result);
    }

    /** Custom templates share the height policy without depending on the three standard ring sizes. */
    public static Optional<BlockPos> match(ServerLevel level, BlockPos axis, RitualShape shape) {
        return select(level, axis, anchor -> shape.match(level, anchor) == RitualShape.Match.VALID
                ? Optional.of(anchor.immutable()) : Optional.empty());
    }

    /** Nearest complete plane first; equal distances prefer below. This selection is the spatial origin, not the pool allocation. */
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

    public static RitualRings.Layout observe(ServerLevel level, BlockPos anchor) {
        return new RitualRings.Layout(RitualShape.SMALL.match(level, anchor),
                RitualShape.MEDIUM.match(level, anchor), RitualShape.LARGE.match(level, anchor));
    }

    public static Optional<RitualPlane> at(ServerLevel level, BlockPos anchor) {
        var rings = observe(level, anchor);
        return rings.count() == 0 ? Optional.empty() : Optional.of(new RitualPlane(anchor, rings));
    }

    /** Explicit activation must not reuse a geometry result from before external callbacks. */
    public static void invalidate(ServerLevel level, BlockPos axis) {
        RitualShapeCache.invalidateColumn(level, axis, HEIGHT_RADIUS);
    }
}
