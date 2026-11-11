package dev.senluo.witchcraft.mixin.slash;

import dev.senluo.witchcraft.slash.SlashEntity;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.tags.TagKey;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** A blade extends the caster's weapon attack. Preserve TC's cast-source subclass and captured modifiers. */
@Mixin(DamageSource.class)
public abstract class SlashDamageSourceMixin {
    /** Standard hit-window bypass only; never bypass armor, resistance or arbitrary event cancellation. */
    @Inject(method = "is(Lnet/minecraft/tags/TagKey;)Z", at = @At("HEAD"), cancellable = true)
    private void witchcraft$penetratingHit(TagKey<DamageType> tag, CallbackInfoReturnable<Boolean> ci) {
        if (tag.equals(DamageTypeTags.BYPASSES_COOLDOWN)
                && ((DamageSource)(Object)this).getDirectEntity() instanceof SlashEntity slash
                && (slash.penetrating() || dev.senluo.witchcraft.slash.SlashFission.bypassesCooldown((DamageSource)(Object)this)))
            ci.setReturnValue(true);
    }
    @Inject(method = "isDirect", at = @At("HEAD"), cancellable = true)
    private void witchcraft$weaponStrike(CallbackInfoReturnable<Boolean> ci) {
        if (((DamageSource)(Object)this).getDirectEntity() instanceof SlashEntity) ci.setReturnValue(true);
    }
}
