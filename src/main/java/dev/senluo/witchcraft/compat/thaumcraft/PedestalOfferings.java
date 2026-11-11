package dev.senluo.witchcraft.compat.thaumcraft;

import dev.senluo.witchcraft.ritual.RitualMaterials;
import dev.tc4port.thaumcraft.api.ThaumcraftTags;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.items.IItemHandler;

/** The host tag identifies displays; the sided item capability owns extraction, counts and sync. */
final class PedestalOfferings {
    private PedestalOfferings() {}
    static void register() {
        RitualMaterials.registerDisplay(entity -> {
            if (!entity.getBlockState().is(ThaumcraftTags.INFUSION_PEDESTALS) || entity.getLevel() == null) return null;
            return new RitualMaterials.Display() {
                // Resolve for each synchronous operation; never retain a capability through replacement.
                private IItemHandler inventory() {
                    if (entity.isRemoved() || entity.getLevel() == null) return null;
                    var inventory = entity.getLevel().getCapability(Capabilities.ItemHandler.BLOCK,
                            entity.getBlockPos(), entity.getBlockState(), entity, Direction.UP);
                    return inventory != null && inventory.getSlots() > 0 ? inventory : null;
                }
                @Override public ItemStack stack() {
                    var inventory = inventory();
                    return inventory == null ? ItemStack.EMPTY : inventory.getStackInSlot(0);
                }
                @Override public boolean canTake() {
                    var inventory = inventory();
                    return inventory != null && inventory.extractItem(0, 1, true).getCount() == 1;
                }
                @Override public ItemStack takeOne() {
                    var inventory = inventory();
                    return inventory == null ? ItemStack.EMPTY : inventory.extractItem(0, 1, false);
                }
                @Override public boolean returnOne(ItemStack stack) {
                    var inventory = inventory();
                    return inventory != null && stack.getCount() == 1 && inventory.getStackInSlot(0).isEmpty()
                            && inventory.insertItem(0, stack, true).isEmpty()
                            && inventory.insertItem(0, stack, false).isEmpty();
                }
            };
        });
    }
}
