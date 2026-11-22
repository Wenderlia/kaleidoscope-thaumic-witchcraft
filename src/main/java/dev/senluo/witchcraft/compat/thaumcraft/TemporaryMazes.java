package dev.senluo.witchcraft.compat.thaumcraft;

import dev.senluo.witchcraft.worldgen.LocalGeneration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import javax.annotation.Nullable;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.saveddata.SavedData;

/** TC's native maze algorithm, with memory ownership tied to the source structure's resident chunks. */
public final class TemporaryMazes implements LocalGeneration.ResidentData {
    private static final ThreadLocal<Scope> ACTIVE = new ThreadLocal<>();
    private final ServerLevel level;
    private final Map<LocalGeneration.Context, Book> books = new HashMap<>();
    private final Map<Long, Set<Reservation>> byChunk = new HashMap<>();
    private boolean closed;

    private TemporaryMazes(ServerLevel level) { this.level = level; }

    /** Implemented only on the TC data object; no native implementation classes in the compile API. */
    public interface Data {
        void witchcraft$discardMaze(long center, List<Object> cells);
    }

    public static boolean reserve(WorldGenLevel level, int x, int z, BoundingBox bounds, BooleanSupplier original) {
        var context = LocalGeneration.at(level.getLevel(), new ChunkPos(x, z));
        if (context == null) return original.getAsBoolean();
        var owner = LocalGeneration.data(context.level(), TemporaryMazes.class, () -> new TemporaryMazes(context.level()));
        return owner != null && owner.reserve(context, level, ChunkPos.asLong(x, z), bounds, original);
    }

    /** Called at the native get-data boundary, before DimensionDataStorage is queried. */
    @Nullable public static SavedData data(WorldGenLevel level, SavedData.Factory<?> factory) {
        Scope scope = ACTIVE.get();
        if (scope == null || scope.level.getLevel() != level.getLevel()) return null;
        if (scope.book.data == null) scope.book.data = factory.constructor().get();
        return scope.book.data;
    }

    /** Capture the actual native keys; cleanup does not duplicate TC's maze coordinate formulas. */
    public static void cellWritten(SavedData data, Object key) {
        Scope scope = ACTIVE.get();
        if (scope != null && scope.book.data == data) scope.reservation.cells.add(key);
    }

    private synchronized boolean reserve(LocalGeneration.Context context, WorldGenLevel source, long center,
                                          BoundingBox bounds, BooleanSupplier original) {
        if (closed) return false;
        Book book = books.computeIfAbsent(context, Book::new);
        Reservation reservation = book.reservations.get(center);
        boolean fresh = reservation == null;
        if (fresh) reservation = new Reservation(book, center, bounds);
        Scope previous = ACTIVE.get();
        ACTIVE.set(new Scope(source, book, reservation));
        try {
            return original.getAsBoolean();
        } finally {
            if (previous == null) ACTIVE.remove(); else ACTIVE.set(previous);
            // Also own any cells published before an exception, so an unloading source can release them.
            if (fresh && !reservation.cells.isEmpty()) {
                book.reservations.put(center, reservation);
                for (long chunk : reservation.chunks)
                    byChunk.computeIfAbsent(chunk, unused -> new HashSet<>()).add(reservation);
            }
            if (book.reservations.isEmpty()) books.remove(context, book);
        }
    }

    @Override public synchronized void chunkRetired(long chunk) {
        Set<Reservation> affected = byChunk.get(chunk);
        if (affected == null) return;
        for (Reservation reservation : List.copyOf(affected)) {
            boolean held = false;
            for (long dependency : reservation.chunks) {
                if (LocalGeneration.hasHolder(level, dependency)) { held = true; break; }
            }
            if (held) continue;
            Book book = reservation.book;
            ((Data) book.data).witchcraft$discardMaze(reservation.center, reservation.cells);
            book.reservations.remove(reservation.center, reservation);
            for (long dependency : reservation.chunks) {
                Set<Reservation> owners = byChunk.get(dependency);
                owners.remove(reservation);
                if (owners.isEmpty()) byChunk.remove(dependency);
            }
            if (book.reservations.isEmpty()) books.remove(book.context, book);
        }
    }

    @Override public synchronized void close() {
        closed = true;
        byChunk.clear();
        books.clear();
    }

    private static final class Book {
        final LocalGeneration.Context context;
        final Map<Long, Reservation> reservations = new HashMap<>();
        SavedData data;
        Book(LocalGeneration.Context context) { this.context = context; }
    }

    private static final class Reservation {
        final Book book;
        final long center;
        final Set<Long> chunks = new HashSet<>();
        final List<Object> cells = new ArrayList<>();
        Reservation(Book book, long center, BoundingBox bounds) {
            this.book = book; this.center = center;
            chunks.add(center);
            for (int x = bounds.minX() >> 4; x <= bounds.maxX() >> 4; x++)
                for (int z = bounds.minZ() >> 4; z <= bounds.maxZ() >> 4; z++) chunks.add(ChunkPos.asLong(x, z));
        }
    }

    private record Scope(WorldGenLevel level, Book book, Reservation reservation) {}
}
