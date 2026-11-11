package dev.senluo.witchcraft.gametest;

import dev.senluo.witchcraft.ritual.*;
import dev.tc4port.thaumcraft.api.aspect.AspectId;
import dev.tc4port.thaumcraft.block.entity.CrucibleBlockEntity;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Native input and timed payments, including changes to the offering area while a rite is preparing. */
@PrefixGameTestTemplate(false)
public final class RitualOfferingGameTests {
    private static final AspectId V = AspectId.parse("vacuos"), O = AspectId.parse("ordo");

    @GameTest(templateNamespace="kaleidoscope_witchcraft", template="empty", timeoutTicks=150, batch="offering-pedestals")
    public static void elevatedPlaneUsesPedestalsAndWaitsForRealStock(GameTestHelper h) {
        var pot = pot(h); var level = h.getLevel(); var pos = pot.getBlockPos(); var anchor = pos.above(7);
        for (var shape : List.of(RitualShape.SMALL, RitualShape.MEDIUM, RitualShape.LARGE)) {
            for (var offset : shape.marks()) {
                var old = pos.below().offset(offset); level.removeBlock(old.above(), false); level.removeBlock(old, false);
            }
            ring(level, anchor, shape);
        }
        var coal = pedestal(level, anchor.east(6).above(6), "minecraft:coal", 2);
        var wood = pedestal(level, anchor.west(6).below(8), "thaumcraft:silverwood_log", 2);
        var mercury = pedestal(level, anchor.south(6).above(), "thaumcraft:quicksilver", 2);
        var id = prepare(h, pot); var store = StructuralRitualStore.get(level);
        pot.addAspects(Map.of(V, 4, O, 4));
        var trigger = drop(level, pos, "thaumcraft:nitor", 2); pot.attemptSmelt(trigger);
        h.assertTrue(store.progress(id).orElseThrow().terms().getBoolean("Armed") && coal.item().getCount() == 2,
                "A seven-block elevated ring supplies upper/lower boundary pedestal offerings without consuming them during discovery");
        h.runAtTickTime(25, () -> {
            h.assertTrue(coal.item().getCount() == 1 && wood.item().getCount() == 2, "The first payment removes one real pedestal item");
            wood.clearContent();
        });
        h.runAtTickTime(50, () -> {
            h.assertTrue(store.progress(id).orElseThrow().committedSteps() == 1,
                    "A missing pedestal ingredient waits; a previously observed stack is not a payment");
            wood.setFromInfusion(new ItemStack(BuiltInRegistries.ITEM.get(ResourceLocation.parse("thaumcraft:silverwood_log")), 2));
        });
        h.runAtTickTime(115, () -> {
            h.assertTrue(store.progress(id).orElseThrow().phase() == StructuralRitualStore.Phase.ACTIVE
                    && coal.item().getCount() == 1 && wood.item().getCount() == 1 && mercury.item().getCount() == 2
                    && trigger.getItem().getCount() == 1,
                    "Resuming spends each consumable once and leaves the reusable catalyst in its pedestal");
            cleanup(level, pos, trigger); h.succeed();
        });
    }

    private static dev.tc4port.thaumcraft.block.entity.ArcanePedestalBlockEntity pedestal(
            ServerLevel level, BlockPos pos, String item, int count) {
        level.setBlock(pos.below(), Blocks.STONE.defaultBlockState(), 3);
        level.setBlock(pos, BuiltInRegistries.BLOCK.get(ResourceLocation.parse("thaumcraft:arcane_pedestal")).defaultBlockState(), 3);
        var result = (dev.tc4port.thaumcraft.block.entity.ArcanePedestalBlockEntity) level.getBlockEntity(pos);
        result.setFromInfusion(new ItemStack(BuiltInRegistries.ITEM.get(ResourceLocation.parse(item)), count));
        return result;
    }

    private static void ring(ServerLevel level, BlockPos anchor, RitualShape shape) {
        for (var offset : shape.marks()) {
            var at = anchor.offset(offset);
            level.setBlock(at, Blocks.STONE.defaultBlockState(), 3);
            level.setBlock(at.above(), RitualContent.CHALK_SURFACE.get().defaultBlockState(), 3);
        }
    }
    private static CrucibleBlockEntity pot(GameTestHelper h) {
        var level = h.getLevel(); var pos = h.absolutePos(new BlockPos(8, 3, 8));
        level.setBlock(pos.below(), Blocks.MAGMA_BLOCK.defaultBlockState(), 3);
        level.setBlock(pos, BuiltInRegistries.BLOCK.get(ResourceLocation.parse("thaumcraft:crucible")).defaultBlockState(), 3);
        var pot = (CrucibleBlockEntity)level.getBlockEntity(pos);
        pot.fillWater(1000);
        var saved = pot.saveWithoutMetadata(level.registryAccess()); saved.putShort("Heat", (short)200);
        pot.loadWithComponents(saved, level.registryAccess());
        for (var shape : List.of(RitualShape.SMALL, RitualShape.MEDIUM, RitualShape.LARGE)) ring(level, pos.below(), shape);
        return pot;
    }
    private static ItemEntity drop(ServerLevel level, BlockPos pos, ItemStack stack) {
        var entity = new ItemEntity(level, pos.getX() + .5, pos.getY() + .75, pos.getZ() + .5, stack);
        entity.setNoGravity(true); entity.setDeltaMovement(Vec3.ZERO); entity.setUnlimitedLifetime();
        level.addFreshEntity(entity); return entity;
    }
    private static ItemEntity drop(ServerLevel level, BlockPos pos, String id, int count) {
        var item = BuiltInRegistries.ITEM.get(ResourceLocation.parse(id));
        if (item == net.minecraft.world.item.Items.AIR) throw new AssertionError("Missing fixture item " + id);
        return drop(level, pos, new ItemStack(item, count));
    }
    private static void move(ItemEntity entity, BlockPos pos) {
        entity.setPos(pos.getX() + .5, pos.getY() + .75, pos.getZ() + .5); entity.setDeltaMovement(Vec3.ZERO);
    }
    private static UUID prepare(GameTestHelper h, CrucibleBlockEntity pot) {
        return prepare(h,pot,"RITUAL_TRANSPIRATION");
    }
    private static UUID prepare(GameTestHelper h, CrucibleBlockEntity pot, String research) {
        var level = h.getLevel(); var store = StructuralRitualStore.get(level);
        var center = RitualCenters.observe(pot).orElseThrow().reference();
        var before = store.allAt(center).stream().map(StructuralRitualStore.Handle::id).toList();
        var stack = new ItemStack(RitualContent.TAGLOCK.get(), 2);
        var player=SteamRitualGameTests.researchedPlayer(h,"offering-test","STRUCTURAL_RITUALS",research);
        stack.set(RitualContent.TAGLOCK_SAMPLE.get(), new TaglockSample(1, player.getUUID(),
                ResourceLocation.withDefaultNamespace("player"), "offering-test", true));
        var sample = drop(level, pot.getBlockPos().above(), stack);
        pot.attemptSmelt(sample); // Native false also covers an event listener's HANDLED submission.
        h.assertTrue(sample.getItem().getCount() == 1,
                "One filled sample creates one draft through native input");
        sample.discard();
        return store.allAt(center).stream().map(StructuralRitualStore.Handle::id).filter(id -> !before.contains(id)).findFirst().orElseThrow();
    }
    private static void cleanup(ServerLevel level, BlockPos pos, ItemEntity... items) {
        level.removeBlock(pos, false);
        for (var item : items) if (item != null) item.discard();
    }

    @GameTest(templateNamespace="kaleidoscope_witchcraft", template="empty", timeoutTicks=40, batch="offering-geometry")
    public static void offeringAreaFollowsCompleteOuterRing(GameTestHelper h) {
        var pot = pot(h); var level = h.getLevel(); var pos = pot.getBlockPos(); var anchor = pos.below();
        var area = RitualOfferingArea.outermost(level, anchor).orElseThrow();
        h.assertTrue(area.containsHorizontal(Vec3.atCenterOf(pos.east(7))), "The outer marked block belongs to the offering area");
        h.assertTrue(area.containsHorizontal(Vec3.atCenterOf(pos.offset(3, 0, -7))), "The last block in the outer top row is included");
        h.assertTrue(!area.containsHorizontal(Vec3.atCenterOf(pos.offset(4, 0, -7)))
                && !area.containsHorizontal(Vec3.atCenterOf(pos.offset(7, 0, 7))), "Bounding-box corners outside the circle are excluded");
        h.assertTrue(!area.containsHorizontal(new Vec3(pos.getX()+8, pos.getY(), pos.getZ()+.5)), "The exclusive outer edge stays outside");
        var large = anchor.offset(RitualShape.LARGE.marks().getFirst()).above();
        var medium = anchor.offset(RitualShape.MEDIUM.marks().getFirst()).above();
        level.removeBlock(large, false);
        area = RitualOfferingArea.outermost(level, anchor).orElseThrow();
        h.assertTrue(area.containsHorizontal(Vec3.atCenterOf(pos.east(5))) && !area.containsHorizontal(Vec3.atCenterOf(pos.east(6))),
                "Removing the large mark immediately falls back to the middle ring");
        level.removeBlock(medium, false);
        area = RitualOfferingArea.outermost(level, anchor).orElseThrow();
        h.assertTrue(area.containsHorizontal(Vec3.atCenterOf(pos.east(3))) && !area.containsHorizontal(Vec3.atCenterOf(pos.east(4))),
                "Removing the middle mark immediately falls back to the small ring");
        level.setBlock(large, RitualContent.CHALK_SURFACE.get().defaultBlockState(), 3);
        h.assertTrue(RitualOfferingArea.outermost(level, anchor).orElseThrow()
                .containsHorizontal(Vec3.atCenterOf(pos.east(7))), "Small plus large works without a complete middle ring");
        cleanup(level, pos); h.succeed();
    }

    @GameTest(templateNamespace="kaleidoscope_witchcraft", template="empty", timeoutTicks=150, batch="offering-lifecycle")
    public static void outerOfferingsPauseAndResumeWithoutDoublePayment(GameTestHelper h) {
        var pot = pot(h); var level = h.getLevel(); var pos = pot.getBlockPos(); var id = prepare(h, pot);
        var store = StructuralRitualStore.get(level); pot.addAspects(Map.of(V, 4, O, 4));
        var coal = drop(level, pos.offset(7, 0, 7), "minecraft:coal", 2);
        var wood = drop(level, pos.west(6), "thaumcraft:silverwood_log", 2);
        var mercury = drop(level, pos.south(6), "thaumcraft:quicksilver", 2);
        var trigger = drop(level, pos, "thaumcraft:nitor", 2);
        pot.attemptSmelt(trigger);
        h.assertTrue(!store.progress(id).orElseThrow().terms().getBoolean("Armed") && coal.getItem().getCount() == 2,
                "An offering in an outside corner cannot arm or spend any part of the rite");
        move(coal, pos.east(7)); move(trigger, pos); pot.attemptSmelt(trigger);
        h.assertTrue(store.progress(id).orElseThrow().terms().getBoolean("Armed"), "A small rite accepts offerings across the complete outer ring");
        var large = pos.below().offset(RitualShape.LARGE.marks().getFirst()).above();
        var medium = pos.below().offset(RitualShape.MEDIUM.marks().getFirst()).above();
        h.runAtTickTime(25, () -> {
            h.assertTrue(coal.getItem().getCount() == 1 && wood.getItem().getCount() == 2, "First payment consumes only one outer coal");
            level.removeBlock(large, false); level.removeBlock(medium, false);
        });
        h.runAtTickTime(50, () -> {
            h.assertTrue(store.progress(id).orElseThrow().committedSteps() == 1 && wood.getItem().getCount() == 2,
                    "A smaller offering area waits for the unpaid material instead of charging outside it");
            level.setBlock(large, RitualContent.CHALK_SURFACE.get().defaultBlockState(), 3);
        });
        h.runAtTickTime(55, () -> {
            h.assertTrue(store.progress(id).orElseThrow().committedSteps() == 2 && wood.getItem().getCount() == 1,
                    "Repair resumes at the second receipt, without replaying coal");
            move(mercury, pos.offset(7, 0, 7));
        });
        h.runAtTickTime(85, () -> {
            h.assertTrue(store.progress(id).orElseThrow().committedSteps() == 2 && mercury.getItem().getCount() == 2,
                    "Taking the unpaid catalyst outside the ring pauses its own step");
            move(mercury, pos.south(6));
        });
        h.runAtTickTime(90, () -> {
            h.assertTrue(store.progress(id).orElseThrow().committedSteps() == 3, "Returning the catalyst commits resonance");
            trigger.discard();
        });
        var replacement = new ItemEntity[1];
        h.runAtTickTime(115, () -> {
            h.assertTrue(store.progress(id).orElseThrow().committedSteps() == 3 && pot.aspectAmount(V) == 4 && pot.aspectAmount(O) == 4,
                    "A missing trigger cannot pay final aspects or silently activate");
            replacement[0] = drop(level, pos, "thaumcraft:nitor", 2); pot.attemptSmelt(replacement[0]);
        });
        h.runAtTickTime(120, () -> {
            h.assertTrue(store.progress(id).orElseThrow().phase() == StructuralRitualStore.Phase.ACTIVE,
                    "A replacement physical trigger resumes the paid preparation");
            h.assertTrue(pot.aspectAmount(V) == 0 && pot.aspectAmount(O) == 0 && replacement[0].getItem().getCount() == 1
                    && coal.getItem().getCount() == 1 && wood.getItem().getCount() == 1 && mercury.getItem().getCount() == 2,
                    "Each consumed offering, trigger and aspect balance is paid exactly once; the catalyst remains");
            cleanup(level, pos, coal, wood, mercury, trigger, replacement[0]); h.succeed();
        });
    }

    @GameTest(templateNamespace="kaleidoscope_witchcraft", template="empty", timeoutTicks=140, batch="offering-sharing")
    public static void twoRitesShareOuterAreaButNeverOverspendAspects(GameTestHelper h) {
        var pot = pot(h); var level = h.getLevel(); var pos = pot.getBlockPos(); var store = StructuralRitualStore.get(level);
        var steam = prepare(h, pot); pot.addAspects(Map.of(V, 16, O, 16));
        var coal = drop(level, pos.east(6), "minecraft:coal", 2);
        var wood = drop(level, pos.west(6), "thaumcraft:silverwood_log", 3);
        var mercury = drop(level, pos.south(6), "thaumcraft:quicksilver", 3);
        var feather = drop(level, pos.north(6), "minecraft:feather", 2);
        var gold = drop(level, pos.east(5), "minecraft:gold_ingot", 2);
        var nitor = drop(level, pos, "thaumcraft:nitor", 2); pot.attemptSmelt(nitor);
        var suction = prepare(h, pot, "RITUAL_SUCTION_CHANNEL");
        var pipe = drop(level, pos, "thaumcraft:essentia_tube", 2); pot.attemptSmelt(pipe);
        h.assertTrue(store.progress(suction).orElseThrow().terms().getBoolean("Armed"),
                "The middle rite can use the large offering area while the small ring is occupied");
        h.runAtTickTime(110, () -> {
            h.assertTrue(store.progress(steam).orElseThrow().phase() == StructuralRitualStore.Phase.ACTIVE,
                    "The earlier steam receipt completes normally");
            h.assertTrue(store.progress(suction).orElseThrow().phase() == StructuralRitualStore.Phase.PREPARING
                    && store.progress(suction).orElseThrow().committedSteps() == 4 && pipe.getItem().getCount() == 2,
                    "A competing final payment waits for real aspects without consuming its trigger");
            h.assertTrue(pot.aspectAmount(V) == 0 && pot.aspectAmount(O) == 0, "The first receipt exhausts exactly its quoted aspects");
            pot.addAspects(Map.of(V, 16, O, 16));
        });
        h.runAtTickTime(115, () -> {
            h.assertTrue(store.progress(suction).orElseThrow().phase() == StructuralRitualStore.Phase.ACTIVE
                    && pipe.getItem().getCount() == 1 && nitor.getItem().getCount() == 1,
                    "Funding the second receipt completes it independently");
            h.assertTrue(coal.getItem().getCount() == 1 && wood.getItem().getCount() == 1 && mercury.getItem().getCount() == 2
                    && feather.getItem().getCount() == 1 && gold.getItem().getCount() == 2,
                    "Shared stacks supply exact per-recipe counts and both retained catalysts remain");
            h.assertTrue(pot.aspectAmount(V) == 0 && pot.aspectAmount(O) == 0 && RitualHerba.count(pot) == 2,
                    "Two paid rites preserve two distinguished leaves and no aspect debt");
            cleanup(level, pos, coal, wood, mercury, feather, gold, nitor, pipe); h.succeed();
        });
    }
}
