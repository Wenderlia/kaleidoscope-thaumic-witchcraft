package dev.senluo.witchcraft.gametest;

import dev.senluo.witchcraft.ritual.*;
import dev.senluo.witchcraft.time.*;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.*;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.*;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Public activation and native callback contracts; fixtures live only in the independent test world. */
@PrefixGameTestTemplate(false)
public final class RitualRecipeGameTests {
    private static ServerPlayer player(GameTestHelper h, BlockPos pos, String... research) {
        var cookie = net.minecraft.server.network.CommonListenerCookie.createInitial(
                new com.mojang.authlib.GameProfile(UUID.randomUUID(), "recipe-test"), false);
        var player = new ServerPlayer(h.getLevel().getServer(), h.getLevel(), cookie.gameProfile(), cookie.clientInformation());
        var connection = new net.minecraft.network.Connection(net.minecraft.network.protocol.PacketFlow.SERVERBOUND);
        new io.netty.channel.embedded.EmbeddedChannel(connection);
        // Native GameTest helper omits NeoForge channel negotiation; use the platform's mock contract.
        net.neoforged.neoforge.network.registration.NetworkRegistry.configureMockConnection(connection);
        h.getLevel().getServer().getPlayerList().placeNewPlayer(connection, player, cookie);
        player.setPos(Vec3.atCenterOf(pos).add(1, 1, 0));
        var knowledge = dev.tc4port.thaumcraft.data.PlayerKnowledgeData.initial(h.getLevel().random);
        for (var key : research) knowledge = knowledge.completeResearch(
                dev.tc4port.thaumcraft.api.research.ResearchKey.parse("kaleidoscope_witchcraft:" + key));
        player.setData(dev.tc4port.thaumcraft.state.TCObjectState.PLAYER_KNOWLEDGE.native$type, knowledge);
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(RitualContent.TAGLOCK.get()));
        return player;
    }
    private static BlockPos plane(GameTestHelper h, RitualShape... shapes) {
        var pos = h.absolutePos(new BlockPos(8, 3, 8));
        RitualFrameworkGameTests.circle(h, pos, shapes);
        h.getLevel().setBlock(pos.above(), Blocks.BARREL.defaultBlockState(), 3);
        return pos;
    }
    private static ItemEntity drop(GameTestHelper h, BlockPos at, ItemStack stack) {
        var item = new ItemEntity(h.getLevel(), at.getX() + .5, at.getY() + 1.5, at.getZ() + .5, stack);
        item.setNoGravity(true); item.setDeltaMovement(Vec3.ZERO); item.setUnlimitedLifetime();
        h.getLevel().addFreshEntity(item); return item;
    }
    private static void close(GameTestHelper h, ServerPlayer player, BlockPos core) {
        var store = StructuralRitualStore.get(h.getLevel());
        RitualCenters.peek(h.getLevel().getBlockEntity(core)).ifPresent(center -> {
            for (var handle : store.allAt(center.reference())) RitualCenters.end(h.getLevel(), handle.id());
        });
        player.server.getPlayerList().remove(player);
    }
    private static StructuralRitualStore.Definition definition(String name, int cost, int ingredients) {
        int[] steps = java.util.stream.IntStream.rangeClosed(1, ingredients).map(i -> i * 20).toArray();
        return new StructuralRitualStore.Definition(ResourceLocation.fromNamespaceAndPath("kaleidoscope_witchcraft", name),
                1, ingredients * 20, steps, cost, Optional.empty());
    }
    private static InteractionResult activate(GameTestHelper h, ServerPlayer player, BlockPos core) {
        return RitualContent.TAGLOCK.get().onItemUseFirst(player.getMainHandItem(), new UseOnContext(player,
                InteractionHand.MAIN_HAND, new BlockHitResult(Vec3.atCenterOf(core), Direction.UP, core, false)));
    }

    @GameTest(templateNamespace="kaleidoscope_witchcraft", template="empty")
    public static void offlineResearchReadsExternalGrantAndRevocationInSameTick(GameTestHelper h) {
        var id = UUID.randomUUID(); // No player entity or playerdata file in this world.
        var need = RitualResearch.thaumcraft("WITCHERY_CIRCLE_BASICS");
        var key = dev.tc4port.thaumcraft.api.research.ResearchKey.parse(need.orElseThrow().key());
        var bus = net.neoforged.neoforge.common.NeoForge.EVENT_BUS;
        boolean[] grant = {true};
        java.util.function.Consumer<dev.tc4port.thaumcraft.api.event.ResearchDataEvent.Read> listener = event -> {
            if (event.playerId().equals(id)) event.setData(grant[0] ? event.data().withCompleted(key) : event.data().withoutCompleted(key));
        };
        bus.addListener(dev.tc4port.thaumcraft.api.event.ResearchDataEvent.Read.class, listener);
        try {
            h.assertTrue(RitualResearch.check(h.getLevel(), id, need) == RitualResearch.Result.ALLOWED,
                    "An externally backed UUID needs no local player save");
            grant[0] = false;
            h.assertTrue(RitualResearch.check(h.getLevel(), id, need) != RitualResearch.Result.ALLOWED,
                    "A second decision in the same tick observes the external revocation");
            h.succeed();
        } finally {
            bus.unregister(listener);
        }
    }

    @GameTest(templateNamespace="kaleidoscope_witchcraft", template="empty", timeoutTicks=20)
    public static void unmatchedMediumDoesNotCreateCenterIdentity(GameTestHelper h) {
        var plane = plane(h, RitualShape.SMALL); var core = plane.above();
        var player = player(h, core);
        try {
            h.assertTrue(activate(h, player, core) == InteractionResult.PASS, "No recipe leaves vanilla interaction available");
            h.assertTrue(!h.getLevel().getBlockEntity(core).hasData(RitualContent.RITUAL_ANCHOR),
                    "A failed search must not attach a permanent identity to an ordinary machine");
        } finally { close(h, player, core); }
        h.succeed();
    }

    @GameTest(templateNamespace="kaleidoscope_witchcraft", template="empty", timeoutTicks=20)
    public static void pointAdmissionPrecedesIdentityAndAcceptsNewMachines(GameTestHelper h) {
        var plane = plane(h, RitualShape.SMALL); var entity = h.getLevel().getBlockEntity(plane.above());
        var store = StructuralRitualStore.get(h.getLevel()); var definition = definition("recipe_point_fixture", 3, 1);
        var candidate = RitualCenters.candidate(entity).orElseThrow();
        h.assertTrue(store.begin(h.getLevel(), definition, candidate, UUID.randomUUID(), new CompoundTag()).isEmpty()
                        && !entity.hasData(RitualContent.RITUAL_ANCHOR), "Insufficient points create no persistent center identity");
        RitualFrameworkGameTests.circle(h, plane, RitualShape.MEDIUM);
        var id = store.begin(h.getLevel(), definition, RitualCenters.candidate(entity).orElseThrow(), UUID.randomUUID(), new CompoundTag()).orElseThrow();
        h.assertTrue(entity.hasData(RitualContent.RITUAL_ANCHOR) && store.eligible(h.getLevel(), id),
                "An unregistered barrel joins after a small and medium ring jointly provide three points");
        RitualCenters.end(h.getLevel(), id); h.succeed();
    }

    @GameTest(templateNamespace="kaleidoscope_witchcraft", template="empty", timeoutTicks=20)
    public static void preparingLandPeaceDoesNotConsumeAnotherRecipe(GameTestHelper h) {
        var plane = plane(h, RitualShape.SMALL, RitualShape.MEDIUM, RitualShape.LARGE); var core = plane.above();
        var player = player(h, core, "CRUCIBLE_STABILITY", "WITCHERY_CIRCLE_BASICS");
        var offerings = new ArrayList<ItemEntity>();
        try {
            for (var item : List.of(Items.CRYING_OBSIDIAN, Items.TNT, Items.COBWEB, Items.IRON_SHOVEL,
                    Items.WITHER_SKELETON_SKULL, Items.APPLE, Items.GOLD_INGOT)) offerings.add(drop(h, plane.east(2), new ItemStack(item)));
            h.assertTrue(activate(h, player, core).consumesAction(), "The real land-peace recipe starts through the medium");
            var store = StructuralRitualStore.get(h.getLevel()); var ref = RitualCenters.peek(h.getLevel().getBlockEntity(core)).orElseThrow().reference();
            h.assertTrue(store.allAt(ref).stream().anyMatch(handle -> handle.definition().kind().equals(LandPeaceRitual.DEFINITION.kind())),
                    "Land peace is preparing before the second click");
            offerings.forEach(ItemEntity::discard); offerings.clear();
            offerings.add(drop(h, plane.east(2), FruitItem.stack(5)));
            offerings.add(drop(h, plane.east(2), new ItemStack(TemporalContent.LOG_ITEM.get())));
            offerings.add(drop(h, plane.east(2), new ItemStack(Items.GLASS_BOTTLE)));
            h.assertTrue(activate(h, player, core).consumesAction() && store.allAt(ref).size() == 2,
                    "Preparing land peace cannot swallow another matched recipe's click");
        } finally { offerings.forEach(ItemEntity::discard); close(h, player, core); }
        h.succeed();
    }

    @GameTest(templateNamespace="kaleidoscope_witchcraft", template="empty", timeoutTicks=20)
    public static void recipeSelectsLargeScaleFromSharedPoints(GameTestHelper h) {
        var plane = plane(h, RitualShape.SMALL, RitualShape.MEDIUM); var core = plane.above();
        var player = player(h, core, "WITCHERY_CIRCLE_BASICS");
        var offerings = List.of(drop(h, plane.east(2), FruitItem.stack(5).copyWithCount(2)),
                drop(h, plane.east(2), new ItemStack(TemporalContent.LOG_ITEM.get(), 2)),
                drop(h, plane.east(2), new ItemStack(Items.GLASS_BOTTLE, 2)));
        try {
            h.assertTrue(activate(h, player, core).consumesAction(), "Two sets activate without a physical large ring");
            var store = StructuralRitualStore.get(h.getLevel());
            var handle = store.allAt(RitualCenters.peek(h.getLevel().getBlockEntity(core)).orElseThrow().reference()).getFirst();
            var scale = store.progress(handle.id()).orElseThrow().terms().getCompound("OfferingScale");
            h.assertTrue(handle.definition().pointCost() == 3 && scale.getInt("Copies") == 2
                    && scale.getCompound("Parameters").getInt("ExtraWork") == 5, "The persisted large quote is total speed six, at three shared points");
            h.assertTrue(offerings.stream().allMatch(item -> item.getItem().getCount() == 2), "Matching never spends real materials");
        } finally { offerings.forEach(ItemEntity::discard); close(h, player, core); }
        h.succeed();
    }

    @GameTest(templateNamespace="kaleidoscope_witchcraft", template="empty", timeoutTicks=20)
    public static void recipeMatchingDoesNotDoubleCountOverlappingIngredients(GameTestHelper h) {
        var plane = plane(h, RitualShape.SMALL); var core = plane.above(); var player = player(h, core);
        var diamond = drop(h, plane.east(2), new ItemStack(Items.DIAMOND));
        ItemEntity coal = null;
        var recipe = new RitualRecipe(definition("recipe_overlap_fixture", 1, 2),
                List.of(stack -> stack.is(Items.DIAMOND) || stack.is(Items.COAL), stack -> stack.is(Items.DIAMOND)));
        var candidate = RitualCenters.candidate(h.getLevel().getBlockEntity(core)).orElseThrow();
        try {
            h.assertTrue(recipe.activate(player, List.of(new RitualRecipe.Input(candidate)), (site, owner) -> Optional.of(new CompoundTag())) == InteractionResult.PASS,
                    "One diamond is not two ingredient units");
            coal = drop(h, plane.east(2), new ItemStack(Items.COAL));
            h.assertTrue(recipe.activate(player, List.of(new RitualRecipe.Input(candidate)), (site, owner) -> Optional.of(new CompoundTag())).consumesAction(),
                    "Matching reroutes the broad ingredient to coal and leaves the diamond for the narrow ingredient");
        } finally { diamond.discard(); if (coal != null) coal.discard(); close(h, player, core); }
        h.succeed();
    }

    @GameTest(templateNamespace="kaleidoscope_witchcraft", template="empty", timeoutTicks=20)
    public static void recipeRechecksMovedSourcesBeforeAdmission(GameTestHelper h) {
        var plane = plane(h, RitualShape.SMALL); var core = plane.above(); var player = player(h, core);
        var offering = drop(h, plane.east(2), new ItemStack(Items.DIAMOND));
        var recipe = new RitualRecipe(definition("recipe_move_fixture", 1, 1), List.of(stack -> stack.is(Items.DIAMOND)));
        try {
            var result = recipe.activate(player, List.of(new RitualRecipe.Input(RitualCenters.candidate(h.getLevel().getBlockEntity(core)).orElseThrow())),
                    (site, owner) -> { offering.setPos(Vec3.atCenterOf(plane.east(30))); return Optional.of(new CompoundTag()); });
            h.assertTrue(result == InteractionResult.FAIL && !h.getLevel().getBlockEntity(core).hasData(RitualContent.RITUAL_ANCHOR)
                    && offering.getItem().getCount() == 1, "A moved source cannot authorize a new identity or lose its item");
        } finally { offering.discard(); close(h, player, core); }
        h.succeed();
    }

    @GameTest(templateNamespace="kaleidoscope_witchcraft", template="empty", timeoutTicks=20)
    public static void ownershipSpawnCallbackCannotOverwriteAnotherSession(GameTestHelper h) {
        var tree = TreeGameTests.plant(h, true); var plane = tree.getBlockPos().below();
        RitualFrameworkGameTests.circle(h, plane, RitualShape.SMALL);
        var subject = player(h, tree.getBlockPos(), "TIMEWOOD_OWNERSHIP");
        var other = player(h, tree.getBlockPos());
        var sample = new TaglockSample(1, subject.getUUID(), ResourceLocation.withDefaultNamespace("player"), "owner", true);
        subject.getMainHandItem().set(RitualContent.TAGLOCK_SAMPLE.get(), sample);
        other.getMainHandItem().set(RitualContent.TAGLOCK_SAMPLE.get(), sample);
        var originals = List.of(drop(h, plane.east(2), new ItemStack(Items.STRING, 2)),
                drop(h, plane.east(2), new ItemStack(Items.GOLD_INGOT, 2)),
                drop(h, plane.east(2), new ItemStack(Items.AMETHYST_SHARD, 2)));
        var entered = new java.util.concurrent.atomic.AtomicBoolean();
        java.util.function.Consumer<net.neoforged.neoforge.event.entity.EntityJoinLevelEvent> callback = event -> {
            if (event.getLevel() == h.getLevel() && OwnershipOfferings.held(event.getEntity()) && entered.compareAndSet(false, true))
                OwnershipRitual.start(other, InteractionHand.MAIN_HAND, tree);
        };
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(callback);
        try {
            OwnershipRitual.start(subject, InteractionHand.MAIN_HAND, tree);
            h.assertTrue(entered.get() && OwnershipRitual.occupied(subject), "The callback establishes a real competing preparation");
            h.assertTrue(!subject.getMainHandItem().has(DataComponents.CUSTOM_DATA)
                    && other.getMainHandItem().has(DataComponents.CUSTOM_DATA), "The inner session keeps its vial; outer admission is rejected");
            RitualContent.TAGLOCK.get().inventoryTick(other.getMainHandItem(), h.getLevel(), other, 0, true);
            h.assertTrue(other.getMainHandItem().has(DataComponents.CUSTOM_DATA), "The surviving token still resolves to its actual session");
        } finally {
            net.neoforged.neoforge.common.NeoForge.EVENT_BUS.unregister(callback);
            OwnershipRitual.coreUnavailable(tree); originals.forEach(ItemEntity::discard);
            h.getLevel().getEntitiesOfClass(ItemEntity.class, new AABB(tree.getBlockPos()).inflate(5)).forEach(ItemEntity::discard);
            close(h, subject, tree.getBlockPos()); close(h, other, tree.getBlockPos());
        }
        h.succeed();
    }
    private static RitualRecipe configured(StructuralRitualStore.Definition definition) {
        return new RitualRecipe(definition, List.of(stack -> stack.is(Items.POTION)), false,
                new RitualRecipe.Payment(RitualRecipe.SourceOrder.NEAREST, true,
                        Map.of(0, Items.GLASS_BOTTLE), .1, .05, null));
    }
    @GameTest(templateNamespace="kaleidoscope_witchcraft", template="empty", timeoutTicks=40)
    public static void configuredPaymentSelectsNearestAndReturnsOnce(GameTestHelper h) {
        var level = h.getLevel(); var plane = plane(h, RitualShape.SMALL); var store = StructuralRitualStore.get(level);
        var def = new StructuralRitualStore.Definition(ResourceLocation.parse("kaleidoscope_witchcraft:payment_fixture"),
                1, 1, new int[]{1}, 1, Optional.empty());
        var recipe = configured(def);
        var far = drop(h, plane.east(2), new ItemStack(Items.POTION, 2));
        var near = drop(h, plane.east(), new ItemStack(Items.POTION, 2));
        var id = store.begin(level, def, RitualCenters.candidate(level.getBlockEntity(plane.above())).orElseThrow(),
                UUID.randomUUID(), new CompoundTag()).orElseThrow();
        try {
            var site = store.site(level, id);
            var pay = recipe.payments(level, site, def, store.progress(id).orElseThrow(), () -> true, () -> RitualRecipe.Commit.APPLIED);
            store.advance(level, id, def, StructuralRitualStore.Presence.PRESENT, RitualShape.Match.VALID, pay);
            h.assertTrue(near.getItem().getCount() == 1 && far.getItem().getCount() == 2,
                    "Nearest config chooses the nearer source even though the farther one was discovered first");
            store.advance(level, id, def, StructuralRitualStore.Presence.PRESENT, RitualShape.Match.VALID, pay);
            int bottles = level.getEntitiesOfClass(ItemEntity.class, new AABB(plane).inflate(5), e -> e.getItem().is(Items.GLASS_BOTTLE))
                    .stream().mapToInt(e -> e.getItem().getCount()).sum();
            h.assertTrue(bottles == 1 && store.progress(id).orElseThrow().committedSteps() == 1,
                    "A committed milestone cannot pay or return its configured vessel twice");
        } finally { far.discard(); near.discard(); RitualCenters.end(level, id); }
        h.succeed();
    }
    @GameTest(templateNamespace="kaleidoscope_witchcraft", template="empty", timeoutTicks=40)
    public static void configuredPaymentRejectsMovedSourceAndThrottlesRetry(GameTestHelper h) {
        var level = h.getLevel(); var plane = plane(h, RitualShape.SMALL); var store = StructuralRitualStore.get(level);
        var def = new StructuralRitualStore.Definition(ResourceLocation.parse("kaleidoscope_witchcraft:payment_move_fixture"),
                1, 1, new int[]{1}, 1, Optional.empty());
        var recipe = configured(def); var item = drop(h, plane.east(), new ItemStack(Items.POTION)); item.setPickUpDelay(32767);
        var id = store.begin(level, def, RitualCenters.candidate(level.getBlockEntity(plane.above())).orElseThrow(),
                UUID.randomUUID(), new CompoundTag()).orElseThrow();
        var site = store.site(level, id);
        var pay = recipe.payments(level, site, def, store.progress(id).orElseThrow(), () -> true, () -> RitualRecipe.Commit.APPLIED);
        store.advance(level, id, def, StructuralRitualStore.Presence.PRESENT, RitualShape.Match.VALID, (step, terms) -> {
            var prepared = pay.prepare(step, terms); item.setPos(Vec3.atCenterOf(plane.east(30))); return prepared;
        });
        h.assertTrue(item.getItem().getCount() == 1 && store.progress(id).orElseThrow().committedSteps() == 0,
                "Movement between quote and debit does not consume or commit the offering");
        item.setPos(Vec3.atCenterOf(plane.east()).add(0, 1, 0));
        int delay = level.getGameTime() % 20 == 19 ? 2 : 1;
        h.runAfterDelay(delay, () -> {
            try {
                h.assertTrue(level.getGameTime() % 20 != 0, "Exercise a non-retry tick");
                var retry = recipe.payments(level, site, def, store.progress(id).orElseThrow(), () -> true, () -> RitualRecipe.Commit.APPLIED);
                h.assertTrue(retry.prepare(new StructuralRitualStore.StepToken(id, 0), new CompoundTag()).isEmpty(),
                        "Immediate first attempts do not turn missing-material retries into per-tick discovery");
            } finally { item.discard(); RitualCenters.end(level, id); }
            h.succeed();
        });
    }
    @GameTest(templateNamespace="kaleidoscope_witchcraft", template="empty", timeoutTicks=260)
    public static void landPeacePublicationRepairsReceiptWithoutRepayment(GameTestHelper h) {
        var level = h.getLevel(); var plane = plane(h, RitualShape.MEDIUM); var core = plane.above();
        var player = player(h, core, "CRUCIBLE_STABILITY"); var offerings = new ArrayList<ItemEntity>();
        for (var item : List.of(Items.CRYING_OBSIDIAN, Items.TNT, Items.COBWEB, Items.IRON_SHOVEL,
                Items.WITHER_SKELETON_SKULL, Items.APPLE, Items.GOLD_INGOT)) {
            var offering = drop(h, plane.east(2), new ItemStack(item)); offering.setPickUpDelay(32767); offerings.add(offering);
        }
        h.assertTrue(activate(h, player, core).consumesAction(), "Start the real land-peace recipe");
        var store = StructuralRitualStore.get(level);
        var id = store.allAt(RitualCenters.peek(level.getBlockEntity(core)).orElseThrow().reference()).getFirst().id();
        h.runAfterDelay(210, () -> {
            var regions = dev.senluo.witchcraft.region.RegionRules.store(level);
            try {
                h.assertTrue(store.phase(id) == StructuralRitualStore.Phase.ACTIVE && regions.contains(id), "Real timed payments install the region");
                h.assertTrue(offerings.stream().allMatch(ItemEntity::isRemoved), "All ordinary offerings were actually paid");
                var progress = store.progress(id).orElseThrow(); var receipt = regions.entry(id).orElseThrow();
                long version = regions.receiptVersion();
                h.runAfterDelay(2, () -> {
                    try {
                        h.assertTrue(store.progress(id).orElseThrow() == progress && regions.receiptVersion() == version,
                                "An unchanged active effect reuses its publication instead of advancing or rewriting it");
                        regions.remove(id);
                        h.runAfterDelay(2, () -> {
                            try {
                                h.assertTrue(regions.entry(id).filter(receipt::equals).isPresent()
                                                && store.progress(id).orElseThrow().committedSteps() == 8,
                                        "Receipt removal wakes repair without reconstructing material payments");
                                level.removeBlock(plane.offset(RitualShape.MEDIUM.marks().getFirst()), false);
                                h.assertTrue(!dev.senluo.witchcraft.region.RegionRules.applies(level, core,
                                                dev.senluo.witchcraft.region.RegionRules.NO_EXPLOSION_BLOCK_CHANGES),
                                        "Static publication still follows live ritual permission");
                                h.succeed();
                            } finally { offerings.forEach(ItemEntity::discard); close(h, player, core); }
                        });
                    } catch (RuntimeException | Error failure) { offerings.forEach(ItemEntity::discard); close(h, player, core); throw failure; }
                });
            } catch (RuntimeException | Error failure) { offerings.forEach(ItemEntity::discard); close(h, player, core); throw failure; }
        });
    }

    private static int inputUnits() {
        return dev.tc4port.thaumcraft.api.essentia.EssentiaContainerApi.capacity(
                new ItemStack(dev.tc4port.thaumcraft.api.ThaumcraftContent.item("essence_phial")));
    }
    private static ItemStack inputBag(GameTestHelper h) {
        var bag = new ItemStack(dev.senluo.witchcraft.reservoir.ReservoirContent.BACKPACK.get());
        var phial = new ItemStack(dev.tc4port.thaumcraft.api.ThaumcraftContent.item("essence_phial"));
        h.assertTrue(dev.tc4port.thaumcraft.api.essentia.EssentiaContainerApi.insert(h.getLevel(), phial,
                        dev.tc4port.thaumcraft.api.aspect.AspectId.parse("aer"), inputUnits(),
                        dev.tc4port.thaumcraft.api.essentia.EssentiaTransferMode.EXECUTE) == inputUnits(), "Use a real whole native phial");
        dev.senluo.witchcraft.reservoir.ReservoirData.slot(bag, 0, phial.copyWithCount(2), h.getLevel().registryAccess());
        return bag;
    }
    private static void inputPre(ServerPlayer player) {
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.post(new net.neoforged.neoforge.event.tick.PlayerTickEvent.Pre(player));
    }
    private static void inputPost(ServerPlayer player) {
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.post(new net.neoforged.neoforge.event.tick.PlayerTickEvent.Post(player));
    }
    @GameTest(templateNamespace="kaleidoscope_witchcraft", template="empty", timeoutTicks=20)
    public static void backpackInputUsesNativeCandidatesOnce(GameTestHelper h) {
        var pos = h.absolutePos(new BlockPos(3, 3, 3)); var player = player(h, pos); var bag = inputBag(h);
        try {
            player.tickCount = 5;
            player.getInventory().setItem(0, bag);
            player.setItemSlot(net.minecraft.world.entity.EquipmentSlot.CHEST, bag);
            inputPre(player); player.getInventory().tick();
            h.assertTrue(dev.senluo.witchcraft.reservoir.ReservoirData.amount(bag, 0) == 0,
                    "Native discovery does not move input before the post-player stage");
            inputPost(player);
            h.assertTrue(dev.senluo.witchcraft.reservoir.ReservoirData.amount(bag, 0) == inputUnits()
                            && dev.senluo.witchcraft.reservoir.ReservoirData.slot(bag, 0, h.getLevel().registryAccess()).getCount() == 1,
                    "Inventory aliases and current worn lookup settle the same bag only once");
        } finally { close(h, player, pos); }
        h.succeed();
    }
    @GameTest(templateNamespace="kaleidoscope_witchcraft", template="empty", timeoutTicks=20)
    public static void backpackInputDropsStaleSlotCandidates(GameTestHelper h) {
        var pos = h.absolutePos(new BlockPos(3, 3, 3)); var player = player(h, pos);
        var old = inputBag(h); var replacement = inputBag(h);
        try {
            player.tickCount = 5; player.getInventory().setItem(0, old);
            inputPre(player); player.getInventory().tick(); player.getInventory().setItem(0, replacement); inputPost(player);
            h.assertTrue(dev.senluo.witchcraft.reservoir.ReservoirData.amount(old, 0) == 0
                            && dev.senluo.witchcraft.reservoir.ReservoirData.amount(replacement, 0) == 0,
                    "A swapped slot neither processes the removed bag nor mistakes the new one for its quote");
            player.tickCount = 10; inputPre(player); player.getInventory().tick(); inputPost(player);
            h.assertTrue(dev.senluo.witchcraft.reservoir.ReservoirData.amount(old, 0) == 0
                            && dev.senluo.witchcraft.reservoir.ReservoirData.amount(replacement, 0) == inputUnits(),
                    "The next native discovery processes the replacement without retaining the old stack");
        } finally { close(h, player, pos); }
        h.succeed();
    }

}
