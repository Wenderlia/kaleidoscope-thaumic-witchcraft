package dev.senluo.witchcraft.mixin.ritual;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.senluo.witchcraft.region.RegionProtection;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.goal.EatBlockGoal;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Both grass targets share the mob's column. Preserve eating/wool behavior as vanilla does with griefing off. */
@Mixin(EatBlockGoal.class)
public abstract class RegionalGrazingMixin {
    @WrapOperation(method = "tick", at = @At(value = "INVOKE", target = "Lnet/neoforged/neoforge/event/EventHooks;canEntityGrief(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/entity/Entity;)Z"), require = 2)
    private boolean witchcraft$graze(Level level, Entity actor, Operation<Boolean> original) {
        return !RegionProtection.mobEdit(level, actor.blockPosition(), actor) && original.call(level, actor);
    }
}
