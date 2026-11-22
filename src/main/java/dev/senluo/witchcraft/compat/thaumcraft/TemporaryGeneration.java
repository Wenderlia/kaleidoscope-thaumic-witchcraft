package dev.senluo.witchcraft.compat.thaumcraft;

import dev.senluo.witchcraft.worldgen.LocalGeneration;
import java.util.*;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.saveddata.SavedData;

/** TC's native post-generation queue and ledger, scoped to resident temporary chunks. */
public final class TemporaryGeneration implements LocalGeneration.ResidentData {
    public interface Pending { boolean witchcraft$pending(long chunk); }
    public interface Data {
        void witchcraft$owner(ServerLevel level);
        void witchcraft$discard(long chunk, Set<Long> claims);
    }
    private static Map<ServerLevel, ?> queues = Map.of();
    private static final ThreadLocal<Scope> ACTIVE = new ThreadLocal<>();
    private final ServerLevel level;
    private final Map<Long, Set<Long>> claims = new HashMap<>();
    private final Set<Long> retired = new HashSet<>();
    private SavedData data;

    private TemporaryGeneration(ServerLevel level) { this.level = level; }
    public static void queues(Map<ServerLevel, ?> nativeQueues) { queues = nativeQueues; }
    public static void changed(ServerLevel level, BlockPos pos) { MineSample.changed(level, pos); }
    private static TemporaryGeneration book(ServerLevel level) {
        return LocalGeneration.data(level, TemporaryGeneration.class, () -> new TemporaryGeneration(level));
    }
    public static boolean ready(ServerLevel level, long chunk) {
        // TC removes a queue entry before executing it; callbacks inside that work are not completion.
        for (Scope scope = ACTIVE.get(); scope != null; scope = scope.previous)
            if (scope.owner.level == level && scope.chunk == chunk) return false;
        Object pending = queues.get(level);
        return !(pending instanceof Pending view) || !view.witchcraft$pending(chunk);
    }
    @Nullable public static SavedData data(ServerLevel level, SavedData.Factory<?> factory) {
        if (MixedMine.source(level.dimension().location()) == null) return null;
        LocalGeneration.attach(level);
        var book = book(level);
        if (book.data == null) {
            book.data = factory.constructor().get();
            ((Data) book.data).witchcraft$owner(level);
        }
        return book.data;
    }
    public static void during(ServerLevel level, ChunkPos origin, Runnable work) {
        if (MixedMine.source(level.dimension().location()) == null) { work.run(); return; }
        Scope before = ACTIVE.get(); ACTIVE.set(new Scope(book(level), origin.toLong(), before));
        try { work.run(); } finally { if (before == null) ACTIVE.remove(); else ACTIVE.set(before); }
    }
    public static void claimed(SavedData data, BlockPos pos) {
        Scope scope = ACTIVE.get();
        if (scope != null && scope.owner.data == data)
            scope.owner.claims.computeIfAbsent(scope.chunk, unused -> new HashSet<>()).add(pos.asLong());
    }
    public static void finished(ServerLevel level) {
        if (MixedMine.source(level.dimension().location()) == null) return;
        var book = book(level);
        if (book != null) for (long chunk : List.copyOf(book.retired)) book.chunkRetired(chunk);
    }
    @Override public void chunkRetired(long chunk) {
        if (LocalGeneration.hasHolder(level, chunk)) { retired.remove(chunk); return; }
        if (!ready(level, chunk)) { retired.add(chunk); return; }
        retired.remove(chunk);
        Set<Long> positions = claims.remove(chunk);
        if (data != null) ((Data) data).witchcraft$discard(chunk, positions == null ? Set.of() : positions);
    }
    @Override public void close() { claims.clear(); retired.clear(); data = null; }
    private record Scope(TemporaryGeneration owner, long chunk, Scope previous) {}
}
