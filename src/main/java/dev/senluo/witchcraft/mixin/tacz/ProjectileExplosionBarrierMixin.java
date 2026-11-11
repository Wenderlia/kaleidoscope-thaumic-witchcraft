package dev.senluo.witchcraft.mixin.tacz;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.tacz.guns.util.block.ProjectileExplosion;
import dev.senluo.witchcraft.compat.tacz.TaczBarrier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(value = ProjectileExplosion.class, remap = false)
public abstract class ProjectileExplosionBarrierMixin {
    @Shadow @Final private Level level;

    @WrapMethod(method = "explode")
    private void witchcraft$blast(Operation<Void> original) {
        TaczBarrier.explode(level, (Explosion)(Object)this, () -> original.call());
    }

    @WrapOperation(method = "explode", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/Entity;ignoreExplosion(Lnet/minecraft/world/level/Explosion;)Z"))
    private boolean witchcraft$unsampled(Entity target, Explosion explosion, Operation<Boolean> original) {
        return original.call(target, explosion) || TaczBarrier.blocksUnsampledExplosion(target, explosion);
    }
}
