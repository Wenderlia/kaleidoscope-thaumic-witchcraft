package dev.senluo.witchcraft.gametest;

import com.mojang.authlib.GameProfile;
import dev.senluo.witchcraft.brew.*;
import dev.senluo.witchcraft.time.TemporalContent;
import java.util.UUID;
import net.minecraft.gametest.framework.*;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.*;
import net.minecraft.world.item.*;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@PrefixGameTestTemplate(false)
public final class BrewGameTests {
    @GameTest(templateNamespace="kaleidoscope_witchcraft",template="empty",timeoutTicks=40)
    public static void flightStrideEligibility(GameTestHelper h) {FlightStrideCases.eligibility(h);}
    @GameTest(templateNamespace="kaleidoscope_witchcraft",template="empty",timeoutTicks=40)
    public static void flightStrideRamp(GameTestHelper h) {FlightStrideCases.ramp(h);}
    @GameTest(templateNamespace="kaleidoscope_witchcraft",template="empty",timeoutTicks=40)
    public static void gradeDiscount(GameTestHelper h) {GradeSixCases.discount(h);}
    @GameTest(templateNamespace="kaleidoscope_witchcraft",template="empty",timeoutTicks=40)
    public static void gradeSixAutomatic(GameTestHelper h) {GradeSixCases.automatic(h);}
    @GameTest(templateNamespace="kaleidoscope_witchcraft",template="empty",timeoutTicks=40)
    public static void rangedCharge(GameTestHelper h) {GradeSixCases.rangedCharge(h);}
    @GameTest(templateNamespace="kaleidoscope_witchcraft",template="empty",timeoutTicks=40)
    public static void gradeFiveCombat(GameTestHelper h) {GradeFiveCases.combat(h);}
    @GameTest(templateNamespace="kaleidoscope_witchcraft",template="empty",timeoutTicks=280)
    public static void gradeFiveMobility(GameTestHelper h) {GradeFiveCases.mobility(h);}
    @GameTest(templateNamespace="kaleidoscope_witchcraft",template="empty",timeoutTicks=40)
    public static void wineScoringAndCompression(GameTestHelper h) {
        h.assertTrue(WineData.grade(100)==1 && WineData.grade(100.001)==2 && WineData.grade(160)==2 && WineData.grade(160.001)==3,
                "A single bottle, not accumulated stock, determines grade");
        WineData wine=new WineData(384000,1,6);
        h.assertTrue(Math.abs(wine.score()-4353.2505168)<.001 && wine.grade()==10 && wine.version()==3,"New saturated tree-root batch uses economy three");
        ItemStack batch=new ItemStack(TemporalContent.LIUJIN_VODKA.get());
        wine.aged(1).write(batch);
        WineData restored=WineData.read(batch).aged(6);
        h.assertTrue(restored.version()==3 && restored.score()==wine.score(),"Current aging and item round trips retain score");
        BodyTime t=new BodyTime();t.drink(100);t.drink(100);
        h.assertTrue(t.grade()==1 && t.baseTicks()==6000,"Overflow is capped without promotion");
        t.drink(400);
        h.assertTrue(t.grade()==4 && t.baseTicks()==30000 && t.displayTicks()==40000,"Higher bottle preserves base time; display includes the grade discount");
        t.drink(100);
        h.assertTrue(t.grade()==4 && t.baseTicks()==36000,"Lower bottle adds its real value without changing the active grade");
        for(int i=0;i<20;i++)t.tick();
        h.assertTrue(Math.abs(t.baseTicks()-(36000-20*.75))<.00001,"Grade four upkeep discounts one base tick each game tick");
        BodyTime copy=new BodyTime();copy.deserializeNBT(h.getLevel().registryAccess(),t.serializeNBT(h.getLevel().registryAccess()));
        h.assertTrue(copy.grade()==4 && copy.baseTicks()==t.baseTicks(),"Saved balance retains exact base units, without offline drain");
        t.clear();t.drink(100);
        for(int i=0;i<5999;i++)t.tick();
        h.assertTrue(t.active() && t.displayTicks()==1,"Grade one lasts exactly 300 seconds at full stock");t.tick();
        h.assertTrue(!t.active(),"Exhaustion clears grade");
        t.drink(20000);double before=t.baseTicks();t.tick();
        h.assertTrue(t.grade()==13 && t.spend(1e9) && t.baseTicks()==before && t.displayTicks()==-1,"Thirteenth grade is cost-free");
        h.succeed();
    }
    private static FakePlayer player(GameTestHelper h) {
        return new FakePlayer(h.getLevel(),new GameProfile(UUID.randomUUID(),"wine-test"));
    }
    @GameTest(templateNamespace="kaleidoscope_witchcraft",template="empty",timeoutTicks=40)
    public static void extraTimeAbilities(GameTestHelper h) {
        FakePlayer p=player(h);BodyTime time=p.getData(BrewContent.BODY_TIME);
        ItemStack wine=LiujinVodkaItem.sample();wine.setCount(2);
        ItemStack left=wine.finishUsingItem(h.getLevel(),p);
        h.assertTrue(left.getCount()==1 && time.baseTicks()==6000 && p.hasEffect(BrewContent.YEARS_ABUNDANCE),"Drink credits once and presents registered icon");
        int bottles=p.getInventory().items.stream().filter(i->i.getItem().toString().contains("empty_bottle") || i.is(Items.GLASS_BOTTLE)).mapToInt(ItemStack::getCount).sum();
        h.assertTrue(bottles==1,"One consumed wine returns one bottle");
        p.removeAllEffects();ExtraTime.sync(p,time);
        h.assertTrue(p.hasEffect(BrewContent.YEARS_ABUNDANCE) && time.baseTicks()==6000,"Clearing display cannot erase internal time");
        MobEffectInstance poison=new MobEffectInstance(MobEffects.POISON,200,1);
        h.assertTrue(!poison.tick(p,()->{}) && time.baseTicks()==5600,"Poison duration is paid at amplifier + 1 without fast-forwarding damage");
        time.clear();time.drink(1);
        MobEffectInstance partial=new MobEffectInstance(MobEffects.POISON,100,1);
        partial.tick(p,()->{});
        h.assertTrue(partial.getDuration()==69 && !time.active(),"Insufficient stock removes only payable duration");
        time.drink(2);
        MobEffectInstance hidden=new MobEffectInstance(MobEffects.POISON,200,0);
        MobEffectInstance stronger=new MobEffectInstance(MobEffects.POISON,40,1,false,false,true,hidden);
        stronger.tick(p,()->{});
        h.assertTrue(stronger.getAmplifier()==0 && stronger.getDuration()==159 && !time.active(),"Hidden weaker duration is preserved and separately paid");
        time.drink(100);p.getFoodData().setFoodLevel(10);
        p.setItemInHand(InteractionHand.OFF_HAND,new ItemStack(Items.MUSHROOM_STEW));
        p.getItemInHand(InteractionHand.OFF_HAND).use(h.getLevel(),p,InteractionHand.OFF_HAND);
        h.assertTrue(!p.isUsingItem() && p.getItemInHand(InteractionHand.OFF_HAND).is(Items.BOWL)
                && time.baseTicks()==5968 && p.getFoodData().getFoodLevel()>10,"Instant off-hand food retains nutrition and bowl; pays actual 32 ticks");
        time.clear();time.drink(.1);p.getFoodData().setFoodLevel(10);
        p.setItemInHand(InteractionHand.MAIN_HAND,new ItemStack(Items.APPLE));
        p.getItemInHand(InteractionHand.MAIN_HAND).use(h.getLevel(),p,InteractionHand.MAIN_HAND);
        h.assertTrue(p.isUsingItem() && p.getItemInHand(InteractionHand.MAIN_HAND).getCount()==1 && time.baseTicks()==6,"Insufficient time falls back to ordinary food use");
        p.stopUsingItem();p.getFoodData().setFoodLevel(20);time.clear();time.drink(100);
        p.getItemInHand(InteractionHand.MAIN_HAND).use(h.getLevel(),p,InteractionHand.MAIN_HAND);
        h.assertTrue(!p.isUsingItem() && time.baseTicks()==6000,"Food conditions remain enforced");
        h.succeed();
    }
    @GameTest(templateNamespace="kaleidoscope_witchcraft",template="empty",timeoutTicks=180)
    public static void wineBarrelLifecycle(GameTestHelper h) { WineBarrelCases.lifecycle(h); }
    @GameTest(templateNamespace="kaleidoscope_witchcraft",template="empty",timeoutTicks=60)
    public static void wineBarrelIncompleteAndRemoval(GameTestHelper h) { WineBarrelCases.removal(h); }
    @GameTest(templateNamespace="kaleidoscope_witchcraft",template="empty",timeoutTicks=180)
    public static void vanillaBarrelStillBrews(GameTestHelper h) { WineBarrelCases.vanilla(h); }
    @GameTest(templateNamespace="kaleidoscope_witchcraft",template="empty",timeoutTicks=180)
    public static void diluteWineAndFinishedRemoval(GameTestHelper h) { WineBarrelCases.diluteAndBreak(h); }
    @GameTest(templateNamespace="kaleidoscope_witchcraft",template="empty",timeoutTicks=40)
    public static void bodyTimePersistence(GameTestHelper h) {
        FakePlayer p=player(h);BodyTime t=p.getData(BrewContent.BODY_TIME);t.drink(400);
        var provider=h.getLevel().registryAccess();
        var saved=p.saveWithoutId(new net.minecraft.nbt.CompoundTag());FakePlayer restored=player(h);restored.load(saved);
        BodyTime loaded=restored.getData(BrewContent.BODY_TIME);
        h.assertTrue(loaded.grade()==4 && loaded.baseTicks()==24000,"Player attachment persistence uses current bottle grade and base units");
        ExtraTime.tick(new net.neoforged.neoforge.event.tick.PlayerTickEvent.Post(restored));
        h.assertTrue(Math.abs(loaded.baseTicks()-(24000-.75))<1e-8
                && restored.hasEffect(BrewContent.YEARS_ABUNDANCE),"Player tick charges discounted maintenance and restores display");
        var future=new net.minecraft.nbt.CompoundTag();future.putInt("Version",99);future.putString("FutureValue","keep");
        loaded.deserializeNBT(provider,future);loaded.drink(100);loaded.tick();
        h.assertTrue(!loaded.acceptsWine() && loaded.serializeNBT(provider).equals(future),"Unknown future ledger is preserved without reinterpretation");
        h.succeed();
    }
    @GameTest(templateNamespace="kaleidoscope_witchcraft",template="empty",timeoutTicks=40)
    public static void bodyTimeInvalidData(GameTestHelper h) {
        var provider=h.getLevel().registryAccess();
        BodyTime ledger=new BodyTime();
        var empty=ledger.serializeNBT(provider);
        ledger.deserializeNBT(provider,empty);
        h.assertTrue(ledger.acceptsWine() && !ledger.active(),"An ordinary empty save remains usable");
        ledger.drink(100);
        h.assertTrue(ledger.active(),"Empty save can receive wine");
        var valid=ledger.serializeNBT(provider);
        var cases=new java.util.ArrayList<net.minecraft.nbt.CompoundTag>();
        for(double amount:new double[]{Double.NaN,Double.POSITIVE_INFINITY,-1,6001}) {
            var bad=valid.copy();bad.putDouble("BaseTicks",amount);cases.add(bad);
        }
        var bad=valid.copy();bad.putInt("Grade",WineData.grade(Double.MAX_VALUE)+1);cases.add(bad);
        bad=valid.copy();bad.putInt("Grade",0);cases.add(bad);
        bad=valid.copy();bad.remove("BaseTicks");cases.add(bad);
        bad=valid.copy();bad.putString("Grade","1");cases.add(bad);
        for(var malformed:cases) {
            ledger.deserializeNBT(provider,malformed);ledger.drink(400);ledger.tick();
            h.assertTrue(!ledger.acceptsWine() && !ledger.active() && !ledger.spend(1),"Invalid ledger cannot mint or spend time");
            h.assertTrue(ledger.serializeNBT(provider).toString().equals(malformed.toString()),"Invalid data survives save without replacement");
        }
        ledger.deserializeNBT(provider,valid);
        h.assertTrue(ledger.active() && ledger.baseTicks()==6000,"A repaired ledger can load normally");
        h.succeed();
    }
    @GameTest(templateNamespace="kaleidoscope_witchcraft",template="empty",timeoutTicks=80)
    public static void projectileDodge(GameTestHelper h) {
        FakePlayer p=player(h);BodyTime time=p.getData(BrewContent.BODY_TIME);time.drink(100);
        var arrow=new net.minecraft.world.entity.projectile.Arrow(net.minecraft.world.entity.EntityType.ARROW,h.getLevel());
        arrow.setDeltaMovement(1,0,0);
        var hit=new net.minecraft.world.phys.EntityHitResult(p);
        h.assertTrue(!net.neoforged.neoforge.event.EventHooks.onProjectileImpact(arrow,hit) && time.baseTicks()==6000,"Full grade one stock does not unlock dodge or consume time");
        time.clear();time.drink(120);
        h.assertTrue(time.grade()==2 && net.neoforged.neoforge.event.EventHooks.onProjectileImpact(arrow,hit) && time.baseTicks()==7145,"Grade two dodge quotes 60 ticks and pays 55 after its discount");
        h.assertTrue(net.neoforged.neoforge.event.EventHooks.onProjectileImpact(arrow,hit) && time.baseTicks()==7145 && !arrow.isRemoved() && arrow.getDeltaMovement().x==1,"Repeated impact in the same passage neither pays again nor destroys the projectile");
        time.drainActual(time.baseTicks()-30);
        var second=new net.minecraft.world.entity.projectile.Arrow(net.minecraft.world.entity.EntityType.ARROW,h.getLevel());
        h.assertTrue(!net.neoforged.neoforge.event.EventHooks.onProjectileImpact(second,hit) && time.baseTicks()==30,"Insufficient balance leaves collision and balance unchanged");
        time.clear();time.drink(400);
        var third=new net.minecraft.world.entity.projectile.Arrow(net.minecraft.world.entity.EntityType.ARROW,h.getLevel());
        h.assertTrue(time.grade()==4 && net.neoforged.neoforge.event.EventHooks.onProjectileImpact(third,hit) && time.baseTicks()==23955,"Higher grade discounts the same three-base-second quote to 45 ticks");
        var wall=new net.minecraft.world.phys.BlockHitResult(net.minecraft.world.phys.Vec3.ZERO,net.minecraft.core.Direction.UP,net.minecraft.core.BlockPos.ZERO,false);
        h.assertTrue(!net.neoforged.neoforge.event.EventHooks.onProjectileImpact(third,wall),"World block impacts are not canceled");
        time.clear();time.drink(20000);
        h.assertTrue(net.neoforged.neoforge.event.EventHooks.onProjectileImpact(second,hit) && time.free(),"Grade thirteen keeps existing free ability rule");
        h.runAfterDelay(3,()->{
            time.clear();time.drink(120);
            h.assertTrue(net.neoforged.neoforge.event.EventHooks.onProjectileImpact(arrow,hit) && time.baseTicks()==7145,"A later separate passage pays again");
            var lastArrow=new net.minecraft.world.entity.projectile.Arrow(net.minecraft.world.entity.EntityType.ARROW,h.getLevel());
            time.drainActual(time.baseTicks()-55);
            h.assertTrue(net.neoforged.neoforge.event.EventHooks.onProjectileImpact(lastArrow,hit) && !time.active(),"Exact payment can exhaust a grade two ledger");
            h.assertTrue(net.neoforged.neoforge.event.EventHooks.onProjectileImpact(lastArrow,hit),"Paid passage continues after grade resets on exhaustion");
            h.succeed();
        });
    }
    @GameTest(templateNamespace="kaleidoscope_witchcraft",template="empty",timeoutTicks=80)
    public static void nativeWinePlacement(GameTestHelper h) { WineBarrelCases.placement(h); }
    @GameTest(templateNamespace="kaleidoscope_witchcraft",template="empty",timeoutTicks=180)
    public static void placedCarrierKeepsWine(GameTestHelper h) { WineBarrelCases.placedCarrier(h); }
    @GameTest(templateNamespace="kaleidoscope_witchcraft",template="empty",timeoutTicks=60)
    public static void wineIntoxication(GameTestHelper h) { WineBarrelCases.intoxication(h); }
    @GameTest(templateNamespace="kaleidoscope_witchcraft",template="empty",timeoutTicks=110)
    public static void tapFeedback(GameTestHelper h) { TapFeedbackCases.run(h); }
    @GameTest(templateNamespace="kaleidoscope_witchcraft",template="empty",timeoutTicks=40)
    public static void combatPoise(GameTestHelper h) { CombatPoiseCases.run(h); }
    @GameTest(templateNamespace="kaleidoscope_witchcraft",template="empty",timeoutTicks=40)
    public static void abilityNutrition(GameTestHelper h) { AbilityBudgetCases.nutrition(h); }
    @GameTest(templateNamespace="kaleidoscope_witchcraft",template="empty",timeoutTicks=40)
    public static void instantRecovery(GameTestHelper h) { AbilityBudgetCases.recovery(h); }
    @GameTest(templateNamespace="kaleidoscope_witchcraft",template="empty",timeoutTicks=40)
    public static void recoveryHooks(GameTestHelper h) { AbilityBudgetCases.recoveryHooks(h); }
    @GameTest(templateNamespace="kaleidoscope_witchcraft",template="empty",timeoutTicks=100)
    public static void timeStride(GameTestHelper h) { AbilityBudgetCases.stride(h); }
}
