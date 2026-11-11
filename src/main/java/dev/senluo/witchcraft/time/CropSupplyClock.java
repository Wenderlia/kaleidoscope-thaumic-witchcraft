package dev.senluo.witchcraft.time;

import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;

/** Per-provider, per-plant work clocks. No money, world lookup or persisted/offline work. */
final class CropSupplyClock {
    private record Key(UUID source, BlockPos plant) {}
    private static final class Session {
        long seen;
        final int rate;
        Session(long tick, int rate) { this.seen = tick; this.rate = rate; }
    }
    private static final class Credit {
        final Session session;
        long tick;
        int work, remainder;
        Credit(Session session, long tick) { this.session = session; this.tick = tick - 1; }
    }
    private final Map<UUID, Session> sources = new HashMap<>();
    private final Map<Key, Credit> plants = new LinkedHashMap<>();
    private final Map<BlockPos, Set<Key>> owners = new HashMap<>();

    void retainSources(Set<UUID> active) { sources.keySet().retainAll(active); }
    // Old bounded records become unusable by session identity without walking all plants.
    void topologyChanged() { sources.clear(); }
    void topologyChanged(UUID source) { sources.remove(source); }

    void begin(UUID source, long tick, int rate) {
        Session current = sources.get(source);
        if (current == null || current.rate != rate || current.seen < tick - 1 || current.seen > tick)
            sources.put(source, new Session(tick, rate));
        else current.seen = tick;
    }

    int available(UUID source, BlockPos plant, long tick, int unit) {
        Session session = sources.get(source);
        if (session == null || session.seen != tick) return 0;
        Key key = new Key(source, plant.immutable());
        Credit credit = plants.get(key);
        if (credit == null || credit.session != session) credit = new Credit(session, tick);
        long elapsed = Math.clamp(tick - credit.tick, 0, 20);
        long work = elapsed * session.rate * CropSupplyRates.RANDOM_WORK + credit.remainder;
        // At most one random opportunity or one bounded native-tick batch; no backlog burst.
        // Keep a fractional opportunity too: clamping at exactly one would turn 8/s
        // into one attempt every three ticks (6.67/s), discarding the 2/3-tick cadence.
        int capacity = unit == CropSupplyRates.RANDOM_WORK ? 2 * unit - 1 : 16 * unit;
        credit.work = (int) Math.min(capacity, credit.work + work / 20);
        credit.remainder = credit.work == capacity ? 0 : (int) (work % 20);
        credit.tick = tick;
        plants.remove(key);
        plants.put(key, credit);
        owners.computeIfAbsent(key.plant(), unused -> new HashSet<>()).add(key);
        if (plants.size() > 16384) remove(plants.keySet().iterator().next());
        return credit.work;
    }

    boolean ready(UUID source, BlockPos plant, long tick, int unit) {
        Credit credit = plants.get(new Key(source, plant));
        return credit != null && credit.session == sources.get(source)
                && credit.tick == tick && credit.work >= unit;
    }

    void consume(UUID source, BlockPos plant, long tick, int unit) {
        if (!ready(source, plant, tick, unit)) throw new IllegalStateException("Crop work was not available");
        plants.get(new Key(source, plant)).work -= unit;
    }

    private void remove(Key key) {
        plants.remove(key);
        Set<Key> attached = owners.get(key.plant());
        if (attached != null) {
            attached.remove(key);
            if (attached.isEmpty()) owners.remove(key.plant());
        }
    }
    void unavailable(UUID source, BlockPos plant) { remove(new Key(source, plant)); }
    void changed(BlockPos plant) {
        Set<Key> attached = owners.remove(plant);
        if (attached != null) attached.forEach(plants::remove);
    }
    void chunkChanged(long chunk) {
        for (BlockPos plant : owners.keySet().stream().filter(pos -> ChunkPos.asLong(pos) == chunk).toList()) changed(plant);
    }
}
