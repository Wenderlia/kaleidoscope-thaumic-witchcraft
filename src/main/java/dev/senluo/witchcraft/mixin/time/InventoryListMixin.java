package dev.senluo.witchcraft.mixin.time;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import dev.senluo.witchcraft.time.InventoryChanges;
import net.minecraft.core.NonNullList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Captures the actual backing storage, including both halves of a double chest and wrapped handlers. */
@Mixin(NonNullList.class)
public abstract class InventoryListMixin implements InventoryChanges.Source {
    @Unique private InventoryChanges.Listeners witchcraft$changes;
    @Override public InventoryChanges.Listeners witchcraft$inventoryChanges(boolean create) {
        if (create && witchcraft$changes == null) witchcraft$changes = new InventoryChanges.Listeners();
        return witchcraft$changes;
    }
    @ModifyReturnValue(method = "get", at = @At("RETURN"))
    private Object witchcraft$read(Object value) {
        InventoryChanges.readList(this, value);
        return value;
    }
    @ModifyReturnValue(method = "set", at = @At("RETURN"))
    private Object witchcraft$replace(Object before, int slot, Object value) {
        if (witchcraft$changes != null) witchcraft$changes.replaced(before, value);
        return before;
    }
    @Inject(method = "add(ILjava/lang/Object;)V", at = @At("RETURN"))
    private void witchcraft$add(int slot, Object value, CallbackInfo ci) {
        if (witchcraft$changes != null) witchcraft$changes.changed();
    }
    @ModifyReturnValue(method = "remove(I)Ljava/lang/Object;", at = @At("RETURN"))
    private Object witchcraft$remove(Object before) {
        if (witchcraft$changes != null) witchcraft$changes.changed();
        return before;
    }
    // Fixed-size clear uses set; variable-size clear uses remove. Both are covered above.
}
