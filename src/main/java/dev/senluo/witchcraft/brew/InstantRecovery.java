package dev.senluo.witchcraft.brew;

import dev.senluo.witchcraft.mixin.brew.FoodRecoveryAccess;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.food.FoodData;
import net.minecraft.world.level.GameRules;

/** Advances only natural food recovery. It never ticks an entity, food effect or damage source. */
public final class InstantRecovery {
    private static final int MAX_STEPS=64;
    private static final ThreadLocal<Pending> PENDING=new ThreadLocal<>();
    private InstantRecovery() {}
    public static boolean hasNutritionBudget(ServerPlayer player) {
        return quote(Snapshot.read(player.getFoodData()),player.level().getDifficulty())!=null;
    }
    /** Pending replenishment is not spendable nutrition. Let the native effect tick supply it. */
    public static boolean awaitingNutritionSupply(ServerPlayer player) {
        var effect=player.getEffect(MobEffects.SATURATION);
        return effect!=null && (effect.isInfiniteDuration() || effect.getDuration()>0);
    }
    public static void recover(ServerPlayer player,BodyTime time) {
        if(PENDING.get()!=null || time.grade()<3 || !time.active() || !player.isAlive() || player.isSpectator()
                || !player.level().getGameRules().getBoolean(GameRules.RULE_NATURAL_REGENERATION)
                || !player.getData(BrewContent.NUTRITION).supported())return;
        for(int count=0;count<MAX_STEPS && time.active() && player.isHurt();count++) {
            FoodData food=player.getFoodData();
            Snapshot before=Snapshot.read(food);
            Step step=quote(before,player.level().getDifficulty());
            if(step==null || !time.canSpend(step.waitTicks()))break;
            Pending pending=new Pending(player,time,before,step);
            PENDING.set(pending);
            float health=player.getHealth();
            try {player.heal(Math.min(step.heal(),player.getMaxHealth()-health));}
            finally {PENDING.remove();}
            if(!pending.paid || player.getHealth()<=health)break;
        }
    }
    public static void enterHeal(LivingEntity entity) {
        Pending pending=PENDING.get();
        if(pending!=null && pending.player==entity)pending.healDepth++;
    }
    public static void leaveHeal(LivingEntity entity) {
        Pending pending=PENDING.get();
        if(pending!=null && pending.player==entity)pending.healDepth--;
    }
    /** Called after the normal heal event has approved/modified the amount, just before health changes. */
    public static boolean commit(LivingEntity entity,float targetHealth) {
        Pending pending=PENDING.get();
        // A heal listener may perform its own nested heal; that must not consume this recovery's quote.
        if(pending==null || pending.player!=entity || pending.healDepth!=1)return true;
        if(pending.paid || !Float.isFinite(targetHealth) || targetHealth<=entity.getHealth()
                || !entity.isAlive() || !pending.before.equals(Snapshot.read(pending.player.getFoodData()))
                || pending.time.grade()<3 || !pending.time.spend(pending.step.waitTicks()))return false;
        pending.step.after().apply(pending.player.getFoodData());
        pending.paid=true;
        return true;
    }
    private static Step quote(Snapshot start,Difficulty difficulty) {
        if(start.food()<18 || !Float.isFinite(start.saturation()) || start.saturation()<0
                || !Float.isFinite(start.exhaustion()) || start.exhaustion()<0 || start.exhaustion()>40
                || start.timer()<0)return null;
        int food=start.food(), timer=start.timer();
        float saturation=start.saturation(), exhaustion=start.exhaustion();
        // At most 80 virtual food ticks to the next recovery. Already elapsed timer is retained.
        for(int waited=1;waited<=80;waited++) {
            if(exhaustion>4) {
                exhaustion-=4;
                if(saturation>0)saturation=Math.max(0,saturation-1);
                else if(difficulty!=Difficulty.PEACEFUL)food=Math.max(0,food-1);
            }
            if(food<18)return null;
            boolean fast=food>=20 && saturation>0;
            timer++;
            if(timer<(fast?10:80))continue;
            float effort=fast?Math.min(saturation,6):6;
            float heal=effort/6;
            exhaustion=Math.min(40,exhaustion+effort);
            // Settle the incurred nutrition now rather than losing it to exhaustion's cap during a burst.
            // This is bookkeeping, not an additional recovery wait or extra ability nutrition charge.
            while(exhaustion>4) {
                exhaustion-=4;
                if(saturation>0)saturation=Math.max(0,saturation-1);
                else if(difficulty!=Difficulty.PEACEFUL)food=Math.max(0,food-1);
            }
            return new Step(waited,heal,new Snapshot(food,saturation,exhaustion,0));
        }
        return null;
    }
    private record Snapshot(int food,float saturation,float exhaustion,int timer) {
        private static Snapshot read(FoodData data) {
            return new Snapshot(data.getFoodLevel(),data.getSaturationLevel(),data.getExhaustionLevel(),
                    ((FoodRecoveryAccess)data).witchcraft$recoveryTimer());
        }
        private void apply(FoodData data) {
            data.setFoodLevel(food);data.setSaturation(saturation);data.setExhaustion(exhaustion);
            ((FoodRecoveryAccess)data).witchcraft$recoveryTimer(timer);
        }
    }
    private record Step(int waitTicks,float heal,Snapshot after) {}
    private static final class Pending {
        private final ServerPlayer player;
        private final BodyTime time;
        private final Snapshot before;
        private final Step step;
        private boolean paid;
        private int healDepth;
        private Pending(ServerPlayer player,BodyTime time,Snapshot before,Step step) {
            this.player=player;this.time=time;this.before=before;this.step=step;
        }
    }
}
