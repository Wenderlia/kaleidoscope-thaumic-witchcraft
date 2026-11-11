package dev.senluo.witchcraft.mixin.tacz;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.tacz.guns.entity.EntityKineticBullet;
import com.tacz.guns.util.TacHitResult;
import dev.senluo.witchcraft.compat.tacz.TaczBarrier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(value = EntityKineticBullet.class, remap = false)
public abstract class KineticBulletBarrierMixin {
    @WrapMethod(method = "tick")
    private void witchcraft$flight(Operation<Void> original) {
        TaczBarrier.tick((Entity)(Object)this, () -> original.call());
    }

    @WrapMethod(method = "onHitEntity")
    private void witchcraft$entity(TacHitResult hit, Vec3 from, Vec3 to, Operation<Void> original) {
        Entity bullet = (Entity)(Object)this;
        if (!TaczBarrier.blocks(bullet, from, hit))
            TaczBarrier.impact(bullet, () -> original.call(hit, from, to));
    }

    @WrapMethod(method = "onHitBlock")
    private void witchcraft$block(BlockHitResult hit, Vec3 from, Vec3 to, Operation<Void> original) {
        if (hit.getType() == HitResult.Type.MISS) { original.call(hit, from, to); return; }
        Entity bullet = (Entity)(Object)this;
        if (!TaczBarrier.blocks(bullet, from, hit))
            TaczBarrier.impact(bullet, () -> original.call(hit, from, to));
    }
}
