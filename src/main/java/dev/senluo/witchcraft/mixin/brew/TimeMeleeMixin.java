package dev.senluo.witchcraft.mixin.brew;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.senluo.witchcraft.brew.TimeMelee;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.damagesource.DamageSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(Player.class)
public abstract class TimeMeleeMixin {
    @WrapMethod(method="attack")
    private void witchcraft$meleeScope(Entity target,Operation<Void> original) {
        try(var attempt=TimeMelee.begin((Player)(Object)this,target)) {original.call(target);}
    }
    @WrapMethod(method="getAttackStrengthScale")
    private float witchcraft$attackStrength(float partial,Operation<Float> original) {
        float normal=original.call(partial);
        Player self=(Player)(Object)this;
        TimeMelee.prepareFullStrength(self,normal);
        return TimeMelee.strength(self,normal);
    }
    @WrapOperation(method="attack",at=@At(value="INVOKE",
            target="Lnet/minecraft/world/entity/Entity;hurt(Lnet/minecraft/world/damagesource/DamageSource;F)Z"))
    private boolean witchcraft$acceptedHit(Entity target,DamageSource source,float amount,Operation<Boolean> original) {
        return TimeMelee.hit(target,()->original.call(target,source,amount));
    }
}
