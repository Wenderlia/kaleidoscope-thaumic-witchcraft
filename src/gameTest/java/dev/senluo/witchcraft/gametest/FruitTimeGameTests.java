package dev.senluo.witchcraft.gametest;

import dev.senluo.witchcraft.brew.BrewContent;
import dev.senluo.witchcraft.time.*;
import java.util.UUID;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@PrefixGameTestTemplate(false)
public final class FruitTimeGameTests {
    @GameTest(templateNamespace="kaleidoscope_witchcraft",template="empty",timeoutTicks=60)
    public static void fruitFertilizerCreditsOnlyTrunkAndPreservesRejectedFruit(GameTestHelper h) throws ReflectiveOperationException {
        long[] grants={400,1200,4400,30000,439600};
        for(int i=0;i<grants.length;i++)h.assertTrue(FruitTime.plantAmount(i+1)==grants[i]*Tissue.TU,
                "Completed quality gives the agreed twenty-percent cumulative grant");
        LivingEntity target=DevelopmentGameTests.plant(h,4,4,false);
        Tissue.Side side=TreeGameTests.side(4);target.tissue.sides.add(side);
        target.tissue.productionMode=3;
        var player=DevelopmentGameTests.player(h,target);
        ItemStack fruit=FruitItem.stack(4).copyWithCount(2);
        long amount=30_000*Tissue.TU;
        DevelopmentGameTests.use(h,target,player,fruit);
        h.assertTrue(fruit.getCount()==1 && target.tissue.coreQuantity()==amount && side.tissue.coreQuantity()==0,
                "Using a fruit on the rhizome consumes one and credits its trunk, without immediate side-root redistribution");
        target.tissue.setCoreQuantity(target.tissue.coreCapacity()-amount+1);
        long before=target.tissue.coreQuantity();
        DevelopmentGameTests.use(h,target,player,fruit);
        h.assertTrue(fruit.getCount()==1 && target.tissue.coreQuantity()==before,
                "Insufficient trunk room rejects the whole fruit, even if side-root storage is empty");
        target.tissue.setCoreQuantity(0);target.tissue.bond=dev.senluo.witchcraft.ritual.TreeBond.of(UUID.randomUUID(), "");
        DevelopmentGameTests.use(h,target,player,fruit);
        h.assertTrue(fruit.isEmpty() && target.tissue.coreQuantity()==amount,
                "Fertilizer remains available to nonowners without granting management rights");
        h.succeed();
    }

    @GameTest(templateNamespace="kaleidoscope_witchcraft",template="empty",timeoutTicks=60)
    public static void eatingFruitRefillsCurrentTierWithoutCreatingOrPromotingIt(GameTestHelper h) {
        var player=DevelopmentGameTests.player(h,TreeGameTests.plant(h,true));
        var time=player.getData(BrewContent.BODY_TIME);
        player.getFoodData().setFoodLevel(0);player.getFoodData().setSaturation(0);
        FruitItem.stack(5).finishUsingItem(h.getLevel(),player);
        h.assertTrue(!time.active() && time.grade()==0,"Food alone cannot create Years Abundance");
        time.drink(401);double before=time.baseTicks();
        FruitItem.stack(1).finishUsingItem(h.getLevel(),player);
        h.assertTrue(time.grade()==3 && time.baseTicks()==before+8_000,
                "A first-grade fruit adds 400 base-use seconds to the current compression tier, without discounting the grant");
        FruitItem.stack(5).finishUsingItem(h.getLevel(),player);
        h.assertTrue(time.grade()==3 && time.baseTicks()==time.capacity(),
                "Even a fifth-grade fruit only fills the existing tier; overflow never buys an upgrade");
        h.succeed();
    }

    @GameTest(templateNamespace="kaleidoscope_witchcraft",template="empty",timeoutTicks=60)
    public static void lastInstantEatingFeeRetainsTierUntilFruitRefillsIt(GameTestHelper h) {
        var player=DevelopmentGameTests.player(h,TreeGameTests.plant(h,true));
        var time=player.getData(BrewContent.BODY_TIME);
        time.drink(1);time.spend(28); // Exactly 32 base-use ticks remain at tier I.
        player.getFoodData().setFoodLevel(0);player.getFoodData().setSaturation(0);
        ItemStack fruit=FruitItem.stack(1);
        player.setItemInHand(InteractionHand.MAIN_HAND,fruit);
        fruit.use(h.getLevel(),player,InteractionHand.MAIN_HAND);
        h.assertTrue(player.getMainHandItem().isEmpty() && time.grade()==1 && time.baseTicks()==6_000,
                "Paying the exact remaining instant-eat fee still allows this fruit to refill the original tier");
        h.assertTrue(player.hasEffect(BrewContent.YEARS_ABUNDANCE),"The refilled ledger is reflected in the status display");
        h.succeed();
    }

    @GameTest(templateNamespace="kaleidoscope_witchcraft",template="empty",timeoutTicks=60)
    public static void fullHungerFruitUseRequiresExistingTimeWithRoom(GameTestHelper h) {
        var player=DevelopmentGameTests.player(h,TreeGameTests.plant(h,true));
        player.getAbilities().invulnerable=false;
        player.getAbilities().instabuild=false;
        player.getFoodData().setFoodLevel(20);player.getFoodData().setSaturation(20);
        var time=player.getData(BrewContent.BODY_TIME);
        ItemStack fruit=FruitItem.stack(5).copyWithCount(2);
        player.setItemInHand(InteractionHand.MAIN_HAND,fruit);
        fruit.use(h.getLevel(),player,InteractionHand.MAIN_HAND);
        h.assertTrue(!player.isUsingItem() && fruit.getCount()==2 && !time.active(),
                "Full hunger without an active personal tier cannot eat fruit to create one");
        time.drink(100);
        fruit.use(h.getLevel(),player,InteractionHand.MAIN_HAND);
        h.assertTrue(!player.isUsingItem() && fruit.getCount()==2 && time.baseTicks()==6_000,
                "A full ledger rejects use before any instant-eating fee can manufacture room");
        time.spend(60);
        fruit.use(h.getLevel(),player,InteractionHand.MAIN_HAND);
        h.assertTrue(fruit.getCount()==1 && time.grade()==1 && time.baseTicks()==6_000
                && player.getFoodData().getFoodLevel()==20,
                "Full hunger and saturation allow a fruit to refill an existing incomplete tier through normal eating");
        time.drink(500_000_000);
        fruit.use(h.getLevel(),player,InteractionHand.MAIN_HAND);
        h.assertTrue(time.free() && !player.isUsingItem() && fruit.getCount()==1,
                "A free tier has no refillable room and does not bypass normal hunger rules");
        h.succeed();
    }
}
