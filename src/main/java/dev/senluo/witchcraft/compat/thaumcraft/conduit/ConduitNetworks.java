package dev.senluo.witchcraft.compat.thaumcraft.conduit;

import dev.senluo.witchcraft.ritual.SuctionTransferStore;
import dev.tc4port.thaumcraft.api.aspect.AspectId;
import dev.tc4port.thaumcraft.api.essentia.EssentiaTransferMode;
import java.util.*;
import java.util.function.Supplier;
import net.minecraft.core.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import static dev.senluo.witchcraft.compat.thaumcraft.conduit.ConduitEndpoint.Port;

/** Loaded topology and scheduling. Matter ownership is kept in the buffer or persistent receipt. */
final class ConduitNetworks {
    private static final Map<ServerLevel, World> WORLDS = new IdentityHashMap<>();
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(ConduitNetworks.class);
    private static World world(ServerLevel level) { return WORLDS.computeIfAbsent(level, World::new); }
    static void add(ServerLevel level, ConduitEntity pipe) {
        var w = world(level); var old = w.loaded.put(pipe.getBlockPos().asLong(), pipe);
        if (old != null && old != pipe) w.invalidate(old.network);
        w.chunks.computeIfAbsent(ChunkPos.asLong(pipe.getBlockPos()), ignored -> new HashSet<>()).add(pipe);
        changed(level, pipe);
    }
    static void remove(ServerLevel level, ConduitEntity pipe) {
        var w = WORLDS.get(level); if (w == null) return;
        w.invalidate(pipe.network); w.pending.remove(pipe); w.sync.remove(pipe);
        w.loaded.remove(pipe.getBlockPos().asLong(), pipe);
        var chunk = w.chunks.get(ChunkPos.asLong(pipe.getBlockPos()));
        if (chunk != null) { chunk.remove(pipe); if (chunk.isEmpty()) w.chunks.remove(ChunkPos.asLong(pipe.getBlockPos())); }
    }
    static void changed(ServerLevel level, ConduitEntity pipe) {
        var w = world(level); w.invalidate(pipe.network); w.pending.add(pipe);
        for (Direction side : Direction.values()) {
            var other = w.loaded.get(pipe.getBlockPos().relative(side).asLong());
            if (other != null) { w.invalidate(other.network); w.pending.add(other); }
        }
    }
    static void chunkChanged(ServerLevel level, ChunkPos pos) {
        var w = WORLDS.get(level); if (w == null) return;
        // A port may straddle the chunk edge; retain only the local reverse-index invalidation.
        for (int x = -1; x <= 1; x++) for (int z = -1; z <= 1; z++) {
            var affected = w.chunks.get(ChunkPos.asLong(pos.x + x, pos.z + z));
            if (affected != null) for (var pipe : List.copyOf(affected)) { w.invalidate(pipe.network); w.pending.add(pipe); }
        }
    }
    static void sync(ServerLevel level, ConduitEntity pipe) { world(level).sync.add(pipe); }
    static void unload(ServerLevel level) { WORLDS.remove(level); }
    static void tick(ServerLevel level) { var w = WORLDS.get(level); if (w != null) w.tick(); }

    record Draw(AspectId aspect, int amount) {}
    /** A consumer pulling from a boundary face owns the receipt of the returned amount. */
    static Draw draw(ConduitEntity pipe, Direction face, AspectId wanted, int limit, EssentiaTransferMode mode) {
        if (!(pipe.getLevel() instanceof ServerLevel level) || !level.getServer().isSameThread()
                || !pipe.open(face) || limit <= 0) return null;
        Network network = pipe.network;
        World world = WORLDS.get(level);
        if (world == null || world.running || network == null || !network.valid || pipe.credit(level.getGameTime()) <= 0)
            return null;
        world.running = true;
        try { return world.draw(network, new Port(pipe.getBlockPos(), face), wanted, limit, mode); }
        finally { world.running = false; }
    }

    private record Node(ConduitEntity pipe, Node parent, int depth) {}
    private record Route(long first, long last) {}
    static final class Network {
        boolean valid, retired, ready;
        int pressure, cursor;
        final Map<Long, Node> nodes = new LinkedHashMap<>();
        final Set<ConduitEntity> stocked = Collections.newSetFromMap(new IdentityHashMap<>());
        final ArrayDeque<Node> frontier = new ArrayDeque<>();
        final List<Port> ports = new ArrayList<>();
        List<Port> drawSources = List.of(); // Position hints from the last pulse, never capability instances.
        final Set<Port> warned = new HashSet<>();
        final Map<Port, Long> failedUntil = new HashMap<>();
        // Geometry only. Allowance and external ports remain live for every execution.
        private final Map<Route, List<ConduitEntity>> paths = new LinkedHashMap<>(16, .75F, true);
        private int pathReferences;
        void attach(ConduitEntity pipe, Node parent) {
            Node node = new Node(pipe, parent, parent == null ? 0 : parent.depth + 1);
            nodes.put(pipe.getBlockPos().asLong(), node); frontier.add(node); pipe.network = this;
            stockChanged(pipe);
        }
        void stockChanged(ConduitEntity pipe) {
            if (pipe.hasStock()) stocked.add(pipe); else stocked.remove(pipe);
        }
        boolean hasLocalMatter(SuctionTransferStore journal, net.minecraft.resources.ResourceLocation dimension) {
            return !stocked.isEmpty() || journal.hasInDimension(dimension, SuctionTransferStore.Scope.CONDUIT);
        }
        List<ConduitEntity> path(BlockPos first, BlockPos last) {
            if (!valid) return List.of();
            Route key = new Route(first.asLong(), last.asLong());
            List<ConduitEntity> cached = paths.get(key);
            if (cached != null) return cached;
            Node a = nodes.get(first.asLong()), b = nodes.get(last.asLong());
            if (a == null || b == null) return List.of();
            var route = new ArrayList<ConduitEntity>();
            while (a != b) {
                if (a.depth >= b.depth) { route.add(a.pipe); a = a.parent; }
                else { route.add(b.pipe); b = b.parent; }
            }
            route.add(a.pipe);
            int budget = (int) Math.min(8192L, Math.max(64L, 2L * nodes.size()));
            if (route.size() > budget) return route;
            while (!paths.isEmpty() && (paths.size() >= 256 || pathReferences + route.size() > budget)) {
                var oldest = paths.entrySet().iterator();
                pathReferences -= oldest.next().getValue().size(); oldest.remove();
            }
            List<ConduitEntity> result = List.copyOf(route);
            paths.put(key, result); pathReferences += result.size();
            return result;
        }
        void clearPaths() { paths.clear(); pathReferences = 0; }
        <T> T query(World w, Port port, Supplier<T> call, T fallback) {
            if (failedUntil.getOrDefault(port, Long.MIN_VALUE) > w.level.getGameTime()) return fallback;
            try { return call.get(); }
            catch (RuntimeException externalFailure) { failed(w, port, externalFailure); return fallback; }
        }
        void failed(World w, Port port, RuntimeException failure) {
            failedUntil.put(port, w.level.getGameTime() + 20);
            if (warned.add(port)) LOG.warn("Conduit endpoint paused at {} {}: {}", port.position(), port.face(), failure.toString());
        }
    }

    private static final class World {
        final ServerLevel level;
        final Map<Long, ConduitEntity> loaded = new HashMap<>();
        final Map<Long, Set<ConduitEntity>> chunks = new HashMap<>();
        final Set<ConduitEntity> pending = new LinkedHashSet<>(), sync = new LinkedHashSet<>();
        final Set<Network> networks = new LinkedHashSet<>();
        Network building;
        boolean running;
        World(ServerLevel level) { this.level = level; }
        void invalidate(Network n) {
            if (n == null || n.retired) return;
            n.retired = true; n.valid = false; n.pressure = 0; networks.remove(n);
            n.clearPaths();
            if (building == n) building = null;
            for (var node : n.nodes.values()) if (node.pipe.network == n) { node.pipe.network = null; pending.add(node.pipe); }
        }
        void build() {
            int budget = 256;
            while (budget > 0) {
                Network n = building;
                if (n == null) {
                    if (pending.isEmpty()) break;
                    var iterator = pending.iterator(); var seed = iterator.next(); iterator.remove();
                    if (seed.isRemoved() || loaded.get(seed.getBlockPos().asLong()) != seed || seed.network != null) { budget--; continue; }
                    n = new Network(); n.attach(seed, null); networks.add(n); building = n;
                }
                Node node = n.frontier.poll();
                if (node == null) { n.valid = true; building = null; continue; }
                budget--;
                for (Direction side : Direction.values()) {
                    if (!node.pipe.open(side)) continue;
                    var nextPos = node.pipe.getBlockPos().relative(side);
                    var next = loaded.get(nextPos.asLong());
                    if (next != null && !next.isRemoved() && next.open(side.getOpposite())) {
                        if (next.network == n) continue;
                        if (next.network != null) { invalidate(next.network); invalidate(n); break; }
                        n.attach(next, node); pending.remove(next);
                    } else if (next == null) {
                        var port = new Port(node.pipe.getBlockPos(), side);
                        // Keep every boundary coordinate. Capability absence may change without a block update.
                        // Empty space is cheap to reject before capability lookup during a work pulse.
                        n.ports.add(port);
                    }
                }
                if (!n.retired && n.frontier.isEmpty()) { n.valid = true; building = null; }
            }
        }
        void tick() {
            if (running) return;
            running = true;
            try {
                build(); var journal = SuctionTransferStore.get(level);
                if (journal.supported()) for (Network n : List.copyOf(networks)) if (n.valid) work(n, journal);
                for (var pipe : List.copyOf(sync)) if (!pipe.isRemoved() && level.hasChunkAt(pipe.getBlockPos()))
                    level.sendBlockUpdated(pipe.getBlockPos(), pipe.getBlockState(), pipe.getBlockState(), 2);
                sync.clear();
            } finally { running = false; }
        }
        int allowance(Network n, List<ConduitEntity> path) {
            if (!n.valid || path.isEmpty()) return 0;
            int amount = Integer.MAX_VALUE;
            for (var pipe : path) {
                if (pipe.network != n || !pipe.available(level)) return 0;
                amount = Math.min(amount, pipe.credit(level.getGameTime()));
            }
            return amount;
        }
        SuctionTransferStore.Outlet outlet(BlockPos pos) {
            return new SuctionTransferStore.Outlet(level.dimension().location(), pos, SuctionTransferStore.Scope.CONDUIT);
        }
        ConduitEndpoint endpoint(Network n, Port port) {
            if (!level.hasChunkAt(port.position()) || level.getBlockState(port.position()).isAir()) return null;
            return n.query(this, port, () -> ConduitEndpoint.resolve(level, port), null);
        }
        void work(Network n, SuctionTransferStore journal) {
            if (!n.ready) return;
            n.ready = false;
            if (n.nodes.values().stream().noneMatch(node -> node.pipe.credit(level.getGameTime()) > 0)) return;
            var inputs = new ArrayList<Port>(); var outputs = new ArrayList<Port>();
            int pressure = 0;
            for (Port port : n.ports) {
                var e = endpoint(n, port); if (e == null) continue;
                if (n.query(this, port, e::input, false)) { inputs.add(port); pressure = Math.max(pressure, n.query(this, port, e::pressure, 0)); }
                if (n.query(this, port, e::output, false)) outputs.add(port);
            }
            // One fixed interface offset permits native strict downstream > upstream comparisons.
            // There is no per-pipe loss, and only external consumers contribute pressure.
            n.pressure = Math.max(0, pressure - 1);
            n.drawSources = List.copyOf(outputs);
            if (inputs.isEmpty()) return;
            int turn = n.cursor++; Collections.rotate(inputs, Math.floorMod(turn, inputs.size()));
            if (!outputs.isEmpty()) Collections.rotate(outputs, Math.floorMod(turn, outputs.size()));
            // Settled buffers and partial receipts drain before any fresh foreign extraction.
            if (n.hasLocalMatter(journal, level.dimension().location())) {
                var nodes = new ArrayList<>(n.nodes.values()); Collections.rotate(nodes, Math.floorMod(turn, nodes.size()));
                for (Node node : nodes) {
                    if (!n.valid) return;
                    var receipt = journal.receipt(outlet(node.pipe.getBlockPos()));
                    if (receipt != null) { if (!receipt.uncertain()) settle(n, journal, receipt, inputs); continue; }
                    var aspects = new ArrayList<>(node.pipe.stock().keySet());
                    if (!aspects.isEmpty()) Collections.rotate(aspects, Math.floorMod(turn, aspects.size()));
                    for (var aspect : aspects) transfer(n, journal, node.pipe.getBlockPos(), null, aspect, inputs);
                }
            }
            for (Port port : outputs) {
                if (!n.valid) return;
                if (journal.holds(outlet(port.pipe()))) continue;
                var source = endpoint(n, port); if (source == null) continue;
                var aspects = new ArrayList<>(n.query(this, port, source::aspects, List.<AspectId>of()));
                if (!aspects.isEmpty()) Collections.rotate(aspects, Math.floorMod(turn, aspects.size()));
                for (var aspect : aspects) transfer(n, journal, port.pipe(), port, aspect, inputs);
            }
        }
        Draw draw(Network n, Port exit, AspectId wanted, int limit, EssentiaTransferMode mode) {
            var target = endpoint(n, exit);
            if (target == null || !n.query(this, exit, target::input, false)) return null;
            int pressure = n.query(this, exit, target::pressure, 0);
            if (pressure <= 0) return null;
            AspectId filter = n.query(this, exit, () -> target.transport.suctionType(exit.face()), null);
            if (filter != null && wanted != null && !filter.equals(wanted)) return null;
            AspectId aspect = wanted == null ? filter : wanted;
            var journal = SuctionTransferStore.get(level);
            if (!journal.supported()) return null;
            if (n.hasLocalMatter(journal, level.dimension().location())) for (Node node : n.nodes.values()) {
                var buffer = node.pipe;
                var owner = buffer.getBlockPos();
                var receipt = journal.receipt(outlet(owner));
                if (receipt != null) {
                    if (receipt.uncertain() || receipt.face() != null && owner.relative(receipt.face()).equals(exit.position()))
                        continue;
                    var type = new AspectId(receipt.aspect());
                    if (aspect != null && !aspect.equals(type)) continue;
                    var path = n.path(owner, exit.pipe());
                    int amount = Math.min(receipt.amount(), Math.min(limit, allowance(n, path)));
                    if (amount == 0) continue;
                    if (mode.executes()) {
                        for (var pipe : path) pipe.spend(amount);
                        journal.accepted(receipt, amount);
                    }
                    return new Draw(type, amount);
                }
                if (owner.equals(exit.pipe()) || !buffer.hasStock()) continue;
                var path = n.path(owner, exit.pipe());
                int capacity = Math.min(limit, allowance(n, path));
                for (var entry : buffer.stock().entrySet()) {
                    if (aspect != null && !aspect.equals(entry.getKey())) continue;
                    int amount = Math.min(capacity, entry.getValue());
                    if (amount <= 0) continue;
                    if (mode.executes()) {
                        for (var pipe : path) pipe.spend(amount);
                        buffer.removeStored(entry.getKey(), amount);
                    }
                    return new Draw(entry.getKey(), amount);
                }
            }
            for (Port port : n.drawSources) {
                if (port.position().equals(exit.position()) || journal.holds(outlet(port.pipe()))) continue;
                var source = endpoint(n, port);
                if (source == null || !n.query(this, port, source::output, false)
                        || n.query(this, port, source::pressure, Integer.MAX_VALUE) >= pressure
                        || n.query(this, port, source::minimum, Integer.MAX_VALUE) > pressure) continue;
                var path = n.path(port.pipe(), exit.pipe());
                int capacity = Math.min(limit, allowance(n, path));
                if (capacity <= 0) continue;
                var types = aspect == null ? n.query(this, port, source::aspects, List.<AspectId>of()) : List.of(aspect);
                for (AspectId type : types) {
                    int amount = n.query(this, port, () -> source.extract(type, capacity, EssentiaTransferMode.SIMULATE), 0);
                    if (amount <= 0 || allowance(n, path) < amount || !source.current() || !target.current()) continue;
                    if (!mode.executes()) return new Draw(type, amount);
                    for (var pipe : path) pipe.spend(amount);
                    var receipt = journal.reserve(outlet(port.pipe()), port.direction(), type.value(), amount);
                    try { receipt = journal.extracted(receipt, source.extract(type, amount, mode)); }
                    catch (RuntimeException failure) { n.failed(this, port, failure); return null; }
                    if (receipt == null) continue;
                    if (!n.valid || !target.current()) return null;
                    int moved = receipt.amount();
                    journal.accepted(receipt, moved);
                    return new Draw(type, moved);
                }
            }
            return null;
        }
        void transfer(Network n, SuctionTransferStore journal, BlockPos owner, Port sourcePort, AspectId aspect, List<Port> inputs) {
            // Actual contents and capacity are re-queried after each synchronous foreign mutation.
            for (Port targetPort : inputs) {
                if (!n.valid || journal.holds(outlet(owner))) return;
                if (sourcePort != null && sourcePort.position().equals(targetPort.position())) continue;
                var path = n.path(owner, targetPort.pipe());
                while (allowance(n, path) > 0 && !journal.holds(outlet(owner))) {
                    var target = endpoint(n, targetPort); if (target == null) break;
                    var source = sourcePort == null ? null : endpoint(n, sourcePort);
                    if (sourcePort != null && source == null) break;
                    int sourcePressure = source == null ? 0 : n.query(this, sourcePort, source::pressure, Integer.MAX_VALUE);
                    int minimum = source == null ? 0 : n.query(this, sourcePort, source::minimum, Integer.MAX_VALUE);
                    int targetPressure = n.query(this, targetPort, target::pressure, 0);
                    if (targetPressure <= sourcePressure || targetPressure < minimum) break;
                    int requested = Math.min(ConduitEntity.THROUGHPUT, allowance(n, path));
                    int space = n.query(this, targetPort, () -> target.receive(aspect, requested, EssentiaTransferMode.SIMULATE), 0);
                    if (space == 0) break;
                    var buffer = loaded.get(owner.asLong());
                    int amount = source == null ? Math.min(space, buffer.stored(aspect))
                            : n.query(this, sourcePort, () -> source.extract(aspect, space, EssentiaTransferMode.SIMULATE), 0);
                    if (amount <= 0 || allowance(n, path) < amount || !target.current() || source != null && !source.current()) break;
                    for (var pipe : path) pipe.spend(amount); // reserve entitlement before invoking foreign code
                    SuctionTransferStore.Receipt receipt;
                    if (source == null) {
                        receipt = journal.recordBuffer(outlet(owner), aspect.value(), amount);
                        buffer.removeStored(aspect, amount);
                    } else {
                        receipt = journal.reserve(outlet(owner), sourcePort.direction(), aspect.value(), amount);
                        try { receipt = journal.extracted(receipt, source.extract(aspect, amount, EssentiaTransferMode.EXECUTE)); }
                        catch (RuntimeException failure) { n.failed(this, sourcePort, failure); return; }
                    }
                    if (receipt == null) break;
                    if (!n.valid || !target.current() || path.stream().anyMatch(p -> !p.available(level))) return;
                    deliver(n, journal, receipt, targetPort);
                }
            }
        }
        void settle(Network n, SuctionTransferStore journal, SuctionTransferStore.Receipt receipt, List<Port> inputs) {
            for (Port port : inputs) {
                var current = journal.receipt(receipt.outlet());
                if (!n.valid || current == null || current.uncertain()) return;
                // Never feed a retained extraction straight back into the same boundary face.
                if (port.pipe().equals(receipt.outlet().position()) && port.direction() == receipt.face()) continue;
                var path = n.path(receipt.outlet().position(), port.pipe());
                if (allowance(n, path) <= 0) continue;
                var target = endpoint(n, port); if (target == null || n.query(this, port, target::pressure, 0) <= 0) continue;
                int maximum = Math.min(current.amount(), allowance(n, path));
                int quote = n.query(this, port, () -> target.receive(new AspectId(current.aspect()), maximum, EssentiaTransferMode.SIMULATE), 0);
                if (quote <= 0 || allowance(n, path) < quote) continue;
                for (var pipe : path) pipe.spend(quote);
                deliver(n, journal, current, port, quote);
            }
        }
        void deliver(Network n, SuctionTransferStore journal, SuctionTransferStore.Receipt receipt, Port target) {
            deliver(n, journal, receipt, target, receipt.amount());
        }
        void deliver(Network n, SuctionTransferStore journal, SuctionTransferStore.Receipt receipt, Port port, int maximum) {
            var target = endpoint(n, port); if (target == null) return;
            int quote = n.query(this, port, () -> target.receive(new AspectId(receipt.aspect()), maximum, EssentiaTransferMode.SIMULATE), 0);
            if (quote <= 0 || !n.valid || !target.current()) return;
            var uncertain = journal.quarantine(receipt);
            final int actual;
            try { actual = target.receive(new AspectId(receipt.aspect()), quote, EssentiaTransferMode.EXECUTE); }
            catch (RuntimeException failure) { n.failed(this, port, failure); return; }
            if (actual == 0) journal.retryable(uncertain); else journal.accepted(uncertain, actual);
        }
    }
    private ConduitNetworks() {}
}
