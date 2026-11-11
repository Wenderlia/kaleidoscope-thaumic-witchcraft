package dev.senluo.witchcraft.time;

import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.block.Block;
import net.neoforged.neoforge.common.ItemAbilities;

/** Shared shearing yield and adjacent-container harvesting; never runs on accelerated fruit ticks. */
public final class FruitHarvest {
    private FruitHarvest() {}

    public static boolean isShears(ItemStack stack) {
        return !stack.isEmpty() && stack.canPerformAction(ItemAbilities.SHEARS_HARVEST);
    }

    public static int fortune(ServerLevel level, ItemStack tool) {
        if (!isShears(tool)) return 0;
        var enchantment = level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.FORTUNE);
        return EnchantmentHelper.getItemEnchantmentLevel(enchantment, tool);
    }

    /** Discrete Gaussian on 1..n+1: mean=(n+2)/2, sigma=(n+1)/4. III gives 13/37/37/13%. */
    public static int count(int fortune, double roll) {
        // 255 is the item-enchantment component's own supported maximum, not a survival-level cap.
        int maximum = 1 + Math.clamp(fortune, 0, 255);
        if (maximum == 1) return 1;
        double mean = (maximum + 1) / 2.0, sigma = maximum / 4.0, total = 0;
        for (int count = 1; count <= maximum; count++) total += weight(count, mean, sigma);
        double remaining = Math.clamp(Double.isFinite(roll) ? roll : 0.5, 0, Math.nextDown(1.0)) * total;
        for (int count = 1; count < maximum; count++) {
            remaining -= weight(count, mean, sigma);
            if (remaining < 0) return count;
        }
        return maximum;
    }

    private static double weight(int count, double mean, double sigma) {
        double z = (count - mean) / sigma;
        return Math.exp(-0.5 * z * z);
    }

    public static ItemStack product(FruitEntity fruit, int fortune) {
        if (fruit.grade <= 0 && fruit.supported()) return ItemStack.EMPTY;
        ItemStack stack = FruitItem.stack(fruit.grade, fruit.economyVersion);
        if (!fruit.supported()) {
            var data = stack.getOrDefault(net.minecraft.core.component.DataComponents.CUSTOM_DATA,
                    net.minecraft.world.item.component.CustomData.EMPTY).copyTag();
            data.put("UnsupportedFruit", fruit.preservedData());
            stack.set(net.minecraft.core.component.DataComponents.CUSTOM_DATA, net.minecraft.world.item.component.CustomData.of(data));
            return stack;
        }
        stack.setCount(fortune <= 0 ? 1 : count(fortune, fruit.harvestRoll()));
        return stack;
    }

    /** Also handles command-level Fortune without spawning an over-sized item stack. */
    public static void drop(ServerLevel level, BlockPos pos, ItemStack stack) {
        while (!stack.isEmpty()) Block.popResource(level, pos, stack.split(stack.getMaxStackSize()));
    }

    public static void tick(LivingEntity core, List<FruitEntity> fruits) {
        if (!(core.getLevel() instanceof ServerLevel level) || core.isRemoved() || !core.tree() || !core.tissue.giant || !core.hasOwner()
                || !level.tickRateManager().runsNormally() || !level.shouldTickBlocksAt(core.getBlockPos()) || fruits.isEmpty()
                || !core.shears.awake()) return;
        long now = level.getGameTime();
        if (core.lastFruitHarvestTick != Long.MIN_VALUE && now >= core.lastFruitHarvestTick && now - core.lastFruitHarvestTick < 20) return;
        core.lastFruitHarvestTick = now;
        int start = Math.floorMod(core.fruitHarvestCursor, fruits.size());
        for (int offset = 0; offset < fruits.size(); offset++) {
            int index = (start + offset) % fruits.size();
            FruitEntity fruit = fruits.get(index);
            if (!ready(core, fruit)) continue;
            // Rotate even when storage is full so another quality cannot be starved by this one.
            core.fruitHarvestCursor = (index + 1) % fruits.size();
            for (AdjacentInventory receiver : AdjacentInventory.around(core)) {
                if (collect(level, core, fruit, receiver)) {
                    core.fruitContainerCursor = (core.fruitContainerCursor + 1) % 6;
                    return;
                }
            }
            return; // At most one fruit candidate per native second; no tree-volume inventory scan.
        }
    }

    private static boolean ready(LivingEntity core, FruitEntity fruit) {
        if (fruit.isRemoved() || fruit.harvested || fruit.grade <= 0 || fruit.core() != core) return false;
        int limit = fruit.limit();
        return limit > 0 && fruit.grade >= limit;
    }

    private static boolean collect(ServerLevel level, LivingEntity core, FruitEntity fruit, AdjacentInventory receiver) {
        var inventory = receiver.inventory;
        BlockPos pos = receiver.pos;
        for (int slot = 0; slot < inventory.getSlots(); slot++) {
            ItemStack stored = inventory.getStackInSlot(slot);
            if (!core.shears.usable(stored) || !inventory.isItemValid(slot, stored)) continue;
            ItemStack expected = inventory.extractItem(slot, 1, true).copy();
            if (expected.getCount() != 1 || !ItemStack.isSameItemSameComponents(expected, stored) || !core.shears.usable(expected)) continue;
            core.shears.foundUsable();
            ItemStack product = product(fruit, fortune(level, expected));
            // Reserve the tool's return slot; the last durability point is never spent by tree automation.
            if (!receiver.fits(List.of(product))) return false;
            ItemStack tool = receiver.take(slot, expected);
            if (!ItemStack.matches(tool, expected) || !ready(core, fruit)
                    || level.getBlockEntity(fruit.getBlockPos()) != fruit || !receiver.present() || !core.shears.usable(tool)) {
                receiver.returnTo(slot, tool);
                return false;
            }
            fruit.harvested = true; // onRemove must not also emit a world drop.
            if (!level.removeBlock(fruit.getBlockPos(), false)) {
                fruit.harvested = false;
                receiver.returnTo(slot, tool);
                return false;
            }
            // Vanilla's item hook and durability enchantments (including Unbreaking) run once per cut.
            tool.hurtAndBreak(1, level, (ServerPlayer) null, item -> level.playSound(null, pos,
                    SoundEvents.ITEM_BREAK, SoundSource.BLOCKS, 0.5F, 1));
            receiver.returnTo(slot, tool);
            receiver.receive(product);
            level.playSound(null, fruit.getBlockPos(), SoundEvents.SHEEP_SHEAR, SoundSource.BLOCKS, 0.5F, 1);
            return true;
        }
        return false;
    }

}
