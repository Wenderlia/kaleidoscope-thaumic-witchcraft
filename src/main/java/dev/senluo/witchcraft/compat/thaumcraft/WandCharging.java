package dev.senluo.witchcraft.compat.thaumcraft;

import dev.tc4port.thaumcraft.api.aspect.VisAction;
import dev.tc4port.thaumcraft.api.aspect.VisCost;
import dev.tc4port.thaumcraft.api.vis.*;
import dev.tc4port.thaumcraft.api.wand.WandCapabilities;
import dev.tc4port.thaumcraft.api.wand.pipeline.WandVisStages;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

/** The ritual and the converter charge valid casting tools through their actual storage. */
final class WandCharging {
    private WandCharging() {}

    static VisStorage storage(ItemStack stack) {
        if (stack.isEmpty()) return null;
        var tool = stack.getCapability(WandCapabilities.TOOL);
        return tool == null || tool.view().isEmpty() ? null : stack.getCapability(VisStorageCapabilities.ITEM);
    }

    static VisChangeResult insert(VisStorage storage, ServerPlayer player, VisCost amount, VisChangeSettlement settle) {
        return storage.change(VisAccess.player(player), VisChangeRequest.insert(WandVisStages.INSERT, amount),
                VisAction.EXECUTE, true, false, settle);
    }
}
