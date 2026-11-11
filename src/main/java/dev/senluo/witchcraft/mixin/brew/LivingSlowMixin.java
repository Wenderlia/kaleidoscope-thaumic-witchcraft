package dev.senluo.witchcraft.mixin.brew;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.senluo.witchcraft.brew.TemporalMobility;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(LivingEntity.class)
public abstract class LivingSlowMixin {
    @Shadow protected abstract float getJumpPower();
    @Unique private LivingEntity witchcraft$self() {return (LivingEntity)(Object)this;}
    @ModifyReturnValue(method="getBlockSpeedFactor",at=@At("RETURN"))
    private float witchcraft$easeGround(float factor) {
        // This return already includes vanilla movement-efficiency / Soul Speed handling.
        return (float)TemporalMobility.restoreFactor(witchcraft$self(),factor);
    }
    @ModifyReturnValue(method="handleOnClimbable",at=@At("RETURN"))
    private Vec3 witchcraft$easeClimb(Vec3 result,Vec3 input) {
        LivingEntity self=witchcraft$self();
        if(!self.onClimbable() || TemporalMobility.resistance(self)<=0)return result;
        return new Vec3(TemporalMobility.restoreLimit(self,result.x,input.x),result.y,
                TemporalMobility.restoreLimit(self,result.z,input.z));
    }
    @ModifyReturnValue(method="handleRelativeFrictionAndCalculateMovement",at=@At("RETURN"))
    private Vec3 witchcraft$easeUpClimb(Vec3 result) {
        LivingEntity self=witchcraft$self();
        if(result.y<=0 || !self.onClimbable() || !TemporalMobility.activeUp(self)
                || TemporalMobility.resistance(self)<=0)return result;
        // The native upward climb impulse is 0.2; use this entity's jump strength as its
        // unconstrained upward propulsion reference, never alter the downward clamp.
        return new Vec3(result.x,TemporalMobility.restoreLimit(self,result.y,Math.max(result.y,getJumpPower())),result.z);
    }
    @WrapOperation(method="travel",at=@At(value="INVOKE",target="Lnet/minecraft/world/entity/LivingEntity;moveRelative(FLnet/minecraft/world/phys/Vec3;)V"))
    private void witchcraft$easeFluidInput(LivingEntity self,float acceleration,Vec3 input,Operation<Void> original) {
        if((self.isInWater() || self.isInLava()) && TemporalMobility.resistance(self)>0) {
            float eased=(float)TemporalMobility.restoreLimit(self,acceleration,Math.max(acceleration,self.getSpeed()));
            // Keep vertical propulsion unchanged unless the player is actively ascending.
            if(eased>0 && input.y!=0 && !TemporalMobility.activeUp(self))input=new Vec3(input.x,input.y*acceleration/eased,input.z);
            acceleration=eased;
        }
        original.call(self,acceleration,input);
    }
    @WrapOperation(method="travel",at=@At(value="INVOKE",target="Lnet/minecraft/world/phys/Vec3;multiply(DDD)Lnet/minecraft/world/phys/Vec3;"))
    private Vec3 witchcraft$easeFluidDrag(Vec3 velocity,double x,double y,double z,Operation<Vec3> original) {
        LivingEntity self=witchcraft$self();
        if((self.isInWater() || self.isInLava()) && TemporalMobility.resistance(self)>0) {
            x=TemporalMobility.restoreFactor(self,x);z=TemporalMobility.restoreFactor(self,z);
            if(velocity.y>0 && TemporalMobility.activeUp(self))y=TemporalMobility.restoreFactor(self,y);
        }
        return original.call(velocity,x,y,z);
    }
    @WrapOperation(method="travel",at=@At(value="INVOKE",target="Lnet/minecraft/world/phys/Vec3;scale(D)Lnet/minecraft/world/phys/Vec3;"))
    private Vec3 witchcraft$easeDeepLava(Vec3 velocity,double factor,Operation<Vec3> original) {
        LivingEntity self=witchcraft$self();
        Vec3 result=original.call(velocity,factor);
        if(!self.isInLava() || TemporalMobility.resistance(self)<=0)return result;
        return new Vec3(TemporalMobility.restoreLimit(self,result.x,velocity.x),
                velocity.y>0 && TemporalMobility.activeUp(self)?TemporalMobility.restoreLimit(self,result.y,velocity.y):result.y,
                TemporalMobility.restoreLimit(self,result.z,velocity.z));
    }
}
