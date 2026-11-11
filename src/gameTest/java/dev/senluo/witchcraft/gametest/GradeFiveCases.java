package dev.senluo.witchcraft.gametest;

import com.mojang.authlib.GameProfile;
import dev.senluo.witchcraft.brew.*;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;

final class GradeFiveCases {
    private static boolean near(double a,double b) {return Math.abs(a-b)<.0001;}
    private static FakePlayer player(GameTestHelper h) {
        FakePlayer p=new FakePlayer(h.getLevel(),new GameProfile(UUID.randomUUID(),"grade-five"));
        p.getData(BrewContent.BODY_TIME).drink(600);
        p.getFoodData().setFoodLevel(20);p.getFoodData().setSaturation(10);
        p.getAttribute(Attributes.ATTACK_DAMAGE).setBaseValue(4);
        p.getAttribute(Attributes.ATTACK_SPEED).setBaseValue(2);
        p.setOnGround(true);
        return p;
    }
    private static void charge(FakePlayer p,int ticks) {
        try {
            var field=LivingEntity.class.getDeclaredField("attackStrengthTicker");
            field.setAccessible(true);field.setInt(p,ticks);
        } catch(ReflectiveOperationException e) {throw new IllegalStateException(e);}
    }
    static void combat(GameTestHelper h) {
        FakePlayer p=player(h);BodyTime time=p.getData(BrewContent.BODY_TIME);
        var target=h.spawn(EntityType.COW,new net.minecraft.core.BlockPos(1,2,1));
        p.setPos(target.position().add(0,0,1));p.setSprinting(true);
        double before=time.baseTicks();charge(p,4);p.attack(target);
        h.assertTrue(target.getHealth()<17 && near(before-time.baseTicks(),3),"Half-charge damage is boosted; 4.5 skipped base ticks receive the grade-five discount");
        h.assertTrue(p.isSprinting(),"Successful sprint attack must retain sprint");
        h.assertTrue(near(p.getFoodData().getSaturationLevel(),9.9),"One hit costs 0.1 nutrition");

        float health=target.getHealth();target.invulnerableTime=15;before=time.baseTicks();charge(p,4);p.attack(target);
        h.assertTrue(target.getHealth()<health && near(before-time.baseTicks(),10.0/3),"Personal second-half hit costs max(4.5,5) with the grade-five discount, not their sum");
        FakePlayer other=player(h);
        health=target.getHealth();
        h.assertTrue(target.invulnerableTime==20 && !target.hurt(other.damageSources().playerAttack(other),2)
                && target.getHealth()==health,"Bypass never opens the target's cooldown for other attackers");

        target.invulnerableTime=16;before=time.baseTicks();float food=p.getFoodData().getSaturationLevel();
        charge(p,4);p.attack(target);
        h.assertTrue(target.getHealth()==health && near(time.baseTicks(),before) && near(food,p.getFoodData().getSaturationLevel()),
                "First-half blocked hit refunds both escrowed resources");
        Consumer<LivingIncomingDamageEvent> cancel=e->{if(e.getEntity()==target)e.setCanceled(true);};
        NeoForge.EVENT_BUS.addListener(cancel);
        try {target.invulnerableTime=0;charge(p,4);p.attack(target);}
        finally {NeoForge.EVENT_BUS.unregister(cancel);}
        h.assertTrue(near(time.baseTicks(),before) && near(food,p.getFoodData().getSaturationLevel()),"Event-cancelled hit costs nothing");

        p.getFoodData().setSaturation(0);p.getFoodData().setFoodLevel(0);target.invulnerableTime=15;
        charge(p,4);p.attack(target);
        h.assertTrue(target.getHealth()==health && near(time.baseTicks(),before),"Missing nutrition preserves native cooldown and budget");
        target.invulnerableTime=0;p.getFoodData().setFoodLevel(20);p.getFoodData().setSaturation(10);
        charge(p,20);p.attack(target);
        h.assertTrue(time.baseTicks()==before && p.getFoodData().getSaturationLevel()==10,"Already fully waited normal attack is free");
        BodyTime escrow=new BodyTime();escrow.drink(600);escrow.drainActual(escrow.baseTicks()-10);
        BodyTime.Hold hold=escrow.hold(7.5);escrow.spend(7.5);hold.finish(false);
        h.assertTrue(escrow.grade()==5 && escrow.baseTicks()==5,"Cancelled escrow survives other callbacks exhausting the available balance");
        hold=escrow.hold(7.5);hold.finish(true);
        h.assertTrue(!escrow.active() && escrow.grade()==0,"Accepted last payment clears only after escrow is settled");
        h.succeed();
    }
    static void mobility(GameTestHelper h) {
        FakePlayer p=player(h);BodyTime time=p.getData(BrewContent.BODY_TIME);
        h.assertTrue(near(TimeStride.speedBonus(60),1) && near(TimeStride.speedBonus(90),1)
                && near(TimeStride.speedBonus(150),3) && near(TimeStride.speedBonus(180),3)
                && near(TimeStride.speedBonus(240),7),"2x, 4x, 8x ramps retain both 1.5-second plateaus");
        TemporalMobility.update(p,time);
        h.assertTrue(near(TemporalMobility.restoreFactor(p,.4),.7),"Grade five restores half of lost movement");
        h.assertTrue(near(CombatPoise.knockback(p,.4F),.025),"Grade five retains 1/16 knockback");
        p.setPos(h.absolutePos(new net.minecraft.core.BlockPos(1,2,1)).getCenter());p.setSprinting(true);
        double base=p.getAttributeValue(Attributes.MOVEMENT_SPEED);
        TimeStride.update(p,time);
        for(int i=1;i<=240;i++) {
            final int tick=i;
            h.runAfterDelay(i,()->{
                p.setPos(p.position().add(.3,0,0));TimeStride.update(p,time);
                if(tick==240) {
                    h.assertTrue(near(p.getAttributeValue(Attributes.MOVEMENT_SPEED),base*8)
                            && near(p.getAttributeValue(Attributes.STEP_HEIGHT),1.5),"Real updates reach 8x and 1.5-block step");
                    p.setShiftKeyDown(true);TimeStride.update(p,time);
                    h.assertTrue(near(p.getAttributeValue(Attributes.MOVEMENT_SPEED),base)
                            && p.getAttributeValue(Attributes.STEP_HEIGHT)<1.5,"Sneaking removes both modifiers");
                    h.succeed();
                }
            });
        }
    }
}
