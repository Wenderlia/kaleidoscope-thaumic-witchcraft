package dev.senluo.witchcraft.compat.thaumcraft;

import dev.senluo.witchcraft.ritual.SuctionCharge;
import dev.senluo.witchcraft.ritual.StructuralRitualStore;
import dev.senluo.witchcraft.ritual.SuctionTransferStore;
import dev.tc4port.thaumcraft.api.aspect.AspectApi;
import dev.tc4port.thaumcraft.api.aspect.AspectId;
import dev.tc4port.thaumcraft.api.essentia.EssentiaApi;
import dev.tc4port.thaumcraft.api.ThaumcraftTags;
import dev.tc4port.thaumcraft.api.essentia.EssentiaTransferMode;
import dev.tc4port.thaumcraft.api.essentia.EssentiaTransport;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Loaded-position discovery and native sided transfers. No artificial machine updates or copied inventories. */
final class SuctionChannels {
    private static final Logger LOG = LoggerFactory.getLogger(SuctionChannels.class);
    private static final Set<String> WARNED = new HashSet<>();
    private static final Direction[] FACES = Direction.values();
    private static final Map<RateKey, SuctionCharge.Rate> RATES = new LinkedHashMap<>(32, .75F, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<RateKey, SuctionCharge.Rate> e) { return size() > 64; }
    };
    private record RateKey(int groups, int interval, int perPulse) {}
    record Pulse(UUID ritual, BlockPos center, SuctionCharge charge, long amount) {}
    private static final class Contribution {
        final UUID ritual; long remaining;
        Contribution(Pulse pulse) { ritual = pulse.ritual; remaining = pulse.amount; }
    }
    private static long remaining(ServerLevel level, List<Contribution> contributions) {
        long sum = 0;
        for (var contribution : contributions) if (contribution.remaining > 0 && StructuralRitualStore.get(level).running(level, contribution.ritual))
            sum = sum > Long.MAX_VALUE - contribution.remaining ? Long.MAX_VALUE : sum + contribution.remaining;
        return sum;
    }
    private record Destination(BlockPos position, Direction face) {}
    private record Route(BlockPos source, Direction sourceFace, BlockPos target, Direction targetFace, AspectId aspect) {}
    private record Sent(Route route, long tick) {}
    private static final Map<ServerLevel, Map<BlockPos, Sent>> VISUALS = new WeakHashMap<>();
    private SuctionChannels() {}
    static SuctionCharge.Rate rate(SuctionCharge charge, int interval, int perPulse) {
        return RATES.computeIfAbsent(new RateKey(charge.flowGroups(), interval, perPulse), ignored -> charge.rate(interval, perPulse));
    }
    static void unload(ServerLevel level) { VISUALS.remove(level); }

    static void tick(ServerLevel level, List<Pulse> channels) {
        var journal = SuctionTransferStore.get(level);
        if (!journal.supported()) return;
        var pending = journal.inDimension(level.dimension().location());
        if (channels.isEmpty() && pending.isEmpty()) {
            var old = VISUALS.get(level);
            if (old != null && level.getGameTime() % 100 == 0) {
                old.values().removeIf(v -> level.getGameTime() < v.tick || level.getGameTime() - v.tick > 100);
                if (old.isEmpty()) VISUALS.remove(level);
            }
            return;
        }
        var index = SuctionEndpointIndex.get(level);
        var sent = VISUALS.computeIfAbsent(level, ignored -> new HashMap<>());
        long now = level.getGameTime();
        if (now % 100 == 0) sent.values().removeIf(v -> now < v.tick || now - v.tick > 100);
        // Settles only matter already removed by a prior committed transfer. No offline flow entitlement.
        if (now % 20 == 0) for (var receipt : pending) {
            if (receipt.uncertain() || !loaded(level, receipt.outlet().position())) continue;
            settle(level, journal, receipt, index, sent);
        }
        Map<BlockPos, List<Contribution>> budgets = new TreeMap<>();
        for (Pulse channel : channels) for (BlockPos source : index.sources(level, channel.center, channel.charge))
            budgets.computeIfAbsent(source, unused -> new ArrayList<>()).add(new Contribution(channel));
        for (var entry : budgets.entrySet()) {
            BlockPos sourcePos = entry.getKey(); var contributions = entry.getValue(); long budget = remaining(level, contributions);
            if (budget <= 0 || !loaded(level, sourcePos)) continue;
            var outlet = new SuctionTransferStore.Outlet(level.dimension().location(), sourcePos);
            if (journal.holds(outlet)) continue;
            var be = level.getBlockEntity(sourcePos);
            if (!SuctionEndpointIndex.outlet(be)) continue;
            var storage = EssentiaApi.storage(level, sourcePos, null);
            if (storage == null) continue;
            var contents = storage.essentiaContents(null).amounts();
            budget = Math.min(budget, contents.values().stream().filter(n -> n > 0).mapToLong(Integer::longValue).sum());
            var aspects = new ArrayList<>(contents.keySet()); aspects.sort(AspectId::compareTo);
            if (!aspects.isEmpty()) Collections.rotate(aspects, -(int)Math.floorMod(now / 20, aspects.size()));
            if (contents.isEmpty()) continue;
            boolean nozzle = be.getType() == dev.tc4port.thaumcraft.api.ThaumcraftContent.blockEntity("advanced_alchemy_furnace");
            var positions = index.destinations(level, sourcePos);
            for (var aspect : aspects) {
                if (budget <= 0 || journal.holds(outlet)) break;
                for (var destination : destinations(level, sourcePos, aspect, positions)) {
                    if (budget <= 0 || journal.holds(outlet) || level.getBlockEntity(sourcePos) != be) break;
                    Direction face = output(level, aspect, sourcePos, destination.position);
                    if (face == null || nozzle && !loaded(level, sourcePos.relative(face.getOpposite()))) break;
                    var source = endpoint(level, sourcePos, face);
                    if (source == null) continue;
                    int available = source.transferView().availableEssentia(aspect, face);
                    int request = (int)Math.min(budget, Math.max(0, available));
                    if (request <= 0) break;
                    int accepted = insert(level, destination, aspect, request, EssentiaTransferMode.SIMULATE);
                    if (accepted <= 0) continue;
                    int quote = EssentiaApi.take(level, source, aspect, accepted, face, EssentiaTransferMode.SIMULATE);
                    if (quote <= 0 || !loaded(level, destination.position)) continue;
                    source = endpoint(level, sourcePos, face);
                    if (source == null || level.getBlockEntity(sourcePos) != be) break;
                    // Drop revoked contributions before quoting; debit this exact entitlement after extraction.
                    contributions.removeIf(c -> !StructuralRitualStore.get(level).running(level, c.ritual));
                    quote = (int)Math.min(quote, remaining(level, contributions));
                    if (quote <= 0) break;
                    int extracted = EssentiaApi.take(level, source, aspect, quote, face, EssentiaTransferMode.EXECUTE);
                    if (extracted <= 0) continue;
                    var receipt = journal.record(outlet, face, aspect.value(), extracted);
                    int delivered = insert(level, destination, aspect, extracted, EssentiaTransferMode.EXECUTE);
                    if (delivered < 0) journal.quarantine(receipt);
                    else {
                        journal.accepted(receipt, delivered);
                        if (delivered > 0) visualize(level, new Route(sourcePos, face, destination.position, destination.face, aspect), sent);
                    }
                    long debit = extracted;
                    for (var contribution : contributions) { long amount = Math.min(debit, contribution.remaining); contribution.remaining -= amount; debit -= amount; if (debit == 0) break; }
                    budget = Math.min(budget - extracted, remaining(level, contributions));
                }
            }
        }
    }
    private static void settle(ServerLevel level, SuctionTransferStore journal, SuctionTransferStore.Receipt receipt,
            SuctionEndpointIndex index, Map<BlockPos, Sent> sent) {
        var aspect = AspectId.of(receipt.aspect());
        if (AspectApi.registry().get(aspect).isEmpty()) return;
        BlockPos source = receipt.outlet().position();
        for (var destination : destinations(level, source, aspect, index.destinations(level, source))) {
            int quote = insert(level, destination, aspect, receipt.amount(), EssentiaTransferMode.SIMULATE);
            if (quote <= 0) continue;
            int accepted = insert(level, destination, aspect, quote, EssentiaTransferMode.EXECUTE);
            if (accepted < 0) { journal.quarantine(receipt); return; }
            journal.accepted(receipt, accepted);
            if (accepted > 0) visualize(level, new Route(source, receipt.face(), destination.position, destination.face, aspect), sent);
            if (accepted == receipt.amount()) return;
            receipt = new SuctionTransferStore.Receipt(receipt.outlet(), receipt.face(), receipt.aspect(), receipt.amount() - accepted, false);
        }
    }
    private static boolean loaded(ServerLevel level, BlockPos pos) {
        return level.hasChunkAt(pos) && level.shouldTickBlocksAt(pos);
    }
    /** Re-query the public sided capability; never retain a provider across ticks or invalidation. */
    static EssentiaTransport endpoint(ServerLevel level, BlockPos pos, Direction face) {
        if (!loaded(level, pos)) return null;
        try {
            level.getProfiler().incrementCounter("kw_suction_capability_query");
            return EssentiaApi.transport(level, pos, face);
        }
        catch (RuntimeException failure) {
            warn(level.getBlockState(pos).getBlock(), "capability lookup", failure.toString()); return null;
        }
    }
    private static Direction output(ServerLevel level, AspectId aspect, BlockPos position, BlockPos target) {
        // Only the two audited native source types reach this method.
        Direction result = null; double best = -Double.MAX_VALUE;
        for (var face : FACES) {
            var source = endpoint(level, position, face);
            var view = source == null ? null : source.transferView();
            if (view == null || !view.isConnectable(face) || !view.canOutputTo(face) || view.availableEssentia(aspect, face) <= 0) continue;
            double dot = (double)(target.getX()-position.getX()) * face.getStepX()
                    + (double)(target.getY()-position.getY()) * face.getStepY() + (double)(target.getZ()-position.getZ()) * face.getStepZ();
            if (dot > best) { best = dot; result = face; }
        }
        return result;
    }
    private static List<Destination> destinations(ServerLevel level, BlockPos source, AspectId aspect, List<BlockPos> positions) {
        var matching = new ArrayList<Destination>();
        var other = new ArrayList<Destination>();
        for (BlockPos pos : positions) {
            for (var face : FACES) {
                var transport = endpoint(level, pos, face);
                if (transport == null) continue;
                try {
                    var view = transport.transferView();
                    if (view == null) { warn(transport, "query", "null transfer view"); continue; }
                    if (view.isConnectable(face) && view.canInputFrom(face)) {
                        boolean same = EssentiaStores.stocked(EssentiaStores.at(level, pos, face), face, aspect)
                                || aspect.equals(view.essentiaType(face)) && view.essentiaAmount(face) > 0;
                        (same ? matching : other).add(new Destination(pos, face));
                    }
                } catch (RuntimeException failure) { warn(transport, "query", failure.toString()); }
            }
        }
        // Geometry already has stable distance order. Keep live same-aspect priority without sorting every face.
        matching.addAll(other);
        return matching;
    }
    /** Negative means EXECUTE is ambiguous; its receipt is quarantined instead of guessed or replayed. */
    private static int insert(ServerLevel level, Destination destination, AspectId aspect, int amount, EssentiaTransferMode mode) {
        if (amount <= 0) return 0;
        var transport = endpoint(level, destination.position, destination.face);
        if (transport == null) return 0;
        boolean executing = false;
        try {
            var view = transport.transferView();
            if (view == null || !view.isConnectable(destination.face) || !view.canInputFrom(destination.face)) return 0;
            // Native jar addEssentia bypasses label filtering and void jars accept overflow destructively.
            // Enforce the public inspection contract before simulation AND execution, also for addon jars.
            var storage = EssentiaStores.at(level, destination.position, destination.face);
            amount = EssentiaStores.limit(level, destination.position, storage, destination.face, aspect, amount);
            if (amount <= 0) return 0;
            AspectId wanted = view.suctionType(destination.face);
            if (!level.getBlockState(destination.position).is(ThaumcraftTags.ESSENTIA_JARS)
                    && wanted != null && !aspect.equals(wanted)) return 0;
            executing = mode.executes();
            level.getProfiler().incrementCounter(executing ? "kw_suction_insert_execute" : "kw_suction_insert_simulate");
            int accepted = view.addEssentia(aspect, amount, destination.face, mode);
            if (accepted < 0 || accepted > amount) {
                warn(transport, "insert", "out-of-contract transfer result " + accepted + "/" + amount);
                return executing ? -1 : 0;
            }
            if (executing && accepted > 0) {
                level.getProfiler().incrementCounter("kw_suction_delivery_success");
                level.getProfiler().incrementCounter("kw_suction_delivered_units", accepted);
            }
            return accepted;
        } catch (RuntimeException failure) {
            warn(transport, "insert", failure.toString()); return executing ? -1 : 0;
        }
    }
    private static void warn(Object provider, String operation, String reason) {
        String key = provider.getClass().getName() + ":" + operation;
        if (WARNED.add(key)) LOG.warn("Suction endpoint {} failed {}: {}. Ambiguous executed transfers are retained, not retried.",
                provider.getClass().getName(), operation, reason);
    }
    private static Vec3 mouth(BlockPos pos, Direction face) {
        return Vec3.atCenterOf(pos).add(face.getStepX() * .51, face.getStepY() * .51, face.getStepZ() * .51);
    }
    private static void visualize(ServerLevel level, Route route, Map<BlockPos, Sent> sent) {
        long now = level.getGameTime();
        Sent previous = sent.get(route.source);
        // Presentation is bounded per outlet, independent of transferred amount or number of overlapping rituals.
        if (previous != null && now >= previous.tick && now - previous.tick < 10) return;
        int color = AspectApi.registry().get(route.aspect).map(a -> a.color()).orElse(0xB7D6C8);
        sent.put(route.source, new Sent(route, now));
        SuctionPresentation.send(level, mouth(route.source, route.sourceFace), mouth(route.target, route.targetFace), color);
    }
}
