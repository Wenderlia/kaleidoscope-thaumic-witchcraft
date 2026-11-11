package dev.senluo.witchcraft.gametest;

import dev.senluo.witchcraft.ritual.TreeBond;
import dev.senluo.witchcraft.time.*;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@PrefixGameTestTemplate(false)
public final class FruitFeedingGameTests {
    @GameTest(templateNamespace="kaleidoscope_witchcraft",template="empty",timeoutTicks=60)
    public static void ownedFruitFundingReservesTreeAndSharesPayments(GameTestHelper h) {
        LivingEntity core = TreeGameTests.plant(h, true);
        TreeGameTests.fruit(h, core, TreeGameTests.side(4));
        TreeGameTests.fruit(h, core, TreeGameTests.side(4));
        var trees = TemporalWorld.get(h.getLevel()).trees;
        trees.tick(core);
        List<FruitEntity> fruit = trees.fruits(core);
        h.assertTrue(fruit.size() == 2, "Fixture must supply two independent fruit slots");
        core.tissue.bond = TreeBond.of(UUID.randomUUID(), "owner");
        TemporalWorld.get(h.getLevel()).invalidate();
        long reserve = trees.restorationReserve(core);
        h.assertTrue(reserve == 0, "A complete tree does not reserve its entire construction price");
        Colony group = TemporalWorld.get(h.getLevel()).colony(core);
        group.chooseMode(0, h.getLevel().getGameTime(), UUID.randomUUID());
        core.tissue.setCoreQuantity(3_000*Tissue.TU);
        fruit.forEach(f -> f.advanceDevelopment(1_200));
        TemporalWorld.get(h.getLevel()).production.flush(List.of());
        h.assertTrue(fruit.stream().allMatch(f -> f.ticks == 0 && f.invested == 0),
                "Pause stops paid production even when the tree is owned and stocked");
        group.chooseMode(1, h.getLevel().getGameTime(), UUID.randomUUID());
        fruit.forEach(f -> f.advanceDevelopment(1_200));
        TemporalWorld.get(h.getLevel()).production.flush(List.of());
        h.assertTrue(fruit.stream().allMatch(f -> f.ticks == 1_200 && f.invested == 1_000*Tissue.TU)
                && group.spendable() == 1_000*Tissue.TU,
                "Half of each stage consumes exactly half its price and advances only that work");
        h.succeed();
    }

    @GameTest(templateNamespace="kaleidoscope_witchcraft",template="empty",timeoutTicks=60)
    public static void fruitPrechargeProtectsOwnerReserveAndLockedRoots(GameTestHelper h) {
        LivingEntity core = TreeGameTests.plant(h, true);
        Tissue.Side side = TreeGameTests.side(4);
        FruitEntity fruit = TreeGameTests.fruit(h, core, side);
        var owner = DevelopmentGameTests.player(h, core);
        core.tissue.bond = TreeBond.of(owner.getUUID(), "owner");
        TemporalWorld.get(h.getLevel()).invalidate();
        core.tissue.setCoreQuantity(500*Tissue.TU);
        side.locked = true;
        side.tissue.setCoreQuantity(50_000*Tissue.TU);
        fruit.advanceDevelopment(TreeBalance.FRUIT_TICKS[1]);
        TemporalWorld.get(h.getLevel()).production.flush(List.of());
        h.assertTrue(fruit.ticks == 600 && fruit.invested == 500*Tissue.TU
                && side.tissue.coreQuantity() == 50_000*Tissue.TU,
                "Paid work uses only unlocked available stock, never a precharged root");
        h.getLevel().removeBlock(core.getBlockPos(), false);
        h.assertTrue(!fruit.canGrow() && !fruit.hasten(),
                "A removed core cannot fund more work through an old fruit reference");
        h.succeed();
    }

    @GameTest(templateNamespace="kaleidoscope_witchcraft",template="empty",timeoutTicks=60)
    public static void goldFruitCompletesOneStageAtCurrentPrice(GameTestHelper h) {
        LivingEntity core=TreeGameTests.plant(h,true);
        FruitEntity fruit=TreeGameTests.fruit(h,core,TreeGameTests.side(4));
        Colony group=TemporalWorld.get(h.getLevel()).colony(core);
        group.chooseMode(0,h.getLevel().getGameTime(),UUID.randomUUID());
        core.tissue.setCoreQuantity(999*Tissue.TU);
        fruit.invested=1000*Tissue.TU;fruit.ticks=1200;
        h.assertTrue(!fruit.hasten() && fruit.ticks==1200 && core.tissue.stored()==999*Tissue.TU,
                "Explicit stage completion has no partial purchase when one TU is missing");
        core.tissue.setCoreQuantity(1000*Tissue.TU);
        h.assertTrue(fruit.hasten() && fruit.grade==1 && fruit.ticks==0 && fruit.invested==0
                && core.tissue.stored()==0 && group.productionMode()==0,
                "One catalyst buys only remaining work and leaves the player's paused policy unchanged");
        h.succeed();
    }

    @GameTest(templateNamespace="kaleidoscope_witchcraft",template="empty",timeoutTicks=60)
    public static void connectedTreeReservesAddWithoutChangingOnDamage(GameTestHelper h) {
        LivingEntity first = TreeGameTests.plant(h, true);
        BlockPos otherPos = first.getBlockPos().east(8);
        h.getLevel().setBlock(otherPos.below(), Blocks.FARMLAND.defaultBlockState(), 3);
        h.getLevel().setBlock(otherPos, TemporalContent.CORE.get().defaultBlockState(), 3);
        LivingEntity second = (LivingEntity) h.getLevel().getBlockEntity(otherPos);
        first.tissue.bond = second.tissue.bond = TreeBond.of(UUID.randomUUID(), "owner");
        TreeGameTests.crown(h, first);
        TreeGameTests.crown(h, second);
        var trees = TemporalWorld.get(h.getLevel()).trees;
        trees.tick(first); trees.tick(second);
        second.tissue.setCoreQuantity(600*Tissue.TU);
        first.tissue.setCoreQuantity(600*Tissue.TU);
        Colony group = new Colony(List.of(first, second), List.of());
        h.assertTrue(FruitFeeding.reserve(group) == 0 && FruitFeeding.available(group) == 1_200*Tissue.TU,
                "Intact trees leave all stock available");
        h.getLevel().removeBlock(first.getBlockPos().above(), false);
        h.assertTrue(trees.restorationReserve(first) == TreeBalance.LOG_REPAIR,
                "One executable missing log reserves one repair");
        h.getLevel().setBlock(first.getBlockPos().above(), Blocks.STONE.defaultBlockState(), 3);
        h.assertTrue(trees.restorationReserve(first) == 0,
                "A blocked repair must not freeze inventory forever");
        h.succeed();
    }

    @GameTest(templateNamespace="kaleidoscope_witchcraft",template="empty",timeoutTicks=60)
    public static void fruitBottleInvestmentIsSilentExactAndSeparateFromGrowth(GameTestHelper h) {
        if (!ModList.get().isLoaded("tiab")) { h.succeed(); return; }
        FruitBottleCases.run(h);
    }
}
