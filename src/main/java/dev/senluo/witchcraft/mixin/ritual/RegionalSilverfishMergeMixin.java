package dev.senluo.witchcraft.mixin.ritual;

import dev.senluo.witchcraft.region.RegionProtection;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.ai.goal.RandomStrollGoal;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "net.minecraft.world.entity.monster.Silverfish$SilverfishMergeWithStoneGoal")
public abstract class RegionalSilverfishMergeMixin extends RandomStrollGoal {
    @Shadow private Direction selectedDirection;
    @Shadow private boolean doMerge;
    protected RegionalSilverfishMergeMixin() { super(null, 0); }
    @Inject(method = "start", at = @At("HEAD"), cancellable = true)
    private void witchcraft$infest(CallbackInfo ci) {
        if (doMerge && selectedDirection != null && RegionProtection.mobEdit(mob.level(),
                BlockPos.containing(mob.getX(), mob.getY() + .5, mob.getZ()).relative(selectedDirection), mob)) ci.cancel();
    }
}
