package dev.senluo.witchcraft.ritual;

import dev.senluo.witchcraft.region.*;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;

/** Medium golden-circle rite. Preparation is timed; the installed region has no ticker. */
public final class LandPeaceRitual implements RitualCenters.Behavior {
    @Override public StructuralRitualStore.Definition definition() { return DEFINITION; }
    public static final StructuralRitualStore.Definition DEFINITION = new StructuralRitualStore.Definition(
            RegionRules.id("land_peace"), 1, 160, new int[]{20,40,60,80,100,120,140,160}, RitualRings.MEDIUM, RitualResearch.thaumcraft("CRUCIBLE_STABILITY"));
    private static final ResourceLocation CONDITION = RegionRules.id("golden_bearing_ring");
    public record Node(BlockPos position, UUID identity) { public Node { position = position.immutable(); } }
    public interface NodeOffering {
        Optional<Node> find(ServerLevel level, RitualSite site);
        boolean present(ServerLevel level, Node node);
        boolean consume(ServerLevel level, Node node);
    }
    private static NodeOffering nodes;
    public static void nodeOffering(NodeOffering provider) {
        if (nodes != null) throw new IllegalStateException("Node offering already registered");
        nodes = Objects.requireNonNull(provider);
    }
    public static void register() {
        RitualCenters.register(new LandPeaceRitual());
        RegionRules.registerCondition(CONDITION, LandPeaceRitual::validCondition);
    }
    private static boolean matches(ItemStack stack, int step) {
        if (stack.isEmpty()) return false;
        return switch (step) {
            case 0 -> stack.is(Items.CRYING_OBSIDIAN);
            case 1 -> stack.is(Items.TNT);
            case 2 -> stack.is(Items.COBWEB);
            case 3 -> stack.is(ItemTags.SHOVELS);
            case 4 -> stack.is(Items.WITHER_SKELETON_SKULL);
            case 5 -> stack.has(DataComponents.FOOD);
            case 6 -> stack.is(Items.GOLD_INGOT);
            default -> false;
        };
    }
    private static Optional<RitualMaterials.Source> offering(ServerLevel level, RitualPlane plane, int step) {
        var area = RitualOfferingArea.outermost(plane);
        return RitualMaterials.find(level, area, stack -> matches(stack, step)).stream()
                .min(Comparator.comparingDouble(source -> source.position().distanceToSqr(area.bounds().getCenter())));
    }
    private static List<StructuralRitualStore.Handle> sessions(ServerLevel level, RitualSite site) {
        return GoldenRitualSlots.sessions(level, site.plane()).stream()
                .filter(h -> h.definition().kind().equals(DEFINITION.kind())).toList();
    }
    private static boolean validTerms(CompoundTag terms) {
        int ring = terms.getInt(GoldenRitualSlots.PINNED_RING);
        return ring >= RitualRings.MEDIUM && ring <= RitualRings.LARGE
                && terms.contains("Permanent", Tag.TAG_BYTE)
                && (!terms.getBoolean("Permanent") || terms.hasUUID("Node") && terms.contains("NodePosition", Tag.TAG_LONG));
    }
    private static Node node(CompoundTag terms) { return new Node(BlockPos.of(terms.getLong("NodePosition")), terms.getUUID("Node")); }
    private static boolean nodeReady(ServerLevel level, CompoundTag terms) {
        return !terms.getBoolean("Permanent") || nodes != null && nodes.present(level, node(terms));
    }
    @Override public InteractionResult activate(ServerLevel level, RitualSite site, net.minecraft.server.level.ServerPlayer player) {
        var store = StructuralRitualStore.get(level);
        // Repeated clicks while this recipe is preparing do not dispatch into another recipe.
        for (var handle : sessions(level, site)) if (store.progress(handle.id())
                .filter(p -> p.phase() == StructuralRitualStore.Phase.PREPARING).isPresent()) return InteractionResult.CONSUME;
        var center = site.center();
        RitualPlane.invalidate(level, center.axis());
        var plane = site.plane();
        if (plane == null) return InteractionResult.PASS;
        for (int step = 0; step < 7; step++) if (offering(level, plane, step).isEmpty()) return InteractionResult.PASS;
        if (RitualResearch.check(level, player.getUUID(), DEFINITION.research()) != RitualResearch.Result.ALLOWED) return InteractionResult.FAIL;
        UUID prospective = UUID.randomUUID();
        Integer ring = GoldenRitualSlots.allocate(level, plane, prospective, RitualRings.MEDIUM).get(prospective);
        if (ring == null || !RegionRules.store(level).canInstall(prospective)) return InteractionResult.FAIL;
        var terms = new CompoundTag();
        terms.putInt(GoldenRitualSlots.PINNED_RING, ring);
        terms.putLong("Plane", plane.anchor().asLong());
        Optional<Node> node = nodes == null ? Optional.empty() : nodes.find(level, site);
        terms.putBoolean("Permanent", node.isPresent());
        node.ifPresent(n -> { terms.putUUID("Node", n.identity); terms.putLong("NodePosition", n.position.asLong()); });
        if (store.begin(level, DEFINITION, center, player.getUUID(), terms).isEmpty()) return InteractionResult.FAIL;
         return InteractionResult.CONSUME;
    }
    @Override public void tick(ServerLevel level, RitualSite site, StructuralRitualStore.Handle handle) {
        var store = StructuralRitualStore.get(level);
        var progress = store.progress(handle.id()).orElseThrow();
        if (progress.phase() == StructuralRitualStore.Phase.ENDED) { store.finish(handle.id()); return; }
        if (!validTerms(progress.terms())) return;
        if (progress.phase() == StructuralRitualStore.Phase.ACTIVE) {
            // Repair a missing derived region receipt after a partial world save, without replaying payment.
            if (!RegionRules.store(level).contains(handle.id()) && RegionRules.store(level).canInstall(handle.id()))
                RegionRules.store(level).install(entry(handle.id(), site, progress.terms()));
            if (progress.terms().getBoolean("Permanent") && RegionRules.store(level).contains(handle.id())) {
                store.end(level, handle.id()); store.finish(handle.id());
            }
            return; // No structural inspection or per-second effect work once installed.
        }
        if (site.plane() == null) return;
        var center = site.center();
        boolean alreadyPaid = RegionRules.store(level).contains(handle.id());
        var result = store.advance(level, handle.id(), DEFINITION, StructuralRitualStore.Presence.PRESENT,
                center.reference(), GoldenRitualSlots.allocate(level, site.plane(), null, RitualRings.MEDIUM).containsKey(handle.id())
                        ? RitualShape.Match.VALID : RitualShape.Match.INVALID,
                alreadyPaid || nodeReady(level, progress.terms()), (token, terms) -> prepare(level, site, token, terms));
        if (result.newlyCommittedStep() == 7) RegionRules.store(level).chunkChanged(ChunkPos.asLong(site.position()));
    }
    private static Optional<StructuralRitualStore.PreparedStep> prepare(ServerLevel level, RitualSite site,
            StructuralRitualStore.StepToken token, CompoundTag terms) {
        if (level.getGameTime() % 20 != 0) return Optional.empty(); // Failed material lookups retry at most once a second.
        var region = RegionRules.store(level);
        if (token.index() == 7 && region.contains(token.session()))
            return Optional.of(new StructuralRitualStore.PreparedStep(terms, () -> true));
        var plane = site.plane();
        if (plane == null || !nodeReady(level, terms) || !region.canInstall(token.session())) return Optional.empty();
        var source = token.index() < 7 ? offering(level, plane, token.index()).orElse(null) : null;
        if (token.index() < 7 && source == null) return Optional.empty();
        var receipt = entry(token.session(), site, terms);
        return Optional.of(new StructuralRitualStore.PreparedStep(terms, () -> {
            if (!RitualCenters.current(level, site) || !nodeReady(level, terms)) return false;
            RitualPlane.invalidate(level, plane.anchor());
            if (RitualPlane.at(level, plane.anchor()).filter(fresh -> GoldenRitualSlots.allocate(
                    level, fresh, null, RitualRings.MEDIUM).containsKey(token.session())).isEmpty()) return false;
            if (token.index() == 7) {
                if (!region.canInstall(token.session())) return false;
                if (terms.getBoolean("Permanent") && !nodes.consume(level, node(terms))) return false;
                region.install(receipt);
                level.playSound(null, site.position(), SoundEvents.BEACON_ACTIVATE, SoundSource.BLOCKS, .6F, .8F);
                level.sendParticles(ParticleTypes.ENCHANT, site.position().getX() + .5,
                        site.position().getY() + .4, site.position().getZ() + .5, 18, .7, .3, .7, .03);
                return true;
            }
            var fresh = site.plane();
            if (fresh == null || !RitualMaterials.inArea(source, RitualOfferingArea.outermost(fresh)) || !matches(source.item(), token.index())) return false;
            var item = source.takeOne();
            if (item.isEmpty()) return false;
            Vec3 origin = source.position(), end = Vec3.atCenterOf(site.position());
            OfferingSounds.absorb(level, origin, item);
            for (int i = 0; i < 8; i++) {
                Vec3 p = origin.lerp(end, i / 7.0);
                level.sendParticles(ParticleTypes.ENCHANT, p.x, p.y, p.z, 1, .04, .04, .04, .01);
            }
            return true;
        }));
    }
    private static RegionRuleStore.Entry entry(UUID id, RitualSite site, CompoundTag terms) {
        BlockPos position = site.position(), anchor = terms.contains("Plane", Tag.TAG_LONG)
                ? BlockPos.of(terms.getLong("Plane")) : site.axis();
        int ring = terms.getInt(GoldenRitualSlots.PINNED_RING);
        Optional<RegionRuleStore.Condition> condition = Optional.empty();
        if (!terms.getBoolean("Permanent")) {
            CompoundTag data = new CompoundTag();
            data.putLong("Heart", position.asLong()); data.putUUID("Identity", site.reference().identity());
            data.putLong("Plane", anchor.asLong()); data.putInt("Ring", ring); data.putUUID("Session", id);
            List<BlockPos> dependencies = new ArrayList<>(List.of(position, anchor));
            for (BlockPos offset : RitualRings.shape(ring).marks()) {
                BlockPos support = anchor.offset(offset); dependencies.add(support); dependencies.add(support.above());
            }
            condition = Optional.of(new RegionRuleStore.Condition(CONDITION, data, dependencies));
        }
        ChunkPos center = new ChunkPos(position);
        return new RegionRuleStore.Entry(id, Set.of(RegionRules.NO_MOB_BLOCK_CHANGES, RegionRules.NO_EXPLOSION_BLOCK_CHANGES),
                center.x - 1, center.z - 1, center.x + 1, center.z + 1, condition);
    }
    private static boolean validCondition(ServerLevel level, RegionRuleStore.Condition condition) {
        CompoundTag data = condition.data(); int ring = data.getInt("Ring");
        if (!data.contains("Heart", Tag.TAG_LONG) || !data.hasUUID("Identity") || !data.hasUUID("Session")
                || ring < RitualRings.MEDIUM || ring > RitualRings.LARGE) return false;
        BlockPos pos = BlockPos.of(data.getLong("Heart"));
        // Unloaded is unavailable, never interpreted as destruction or forced to load.
        if (!level.hasChunkAt(pos)) return false;
        var center = RitualCenters.observe(level.getBlockEntity(pos)).orElse(null);
        if (center == null || !center.mayPrepare() || !center.reference().identity().equals(data.getUUID("Identity"))) return false;
        var store = StructuralRitualStore.get(level);
        if (store.progress(data.getUUID("Session")).filter(p -> p.phase() == StructuralRitualStore.Phase.ACTIVE).isEmpty()) return false;
        // The installed lease pins this ring; unrelated receipts cannot move it or invalidate this cache.
        BlockPos plane = data.contains("Plane", Tag.TAG_LONG) ? BlockPos.of(data.getLong("Plane")) : pos.below();
        return RitualRings.shape(ring).inspectLoaded(level, plane) == RitualShape.Match.VALID;
    }
    @Override public void removed(ServerLevel level, StructuralRitualStore.Handle handle) {
        var region = RegionRules.store(level);
        region.entry(handle.id()).filter(e -> e.condition().isPresent()).ifPresent(e -> region.remove(handle.id()));
        RitualCenters.Behavior.super.removed(level, handle);
    }
    private LandPeaceRitual() {}
}
