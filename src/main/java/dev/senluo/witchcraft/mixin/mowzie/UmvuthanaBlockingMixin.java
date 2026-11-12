package dev.senluo.witchcraft.mixin.mowzie;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.senluo.witchcraft.slash.SlashCombat;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

/** Mowzie 1.8.2 checks BYPASSES_ARMOR for talon blocking before entering normal damage handling. */
@Pseudo
@Mixin(targets = "com.bobmowzie.mowziesmobs.server.entity.umvuthana.EntityUmvuthana", remap = false)
public abstract class UmvuthanaBlockingMixin {
    @WrapOperation(method = "hurt(Lnet/minecraft/world/damagesource/DamageSource;F)Z",
            at = @At(value = "INVOKE", ordinal = 0,
                    target = "Lnet/minecraft/world/damagesource/DamageSource;is(Lnet/minecraft/tags/TagKey;)Z"))
    private boolean witchcraft$blockingPolicy(DamageSource source, TagKey<DamageType> tag,
                                              Operation<Boolean> original) {
        boolean bypass = original.call(source, tag);
        // Adapt this one blocking decision; never give the damage source global armor bypass.
        return bypass || tag.equals(DamageTypeTags.BYPASSES_ARMOR) && SlashCombat.bypassesBlocking(source);
    }
}
