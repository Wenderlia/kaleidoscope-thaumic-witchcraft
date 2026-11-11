package dev.senluo.witchcraft.mixin.time;

import dev.senluo.witchcraft.time.TreeSignMemory;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The native sign setters share this final update; no edit-packet or global dirty-state interception. */
@Mixin(SignBlockEntity.class)
public abstract class TreeSignMemoryMixin {
    @Inject(method = "markUpdated", at = @At("TAIL"))
    private void senluo$edited(CallbackInfo ci) { TreeSignMemory.changed((SignBlockEntity)(Object)this); }
    @Inject(method = "loadAdditional", at = @At("TAIL"))
    private void senluo$loaded(CompoundTag tag, HolderLookup.Provider registries, CallbackInfo ci) {
        TreeSignMemory.changed((SignBlockEntity)(Object)this);
    }
}
