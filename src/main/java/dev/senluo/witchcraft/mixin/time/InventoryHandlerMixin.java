package dev.senluo.witchcraft.mixin.time;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import dev.senluo.witchcraft.time.InventoryChanges;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.ItemStackHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ItemStackHandler.class)
public abstract class InventoryHandlerMixin implements InventoryChanges.Source {
    @Unique private InventoryChanges.Listeners witchcraft$changes;
    @Override public InventoryChanges.Listeners witchcraft$inventoryChanges(boolean create) {
        if (create && witchcraft$changes == null) witchcraft$changes = new InventoryChanges.Listeners();
        return witchcraft$changes;
    }
    @ModifyReturnValue(method = "getStackInSlot", at = @At("RETURN"))
    private ItemStack witchcraft$read(ItemStack value) { InventoryChanges.read(this); return value; }
    // setSize/deserialize replace the list itself; ordinary slot writes already notify through NonNullList.
    @Inject(method = {"setSize", "deserializeNBT(Lnet/minecraft/core/HolderLookup$Provider;Lnet/minecraft/nbt/CompoundTag;)V"}, at = @At("RETURN"))
    private void witchcraft$reload(CallbackInfo ci) {
        if (witchcraft$changes != null) witchcraft$changes.changed();
    }
}
