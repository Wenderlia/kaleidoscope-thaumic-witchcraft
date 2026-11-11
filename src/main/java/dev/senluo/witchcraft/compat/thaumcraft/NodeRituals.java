package dev.senluo.witchcraft.compat.thaumcraft;

import dev.tc4port.thaumcraft.api.ThaumcraftContent;
import dev.senluo.witchcraft.ritual.*;
import dev.tc4port.thaumcraft.api.aspect.VisAction;
import dev.tc4port.thaumcraft.api.aspect.VisChannel;
import dev.tc4port.thaumcraft.api.aspect.VisCost;
import dev.tc4port.thaumcraft.api.node.NodeApi;
import dev.tc4port.thaumcraft.api.node.NodeVis;
import dev.tc4port.thaumcraft.api.wand.WandApi;
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
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.PotionItem;
import net.minecraft.world.phys.Vec3;

/** Node effect adapter. Geometry, slots, material access and persistence remain shared. */
public final class NodeRituals implements RitualCenters.Behavior {
    @Override public StructuralRitualStore.Definition definition() { return RESTORATION; }
    public static final StructuralRitualStore.Definition RESTORATION = new StructuralRitualStore.Definition(
            ResourceLocation.fromNamespaceAndPath("kaleidoscope_witchcraft", "aura_restoration"),
            1, 80, new int[]{20, 40, 60, 80}, RitualRings.SMALL, RitualResearch.thaumcraft("CRUCIBLE_STABILITY"));
    private record Recharge(ServerPlayer player, int slot, ItemStack wand, ItemStack snapshot, VisCost amount) {}
    private static final RingBonus STABILITY = RingBonus.additive(1, 2, 4, 11);
    private NodeRituals() {}
    public static void register() {
        NodeSteam.register();
        NodePromotion.register();
        RitualCenters.register(new NodeRituals());
        LandPeaceRitual.nodeOffering(new LandPeaceRitual.NodeOffering() {
            @Override public Optional<LandPeaceRitual.Node> find(ServerLevel level, RitualSite site) {
                var target = findNode(level, site);
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
    static BlockEntity findNode(ServerLevel level, RitualSite site) {
        return site.plane() == null ? null : findNodeAtAxis(level, site.plane().anchor());
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
    private static RitualOfferingArea area(RitualPlane plane) {
        return RitualOfferingArea.outermost(plane);
    }
    private static Optional<RitualMaterials.Source> offering(ServerLevel level, RitualOfferingArea area, int step) {
        return RitualMaterials.find(level, area, stack -> matches(stack, step)).stream()
                .min(Comparator.comparingDouble(source -> source.position().distanceToSqr(area.bounds().getCenter())));
    }
    private static List<StructuralRitualStore.Handle> sessions(ServerLevel level, RitualSite site) {
        return GoldenRitualSlots.sessions(level, site.plane()).stream()
                .filter(h -> h.definition().kind().equals(RESTORATION.kind())).toList();
    }
    @Override public InteractionResult activate(ServerLevel level, RitualSite site, net.minecraft.server.level.ServerPlayer player) {
        var center = site.center();
        if (site.plane() == null) return InteractionResult.PASS;
        for (int step = 0; step < 3; step++) if (offering(level, area(site.plane()), step).isEmpty()) return InteractionResult.PASS;
        if (!center.mayPrepare()) return InteractionResult.FAIL;
        if (RitualResearch.check(level, player.getUUID(), RESTORATION.research()) != RitualResearch.Result.ALLOWED) return InteractionResult.FAIL;
        RitualPlane.invalidate(level, center.axis());
        var plane = site.plane();
        var node = findNode(level, site);
        if (plane == null || node == null || NodeApi.view(level, node.getBlockPos()).nodeState().instanceId().isEmpty()) return InteractionResult.FAIL;
        var store = StructuralRitualStore.get(level);
        if (sessions(level, site).stream().anyMatch(h -> store.progress(h.id())
                .filter(p -> p.phase() == StructuralRitualStore.Phase.PREPARING).isPresent())) return InteractionResult.CONSUME;
        UUID prospective = UUID.randomUUID();
        if (!GoldenRitualSlots.allocate(level, plane, prospective, RitualRings.SMALL).containsKey(prospective)) return InteractionResult.FAIL;
        var area = area(plane);
        for (int step = 0; step < 3; step++) if (offering(level, area, step).isEmpty()) return InteractionResult.FAIL;
        var terms = new CompoundTag();
        terms.putLong("Plane", plane.anchor().asLong());
        terms.putUUID("Node", NodeApi.view(level, node.getBlockPos()).nodeState().instanceId().orElseThrow());
        terms.putLong("NodePosition", node.getBlockPos().asLong());
        if (store.begin(level, RESTORATION, center, player.getUUID(), terms).isEmpty()) return InteractionResult.FAIL;
        
        return InteractionResult.CONSUME;
    }
    @Override public void tick(ServerLevel level, RitualSite site, StructuralRitualStore.Handle h) {
        var store = StructuralRitualStore.get(level);
        var center = site.center();
        var plane = site.plane();
        var assigned = plane == null ? Map.<UUID, Integer>of() : GoldenRitualSlots.allocate(level, plane, null, RitualRings.SMALL);
        var progress = store.progress(h.id()).orElseThrow();
        // Unknown future terms are preserved, not activated or silently reset.
        if (!termsValid(progress.terms())) return;
        BlockPos nodePos = BlockPos.of(progress.terms().getLong("NodePosition"));
        if (!level.hasChunkAt(nodePos) || !level.shouldTickBlocksAt(nodePos)) return;
        if (target(level, progress.terms()) == null) { store.end(level, h.id()); store.finish(h.id()); return; }
        if (progress.phase() == StructuralRitualStore.Phase.ENDED) { store.finish(h.id()); return; }
        boolean ready = assigned.containsKey(h.id()) && plane.containsCenter(nodePos);
        boolean firstAttempt = progress.phase() == StructuralRitualStore.Phase.PREPARING
                && progress.committedSteps() < RESTORATION.steps().length
                && progress.elapsed() < RESTORATION.steps()[progress.committedSteps()];
        var result = store.advance(level, h.id(), RESTORATION, StructuralRitualStore.Presence.PRESENT,
                center.reference(), ready ? RitualShape.Match.VALID : RitualShape.Match.INVALID,
                (token, terms) -> prepare(level, site, token.index(), terms, firstAttempt));
        if (result.mayRun() && level.getGameTime() % 20 == 0 && plane != null && recharge(level, site, plane, progress.terms()))
            RitualSounds.infusionPulse(level, site.position());
    }
    private static Optional<StructuralRitualStore.PreparedStep> prepare(ServerLevel level, RitualSite site,
                                                                       int step, CompoundTag terms, boolean firstAttempt) {
        // Once a milestone is reached, missing materials do not cause twenty inventory scans per second.
        if (!firstAttempt && level.getGameTime() % 20 != 0 || target(level, terms) == null) return Optional.empty();
        var plane = site.plane();
        if (plane == null) return Optional.empty();
        var source = step < 3 ? offering(level, area(plane), step).orElse(null) : null;
        if (step < 3 && source == null) return Optional.empty();
        return Optional.of(new StructuralRitualStore.PreparedStep(terms, () -> {
            if (!RitualCenters.current(level, site) || target(level, terms) == null) return false;
            if (step == 3) {
                level.playSound(null, site.position(), SoundEvents.BEACON_ACTIVATE, SoundSource.BLOCKS, .5F, 1.2F);
                return true;
            }
            var fresh = site.plane();
            if (fresh == null || !RitualMaterials.inArea(source, area(fresh)) || !matches(source.item(), step)) return false;
            var removed = source.takeOne();
            if (removed.isEmpty()) return false;
            Vec3 origin = source.position();
            OfferingSounds.absorb(level, origin, removed);
            // Both ordinary and thrown bottles leave one reusable empty vessel by the actual offering.
            if (step == 0) level.addFreshEntity(new ItemEntity(level, origin.x, origin.y + .1, origin.z, new ItemStack(Items.GLASS_BOTTLE)));
            Vec3 end = Vec3.atCenterOf(site.position());
            for (int i = 0; i < 8; i++) {
                Vec3 p = origin.lerp(end, i / 7.0);
                level.sendParticles(ParticleTypes.ENCHANT, p.x, p.y + .1, p.z, 1, .05, .05, .05, .01);
            }
            return true;
        }));
    }
    private static boolean recharge(ServerLevel level, RitualSite site, RitualPlane plane, CompoundTag terms) {
        var node = target(level, terms);
        if (node == null) return false;
        if (NodeApi.view(level, node.getBlockPos()).nodeState().baseVis().amounts().values().stream().noneMatch(value -> value > 0)) {
            level.removeBlock(node.getBlockPos(), false); return false;
        }
        var area = area(plane);
        var credits = new ArrayList<Recharge>();
        for (var player : level.players()) {
            if (!player.isAlive() || player.isSpectator() || !area.bounds().contains(player.position())
                    || !area.containsHorizontal(player.position())) continue;
            var inventory = player.getInventory();
            for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
                var wand = inventory.getItem(slot);
                var view = WandApi.view(wand).orElse(null);
                if (view == null) continue;
                var amounts = new EnumMap<VisChannel, Integer>(VisChannel.class);
                for (VisChannel channel : VisChannel.values()) {
                    int capacity = view.capacityCentivis().getOrDefault(channel, 0);
                    int amount = Math.min(capacity / 10, Math.max(0, capacity - view.visCentivis().getOrDefault(channel, 0)));
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
            if (WandApi.insert(credit.player(), credit.wand(), credit.amount(), VisAction.EXECUTE, plan -> {
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
                    || !heart.center(level).mayPrepare() || findNode(level, pos) != node) continue;
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
