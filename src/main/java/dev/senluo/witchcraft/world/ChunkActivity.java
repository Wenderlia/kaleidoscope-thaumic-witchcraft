package dev.senluo.witchcraft.world;

import it.unimi.dsi.fastutil.longs.Long2BooleanOpenHashMap;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;

/** Shared reads within one synchronous observation, never a cross-tick permission lease. */
public final class ChunkActivity {
    private record Pass(ServerLevel level, Long2BooleanOpenHashMap chunks) {}
    private static final ThreadLocal<Pass> CURRENT = new ThreadLocal<>();

    public static <T> T within(ServerLevel level, Supplier<T> query) {
        Pass previous = CURRENT.get();
        if (previous != null && previous.level == level) return query.get();
        CURRENT.set(new Pass(level, new Long2BooleanOpenHashMap()));
        try { return query.get(); }
        finally { if (previous == null) CURRENT.remove(); else CURRENT.set(previous); }
    }

    /** Discard observations across foreign callbacks and before final validation. */
    public static void fresh(ServerLevel level) {
        Pass pass = CURRENT.get();
        if (pass != null && pass.level == level) pass.chunks.clear();
    }

    public static boolean active(ServerLevel level, BlockPos pos) { return active(level, ChunkPos.asLong(pos)); }
    public static boolean active(ServerLevel level, long chunk) {
        Pass pass = CURRENT.get();
        if (pass == null || pass.level != level) return actual(level, chunk);
        if (!pass.chunks.containsKey(chunk)) pass.chunks.put(chunk, actual(level, chunk));
        return pass.chunks.get(chunk);
    }
    public static boolean actual(ServerLevel level, long chunk) {
        return level.getChunkSource().getChunkNow(ChunkPos.getX(chunk), ChunkPos.getZ(chunk)) != null
                && level.shouldTickBlocksAt(chunk);
    }
    private ChunkActivity() {}
}
