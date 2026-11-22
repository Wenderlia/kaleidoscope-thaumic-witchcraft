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
    public static final class Connection implements java.util.function.BooleanSupplier {
        private final boolean connected;
        private final ServerLevel level;
        private Set<Long> wood, chunks;
        private Map<Long, Boolean> activity;
        private Map<LivingEntity, Boolean> cores;
        private boolean valid = true;
        private Connection(boolean connected, ServerLevel level, Set<Long> wood, Set<Long> chunks,
                           Map<Long, Boolean> activity, Map<LivingEntity, Boolean> cores) {
            this.connected = connected; this.level = level;
            this.wood = Set.copyOf(wood); this.chunks = Set.copyOf(chunks);
            this.activity = Map.copyOf(activity); this.cores = Map.copyOf(cores);
        }
        public boolean connected() { return connected; }
        @Override public boolean getAsBoolean() { return current(); }
        public boolean current() {
            if (!valid) return false;
            for (var entry : activity.entrySet())
                if (entry.getValue() != dev.senluo.witchcraft.world.ChunkActivity.active(level, entry.getKey())) return false;
            for (var entry : cores.entrySet()) {
                LivingEntity core = entry.getKey();
                if (core.isRemoved() || level.getBlockEntity(core.getBlockPos()) != core
                        || entry.getValue() != (core.tree() && core.tissue.supported())) return false;
            }
            return true;
        }
    }
    private final Map<Long, Connection> entries = new HashMap<>();
    private final Map<Long, Set<Connection>> watchers = new HashMap<>();
    private final Set<Connection> components = new HashSet<>();
    private static final int MAX_COMPONENT = TreeShapeSpace.MAX_PARTS;
    private static final int MAX_CACHE = MAX_COMPONENT * 2;
    private static final Connection UNAVAILABLE = new Connection(false, null, Set.of(), Set.of(), Map.of(), Map.of());
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
        connection.activity = Map.of(); connection.cores = Map.of();
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
        if (!loaded(level, origin) || !level.getBlockState(origin).is(TreeWood.LOGS))
            return UNAVAILABLE;
        Connection cached = entries.get(origin.asLong());
        if (cached != null && cached.current()) return cached;
        if (cached != null) remove(cached);
        Set<Long> wood = new HashSet<>();
        Set<Long> chunks = new HashSet<>();
        Map<LivingEntity, Boolean> cores = new IdentityHashMap<>();
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
                    if (level.getBlockEntity(next) instanceof LivingEntity core && !core.isRemoved()) {
                        boolean usable = core.tree() && core.tissue.supported();
                        cores.put(core, usable);
                        connected |= usable;
                    }
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
        Map<Long, Boolean> activity = new HashMap<>();
        for (long chunk : chunks) activity.put(chunk, dev.senluo.witchcraft.world.ChunkActivity.active(level, chunk));
        Connection result = new Connection(connected, level, wood, chunks, activity, cores);
        if (entries.size() + wood.size() > MAX_CACHE) invalidate();
        components.add(result);
        for (long pos : wood) entries.put(pos, result);
        for (long chunk : chunks) watchers.computeIfAbsent(chunk, ignored -> new HashSet<>()).add(result);
        return result;
    }

    private static boolean loaded(ServerLevel level, BlockPos pos) {
        return dev.senluo.witchcraft.world.ChunkActivity.active(level, pos);
    }
}
