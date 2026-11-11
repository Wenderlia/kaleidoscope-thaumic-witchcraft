package dev.senluo.witchcraft.vishroom;

import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.block.Block;

final class VishroomCoreItem extends BlockItem {
    VishroomCoreItem(Block block, Properties properties) { super(block, properties); }

    @Override public void inventoryTick(ItemStack stack, net.minecraft.world.level.Level level,
            net.minecraft.world.entity.Entity carrier, int slot, boolean selected) {
        super.inventoryTick(stack, level, carrier, slot, selected);
        if (!(level instanceof net.minecraft.server.level.ServerLevel server) || level.getGameTime() % 100 != 0) return;
        var data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null) return;
        var saved = data.copyTag();
        var organism = saved.getCompound("Organism");
        if (organism.getInt("Version") != 1 || organism.getInt("Form") != 1 || !organism.hasUUID("Owner")
                || !organism.getString("OwnerName").isEmpty()) return;
        String name = VishroomEntity.knownOwnerName(server, organism.getUUID("Owner"), "");
        if (!name.isEmpty()) {
            organism.putString("OwnerName", name);
            stack.set(DataComponents.CUSTOM_DATA, CustomData.of(saved));
        }
    }

    @Override public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        var tag = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag().getCompound("Organism");
        if (tag.hasUUID("Owner")) {
            var id = tag.getUUID("Owner");
            String name = tag.getString("OwnerName");
            if (name.isEmpty() && context.level() != null) {
                var player = context.level().getPlayerByUUID(id);
                if (player != null) name = player.getGameProfile().getName();
            }
            tooltip.add(Component.translatable("tooltip.kaleidoscope_witchcraft.vishroom_owner",
                    name.isEmpty() ? id.toString() : name).withStyle(ChatFormatting.LIGHT_PURPLE));
        }
        super.appendHoverText(stack, context, tooltip, flag);
    }
}
