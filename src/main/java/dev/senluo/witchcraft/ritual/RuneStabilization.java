package dev.senluo.witchcraft.ritual;

import java.util.*;
import java.util.function.BooleanSupplier;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;

/** Shared rune-support observation. Consumers decide whether stabilization is required or grants a bonus. */
public final class RuneStabilization {
    public enum Result { STABILIZED, UNSTABILIZED, UNLOADED }

    /** Material eligibility belongs to its provider; rune position rules belong here. */
    @FunctionalInterface
    public interface Stabilizer {
        boolean matches(ServerLevel level, BlockPos pos, BlockState state);
        /** Unknown providers stay live unless they supply a complete dynamic dependency proof. */
        default Evidence observe(ServerLevel level, BlockPos pos, BlockState state) {
            return new Evidence(matches(level, pos, state), () -> false);
        }
    }

    public record Evidence(boolean matches, BooleanSupplier current) {
        private static final BooleanSupplier STATIC = () -> true;
        public static Evidence material(boolean matches) { return new Evidence(matches, STATIC); }
    }
    private record Key(BlockPos anchor, RitualShape shape, Stabilizer provider) {
        boolean uses(BlockPos pos) {
            int y = anchor.getY() - pos.getY();
            return (y == 0 || y == 1) && shape.containsOffset(pos.getX() - anchor.getX(), pos.getZ() - anchor.getZ());
        }
    }
    private record Entry(Result result, Set<Long> chunks, Set<BooleanSupplier> proofs) {
        boolean current(ServerLevel level) {
            for (long chunk : chunks) if (level.getChunkSource().getChunkNow(ChunkPos.getX(chunk), ChunkPos.getZ(chunk)) == null) return false;
            for (var proof : proofs) if (!proof.getAsBoolean()) return false;
            return true;
        }
    }
    private static final class Index {
        final Map<Key, Entry> entries = new LinkedHashMap<>(32, .75F, true);
        final Map<Long, Set<Key>> watchers = new HashMap<>();
        final Set<Key> computing = new HashSet<>();
        long revision;
        void remove(Key key) {
            Entry old = entries.remove(key);
            if (old == null) return;
            for (long chunk : old.chunks) {
                var keys = watchers.get(chunk);
                if (keys != null && keys.remove(key) && keys.isEmpty()) watchers.remove(chunk);
            }
        }
    }
    private static final Map<ServerLevel, Index> LEVELS = new WeakHashMap<>();

    private RuneStabilization() {}

    /** Input is a known rune's marked support block, not the physical chalk layer above it. */
    public static Result at(ServerLevel level, BlockPos support, Stabilizer stabilizer) {
        if (!level.hasChunkAt(support)) return Result.UNLOADED;
        if (stabilizer.matches(level, support, level.getBlockState(support))) return Result.STABILIZED;
        BlockPos below = support.below();
        return stabilizer.matches(level, below, level.getBlockState(below))
                ? Result.STABILIZED : Result.UNSTABILIZED;
    }

    /** Aggregate the rune observations only; RitualShape separately owns complete, drawable geometry. */
    public static Result all(ServerLevel level, BlockPos anchor, RitualShape shape, Stabilizer stabilizer) {
        Index index = LEVELS.computeIfAbsent(level, unused -> new Index());
        Key key = new Key(anchor.immutable(), shape, stabilizer);
        Entry old = index.entries.get(key);
        if (old != null && old.current(level)) return old.result;
        if (!index.computing.add(key)) return Result.UNLOADED;
        index.remove(key);
        long revision = index.revision;
        try {
            Set<Long> chunks = new HashSet<>();
            Set<BooleanSupplier> proofs = new HashSet<>();
            Result result = Result.STABILIZED;
            for (BlockPos offset : shape.marks()) {
                BlockPos pos = anchor.offset(offset);
                chunks.add(ChunkPos.asLong(pos));
                if (!level.hasChunkAt(pos)) return Result.UNLOADED;
                Evidence top = stabilizer.observe(level, pos, level.getBlockState(pos));
                proofs.add(top.current);
                if (top.matches) continue;
                pos = pos.below();
                Evidence below = stabilizer.observe(level, pos, level.getBlockState(pos));
                proofs.add(below.current);
                if (!below.matches) { result = Result.UNSTABILIZED; break; }
            }
            var entry = new Entry(result, Set.copyOf(chunks), Set.copyOf(proofs));
            dev.senluo.witchcraft.world.ChunkActivity.fresh(level);
            if (revision != index.revision || LEVELS.get(level) != index) return Result.UNLOADED;
            // Uncached providers still answer this query; only reusable evidence enters the cache.
            if (entry.current(level)) {
                if (index.entries.size() >= 1024) index.remove(index.entries.keySet().iterator().next());
                index.entries.put(key, entry);
                for (long chunk : chunks) index.watchers.computeIfAbsent(chunk, unused -> new HashSet<>()).add(key);
            }
            return result;
        } finally { index.computing.remove(key); }
    }
    static void changed(ServerLevel level, BlockPos pos) {
        Index index = LEVELS.get(level);
        if (index == null) return;
        index.revision++;
        var keys = index.watchers.get(ChunkPos.asLong(pos));
        if (keys != null) for (Key key : List.copyOf(keys)) if (key.uses(pos)) index.remove(key);
    }
    static void changed(ServerLevel level, long chunk) {
        Index index = LEVELS.get(level);
        if (index != null) {
            index.revision++;
            var keys = index.watchers.get(chunk);
            if (keys != null) for (Key key : List.copyOf(keys)) index.remove(key);
        }
    }
    static void clear(ServerLevel level) { LEVELS.remove(level); }
    static void clear() { LEVELS.clear(); }
}
