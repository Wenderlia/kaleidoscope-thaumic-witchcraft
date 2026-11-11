package dev.senluo.witchcraft.mixin.time;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import dev.senluo.witchcraft.time.InventoryChanges;
import java.util.Objects;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** In-place repairs may never replace a slot or mark its container dirty. */
@Mixin(ItemStack.class)
public abstract class InventoryStackMixin implements InventoryChanges.Source {
    @Unique private InventoryChanges.Listeners witchcraft$changes;
    @Override public InventoryChanges.Listeners witchcraft$inventoryChanges(boolean create) {
        if (create && witchcraft$changes == null) witchcraft$changes = new InventoryChanges.Listeners();
        return witchcraft$changes;
    }
    @ModifyReturnValue(method = "set", at = @At("RETURN"))
    private Object witchcraft$component(Object before, DataComponentType<?> type, Object value) {
        if (witchcraft$changes != null && !Objects.equals(before, value)) witchcraft$changes.changed();
        return before;
    }
    @ModifyReturnValue(method = "remove", at = @At("RETURN"))
    private Object witchcraft$removeComponent(Object before) {
        if (witchcraft$changes != null && before != null) witchcraft$changes.changed();
        return before;
    }
    @ModifyVariable(method = "setCount", at = @At("HEAD"), argsOnly = true)
    private int witchcraft$count(int count) {
        if (witchcraft$changes != null && ((ItemStack)(Object)this).getCount() != count) witchcraft$changes.changed();
        return count;
    }
    @Inject(method = {"applyComponentsAndValidate", "applyComponents"}, at = @At("RETURN"))
    private void witchcraft$components(CallbackInfo ci) {
        if (witchcraft$changes != null) witchcraft$changes.changed();
    }
}
