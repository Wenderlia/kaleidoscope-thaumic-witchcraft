package dev.senluo.witchcraft.ritual;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/** Immutable support-plane templates. Centers supply an anchor; shapes know nothing about trees or crucibles. */
public record RitualShape(List<BlockPos> marks) {
    public enum Match { VALID, INVALID, UNLOADED }

    public static final RitualShape SMALL = rows("..###..", ".#...#.", "#.....#", "#.....#",
            "#.....#", ".#...#.", "..###..");
    public static final RitualShape MEDIUM = rows("...#####...", "..#.....#..", ".#.......#.",
            "#.........#", "#.........#", "#.........#", "#.........#", "#.........#",
            ".#.......#.", "..#.....#..", "...#####...");
    public static final RitualShape LARGE = rows("....#######....", "...#.......#...", "..#.........#..",
            ".#...........#.", "#.............#", "#.............#", "#.............#", "#.............#",
            "#.............#", "#.............#", "#.............#", ".#...........#.",
            "..#.........#..", "...#.......#...", "....#######....");

    public RitualShape {
        if (marks == null || marks.isEmpty() || marks.size() > 256)
            throw new IllegalArgumentException("A ritual shape needs 1..256 support positions");
        marks = marks.stream().map(BlockPos::immutable).distinct().toList();
        if (marks.stream().anyMatch(pos -> pos.getY() != 0 || Math.abs((long) pos.getX()) > 32
                || Math.abs((long) pos.getZ()) > 32)) throw new IllegalArgumentException("Invalid ritual support plane");
    }

    public static RitualShape combine(RitualShape... shapes) {
        var positions = new ArrayList<BlockPos>();
        for (var shape : shapes) positions.addAll(shape.marks);
        return new RitualShape(positions);
    }

    private static RitualShape rows(String... rows) {
        int size = rows.length;
        var positions = new ArrayList<BlockPos>();
        for (int z = 0; z < size; z++) {
            if (rows[z].length() != size || size % 2 == 0) throw new IllegalArgumentException("Invalid circle");
            for (int x = 0; x < size; x++) if (rows[z].charAt(x) == '#')
                positions.add(new BlockPos(x - size / 2, 0, z - size / 2));
        }
        return new RitualShape(positions);
    }

    /** Never loads chunks. A missing chunk is not evidence of a broken structure. */
    public Match match(ServerLevel level, BlockPos anchor) {
        return RitualShapeCache.match(this, level, anchor);
    }
    /** Relative template lookup, shared by cached shapes and registered source spaces. */
    boolean containsOffset(int x, int z) { return marks.contains(new BlockPos(x, 0, z)); }
    Match inspect(ServerLevel level, BlockPos anchor) {
        return inspect(level, anchor, true);
    }
    /** Passive regional rules need loaded geometry, not a ticking-machine ticket. Caller owns invalidation. */
    public Match inspectLoaded(ServerLevel level, BlockPos anchor) { return inspect(level, anchor, false); }
    private Match inspect(ServerLevel level, BlockPos anchor, boolean ticking) {
        boolean unavailable = false;
        for (BlockPos offset : marks) {
            BlockPos pos = anchor.offset(offset);
            if (RitualChalk.chunk(level, pos) == null || ticking && !level.shouldTickBlocksAt(pos)) {
                unavailable = true;
                continue;
            }
            var mark = RitualChalk.mark(level, pos);
            var state = level.getBlockState(pos);
            if (mark == null || !mark.supported() || RitualChalk.surface(level, pos, state) < 0) return Match.INVALID;
            boolean engraved = RitualChalk.wood(state) && mark.medium() == 1;
            if (!engraved && (!level.getBlockState(pos.above()).getCollisionShape(level, pos.above()).isEmpty()
                    || !level.getFluidState(pos.above()).isEmpty())) return Match.INVALID;
        }
        return unavailable ? Match.UNLOADED : Match.VALID;
    }
}
