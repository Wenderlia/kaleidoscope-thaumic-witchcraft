package dev.senluo.witchcraft.time;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/** One undirected connection rule for models, colonies and root growth. */
public final class RootConnections {
    private RootConnections() {}

    public static BlockPos neighbor(BlockGetter world, BlockPos pos, Direction direction) {
        if (!loaded(world, pos)) return null;
        var current = world.getBlockState(pos);
        BlockPos next = pos.relative(direction);
        if (!loaded(world, next)) return null;
        var adjacent = world.getBlockState(next);
        // A plant connects through its supporting rooted soil; foliage is never a port.
        if (current.is(TemporalContent.RHIZOME.get()) || current.is(TemporalContent.CORE.get())) {
            if (adjacent.is(dev.senluo.witchcraft.time.TreeWood.LOGS) && (current.is(TemporalContent.CORE.get()) || direction == Direction.DOWN)) return next;
            if (direction != Direction.DOWN) return null;
            if (isRoot(adjacent) && TissueItem.coreSoil(adjacent)) return next;
            if (adjacent.is(TemporalContent.ROOTED_FARMLAND.get())) return next;
            if (!adjacent.is(Blocks.FARMLAND)) return null;
            // Established underground routes remain connected without requiring an exposed surface.
            BlockPos collar = pos.below(2);
            return loaded(world, collar) && isConduit(world.getBlockState(collar)) ? collar : null;
        }
        if (!isConduit(current) && !current.is(TemporalContent.AMBER.get())) return null;
        if (current.is(dev.senluo.witchcraft.time.TreeWood.LOGS) && (adjacent.is(TemporalContent.CORE.get())
                || direction == Direction.UP && adjacent.is(TemporalContent.RHIZOME.get()))) return next;
        if (isRoot(current) && TissueItem.coreSoil(current) && direction == Direction.UP
                && (adjacent.is(TemporalContent.CORE.get()) || adjacent.is(TemporalContent.RHIZOME.get()))) return next;
        if (isConduit(current) && direction == Direction.UP && adjacent.is(Blocks.FARMLAND)) {
            BlockPos crop = pos.above(2);
            return loaded(world, crop) && (world.getBlockState(crop).is(TemporalContent.RHIZOME.get())
                    || world.getBlockState(crop).is(TemporalContent.CORE.get())) ? crop : null;
        }
        return isConduit(adjacent) || adjacent.is(TemporalContent.AMBER.get()) ? next : null;
    }

    /** Tree wood uses the same face/edge/corner contact as the existing wood-memory geometry. */
    public static java.util.Set<BlockPos> neighbors(BlockGetter world, BlockPos pos) {
        java.util.Set<BlockPos> result = new java.util.LinkedHashSet<>();
        for (Direction direction : Direction.values()) {
            BlockPos next = neighbor(world, pos, direction);
            if (next != null) result.add(next);
        }
        if (!loaded(world, pos)) return result;
        BlockState state = world.getBlockState(pos);
        boolean log = state.is(dev.senluo.witchcraft.time.TreeWood.LOGS);
        if (log || state.is(TemporalContent.CORE.get())) for (BlockPos offset : TreeShapeSpace.NEIGHBORS) {
            BlockPos next = pos.offset(offset);
            if (!loaded(world, next)) continue;
            BlockState adjacent = world.getBlockState(next);
            if (adjacent.is(dev.senluo.witchcraft.time.TreeWood.LOGS) || log && adjacent.is(TemporalContent.CORE.get())) result.add(next);
        }
        return result;
    }

    public static boolean connects(BlockGetter world, BlockPos pos, Direction direction) {
        return neighbor(world, pos, direction) != null;
    }

    public static boolean isRoot(BlockState state) {
        return state.is(TemporalContent.ROOT.get()) || state.is(TemporalContent.ROOTED_FARMLAND.get());
    }

    public static boolean isConduit(BlockState state) {
        return isRoot(state) || state.is(dev.senluo.witchcraft.time.TreeWood.LOGS);
    }

    private static boolean loaded(BlockGetter world, BlockPos pos) {
        return !(world instanceof LevelReader reader) || reader.hasChunkAt(pos);
    }
}
