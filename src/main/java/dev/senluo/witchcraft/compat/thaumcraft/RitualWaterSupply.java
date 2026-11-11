package dev.senluo.witchcraft.compat.thaumcraft;

import net.minecraft.world.level.block.entity.BlockEntity;
import dev.senluo.witchcraft.ritual.*;
import dev.tc4port.thaumcraft.api.aspect.AspectId;
import dev.tc4port.thaumcraft.api.essentia.*;

import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

/** Event-loop adapter: discovery, exact extraction accounting, receiver commit, then presentation. */
final class RitualWaterSupply {
    private static final AspectId AQUA = AspectId.parse("aqua");
    private static final Map<ServerLevel, Map<BlockPos, Long>> FAULTS = new WeakHashMap<>();
    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger(RitualWaterSupply.class);
    private RitualWaterSupply() {}
    static void unload(ServerLevel level) { FAULTS.remove(level); }
    static void tick(ServerLevel level) {
        if (level.getGameTime() % 20 != 0) return;
        var journal = SuctionTransferStore.get(level);
        if (!journal.supported()) return;
        var failures = FAULTS.computeIfAbsent(level, ignored -> new HashMap<>());
        failures.entrySet().removeIf(e -> e.getValue() <= level.getGameTime());
        var index = RitualWaterIndex.get(level);
        Set<BlockPos> serviced = new HashSet<>(), usedSources = new HashSet<>();
        var centers = index.centers(level);
        rotate(centers, level.getGameTime()/20);
        for (var center : centers) {
            var pot = EssentiaStores.pot(level.getBlockEntity(center));
            if (pot == null || SteamRituals.profile(pot).filter(p -> p.rings().count() > 0).isEmpty()) continue;
            var anchor = pot.getData(RitualContent.RITUAL_ANCHOR);
            if (!anchor.supported()) continue;
            var sources = index.nearby(level, center, false); rotate(sources, level.getGameTime()/20);
            for (BlockPos source : sources) {
                if (usedSources.contains(source) || failures.containsKey(source)) continue;
                var outlet = new SuctionTransferStore.Outlet(level.dimension().location(), source);
                if (journal.holds(outlet)) continue;
                var ref = new EssentiaSourceRef(level.dimension(), source);
                try {
                    if (EssentiaApi.extract(level, ref, AQUA, 1, EssentiaTransferMode.SIMULATE) != 1) continue;
                } catch (RuntimeException ex) { fault(level, source, ex); continue; }
                var targets = index.nearby(level, source, true); rotate(targets, level.getGameTime()/20);
                for (BlockPos target : targets) {
                    if (serviced.contains(target) || failures.containsKey(target)) continue;
                    WaterReceivers.Fill fill;
                    try { fill = WaterReceivers.quote(level, target, anchor.waterSupplyThirds()).orElse(null); }
                    catch (RuntimeException ex) { fault(level, target, ex); continue; }
                    if (fill == null) continue;
                    EssentiaSource provider;
                    try {
                        if (!fill.ready() || EssentiaApi.extract(level, ref, AQUA, fill.cost(), EssentiaTransferMode.SIMULATE) != fill.cost()) continue;
                        provider = EssentiaApi.source(level, source);
                    } catch (RuntimeException ex) { fault(level, target, ex); continue; }
                    if (provider == null) continue;
                    int cost = fill.cost(), nextThirds = fill.nextThirds();
                    if (cost < 1 || cost > 3 || nextThirds < 0 || nextThirds > 2) {
                        fault(level, target, new IllegalArgumentException("Invalid receiver quote")); continue;
                    }
                    usedSources.add(source);
                    // Keep internal ledger mutations outside foreign exception boundaries.
                    var pending = journal.reserve(outlet, Direction.UP, AQUA.value(), cost);
                    int taken;
                    try {
                        // Direct public contract preserves exceptions; the convenience facade maps them to zero.
                        taken = provider.extractEssentia(AQUA, cost, EssentiaTransferMode.EXECUTE);
                    } catch (RuntimeException ex) { fault(level, source, ex); break; }
                    if (taken < 0 || taken > cost) {
                        fault(level, source, new IllegalStateException("Invalid essentia extraction amount")); break;
                    }
                    var receipt = journal.extracted(pending, taken);
                    // TC4R permits partial extraction. Keep that confirmed matter in the existing
                    // delivery journal; do not fill water until the entire quoted fee was paid.
                    if (taken != cost) break;
                    receipt = journal.quarantine(receipt);
                    WaterReceivers.Result result;
                    try { result = fill.commit(); }
                    catch (RuntimeException ex) { fault(level, target, ex); break; }
                    if (result == WaterReceivers.Result.FILLED) {
                        journal.accepted(receipt, cost);
                        anchor.waterSupplyThirds(nextThirds); pot.setChanged();
                        serviced.add(target);
                        SuctionPresentation.send(level, Vec3.atCenterOf(source), Vec3.atCenterOf(target), 0x3CD4FC);
                    } else if (result == WaterReceivers.Result.UNCHANGED) {
                        // Known unchanged: retry only matter whose extraction is confirmed.
                        journal.retryable(receipt);
                    } else fault(level, target, new IllegalStateException("Receiver violated exact-fill contract"));
                    break;
                }
            }
        }
    }
    private static void rotate(List<?> values, long cursor) {
        if (!values.isEmpty()) Collections.rotate(values, -(int)Math.floorMod(cursor, (long)values.size()));
    }
    private static void fault(ServerLevel level, BlockPos pos, RuntimeException ex) {
        FAULTS.computeIfAbsent(level, ignored -> new HashMap<>()).put(pos.immutable(), level.getGameTime()+1200);
        LOGGER.warn("Ritual water provider at {} in {} suspended for 60s: {}", pos, level.dimension().location(), ex.toString());
    }
}
