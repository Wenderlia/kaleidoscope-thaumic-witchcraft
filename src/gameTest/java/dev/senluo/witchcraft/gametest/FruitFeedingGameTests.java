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
    @GameTest(batch="regrowthFunds", templateNamespace="kaleidoscope_witchcraft", template="empty", timeoutTicks=140)
    public static void freshLeafReserveProtectsTheNextDeposit(GameTestHelper h) {
        var core = TreeGameTests.plant(h,true);
        TreeGameTests.crown(h,core);
        var world = TemporalWorld.get(h.getLevel());
        var fruit = TreeGameTests.nodeFruit(h,core,1);
        BlockPos eastLeaf = core.getBlockPos().offset(1,3,0);
        BlockPos leaf = eastLeaf.equals(fruit.getBlockPos().above()) ? core.getBlockPos().offset(-1,3,0) : eastLeaf;
        h.runAfterDelay(65,()->{
            var colony = world.colony(core);
            core.tissue.setCoreQuantity(0);
            h.assertTrue(FruitFeeding.reserve(colony)==0, "An intact tree has no paid repair");
            var player = DevelopmentGameTests.player(h,core);
            player.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);
            player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND,
                    new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.SHEARS));
            player.setPos(leaf.getX()+0.5,leaf.getY(),leaf.getZ()+1.5);
            h.assertTrue(player.gameMode.destroyBlock(leaf) && h.getLevel().getBlockState(leaf).isAir(), "Shearing removed the leaf");
            world.trees.flush();
            world.trees.tick(core);
            for (int i=0; i<8; i++) world.trees.flush();
            fruit.invested = TreeBalance.nodeFruitFee(1) - TreeBalance.LEAF_REPAIR;
            h.assertTrue(fruit.limit()==1 && fruit.remainingFee()==TreeBalance.LEAF_REPAIR,"This live fruit needs exactly the same payment");
            h.assertTrue(colony.credit(TreeBalance.LEAF_REPAIR,core), "Credit exactly one leaf repair");
            h.assertTrue(FruitFeeding.reserve(colony)==TreeBalance.LEAF_REPAIR && FruitFeeding.available(colony)==0,
                    "The confirmed leaf repair protects the deposit");
            h.assertFalse(fruit.hasten(),"Active fruit catalysis cannot take the leaf's payment");
            world.trees.tick(core);
            world.trees.flush();
            h.assertTrue(h.getLevel().getBlockState(leaf).is(TemporalContent.TIME_LEAVES.get())
                    && colony.spendable()==0 && FruitFeeding.reserve(colony)==0, "The leaf settles once and releases the quote");
            world.trees.tick(core);
            for (int i=0; i<8; i++) world.trees.flush();
            h.assertTrue(colony.credit(TreeBalance.LEAF_REPAIR,core) && fruit.hasten() && fruit.grade==1 && colony.spendable()==0,
                    "The same fruit can buy its last 4 TU once the leaf has grown back");
            h.getLevel().removeBlock(core.getBlockPos(),false);
            h.succeed();
        });
    }

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
        var level = h.getLevel();
        LivingEntity first = TreeGameTests.plant(h, true);
        BlockPos otherPos = first.getBlockPos().east(12);
        level.setBlock(otherPos.below(), Blocks.FARMLAND.defaultBlockState(), 3);
        level.setBlock(otherPos, TemporalContent.CORE.get().defaultBlockState(), 3);
        LivingEntity second = (LivingEntity) level.getBlockEntity(otherPos);
        second.tissue.rootStage=4; second.tissue.tree=true; second.changed();
        first.tissue.bond = second.tissue.bond = TreeBond.of(UUID.randomUUID(), "owner");
        TemporalWorld.get(level).add(second);
        TreeGameTests.crown(h,first);
        TreeGameTests.crown(h,second);
        var trees = TemporalWorld.get(level).trees;
        Colony group = new Colony(List.of(first, second), List.of());
        first.tissue.setCoreQuantity(0); second.tissue.setCoreQuantity(0);
        for (int i=0;i<20;i++) trees.flush();
        h.assertTrue(FruitFeeding.reserve(group)==0,"Intact trees leave the wallet available");
        var firstLeaf = first.getBlockPos().offset(1,3,0);
        var secondLeaf = second.getBlockPos().offset(1,3,0);
        level.removeBlock(firstLeaf,false);
        level.removeBlock(secondLeaf,false);
        for (int i=0;i<12;i++) trees.flush();
        h.assertTrue(FruitFeeding.reserve(group)==2*TreeBalance.LEAF_REPAIR, "Both confirmed repairs contribute once");
        level.setBlock(firstLeaf,Blocks.STONE.defaultBlockState(),3);
        h.assertTrue(FruitFeeding.reserve(group)==TreeBalance.LEAF_REPAIR && trees.restorationReserve(first)==0,
                "Blocking one repair immediately releases only that tree's reserve");
        level.removeBlock(otherPos,false);
        h.assertTrue(FruitFeeding.reserve(group)==0,"Removing a core releases its cached quote, even through an old colony view");
        level.setBlock(otherPos,TemporalContent.CORE.get().defaultBlockState(),3);
        var replacement = (LivingEntity)level.getBlockEntity(otherPos);
        h.assertTrue(replacement != second && trees.restorationReserve(second)==0 && trees.restorationReserve(replacement)==0,
                "A new core at the same site cannot inherit the old repair bill");
        level.removeBlock(otherPos,false);
        level.removeBlock(first.getBlockPos(),false);
        h.succeed();
    }

    @GameTest(templateNamespace="kaleidoscope_witchcraft",template="empty",timeoutTicks=60)
    public static void fruitBottleInvestmentIsSilentExactAndSeparateFromGrowth(GameTestHelper h) {
        if (!ModList.get().isLoaded("tiab")) { h.succeed(); return; }
        FruitBottleCases.run(h);
    }
}
