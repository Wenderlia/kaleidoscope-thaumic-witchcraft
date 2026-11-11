package dev.senluo.witchcraft.time;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.capabilities.BlockCapabilityCache;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.items.IItemHandler;

/** Tree policy shared by fruit and hanging-crop harvesting; inventory notifications contain no tree policy. */
final class TreeShears {
    private boolean rejectedWornTool, foundSafeTool;
    private long changes;
    private InventoryChanges.Observation pause;
    private List<BlockCapabilityCache<IItemHandler, Direction>> neighbors;

    private static boolean safe(ItemStack tool) {
        return !tool.isDamageableItem() || tool.getMaxDamage() - tool.getDamageValue() > 1;
    }

    boolean usable(ItemStack tool) {
        if (!FruitHarvest.isShears(tool)) return false;
        if (safe(tool)) return true;
        rejectedWornTool = true;
        return false;
    }

    boolean awake() {
        if (pause != null && pause.invalidated()) { pause.close(); pause = null; }
        return pause == null;
    }

    void foundUsable() { foundSafeTool = true; }

    private void wake() {
        changes++;
        if (pause != null) pause.invalidate();
    }

    /** Only after a rejected tool, not every tick or every ordinary harvest failure. */
    void settle(LivingEntity core) {
        boolean needsObservation = rejectedWornTool && !foundSafeTool;
        rejectedWornTool = false; foundSafeTool = false;
        if (!needsObservation) return;
        if (!awake() || core.isRemoved() || !(core.getLevel() instanceof ServerLevel level)) return;
        if (neighbors == null) {
            neighbors = new ArrayList<>(6);
            for (Direction side : Direction.values()) neighbors.add(BlockCapabilityCache.create(
                    Capabilities.ItemHandler.BLOCK, level, core.getBlockPos().relative(side), side.getOpposite(),
                    () -> !core.isRemoved(), this::wake));
        }
        // Re-arm invalidation listeners once for this observation. The host holds weak listener references.
        for (var neighbor : neighbors) neighbor.getCapability();
        long before = changes;
        boolean[] tools = new boolean[2]; // safe extractable tool, worn tool
        InventoryChanges.Observation observation = InventoryChanges.observe(FruitHarvest::isShears, () -> {
            for (var neighbor : neighbors) {
                IItemHandler items = neighbor.getCapability();
                if (items == null) continue;
                InventoryChanges.read(items);
                InventoryChanges.read(level.getBlockEntity(neighbor.pos()));
                for (int slot = 0; slot < items.getSlots(); slot++) {
                    ItemStack stored = items.getStackInSlot(slot);
                    if (!FruitHarvest.isShears(stored)) continue;
                    InventoryChanges.read(stored);
                    if (!items.isItemValid(slot, stored)) continue;
                    if (!safe(stored)) { tools[1] = true; continue; }
                    ItemStack candidate = items.extractItem(slot, 1, true);
                    if (candidate.getCount() == 1 && ItemStack.isSameItemSameComponents(candidate, stored)) tools[0] = true;
                }
            }
        });
        if (core.isRemoved() || before != changes || observation.invalidated() || tools[0] || !tools[1]) observation.close();
        else pause = observation;
    }

    void reset() {
        if (pause != null) pause.close();
        pause = null; neighbors = null; rejectedWornTool = false; foundSafeTool = false;
    }
}
