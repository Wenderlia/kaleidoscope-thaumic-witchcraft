package dev.senluo.witchcraft.ritual;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/** Bounds synchronous extra work, never native work. Reservations precede foreign callbacks.
 * Previous-frame demand gives recurring targets fair shares without storing callbacks or debt.
 * Cold targets use unreserved capacity; they participate in fair allocation on the next frame.
 */
public final class RitualWorkBudget {
    public enum Kind { MACHINE, PRODUCTION_MACHINE, NODE, FIRE, BURN }
    private record Key(Kind kind, Object identity) {}
    public record Snapshot(int limit, int targets, int granted, long limitedRequests) {}
    private static final Map<ServerLevel, Frame> LEVELS = new IdentityHashMap<>();
    private RitualWorkBudget() {}

    public static int claim(ServerLevel level, Kind kind, BlockPos pos, BigInteger requested) {
        return claim(level, new Key(kind, pos.asLong()), requested);
    }
    public static int claimBurn(ServerLevel level, UUID entity, BigInteger requested) {
        return claim(level, new Key(Kind.BURN, entity), requested);
    }
    private static int claim(ServerLevel level, Key key, BigInteger requested) {
        if (requested.signum() <= 0) return 0;
        if (!level.getServer().isSameThread()) throw new IllegalStateException("Ritual work must run on the server thread");
        Frame frame = LEVELS.computeIfAbsent(level, unused -> new Frame());
        frame.begin(level.getServer().getTickCount(), RitualConfig.EXTRA_UPDATES.get());
        int demand = requested.min(BigInteger.valueOf(frame.limit)).intValueExact();
        Entry entry = frame.entries.computeIfAbsent(key, unused -> new Entry());
        entry.demand = (int) Math.min(frame.limit, (long) entry.demand + demand);
        int granted = Math.min(demand, entry.quota);
        entry.quota -= granted;
        int spare = Math.min(demand - granted, frame.free);
        frame.free -= spare;
        granted += spare;
        frame.granted += granted;
        // Counters measure admitted opportunities, not successful foreign work.
        level.getProfiler().incrementCounter("kw_ritual_budget_granted", granted);
        if (requested.compareTo(BigInteger.valueOf(granted)) > 0) {
            frame.limitedRequests++;
            level.getProfiler().incrementCounter("kw_ritual_budget_limited_requests");
        }
        return granted;
    }
    public static Snapshot snapshot(ServerLevel level) {
        Frame frame = LEVELS.get(level);
        if (frame == null) return new Snapshot(RitualConfig.EXTRA_UPDATES.get(), 0, 0, 0);
        frame.begin(level.getServer().getTickCount(), RitualConfig.EXTRA_UPDATES.get());
        return new Snapshot(frame.limit, frame.entries.size(), frame.granted, frame.limitedRequests);
    }
    public static void unload(ServerLevel level) { LEVELS.remove(level); }

    private static final class Entry { int demand, quota; }
    private static final class Frame {
        final Map<Key, Entry> entries = new LinkedHashMap<>();
        int tick, limit, free, granted, rotation;
        long limitedRequests;
        boolean started;
        void begin(int now, int capacity) {
            if (started && tick == now) return;
            // A dormant world must not revive reservations from an old server frame.
            if (started && now != tick + 1) entries.clear();
            entries.values().removeIf(entry -> entry.demand == 0);
            var active = new ArrayList<>(entries.values());
            if (!active.isEmpty()) Collections.rotate(active, Math.floorMod(rotation++, active.size()));
            // Low demand is satisfied first, then remaining capacity is evenly shared.
            // Stable sorting retains the rotating order for equal demands and remainder slots.
            // When targets outnumber slots, rotate all targets, regardless of demand;
            // sorting here would permanently starve smaller requests of their first slot.
            if (active.size() <= capacity) active.sort(Comparator.comparingInt(entry -> entry.demand));
            int available = capacity;
            for (int i = 0; i < active.size(); i++) {
                Entry entry = active.get(i);
                entry.quota = active.size() > capacity ? (i < capacity ? 1 : 0)
                        : Math.min(entry.demand, available / (active.size() - i));
                available -= entry.quota;
                entry.demand = 0;
            }
            free = available; granted = 0; limitedRequests = 0;
            tick = now; limit = capacity; started = true;
        }
    }
}
