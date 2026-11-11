package dev.senluo.witchcraft.ritual;

import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;

/** Fresh chalk stacks normally; only the active stick becomes a native damageable tool. */
public final class ChalkUsage {
    /** The unused stack carries enchantments; consume still owns the single-stick durability transition. */
    public abstract static class Item extends net.minecraft.world.item.Item {
        protected Item(Properties properties) { super(properties); }
        @Override public boolean isEnchantable(ItemStack stack) { return true; }
        @Override public int getEnchantmentValue() { return 1; }
    }

    public static boolean isChalk(ItemStack stack) { return stack.getItem() instanceof Item; }

    static void consume(UseOnContext context) {
        var player = context.getPlayer();
        if (player == null || player.hasInfiniteMaterials()) return;
        ItemStack held = context.getItemInHand();
        if (!held.isDamageableItem()) {
            ItemStack spare = held.copyWithCount(held.getCount() - 1);
            held.setCount(1);
            held.set(DataComponents.MAX_STACK_SIZE, 1);
            held.set(DataComponents.MAX_DAMAGE, 64);
            held.set(DataComponents.DAMAGE, 0);
            if (!spare.isEmpty() && !player.getInventory().add(spare)) player.drop(spare, false);
        }
        held.hurtAndBreak(1, player, LivingEntity.getSlotForHand(context.getHand()));
    }
    private ChalkUsage() {}
}
