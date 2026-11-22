package dev.senluo.witchcraft.ritual;

import java.util.*;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import java.util.function.Predicate;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.phys.Vec3;

/** Largest payable scale first. Its immutable receipt owns point demand, unit payments and effect parameters. */
public final class RitualRecipe {
    private static final String SCALE = "OfferingScale";
    private static final StructuralRitualStore.StepAction NO_PAYMENT = (step, terms) -> Optional.empty();
    public enum SourceOrder { FIRST, NEAREST }
    public enum Commit { REJECTED, ALREADY_APPLIED, APPLIED }
    public record Burst(int count, Vec3 offset, Vec3 spread, double speed) {}
    public record Completion(SoundEvent sound, float volume, float pitch, Burst burst) {
        void emit(ServerLevel level, RitualSite site) {
            level.playSound(null, site.position(), sound, SoundSource.BLOCKS, volume, pitch);
            if (burst != null) {
                var pos = Vec3.atLowerCornerOf(site.position()).add(burst.offset);
                level.sendParticles(ParticleTypes.ENCHANT, pos.x, pos.y, pos.z, burst.count,
                        burst.spread.x, burst.spread.y, burst.spread.z, burst.speed);
            }
        }
    }
    /** Ordinary item payments are data; only external capability commits remain callbacks. */
    public record Payment(SourceOrder order, boolean firstAtMilestone, Map<Integer, Item> remainders,
                          double trailHeight, double trailSpread, Completion completion) {
        public static final Payment DEFAULT = new Payment(SourceOrder.FIRST, false, Map.of(), 0, .05, null);
        public Payment { Objects.requireNonNull(order); remainders = Map.copyOf(remainders); }
    }
    public record Scale(int pointCost, int copies, CompoundTag parameters) {
        public Scale {
            if (pointCost < RitualRings.SMALL || pointCost > RitualRings.LARGE || copies < 1 || copies > 64)
                throw new IllegalArgumentException("Invalid offering scale");
            parameters = parameters.copy();
        }
        @Override public CompoundTag parameters() { return parameters.copy(); }
        private CompoundTag snapshot() {
            var tag = new CompoundTag();
            tag.putInt("Points", pointCost); tag.putInt("Copies", copies); tag.put("Parameters", parameters());
            return tag;
        }
    }
    private record Profile(Scale scale, StructuralRitualStore.Definition definition, CompoundTag receipt) {}
    /** Shared across all recipes in one click; never retained by a ritual or used as an inventory. */
    public static final class Input {
        final RitualCandidate site;
        private List<RitualMaterials.Source> sources;
        public Input(RitualCandidate site) { this.site = site; }
        private List<RitualMaterials.Source> sources(ServerLevel level) {
            if (sources == null) sources = RitualMaterials.find(level, RitualOfferingArea.outermost(site.footprint()), stack -> true);
            return sources;
        }
    }
    private record Bill(Input input, List<RitualMaterials.Source> sources, boolean[][] matches) {
        boolean canPay(int copies) {
            int units = matches.length * copies;
            int[] counts = new int[sources.size()], used = new int[sources.size()], assigned = new int[units];
            Arrays.fill(assigned, -1);
            var area = RitualOfferingArea.outermost(input.site.footprint());
            for (int i = 0; i < counts.length; i++)
                counts[i] = RitualMaterials.inArea(sources.get(i), area) ? Math.min(units, sources.get(i).count()) : 0;
            for (int unit = 0; unit < units; unit++)
                if (!claim(unit, copies, counts, used, assigned, new boolean[counts.length])) return false;
            return true;
        }
        // Capacitated matching: a broad ingredient cannot borrow a unit already promised to another ingredient.
        private boolean claim(int unit, int copies, int[] counts, int[] used, int[] assigned, boolean[] seen) {
            for (int source = 0; source < counts.length; source++) {
                if (seen[source] || counts[source] == 0 || !matches[unit / copies][source]) continue;
                seen[source] = true;
                boolean room = used[source] < counts[source];
                if (!room) for (int previous = 0; previous < assigned.length; previous++) {
                    if (assigned[previous] == source && claim(previous, copies, counts, used, assigned, seen)) {
                        room = true; break;
                    }
                }
                if (room) {
                    if (assigned[unit] >= 0) used[assigned[unit]]--;
                    assigned[unit] = source; used[source]++; return true;
                }
            }
            return false;
        }
    }
    private final StructuralRitualStore.Definition definition;
    private final List<Predicate<ItemStack>> materials;
    private final List<Profile> profiles;
    private final boolean singlePreparation;
    private final Payment payment;
    public RitualRecipe(StructuralRitualStore.Definition definition, List<Predicate<ItemStack>> materials) {
        this(definition, materials, false);
    }
    public RitualRecipe(StructuralRitualStore.Definition definition, List<Predicate<ItemStack>> materials, boolean singlePreparation) {
        this(definition, materials, singlePreparation, Payment.DEFAULT);
    }
    public RitualRecipe(StructuralRitualStore.Definition definition, List<Predicate<ItemStack>> materials,
                        boolean singlePreparation, Payment payment) {
        this(definition, materials, List.of(new Scale(definition.pointCost(), 1, new CompoundTag())), singlePreparation, payment);
    }
    public RitualRecipe(StructuralRitualStore.Definition definition, List<Predicate<ItemStack>> materials, List<Scale> scales) {
        this(definition, materials, scales, false, Payment.DEFAULT);
    }
    private RitualRecipe(StructuralRitualStore.Definition definition, List<Predicate<ItemStack>> materials, List<Scale> scales,
                         boolean singlePreparation, Payment payment) {
        this.definition = definition; this.materials = List.copyOf(materials); this.singlePreparation = singlePreparation;
        this.payment = Objects.requireNonNull(payment);
        if (materials.isEmpty() || definition.steps().length < materials.size() || scales.isEmpty()
                || scales.stream().anyMatch(scale -> scale.copies() != 1 && definition.steps().length != materials.size())
                || scales.stream().map(Scale::pointCost).distinct().count() != scales.size())
            throw new IllegalArgumentException("Offering scales need one bill and distinct point costs");
        profiles = scales.stream().sorted(Comparator.comparingInt(Scale::pointCost).reversed()).map(scale -> {
            int[] base = definition.steps();
            if (base.length * scale.copies() > 64) throw new IllegalArgumentException("Too many offering milestones");
            int[] steps = new int[base.length * scale.copies()];
            for (int material = 0; material < base.length; material++) {
                int previous = material == 0 ? 0 : base[material - 1];
                for (int copy = 0; copy < scale.copies(); copy++)
                    steps[material * scale.copies() + copy] = Math.addExact(Math.multiplyExact(previous, scale.copies()),
                            Math.multiplyExact(base[material] - previous, copy + 1));
            }
            return new Profile(scale, new StructuralRitualStore.Definition(definition.kind(), definition.version(),
                    Math.multiplyExact(definition.duration(), scale.copies()), steps, scale.pointCost(), definition.research()), scale.snapshot());
        }).toList();
    }
    public StructuralRitualStore.Definition definition() { return definition; }
    private Bill quote(ServerLevel level, Input input) {
        var sources = input.sources(level);
        boolean[][] matches = new boolean[materials.size()][sources.size()];
        for (int source = 0; source < sources.size(); source++) {
            ItemStack item = sources.get(source).item();
            for (int ingredient = 0; ingredient < materials.size(); ingredient++)
                matches[ingredient][source] = materials.get(ingredient).test(item);
        }
        return new Bill(input, sources, matches);
    }
    /** Quote materials once per plane, then try every plane at the larger scale before descending. */
    public InteractionResult activate(ServerPlayer player, List<Input> inputs,
            java.util.function.BiFunction<RitualCandidate, ServerPlayer, Optional<CompoundTag>> prepare) {
        ServerLevel level = player.serverLevel();
        var candidates = inputs.stream().map(input -> quote(level, input)).toList();
        if (candidates.stream().noneMatch(candidate -> profiles.stream().anyMatch(profile -> candidate.canPay(profile.scale.copies()))))
            return InteractionResult.PASS;
        if (player.isFakePlayer() || player.isSpectator() || !player.mayBuild()
                || RitualResearch.check(level, player.getUUID(), definition.research()) != RitualResearch.Result.ALLOWED)
            return InteractionResult.FAIL;
        for (var profile : profiles) for (var candidate : candidates) {
            var site = candidate.input.site;
            if (!candidate.canPay(profile.scale.copies()) || !level.mayInteract(player, site.position())
                    || !site.current(level)) continue;
            var store = StructuralRitualStore.get(level);
            // A preparation owns only its matched recipe, never the entire click dispatcher.
            if (singlePreparation && store.atPlane(level.dimension().location(), site.footprint().anchor()).stream()
                    .anyMatch(h -> h.definition().kind().equals(definition.kind())
                            && store.phase(h.id()) == StructuralRitualStore.Phase.PREPARING)) return InteractionResult.CONSUME;
            var supplied = prepare.apply(site, player);
            if (supplied.isEmpty() || !candidate.canPay(profile.scale.copies())) continue;
            CompoundTag terms = supplied.get().copy(); terms.put(SCALE, profile.receipt.copy());
            // The existing atomic admission quotes legal pooled capacity without displacing incumbents.
            if (RitualCenters.begin(player, site, profile.definition, terms).isPresent()) return InteractionResult.CONSUME;
        }
        return InteractionResult.FAIL;
    }
    private Profile profile(StructuralRitualStore.Definition actual, CompoundTag terms) {
        for (var profile : profiles) if (profile.scale.pointCost() == actual.pointCost()
                && (!terms.contains(SCALE) ? profile.scale.copies() == 1
                : terms.contains(SCALE, Tag.TAG_COMPOUND) && terms.getCompound(SCALE).equals(profile.receipt))) return profile;
        return null;
    }
    /** Old receipts have no scale tag and retain their original one-copy specification. */
    public Scale scale(StructuralRitualStore.Definition actual, CompoundTag terms) {
        var profile = profile(actual, terms);
        return profile == null ? null : profile.scale;
    }
    private Profile profile(StructuralRitualStore.Definition actual, StructuralRitualStore.Progress progress) {
        for (var profile : profiles) if (profile.scale.pointCost() == actual.pointCost()
                && (!progress.hasTerm(SCALE) ? profile.scale.copies() == 1 : progress.termEquals(SCALE, profile.receipt))) return profile;
        return null;
    }
    public Scale scale(StructuralRitualStore.Definition actual, StructuralRitualStore.Progress progress) {
        var profile = profile(actual, progress);
        return profile == null ? null : profile.scale;
    }

    public boolean tick(ServerLevel level, RitualSite site, UUID id, boolean resourcesAvailable) {
        var store = StructuralRitualStore.get(level);
        var progress = store.progress(id).orElseThrow();
        var profile = profile(store.handle(id).orElseThrow().definition(), progress);
        if (profile == null) { store.revoke(level, id); return false; }
        return store.advance(level, id, profile.definition, StructuralRitualStore.Presence.PRESENT,
                RitualShape.Match.VALID, resourcesAvailable, payments(level, site, profile.definition, progress,
                        () -> true, () -> Commit.APPLIED)).mayRun();
    }

    /** Capture the pre-advance progress, then let the ledger alone validate and commit each step. */
    public StructuralRitualStore.StepAction payments(ServerLevel level, RitualSite site,
            StructuralRitualStore.Definition actual, StructuralRitualStore.Progress before,
            BooleanSupplier ready, Supplier<Commit> complete) {
        if (before.phase() != StructuralRitualStore.Phase.PREPARING) return NO_PAYMENT;
        var profile = profile(actual, before);
        return (step, terms) -> {
            if (profile == null || site.footprint() == null) return Optional.empty();
            boolean first = payment.firstAtMilestone && before.elapsed() < actual.steps()[step.index()];
            if (!first && level.getGameTime() % 20 != 0 || !ready.getAsBoolean()) return Optional.empty();
            int ingredient = step.index() / profile.scale.copies();
            if (ingredient >= materials.size()) return Optional.of(new StructuralRitualStore.PreparedStep(terms, () -> {
                if (!RitualCenters.current(level, site) || !ready.getAsBoolean()) return false;
                Commit result = complete.get();
                if (result == Commit.APPLIED && payment.completion != null) payment.completion.emit(level, site);
                return result != Commit.REJECTED;
            }));
            var area = RitualOfferingArea.outermost(site.footprint());
            var wanted = materials.get(ingredient);
            var sources = RitualMaterials.find(level, area, wanted);
            var source = payment.order == SourceOrder.NEAREST
                    ? sources.stream().min(Comparator.comparingDouble(s -> s.position().distanceToSqr(area.bounds().getCenter()))).orElse(null)
                    : sources.stream().findFirst().orElse(null);
            if (source == null) return Optional.empty();
            return Optional.of(new StructuralRitualStore.PreparedStep(terms, () -> {
                if (!RitualCenters.current(level, site) || !ready.getAsBoolean()
                        || !RitualMaterials.inArea(source, area) || !wanted.test(source.item())) return false;
                var paid = source.takeOne();
                if (paid.isEmpty()) return false;
                Vec3 origin = source.position(), end = Vec3.atCenterOf(site.position());
                OfferingSounds.absorb(level, origin, paid);
                Item remainder = payment.remainders.get(ingredient);
                if (remainder != null) level.addFreshEntity(new ItemEntity(level, origin.x, origin.y + .1, origin.z, new ItemStack(remainder)));
                for (int i = 0; i < 8; i++) {
                    var p = origin.lerp(end, i / 7.0);
                    level.sendParticles(ParticleTypes.ENCHANT, p.x, p.y + payment.trailHeight, p.z, 1,
                            payment.trailSpread, payment.trailSpread, payment.trailSpread, .01);
                }
                return true;
            }));
        };
    }
}
