package dev.senluo.witchcraft.time;

import dev.senluo.witchcraft.compat.CropGrowth;
import java.lang.ref.WeakReference;
import java.util.*;
import java.util.function.BiPredicate;
import java.util.function.BooleanSupplier;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.TickingBlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/** Sources maintain target routes; only selected targets enter work accounting. */
public final class WorkTicks {
    private static final int DOMAIN = 1, CONTEXT = 2;
    /** Transient binding metadata, never saved or synchronized. -1 means unresolved/invalidated. */
    public interface Target {
        int senluo$workRoutes();
        void senluo$workRoutes(int routes);
    }
    @FunctionalInterface public interface Effect {
        void afterNative(ServerLevel level, TickingBlockEntity ticker, BlockEntity entity, Runnable tick);
    }
    private record EffectRoute(BiPredicate<ServerLevel, BlockEntity> affected, Effect effect) {}
    private static final List<EffectRoute> EFFECTS = new ArrayList<>();
    private static final ThreadLocal<IdentityHashMap<BlockEntity, Boolean>> ACTIVE = ThreadLocal.withInitial(IdentityHashMap::new);
    // Neither side may retain a removed scheduler or its level through this binding lookup.
    private static final Map<TickingBlockEntity, WeakReference<Scheduled>> BINDINGS = new WeakHashMap<>();
    private WorkTicks() {}

    public static void registerEffect(BiPredicate<ServerLevel, BlockEntity> affected, Effect effect) {
        if (EFFECTS.size() >= 29) throw new IllegalStateException("Too many work routes");
        EFFECTS.add(new EffectRoute(Objects.requireNonNull(affected), Objects.requireNonNull(effect)));
    }
    /** Binding and source changes invalidate; the next actual call resolves the route once. */
    private static void refresh(BlockEntity entity) {
        if (!(entity.getLevel() instanceof ServerLevel level)) return;
        int routes = 0;
        if (!entity.isRemoved()) {
            if (TemporalWorld.get(level).hasDomain(entity.getBlockPos())) routes |= DOMAIN;
            if (WorkSources.nativeContext(entity)) routes |= CONTEXT;
            for (int i = 0; i < EFFECTS.size(); i++)
                if (EFFECTS.get(i).affected.test(level, entity)) routes |= 1 << (i + 2);
        }
        ((Target) entity).senluo$workRoutes(routes);
    }
    public static void invalidate(BlockEntity entity) { ((Target) entity).senluo$workRoutes(-1); }
    public static void invalidateChunk(ServerLevel level, long chunk) {
        var loaded = level.getChunkSource().getChunkNow(ChunkPos.getX(chunk), ChunkPos.getZ(chunk));
        if (loaded != null) for (var entity : loaded.getBlockEntities().values()) invalidate(entity);
    }
    public static void invalidateAt(ServerLevel level, BlockPos pos) {
        var loaded = level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
        var entity = loaded == null ? null : loaded.getBlockEntities().get(pos);
        if (entity != null) invalidate(entity);
    }
    private static int routes(BlockEntity entity) {
        var target = (Target) entity;
        if (target.senluo$workRoutes() < 0) refresh(entity);
        return target.senluo$workRoutes();
    }
    /** The chunk keeps its original rebindable ticker for sleeping and external accelerators. */
    public static TickingBlockEntity bind(Level level, TickingBlockEntity delegate, BlockEntity entity) {
        if (!(level instanceof ServerLevel server)) return delegate;
        invalidate(entity);
        var scheduled = new Scheduled(server, delegate, entity);
        BINDINGS.put(delegate, new WeakReference<>(scheduled));
        return scheduled;
    }
    public static void rebound(TickingBlockEntity delegate, BlockEntity entity) {
        if (!(entity.getLevel() instanceof ServerLevel)) return;
        invalidate(entity);
        var reference = BINDINGS.get(delegate);
        var scheduled = reference == null ? null : reference.get();
        if (scheduled != null) scheduled.entity = entity;
    }
    private static final class Scheduled implements TickingBlockEntity {
        private final ServerLevel level;
        private final TickingBlockEntity delegate;
        private BlockEntity entity;
        Scheduled(ServerLevel level, TickingBlockEntity delegate, BlockEntity entity) {
            this.level = level; this.delegate = delegate; this.entity = entity;
        }
        public boolean isRemoved() { return delegate.isRemoved(); }
        public BlockPos getPos() { return delegate.getPos(); }
        public String getType() { return delegate.getType(); }
        public void tick() {
            // After lazy resolution, unrelated machines need no discovery or per-call closures.
            if (routes(entity) == 0) { delegate.tick(); return; }
            BlockEntity current = entity;
            if (!run(level, current, delegate::tick) || ExtraTicks.active()) return;
            int selected = routes(current);
            for (int i = 0; i < EFFECTS.size(); i++) if ((selected & 1 << (i + 2)) != 0) {
                if (!isScheduledTarget(level, delegate, current) || !level.shouldTickBlocksAt(current.getBlockPos())) break;
                EFFECTS.get(i).effect.afterNative(level, delegate, current, () -> run(level, current, delegate::tick));
            }
        }
    }
    /** Native callbacks can sleep, move, remove or replace their target synchronously. */
    public static boolean isScheduledTarget(ServerLevel level, TickingBlockEntity ticker, BlockEntity expected) {
        if (expected == null || expected.isRemoved() || expected.getLevel() != level || ticker.isRemoved()) return false;
        BlockPos pos = expected.getBlockPos();
        return pos.equals(ticker.getPos()) && level.hasChunkAt(pos) && level.getBlockEntity(pos) == expected;
    }
    /** Consume granted consecutive opportunities, preserving native fallback and per-step absorption. */
    public static void advance(ServerLevel level, TickingBlockEntity ticker, BlockEntity entity,
                               int steps, BooleanSupplier eligible, Runnable nativeTick) {
        while (steps > 0 && isScheduledTarget(level, ticker, entity)
                && level.shouldTickBlocksAt(entity.getBlockPos()) && eligible.getAsBoolean()) {
            if (steps > 1 && entity.getType().isValid(entity.getBlockState()) && ((routes(entity) & DOMAIN) == 0
                    || !CropGrowth.timed(entity.getBlockState()) && WorkSources.price(level, entity) == 0)) {
                Runnable batch = WorkSources.batch(level, entity, steps);
                if (batch != null) {
                    if (isScheduledTarget(level, ticker, entity) && level.shouldTickBlocksAt(entity.getBlockPos())
                            && eligible.getAsBoolean()) batch.run();
                    return;
                }
            }
            nativeTick.run();
            steps--;
        }
    }
    /** An adapter's explicit extra opportunity inside the same entity's original callback. */
    public static boolean additional(ServerLevel level, BlockEntity entity, Runnable nativeTick) {
        IdentityHashMap<BlockEntity, Boolean> previous = ACTIVE.get();
        Boolean nested = previous.remove(entity);
        try { return run(level, entity, nativeTick); }
        finally { if (nested != null) previous.put(entity, nested); }
    }
    public static <T extends BlockEntity> BlockEntityTicker<T> wrap(BlockEntityTicker<T> ticker) {
        return ticker == null || ticker instanceof Guarded<?> ? ticker : new Guarded<>(ticker);
    }
    private record Guarded<T extends BlockEntity>(BlockEntityTicker<T> delegate) implements BlockEntityTicker<T> {
        public void tick(Level level, BlockPos pos, BlockState state, T entity) {
            if (!(level instanceof ServerLevel server)) { delegate.tick(level, pos, state, entity); return; }
            if (entity.isRemoved()) return;
            if (routes(entity) == 0) { delegate.tick(level, pos, entity.getBlockState(), entity); return; }
            run(server, entity, () -> WorkSources.tick(server, entity,
                    () -> delegate.tick(level, pos, entity.getBlockState(), entity)));
        }
    }
    /** False means absorbed. Nested wrappers around the same call are not additional work. */
    public static boolean run(ServerLevel level, BlockEntity entity, Runnable nativeTick) {
        if (entity == null) { nativeTick.run(); return true; }
        if (entity.isRemoved()) return false;
        int selected = routes(entity);
        if (selected == 0) { nativeTick.run(); return true; }
        IdentityHashMap<BlockEntity, Boolean> active = ACTIVE.get();
        if (active.containsKey(entity)) { nativeTick.run(); return true; }
        if ((selected & DOMAIN) != 0 && (!level.hasChunkAt(entity.getBlockPos())
                || level.getBlockEntity(entity.getBlockPos()) != entity)) return false;
        active.put(entity, Boolean.TRUE);
        try {
            boolean absorbed = false;
            if ((selected & DOMAIN) != 0) {
                AccelerationSources.observe(level, entity);
                absorbed = TemporalWorld.get(level).absorbWork(entity.getBlockPos());
            }
            if (!absorbed || WorkSources.prepareSuppressedProgress(entity)) nativeTick.run();
            return !absorbed;
        } finally { active.remove(entity); }
    }
}
