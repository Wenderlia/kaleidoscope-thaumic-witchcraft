package dev.senluo.witchcraft.compat.thaumcraft;

import dev.tc4port.thaumcraft.api.ThaumcraftContent;
import dev.senluo.witchcraft.ritual.*;
import dev.tc4port.thaumcraft.api.aspect.AspectId;
import java.math.BigInteger;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.LevelEvent;

/** Anchor, node parameter source and derived field have separate roles; activation does not choose the executor. */
public final class NodePromotion implements RitualCenters.Behavior {
    @Override public int activationPriority() { return 30; }
    @Override public StructuralRitualStore.Definition definition() { return DEFINITION; }
    public static final StructuralRitualStore.Definition DEFINITION = new StructuralRitualStore.Definition(
            ResourceLocation.fromNamespaceAndPath("kaleidoscope_witchcraft", "node_promotion"),
            1, 80, new int[]{20,40,60,80}, RitualRings.MEDIUM, RitualResearch.thaumcraft("WITCHERY_CIRCLE_BASICS"));
    private static final AspectId ENERGY = AspectId.parse("potentia"), AURA = AspectId.parse("auram");
    private static final Map<ServerLevel, NodePromotionFields> FIELDS = new WeakHashMap<>();
    private static final NodePromotionFields.Influence NONE = new NodePromotionFields.Influence(BigInteger.ONE, Set.of());
    private NodePromotion() {}
    public static void register() {
        RitualCenters.register(new NodePromotion());
        NeoForge.EVENT_BUS.addListener((LevelEvent.Unload event) -> {
            if (event.getLevel() instanceof ServerLevel level) FIELDS.remove(level);
        });
    }
    public static NodePromotionFields.Influence influence(ServerLevel level, BlockPos pos) {
        var fields = FIELDS.get(level);
        return fields == null ? NONE : fields.at(pos);
    }
    private static boolean matches(ItemStack stack, int step) {
        return switch (step) {
            case 0 -> stack.is(Items.AMETHYST_SHARD);
            case 1 -> stack.is(ThaumcraftContent.item("wisp_essence"));
            case 2 -> stack.is(ThaumcraftContent.item("primordial_pearl"));
            default -> false;
        };
    }
    private static Optional<RitualMaterials.Source> offering(ServerLevel level, RitualSite.Footprint footprint, int step) {
        var area = RitualOfferingArea.outermost(footprint);
        return RitualMaterials.find(level, area, stack -> matches(stack, step)).stream()
                .min(Comparator.comparingDouble(source -> source.position().distanceToSqr(area.bounds().getCenter())));
    }
    @Override public InteractionResult activate(ServerLevel level, RitualSite site, net.minecraft.server.level.ServerPlayer player) {
        var footprint = site.footprint();
        if (footprint == null) return InteractionResult.PASS;
        for (int step = 0; step < 3; step++) if (offering(level, footprint, step).isEmpty()) return InteractionResult.PASS;
        if (!site.center().mayPrepare()
                || RitualResearch.check(level, player.getUUID(), DEFINITION.research()) != RitualResearch.Result.ALLOWED
                || NodeRituals.findNode(level, site) == null) return InteractionResult.FAIL;
        var store = StructuralRitualStore.get(level);
        var handles = store.atPlane(level.dimension().location(), footprint.anchor());
        if (handles.stream().anyMatch(h -> h.definition().kind().equals(DEFINITION.kind()) && store.progress(h.id())
                .filter(p -> p.phase() == StructuralRitualStore.Phase.PREPARING).isPresent())) return InteractionResult.CONSUME;
        var terms = new CompoundTag();
        terms.putLong("Plane", footprint.anchor().asLong());
        return RitualCenters.begin(player, site, DEFINITION, terms).isPresent()
                ? InteractionResult.CONSUME : InteractionResult.FAIL;
    }
    @Override public java.util.function.Predicate<net.minecraft.world.level.block.entity.BlockEntity> target() {
        return entity -> entity.getBlockState().is(ThaumcraftContent.block("aura_node"));
    }
    @Override public void unavailable(ServerLevel level, StructuralRitualStore.Handle handle) {
        var fields = FIELDS.get(level);
        if (fields != null) fields.remove(handle.id());
    }
    private static net.minecraft.world.level.block.entity.BlockEntity source(ServerLevel level, UUID session) {
        var target = RitualCenters.target(level, session);
        return target == null ? null : NodeRituals.node(level, target.getBlockPos());
    }
    @Override public void tick(ServerLevel level, RitualSite site, StructuralRitualStore.Handle handle) {
        var store = StructuralRitualStore.get(level);
        var fields = FIELDS.computeIfAbsent(level, unused -> new NodePromotionFields(session -> store.running(level, session)));
        var id = handle.id();
        var node = source(level, id);
        var result = store.advance(level, id, DEFINITION, StructuralRitualStore.Presence.PRESENT,
                RitualShape.Match.VALID, node != null,
                (token, terms) -> prepare(level, site, token.session(), token.index(), terms));
        if (!result.mayRun() || node == null) {
            if (!store.pending(id)) fields.remove(id);
            return;
        }
        // Unchanged triangular thresholds do not rebuild the field index.
        var capacity = dev.tc4port.thaumcraft.api.node.NodeApi.view(level, node.getBlockPos()).nodeState().baseVis();
        fields.put(new NodePromotionFields.Field(id, node.getBlockPos(),
                1 + NodePromotionFields.steps(capacity.amount(AURA)),
                2 + NodePromotionFields.steps(capacity.amount(ENERGY)), store.initiator(id).orElse(null)));
    }
    private static Optional<StructuralRitualStore.PreparedStep> prepare(ServerLevel level, RitualSite site, UUID session, int step, CompoundTag terms) {
        if (site == null || level.getGameTime() % 20 != 0) return Optional.empty();
        var footprint = site.footprint();
        if (footprint == null) return Optional.empty();
        var source = step < 3 ? offering(level, footprint, step).orElse(null) : null;
        if (step < 3 && source == null) return Optional.empty();
        return Optional.of(new StructuralRitualStore.PreparedStep(terms, () -> {
            if (!RitualCenters.current(level, site) || source(level, session) == null) return false;
            if (step == 3) {
                level.playSound(null, site.position(), SoundEvents.BEACON_ACTIVATE, SoundSource.BLOCKS, .5F, 1.2F); return true;
            }
            if (!RitualMaterials.inArea(source, RitualOfferingArea.outermost(footprint)) || !matches(source.item(),step)) return false;
            var taken = source.takeOne();
            if (taken.isEmpty()) return false;
            OfferingSounds.absorb(level, source.position(), taken);
            Vec3 end = Vec3.atCenterOf(site.position());
            for (int i=0;i<8;i++) {
                var point = source.position().lerp(end,i/7.0);
                level.sendParticles(ParticleTypes.ENCHANT,point.x,point.y+.1,point.z,1,.05,.05,.05,.01);
            }
            return true;
        }));
    }
}
