package dev.senluo.witchcraft.compat.thaumcraft;

import dev.tc4port.thaumcraft.api.event.ArcaneBoreEvent;
import dev.tc4port.thaumcraft.api.machine.ArcaneBoreApi;
import dev.tc4port.thaumcraft.api.machine.ArcaneBoreTarget;
import dev.tc4port.thaumcraft.api.machine.ArcaneBoreView;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/** Selects real targets; the native bore owns timing, energy, tools and presentation. */
final class BoreExcavation {
    private long portalCursor;
    private BlockPos entrance;
    private MixedMine.Target target;
    private ArcaneBoreTarget request;
    private boolean attempted, committed;
    private boolean nativeCall, cancelPending;

    boolean owns(ArcaneBoreTarget current) { return request != null && current == request; }

    /** Outside the host's busy scope, before its next actual work update. */
    void before(ServerLevel level, BlockEntity bore, BoreRings.State rings, MixedMine samples) {
        if (request == null) return;
        var current = ArcaneBoreApi.requestedTarget(level, bore.getBlockPos()).orElse(null);
        if (!owns(current)) { clear(); return; }
        if (!(bore instanceof ArcaneBoreView view) || !eligible(level, bore, view)
                || !withinRange(bore, rings, view, entrance)
                || !target.withinRadius(rings.displayedRadius(view))
                || !samples.current(target)
                || !samples.accessible(request.level(), target)
                || !request.level().getBlockState(target.virtual()).equals(target.state())) {
            cancel(bore);
        } else samples.maintain(bore, rings.displayedRadius(view));
    }

    void harvest(ArcaneBoreEvent.Harvest event, BlockEntity bore, BoreRings.State rings, MixedMine samples) {
        if (!matches(event)) return;
        var expected = request;
        var sampled = target;
        // Refresh can revoke this receipt while a different ritual still serves the same bore.
        if (cancelPending || !(bore instanceof ArcaneBoreView view) || !rings.permits(event.level(), bore, this)
                || request != expected || target != sampled || !samples.current(sampled)
                || !withinRange(bore, rings, view, entrance)
                || !sampled.withinRadius(rings.displayedRadius(view))
                || !entrance.equals(tracePortal(event.level(), bore, entrance))) {
            event.setCanceled(true);
            return;
        }
        samples.started(sampled);
        attempted = true;
    }

    void harvested(ArcaneBoreEvent.Harvested event, BlockEntity bore, MixedMine samples) {
        if (!matches(event)) return;
        boolean produced = event.drops().stream().anyMatch(stack -> !stack.isEmpty())
                || event.vacuumed().stream().anyMatch(stack -> !stack.isEmpty());
        samples.completed(target, produced);
        committed = true;
    }

    void vacuum(ArcaneBoreEvent.Vacuum event) {
        if (matches(event)) BoreCarts.collect(event.targetLevel(), target);
    }

    private boolean matches(ArcaneBoreEvent event) {
        return request != null && event.targetLevel() == request.level()
                && event.target().equals(request.position()) && event.beamTarget().equals(request.beamPosition())
                && owns(ArcaneBoreApi.requestedTarget(event.level(), event.bore()).orElse(null));
    }

    void after(ServerLevel level, BlockEntity bore, BoreRings.State rings, MixedMine samples, boolean idle) {
        var current = ArcaneBoreApi.requestedTarget(level, bore.getBlockPos()).orElse(null);
        if (request != null) {
            if (owns(current)) return;
            // Refused attempts move on; cancellation/replacement alone preserves the column.
            if (attempted) {
                if (!committed) samples.completed(target);
                samples.advance(bore);
                clear();
            } else clear();
        }
        if (current != null || !idle || !(bore instanceof ArcaneBoreView view) || !eligible(level, bore, view)) return;
        int radius = rings.displayedRadius(view);
        if (target != null && (!samples.current(target) || !target.withinRadius(radius))) clear();
        if (entrance != null && (!withinRange(bore, rings, view, entrance)
                || !entrance.equals(tracePortal(level, bore, entrance)))) entrance = null;
        if (entrance == null) {
            portalCursor = MixedMine.nextColumn(portalCursor, radius);
            int[] offset = MixedMine.column(portalCursor++, radius);
            entrance = portal(level, bore, bore.getBlockPos().offset(offset[0], 0, offset[1]), rings.depth());
            if (entrance == null) return;
        }
        ServerLevel mine = samples.level(level);
        if (mine == null) return;
        samples.maintain(bore, radius);
        if (!rings.permits(level, bore, this)) { clear(); return; }
        if (target == null) {
            MixedMine.Probe probe = samples.next(mine, bore, radius);
            target = probe.target();
            if (target == null) {
                // Try other virtual columns while retaining the real entrance and this column's progress.
                if (probe.state() != MixedMine.Supply.UNAVAILABLE) samples.advance(bore);
                return;
            }
        }
        if (!samples.accessible(mine, target)) return;
        if (!withinRange(bore, rings, view, entrance)
                || !entrance.equals(tracePortal(level, bore, entrance))
                || !mine.getBlockState(target.virtual()).equals(target.state())) { clear(); return; }
        if (!rings.permits(level, bore, this) || !samples.current(target)
                || !target.withinRadius(rings.displayedRadius(view))) { clear(); return; }
        if (!submit(level, bore, mine, target, entrance)) samples.advance(bore);
    }

    /** Own the exact receipt before invoking the host's synchronous Target callbacks. */
    boolean submit(ServerLevel level, BlockEntity bore, ServerLevel mine, MixedMine.Target sampled, BlockPos portal) {
        if (target != null && target != sampled) clear();
        target = sampled;
        entrance = portal;
        request = new ArcaneBoreTarget(mine, sampled.virtual(), beam(bore.getBlockPos(), portal));
        boolean accepted;
        beginNativeCall();
        try {
            accepted = ArcaneBoreApi.dig(level, bore.getBlockPos(), request);
        } finally {
            endNativeCall(bore);
        }
        if (!accepted) clear();
        return accepted;
    }

    void beginNativeCall() { nativeCall = true; }
    void endNativeCall(BlockEntity bore) {
        nativeCall = false;
        retryCancellation(bore);
    }
    boolean cancel(BlockEntity bore) {
        cancelPending = true;
        return retryCancellation(bore);
    }

    /** A refused cancellation is still pending, including inside another caller's native dig(). */
    boolean retryCancellation(BlockEntity bore) {
        if (!cancelPending) return true;
        if (nativeCall) return false;
        if (bore != null && !bore.isRemoved() && bore.getLevel() instanceof ServerLevel level
                && level.hasChunkAt(bore.getBlockPos()) && level.getBlockEntity(bore.getBlockPos()) == bore
                && owns(ArcaneBoreApi.requestedTarget(level, bore.getBlockPos()).orElse(null))) {
            if (!ArcaneBoreApi.cancelDig(level, bore.getBlockPos())
                    && owns(ArcaneBoreApi.requestedTarget(level, bore.getBlockPos()).orElse(null))) return false;
        }
        clear();
        return true;
    }
    private void clear() {
        if (target != null) target.close();
        target = null; request = null; attempted = committed = cancelPending = false;
    }

    private static boolean withinRange(BlockEntity bore, BoreRings.State rings, ArcaneBoreView view, BlockPos rock) {
        if (rock == null) return false;
        long dx = rock.getX() - bore.getBlockPos().getX(), dz = rock.getZ() - bore.getBlockPos().getZ();
        int radius = rings.displayedRadius(view);
        return dx * dx + dz * dz <= (long) radius * radius
                && rock.getY() >= bore.getBlockPos().getY() - 1 - rings.depth();
    }

    private static Direction direction(BlockState state, String name, Direction fallback) {
        var property = state.getBlock().getStateDefinition().getProperty(name);
        return property instanceof DirectionProperty direction ? state.getValue(direction) : fallback;
    }

    static boolean eligible(ServerLevel level, BlockEntity bore, ArcaneBoreView view) {
        BlockPos pos = bore.getBlockPos();
        BlockPos base = pos.relative(direction(bore.getBlockState(), "base_direction", Direction.UP).getOpposite());
        return !bore.isRemoved() && level.hasChunkAt(pos) && level.shouldTickBlocksAt(pos)
                && level.getBlockEntity(pos) == bore && view.hasFocus() && view.hasPickaxe()
                && !view.isPickaxeBroken() && direction(bore.getBlockState(), "facing", Direction.UP) == Direction.DOWN
                && level.hasChunksAt(pos.offset(-3, 0, -3), pos.offset(3, 0, 3))
                && (level.hasNeighborSignal(pos) || level.hasNeighborSignal(base));
    }

    private static BlockPos portal(ServerLevel level, BlockEntity bore, BlockPos column, int depth) {
        BlockPos start = bore.getBlockPos().below();
        return tracePortal(level, bore, new BlockPos(column.getX(),
                Math.max(level.getMinBuildHeight(), start.getY() - depth), column.getZ()));
    }

    private static BlockPos tracePortal(ServerLevel level, BlockEntity bore, BlockPos end) {
        BlockPos start = bore.getBlockPos().below();
        if (!level.hasChunksAt(new BlockPos(Math.min(start.getX(), end.getX()), end.getY(), Math.min(start.getZ(), end.getZ())),
                new BlockPos(Math.max(start.getX(), end.getX()), start.getY(), Math.max(start.getZ(), end.getZ())))) return null;
        var hit = level.clip(new ClipContext(Vec3.atCenterOf(start), Vec3.atCenterOf(end),
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE,
                net.neoforged.neoforge.common.util.FakePlayerFactory.get(level, BoreCarts.ACTOR)));
        if (hit.getType() == HitResult.Type.BLOCK)
            return level.getBlockState(hit.getBlockPos()).is(Blocks.BEDROCK) ? hit.getBlockPos() : null;
        return end.getY() == level.getMinBuildHeight() ? end : null;
    }

    /** Native presentation has a spherical path limit; sampling keeps its vertical depth. */
    private static BlockPos beam(BlockPos bore, BlockPos rock) {
        double length = Math.sqrt(bore.distSqr(rock));
        if (length <= ArcaneBoreApi.MAX_BEAM_LENGTH) return rock;
        Vec3 delta = Vec3.atLowerCornerOf(rock.subtract(bore))
                .scale((ArcaneBoreApi.MAX_BEAM_LENGTH - 1) / length);
        return bore.offset((int) delta.x, (int) delta.y, (int) delta.z);
    }

}
