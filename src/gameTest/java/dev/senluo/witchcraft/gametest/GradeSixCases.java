package dev.senluo.witchcraft.gametest;

import com.mojang.authlib.GameProfile;
import dev.senluo.witchcraft.brew.*;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayer;

final class GradeSixCases {
    private static boolean near(double a,double b) {return Math.abs(a-b)<.00001;}
    static void discount(GameTestHelper h) {
        BodyTime t=new BodyTime();t.drink(1000);
        h.assertTrue(t.grade()==6 && near(t.costFactor(),7.0/12),"Grade six pays seven twelfths");
        double start=t.baseTicks();t.tick();
        h.assertTrue(near(start-t.baseTicks(),7.0/12),"Maintenance receives the same discount");
        start=t.baseTicks();t.tick(2);
        h.assertTrue(near(start-t.baseTicks(),2*7.0/12),"Movement doubles upkeep before discount, not after rounding");
        start=t.baseTicks();var hold=t.hold(120);hold.finish(60);
        h.assertTrue(near(start-t.baseTicks(),35),"Partial settlement discounts the final raw quote exactly once");
        start=t.baseTicks();hold=t.hold(120);hold.finish(false);
        h.assertTrue(near(start,t.baseTicks()),"Cancellation refunds the discounted reservation");
        start=t.baseTicks();hold=t.hold(120);hold.finish(true);
        h.assertTrue(near(start-t.baseTicks(),70),"Accepted reservation must not receive a second discount");
        h.assertTrue(t.displayTicks()==(int)Math.ceil(t.baseTicks()/(7.0/12)),"Display predicts discounted idle lifetime");
        var saved=t.serializeNBT(h.getLevel().registryAccess());saved.putDouble("BaseTicks",35);
        t.deserializeNBT(h.getLevel().registryAccess(),saved);
        int removed=t.payNegative(120,0);
        h.assertTrue(removed==60 && !t.active(),"Negative-effect affordability uses the discounted unit price: removed="+removed+", balance="+t.baseTicks());
        t.drink(20000);start=t.baseTicks();t.tick(2);hold=t.hold(5);hold.finish(true);
        h.assertTrue(t.free() && t.baseTicks()==start && t.displayTicks()==-1,"Grade thirteen retains free upkeep and abilities");
        h.succeed();
    }
    static void automatic(GameTestHelper h) {
        FakePlayer p=new FakePlayer(h.getLevel(),new GameProfile(UUID.randomUUID(),"grade-six"));
        BodyTime t=p.getData(BrewContent.BODY_TIME);t.drink(1000);
        p.setPos(h.absolutePos(new BlockPos(2,2,2)).getCenter());
        p.setOnGround(true);p.setSprinting(true);p.setDeltaMovement(new Vec3(.4,0,.2));
        p.setItemInHand(InteractionHand.MAIN_HAND,new ItemStack(Items.IRON_SWORD));
        p.getAttribute(Attributes.ATTACK_DAMAGE).setBaseValue(4);
        p.getAttribute(Attributes.ATTACK_SPEED).setBaseValue(2);
        p.getFoodData().setFoodLevel(20);p.getFoodData().setSaturation(10);
        try {
            var field=LivingEntity.class.getDeclaredField("attackStrengthTicker");
            field.setAccessible(true);field.setInt(p,2);
        } catch(ReflectiveOperationException e) {throw new IllegalStateException(e);}
        var enemy=h.spawn(EntityType.ZOMBIE,new BlockPos(2,2,3));enemy.setNoAi(true);
        var aggro=h.spawn(EntityType.COW,new BlockPos(2,2,4));aggro.setNoAi(true);aggro.setTarget(p);
        aggro.getAttribute(Attributes.KNOCKBACK_RESISTANCE).setBaseValue(1);
        var pet=h.spawn(EntityType.WOLF,new BlockPos(3,2,2));pet.setNoAi(true);pet.setOwnerUUID(p.getUUID());pet.setTarget(p);
        h.assertTrue(!AutomaticAttack.eligible(p,pet),"Owned pets remain excluded even with a target");
        aggro.getAttribute(Attributes.MAX_HEALTH).setBaseValue(512);aggro.setHealth(512);
        double gated=t.baseTicks();AutomaticAttack.tick(p,t);
        h.assertTrue(t.baseTicks()==gated && aggro.getHealth()==512,"Grade six without twofold stride cannot attack automatically");
        stride(p,.99);AutomaticAttack.tick(p,t);
        h.assertTrue(t.baseTicks()==gated,"Incomplete twofold acceleration cannot trigger automatic attacks");
        stride(p,1);
        double start=t.baseTicks();float food=p.getFoodData().getSaturationLevel(), effort=p.getFoodData().getExhaustionLevel();
        Vec3 motion=p.getDeltaMovement();float strength=p.getAttackStrengthScale(0);
        AutomaticAttack.tick(p,t);
        h.assertTrue(aggro.getHealth()<aggro.getMaxHealth() && enemy.getHealth()==enemy.getMaxHealth(),"Aggro priority beats nearer hostile; no sweep");
        h.assertTrue(near(start-t.baseTicks(),5*7.0/12),"Automatic invocation pays its discounted fixed fee only");
        h.assertTrue(p.getDeltaMovement().equals(motion) && p.isSprinting() && p.getAttackStrengthScale(0)==strength,"Automatic attack preserves movement and manual charge");
        h.assertTrue(p.getFoodData().getSaturationLevel()==food && p.getFoodData().getExhaustionLevel()==effort,"No nutrition or native attack exhaustion");
        double after=t.baseTicks();AutomaticAttack.tick(p,t);
        h.assertTrue(t.baseTicks()==after,"Repeated callback cannot duplicate attacks already dispatched this tick");
        h.runAfterDelay(1,()->{
            float health=aggro.getHealth();AutomaticAttack.tick(p,t);
            h.assertTrue(aggro.getHealth()<health,"Fractional 1.25-tick interval carries into the next tick");
        });
        h.runAfterDelay(2,()->{
            stride(p,15);
            float health=aggro.getHealth();double before=t.baseTicks();AutomaticAttack.tick(p,t);
            double attacks=(before-t.baseTicks())/(5*7.0/12);
            h.assertTrue(attacks>=2 && attacks<=64 && near(attacks,Math.rint(attacks)),"High stride batches separately charged attacks in one tick");
            h.assertTrue(health-aggro.getHealth()>4,"Personal fractional hurt windows allow multiple real hits, not only extra fees");
            FakePlayer other=new FakePlayer(h.getLevel(),new GameProfile(UUID.randomUUID(),"unboosted"));
            h.assertTrue(!aggro.hurt(h.getLevel().damageSources().playerAttack(other),1),"Another attacker still observes the public hurt window");
            before=t.baseTicks();AutomaticAttack.tick(p,t);
            h.assertTrue(t.baseTicks()==before,"Repeated callback in the same tick cannot duplicate a burst");
            p.setSprinting(false);AutomaticAttack.tick(p,t);
            h.assertTrue(t.baseTicks()==before,"Paused sprint cannot attack from cached speed alone");
            h.succeed();
        });
    }
    private static void stride(FakePlayer player,double bonus) {
        var attribute=player.getAttribute(Attributes.MOVEMENT_SPEED);
        var id=ResourceLocation.fromNamespaceAndPath("kaleidoscope_witchcraft","time_stride");
        attribute.removeModifier(id);
        attribute.addTransientModifier(new AttributeModifier(id,bonus,AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
    }
    static void rangedCharge(GameTestHelper h) {
        FakePlayer p=new FakePlayer(h.getLevel(),new GameProfile(UUID.randomUUID(),"ranged-charge"));
        BodyTime t=p.getData(BrewContent.BODY_TIME);t.drink(1000);
        ItemStack bow=new ItemStack(Items.BOW);
        p.setItemInHand(InteractionHand.MAIN_HAND,bow);p.startUsingItem(InteractionHand.MAIN_HAND);
        int remaining=p.getUseItemRemainingTicks();double before=t.baseTicks();
        int advanced=TimeRangedCharge.advance(p,bow,remaining);
        h.assertTrue(advanced==remaining-3 && near(before-t.baseTicks(),3*7.0/12),"Sixth-grade stationary bow buys only three extra charge ticks");
        before=t.baseTicks();
        h.assertTrue(TimeRangedCharge.advance(p,bow,bow.getUseDuration(p)-20)==bow.getUseDuration(p)-20
                && t.baseTicks()==before,"Holding a fully drawn bow has no further charge fee");
        stride(p,15);before=t.baseTicks();
        h.assertTrue(TimeRangedCharge.advance(p,bow,remaining)==remaining-19 && near(before-t.baseTicks(),19*7.0/12),
                "Sixteenfold stride finishes useful bow charge without charging past full power");
        p.stopUsingItem();
        ItemStack crossbow=new ItemStack(Items.CROSSBOW);
        p.setItemInHand(InteractionHand.MAIN_HAND,crossbow);p.startUsingItem(InteractionHand.MAIN_HAND);
        int duration=CrossbowItem.getChargeDuration(crossbow,p);
        remaining=p.getUseItemRemainingTicks();before=t.baseTicks();
        advanced=TimeRangedCharge.advance(p,crossbow,remaining);
        h.assertTrue(advanced==remaining-Math.min(63,duration-1)
                && near(before-t.baseTicks(),Math.min(63,duration-1)*7.0/12),"Crossbow charge follows its item-provided duration and pays useful skipped time");
        var saved=t.serializeNBT(h.getLevel().registryAccess());saved.putDouble("BaseTicks",.1);
        t.deserializeNBT(h.getLevel().registryAccess(),saved);before=t.baseTicks();
        h.assertTrue(TimeRangedCharge.advance(p,crossbow,remaining)==remaining && t.baseTicks()==before,
                "Unaffordable acceleration leaves native charging and nutrition untouched");
        h.succeed();
    }
}
