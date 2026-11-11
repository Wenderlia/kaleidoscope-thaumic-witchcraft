package dev.senluo.witchcraft.mixin.brew;

import dev.senluo.witchcraft.brew.*;
import net.minecraft.core.Holder;
import net.minecraft.network.protocol.game.ClientboundUpdateMobEffectPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.*;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(MobEffectInstance.class)
public abstract class NegativeTimeMixin {
    @Shadow private int duration;
    @Shadow private int amplifier;
    @Shadow private MobEffectInstance hiddenEffect;
    @Shadow public abstract Holder<MobEffect> getEffect();
    @Shadow abstract void setDetailsFrom(MobEffectInstance other);
    @Inject(method="tick", at=@At("HEAD"))
    private void witchcraft$trim(LivingEntity entity, Runnable changed, CallbackInfoReturnable<Boolean> ci) {
        if (!(entity instanceof ServerPlayer player) || getEffect().value().getCategory() != MobEffectCategory.HARMFUL) return;
        BodyTime time = ExtraTime.existing(player);
        if (time == null || !time.active()) return;
        boolean shortened = false;
        // Do not fast-forward effect ticks: poison/wither must never deliver skipped damage.
        for (int depth = 0; depth < 128 && duration >= 0 && time.active(); depth++) {
            int removed = time.payNegative(duration, amplifier);
            duration -= removed; shortened |= removed > 0;
            if (duration != 0 || hiddenEffect == null) break;
            MobEffectInstance next = hiddenEffect;
            hiddenEffect = ((NegativeTimeMixin)(Object)next).hiddenEffect;
            setDetailsFrom(next); changed.run();
        }
        if (shortened && duration > 0) player.connection.send(new ClientboundUpdateMobEffectPacket(player.getId(), (MobEffectInstance)(Object)this, true));
    }
}
