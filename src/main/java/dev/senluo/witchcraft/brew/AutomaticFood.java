package dev.senluo.witchcraft.brew;

import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.SuspiciousStewEffects;
import net.minecraft.world.level.GameRules;

/** Scans only after a real need; uses normal food use/finish hooks without changing the selected slot. */
public final class AutomaticFood {
    private static final Map<ServerPlayer,Long> NEXT_ATTEMPT=new WeakHashMap<>();
    private static final ThreadLocal<ServerPlayer> EATING=new ThreadLocal<>();
    private AutomaticFood() {}
    public static boolean tryEat(ServerPlayer player,BodyTime time,boolean allowRecovery) {
        if(time==null || !time.active() || time.grade()<4 || !player.isAlive() || player.isSpectator()
                || player.getAbilities().instabuild || player.isUsingItem() || EATING.get()!=null
                || !player.getData(BrewContent.NUTRITION).supported())return false;
        // Recovery can exhaust current stock between native saturation ticks. That gap
        // must not consume inventory food, whether called by the pre- or post-tick path.
        // Do not delay NEXT_ATTEMPT: food becomes eligible as soon as the effect expires.
        if(InstantRecovery.awaitingNutritionSupply(player))return false;
        var food=player.getFoodData();
        boolean recovery=allowRecovery && player.isHurt()
                && player.level().getGameRules().getBoolean(GameRules.RULE_NATURAL_REGENERATION)
                && !InstantRecovery.hasNutritionBudget(player);
        double reserve=food.getSaturationLevel()-player.getData(BrewContent.NUTRITION).foodDebt();
        boolean running=player.isSprinting() && food.getFoodLevel()<=7
                && (reserve<=1 || food.getExhaustionLevel()>4);
        if((!recovery && !running) || food.getFoodLevel()>=20)return false;
        long tick=player.level().getGameTime();
        if(tick<NEXT_ATTEMPT.getOrDefault(player,Long.MIN_VALUE))return false;
        Candidate best=null;
        int target=recovery?18:8;
        for(int slot=0;slot<Math.min(36,player.getInventory().items.size());slot++) {
            ItemStack stack=player.getInventory().items.get(slot);
            if(stack.isEmpty() || player.getCooldowns().isOnCooldown(stack.getItem()))continue;
            FoodProperties properties=stack.getFoodProperties(player);
            if(properties==null || properties.nutrition()<=0 || !Float.isFinite(properties.saturation())
                    || properties.saturation()<0 || !player.canEat(properties.canAlwaysEat()))continue;
            int duration=stack.getUseDuration(player);
            if(duration<=0 || !time.canSpend(duration))continue;
            int after=Math.min(20,food.getFoodLevel()+properties.nutrition());
            double usedSaturation=Math.min(properties.saturation(),Math.max(0,after-food.getSaturationLevel()));
            double waste=Math.max(0,food.getFoodLevel()+properties.nutrition()-20)+properties.saturation()-usedSaturation;
            Candidate next=new Candidate(slot,effectRank(stack,properties),Math.max(0,target-after),waste,
                    properties.nutrition()+usedSaturation);
            if(best==null || next.betterThan(best))best=next;
        }
        if(best==null) {NEXT_ATTEMPT.put(player,tick+10);return false;}
        NEXT_ATTEMPT.put(player,tick+1); // At most one food across pre/post hooks per real tick.
        int slot=best.slot();
        ItemStack selected=player.getInventory().items.get(slot);
        ItemStack oldOffhand=player.getOffhandItem();
        int beforeCount=selected.getCount();
        int beforeFood=food.getFoodLevel();float beforeSaturation=food.getSaturationLevel();
        player.getInventory().items.set(slot,ItemStack.EMPTY);
        player.setItemInHand(InteractionHand.OFF_HAND,selected);
        EATING.set(player);
        try {
            // Existing InstantFoodMixin charges the actual event-approved duration and finishes use.
            // If the event rejects use or makes it unaffordable, cancel only our staged action.
            var result=selected.use(player.level(),player,InteractionHand.OFF_HAND);
            // Respect a use override's replacement, but do not replace the container already
            // produced by synchronous completion with the now-consumed original stack.
            if(player.getOffhandItem()==selected && result.getObject()!=selected)
                player.setItemInHand(InteractionHand.OFF_HAND,result.getObject());
        } finally {
            if(player.isUsingItem() && player.getUsedItemHand()==InteractionHand.OFF_HAND)player.stopUsingItem();
            ItemStack remainder=player.getOffhandItem();
            player.setItemInHand(InteractionHand.OFF_HAND,oldOffhand);
            if(player.getInventory().items.get(slot).isEmpty())player.getInventory().items.set(slot,remainder);
            else if(!remainder.isEmpty() && !player.getInventory().add(remainder))player.drop(remainder,false);
            player.getInventory().setChanged();
            EATING.remove();
        }
        boolean consumed=selected.getCount()<beforeCount || food.getFoodLevel()>beforeFood
                || food.getSaturationLevel()>beforeSaturation;
        if(!consumed)NEXT_ATTEMPT.put(player,tick+10);
        return consumed;
    }
    private static int effectRank(ItemStack stack,FoodProperties food) {
        int rank=0;
        for(var possible:food.effects()) {
            if(possible.probability()<=0)continue;
            if(possible.effect().getEffect().value().getCategory()==MobEffectCategory.HARMFUL)return 2;
            rank=1;
        }
        for(var effect:stack.getOrDefault(DataComponents.SUSPICIOUS_STEW_EFFECTS,SuspiciousStewEffects.EMPTY).effects()) {
            if(effect.effect().value().getCategory()==MobEffectCategory.HARMFUL)return 2;
            rank=1;
        }
        // These foods have real special effects even when no potion component advertises them.
        if(stack.is(Items.CHORUS_FRUIT) || stack.is(Items.HONEY_BOTTLE)
                || stack.is(dev.senluo.witchcraft.time.TemporalContent.FRUIT_ITEM.get()))rank=Math.max(rank,1);
        return rank;
    }
    private record Candidate(int slot,int effects,int shortfall,double waste,double value) {
        private boolean betterThan(Candidate other) {
            if(effects!=other.effects)return effects<other.effects;
            if(shortfall!=other.shortfall)return shortfall<other.shortfall;
            if(Math.abs(waste-other.waste)>1e-5)return waste<other.waste;
            if(Math.abs(value-other.value)>1e-5)return value<other.value;
            return slot<other.slot; // Hotbar 0..8, then the ordinary main inventory.
        }
    }
}
