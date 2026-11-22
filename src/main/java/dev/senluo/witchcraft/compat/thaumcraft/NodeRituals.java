package dev.senluo.witchcraft.compat.thaumcraft;

import dev.tc4port.thaumcraft.api.ThaumcraftContent;
import dev.senluo.witchcraft.ritual.*;
import dev.tc4port.thaumcraft.api.aspect.VisAction;
import dev.tc4port.thaumcraft.api.aspect.AspectId;
import dev.tc4port.thaumcraft.api.aspect.VisCost;
import dev.tc4port.thaumcraft.api.node.NodeApi;
import dev.tc4port.thaumcraft.api.node.NodeVis;
import net.minecraft.world.level.block.entity.BlockEntity;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.PotionItem;

/** Node effect adapter. Geometry, slots, material access and persistence remain shared. */
public final class NodeRituals implements RitualCenters.Behavior {
    @Override public int activationPriority() { return 40; }
    @Override public RitualRecipe recipe() { return RECIPE; }
    public static final StructuralRitualStore.Definition RESTORATION = new StructuralRitualStore.Definition(
            ResourceLocation.fromNamespaceAndPath("kaleidoscope_witchcraft", "aura_restoration"),
            1, 80, new int[]{20, 40, 60, 80}, RitualRings.SMALL, RitualResearch.thaumcraft("CRUCIBLE_STABILITY"));
    private static final RitualRecipe RECIPE = new RitualRecipe(RESTORATION,
            java.util.stream.IntStream.range(0, 3).mapToObj(step -> (java.util.function.Predicate<ItemStack>) stack -> matches(stack, step)).toList(), true,
            new RitualRecipe.Payment(RitualRecipe.SourceOrder.NEAREST, true, Map.of(0, Items.GLASS_BOTTLE), .1, .05,
                    new RitualRecipe.Completion(SoundEvents.BEACON_ACTIVATE, .5F, 1.2F, null)));
    private record Recharge(ServerPlayer player, int slot, ItemStack wand, ItemStack snapshot, VisCost amount) {}
    private static final RingBonus STABILITY = RingBonus.additive(1, 2, 4, 11);
    private NodeRituals() {}
    public static void register() {
        NodeSteam.register();
        NodePromotion.register();
        RitualCenters.register(new NodeRituals());
        LandPeaceRitual.nodeOffering(new LandPeaceRitual.NodeOffering() {
            @Override public Optional<LandPeaceRitual.Node> find(ServerLevel level, RitualSite.Footprint footprint) {
                var target = findNodeAtAxis(level, footprint.anchor());
                return target == null ? Optional.empty() : NodeApi.view(level, target.getBlockPos()).nodeState().instanceId()
                        .map(id -> new LandPeaceRitual.Node(target.getBlockPos(), id));
            }
            @Override public boolean present(ServerLevel level, LandPeaceRitual.Node offering) {
                var target = node(level, offering.position());
                return target != null && NodeApi.view(level, target.getBlockPos()).nodeState().instanceId().filter(offering.identity()::equals).isPresent();
            }
            @Override public boolean consume(ServerLevel level, LandPeaceRitual.Node offering) {
                return present(level, offering) && level.removeBlock(offering.position(), false);
            }
        });
    }

    static BlockEntity node(ServerLevel level, BlockPos pos) {
        if (!level.hasChunkAt(pos) || !level.shouldTickBlocksAt(pos)
                || !level.getBlockState(pos).is(ThaumcraftContent.block("aura_node"))) return null;
        var node = level.getBlockEntity(pos);
        return node != null && NodeApi.view(level, pos) != null && !node.isRemoved() ? node : null;
    }
    /** Nearest first, above before below on ties; bounded by the existing ritual height convention. */
    static BlockEntity findNode(ServerLevel level, BlockPos heart) {
        return findNodeAtAxis(level, heart.below());
    }
    static BlockEntity findNodeAtAxis(ServerLevel level, BlockPos center) {
        return RitualPlane.centerPositions(center).map(pos -> node(level, pos)).filter(Objects::nonNull).findFirst().orElse(null);
    }
    private static boolean termsValid(CompoundTag terms) {
        return terms.hasUUID("Node") && terms.contains("NodePosition", Tag.TAG_LONG);
    }
    private static BlockEntity target(ServerLevel level, CompoundTag terms) {
        if (!termsValid(terms)) return null;
        var target = node(level, BlockPos.of(terms.getLong("NodePosition")));
        return target != null && NodeApi.view(level, target.getBlockPos()).nodeState().instanceId().filter(terms.getUUID("Node")::equals).isPresent() ? target : null;
    }
    private static boolean matches(ItemStack stack, int step) {
        if (stack.isEmpty()) return false;
        if (step == 1) return stack.is(ThaumcraftContent.item("wisp_essence"));
        if (step == 2) return stack.is(Items.DIAMOND);
        if (step != 0 || !(stack.getItem() instanceof PotionItem)) return false;
        var potion = stack.get(DataComponents.POTION_CONTENTS);
        if (potion == null) return false;
        for (var effect : potion.getAllEffects()) if (effect.is(MobEffects.REGENERATION)) return true;
        return false;
    }
    private static RitualOfferingArea area(RitualSite.Footprint footprint) {
        return RitualOfferingArea.outermost(footprint);
    }
    @Override public Optional<CompoundTag> prepare(RitualCandidate site, ServerPlayer player) {
        var level = player.serverLevel();
        var node = findNodeAtAxis(level, site.footprint().anchor());
        if (node == null) return Optional.empty();
        var identity = NodeApi.view(level, node.getBlockPos()).nodeState().instanceId();
        if (identity.isEmpty()) return Optional.empty();
        var terms = new CompoundTag();
        terms.putUUID("Node", identity.get());
        terms.putLong("NodePosition", node.getBlockPos().asLong());
        return Optional.of(terms);
    }
    @Override public void tick(ServerLevel level, RitualSite site, StructuralRitualStore.Handle h) {
        var store = StructuralRitualStore.get(level);
        var footprint = site.footprint();
        var progress = store.progress(h.id()).orElseThrow();
        // Unknown future terms are preserved, not activated or silently reset.
        if (!termsValid(progress.terms())) return;
        BlockPos nodePos = BlockPos.of(progress.terms().getLong("NodePosition"));
        if (!level.hasChunkAt(nodePos) || !level.shouldTickBlocksAt(nodePos)) return;
        if (target(level, progress.terms()) == null) { RitualCenters.end(level, h.id()); return; }
        if (progress.phase() == StructuralRitualStore.Phase.ENDED) { RitualCenters.end(level, h.id()); return; }
        boolean ready = footprint != null && footprint.containsCenter(nodePos);
        var result = store.advance(level, h.id(), RESTORATION, StructuralRitualStore.Presence.PRESENT,
                ready ? RitualShape.Match.VALID : RitualShape.Match.INVALID,
                RECIPE.payments(level, site, RESTORATION, progress,
                        () -> target(level, progress.terms()) != null, () -> RitualRecipe.Commit.APPLIED));
        if (result.mayRun() && level.getGameTime() % 20 == 0 && footprint != null && recharge(level, site, footprint, progress.terms()))
            RitualSounds.infusionPulse(level, site.position());
    }
    private static boolean recharge(ServerLevel level, RitualSite site, RitualSite.Footprint footprint, CompoundTag terms) {
        var node = target(level, terms);
        if (node == null) return false;
        if (NodeApi.view(level, node.getBlockPos()).nodeState().baseVis().amounts().values().stream().noneMatch(value -> value > 0)) {
            level.removeBlock(node.getBlockPos(), false); return false;
        }
        var area = area(footprint);
        var credits = new ArrayList<Recharge>();
        for (var player : level.players()) {
            if (!player.isAlive() || player.isSpectator() || !area.bounds().contains(player.position())
                    || !area.containsHorizontal(player.position())) continue;
            var inventory = player.getInventory();
            for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
                var wand = inventory.getItem(slot);
                var storage = WandCharging.storage(wand);
                if (storage == null) continue;
                var view = storage.snapshot();
                var amounts = new LinkedHashMap<AspectId, Integer>();
                for (var entry : view.capacityCentivis().entrySet()) {
                    var channel = entry.getKey();
                    int capacity = entry.getValue();
                    int amount = Math.min(capacity / 10, Math.max(0, capacity - view.currentCentivis().getOrDefault(channel, 0)));
                    if (amount > 0) amounts.put(channel, amount);
                }
                if (amounts.isEmpty()) continue;
                credits.add(new Recharge(player, slot, wand, wand.copy(), VisCost.ofCentivis(amounts)));
            }
        }
        if (credits.isEmpty()) return false;
        // One fee for the whole pulse, settled only when the first wand has a validated nonzero receipt.
        boolean[] paid = {false};
        var recipients = new HashSet<ServerPlayer>();
        for (var credit : credits) {
            if (credit.player().getInventory().getItem(credit.slot()) != credit.wand()
                    || !ItemStack.matches(credit.wand(), credit.snapshot())) continue;
            var storage = WandCharging.storage(credit.wand());
            if (storage == null) continue;
            if (WandCharging.insert(storage, credit.player(), credit.amount(), plan -> {
                if (target(level, terms) != node || !RitualCenters.current(level, site) || !credit.player().isAlive()
                        || credit.player().isSpectator() || credit.player().level() != level
                        || credit.player().getInventory().getItem(credit.slot()) != credit.wand()
                        || !ItemStack.matches(credit.wand(), credit.snapshot())
                        || !area.bounds().contains(credit.player().position())
                        || !area.containsHorizontal(credit.player().position())) return false;
                if (paid[0]) return true;
                var before = NodeApi.view(level, node.getBlockPos()).nodeState();
                var payable = before.baseVis().amounts().entrySet().stream().filter(e -> e.getValue() > 0).toList();
                if (payable.isEmpty()) return false;
                var chosen = payable.get(level.random.nextInt(payable.size()));
                var base = new LinkedHashMap<>(before.baseVis().amounts());
                var current = new LinkedHashMap<>(before.currentVis().amounts());
                int left = chosen.getValue() - 1;
                base.put(chosen.getKey(), left);
                current.computeIfPresent(chosen.getKey(), (key, amount) -> Math.min(amount, left));
                paid[0] = NodeApi.replaceLoadedState(level, node.getBlockPos(), before,
                        before.withVis(new NodeVis(base), new NodeVis(current)), VisAction.EXECUTE).changed();
                return paid[0];
            }).mutated())
                recipients.add(credit.player());
        }
        if (recipients.isEmpty()) return false;
        for (var player : recipients) {
            player.getInventory().setChanged();
            player.containerMenu.broadcastChanges();
            level.sendParticles(ParticleTypes.ENCHANT, player.getX(), player.getY() + 1, player.getZ(), 4, .3, .3, .3, .02);
        }
        if (level.getBlockEntity(node.getBlockPos()) == node
                && NodeApi.view(level, node.getBlockPos()).nodeState().baseVis().amounts().values().stream().noneMatch(value -> value > 0))
            level.removeBlock(node.getBlockPos(), false);
        return true;
    }
    /** Called through a per-node, one-second cache in the existing node bridge. No new world index. */
    public static int findBonus(BlockEntity node) {
        if (!(node.getLevel() instanceof ServerLevel level) || !node.getBlockState().is(ThaumcraftContent.block("aura_node"))) return 4;
        int best = 4;
        for (int dy = -RitualPlane.HEIGHT_RADIUS; dy <= RitualPlane.HEIGHT_RADIUS; dy++) {
            var pos = node.getBlockPos().offset(0, dy + 1, 0);
            if (!level.hasChunkAt(pos) || !level.shouldTickBlocksAt(pos)
                    || !(level.getBlockEntity(pos) instanceof GoldenHeartEntity heart)
                    || !RitualCenters.mayPrepare(heart) || findNode(level, pos) != node) continue;
            var plane = RitualPlane.at(level, pos.below()).orElse(null);
            if (plane == null) continue;
            int bonus = (int) STABILITY.resolve(4, plane.rings().completeMask());
            best = Math.max(best, bonus);
        }
        return best;
    }
    private record CachedBonus(long tick, int bonus) {}
    private static final Map<BlockEntity, CachedBonus> RINGS = new WeakHashMap<>();
    public static int bonus(BlockEntity node) {
        if (!(node.getLevel() instanceof ServerLevel level) || node.isRemoved()) return 4;
        long now = level.getGameTime();
        CachedBonus cached = RINGS.get(node);
        if (cached == null || now < cached.tick() || now - cached.tick() >= 20) {
            cached = new CachedBonus(now, findBonus(node));
            RINGS.put(node, cached);
        }
        return cached.bonus();
    }
    public static int bonus(ServerLevel level, BlockPos position) {
        var node = node(level, position);
        return node == null ? 4 : bonus(node);
    }
    public static int contest(ServerLevel level, BlockPos position, int nativeAmount) {
        return (int)Math.min(Integer.MAX_VALUE, (long)Math.max(0, nativeAmount) * bonus(level, position) / 4);
    }
    /** An effective contest weight only; never writes or enlarges stored node capacities. */
    public static int contest(BlockEntity node, int nativeAmount) {
        return (int) Math.min(Integer.MAX_VALUE, (long) Math.max(0, nativeAmount) * bonus(node) / 4);
    }
}
