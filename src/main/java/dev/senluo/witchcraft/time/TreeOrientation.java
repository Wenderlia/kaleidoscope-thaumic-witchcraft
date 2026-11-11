package dev.senluo.witchcraft.time;

import java.util.HashSet;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

/** Horizontal turns shared by placement, portable wood memory and the natural tree plan. */
public final class TreeOrientation {
    private TreeOrientation() {}

    static int turns(int facing) {
        return switch (facing) {
            case 5 -> 1; // east
            case 3 -> 2; // south
            case 4 -> 3; // west
            default -> 0; // north, including old items without a facing
        };
    }

    public static BlockPos relative(BlockPos local, int facing) {
        return rotate(local, turns(facing));
    }

    static BlockPos rotate(BlockPos pos, int turns) {
        return switch (Math.floorMod(turns, 4)) {
            case 1 -> new BlockPos(-pos.getZ(), pos.getY(), pos.getX());
            case 2 -> new BlockPos(-pos.getX(), pos.getY(), -pos.getZ());
            case 3 -> new BlockPos(pos.getZ(), pos.getY(), -pos.getX());
            default -> pos;
        };
    }

    static Direction.Axis rotate(Direction.Axis axis, int turns) {
        return (turns & 1) == 0 || axis == Direction.Axis.Y ? axis
                : axis == Direction.Axis.X ? Direction.Axis.Z : Direction.Axis.X;
    }

    /** Only called for the freshly decoded placement copy, never an already placed tree. */
    static void place(Tissue tissue, Direction facing) {
        int next = facing.getAxis().isHorizontal() ? facing.get3DDataValue() : Direction.NORTH.get3DDataValue();
        int delta = Math.floorMod(turns(next) - turns(tissue.treeFacing), 4);
        if (delta != 0) {
            rotate(tissue.formedTree, delta);
            rotate(tissue.formedLogs, delta);
            tissue.shapeSpace.rotate(delta);
            tissue.fruitSites.rotate(delta);
        }
        tissue.treeFacing = next;
    }

    private static void rotate(Set<Long> positions, int turns) {
        Set<Long> rotated = new HashSet<>(positions.size());
        for (long position : positions) rotated.add(rotate(BlockPos.of(position), turns).asLong());
        positions.clear();
        positions.addAll(rotated);
    }
}
