package dev.senluo.witchcraft.gametest;

import com.mojang.authlib.GameProfile;
import dev.senluo.witchcraft.brew.*;
import dev.senluo.witchcraft.mixin.brew.FoodRecoveryAccess;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.GameRules;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.entity.living.LivingHealEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

final class AbilityBudgetCases {
    private static FakePlayer player(GameTestHelper h,double score) {
        FakePlayer p=new FakePlayer(h.getLevel(),new GameProfile(UUID.randomUUID(),"ability-budget"));
        p.getData(BrewContent.BODY_TIME).drink(score);
        return p;
    }
    private static boolean near(double actual,double expected) {return Math.abs(actual-expected)<.00001;}
    private static long debt(FakePlayer p) {
        return p.getData(BrewContent.NUTRITION).serializeNBT(p.registryAccess()).getLong("FoodDebt");
    }
    static void nutrition(GameTestHelper h) {
        FakePlayer p=player(h,400);BodyTime time=p.getData(BrewContent.BODY_TIME);
        p.getFoodData().setFoodLevel(2);p.getFoodData().setSaturation(.05F);
        h.assertTrue(AbilityCosts.pay(p,2,50,AbilityCosts.SHIELD),"Mixed saturation and food payment succeeds");
        h.assertTrue(p.getFoodData().getSaturationLevel()==0 && p.getFoodData().getFoodLevel()==2
                && debt(p)==250000 && time.baseTicks()==23962.5,"Saturation is paid first and fractional food is reserved");
        for(int i=0;i<3;i++)AbilityCosts.pay(p,2,20,AbilityCosts.STEADY);
        AbilityCosts.pay(p,2,60,AbilityCosts.DODGE);AbilityCosts.pay(p,2,60,AbilityCosts.DODGE);
        h.assertTrue(p.getFoodData().getFoodLevel()==1 && debt(p)==50000
                && p.getFoodData().getExhaustionLevel()==0,"A whole accumulated point is deducted, without duplicate exhaustion");
        time.clear();time.drink(400);
        h.assertTrue(debt(p)==50000,"Re-drinking after exhaustion does not erase debt");
        FakePlayer restored=player(h,1);restored.load(p.saveWithoutId(new CompoundTag()));
        h.assertTrue(debt(restored)==50000 && restored.getFoodData().getFoodLevel()==1,"Debt survives actual player serialization");
        CompoundTag tail=new CompoundTag();tail.putInt("Version",1);tail.putLong("FoodDebt",900000);
        p.getData(BrewContent.NUTRITION).deserializeNBT(p.registryAccess(),tail);
        double before=time.baseTicks();
        h.assertTrue(!AbilityCosts.pay(p,2,20,AbilityCosts.STEADY) && time.baseTicks()==before
                && debt(p)==900000,"Reserved food cannot be spent twice; failed nutrition leaves time untouched");
        p.getFoodData().setSaturation(1);time.drainActual(time.baseTicks()-10);
        h.assertTrue(!AbilityCosts.pay(p,2,20,AbilityCosts.STEADY)
                && p.getFoodData().getSaturationLevel()==1 && debt(p)==900000,"Failed time payment leaves nutrition untouched");
        time.clear();time.drink(20000);
        h.assertTrue(AbilityCosts.pay(p,2,20,AbilityCosts.STEADY)
                && near(p.getFoodData().getSaturationLevel(),.8) && time.free(),"Grade thirteen still pays nutrition");
        p.getFoodData().setSaturation(0);p.getFoodData().setFoodLevel(0);
        h.assertTrue(!AbilityCosts.pay(p,2,60,AbilityCosts.DODGE),"Free time cannot pay missing nutrition");
        CompoundTag future=new CompoundTag();future.putInt("Version",99);future.putString("Other","preserve");
        p.getData(BrewContent.NUTRITION).deserializeNBT(p.registryAccess(),future);
        p.getFoodData().setSaturation(5);
        h.assertTrue(!AbilityCosts.pay(p,2,20,AbilityCosts.STEADY)
                && p.getData(BrewContent.NUTRITION).serializeNBT(p.registryAccess()).equals(future),"Unknown nutrition saves fail closed without data loss");
        h.succeed();
    }
    private static void food(FakePlayer p,int level,float saturation,float exhaustion,int timer) {
        p.getFoodData().setFoodLevel(level);p.getFoodData().setSaturation(saturation);
        p.getFoodData().setExhaustion(exhaustion);
        ((FoodRecoveryAccess)p.getFoodData()).witchcraft$recoveryTimer(timer);
    }
    static void recovery(GameTestHelper h) {
        FakePlayer p=player(h,200);BodyTime time=p.getData(BrewContent.BODY_TIME);
        p.setHealth(19);food(p,20,6,0,7);InstantRecovery.recover(p,time);
        h.assertTrue(p.getHealth()==20 && time.baseTicks()==11997.5
                && p.getFoodData().getSaturationLevel()==5 && p.getFoodData().getExhaustionLevel()==2,
                "Fast recovery pays only three unelapsed ticks and native nutrition");
        p.setHealth(10);food(p,18,0,0,0);double before=time.baseTicks();InstantRecovery.recover(p,time);
        h.assertTrue(p.getHealth()==11 && p.getFoodData().getFoodLevel()==17 && near(time.baseTicks(),before-80*(10.0/12)),
                "Hunger eighteen redeems one slow heal and stops below the threshold");
        food(p,20,6,0,0);p.setHealth(19);InstantRecovery.recover(p,time);
        h.assertTrue(p.getHealth()==20,"Replenished nutrition resumes recovery");
        p.setHealth(19);food(p,20,6,0,0);time.drainActual(time.baseTicks()-8);InstantRecovery.recover(p,time);
        h.assertTrue(p.getHealth()==19 && time.baseTicks()==8 && p.getFoodData().getSaturationLevel()==6,
                "Insufficient time does not consume nutrition or advance the food timer");
        time.clear();time.drink(120);InstantRecovery.recover(p,time);
        h.assertTrue(p.getHealth()==19 && time.baseTicks()==7200,"Grade two does not unlock recovery");
        time.clear();time.drink(200);food(p,18,0,8,0);before=time.baseTicks();InstantRecovery.recover(p,time);
        h.assertTrue(p.getHealth()==19 && time.baseTicks()==before,"Existing exhaustion cannot be ignored to fabricate recovery budget");
        food(p,20,6,0,0);
        var rule=h.getLevel().getGameRules().getRule(GameRules.RULE_NATURAL_REGENERATION);
        boolean old=rule.get();rule.set(false,h.getLevel().getServer());
        try {InstantRecovery.recover(p,time);h.assertTrue(p.getHealth()==19,"Natural regeneration rule disables instant recovery");}
        finally {rule.set(old,h.getLevel().getServer());}
        time.clear();time.drink(20000);food(p,18,0,0,0);p.setHealth(10);InstantRecovery.recover(p,time);
        h.assertTrue(p.getHealth()==11 && p.getFoodData().getFoodLevel()==17 && time.free(),"Grade thirteen still runs out of natural recovery nutrition");
        p.setHealth(0);food(p,20,20,0,0);InstantRecovery.recover(p,time);
        h.assertTrue(p.getHealth()==0,"Recovery cannot resurrect a dead player");
        h.succeed();
    }
    static void recoveryHooks(GameTestHelper h) {
        FakePlayer p=player(h,200);BodyTime time=p.getData(BrewContent.BODY_TIME);
        p.setHealth(19);food(p,20,6,0,0);
        Consumer<LivingHealEvent> cancel=e->{if(e.getEntity()==p)e.setCanceled(true);};
        NeoForge.EVENT_BUS.addListener(cancel);
        try {InstantRecovery.recover(p,time);}
        finally {NeoForge.EVENT_BUS.unregister(cancel);}
        h.assertTrue(p.getHealth()==19 && time.baseTicks()==12000 && p.getFoodData().getSaturationLevel()==6,
                "Canceled healing charges neither resource");
        Consumer<LivingHealEvent> amplify=e->{if(e.getEntity()==p)e.setAmount(e.getAmount()*2);};
        p.setHealth(18);NeoForge.EVENT_BUS.addListener(amplify);
        try {InstantRecovery.recover(p,time);}
        finally {NeoForge.EVENT_BUS.unregister(amplify);}
        h.assertTrue(p.getHealth()==20 && near(time.baseTicks(),12000-10*(10.0/12)),"Normal healing modifiers remain effective");
        p.setHealth(18);food(p,20,6,0,0);double before=time.baseTicks();
        boolean[] inside={false};
        Consumer<LivingHealEvent> nested=e->{if(e.getEntity()==p && !inside[0]) {
            inside[0]=true;try {p.heal(1);}finally {inside[0]=false;}
        }};
        NeoForge.EVENT_BUS.addListener(nested);
        try {InstantRecovery.recover(p,time);}
        finally {NeoForge.EVENT_BUS.unregister(nested);}
        h.assertTrue(p.getHealth()==20 && near(time.baseTicks(),before-10*(10.0/12))
                && p.getFoodData().getSaturationLevel()==5,"Nested external healing cannot steal or double-pay the recovery quote");
        p.setHealth(19);food(p,20,6,0,0);before=time.baseTicks();
        Consumer<LivingHealEvent> alter=e->{if(e.getEntity()==p)p.getFoodData().setSaturation(4);};
        NeoForge.EVENT_BUS.addListener(alter);
        try {InstantRecovery.recover(p,time);}
        finally {NeoForge.EVENT_BUS.unregister(alter);}
        h.assertTrue(p.getHealth()==19 && time.baseTicks()==before && p.getFoodData().getSaturationLevel()==4,
                "Food changes in a heal callback are preserved, not overwritten by stale quotes");
        p.heal(1);
        h.assertTrue(p.getHealth()==20 && time.baseTicks()==before,"Normal healing outside the recovery scope remains untouched");
        h.succeed();
    }
    static void stride(GameTestHelper h) {
        FakePlayer p=player(h,200);BodyTime time=p.getData(BrewContent.BODY_TIME);
        p.setPos(h.absolutePos(new net.minecraft.core.BlockPos(1,2,1)).getCenter());
        p.setOnGround(true);p.setSprinting(true);
        double normal=p.getAttributeValue(Attributes.MOVEMENT_SPEED);
        ExtraTime.tick(new PlayerTickEvent.Post(p));
        for(int i=1;i<=60;i++) {
            final int elapsed=i;
            h.runAfterDelay(i,()->{
                p.setPos(p.position().add(.3,0,0));ExtraTime.tick(new PlayerTickEvent.Post(p));
                h.assertTrue(near(p.getAttributeValue(Attributes.MOVEMENT_SPEED),normal*(1+elapsed/60.0)),"Speed follows sixty actual ground movement ticks");
                h.assertTrue(near(time.baseTicks(),12000-(1+2*elapsed)*(10.0/12)),"Stride charges double total upkeep, not triple");
            });
        }
        h.runAfterDelay(61,()->{
            p.setOnGround(false);p.setPos(p.position().add(.3,.1,0));ExtraTime.tick(new PlayerTickEvent.Post(p));
            h.assertTrue(near(p.getAttributeValue(Attributes.MOVEMENT_SPEED),normal*2),"Sprint jumps retain attained speed");
        });
        h.runAfterDelay(62,()->{
            double before=time.baseTicks();ExtraTime.tick(new PlayerTickEvent.Post(p));
            h.assertTrue(near(p.getAttributeValue(Attributes.MOVEMENT_SPEED),normal*2) && near(time.baseTicks(),before-2*(10.0/12)),
                    "One stationary sprint tick retains the speed bonus and its double upkeep");
            p.setSprinting(false);TimeStride.clear(p);p.setOnGround(true);p.setSprinting(true);
            time.clear();time.drink(20000);TimeStride.update(p,time);
        });
        h.runAfterDelay(63,()->{
            p.setPos(p.position().add(.3,0,0));ExtraTime.tick(new PlayerTickEvent.Post(p));
            h.assertTrue(p.getAttributeValue(Attributes.MOVEMENT_SPEED)>normal && time.free(),"Free-time grades can still stride");
            p.setSprinting(false);ExtraTime.tick(new PlayerTickEvent.Post(p));
            h.assertTrue(near(p.getAttributeValue(Attributes.MOVEMENT_SPEED),normal/1.3),"Releasing sprint removes the extra modifier");
            h.assertTrue(p.getFoodData().getExhaustionLevel()==0,"Stride does not add duplicate nutrition to native movement accounting");
            h.succeed();
        });
    }
}
