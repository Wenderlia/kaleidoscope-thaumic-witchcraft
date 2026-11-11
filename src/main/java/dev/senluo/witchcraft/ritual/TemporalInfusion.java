package dev.senluo.witchcraft.ritual;

import dev.senluo.witchcraft.time.AccelerationSources;
import dev.senluo.witchcraft.time.ExtraTicks;
import dev.senluo.witchcraft.time.TemporalContent;
import dev.senluo.witchcraft.time.WorkTicks;
import java.math.BigInteger;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.TickingBlockEntity;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

public final class TemporalInfusion implements RitualCenters.Behavior {
    private static final StructuralRitualStore.Definition RITE = new StructuralRitualStore.Definition(
            ResourceLocation.fromNamespaceAndPath("kaleidoscope_witchcraft", "temporal_infusion"),
            1, 60, new int[]{20,40,60}, RitualRings.MEDIUM, RitualResearch.thaumcraft("WITCHERY_CIRCLE_BASICS"));
    private record Active(UUID owner, RitualSite site) {}
    private static final Map<ServerLevel, Map<BlockPos, List<Active>>> ACTIVE = new IdentityHashMap<>();
    private static CenterOfferings offerings;
    private TemporalInfusion() {}
    public static void register() {
        offerings = new CenterOfferings(RITE, List.of(
                stack -> stack.is(TemporalContent.FRUIT_ITEM.get()) && dev.senluo.witchcraft.time.FruitItem.grade(stack) == 5,
                stack -> stack.is(TemporalContent.LOG_ITEM.get()), stack -> stack.is(Items.GLASS_BOTTLE)));
        RitualCenters.register(new TemporalInfusion());
        WorkTicks.registerEffect(TemporalInfusion::afterNative);
        AccelerationSources.registerObserver((level, entity) -> {
            for (var owner : owners(level, entity)) AccelerationSources.renew(level, entity, owner, 1);
        });
        NeoForge.EVENT_BUS.addListener(net.neoforged.bus.api.EventPriority.HIGHEST, (LevelTickEvent.Pre event) -> {
            var active = ACTIVE.get(event.getLevel());
            if (active != null) active.clear();
        });
        NeoForge.EVENT_BUS.addListener((LevelEvent.Unload event) -> ACTIVE.remove(event.getLevel()));
    }
    @Override public StructuralRitualStore.Definition definition() { return RITE; }
    @Override public net.minecraft.world.InteractionResult activate(ServerLevel level, RitualSite site, ServerPlayer player) {
        if (!offerings.matches(level, site)) return net.minecraft.world.InteractionResult.PASS;
        return offerings.start(player, site, new net.minecraft.nbt.CompoundTag())
                ? net.minecraft.world.InteractionResult.CONSUME : net.minecraft.world.InteractionResult.FAIL;
    }

    @Override public void tick(ServerLevel level, RitualSite site, StructuralRitualStore.Handle h) {
        var store = StructuralRitualStore.get(level);
        if (!offerings.tick(level, site, h.id(), true)) return;
        var active = ACTIVE.computeIfAbsent(level, unused -> new HashMap<>());
        var effect = new Active(store.initiator(h.id()).orElseThrow(), site);
        RitualPlane.centerPositions(site.plane().anchor()).forEach(pos ->
                active.computeIfAbsent(pos, unused -> new ArrayList<>()).add(effect));
    }
    private static Set<UUID> owners(ServerLevel level, BlockEntity entity) {
        var centers = ACTIVE.get(level);
        var effects = centers == null ? null : centers.get(RitualCenters.axis(entity));
        if (effects == null) return Set.of();
        var owners = new HashSet<UUID>();
        for (var effect : effects) if (RitualCenters.current(level, effect.site)) owners.add(effect.owner);
        return owners;
    }
    private static void afterNative(ServerLevel level, TickingBlockEntity ticker, BlockEntity entity, Runnable tick) {
        var owners = owners(level, entity);
        if (owners.isEmpty()) return;
        AccelerationSources.run(owners, () -> ExtraTicks.run(() -> {
            int work = RitualWorkBudget.claim(level, RitualWorkBudget.Kind.MACHINE, entity.getBlockPos(), BigInteger.TWO);
            for (int i = 0; i < work && WorkTicks.isScheduledTarget(level, ticker, entity); i++) {
                if (owners(level, entity).isEmpty()) break;
                tick.run();
            }
        }));
    }
}
