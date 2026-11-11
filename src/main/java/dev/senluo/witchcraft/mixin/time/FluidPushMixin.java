package dev.senluo.witchcraft.mixin.time;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.senluo.witchcraft.time.FluidTime;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
@Mixin(Entity.class)
public abstract class FluidPushMixin {
    @WrapMethod(method="updateFluidHeightAndDoFluidPushing()V")
    private void senluo$push(Operation<Void> original) {
        Entity self=(Entity)(Object)this;Vec3 before=self.getDeltaMovement();
        original.call();self.setDeltaMovement(FluidTime.adjustPush(self,before,self.getDeltaMovement()));
    }
}
