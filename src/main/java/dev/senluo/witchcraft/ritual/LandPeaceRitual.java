package dev.senluo.witchcraft.ritual;

import dev.senluo.witchcraft.region.*;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;

/** Medium golden-circle rite. Preparation is timed; the installed region has no ticker. */
public final class LandPeaceRitual implements RitualCenters.Behavior {
    @Override public int activationPriority() { return 10; }
    @Override public RitualRecipe recipe() { return RECIPE; }
    @Override public boolean continuous() { return false; }
    @Override public long publicationVersion(ServerLevel level) { return RegionRules.store(level).receiptVersion(); }
    public static final StructuralRitualStore.Definition DEFINITION = new StructuralRitualStore.Definition(
            RegionRules.id("land_peace"), 1, 160, new int[]{20,40,60,80,100,120,140,160}, RitualRings.MEDIUM, RitualResearch.thaumcraft("CRUCIBLE_STABILITY"));
    private static final RitualRecipe RECIPE = new RitualRecipe(DEFINITION,
            java.util.stream.IntStream.range(0, 7).mapToObj(step -> (java.util.function.Predicate<ItemStack>) stack -> matches(stack, step)).toList(), true,
            new RitualRecipe.Payment(RitualRecipe.SourceOrder.NEAREST, false, Map.of(), 0, .04,
                    new RitualRecipe.Completion(SoundEvents.BEACON_ACTIVATE, .6F, .8F,
                            new RitualRecipe.Burst(18, new Vec3(.5, .4, .5), new Vec3(.7, .3, .7), .03))));
    private static final ResourceLocation CONDITION = RegionRules.id("ritual_authorization");
    public record Node(BlockPos position, UUID identity) { public Node { position = position.immutable(); } }
    public interface NodeOffering {
        Optional<Node> find(ServerLevel level, RitualSite.Footprint footprint);
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
        RegionRules.registerCondition(CONDITION, LandPeaceRitual::validCondition, LandPeaceRitual::conditionVersion);
        RegionRules.registerCondition(RegionRules.id("golden_bearing_ring"), LandPeaceRitual::validCondition, LandPeaceRitual::conditionVersion);
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
    private static boolean validTerms(CompoundTag terms) {
        return terms.contains("Permanent", Tag.TAG_BYTE)
                && (!terms.getBoolean("Permanent") || terms.hasUUID("Node") && terms.contains("NodePosition", Tag.TAG_LONG));
    }
    private static Node node(CompoundTag terms) { return new Node(BlockPos.of(terms.getLong("NodePosition")), terms.getUUID("Node")); }
    private static boolean nodeReady(ServerLevel level, CompoundTag terms) {
        return !terms.getBoolean("Permanent") || nodes != null && nodes.present(level, node(terms));
    }
    @Override public Optional<CompoundTag> prepare(RitualCandidate site, net.minecraft.server.level.ServerPlayer player) {
        var level = player.serverLevel();
        UUID prospective = UUID.randomUUID();
        if (!RegionRules.store(level).canInstall(prospective)) return Optional.empty();
        var terms = new CompoundTag();
        Optional<Node> node = nodes == null ? Optional.empty() : nodes.find(level, site.footprint());
        terms.putBoolean("Permanent", node.isPresent());
        node.ifPresent(n -> { terms.putUUID("Node", n.identity); terms.putLong("NodePosition", n.position.asLong()); });
        return Optional.of(terms);
    }
    @Override public boolean finished(ServerLevel level, StructuralRitualStore.Handle handle) {
        return RegionRules.store(level).entry(handle.id()).filter(e -> e.condition().isEmpty()).isPresent();
    }
    @Override public void tick(ServerLevel level, RitualSite site, StructuralRitualStore.Handle handle) {
        var store = StructuralRitualStore.get(level);
        var progress = store.progress(handle.id()).orElseThrow();
        if (progress.phase() == StructuralRitualStore.Phase.ENDED) { RitualCenters.end(level, handle.id()); return; }
        if (!validTerms(progress.terms())) return;
        if (progress.phase() == StructuralRitualStore.Phase.ACTIVE) {
            if (!store.advance(level, handle.id(), DEFINITION, StructuralRitualStore.Presence.PRESENT,
                    RitualShape.Match.VALID, (token, terms) -> Optional.empty()).mayRun()) return;
            if (!progress.available()) RegionRules.store(level).invalidate(handle.id());
            RegionRules.store(level).entry(handle.id()).filter(e -> e.condition().isPresent() && !e.condition().get().kind().equals(CONDITION))
                    .ifPresent(e -> {
                        var migrated = new RegionRuleStore.Entry(e.id(), e.rules(), e.minX(), e.minZ(), e.maxX(), e.maxZ(),
                                entry(handle.id(), site, progress.terms()).condition());
                        RegionRules.store(level).remove(handle.id()); RegionRules.store(level).install(migrated);
                    });
            // Repair a missing derived region receipt after a partial world save, without replaying payment.
            if (!RegionRules.store(level).contains(handle.id()) && RegionRules.store(level).canInstall(handle.id()))
                RegionRules.store(level).install(entry(handle.id(), site, progress.terms()));
            if (progress.terms().getBoolean("Permanent") && RegionRules.store(level).contains(handle.id())) {
                RitualCenters.end(level, handle.id());
            }
            return; // No structural inspection or per-second effect work once installed.
        }
        if (site.footprint() == null) return;
        var region = RegionRules.store(level);
        var terms = progress.terms();
        boolean alreadyPaid = region.contains(handle.id());
        var payment = RECIPE.payments(level, site, DEFINITION, progress,
                () -> progress.committedSteps() == 7 && region.contains(handle.id())
                        || nodeReady(level, terms) && region.canInstall(handle.id()),
                () -> {
                    if (region.contains(handle.id())) return RitualRecipe.Commit.ALREADY_APPLIED;
                    if (!region.canInstall(handle.id())
                            || terms.getBoolean("Permanent") && !nodes.consume(level, node(terms))) return RitualRecipe.Commit.REJECTED;
                    region.install(entry(handle.id(), site, terms));
                    return RitualRecipe.Commit.APPLIED;
                });
        var result = store.advance(level, handle.id(), DEFINITION, StructuralRitualStore.Presence.PRESENT,
                RitualShape.Match.VALID,
                alreadyPaid || nodeReady(level, terms), payment);
        if (result.newlyCommittedStep() == 7) {
            RegionRules.store(level).chunkChanged(ChunkPos.asLong(site.position()));
            if (finished(level, handle)) RitualCenters.end(level, handle.id());
        }
    }
    private static RegionRuleStore.Entry entry(UUID id, RitualSite site, CompoundTag terms) {
        BlockPos position = site.position(), anchor = terms.contains("Plane", Tag.TAG_LONG)
                ? BlockPos.of(terms.getLong("Plane")) : site.axis();
        Optional<RegionRuleStore.Condition> condition = Optional.empty();
        if (!terms.getBoolean("Permanent")) {
            CompoundTag data = new CompoundTag();
            data.putUUID("Session", id);
            List<BlockPos> dependencies = List.of(position, anchor);
            condition = Optional.of(new RegionRuleStore.Condition(CONDITION, data, dependencies));
        }
        ChunkPos center = new ChunkPos(position);
        return new RegionRuleStore.Entry(id, Set.of(RegionRules.NO_MOB_BLOCK_CHANGES, RegionRules.NO_EXPLOSION_BLOCK_CHANGES),
                center.x - 1, center.z - 1, center.x + 1, center.z + 1, condition);
    }
    private static boolean validCondition(ServerLevel level, RegionRuleStore.Condition condition) {
        CompoundTag data = condition.data();
        return data.hasUUID("Session") && StructuralRitualStore.get(level).running(level, data.getUUID("Session"));
    }
    private static long conditionVersion(ServerLevel level, RegionRuleStore.Condition condition) {
        CompoundTag data = condition.data();
        return data.hasUUID("Session") ? StructuralRitualStore.get(level).permissionVersion(level, data.getUUID("Session")) : Long.MIN_VALUE;
    }
    @Override public void unavailable(ServerLevel level, StructuralRitualStore.Handle handle) {
        RegionRules.store(level).invalidate(handle.id());
    }
    @Override public void removed(ServerLevel level, StructuralRitualStore.Handle handle) {
        var region = RegionRules.store(level);
        region.entry(handle.id()).filter(e -> e.condition().isPresent()).ifPresent(e -> region.remove(handle.id()));
    }
    private LandPeaceRitual() {}
}
