package dev.senluo.witchcraft.compat.thaumcraft;

import com.mojang.logging.LogUtils;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import javax.annotation.Nullable;
import net.minecraft.nbt.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;

/** Bounded immutable content, lazy slot reads and coalesced writes. All ownership changes use the server thread. */
final class MineSamplePool {
    private static final Logger LOG = LogUtils.getLogger();
    private static final long LIMIT = 256L << 20;
    private static final int SLOTS = 256, MAGIC = 0x574d5031;
    private static final Map<MinecraftServer, MineSamplePool> SERVERS = new WeakHashMap<>();
    private static boolean listening;
    final MinecraftServer server;
    private final Map<ResourceLocation, Pool> pools = new HashMap<>();
    private final LinkedHashSet<BooleanSupplier> evictors = new LinkedHashSet<>();
    private final ConcurrentLinkedQueue<Runnable> completions = new ConcurrentLinkedQueue<>();
    private final Set<CompletableFuture<?>> tasks = new HashSet<>();
    private final ExecutorService io = Executors.newFixedThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "Witchcraft sample storage"); thread.setDaemon(true); return thread;
    });
    private long allocated;
    private long availability;
    private int operations, captureTick = Integer.MIN_VALUE, captures;
    private boolean closing;

    private MineSamplePool(MinecraftServer server) { this.server = server; }
    static MineSamplePool get(MinecraftServer server) {
        if (!listening) {
            listening = true;
            NeoForge.EVENT_BUS.addListener((ServerTickEvent.Post event) -> {
                var pool = SERVERS.get(event.getServer()); if (pool != null) pool.drain();
            });
            NeoForge.EVENT_BUS.addListener((ServerStoppingEvent event) -> {
                var pool = SERVERS.remove(event.getServer()); if (pool != null) pool.close();
            });
        }
        return SERVERS.computeIfAbsent(server, MineSamplePool::new);
    }
    boolean reserve(long bytes) {
        if (closing || bytes < 0 || bytes > LIMIT) return false;
        while (allocated + bytes > LIMIT) {
            boolean changed = false;
            for (Pool pool : pools.values()) if (pool.evict()) { changed = true; break; }
            if (!changed) for (var evict : List.copyOf(evictors)) if (evict.getAsBoolean()) { changed = true; break; }
            if (!changed) return false;
        }
        allocated += bytes; return true;
    }
    void free(long bytes) { allocated -= bytes; if (bytes != 0) availability++; if (allocated < 0) throw new IllegalStateException("Unbalanced sample memory"); }
    long availability() { return availability; }
    void registerEviction(BooleanSupplier evictor) { evictors.add(evictor); }
    void removeEviction(BooleanSupplier evictor) { evictors.remove(evictor); }
    boolean beginOperation() { if (closing || operations >= 2) return false; operations++; return true; }
    void endOperation() { availability++; if (--operations < 0) throw new IllegalStateException("Unbalanced sample operation"); }
    boolean beginCapture() {
        int tick = server.getTickCount();
        if (captureTick != tick) { captureTick = tick; captures = 0; }
        if (captures >= 2 || !beginOperation()) return false;
        captures++; return true;
    }
    Pool source(ResourceLocation source) { return pools.computeIfAbsent(source, Pool::new); }
    static boolean restorable(MineSample sample) { return sample.bytes() <= (LIMIT - 8192) / 2; }
    private void drain() { Runnable done; while ((done = completions.poll()) != null) done.run(); }
    private <T> void task(Callable<T> work, java.util.function.BiConsumer<T, Throwable> done) {
        CompletableFuture<Void> finished = new CompletableFuture<>();
        tasks.add(finished);
        io.execute(() -> {
            T value = null; Throwable failure = null;
            try { value = work.call(); } catch (Exception e) { failure = e; }
            T result = value; Throwable error = failure;
            completions.add(() -> { tasks.remove(finished); done.accept(result, error); });
            finished.complete(null);
        });
    }
    private void close() {
        closing = true;
        // File work never waits for a server callback. Drain callbacks between batches to finish coalesced successors.
        while (!tasks.isEmpty()) {
            for (var task : List.copyOf(tasks)) task.handle((value, failure) -> null).join();
            drain();
        }
        // One final attempt for dirty slots. A failed filesystem stays a reported failure, never an endless shutdown.
        for (Pool pool : pools.values()) for (Entry entry : pool.entries)
            if (entry != null && !entry.saved && entry.content != null) pool.write(entry);
        while (!tasks.isEmpty()) {
            for (var task : List.copyOf(tasks)) task.join();
            drain();
        }
        for (Pool pool : pools.values()) pool.clear();
        io.shutdown(); pools.clear(); evictors.clear();
    }

    enum Status { READY, WAITING, ABSENT, CLOSED }
    record Lookup(Status status, @Nullable MineSample sample) {
        static final Lookup WAITING = new Lookup(Status.WAITING, null), ABSENT = new Lookup(Status.ABSENT, null),
                CLOSED = new Lookup(Status.CLOSED, null);
    }

    final class Pool {
        private final ResourceLocation source;
        private final Path folder;
        private final Entry[] entries = new Entry[SLOTS];
        private final boolean[] blocked = new boolean[SLOTS];
        private boolean started, loaded;
        private long sequence, revision;
        private Set<UUID> ids;
        long revision() { return revision; }
        Pool(ResourceLocation source) {
            this.source = source;
            String name = Base64.getUrlEncoder().withoutPadding().encodeToString(source.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            folder = server.getWorldPath(LevelResource.ROOT).resolve("data/kaleidoscope_witchcraft/mine_samples").resolve(name);
        }
        /** The caller owns selection policy; a pending read is never an absent template. */
        Lookup request(int dx, int dz, Set<UUID> excluded) {
            if (closing) return Lookup.CLOSED;
            if (!started) load();
            if (!loaded) return Lookup.WAITING;
            Entry entry = find(dx, dz, excluded);
            if (entry == null) return Lookup.ABSENT;
            MineSample sample = content(entry);
            return sample == null ? Lookup.WAITING : new Lookup(Status.READY, sample);
        }
        Set<UUID> ids() {
            if (ids == null) {
                Set<UUID> current = new HashSet<>();
                for (Entry entry : entries) if (entry != null) current.add(entry.description.id());
                ids = Set.copyOf(current);
            }
            return ids;
        }
        /** Preparation only: no admission, round reset or consumption mark. */
        void warm(int dx, int dz, Set<UUID> used) {
            if (closing) return;
            if (!started) load();
            if (!loaded) return;
            Entry entry = find(dx, dz, used);
            if (entry == null) entry = find(dx, dz, Set.of());
            if (entry != null && entry.content == null && allocated + entry.weight <= LIMIT) {
                MineSample ready = content(entry);
                if (ready != null) ready.release();
            }
        }
        private Entry find(int dx, int dz, Set<UUID> used) {
            Entry found = null;
            for (Entry entry : entries) if (entry != null && !entry.failed && entry.description.accepts(dx, dz)
                    && !used.contains(entry.description.id()) && (found == null || entry.sequence < found.sequence)) found = entry;
            return found;
        }
        @Nullable private MineSample content(Entry entry) {
            if (entry == null || entry.failed) return null;
            if (entry.content != null) return entry.content.retain();
            if (entry.reading || !reserve(entry.weight)) return null;
            entry.reading = true;
            task(() -> {
                try (DataInputStream input = new DataInputStream(new BufferedInputStream(Files.newInputStream(path(entry.slot))))) {
                    Header header = header(input, false);
                    if (!header.description.id().equals(entry.description.id()) || header.sequence != entry.sequence)
                        throw new IOException("Sample slot changed during read");
                    return NbtIo.readCompressed(input, NbtAccounter.create(entry.weight));
                }
            }, (tag, error) -> {
                revision++;
                entry.reading = false;
                if (error != null || tag == null || closing) {
                    free(entry.weight); entry.failed = error != null;
                    if (error != null) LOG.error("Could not read mine sample {} slot {}", source, entry.slot, error);
                    return;
                }
                MineSample sample;
                try { sample = MineSample.decode(MineSamplePool.this, tag, entry.weight); }
                catch (RuntimeException invalid) {
                    free(entry.weight); entry.failed = true;
                    LOG.error("Invalid mine sample {} slot {}", source, entry.slot, invalid); return;
                }
                if (!sample.description.equals(entry.description)) {
                    sample.release(); entry.failed = true; LOG.error("Mismatched mine sample metadata in {} slot {}", source, entry.slot); return;
                }
                if (entries[entry.slot] == entry) entry.content = sample; else sample.release();
            });
            return null;
        }
        boolean admit(MineSample sample) {
            if (closing || !loaded || !sample.description.source().equals(source)) return false;
            long weight = Math.max(sample.bytes(), (long) sample.encode().sizeInBytes()) + 4096;
            // The disk header must remain a usable decode budget, including the envelope.
            if (weight > LIMIT) return false;
            int slot = -1;
            for (int i = 0; i < SLOTS; i++) {
                if (blocked[i]) continue;
                if (entries[i] == null) { slot = i; break; }
                if (slot < 0 || entries[i].sequence < entries[slot].sequence) slot = i;
            }
            if (slot < 0) return false;
            Entry previous = entries[slot];
            Entry next = new Entry(slot, ++sequence, sample.description, weight);
            next.content = sample.retain(); entries[slot] = next;
            revision++; ids = null;
            // A slot serializes writers, but an active consumer owns the old content independently.
            Entry writer = previous == null ? null : previous.writing ? previous : previous.predecessor;
            if (writer != null) { next.predecessor = writer; writer.successor = next; }
            else write(next);
            if (previous != null && previous.content != null) { previous.content.release(); previous.content = null; }
            return true;
        }
        void reject(UUID id) {
            for (Entry entry : entries) if (entry != null && entry.description.id().equals(id)) {
                entry.failed = true; revision++; return;
            }
        }
        private void write(Entry entry) {
            if (entry.content == null || entry.writing) return;
            entry.writing = true;
            MineSample captured = entry.content.retain();
            CompoundTag payload = captured.encode();
            Header header = new Header(entry.sequence, entry.weight, entry.description);
            task(() -> {
                Files.createDirectories(folder);
                Path target = path(entry.slot), temporary = target.resolveSibling(target.getFileName() + ".pending");
                try (DataOutputStream output = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(temporary)))) {
                    output.writeInt(MAGIC); output.writeInt(MineSample.FORMAT); output.writeLong(header.sequence);
                    output.writeLong(header.weight); NbtIo.write(header.description.save(), output);
                    NbtIo.writeCompressed(payload, output);
                }
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                return true;
            }, (saved, error) -> {
                entry.writing = false; entry.saved = error == null; captured.release();
                if (error != null) LOG.error("Could not save mine sample {} slot {}; keeping the in-memory version", source, entry.slot, error);
                Entry next = entry.successor;
                if (next != null) { next.predecessor = null; entry.successor = null; write(next); }
            });
        }
        private void load() {
            started = true;
            task(() -> {
                SlotRead[] slots = new SlotRead[SLOTS];
                for (int i = 0; i < SLOTS; i++) {
                    try (DataInputStream input = new DataInputStream(new BufferedInputStream(Files.newInputStream(path(i))))) {
                        // Known old cache slots become replaceable; new admission atomically overwrites them.
                        Header header = header(input, true);
                        if (header != null && !header.description.source().equals(source)) throw new IOException("Mismatched sample dimension");
                        slots[i] = new SlotRead(header, null);
                    } catch (NoSuchFileException absent) {
                        // Only an absent file or a known retired format makes a slot replaceable.
                    } catch (IOException | RuntimeException invalid) {
                        slots[i] = new SlotRead(null, invalid);
                    }
                }
                return slots;
            }, (slots, error) -> {
                revision++;
                if (closing) return;
                if (error != null) {
                    Arrays.fill(blocked, true);
                    LOG.error("Could not load mine sample directory {}", source, error);
                } else for (int i = 0; i < SLOTS; i++) if (slots[i] != null) {
                    if (slots[i].failure != null) {
                        blocked[i] = true;
                        LOG.error("Could not read mine sample header {} slot {}; other slots remain available", source, i, slots[i].failure);
                    } else if (slots[i].header != null) {
                        Header h = slots[i].header; entries[i] = new Entry(i, h.sequence, h.description, h.weight);
                        entries[i].saved = true;
                        sequence = Math.max(sequence, h.sequence);
                    }
                }
                loaded = true; ids = null;
            });
        }
        private Path path(int slot) { return folder.resolve("slot_%03d.wmp".formatted(slot)); }
        private boolean evict() {
            for (Entry entry : entries) if (entry != null && entry.saved && !entry.writing && entry.content != null
                    && entry.content.references() == 1) {
                entry.content.release(); entry.content = null; return true;
            }
            return false;
        }
        private void clear() {
            for (Entry entry : entries) if (entry != null && entry.content != null) { entry.content.release(); entry.content = null; }
            Arrays.fill(entries, null);
            ids = Set.of();
        }
    }
    private static final class Entry {
        final int slot;
        final long sequence, weight;
        final MineSample.Description description;
        MineSample content;
        Entry predecessor, successor;
        boolean reading, writing, failed, saved;
        Entry(int slot, long sequence, MineSample.Description description, long weight) {
            this.slot = slot; this.sequence = sequence; this.description = description; this.weight = weight;
        }
    }
    private record Header(long sequence, long weight, MineSample.Description description) {}
    private record SlotRead(@Nullable Header header, @Nullable Exception failure) {}
    @Nullable private static Header header(DataInputStream input, boolean allowRetired) throws IOException {
        if (input.readInt() != MAGIC) throw new IOException("Unsupported mine sample format");
        int format = input.readInt();
        if (allowRetired && format == 1) return null;
        if (format != MineSample.FORMAT) throw new IOException("Unsupported mine sample format");
        long sequence = input.readLong(), weight = input.readLong();
        if (weight < 0 || weight > LIMIT || sequence < 0) throw new IOException("Invalid mine sample header");
        CompoundTag description = NbtIo.read(input, NbtAccounter.create(1 << 20));
        return new Header(sequence, weight, MineSample.Description.load(description));
    }
}
