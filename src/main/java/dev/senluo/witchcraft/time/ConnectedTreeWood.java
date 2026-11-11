package dev.senluo.witchcraft.time;

import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/**
 * Live 26-neighbour wood connectivity, independent of saved regrowth claims and ownership.
 * One component walk supplies all queried logs. Server-thread only; never loads chunks.
 */
public final class ConnectedTreeWood {
    /** A proof belongs to one observed component, not to all wood in the dimension. */
    public static final class Connection {
        private final boolean connected;
        private final long tick;
        private Set<Long> wood, chunks;
        private boolean valid = true;
        private Connection(boolean connected, long tick, Set<Long> wood, Set<Long> chunks) {
            this.connected = connected; this.tick = tick;
            this.wood = Set.copyOf(wood); this.chunks = Set.copyOf(chunks);
        }
        public boolean connected() { return connected; }
        public boolean current(long now) { return valid && now >= tick && now - tick < 20; }
    }
    private final Map<Long, Connection> entries = new HashMap<>();
    private final Map<Long, Set<Connection>> watchers = new HashMap<>();
    private final Set<Connection> components = new HashSet<>();
    private static final int MAX_COMPONENT = TreeShapeSpace.MAX_PARTS;
    private static final int MAX_CACHE = MAX_COMPONENT * 2;
    private static final Connection UNAVAILABLE = new Connection(false, Long.MIN_VALUE, Set.of(), Set.of());
    static { UNAVAILABLE.valid = false; }

    ConnectedTreeWood() {}
    void invalidate() {
        for (Connection connection : components) retire(connection);
        entries.clear(); watchers.clear(); components.clear();
    }
    private void retire(Connection connection) {
        connection.valid = false;
        // Stabilizer scores may still hold the small proof; retired proofs must release geometry.
        connection.wood = Set.of(); connection.chunks = Set.of();
    }
    void changed(long chunk) {
        Set<Connection> affected = watchers.get(chunk);
        if (affected != null) for (Connection connection : List.copyOf(affected)) remove(connection);
    }
    private void remove(Connection connection) {
        connection.valid = false;
        components.remove(connection);
        for (long pos : connection.wood) entries.remove(pos, connection);
        for (long chunk : connection.chunks) {
            Set<Connection> bucket = watchers.get(chunk);
            if (bucket != null && bucket.remove(connection) && bucket.isEmpty()) watchers.remove(chunk);
        }
        retire(connection);
    }

    boolean connected(ServerLevel level, BlockPos origin) {
        return connection(level, origin).connected();
    }
    Connection connection(ServerLevel level, BlockPos origin) {
        long now = level.getGameTime();
        if (!loaded(level, origin) || !level.getBlockState(origin).is(TreeWood.LOGS))
            return UNAVAILABLE;
        Connection cached = entries.get(origin.asLong());
        if (cached != null && cached.current(now)) return cached;
        if (cached != null) remove(cached);
        Set<Long> wood = new HashSet<>();
        Set<Long> chunks = new HashSet<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        wood.add(origin.asLong());
        chunks.add(net.minecraft.world.level.ChunkPos.asLong(origin));
        queue.add(origin.immutable());
        boolean connected = false;
        while (!queue.isEmpty()) {
            BlockPos pos = queue.removeFirst();
            for (BlockPos offset : TreeShapeSpace.NEIGHBORS) {
                BlockPos next = pos.offset(offset);
                // Include unloaded and empty borders so later additions/loading wake this proof.
                chunks.add(net.minecraft.world.level.ChunkPos.asLong(next));
                if (wood.contains(next.asLong()) || !loaded(level, next)) continue;
                var state = level.getBlockState(next);
                if (state.is(TemporalContent.CORE.get())) {
                    if (level.getBlockEntity(next) instanceof LivingEntity core
                            && !core.isRemoved() && core.tree() && core.tissue.supported()) connected = true;
                } else if (state.is(dev.senluo.witchcraft.time.TreeWood.LOGS)) {
                    if (wood.size() >= MAX_COMPONENT) continue;
                    wood.add(next.asLong());
                    queue.add(next);
                }
            }
        }
        // Work is bounded. A found core proves a real loaded path for every collected log;
        // a path beyond the bound receives no speculative qualification.
        Set<Connection> replaced = new HashSet<>();
        for (long pos : wood) {
            Connection old = entries.get(pos);
            if (old != null) replaced.add(old);
        }
        replaced.forEach(this::remove);
        Connection result = new Connection(connected, now, wood, chunks);
        if (entries.size() + wood.size() > MAX_CACHE) invalidate();
        components.add(result);
        for (long pos : wood) entries.put(pos, result);
        for (long chunk : chunks) watchers.computeIfAbsent(chunk, ignored -> new HashSet<>()).add(result);
        return result;
    }

    private static boolean loaded(ServerLevel level, BlockPos pos) {
        return level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4) != null
                && level.shouldTickBlocksAt(pos);
    }
}
