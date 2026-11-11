package dev.senluo.witchcraft.mixin.brew;

import dev.senluo.witchcraft.brew.TemporalMobility;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(Entity.class)
public abstract class EnvironmentalSlowMixin {
    @ModifyVariable(method="makeStuckInBlock",at=@At("HEAD"),argsOnly=true)
    private Vec3 witchcraft$easeBinding(Vec3 multiplier, net.minecraft.world.level.block.state.BlockState state, Vec3 original) {
        // ZERO means no stuck-block adjustment. ONE still makes Entity.move clear velocity.
        if (dev.senluo.witchcraft.brew.WineAbilities.sun((Entity)(Object)this)>=6) return Vec3.ZERO;
        if (dev.senluo.witchcraft.brew.DawnWalk.ignoresThorns((Entity)(Object)this, state)) return Vec3.ZERO;
        if(!((Object)this instanceof LivingEntity living) || TemporalMobility.resistance(living)<=0)return multiplier;
        boolean up=living.getDeltaMovement().y>0 && TemporalMobility.activeUp(living);
        return new Vec3(TemporalMobility.restoreFactor(living,multiplier.x),
                up?TemporalMobility.restoreFactor(living,multiplier.y):multiplier.y,
                TemporalMobility.restoreFactor(living,multiplier.z));
    }
}
