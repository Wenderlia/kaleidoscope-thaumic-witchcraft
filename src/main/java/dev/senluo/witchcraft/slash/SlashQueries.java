package dev.senluo.witchcraft.slash;

import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;

/** Broad-phase cover only. Contacts are always clipped against the original continuous sweep. */
final class SlashQueries {
    static final int MAX_BOXES = 512;
    private static final int LEAF_CELLS = 256;
    private SlashQueries() {}

    /** Pure geometry. World state and occupants are collected anew before each settlement. */
    static final class Coverage {
        private final SlashGeometry.Sweep sweep;
        private final AABB bounds;
        private List<AABB> boxes;

        Coverage(SlashGeometry.Sweep sweep) {
            this.sweep = sweep;
            this.bounds = sweep.bounds();
        }

        AABB bounds() { return bounds; }
        SlashGeometry.Sweep sweep() { return sweep; }

        List<AABB> boxes() {
            if (boxes == null) boxes = cover(sweep, bounds);
            return boxes;
        }

        boolean exceedsCellLimit(int limit) {
            // Every leaf covers at most LEAF_CELLS. Only an inconclusive bound needs an exact count.
            return boxes().size() * LEAF_CELLS > limit && countCellsUntilOver(limit) > limit;
        }

        /** Exact within budget or the first subtotal above it. Over-budget work is discarded. */
        int countCellsUntilOver(int limit) {
            int total = 0;
            for (AABB box : boxes()) {
                total += (int) volume(box);
                if (total > limit) break;
            }
            return total;
        }

        Coverage forSweep(SlashGeometry.Sweep next) { return sweep.equals(next) ? this : new Coverage(next); }
    }

    private static List<AABB> cover(SlashGeometry.Sweep part, AABB box) {
        double cells = volume(box);
        if (cells <= LEAF_CELLS) return List.of(box);

        List<AABB> result = new ArrayList<>();
        Deque<SlashGeometry.Sweep> pending = new ArrayDeque<>();
        while (true) {
            if (!Double.isFinite(cells)) return List.of();
            if (cells <= LEAF_CELLS) result.add(box);
            else {
                // Splitting a trapezoid along either parameter preserves complete coverage,
                // including growth and the caller's conservative rotation padding.
                double width = 2 * Math.max(part.halfBefore(), part.halfAfter());
                double travel = part.from().distanceTo(part.to()) + Math.abs(part.halfAfter() - part.halfBefore());
                if (width < 1.0E-6 && travel < 1.0E-6 || result.size() + pending.size() + 2 > MAX_BOXES)
                    return List.of();
                if (width >= travel) {
                    for (int sign = -1; sign <= 1; sign += 2) pending.push(new SlashGeometry.Sweep(
                            part.from().add(part.blade().scale(sign * part.halfBefore() * .5)),
                            part.to().add(part.blade().scale(sign * part.halfAfter() * .5)),
                            part.blade(), part.halfBefore() * .5, part.halfAfter() * .5, part.padding()));
                } else {
                    var middle = part.from().lerp(part.to(), .5);
                    double half = (part.halfBefore() + part.halfAfter()) * .5;
                    pending.push(new SlashGeometry.Sweep(middle, part.to(), part.blade(), half, part.halfAfter(), part.padding()));
                    pending.push(new SlashGeometry.Sweep(part.from(), middle, part.blade(), part.halfBefore(), half, part.padding()));
                }
            }
            if (pending.isEmpty()) return result;
            part = pending.pop();
            box = part.bounds();
            cells = volume(box);
        }
    }

    static double volume(AABB box) {
        return (Math.floor(box.maxX) - Math.floor(box.minX) + 1)
                * (Math.floor(box.maxY) - Math.floor(box.minY) + 1)
                * (Math.floor(box.maxZ) - Math.floor(box.minZ) + 1);
    }

    static boolean loaded(ServerLevel level, AABB box) {
        return level.hasChunksAt(Mth.floor(box.minX), Mth.floor(box.minZ), Mth.floor(box.maxX), Mth.floor(box.maxZ));
    }

    static boolean loaded(ServerLevel level, List<AABB> boxes) {
        if (boxes.size() == 1) return loaded(level, boxes.getFirst());
        return firstMissing(level, boxes) == null;
    }

    /** Called only after the native load check failed. A single-chunk rectangle identifies its own witness. */
    static Long missingAfterRejection(ServerLevel level, List<AABB> boxes) {
        if (boxes.size() == 1) {
            AABB box = boxes.getFirst();
            int x = Mth.floor(box.minX) >> 4, z = Mth.floor(box.minZ) >> 4;
            if (x == (Mth.floor(box.maxX) >> 4) && z == (Mth.floor(box.maxZ) >> 4))
                return net.minecraft.world.level.ChunkPos.asLong(x, z);
        }
        return firstMissing(level, boxes);
    }

    /** One authoritative missing chunk suffices to reject this particular sweep. */
    private static Long firstMissing(ServerLevel level, List<AABB> boxes) {
        Set<Long> checked = boxes.size() > 1 ? new HashSet<>() : null;
        for (AABB box : boxes)
            for (int x = Mth.floor(box.minX) >> 4; x <= Mth.floor(box.maxX) >> 4; x++)
                for (int z = Mth.floor(box.minZ) >> 4; z <= Mth.floor(box.maxZ) >> 4; z++) {
                    long key = net.minecraft.world.level.ChunkPos.asLong(x, z);
                    if ((checked == null || checked.add(key)) && !level.hasChunk(x, z)) return key;
                }
        return null;
    }

    static Iterable<BlockPos> cells(AABB box) {
        return BlockPos.betweenClosed(BlockPos.containing(box.minX, box.minY, box.minZ),
                BlockPos.containing(box.maxX, box.maxY, box.maxZ));
    }
}
