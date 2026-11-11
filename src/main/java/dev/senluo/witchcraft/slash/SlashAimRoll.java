package dev.senluo.witchcraft.slash;

import dev.senluo.witchcraft.motion.MotionPath;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Bounded, read-only blade-roll choice. Never redirects the route or vetoes emission. */
final class SlashAimRoll {
    private static final int MAX_PIECES = 128, MAX_CELLS = 2048, MAX_BOXES = 1024;
    private static final int MAX_VISITS = 16384, MAX_CHECKS = 8192;
    private static final double IMPROVEMENT = .05;
    private record Piece(Vec3 from, Vec3 to, double before, double after) {}
    private record Clearance(double distance, boolean known) {}
    private static final class Query {
        final Map<BlockPos, List<AABB>> cells = new HashMap<>();
        int boxes, visits = MAX_VISITS, checks = MAX_CHECKS;
    }
    private SlashAimRoll() {}

    static float select(SlashEntity slash, ServerPlayer player, MotionPath path, Vec3 origin,
                        Vec3 target, SlashShape shape, float original) {
        double reach = Math.min(shape.range(), path.curved() ? path.arrivalDistance()
                : target.subtract(origin).dot(path.direction(0)));
        if (!Double.isFinite(reach) || reach < .1) return original;
        List<Piece> pieces = new ArrayList<>();
        for (double before = 0; before < reach;) {
            if (pieces.size() >= MAX_PIECES) return original;
            // Preserve every authoritative curve vertex; avoid one huge box around the entire flight.
            double after = path.nextDistance(before, Math.min(2, reach - before));
            if (!(after > before)) return original;
            pieces.add(new Piece(origin.add(path.at(before)), origin.add(path.at(after)), before, after));
            before = after;
        }
        Query query = new Query();
        Vec3 axis = path.direction(0);
        Clearance first = clearance(slash, player, pieces, shape, SlashGeometry.blade(axis, original), query, reach);
        if (!first.known || first.distance >= reach) return original;
        double best = first.distance;
        float chosen = original;
        var candidates = new ArrayList<Float>();
        // Explicit screen axes plus nearby rolls, visited in order of deviation from the user's gesture.
        for (float roll : new float[]{0, (float)(Math.PI / 2), (float)(Math.PI / 4), (float)(-Math.PI / 4)})
            add(candidates, roll, original);
        for (int step : new int[]{1, 2, 4, 6, 8}) {
            add(candidates, original + (float)(step * Math.PI / 16), original);
            add(candidates, original - (float)(step * Math.PI / 16), original);
        }
        candidates.sort(Comparator.comparingDouble(roll -> deviation(roll, original)));
        for (float roll : candidates) {
            Clearance result = clearance(slash, player, pieces, shape, SlashGeometry.blade(axis, roll), query, reach);
            // Incomplete discovery is not an obstacle. Keep only the best fully evaluated result so far.
            if (!result.known) return chosen;
            if (result.distance >= reach) return roll;
            if (result.distance > best + IMPROVEMENT) { best = result.distance; chosen = roll; }
        }
        return chosen;
    }

    private static double deviation(float left, float right) {
        return Math.abs(Math.IEEEremainder((double)left - right, Math.PI));
    }
    private static void add(List<Float> candidates, float roll, float original) {
        if (deviation(roll, original) < 1.0E-6) return;
        for (float previous : candidates) if (deviation(roll, previous) < 1.0E-6) return;
        candidates.add(roll);
    }

    private static Clearance clearance(SlashEntity slash, ServerPlayer player, List<Piece> pieces,
                                       SlashShape shape, Vec3 blade, Query query, double reach) {
        for (Piece piece : pieces) {
            var sweep = new SlashGeometry.Sweep(piece.from, piece.to, blade,
                    shape.halfLength(piece.before), shape.halfLength(piece.after));
            AABB envelope = sweep.bounds();
            if (!SlashCollision.bounded(envelope) || !SlashCollision.loaded(player.serverLevel(), envelope))
                return new Clearance(0, false);
            double first = Double.POSITIVE_INFINITY;
            for (BlockPos cursor : BlockPos.betweenClosed(BlockPos.containing(envelope.minX, envelope.minY, envelope.minZ),
                    BlockPos.containing(envelope.maxX, envelope.maxY, envelope.maxZ))) {
                if (--query.visits < 0) return new Clearance(0, false);
                List<AABB> boxes = query.cells.get(cursor);
                if (boxes == null) {
                    if (query.cells.size() >= MAX_CELLS) return new Clearance(0, false);
                    List<AABB> local = SlashBlockPassage.boxes(slash, player, cursor);
                    if (local.size() > 64 || query.boxes + local.size() > MAX_BOXES) return new Clearance(0, false);
                    query.boxes += local.size();
                    boxes = local.stream().map(box -> box.move(cursor)).toList();
                    query.cells.put(cursor.immutable(), boxes);
                }
                for (AABB box : boxes) {
                    if (--query.checks < 0) return new Clearance(0, false);
                    if (!envelope.intersects(box)) continue;
                    var hit = sweep.contact(box);
                    if (hit != null) first = Math.min(first, hit.fraction());
                    if (first == 0) return new Clearance(piece.before, true);
                }
            }
            if (Double.isFinite(first)) return new Clearance(piece.before + (piece.after - piece.before) * first, true);
        }
        return new Clearance(reach, true);
    }
}
