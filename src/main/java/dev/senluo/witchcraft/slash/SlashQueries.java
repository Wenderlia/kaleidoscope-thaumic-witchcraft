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

    static List<AABB> cover(SlashGeometry.Sweep sweep) {
        List<AABB> result = new ArrayList<>();
        Deque<SlashGeometry.Sweep> pending = new ArrayDeque<>();
        pending.push(sweep);
        while (!pending.isEmpty()) {
            var part = pending.pop();
            AABB box = part.bounds();
            if (!Double.isFinite(volume(box))) return List.of();
            if (volume(box) <= LEAF_CELLS) { result.add(box); continue; }
            // Splitting a trapezoid along either parameter preserves complete coverage,
            // including growth and the caller's conservative rotation padding.
            double width = 2 * Math.max(part.halfBefore(), part.halfAfter());
            double travel = part.from().distanceTo(part.to()) + Math.abs(part.halfAfter() - part.halfBefore());
            if (width < 1.0E-6 && travel < 1.0E-6 || result.size() + pending.size() + 2 > MAX_BOXES)
                return List.of();
            if (width >= travel) {
                for (int sign : new int[]{-1, 1}) pending.push(new SlashGeometry.Sweep(
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
        return result;
    }

    static double volume(AABB box) {
        return (Math.floor(box.maxX) - Math.floor(box.minX) + 1)
                * (Math.floor(box.maxY) - Math.floor(box.minY) + 1)
                * (Math.floor(box.maxZ) - Math.floor(box.minZ) + 1);
    }

    static boolean loaded(ServerLevel level, List<AABB> boxes) {
        Set<Long> checked = new HashSet<>();
        for (AABB box : boxes)
            for (int x = Mth.floor(box.minX) >> 4; x <= Mth.floor(box.maxX) >> 4; x++)
                for (int z = Mth.floor(box.minZ) >> 4; z <= Mth.floor(box.maxZ) >> 4; z++) {
                    long key = net.minecraft.world.level.ChunkPos.asLong(x, z);
                    if (checked.add(key) && !level.hasChunk(x, z)) return false;
                }
        return true;
    }

    static Iterable<BlockPos> cells(AABB box) {
        return BlockPos.betweenClosed(BlockPos.containing(box.minX, box.minY, box.minZ),
                BlockPos.containing(box.maxX, box.maxY, box.maxZ));
    }
}
