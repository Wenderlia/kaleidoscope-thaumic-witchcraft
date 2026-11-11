package dev.senluo.witchcraft.mixin.time;

import dev.senluo.witchcraft.time.InventoryChanges;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(BlockEntity.class)
public abstract class InventoryReloadMixin implements InventoryChanges.Source {
    @Unique private InventoryChanges.Listeners witchcraft$changes;
    @Override public InventoryChanges.Listeners witchcraft$inventoryChanges(boolean create) {
        if (create && witchcraft$changes == null) witchcraft$changes = new InventoryChanges.Listeners();
        return witchcraft$changes;
    }
    // Loading may replace a native container's list without a slot write. setChanged is deliberately not observed.
    @Inject(method = {"loadWithComponents", "loadCustomOnly", "setRemoved"}, at = @At("RETURN"))
    private void witchcraft$reload(CallbackInfo ci) {
        if (witchcraft$changes != null) witchcraft$changes.changed();
    }
}
