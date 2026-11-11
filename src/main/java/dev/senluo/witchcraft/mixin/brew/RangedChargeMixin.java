package dev.senluo.witchcraft.mixin.brew;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import dev.senluo.witchcraft.brew.TimeRangedCharge;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(LivingEntity.class)
public abstract class RangedChargeMixin {
    @Unique private long witchcraft$lastChargeTick=Long.MIN_VALUE;

    @ModifyExpressionValue(method="updateUsingItem",at=@At(value="INVOKE",
            target="Lnet/neoforged/neoforge/event/EventHooks;onItemUseTick(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/item/ItemStack;I)I"))
    private int witchcraft$advanceCharge(int remaining) {
        if(!((Object)this instanceof Player player))return remaining;
        long tick=player.level().getGameTime();
        if(witchcraft$lastChargeTick==tick)return remaining;
        witchcraft$lastChargeTick=tick;
        return TimeRangedCharge.advance(player,player.getUseItem(),remaining);
    }
}
