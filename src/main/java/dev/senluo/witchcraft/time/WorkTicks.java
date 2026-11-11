package dev.senluo.witchcraft.time;

import java.util.IdentityHashMap;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.TickingBlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/** One receipt per real native callback, independent of its scheduler or frequency. */
public final class WorkTicks {
    private static final ThreadLocal<IdentityHashMap<BlockEntity, Boolean>> ACTIVE = ThreadLocal.withInitial(IdentityHashMap::new);
    @FunctionalInterface public interface Effect {
        void afterNative(ServerLevel level, TickingBlockEntity ticker, BlockEntity entity, Runnable tick);
    }
    private static final java.util.List<Effect> EFFECTS = new java.util.ArrayList<>();
    public static void registerEffect(Effect effect) { EFFECTS.add(java.util.Objects.requireNonNull(effect)); }
    /** The platform callback knows no ritual types; registered effects reuse the same granted work. */
    public static void scheduled(ServerLevel level, TickingBlockEntity ticker, Runnable nativeTick) {
        var entity = scheduledTarget(level, ticker);
        if (entity == null) { nativeTick.run(); return; }
        if (!run(level, entity, nativeTick) || ExtraTicks.active()) return;
        for (var effect : EFFECTS) {
            if (!isScheduledTarget(level, ticker, entity) || !level.shouldTickBlocksAt(entity.getBlockPos())) break;
            effect.afterNative(level, ticker, entity, () -> run(level, entity, nativeTick));
        }
    }
    private WorkTicks() {}
    /** Resolve once before the native callback; opaque/sleeping tickers remain scheduler-owned. */
    @Nullable
    public static BlockEntity scheduledTarget(ServerLevel level, TickingBlockEntity ticker) {
        if (ticker.isRemoved()) return null;
        BlockPos pos = ticker.getPos();
        if (pos == null || !level.hasChunkAt(pos)) return null;
        BlockEntity entity = level.getBlockEntity(pos);
        return entity != null && !entity.isRemoved() && entity.getLevel() == level
                && pos.equals(entity.getBlockPos()) ? entity : null;
    }
    /** Native callbacks can sleep, move, remove or replace their target synchronously. */
    public static boolean isScheduledTarget(ServerLevel level, TickingBlockEntity ticker, BlockEntity expected) {
        if (expected == null || expected.isRemoved() || expected.getLevel() != level || ticker.isRemoved()) return false;
        BlockPos pos = expected.getBlockPos();
        return pos.equals(ticker.getPos()) && level.hasChunkAt(pos)
                && level.getBlockEntity(pos) == expected;
    }
    /** An adapter's explicit extra opportunity inside the same entity's original callback. */
    public static boolean additional(ServerLevel level, BlockEntity entity, Runnable nativeTick) {
        IdentityHashMap<BlockEntity, Boolean> previous = ACTIVE.get();
        Boolean nested = previous.remove(entity);
        try { return run(level, entity, nativeTick); }
        finally {
            if (nested != null) previous.put(entity, nested);
        }
    }
    public static <T extends BlockEntity> BlockEntityTicker<T> wrap(BlockEntityTicker<T> ticker) {
        return ticker == null || ticker instanceof Guarded<?> ? ticker : new Guarded<>(ticker);
    }
    private record Guarded<T extends BlockEntity>(BlockEntityTicker<T> delegate) implements BlockEntityTicker<T> {
        public void tick(Level level, BlockPos pos, BlockState state, T entity) {
            if (level instanceof ServerLevel server)
                run(server, entity, () -> WorkSources.tick(server, entity,
                        () -> delegate.tick(level, pos, entity.getBlockState(), entity)));
            else delegate.tick(level, pos, state, entity);
        }
    }
    /** False means absorbed. Nested wrappers around the same call are not additional work. */
    public static boolean run(ServerLevel level, BlockEntity entity, Runnable nativeTick) {
        if (entity == null) { nativeTick.run(); return true; }
        IdentityHashMap<BlockEntity, Boolean> active = ACTIVE.get();
        if (active.containsKey(entity)) { nativeTick.run(); return true; }
        if (entity.isRemoved()) return false;
        TemporalWorld world = TemporalWorld.get(level);
        if (!world.hasDomain(entity.getBlockPos())) { nativeTick.run(); return true; }
        if (!level.hasChunkAt(entity.getBlockPos())
                || level.getBlockEntity(entity.getBlockPos()) != entity) return false;
        active.put(entity, Boolean.TRUE);
        try {
            AccelerationSources.observe(level, entity);
            boolean absorbed = world.absorbWork(entity.getBlockPos());
            if (!absorbed || WorkSources.prepareSuppressedProgress(entity)) nativeTick.run();
            return !absorbed;
        } finally {
            active.remove(entity);
        }
    }
}
