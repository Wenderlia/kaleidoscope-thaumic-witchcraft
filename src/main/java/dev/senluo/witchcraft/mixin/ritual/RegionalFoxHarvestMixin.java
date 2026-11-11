package dev.senluo.witchcraft.mixin.ritual;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.senluo.witchcraft.region.RegionProtection;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.goal.MoveToBlockGoal;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(targets = "net.minecraft.world.entity.animal.Fox$FoxEatBerriesGoal")
public abstract class RegionalFoxHarvestMixin extends MoveToBlockGoal {
    protected RegionalFoxHarvestMixin() { super(null, 0, 0); }
    @WrapOperation(method = "onReachedTarget", at = @At(value = "INVOKE", target = "Lnet/neoforged/neoforge/event/EventHooks;canEntityGrief(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/entity/Entity;)Z"))
    private boolean witchcraft$berries(Level level, Entity actor, Operation<Boolean> original) {
        return !RegionProtection.mobEdit(level, blockPos, actor) && original.call(level, actor);
    }
}
