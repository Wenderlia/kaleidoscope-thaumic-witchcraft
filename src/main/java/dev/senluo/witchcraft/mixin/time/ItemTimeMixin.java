package dev.senluo.witchcraft.mixin.time;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.senluo.witchcraft.time.ItemTime;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.phys.Vec3;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/** Do not cancel item ticks: both peers integrate a short physical step on every tick. */
@Mixin(ItemEntity.class)
public abstract class ItemTimeMixin {
    @Shadow private int age;
    @Shadow private int pickupDelay;

    @WrapOperation(method="tick", at=@At(value="INVOKE", target="Lnet/minecraft/world/entity/item/ItemEntity;move(Lnet/minecraft/world/entity/MoverType;Lnet/minecraft/world/phys/Vec3;)V"))
    private void senluo$move(ItemEntity item, MoverType type, Vec3 movement, Operation<Void> original) {
        original.call(item, type, ItemTime.movement(item, movement));
    }
    @WrapOperation(method="tick", at={
            @At(value="INVOKE", target="Lnet/minecraft/world/entity/item/ItemEntity;applyGravity()V"),
            @At(value="INVOKE", target="Lnet/minecraft/world/entity/item/ItemEntity;setUnderwaterMovement()V"),
            @At(value="INVOKE", target="Lnet/minecraft/world/entity/item/ItemEntity;setUnderLavaMovement()V")})
    private void senluo$force(ItemEntity item, Operation<Void> original) {
        Vec3 before = item.getDeltaMovement();
        original.call(item);
        if (ItemTime.slow(item)) item.setDeltaMovement(before.lerp(item.getDeltaMovement(), .5));
    }
    @WrapOperation(method="tick", at=@At(value="INVOKE", target="Lnet/minecraft/world/phys/Vec3;multiply(DDD)Lnet/minecraft/world/phys/Vec3;", ordinal=0))
    private Vec3 senluo$drag(Vec3 velocity, double x, double y, double z, Operation<Vec3> original) {
        if (ItemTime.slow((ItemEntity)(Object)this)) return original.call(velocity, Math.sqrt(x), Math.sqrt(y), Math.sqrt(z));
        return original.call(velocity, x, y, z);
    }
    @WrapOperation(method="tick", at=@At(value="FIELD", target="Lnet/minecraft/world/entity/item/ItemEntity;age:I", opcode=Opcodes.PUTFIELD))
    private void senluo$age(ItemEntity item, int value, Operation<Void> original) {
        if (value != age + 1 || !ItemTime.retainAge(item)) original.call(item, value);
    }
    @WrapOperation(method="tick", at=@At(value="FIELD", target="Lnet/minecraft/world/entity/item/ItemEntity;pickupDelay:I", opcode=Opcodes.PUTFIELD))
    private void senluo$pickup(ItemEntity item, int value, Operation<Void> original) {
        if (value != pickupDelay - 1 || !ItemTime.pauseClock(item)) original.call(item, value);
    }
}
