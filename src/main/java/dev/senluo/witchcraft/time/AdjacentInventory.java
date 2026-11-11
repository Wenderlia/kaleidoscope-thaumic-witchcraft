package dev.senluo.witchcraft.time;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemHandlerHelper;
import net.neoforged.neoforge.items.ItemStackHandler;

/** Short-lived container transaction boundary shared by fruit and crop automation. */
final class AdjacentInventory {
    final ServerLevel level;
    final BlockPos pos;
    final IItemHandler inventory;
    private final Direction face;
    private final Block block;
    private final BlockEntity entity;

    private AdjacentInventory(ServerLevel level, BlockPos pos, Direction face) {
        this.level = level; this.pos = pos.immutable(); this.face = face;
        block = level.getBlockState(pos).getBlock(); entity = level.getBlockEntity(pos);
        // Providers may return fresh wrappers or invalidate an old handler without replacing the BE.
        // Re-query at each inventory operation; handler reference identity is not a capability contract.
        inventory = new IItemHandler() {
            private IItemHandler slot(int index) {
                IItemHandler handler = current();
                return handler != null && index >= 0 && index < handler.getSlots() ? handler : null;
            }
            public int getSlots() { IItemHandler handler = current(); return handler == null ? 0 : handler.getSlots(); }
            public ItemStack getStackInSlot(int index) {
                IItemHandler handler = slot(index); return handler == null ? ItemStack.EMPTY : handler.getStackInSlot(index);
            }
            public ItemStack insertItem(int index, ItemStack stack, boolean simulate) {
                IItemHandler handler = slot(index); return handler == null ? stack : handler.insertItem(index, stack, simulate);
            }
            public ItemStack extractItem(int index, int amount, boolean simulate) {
                IItemHandler handler = slot(index); return handler == null ? ItemStack.EMPTY : handler.extractItem(index, amount, simulate);
            }
            public int getSlotLimit(int index) { IItemHandler handler = slot(index); return handler == null ? 0 : handler.getSlotLimit(index); }
            public boolean isItemValid(int index, ItemStack stack) {
                IItemHandler handler = slot(index); return handler != null && handler.isItemValid(index, stack);
            }
        };
    }

    static List<AdjacentInventory> around(LivingEntity core) {
        List<AdjacentInventory> result = new ArrayList<>();
        if (!(core.getLevel() instanceof ServerLevel level)) return result;
        Direction[] faces = Direction.values();
        for (int n = 0; n < faces.length; n++) {
            Direction direction = faces[Math.floorMod(core.fruitContainerCursor + n, faces.length)];
            BlockPos pos = core.getBlockPos().relative(direction);
            if (!level.hasChunkAt(pos) || !level.shouldTickBlocksAt(pos)) continue;
            Direction face = direction.getOpposite();
            IItemHandler items = level.getCapability(Capabilities.ItemHandler.BLOCK, pos, face);
            if (items != null) result.add(new AdjacentInventory(level, pos, face));
        }
        return result;
    }

    private IItemHandler current() {
        return level.hasChunkAt(pos) && level.getBlockState(pos).is(block) && level.getBlockEntity(pos) == entity
                ? level.getCapability(Capabilities.ItemHandler.BLOCK, pos, face) : null;
    }
    boolean present() { return current() != null; }

    boolean fits(List<ItemStack> products) {
        if (!present()) return false;
        ItemStackHandler copy = new ItemStackHandler(inventory.getSlots()) {
            @Override public int getSlotLimit(int slot) { return inventory.getSlotLimit(slot); }
            @Override public boolean isItemValid(int slot, ItemStack stack) { return inventory.isItemValid(slot, stack); }
        };
        for (int slot = 0; slot < copy.getSlots(); slot++) copy.setStackInSlot(slot, inventory.getStackInSlot(slot).copy());
        for (ItemStack stack : products) {
            if (!ItemHandlerHelper.insertItemStacked(inventory, stack.copy(), true).isEmpty()
                    || !ItemHandlerHelper.insertItemStacked(copy, stack.copy(), false).isEmpty()) return false;
        }
        return present();
    }

    ItemStack take(int slot, ItemStack expected) {
        if (!present()) return ItemStack.EMPTY;
        ItemStack result = inventory.extractItem(slot, 1, false);
        if (ItemStack.matches(expected, result) && present()) return result;
        returnTo(slot, result);
        return ItemStack.EMPTY;
    }

    void returnTo(int slot, ItemStack stack) {
        if (stack.isEmpty()) return;
        IItemHandler target = current();
        receive(target != null && slot < target.getSlots() ? target.insertItem(slot, stack, false) : stack);
    }

    void receive(ItemStack stack) {
        if (stack.isEmpty()) return;
        IItemHandler target = current();
        ItemStack rest = target == null ? stack : ItemHandlerHelper.insertItemStacked(target, stack, false);
        // Already-owned items survive callbacks that replace the container or change its filters.
        while (!rest.isEmpty()) {
            ItemEntity item = new ItemEntity(level, pos.getX() + .5, pos.getY() + .5, pos.getZ() + .5,
                    rest.split(rest.getMaxStackSize()));
            item.setDefaultPickUpDelay(); level.addFreshEntity(item);
        }
    }
}
