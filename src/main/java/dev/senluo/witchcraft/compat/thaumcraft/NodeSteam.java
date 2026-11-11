package dev.senluo.witchcraft.compat.thaumcraft;

import dev.tc4port.thaumcraft.api.ThaumcraftContent;
import dev.tc4port.thaumcraft.api.event.NodeEcologyEvent;
import dev.tc4port.thaumcraft.api.node.NodeApi;
import dev.tc4port.thaumcraft.api.node.VisSource;
import dev.tc4port.thaumcraft.api.aspect.VisAction;
import dev.tc4port.thaumcraft.api.aspect.VisChannel;
import dev.senluo.witchcraft.time.ExtraTicks;
import dev.senluo.witchcraft.time.AccelerationSources;
import dev.senluo.witchcraft.time.WorkTicks;
import dev.senluo.witchcraft.time.WorkSources;
import dev.senluo.witchcraft.ritual.RitualWorkBudget;
import net.minecraft.world.level.block.entity.BlockEntity;
import java.math.BigInteger;
import java.util.EnumMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/** Uses the native behavior clock, including recharge, discharge, ecology and unstable output rolls. */
public final class NodeSteam {
    private record Work(BlockEntity node, BigInteger multiplier) {}
    private static final ThreadLocal<Work> CURRENT = new ThreadLocal<>();
    private static final boolean BOTANIA = net.neoforged.fml.ModList.get().isLoaded("botania");
    private static final VisChannel[] CHANNELS = VisChannel.values();
    private NodeSteam() {}

    /** Resolve the field once for the original opportunity and every extra native step it owns. */
    public static void opportunity(ServerLevel level, BlockPos pos, BlockEntity node, Runnable nativeTick) {
        NodeOutput.clear(node);
        BigInteger rate = available(level, pos, node)
                ? NodePromotion.influence(level, pos).multiplier() : BigInteger.ONE;
        if (node.getBlockState().is(ThaumcraftContent.block("aura_node"))) {
            var scale = new RechargeScale(NodeRituals.bonus(node), rate);
            if (!scale.equals(RECHARGE.put(node, scale))) {
                var ecology = NodeApi.ecology(level, pos);
                if (ecology != null) ecology.refreshRecharge();
            }
        }
        Work previous = CURRENT.get();
        if (rate.equals(BigInteger.ONE) && previous == null) {
            nativeTick.run();
        } else {
            CURRENT.set(new Work(node, rate));
            try {
                nativeTick.run();
                afterNative(level, pos, node, nativeTick, rate);
            } finally {
                if (previous == null) CURRENT.remove(); else CURRENT.set(previous);
            }
        }
        if (BOTANIA) dev.senluo.witchcraft.compat.botania.NodeDaisy.tick(level, node);
    }

    private static void afterNative(ServerLevel level, BlockPos pos, BlockEntity node,
                                    Runnable nativeTick, BigInteger rate) {
        if (ExtraTicks.active() || !available(level, pos, node)) return;
        if (rate.equals(BigInteger.ONE)) return;
        boolean energized = node.getBlockState().is(ThaumcraftContent.block("energized_aura_node"))
                && level.getCapability(dev.tc4port.thaumcraft.api.node.VisCapabilities.SOURCE, pos, null) instanceof NodeOutput;
        var previousOutputs = new EnumMap<VisChannel, BigInteger>(VisChannel.class);
        Runnable advance = energized ? () -> {
            // Transfer the old balance exactly once, just before the native ticker replaces it.
            collect(previousOutputs, (VisSource) node);
            nativeTick.run();
        } : nativeTick;
        AccelerationSources.run(NodePromotion.influence(level, pos).owners(), () -> ExtraTicks.run(() -> {
            int left = RitualWorkBudget.claim(level, RitualWorkBudget.Kind.NODE, pos, rate.subtract(BigInteger.ONE));
            while (left-- > 0 && available(level, pos, node)) {
                WorkTicks.additional(level, node, advance);
            }
        }));
        // The native ticker refreshes rather than adds its per-tick output. Preserve every produced pool,
        // but only until the next native opportunity. Unload/reload cannot store free network energy.
        if (!previousOutputs.isEmpty() && available(level, pos, node) && node.getBlockState().is(ThaumcraftContent.block("energized_aura_node")))
            NodeOutput.publish(node, previousOutputs);
    }

    /** Only the native discharge growth roll opts into this; hungry-node feeding remains unchanged. */
    private record RechargeScale(int ring, BigInteger speed) {}
    private static final java.util.Map<BlockEntity, RechargeScale> RECHARGE = new java.util.WeakHashMap<>();

    static void register() {
        WorkSources.registerProcess(ThaumcraftContent.blockEntity("aura_node"), new WorkSources.Process<>() {
            public double price(ServerLevel level, BlockEntity node) { return WorkSources.UPDATE_PRICE; }
            public void tick(ServerLevel level, BlockEntity node, Runnable nativeTick) {
                opportunity(level, node.getBlockPos(), node, nativeTick);
            }
        });
        AccelerationSources.registerObserver((level, entity) -> {
            if (entity.getType() != ThaumcraftContent.blockEntity("aura_node")) return;
            var influence = NodePromotion.influence(level, entity.getBlockPos());
            if (influence.multiplier().compareTo(BigInteger.ONE) > 0)
                for (var owner : influence.owners()) AccelerationSources.renew(level, entity, owner, 1);
        });
        var bus = net.neoforged.neoforge.common.NeoForge.EVENT_BUS;
        bus.addListener((NodeEcologyEvent.Recharge event) -> {
            if (event.interval() == 0) return;
            var speed = speed(event.level(), event.node()).multiply(BigInteger.valueOf(NodeRituals.bonus(event.level(), event.node())));
            var numerator = BigInteger.valueOf(4L * event.interval());
            event.setInterval(numerator.add(speed).subtract(BigInteger.ONE).divide(speed).max(BigInteger.ONE).intValueExact());
        });
        bus.addListener((NodeEcologyEvent.Contest event) -> {
            event.setSourceCurrent(NodeRituals.contest(event.level(), event.node(), event.sourceCurrent()));
            event.setSourceCapacity(NodeRituals.contest(event.level(), event.node(), event.sourceCapacity()));
            event.setTargetCurrent(NodeRituals.contest(event.level(), event.target(), event.targetCurrent()));
            event.setTargetCapacity(NodeRituals.contest(event.level(), event.target(), event.targetCapacity()));
        });
        bus.addListener((NodeEcologyEvent.PaleRecovery event) ->
                event.setProbability(Math.min(1, event.probability() * NodeRituals.bonus(event.level(), event.node()) / 4)));
        bus.addListener((NodeEcologyEvent.Growth event) -> event.setProbability(growthProbability(event.probability(),
                speed(event.level(), event.node()), NodeRituals.bonus(event.level(), event.node()))));
    }

    public static double growthProbability(double base, BigInteger speed, int ring) {
        if (base == 0) return 0;
        return Math.min(1, base * (ring / 4.0) * Math.max(1, speed.doubleValue() / 4));
    }

    private static BigInteger speed(ServerLevel level, BlockPos pos) {
        Work work = CURRENT.get();
        return work != null && work.node.getLevel() == level && work.node.getBlockPos().equals(pos)
                ? work.multiplier : NodePromotion.influence(level, pos).multiplier();
    }

    private static boolean available(ServerLevel level, BlockPos pos, BlockEntity node) {
        return !node.isRemoved() && level.hasChunkAt(pos) && level.shouldTickBlocksAt(pos)
                && level.getBlockEntity(pos) == node
                && (node.getBlockState().is(ThaumcraftContent.block("aura_node")) || node.getBlockState().is(ThaumcraftContent.block("energized_aura_node")))
                && level.hasChunksAt(pos.offset(-2, 0, -2), pos.offset(2, 0, 2));
    }
    private static void collect(Map<VisChannel, BigInteger> produced, VisSource source) {
        for (VisChannel channel : CHANNELS) {
            int remaining = source.takeVis(channel, Integer.MAX_VALUE, VisAction.EXECUTE, 0);
            if (remaining > 0) produced.merge(channel, BigInteger.valueOf(remaining), BigInteger::add);
        }
    }
}
