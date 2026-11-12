package dev.senluo.witchcraft.compat.thaumcraft;

import dev.tc4port.thaumcraft.api.event.ArcaneBoreEvent;
import dev.tc4port.thaumcraft.api.machine.ArcaneBoreApi;
import dev.tc4port.thaumcraft.api.machine.ArcaneBoreTarget;
import dev.tc4port.thaumcraft.api.machine.ArcaneBoreView;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.INBTSerializable;

/** Selects real targets; the native bore owns timing, energy, tools and presentation. */
final class BoreExcavation implements INBTSerializable<CompoundTag> {
    private final Runnable changed;
    BoreExcavation(Runnable changed) { this.changed = changed; }
    private long cursor;
    private MixedMine.Target target;
    private ArcaneBoreTarget request;
    private boolean attempted, committed;

    boolean owns(ArcaneBoreTarget current) { return request != null && current == request; }

    /** Outside the host's busy scope, before its next actual work update. */
    void before(ServerLevel level, BlockEntity bore, BoreRings.State rings, MixedMine samples) {
        if (request == null) return;
        var current = ArcaneBoreApi.requestedTarget(level, bore.getBlockPos()).orElse(null);
        if (!owns(current)) { clear(); return; }
        if (!(bore instanceof ArcaneBoreView view) || !eligible(level, bore, view)
                || !withinRange(bore, rings, view, target.rock())
                || !samples.accessible(request.level(), target)
                || !request.level().getBlockState(target.virtual()).equals(target.state())) {
            ArcaneBoreApi.cancelDig(level, bore.getBlockPos());
            clear();
        } else samples.maintain(bore, rings.displayedRadius(view));
    }

    void harvest(ArcaneBoreEvent.Harvest event, BlockEntity bore, BoreRings.State rings) {
        if (!matches(event)) return;
        var expected = request;
        var sampled = target;
        // Refresh can revoke this receipt while a different ritual still serves the same bore.
        if (!(bore instanceof ArcaneBoreView view) || !rings.permits(event.level(), bore, this)
                || request != expected || target != sampled
                || !withinRange(bore, rings, view, sampled.rock())
                || !sampled.rock().equals(tracePortal(event.level(), bore, sampled.rock()))) {
            event.setCanceled(true);
            return;
        }
        attempted = true;
    }

    void harvested(ArcaneBoreEvent.Harvested event, BlockEntity bore, MixedMine samples) {
        if (!matches(event)) return;
        samples.completed(bore, target);
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
                if (!committed) samples.completed(bore, target);
                advance();
            } else clear();
        }
        if (current != null || !idle || !(bore instanceof ArcaneBoreView view) || !eligible(level, bore, view)) return;
        int radius = rings.displayedRadius(view);
        long next = nextColumn(cursor, radius);
        if (next != cursor) { cursor = next; changed.run(); }
        int[] offset = column(cursor, radius);
        BlockPos rock = target == null
                ? portal(level, bore, bore.getBlockPos().offset(offset[0], 0, offset[1]), rings.depth()) : target.rock();
        if (rock == null) { advance(); return; }
        samples.maintain(bore, radius);
        ServerLevel mine = MixedMine.forHost(level);
        if (mine == null) return;
        if (target == null) {
            MixedMine.Probe probe = samples.next(mine, bore, rock);
            if (probe.waiting()) return;
            target = probe.target();
            if (target == null) { advance(); return; }
        }
        if (!samples.accessible(mine, target)) return;
        if (!withinRange(bore, rings, view, target.rock())
                || !target.rock().equals(tracePortal(level, bore, target.rock()))
                || !mine.getBlockState(target.virtual()).equals(target.state())) { clear(); return; }
        request = new ArcaneBoreTarget(mine, target.virtual(), beam(bore.getBlockPos(), target.rock()));
        if (!ArcaneBoreApi.dig(level, bore.getBlockPos(), request)) advance();
    }

    private void advance() { cursor++; clear(); changed.run(); }
    void cancel(BlockEntity bore) {
        if (bore != null && !bore.isRemoved() && bore.getLevel() instanceof ServerLevel level
                && owns(ArcaneBoreApi.requestedTarget(level, bore.getBlockPos()).orElse(null)))
            ArcaneBoreApi.cancelDig(level, bore.getBlockPos());
        clear();
    }
    private void clear() { target = null; request = null; attempted = committed = false; }

    static int[] column(long cursor, int radius) {
        long diameter = radius * 2L + 1;
        int x = (int) (Math.floorMod(cursor, diameter) - radius);
        int z = (int) (Math.floorMod(Math.floorDiv(cursor, diameter), diameter) - radius);
        return (long) x * x + (long) z * z <= (long) radius * radius ? new int[]{x, z} : null;
    }

    /** Skip only impossible lattice cells. Keep the existing persisted cursor and valid-column order. */
    static long nextColumn(long cursor, int radius) {
        long diameter = radius * 2L + 1;
        // Every row intersects the disk: at most the current row and the next are considered.
        for (int row = 0; row < 2; row++) {
            long x = Math.floorMod(cursor, diameter) - radius;
            long z = Math.floorMod(Math.floorDiv(cursor, diameter), diameter) - radius;
            long square = (long) radius * radius - z * z;
            long extent = (long) Math.sqrt(square);
            // Preserve the integer circle boundary despite floating-point sqrt rounding.
            while (extent * extent > square) extent--;
            while ((extent + 1) * (extent + 1) <= square) extent++;
            if (x < -extent) return cursor + (-extent - x);
            if (x <= extent) return cursor;
            cursor += radius - x + 1;
        }
        throw new IllegalStateException("A circular scan row must contain a valid column");
    }

    private static boolean withinRange(BlockEntity bore, BoreRings.State rings, ArcaneBoreView view, BlockPos rock) {
        long dx = rock.getX() - bore.getBlockPos().getX(), dz = rock.getZ() - bore.getBlockPos().getZ();
        int radius = rings.displayedRadius(view);
        return dx * dx + dz * dz <= (long) radius * radius
                && rock.getY() >= bore.getBlockPos().getY() - 1 - rings.depth();
    }

    private static Direction direction(BlockState state, String name, Direction fallback) {
        var property = state.getBlock().getStateDefinition().getProperty(name);
        return property instanceof DirectionProperty direction ? state.getValue(direction) : fallback;
    }

    private static boolean eligible(ServerLevel level, BlockEntity bore, ArcaneBoreView view) {
        BlockPos pos = bore.getBlockPos();
        BlockPos base = pos.relative(direction(bore.getBlockState(), "base_direction", Direction.UP).getOpposite());
        return !bore.isRemoved() && level.getBlockEntity(pos) == bore && view.hasFocus() && view.hasPickaxe()
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

    @Override public CompoundTag serializeNBT(HolderLookup.Provider lookup) {
        CompoundTag tag = new CompoundTag(); tag.putLong("Cursor", cursor); return tag;
    }

    @Override public void deserializeNBT(HolderLookup.Provider lookup, CompoundTag tag) {
        cursor = tag.getLong("Cursor");
        // Native requests are transient. MixedMine keeps progress; old prepaid CV is retired by agreement.
        clear();
    }
}
