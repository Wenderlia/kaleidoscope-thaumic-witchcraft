package dev.senluo.witchcraft.ritual;

import dev.senluo.witchcraft.time.AccelerationSources;
import dev.senluo.witchcraft.time.ExtraTicks;
import dev.senluo.witchcraft.time.TemporalContent;
import dev.senluo.witchcraft.time.WorkTicks;
import java.math.BigInteger;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.TickingBlockEntity;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.LevelEvent;

public final class TemporalInfusion implements RitualCenters.Behavior {
    @Override public int activationPriority() { return 20; }
    private static final StructuralRitualStore.Definition RITE = new StructuralRitualStore.Definition(
            ResourceLocation.fromNamespaceAndPath("kaleidoscope_witchcraft", "temporal_infusion"),
            1, 60, new int[]{20,40,60}, RitualRings.MEDIUM, RitualResearch.thaumcraft("WITCHERY_CIRCLE_BASICS"));
    private record Active(UUID owner, RitualSite site, int extraWork) {}
    private record Quote(int extraWork, Set<UUID> owners) {}
    private static final Quote NONE = new Quote(0, Set.of());
    private static final Map<ServerLevel, Fields> ACTIVE = new IdentityHashMap<>();
    private static final class Influence {
        final Map<UUID, Active> sessions = new HashMap<>();
        List<Active> effects = List.of();
        void changed() {
            effects = List.copyOf(sessions.values());
        }
    }
    private static final class Fields {
        final Map<UUID, Active> sessions = new HashMap<>();
        final Map<BlockPos, Influence> centers = new HashMap<>();
        void update(ServerLevel level, UUID id, UUID owner, RitualSite site, int extraWork) {
            Active before = sessions.get(id);
            if (before != null && before.owner.equals(owner) && before.site.equals(site) && before.extraWork == extraWork) return;
            Set<BlockPos> changed = new HashSet<>();
            unlink(id, changed);
            var active = new Active(owner, site, extraWork);
            sessions.put(id, active);
            RitualPlane.centerPositions(site.footprint().anchor()).forEach(axis -> {
                var influence = centers.computeIfAbsent(axis, unused -> new Influence());
                influence.sessions.put(id, active); influence.changed(); changed.add(axis);
            });
            refresh(level, changed);
        }
        void remove(ServerLevel level, UUID id) {
            if (!sessions.containsKey(id)) return;
            Set<BlockPos> changed = new HashSet<>();
            unlink(id, changed); refresh(level, changed);
        }
        void unlink(UUID id, Set<BlockPos> changed) {
            Active active = sessions.remove(id);
            if (active == null) return;
            RitualPlane.centerPositions(active.site.footprint().anchor()).forEach(axis -> {
                var influence = centers.get(axis);
                influence.sessions.remove(id);
                if (influence.sessions.isEmpty()) centers.remove(axis); else influence.changed();
                changed.add(axis);
            });
        }
        void refresh(ServerLevel level, Set<BlockPos> axes) {
            axes.stream().flatMap(RitualCenters::positionsAtAxis).distinct().forEach(pos -> WorkTicks.invalidateAt(level, pos));
        }
    }
    private static CenterOfferings offerings;
    private TemporalInfusion() {}
    public static void register() {
        offerings = new CenterOfferings(RITE, List.of(
                stack -> stack.is(TemporalContent.FRUIT_ITEM.get()) && dev.senluo.witchcraft.time.FruitItem.grade(stack) == 5,
                stack -> stack.is(TemporalContent.LOG_ITEM.get()), stack -> stack.is(Items.GLASS_BOTTLE)),
                List.of(scale(RitualRings.MEDIUM, 1, 2), scale(RitualRings.LARGE, 2, 5)));
        RitualCenters.register(new TemporalInfusion());
        WorkTicks.registerEffect((level, entity) -> influence(level, entity) != null, TemporalInfusion::afterNative);
        AccelerationSources.registerObserver((level, entity) -> {
            for (var owner : owners(level, entity)) AccelerationSources.renew(level, entity, owner, 1);
        });
        NeoForge.EVENT_BUS.addListener((LevelEvent.Unload event) -> ACTIVE.remove(event.getLevel()));
    }
    @Override public StructuralRitualStore.Definition definition() { return RITE; }
    private static CenterOfferings.Scale scale(int points, int copies, int extraWork) {
        var parameters = new CompoundTag(); parameters.putInt("ExtraWork", extraWork);
        return new CenterOfferings.Scale(points, copies, parameters);
    }
    @Override public net.minecraft.world.InteractionResult activate(ServerLevel level, RitualSite site, ServerPlayer player) {
        return activateCandidates(level, List.of(site), player);
    }
    @Override public net.minecraft.world.InteractionResult activateCandidates(ServerLevel level, List<RitualSite> sites, ServerPlayer player) {
        return offerings.activate(player, sites, new CompoundTag());
    }

    @Override public void tick(ServerLevel level, RitualSite site, StructuralRitualStore.Handle h) {
        var store = StructuralRitualStore.get(level);
        if (!offerings.tick(level, site, h.id(), true)) {
            if (!store.pending(h.id())) unavailable(level, h);
            return;
        }
        var scale = offerings.scale(h.definition(), store.progress(h.id()).orElseThrow().terms());
        if (scale != null) ACTIVE.computeIfAbsent(level, unused -> new Fields()).update(level, h.id(),
                store.initiator(h.id()).orElseThrow(), site, scale.parameters().getInt("ExtraWork"));
    }
    @Override public void unavailable(ServerLevel level, StructuralRitualStore.Handle h) {
        var fields = ACTIVE.get(level);
        if (fields != null) fields.remove(level, h.id());
    }
    private static Influence influence(ServerLevel level, BlockEntity entity) {
        var fields = ACTIVE.get(level);
        return fields == null || fields.centers.isEmpty() ? null : fields.centers.get(RitualCenters.axis(entity));
    }
    private static Quote quote(ServerLevel level, BlockEntity entity) {
        for (int attempt = 0; attempt < 2; attempt++) {
            var influence = influence(level, entity);
            if (influence == null) return NONE;
            var effects = influence.effects;
            int extraWork = 0;
            var owners = new HashSet<UUID>();
            for (var effect : effects) if (StructuralRitualStore.get(level).running(level, effect.site.session())) {
                if (effect.extraWork > extraWork) { extraWork = effect.extraWork; owners.clear(); }
                if (effect.extraWork == extraWork) owners.add(effect.owner);
            }
            // A later permission query may revoke a source already counted above.
            if (influence(level, entity) == influence && influence.effects == effects) return new Quote(extraWork, Set.copyOf(owners));
        }
        return NONE;
    }
    private static Set<UUID> owners(ServerLevel level, BlockEntity entity) { return quote(level, entity).owners; }
    private static void afterNative(ServerLevel level, TickingBlockEntity ticker, BlockEntity entity, Runnable tick) {
        var initial = quote(level, entity);
        if (initial.extraWork == 0 || initial.owners.isEmpty()) return;
        AccelerationSources.run(initial.owners, () -> ExtraTicks.run(() -> {
            int work = RitualWorkBudget.claim(level, RitualWorkBudget.Kind.MACHINE, entity.getBlockPos(), BigInteger.valueOf(initial.extraWork));
            WorkTicks.advance(level, ticker, entity, work, () -> {
                var current = quote(level, entity);
                return current.extraWork >= initial.extraWork && current.owners.containsAll(initial.owners);
            }, tick);
        }));
    }
}
