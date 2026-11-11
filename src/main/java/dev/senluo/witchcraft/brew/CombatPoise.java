package dev.senluo.witchcraft.brew;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;

/** Grade two pays for each effective intervention, independently of maintenance. */
public final class CombatPoise {
    public static final int MIN_GRADE = 2;
    public static final int KNOCKBACK_COST = 20;
    private CombatPoise() {}
    private static boolean pay(ServerPlayer player,int ticks,long nutrition) {
        return AbilityCosts.pay(player,MIN_GRADE,ticks,nutrition);
    }
    public static float knockback(LivingEntity entity,float strength) {
        if(!(entity instanceof ServerPlayer player) || !Float.isFinite(strength) || strength<=0
                || player.getAttributeValue(Attributes.KNOCKBACK_RESISTANCE)>=1) return strength;
        BodyTime time=ExtraTime.existing(player);
        // Capture the tier before payment: spending the last units clears the ledger.
        int grade=time==null?0:time.grade();
        float factor=grade>=6?.03125F:grade>=5?.0625F:grade>=4?.125F:grade>=3?.25F:.5F;
        return pay(player,KNOCKBACK_COST,AbilityCosts.STEADY)?strength*factor:strength;
    }
    public static int shieldCooldown(ServerPlayer player,int ticks) {
        if(ticks<=0)return ticks;
        // Round retained cooldown upward so fractional ticks never grant extra shortening.
        BodyTime time=ExtraTime.existing(player);
        int grade=time==null?0:time.grade();
        int remaining=grade>=4?0:grade==3?ticks/4+(ticks%4==0?0:1):ticks-ticks/2;
        int skipped=ticks-remaining;
        return skipped>0 && pay(player,skipped,AbilityCosts.SHIELD)?ticks-skipped:ticks;
    }
}
