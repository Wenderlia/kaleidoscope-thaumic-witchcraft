package dev.senluo.witchcraft.brew;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemStack;

/** Advance accepted charge time, leaving ammunition, release, power and enchantments to the item. */
public final class TimeRangedCharge {
    private TimeRangedCharge() {}

    public static int advance(Player player,ItemStack stack,int remaining) {
        if(remaining<=0 || !player.isAlive() || player.isSpectator() || !player.isUsingItem())return remaining;
        int fullCharge;
        if(stack.getItem() instanceof CrossbowItem) {
            if(CrossbowItem.isCharged(stack))return remaining;
            fullCharge=CrossbowItem.getChargeDuration(stack,player);
        } else if(stack.getItem() instanceof BowItem)fullCharge=20;
        else return remaining;
        if(fullCharge<=0)return remaining;
        BodyTime time=player.level().isClientSide?null:ExtraTime.existing(player);
        var effect=player.getEffect(BrewContent.YEARS_ABUNDANCE);
        int grade=player.level().isClientSide?(effect==null?0:effect.getAmplifier()+1)
                :(time==null || !time.active()?0:time.grade());
        if(grade<5)return remaining;
        int multiplier=TimeMelee.multiplier(grade)*TimeStride.completedMultiplier(player);
        int elapsed=Math.max(0,stack.getUseDuration(player)-remaining);
        // Vanilla supplies the ordinary tick after this hook; stop charging fees at full power.
        int extra=Math.min(multiplier-1,Math.max(0,fullCharge-elapsed-1));
        if(extra<=0)return remaining;
        if(player.level().isClientSide) {
            if(!BodyTime.displayCanSpend(effect.getDuration(),extra))return remaining;
        } else {
            if(time==null || !time.spend(extra))return remaining;
            if(!time.active() && player instanceof ServerPlayer server)ExtraTime.sync(server,time);
        }
        return remaining-extra;
    }
}
