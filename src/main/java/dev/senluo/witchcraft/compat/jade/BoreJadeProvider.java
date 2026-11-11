package dev.senluo.witchcraft.compat.jade;

import dev.senluo.witchcraft.compat.thaumcraft.BoreRings;
import dev.tc4port.thaumcraft.api.machine.ArcaneBoreView;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import snownee.jade.api.*;
import snownee.jade.api.config.IPluginConfig;

/** Uses the existing native Width label; no private GUI fields or host radius override. */
final class BoreJadeProvider implements IBlockComponentProvider, IServerDataProvider<BlockAccessor> {
    private static final BoreJadeProvider INSTANCE = new BoreJadeProvider();
    private static final ResourceLocation UID = ResourceLocation.fromNamespaceAndPath("kaleidoscope_witchcraft", "bore_range");
    static void register(IWailaCommonRegistration r) { r.registerBlockDataProvider(INSTANCE, BlockEntity.class); }
    static void registerClient(IWailaClientRegistration r) { r.registerBlockComponent(INSTANCE, Block.class); }
    @Override public void appendServerData(CompoundTag data, BlockAccessor accessor) {
        var bore = accessor.getBlockEntity();
        if (bore instanceof ArcaneBoreView view) {
            var state = BoreRings.state(bore);
            if (state.mask() != 0) data.putInt(UID.toString(), 1 + 2 * state.displayedRadius(view));
        }
    }
    @Override public void appendTooltip(ITooltip tooltip, BlockAccessor accessor, IPluginConfig config) {
        int width = accessor.getServerData().getInt(UID.toString());
        if (accessor.isServerConnected() && width > 0) tooltip.add(Component.literal("Width: " + width));
    }
    @Override public ResourceLocation getUid() { return UID; }
    @Override public boolean isRequired() { return true; }
}
