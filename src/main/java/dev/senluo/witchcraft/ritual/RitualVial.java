package dev.senluo.witchcraft.ritual;

import java.util.UUID;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

/** A token identifies one physical sample, independently of the selected hotbar slot. */
final class RitualVial {
    private static final String KEY = "kaleidoscope_witchcraft:ownership_session";
    private RitualVial() {}

    static UUID token(ItemStack stack) {
        if (!stack.is(RitualContent.TAGLOCK.get())) return null;
        var data = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        return data.hasUUID(KEY) ? data.getUUID(KEY) : null;
    }

    static boolean reserved(ItemStack stack) {
        UUID token = token(stack);
        return token != null && live(token);
    }
    private static boolean live(UUID token) { return OwnershipRitual.hasSession(token) || MushroomRitual.hasSession(token); }

    static boolean canReserve(ServerPlayer player, InteractionHand hand) {
        ItemStack held = player.getItemInHand(hand);
        int remaining = held.getCount() - 1;
        if (remaining <= 0) return true;
        // The marked bottle occupies the hand; excess samples must fit without dropping them.
        for (int slot = 0; slot < player.getInventory().items.size(); slot++) {
            if (hand == InteractionHand.MAIN_HAND && slot == player.getInventory().selected) continue;
            ItemStack other = player.getInventory().items.get(slot);
            if (other.isEmpty()) return true;
            if (ItemStack.isSameItemSameComponents(held, other)) remaining -= Math.max(0, other.getMaxStackSize() - other.getCount());
            if (remaining <= 0) return true;
        }
        return false;
    }

    static boolean reserve(ServerPlayer player, InteractionHand hand, UUID id) {
        ItemStack held = player.getItemInHand(hand);
        ItemStack remaining = held.copyWithCount(held.getCount() - 1);
        var changes = new java.util.LinkedHashMap<Integer, ItemStack>();
        // Plan the whole insertion before mutating any slot. No ground-drop fallback.
        for (int pass = 0; pass < 2 && !remaining.isEmpty(); pass++) {
            for (int slot = 0; slot < player.getInventory().items.size() && !remaining.isEmpty(); slot++) {
                if (hand == InteractionHand.MAIN_HAND && slot == player.getInventory().selected) continue;
                ItemStack other = changes.getOrDefault(slot, player.getInventory().items.get(slot));
                if (pass == 0 && !other.isEmpty() && ItemStack.isSameItemSameComponents(held, other)) {
                    int amount = Math.min(remaining.getCount(), Math.max(0, other.getMaxStackSize() - other.getCount()));
                    if (amount > 0) {
                        changes.put(slot, other.copyWithCount(other.getCount() + amount));
                        remaining.shrink(amount);
                    }
                } else if (pass == 1 && other.isEmpty()) {
                    int amount = Math.min(remaining.getCount(), remaining.getMaxStackSize());
                    changes.put(slot, remaining.copyWithCount(amount));
                    remaining.shrink(amount);
                }
            }
        }
        if (!remaining.isEmpty()) return false;
        ItemStack one = held.copyWithCount(1);
        var tag = one.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        tag.putUUID(KEY, id);
        one.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        player.setItemInHand(hand, one);
        changes.forEach((slot, stack) -> player.getInventory().setItem(slot, stack));
        player.getInventory().setChanged();
        OwnershipRitual.syncInventory(player);
        return true;
    }

    static ItemStack find(ServerPlayer player, UUID id, TaglockSample sample) {
        if (player == null || !player.isAlive() || player.isSpectator()) return ItemStack.EMPTY;
        ItemStack found = ItemStack.EMPTY;
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (id.equals(token(stack))) {
                // Reject altered samples and duplicated reservation tokens.
                if (!found.isEmpty() || stack.getCount() != 1 || !stack.is(RitualContent.TAGLOCK.get())
                        || !sample.equals(stack.get(RitualContent.TAGLOCK_SAMPLE.get()))) return ItemStack.EMPTY;
                found = stack;
            }
        }
        return found;
    }

    static void release(ItemStack stack) {
        var data = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        data.remove(KEY);
        if (data.isEmpty()) stack.remove(DataComponents.CUSTOM_DATA);
        else stack.set(DataComponents.CUSTOM_DATA, CustomData.of(data));
    }

    static void cleanup(ItemStack stack) {
        UUID id = token(stack);
        if (id != null && !live(id)) release(stack);
    }

    static void releaseAll(ServerPlayer player, UUID id) {
        if (player == null) return;
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (id.equals(token(stack))) release(stack);
        }
        OwnershipRitual.syncInventory(player);
    }
}
