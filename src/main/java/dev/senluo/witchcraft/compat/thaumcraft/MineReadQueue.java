package dev.senluo.witchcraft.compat.thaumcraft;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** Server-thread admission of native read tickets. No worker thread reads a world. */
final class MineReadQueue {
    static final int OWNER_CHUNKS = 384;
    private static final int SERVER_CHUNKS = 512, IN_FLIGHT = 32, BACKGROUND_IN_FLIGHT = 24;
    private static final int SUBMISSIONS_PER_TICK = 8, LEASE = 60, RENEW = 20;
    private static final TicketType<UUID> READ = TicketType.create("witchcraft_ritual_mine_read", UUID::compareTo, LEASE);
    private static final Map<MinecraftServer, Queue> SERVERS = new WeakHashMap<>();
    private static boolean registered;

    record Need(boolean entities, int priority) {}
    private record Address(ServerLevel level, long chunk) {}
    private static final class Entry {
        final long key;
        int planned = Integer.MAX_VALUE, urgentTick = Integer.MIN_VALUE;
        boolean planEntities, urgentEntities, leased;
        int renewed;
        Entry(long key) { this.key = key; }
        boolean urgent(int tick) { return urgentTick != Integer.MIN_VALUE && tick - urgentTick <= 1; }
        int priority(int tick) { return urgent(tick) ? 0 : planned; }
        boolean entities(int tick) { return planEntities || urgent(tick) && urgentEntities; }
    }

    static final class Owner {
        final UUID id;
        final MinePrefetch prefetch;
        final Map<Long, Entry> entries = new LinkedHashMap<>();
        ServerLevel level;
        int activeTick, held, pending, allowance = 4;
        Owner(UUID id, MinePrefetch prefetch) {
            this.id = id; this.prefetch = prefetch;
            register();
        }
        void bind(ServerLevel next) {
            if (level != next) {
                release(); level = next;
                SERVERS.computeIfAbsent(next.getServer(), unused -> new Queue()).owners.add(this);
            }
            touch();
        }
        void touch() { if (level != null) activeTick = level.getServer().getTickCount(); }
        private void require(long key, boolean entities) {
            touch();
            Entry entry = entries.computeIfAbsent(key, Entry::new);
            int tick = level.getServer().getTickCount();
            if (entry.urgentTick != tick) entry.urgentEntities = false;
            entry.urgentTick = tick; entry.urgentEntities |= entities;
        }
        LevelChunk chunk(ChunkPos pos) {
            require(pos.toLong(), true);
            LevelChunk chunk = level.getChunkSource().getChunkNow(pos.x, pos.z);
            return chunk != null && level.areEntitiesLoaded(pos.toLong()) ? chunk : null;
        }
        ChunkAccess structure(ChunkPos pos) {
            require(pos.toLong(), false);
            return structureNow(pos.toLong());
        }
        ChunkAccess structureNow(long key) {
            return level.getChunkSource().getChunkForLighting(ChunkPos.getX(key), ChunkPos.getZ(key))
                    instanceof ChunkAccess chunk ? chunk : null;
        }
        LevelChunk chunkNow(long key) {
            return level.getChunkSource().getChunkNow(ChunkPos.getX(key), ChunkPos.getZ(key));
        }
        boolean ready(long key, boolean entities) {
            return entities ? chunkNow(key) != null && level.areEntitiesLoaded(key) : structureNow(key) != null;
        }
        boolean prepared(long key, boolean entities) {
            Entry entry = entries.get(key);
            return entry != null && entry.leased && ready(key, entities);
        }
        void plan(Map<Long, Need> wanted) {
            for (Entry entry : entries.values()) { entry.planned = Integer.MAX_VALUE; entry.planEntities = false; }
            wanted.forEach((key, need) -> {
                Entry entry = entries.computeIfAbsent(key, Entry::new);
                entry.planned = need.priority(); entry.planEntities = need.entities();
            });
        }
        void release() {
            if (level == null) return;
            Queue queue = SERVERS.get(level.getServer());
            for (Entry entry : entries.values()) if (entry.leased) removeTicket(entry, queue);
            if (queue != null) queue.owners.remove(this);
            entries.clear(); held = pending = 0; level = null;
        }
        private void removeTicket(Entry entry, Queue queue) {
            level.getChunkSource().removeRegionTicket(READ, new ChunkPos(entry.key), 0, id);
            entry.leased = false; held--;
            if (queue != null) queue.held--;
        }
        private void refresh(Queue queue, int tick) {
            pending = 0;
            var iterator = entries.values().iterator();
            while (iterator.hasNext()) {
                Entry entry = iterator.next();
                if (entry.priority(tick) == Integer.MAX_VALUE) {
                    if (entry.leased) removeTicket(entry, queue);
                    iterator.remove(); continue;
                }
                if (!entry.leased) continue;
                if (tick - entry.renewed >= RENEW) {
                    level.getChunkSource().addRegionTicket(READ, new ChunkPos(entry.key), 0, id);
                    entry.renewed = tick;
                }
                // A structure can be readable before the native FULL request has completed.
                if (chunkNow(entry.key) == null || entry.entities(tick) && !level.areEntitiesLoaded(entry.key)) {
                    pending++; queue.pending.add(new Address(level, entry.key));
                }
            }
        }
        private void trimShare(Queue queue, int tick, int fairShare) {
            if (held <= fairShare) return;
            for (Entry entry : entries.values()) {
                // New owners reclaim a fair share of old reserve; current work and in-flight reads stay intact.
                if (entry.leased && entry.priority(tick) == 2 && chunkNow(entry.key) != null
                        && (!entry.entities(tick) || level.areEntitiesLoaded(entry.key))) {
                    removeTicket(entry, queue);
                    if (held <= fairShare) return;
                }
            }
        }
        private boolean evictReserve(Queue queue, int tick) {
            for (Entry entry : entries.values()) if (entry.leased && entry.priority(tick) == 2
                    && chunkNow(entry.key) != null && (!entry.entities(tick) || level.areEntitiesLoaded(entry.key))) {
                removeTicket(entry, queue); return true;
            }
            return false;
        }
        private boolean submit(Queue queue, int tick, int priority, int fairShare) {
            for (Entry entry : entries.values()) {
                if (entry.leased || entry.priority(tick) != priority) continue;
                boolean cold = chunkNow(entry.key) == null || entry.entities(tick) && !level.areEntitiesLoaded(entry.key);
                Address address = new Address(level, entry.key);
                boolean shared = queue.pending.contains(address);
                int pendingLimit = priority == 0 ? Math.min(18, allowance + 2) : allowance;
                if (cold && pending >= pendingLimit) continue;
                int serverLimit = priority == 0 ? IN_FLIGHT : BACKGROUND_IN_FLIGHT;
                if (cold && !shared && queue.pending.size() >= serverLimit) continue;
                if (held >= OWNER_CHUNKS && (priority != 0 || !evictReserve(queue, tick))) continue;
                if (priority > 0 && held >= fairShare) continue;
                if (queue.held >= SERVER_CHUNKS && (priority != 0 || !queue.evictPrepared(tick))) continue;
                level.getChunkSource().addRegionTicket(READ, new ChunkPos(entry.key), 0, id);
                entry.leased = true; entry.renewed = tick; held++; queue.held++;
                if (cold) { pending++; queue.pending.add(address); }
                return true;
            }
            return false;
        }
    }

    private static final class Queue {
        final Set<Owner> owners = new LinkedHashSet<>();
        final Set<Address> pending = new HashSet<>();
        int held, cursor;
        long started;
        boolean evictPrepared(int tick) {
            for (Owner owner : owners) for (Entry entry : owner.entries.values()) {
                if (entry.leased && entry.priority(tick) == 2 && owner.ready(entry.key, entry.entities(tick))) {
                    owner.removeTicket(entry, this); return true;
                }
            }
            return false;
        }
    }

    private static void register() {
        if (registered) return;
        registered = true;
        NeoForge.EVENT_BUS.addListener(MineReadQueue::start);
        NeoForge.EVENT_BUS.addListener(MineReadQueue::tick);
        NeoForge.EVENT_BUS.addListener((ServerStoppedEvent event) -> {
            Queue queue = SERVERS.remove(event.getServer());
            if (queue != null) for (Owner owner : new ArrayList<>(queue.owners)) owner.prefetch.release();
        });
    }
    private static void start(ServerTickEvent.Pre event) {
        Queue queue = SERVERS.get(event.getServer());
        if (queue != null) queue.started = System.nanoTime();
    }
    static void unload(ServerLevel level) {
        Queue queue = SERVERS.get(level.getServer());
        if (queue != null) for (Owner owner : new ArrayList<>(queue.owners))
            if (owner.level == level) owner.prefetch.release();
    }
    private static void tick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        Queue queue = SERVERS.get(server);
        if (queue == null || !server.tickRateManager().runsNormally()) return;
        int tick = server.getTickCount();
        for (Owner owner : new ArrayList<>(queue.owners)) {
            // Check once after every host update; accelerated calls share the same heartbeat.
            if (owner.activeTick != tick) owner.prefetch.release();
            else owner.prefetch.prepare(tick);
        }
        queue.pending.clear();
        for (Owner owner : queue.owners) owner.refresh(queue, tick);
        if (queue.owners.isEmpty()) { SERVERS.remove(server); return; }
        var owners = new ArrayList<>(queue.owners);
        int fairShare = Math.min(OWNER_CHUNKS, Math.max(9, SERVER_CHUNKS / owners.size()));
        for (Owner owner : owners) owner.trimShare(queue, tick, fairShare);
        long elapsed = queue.started == 0 ? 0 : System.nanoTime() - queue.started;
        int submissions = elapsed >= 50_000_000L ? 1 : elapsed >= 40_000_000L ? 2 : SUBMISSIONS_PER_TICK;
        int start = Math.floorMod(queue.cursor++, owners.size());
        for (int priority = 0; priority <= (elapsed >= 50_000_000L ? 0 : 2) && submissions > 0; priority++) {
            boolean progress;
            do {
                progress = false;
                for (int i = 0; i < owners.size() && submissions > 0; i++) {
                    Owner owner = owners.get((start + i) % owners.size());
                    if (owner.submit(queue, tick, priority, fairShare)) { submissions--; progress = true; }
                }
            } while (progress && submissions > 0);
        }
    }
    private MineReadQueue() {}
}
