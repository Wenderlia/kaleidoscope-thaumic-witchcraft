package dev.senluo.witchcraft.ritual;

import java.util.*;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import java.util.function.Predicate;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.phys.Vec3;

/** Largest payable scale first. Its immutable receipt owns point demand, unit payments and effect parameters. */
public final class CenterOfferings {
    private static final String SCALE = "OfferingScale";
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
    private record Profile(Scale scale, StructuralRitualStore.Definition definition) {}
    private record Candidate(RitualSite site, List<List<RitualMaterials.Source>> materials) {
        boolean canPay(int copies) {
            for (var sources : materials) {
                int remaining = copies;
                for (var source : sources) {
                    remaining -= Math.min(remaining, source.count());
                    if (remaining == 0) break;
                }
                if (remaining != 0) return false;
            }
            return true;
        }
    }
    private final StructuralRitualStore.Definition definition;
    private final List<Predicate<ItemStack>> materials;
    private final List<Profile> profiles;
    public CenterOfferings(StructuralRitualStore.Definition definition, List<Predicate<ItemStack>> materials) {
        this(definition, materials, List.of(new Scale(definition.pointCost(), 1, new CompoundTag())));
    }
    public CenterOfferings(StructuralRitualStore.Definition definition, List<Predicate<ItemStack>> materials, List<Scale> scales) {
        this.definition = definition; this.materials = List.copyOf(materials);
        if (materials.isEmpty() || definition.steps().length != materials.size() || scales.isEmpty()
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
                    Math.multiplyExact(definition.duration(), scale.copies()), steps, scale.pointCost(), definition.research()));
        }).toList();
    }
    public StructuralRitualStore.Definition definition() { return definition; }
    public List<Predicate<ItemStack>> materials() { return materials; }
    private Candidate quote(ServerLevel level, RitualSite site) {
        var area = RitualOfferingArea.outermost(site.footprint());
        return new Candidate(site, materials.stream().map(wanted -> RitualMaterials.find(level, area, wanted)).toList());
    }
    public boolean matches(ServerLevel level, RitualSite site) {
        if (site.footprint() == null) return false;
        var candidate = quote(level, site);
        return profiles.stream().anyMatch(profile -> candidate.canPay(profile.scale.copies()));
    }
    public boolean start(ServerPlayer player, RitualSite site, CompoundTag suppliedTerms) {
        return activate(player, List.of(site), suppliedTerms) == InteractionResult.CONSUME;
    }
    /** Quote materials once per plane, then try every plane at the larger scale before descending. */
    public InteractionResult activate(ServerPlayer player, List<RitualSite> sites, CompoundTag suppliedTerms) {
        ServerLevel level = player.serverLevel();
        var candidates = sites.stream().filter(site -> site.footprint() != null).map(site -> quote(level, site)).toList();
        if (candidates.stream().noneMatch(candidate -> profiles.stream().anyMatch(profile -> candidate.canPay(profile.scale.copies()))))
            return InteractionResult.PASS;
        if (player.isFakePlayer() || player.isSpectator() || !player.mayBuild()
                || RitualResearch.check(level, player.getUUID(), definition.research()) != RitualResearch.Result.ALLOWED)
            return InteractionResult.FAIL;
        var store = StructuralRitualStore.get(level);
        for (var profile : profiles) for (var candidate : candidates) {
            var site = candidate.site;
            if (!candidate.canPay(profile.scale.copies()) || !level.mayInteract(player, site.position())
                    || !RitualCenters.current(level, site)) continue;
            if (store.atPlane(level.dimension().location(), site.footprint().anchor()).stream()
                    .anyMatch(h -> h.definition().kind().equals(definition.kind()))) continue;
            CompoundTag terms = suppliedTerms.copy(); terms.put(SCALE, profile.scale.snapshot());
            // The existing atomic admission quotes legal pooled capacity without displacing incumbents.
            if (RitualCenters.begin(player, site, profile.definition, terms).isPresent()) return InteractionResult.CONSUME;
        }
        return InteractionResult.FAIL;
    }
    private Profile profile(StructuralRitualStore.Definition actual, CompoundTag terms) {
        for (var profile : profiles) if (profile.scale.pointCost() == actual.pointCost()
                && (!terms.contains(SCALE) ? profile.scale.copies() == 1
                : terms.contains(SCALE, Tag.TAG_COMPOUND) && terms.getCompound(SCALE).equals(profile.scale.snapshot()))) return profile;
        return null;
    }
    /** Old receipts have no scale tag and retain their original one-copy specification. */
    public Scale scale(StructuralRitualStore.Definition actual, CompoundTag terms) {
        var profile = profile(actual, terms);
        return profile == null ? null : profile.scale;
    }

    public boolean tick(ServerLevel level, RitualSite site, UUID id, boolean resourcesAvailable) {
        var footprint = site.footprint();
        var store = StructuralRitualStore.get(level);
        var progress = store.progress(id).orElseThrow();
        var profile = profile(store.handle(id).orElseThrow().definition(), progress.terms());
        if (profile == null) { store.revoke(level, id); return false; }
        return store.advance(level, id, profile.definition, StructuralRitualStore.Presence.PRESENT,
                RitualShape.Match.VALID, resourcesAvailable, (step, terms) -> {
                    if (level.getGameTime() % 20 != 0) return Optional.empty();
                    var area = RitualOfferingArea.outermost(footprint);
                    var wanted = materials.get(step.index() / profile.scale.copies());
                    var source = RitualMaterials.find(level, area, wanted)
                            .stream().findFirst().orElse(null);
                    if (source == null) return Optional.empty();
                    return Optional.of(new StructuralRitualStore.PreparedStep(terms, () -> {
                        if (!RitualCenters.current(level, site) || !RitualMaterials.inArea(source, area)
                                || !wanted.test(source.item())) return false;
                        var paid = source.takeOne();
                        if (paid.isEmpty()) return false;
                        OfferingSounds.absorb(level, source.position(), paid);
                        Vec3 end = Vec3.atCenterOf(site.position());
                        for (int i = 0; i < 8; i++) {
                            var point = source.position().lerp(end, i / 7.0);
                            level.sendParticles(ParticleTypes.ENCHANT, point.x, point.y, point.z, 1, .05, .05, .05, .01);
                        }
                        return true;
                    }));
                }).mayRun();
    }
}
