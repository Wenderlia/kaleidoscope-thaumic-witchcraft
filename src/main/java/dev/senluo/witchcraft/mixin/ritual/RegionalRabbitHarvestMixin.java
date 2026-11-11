package dev.senluo.witchcraft.mixin.ritual;

import dev.senluo.witchcraft.region.RegionProtection;
import net.minecraft.world.entity.ai.goal.MoveToBlockGoal;
import net.minecraft.world.entity.animal.Rabbit;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "net.minecraft.world.entity.animal.Rabbit$RaidGardenGoal")
public abstract class RegionalRabbitHarvestMixin extends MoveToBlockGoal {
    @Shadow @Final private Rabbit rabbit;
    protected RegionalRabbitHarvestMixin() { super(null, 0, 0); }
    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void witchcraft$carrots(CallbackInfo ci) {
        if (RegionProtection.mobEdit(rabbit.level(), blockPos.above(), rabbit)) ci.cancel();
    }
}
