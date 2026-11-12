package dev.senluo.witchcraft.compat.thaumcraft;

import net.minecraft.world.level.block.entity.BlockEntity;
import dev.senluo.witchcraft.ritual.*;
import dev.tc4port.thaumcraft.api.aspect.AspectApi;
import dev.tc4port.thaumcraft.api.aspect.AspectId;
import dev.tc4port.thaumcraft.api.aspect.AspectQueryApi;


import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ItemParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;

/** One loaded vessel observation drives all of its independent receipts; no recursive machine ticking. */
final class CrucibleRitualHost {
    private static final AspectId HERBA = CrucibleRitualRecipes.HERBA;
    private CrucibleRitualHost() {}
    static void register() {
        for (var definition : List.of(StructuralRitualStore.PREPARATION, StructuralRitualStore.STEAM, StructuralRitualStore.SUCTION)) {
            RitualCenters.registerHost(definition.kind(), entity -> EssentiaStores.pot(entity) != null);
            RitualCenters.observeAvailability(definition.kind(), (level, handle) -> SteamWork.fields(level).remove(handle.id()));
        }
    }
    private static BlockEntity host(ServerLevel level, UUID id) {
        return EssentiaStores.pot(RitualCenters.target(level, id));
    }

    static List<StructuralRitualStore.Handle> sessions(BlockEntity pot) {
        if (!(pot.getLevel() instanceof ServerLevel level)) return List.of();
        var store = StructuralRitualStore.get(level);
        return RitualCenters.observe(pot).map(c -> store.allAt(c.reference())).orElse(List.of()).stream()
                .filter(h -> h.definition().kind().equals(StructuralRitualStore.PREPARATION.kind())
                        || CrucibleRitualRecipes.from(h.definition()).isPresent())
                .filter(h -> store.progress(h.id()).orElseThrow().phase() != StructuralRitualStore.Phase.ENDED).toList();
    }
    static int liveCount(BlockEntity pot) { return sessions(pot).size(); }
    private static boolean untyped(StructuralRitualStore.Progress p) {
        return p.phase() == StructuralRitualStore.Phase.PREPARING && !p.terms().getBoolean("Armed")
                && p.terms().getInt("Format") == 3
                && p.elapsed() == 0 && p.committedSteps() == 0;
    }

    static Optional<Boolean> accept(BlockEntity pot, ItemEntity item) {
        if (!(pot.getLevel() instanceof ServerLevel level) || !item.isAlive() || item.getItem().isEmpty()) return Optional.empty();
        var view = EssentiaStores.crucible(pot).orElse(null);
        if (view == null) return Optional.empty();
        if (!RitualMaterials.available(item)) return Optional.of(false);
        var store = StructuralRitualStore.get(level);
        var sessions = sessions(pot);
        // A physical trigger stack belongs to one preparation, across every center and dimension.
        if (store.reservationOwner(item.getUUID()).isPresent()) return Optional.of(false);
        ItemStack input = item.getItem();
        var draft = sessions.stream().filter(h -> untyped(store.progress(h.id()).orElseThrow())).findFirst();
        if (input.is(RitualContent.TAGLOCK.get())) {
            RitualPlane.invalidate(level, pot.getBlockPos());
            var sample = input.get(RitualContent.TAGLOCK_SAMPLE.get());
            var site = RitualCenters.site(pot).orElse(null);
            if (draft.isPresent() || sample == null || !sample.supported() || !sample.isPlayer() || site == null
                    || !RitualCenters.current(level, site) || !view.boiling()
                    || RitualResearch.check(level, sample.subject(), StructuralRitualStore.PREPARATION.research()) != RitualResearch.Result.ALLOWED) {
                bounce(item); return Optional.of(false);
            }
            long capacity = view.stableCapacity();
            if (AspectApi.registry().get(HERBA).isEmpty() || RitualCapacity.quote(EssentiaStores.total(view.essentiaContents(null)), capacity, 1, 1).accepted() != 1) {
                bounce(item); return Optional.of(false);
            }
            CompoundTag terms = new CompoundTag(); terms.putInt("Format", 3); terms.putBoolean("Armed", false);
            terms.putLong("Plane", site.footprint().anchor().asLong());
            if (store.begin(level, StructuralRitualStore.PREPARATION, site.center(), sample.subject(), terms).isEmpty()) {
                bounce(item); return Optional.of(false);
            }
            SteamRituals.refreshHerba(pot);
            consume(item, 1); addPaidAspects(level, pot, Map.of(HERBA, 1));
            RitualHerba.set(pot, Math.addExact(RitualHerba.count(pot), 1));
            RitualCenterTicks.track(level, pot.getBlockPos());
            sound(level, pot.getBlockPos(), SoundEvents.BREWING_STAND_BREW, 0.4F, 1.2F);
            return Optional.of(true);
        }
        var chosen = CrucibleRitualRecipes.triggeredBy(input);
        if (sessions.isEmpty()) {
            if (chosen.isPresent() && RitualPlane.find(level, pot.getBlockPos()).isPresent()) { bounce(item); return Optional.of(false); }
            return Optional.empty();
        }
        if (chosen.isPresent()) {
            var recipe = chosen.get();
            // Restore a missing physical trigger on an already paid preparation before opening a new one.
            for (var h : sessions) {
                var p = store.progress(h.id()).orElseThrow();
                if (p.phase() == StructuralRitualStore.Phase.PREPARING && h.definition().kind().equals(recipe.definition.kind())
                        && recipe.valid(p.terms()) && trigger(level, pot.getBlockPos(), h.id(), recipe, p.terms()).isEmpty()) {
                    CompoundTag terms = p.terms(); terms.putUUID("Trigger", item.getUUID());
                    store.prepareTerms(level, h.id(), terms, item.getUUID()); return Optional.of(false);
                }
            }
            RitualPlane.invalidate(level, pot.getBlockPos());
            var site = draft.map(h -> store.site(level, h.id())).orElse(null);
            if (site == null || !RitualCenters.current(level, site) || !view.boiling()

                    || recipe.offerings.stream().anyMatch(o -> offering(level, RitualOfferingArea.outermost(site.footprint()), o.item()).isEmpty())) {
                bounce(item); return Optional.of(false);
            }
            var terms = recipe.quote(pot, item.getUUID());
            terms.ifPresent(value -> value.putLong("Plane", site.footprint().anchor().asLong()));
            if (terms.isEmpty() || !recipe.canPay(pot, terms.get())
                    || !store.select(level, draft.get().id(), recipe.definition, terms.get(), item.getUUID())) bounce(item);
            return Optional.of(false);
        }
        // Retained offerings must remain items, also when another recipe uses the same vessel.
        for (var h : sessions) {
            var p = store.progress(h.id()).orElseThrow(); var recipe = CrucibleRitualRecipes.from(h.definition());
            if (recipe.isEmpty() || !p.terms().getBoolean("Armed") || p.phase() == StructuralRitualStore.Phase.ENDED) continue;
            var offerings = recipe.get().offerings;
            for (int step = 0; step < offerings.size(); step++) {
                var o = offerings.get(step);
                if ((!o.consumed() || p.phase() == StructuralRitualStore.Phase.PREPARING && step >= p.committedSteps())
                        && CrucibleRitualRecipes.is(input, o.item())) return Optional.of(false);
            }
        }
        if (sessions.stream().noneMatch(h -> store.eligible(level, h.id()))) { bounce(item); return Optional.of(false); }
        Map<AspectId, Integer> aspects = AspectQueryApi.item(input).amounts();
        long perUnit = aspects.values().stream().filter(n -> n > 0).mapToLong(Integer::longValue).sum();
        if (perUnit <= 0) { bounce(item); return Optional.of(false); }
        var admission = RitualCapacity.quote(EssentiaStores.total(view.essentiaContents(null)), view.stableCapacity(), perUnit, input.getCount());
        if (admission.accepted() > 0) {
            Map<AspectId, Integer> accepted = new LinkedHashMap<>();
            aspects.forEach((key, amount) -> { if (amount > 0) accepted.put(key, Math.toIntExact((long)amount * admission.accepted())); });
            SteamRituals.refreshHerba(pot);
            consume(item, admission.accepted()); addPaidAspects(level, pot, accepted);
            sound(level, pot.getBlockPos(), SoundEvents.BREWING_STAND_BREW, 0.15F, 1.1F);
        }
        if (admission.rejected() > 0) bounce(item);
        return Optional.of(admission.accepted() > 0);
    }

    static void tick(LevelTickEvent.Pre event) {
        if (!(event.getLevel() instanceof ServerLevel level) || !level.tickRateManager().runsNormally()) return;
        RitualWaterSupply.tick(level);
        var store = StructuralRitualStore.get(level);
        Set<UUID> steamed = new HashSet<>(); Set<BlockPos> vented = new HashSet<>();
        Set<BlockPos> watered = new HashSet<>();
        var channels = new ArrayList<SuctionChannels.Pulse>();
        var sessions = store.inDimension(level.dimension().location());
        // Settle existing evaporation before ANY co-hosted ritual observes water availability.
        if (level.getGameTime() % 20 == 0) for (var h : sessions) {
            var recipe = CrucibleRitualRecipes.from(h.definition());
            if (recipe.isEmpty() || recipe.get() == CrucibleRitualRecipes.SUCTION) continue;
            var actualHost = host(level, h.id());
            if (actualHost == null) { store.revoke(level, h.id()); continue; }
            BlockPos pos = actualHost.getBlockPos();
            if (watered.contains(pos)
                    || !level.hasChunkAt(pos) || !level.shouldTickBlocksAt(pos)) continue;
            var pot = EssentiaStores.pot(level.getBlockEntity(pos));
            if (pot == null) continue;
            var progress = store.progress(h.id()).orElseThrow();
            var site = store.site(level, h.id());
            if (progress.phase() == StructuralRitualStore.Phase.ACTIVE && recipe.get().valid(progress.terms())
                    && site != null && store.eligible(level, h.id())) {
                watered.add(pos); evaporate(level, pot);
            }
        }
        for (var h : sessions) {
            var recipe = CrucibleRitualRecipes.from(h.definition());
            if (recipe.isEmpty() && !h.definition().kind().equals(StructuralRitualStore.PREPARATION.kind())) continue;
            var actualHost = host(level, h.id());
            if (actualHost == null) { store.revoke(level, h.id()); continue; }
            BlockPos pos = actualHost.getBlockPos();
            if (!level.hasChunkAt(pos) || !level.shouldTickBlocksAt(pos)) { SteamWork.fields(level).remove(h.id()); continue; }
            var entity = level.getBlockEntity(pos);
            var pot = EssentiaStores.pot(entity);
            var observed = RitualCenters.observe(entity).filter(RitualCenters.Center::mayPrepare);
            var p = store.progress(h.id()).orElseThrow();
            var site = store.site(level, h.id());
            var presence = observed.isPresent() ? StructuralRitualStore.Presence.PRESENT : StructuralRitualStore.Presence.UNLOADED;
            var shape = recipe.isPresent() && recipe.get().valid(p.terms()) ? RitualShape.Match.VALID : RitualShape.Match.INVALID;
            // The shared store preserves paid active receipts and rechecks unfinished preparations.
            var result = store.advance(level, h.id(),
                    recipe.map(r -> r.definition).orElse(StructuralRitualStore.PREPARATION), presence,
                    shape,
                    pot != null && EssentiaStores.hasWater(pot),
                    (token, terms) -> recipe.flatMap(r -> quote(level, pos, h.id(), r, token.index(), terms)));
            if (result.phase() == StructuralRitualStore.Phase.ENDED) {
                RitualCenters.end(level, h.id());
                if (pot != null && observed.isPresent())
                    RitualHerba.set(pot, Math.min(RitualHerba.count(pot), liveCount(pot)));
                continue;
            }
            if (recipe.isPresent() && result.newlyCommittedStep() >= 0)
                stageSound(level, pos, recipe.get(), p.terms(), result.newlyCommittedStep());
            if (!result.mayRun() || recipe.isEmpty()) {
                if (!store.pending(h.id())) SteamWork.fields(level).remove(h.id());
                continue;
            }
            CompoundTag terms = store.progress(h.id()).orElseThrow().terms();
            if (recipe.get() == CrucibleRitualRecipes.SUCTION) {
                SteamWork.fields(level).remove(h.id());
                var charge = recipe.get().suction(terms);
                CompoundTag runtime = store.runtime(h.id());
                var rate = SuctionChannels.rate(charge, (int) HostParameters.value("machines/infusion/work_interval"),
                        (int) HostParameters.value("machines/infusion/essentia_per_pulse"));
                var flow = rate.advance(rate.rebaseCredit(runtime.getLong("FlowCredit"), runtime.getLong("FlowPeriod")));
                runtime.putLong("FlowCredit", flow.credit()); runtime.putLong("FlowPeriod", rate.period());
                store.runtime(level, h.id(), runtime);
                if (flow.amount() > 0) channels.add(new SuctionChannels.Pulse(h.id(), pos, charge, flow.amount()));
            } else {
                SteamCharge paid = recipe.get().steam(terms);
                SteamWork.fields(level).put(new RitualFields.Field(h.id(), pos, paid.radius(), paid.doublings(),
                        store.initiator(h.id()).orElse(null)));
                if (vented.add(pos)) vent(level, pot, steamed);
            }
        }
        SuctionChannels.tick(level, channels);
    }
    private static Optional<StructuralRitualStore.PreparedStep> quote(ServerLevel level, BlockPos pos, UUID session,
            CrucibleRitualRecipes recipe, int step, CompoundTag terms) {
        var pot = EssentiaStores.pot(level.getBlockEntity(pos));
        var site = StructuralRitualStore.get(level).site(level, session);
        if (pot == null || !EssentiaStores.hasWater(pot) || !recipe.valid(terms)
                || site == null || !RitualCenters.current(level, site)
                || trigger(level, pos, session, recipe, terms).isEmpty()) return Optional.empty();
        var area = RitualOfferingArea.outermost(site.footprint());
        var offerings = recipe.offerings;
        boolean last = step == offerings.size();
        var triggerItem = trigger(level, pos, session, recipe, terms).orElse(null);
        var source = last ? null : offering(level, area, offerings.get(step).item()).orElse(null);
        if (triggerItem == null || !last && source == null || last && !recipe.canPay(pot, terms)) return Optional.empty();
        return Optional.of(new StructuralRitualStore.PreparedStep(terms, () -> {
            if (level.getBlockEntity(pos) != pot || !EssentiaStores.hasWater(pot) || !RitualCenters.current(level, site)
                    || trigger(level, pos, session, recipe, terms).filter(current -> current == triggerItem).isEmpty()) return false;
            if (!last && !availableOffering(area, source, offerings.get(step).item())) return false;
            if (last && !recipe.pay(pot, terms)) return false;
            Vec3 origin = last ? triggerItem.position() : source.position();
            ItemStack visual = last ? triggerItem.getItem().copyWithCount(1) : source.item();
            if (last) consume(triggerItem, 1);
            else if (offerings.get(step).consumed() && source.takeOne().isEmpty()) return false;
            level.sendParticles(new ItemParticleOption(ParticleTypes.ITEM, visual),
                    origin.x, origin.y + 0.1, origin.z, 8, 0.15, 0.2, 0.15, 0.03);
            if (!last) {
                if (offerings.get(step).consumed())
                    dev.senluo.witchcraft.ritual.OfferingSounds.absorb(level, Vec3.atCenterOf(pos), visual);
                else dev.senluo.witchcraft.ritual.OfferingSounds.play(level, Vec3.atCenterOf(pos), visual);
            }
            return true;
        }));
    }
    private static boolean availableOffering(RitualOfferingArea area, RitualMaterials.Source source,
            ResourceLocation material) {
        return source.available() && RitualMaterials.inArea(source, area) && CrucibleRitualRecipes.is(source.item(), material);
    }
    private static Optional<RitualMaterials.Source> offering(ServerLevel level, RitualOfferingArea area, ResourceLocation material) {
        Vec3 center = area.bounds().getCenter();
        return RitualMaterials.find(level, area, stack -> CrucibleRitualRecipes.is(stack, material))
                .stream().min(Comparator.comparingDouble(source -> source.position().distanceToSqr(center)));
    }
    private static Optional<ItemEntity> trigger(ServerLevel level, BlockPos pos, UUID session, CrucibleRitualRecipes recipe, CompoundTag terms) {
        if (!terms.hasUUID("Trigger") || !(level.getEntity(terms.getUUID("Trigger")) instanceof ItemEntity item)
                || !StructuralRitualStore.get(level).reservationOwner(item.getUUID()).filter(session::equals).isPresent()
                || !RitualMaterials.available(item) || !recipe.trigger(item.getItem()) || Math.abs(item.getY()-pos.getY()) > 1.5
                || item.position().subtract(Vec3.atCenterOf(pos)).horizontalDistanceSqr() > 9) return Optional.empty();
        return Optional.of(item);
    }
    private static void consume(ItemEntity item, int count) {
        ItemStack left = item.getItem().copy(); left.shrink(count);
        if (left.isEmpty()) item.discard(); else item.setItem(left);
    }
    private static void bounce(ItemEntity item) {
        if (!item.isAlive()) return;
        item.setDeltaMovement(0.22, 0.3, 0.22); item.hasImpulse = true; item.setPickUpDelay(10);
    }
    private static void stageSound(ServerLevel level, BlockPos pos, CrucibleRitualRecipes recipe, CompoundTag terms, int step) {
        var offerings = recipe.offerings;
        if (step == offerings.size()) {
            sound(level, pos, SoundEvents.BREWING_STAND_BREW, 0.65F, 0.7F);
            sound(level, pos, SoundEvents.BEACON_ACTIVATE, 0.25F, 1.4F); return;
        }
    }
    private static void sound(ServerLevel level, BlockPos pos, SoundEvent event, float volume, float pitch) {
        level.playSound(null, pos, event, SoundSource.BLOCKS, volume, pitch);
    }
    private static void addPaidAspects(ServerLevel level, BlockEntity pot, Map<AspectId, Integer> amounts) {
        if (!dev.tc4port.thaumcraft.api.machine.CrucibleApi.addAspects(level, pot.getBlockPos(),
                new dev.tc4port.thaumcraft.api.aspect.AspectAmounts(amounts)))
            throw new IllegalStateException("Paid crucible became unavailable");
    }
    private static void evaporate(ServerLevel level, BlockEntity pot) {
        long now = level.getGameTime();
        if (now % 20 == 0 && EssentiaStores.hasWater(pot)) {
            var data = pot.getData(RitualContent.RITUAL_ANCHOR);
            SteamRituals.profile(pot).ifPresent(profile -> {
                var water = profile.water(1, data.waterCredit());
                var tank = level.getCapability(net.neoforged.neoforge.capabilities.Capabilities.FluidHandler.BLOCK, pot.getBlockPos(), null);
                if (tank != null && data.commitWater(water)) {
                    tank.drain((int)water.millibuckets(), IFluidHandler.FluidAction.EXECUTE);
                    pot.setChanged();
                }
            });
        }
    }
    private static void vent(ServerLevel level, BlockEntity pot, Set<UUID> damaged) {
        long now = level.getGameTime();
        if (now % 10 != 0 || !EssentiaStores.hasWater(pot)) return;
        BlockPos pos = pot.getBlockPos();
        int height = 0;
        for (int y = 1; y <= 3; y++) {
            BlockPos above = pos.above(y);
            if (!level.hasChunkAt(above) || Block.isShapeFullBlock(level.getBlockState(above).getCollisionShape(level, above))) break;
            height = y;
        }
        if (height == 0) return;
        level.sendParticles(ParticleTypes.CLOUD, pos.getX()+0.5, pos.getY()+1.05, pos.getZ()+0.5, 2, 0.16, 0.1, 0.16, 0.025);
        if (now % 20 == 0) {
            AABB area = new AABB(pos.above()).expandTowards(0, height-1, 0);
            for (var entity : level.getEntitiesOfClass(net.minecraft.world.entity.LivingEntity.class, area))
                if (damaged.add(entity.getUUID())) entity.hurt(level.damageSources().inFire(), 1);
            if (now % 80 == 0) sound(level, pos, SoundEvents.FIRE_EXTINGUISH, 0.12F, 1.6F);
        }
    }
}
