package dev.senluo.witchcraft.mixin.brew;

import dev.senluo.witchcraft.brew.FrostGather;
import net.minecraft.world.entity.item.ItemEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Preserve mining provenance during the short attraction window; no pickup ownership changes. */
@Mixin(ItemEntity.class)
abstract class FrostItemMergeMixin {
    @Inject(method="tryToMerge",at=@At("HEAD"),cancellable=true)
    private void separate(ItemEntity other,CallbackInfo ci){
        if(FrostGather.owner((ItemEntity)(Object)this)!=null || FrostGather.owner(other)!=null)ci.cancel();
    }
}
