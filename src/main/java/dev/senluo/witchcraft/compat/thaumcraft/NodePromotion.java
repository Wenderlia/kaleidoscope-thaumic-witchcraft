package dev.senluo.witchcraft.compat.thaumcraft;

import dev.tc4port.thaumcraft.api.ThaumcraftContent;
import dev.senluo.witchcraft.ritual.*;
import dev.tc4port.thaumcraft.api.aspect.AspectId;
import java.math.BigInteger;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.LevelEvent;

/** Anchor, node parameter source and derived field have separate roles; activation does not choose the executor. */
public final class NodePromotion implements RitualCenters.Behavior {
    @Override public int activationPriority() { return 30; }
    @Override public RitualRecipe recipe() { return RECIPE; }
    public static final StructuralRitualStore.Definition DEFINITION = new StructuralRitualStore.Definition(
            ResourceLocation.fromNamespaceAndPath("kaleidoscope_witchcraft", "node_promotion"),
            1, 80, new int[]{20,40,60,80}, RitualRings.MEDIUM, RitualResearch.thaumcraft("WITCHERY_CIRCLE_BASICS"));
    private static final RitualRecipe RECIPE = new RitualRecipe(DEFINITION,
            java.util.stream.IntStream.range(0, 3).mapToObj(step -> (java.util.function.Predicate<ItemStack>) stack -> matches(stack, step)).toList(), true,
            new RitualRecipe.Payment(RitualRecipe.SourceOrder.NEAREST, false, Map.of(), .1, .05,
                    new RitualRecipe.Completion(SoundEvents.BEACON_ACTIVATE, .5F, 1.2F, null)));
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
    @Override public Optional<CompoundTag> prepare(RitualCandidate site, net.minecraft.server.level.ServerPlayer player) {
        return NodeRituals.findNodeAtAxis(player.serverLevel(), site.footprint().anchor()) == null
                ? Optional.empty() : Optional.of(new CompoundTag());
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
                RECIPE.payments(level, site, DEFINITION, store.progress(id).orElseThrow(),
                        () -> source(level, id) != null, () -> RitualRecipe.Commit.APPLIED));
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
}
