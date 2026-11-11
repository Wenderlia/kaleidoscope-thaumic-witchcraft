package dev.senluo.witchcraft.time;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Items;
import net.minecraft.tags.ItemTags;
import net.neoforged.neoforge.common.util.TriState;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/** Keep our crouching controls on the normal client/server block-use path. */
public final class LivingInteractions {
    private LivingInteractions() {}

    public static void rightClick(PlayerInteractEvent.RightClickBlock event) {
        if (!event.getEntity().isShiftKeyDown() || !event.getUseBlock().isDefault()
                || !(event.getLevel().getBlockState(event.getPos()).getBlock() instanceof LivingBlock)) return;
        var stack = event.getItemStack();
        if (stack.is(Items.LEVER) || stack.is(Items.CLOCK) || stack.is(Items.GOLD_NUGGET) || stack.is(Items.GOLD_INGOT) || stack.is(Items.BONE_MEAL) || stack.is(ItemTags.SHOVELS)
                || stack.isEmpty() && event.getHand() == InteractionHand.MAIN_HAND) {
            // Vanilla otherwise skips block use while crouching with either hand occupied.
            // Existing cancellation and explicit protection decisions remain authoritative.
            event.setUseBlock(TriState.TRUE);
        }
    }
}
