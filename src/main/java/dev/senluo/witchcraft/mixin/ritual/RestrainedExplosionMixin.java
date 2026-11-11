package dev.senluo.witchcraft.mixin.ritual;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.senluo.witchcraft.ritual.WorldRestraint;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Explosion.class)
public abstract class RestrainedExplosionMixin {
    @Shadow @Final private Level level;
    @Shadow @Final private Entity source;
    @Shadow @Final private DamageSource damageSource;
    @Shadow @Final @Mutable private boolean fire;
    @Shadow @Final @Mutable private Explosion.BlockInteraction blockInteraction;
    @Unique private WorldRestraint.Action witchcraft$restraint;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void witchcraft$quote(CallbackInfo ci) {
        if (witchcraft$restraint != null || level.isClientSide) return;
        witchcraft$restraint = WorldRestraint.explosion(source, damageSource,
                fire || blockInteraction == Explosion.BlockInteraction.DESTROY
                        || blockInteraction == Explosion.BlockInteraction.DESTROY_WITH_DECAY);
        if (witchcraft$restraint.constrained()) {
            blockInteraction = Explosion.BlockInteraction.KEEP;
            fire = false;
        }
    }
    @WrapOperation(method = "explode", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/Entity;hurt(Lnet/minecraft/world/damagesource/DamageSource;F)Z"))
    private boolean witchcraft$livingDamage(Entity target, DamageSource source, float amount, Operation<Boolean> original) {
        float actual = target instanceof LivingEntity && witchcraft$restraint != null ? witchcraft$restraint.damage(amount) : amount;
        return WorldRestraint.pricedDamage(source, () -> original.call(target, source, actual));
    }
}
