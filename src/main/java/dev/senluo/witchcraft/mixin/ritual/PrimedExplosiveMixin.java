package dev.senluo.witchcraft.mixin.ritual;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import dev.senluo.witchcraft.ritual.TntOrigins;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.TntBlock;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(TntBlock.class)
public abstract class PrimedExplosiveMixin {
    @WrapOperation(method = "explode(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/entity/LivingEntity;)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;addFreshEntity(Lnet/minecraft/world/entity/Entity;)Z"))
    private static boolean witchcraft$source(Level level, Entity entity, Operation<Boolean> original, @Local(argsOnly = true) BlockPos origin) {
        TntOrigins.primed(entity, origin, entity instanceof PrimedTnt tnt ? tnt.getOwner() : null);
        return original.call(level, entity);
    }
    @WrapOperation(method = "wasExploded", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;addFreshEntity(Lnet/minecraft/world/entity/Entity;)Z"))
    private boolean witchcraft$chainSource(Level level, Entity entity, Operation<Boolean> original, @Local(argsOnly = true) BlockPos origin) {
        TntOrigins.primed(entity, origin, entity instanceof PrimedTnt tnt ? tnt.getOwner() : null);
        return original.call(level, entity);
    }
}
