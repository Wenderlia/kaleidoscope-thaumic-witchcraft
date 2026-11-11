package dev.senluo.witchcraft.ritual;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import java.util.function.Predicate;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

/** One paid material per milestone, bound to the same center and marked plane. */
public record CenterOfferings(StructuralRitualStore.Definition definition, List<Predicate<ItemStack>> materials) {
    public boolean matches(ServerLevel level, RitualSite site) {
        if (site.plane() == null) return false;
        var area = RitualOfferingArea.outermost(site.plane());
        return materials.stream().allMatch(p -> !RitualMaterials.find(level, area, p).isEmpty());
    }
    public boolean start(ServerPlayer player, RitualSite site, CompoundTag suppliedTerms) {
        ServerLevel level = player.serverLevel();
        if (player.isFakePlayer() || player.isSpectator() || !player.mayBuild()
                || !level.mayInteract(player, site.position()) || !RitualCenters.current(level, site)) return false;
        if (RitualResearch.check(level, player.getUUID(), definition.research()) != RitualResearch.Result.ALLOWED) return false;
        var occupants = GoldenRitualSlots.sessions(level, site.plane());
        if (occupants.stream().anyMatch(h -> h.definition().kind().equals(definition.kind()))) return false;
        var plane = site.plane();
        UUID proposed = UUID.randomUUID();
        if (plane == null || !GoldenRitualSlots.allocate(level, plane, proposed, definition.minimumRing()).containsKey(proposed)) return false;
        var area = RitualOfferingArea.outermost(plane);
        for (var matches : materials) if (RitualMaterials.find(level, area, matches).isEmpty()) return false;
        return RitualCenters.begin(player, site, definition, suppliedTerms).isPresent();
    }

    public boolean tick(ServerLevel level, RitualSite site, UUID id, boolean resourcesAvailable) {
        var center = site.center();
        var plane = site.plane();
        var store = StructuralRitualStore.get(level);
        var progress = store.progress(id).orElseThrow();
        boolean allocated = plane != null && progress.terms().getLong("Plane") == plane.anchor().asLong()
                && GoldenRitualSlots.allocate(level, plane, null, definition.minimumRing()).containsKey(id);
        return store.advance(level, id, definition, StructuralRitualStore.Presence.PRESENT, center.reference(),
                allocated ? RitualShape.Match.VALID : RitualShape.Match.INVALID, resourcesAvailable, (step, terms) -> {
                    if (level.getGameTime() % 20 != 0) return Optional.empty();
                    var area = RitualOfferingArea.outermost(plane);
                    var source = RitualMaterials.find(level, area, materials.get(step.index()))
                            .stream().findFirst().orElse(null);
                    if (source == null) return Optional.empty();
                    return Optional.of(new StructuralRitualStore.PreparedStep(terms, () -> {
                        if (!RitualCenters.current(level, site) || !RitualMaterials.inArea(source, area)
                                || !materials.get(step.index()).test(source.item())) return false;
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
