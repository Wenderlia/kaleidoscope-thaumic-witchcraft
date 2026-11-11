package dev.senluo.witchcraft.mixin.brew;

import dev.senluo.witchcraft.brew.*;
import dev.senluo.witchcraft.time.TemporalContent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.UseAnim;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LivingEntity.class)
public abstract class InstantFoodMixin {
    @Shadow protected abstract void completeUsingItem();
    @Unique private boolean witchcraft$wasUsing;
    @Inject(method="startUsingItem", at=@At("HEAD"))
    private void witchcraft$beforeUse(InteractionHand hand, CallbackInfo ci) {
        witchcraft$wasUsing = ((LivingEntity)(Object)this).isUsingItem();
    }
    @Inject(method="startUsingItem", at=@At("TAIL"))
    private void witchcraft$eat(InteractionHand hand, CallbackInfo ci) {
        if (witchcraft$wasUsing || !((Object)this instanceof ServerPlayer player) || !player.isUsingItem()) return;
        UseAnim animation=player.getUseItem().getUseAnimation();
        if (animation!=UseAnim.EAT && animation!=UseAnim.DRINK) return;
        BodyTime time = ExtraTime.existing(player);
        int skipped = player.getUseItemRemainingTicks();
        if (time!=null && skipped>0 && player.getUseItem().is(TemporalContent.FRUIT_ITEM.get())) {
            // Paying the last available instant-eating tick must not erase the tier before the fruit can refill it.
            BodyTime.Hold payment=time.hold(skipped);
            if(payment!=null) {
                try {completeUsingItem();}
                finally {payment.finish(true);ExtraTime.sync(player,time);}
            }
            return;
        }
        if (time != null && skipped > 0 && time.spend(skipped)) {
            // Vanilla already accepted the use; retain its food, remainder, event and stat path.
            completeUsingItem(); ExtraTime.sync(player, time);
        }
    }
}
