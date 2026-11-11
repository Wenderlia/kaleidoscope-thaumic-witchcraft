package dev.senluo.witchcraft.ritual;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.SwordItem;
import net.minecraft.world.item.Tier;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.context.UseOnContext;

public final class RitualDaggerItem extends SwordItem {
    public RitualDaggerItem(Tier tier, Properties properties) {
        super(tier, properties);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        return RitualChalk.carve(context);
    }

    @Override
    public boolean hasCraftingRemainingItem(ItemStack stack) {
        return true;
    }

    @Override
    public ItemStack getCraftingRemainingItem(ItemStack stack) {
        // Work on a copy: recipe previews must not consume or reset the input tool.
        ItemStack remainder = stack.copyWithCount(1);
        if (remainder.isDamageableItem()) {
            // NeoForge supplies this actor only while taking the crafted result. Recipe previews
            // and unowned automation keep deterministic ordinary wear and do not draw randomness.
            var crafter = net.neoforged.neoforge.common.CommonHooks.getCraftingPlayer();
            int wear = crafter instanceof net.minecraft.server.level.ServerPlayer player && !player.isFakePlayer()
                    ? DurabilityLoss.forBearer(player).integral(1, player.getRandom()) : 1;
            if (wear == 0) return remainder;
            if (remainder.getDamageValue() >= remainder.getMaxDamage() - wear) {
                return ItemStack.EMPTY;
            }
            remainder.setDamageValue(remainder.getDamageValue() + wear);
        }
        return remainder;
    }
}
