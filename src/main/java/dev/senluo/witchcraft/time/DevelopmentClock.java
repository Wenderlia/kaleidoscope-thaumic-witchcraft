package dev.senluo.witchcraft.time;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;

/** A world-local development clock graph; external tickers submit time, never replay world work. */
public final class DevelopmentClock {
    public interface Participant {
        default BlockEntity developmentParent() { return null; }
        default int developmentRate() { return 1; }
        void advanceDevelopment(int elapsed);
    }

    private final ServerLevel level;
    private final Map<BlockEntity, Integer> pulses = new IdentityHashMap<>();
    public DevelopmentClock(ServerLevel level) { this.level = level; }

    public void pulse(BlockEntity body) {
        if (body instanceof Participant && valid(body))
            pulses.merge(body, 1, (a, b) -> a == Integer.MAX_VALUE ? a : a + b);
    }

    private boolean valid(BlockEntity body) {
        return body != null && body.getLevel() == level && !body.isRemoved()
                && level.tickRateManager().runsNormally() && level.hasChunkAt(body.getBlockPos())
                && level.shouldTickBlocksAt(body.getBlockPos()) && level.getBlockEntity(body.getBlockPos()) == body;
    }

    /** Resolve every clock before advancing any participant, independent of BE ticker order. */
    public void flush() {
        if (pulses.isEmpty()) return;
        Map<BlockEntity, Integer> elapsed = new IdentityHashMap<>();
        Set<BlockEntity> visiting = Collections.newSetFromMap(new IdentityHashMap<>());
        List<BlockEntity> bodies = pulses.keySet().stream()
                .sorted(java.util.Comparator.comparingLong(body -> body.getBlockPos().asLong())).toList();
        for (BlockEntity body : bodies) resolve(body, elapsed, visiting);
        pulses.clear(); // No saved debt: unloading/offline time never becomes development time.
        int start = Math.floorMod(level.getGameTime(), bodies.size());
        for (int i = 0; i < bodies.size(); i++) {
            BlockEntity body = bodies.get((start + i) % bodies.size());
            int ticks = elapsed.getOrDefault(body, 0);
            if (ticks > 0 && valid(body)) ((Participant) body).advanceDevelopment(ticks);
        }
    }

    private int resolve(BlockEntity body, Map<BlockEntity, Integer> elapsed, Set<BlockEntity> visiting) {
        Integer cached = elapsed.get(body);
        if (cached != null) return cached;
        if (!pulses.containsKey(body) || !valid(body) || visiting.size() >= 64 || !visiting.add(body)) return 0;
        Participant participant = (Participant) body;
        int local = pulses.get(body);
        int inherited = resolve(participant.developmentParent(), elapsed, visiting);
        // Two accelerators offer alternative clocks, not two multiplicative copies of the same time.
        long result = (long) Math.max(local, inherited) * Math.clamp(participant.developmentRate(), 1, 1024);
        int ticks = (int) Math.min(Integer.MAX_VALUE, result);
        visiting.remove(body);
        elapsed.put(body, ticks);
        return ticks;
    }
}
