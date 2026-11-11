package dev.senluo.witchcraft.mixin.brew;

import dev.senluo.witchcraft.brew.FrostGather;
import net.minecraft.world.entity.ExperienceOrb;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ExperienceOrb.class)
public abstract class FrostExperienceMixin {
    @Inject(method="canMerge(Lnet/minecraft/world/entity/ExperienceOrb;II)Z",at=@At("HEAD"),cancellable=true)
    private static void witchcraft$separateOwned(ExperienceOrb orb,int id,int value,CallbackInfoReturnable<Boolean> cir){
        if(FrostGather.owner(orb)!=null)cir.setReturnValue(false);
    }
    @Inject(method="canMerge(Lnet/minecraft/world/entity/ExperienceOrb;)Z",at=@At("HEAD"),cancellable=true)
    private void witchcraft$noForeignMerge(ExperienceOrb other,CallbackInfoReturnable<Boolean> cir){
        if(FrostGather.owner((ExperienceOrb)(Object)this)!=null)cir.setReturnValue(false);
    }
}
