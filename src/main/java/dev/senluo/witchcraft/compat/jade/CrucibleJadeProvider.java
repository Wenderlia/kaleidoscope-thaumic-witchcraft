package dev.senluo.witchcraft.compat.jade;

import dev.senluo.witchcraft.KaleidoscopeWitchcraft;
import dev.senluo.witchcraft.compat.thaumcraft.SteamRituals;
import dev.senluo.witchcraft.time.TemporalText;
import dev.tc4port.thaumcraft.api.ThaumcraftContent;
import net.minecraft.world.level.block.Block;
import dev.senluo.witchcraft.compat.thaumcraft.EssentiaStores;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.math.BigDecimal;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import snownee.jade.api.*;
import snownee.jade.api.config.IPluginConfig;

/** Two additive lines. Never replaces the upstream aspect list or reads client-only machine guesses. */
final class CrucibleJadeProvider implements IBlockComponentProvider, IServerDataProvider<BlockAccessor> {
    private static final CrucibleJadeProvider INSTANCE = new CrucibleJadeProvider();
    private static final ResourceLocation UID = ResourceLocation.fromNamespaceAndPath(KaleidoscopeWitchcraft.MOD_ID, "crucible_properties");
    private static final String DATA = UID.toString();
    static void register(IWailaCommonRegistration registration) {
        registration.registerBlockDataProvider(INSTANCE, BlockEntity.class);
    }
    static void registerClient(IWailaClientRegistration registration) {
        registration.registerBlockComponent(INSTANCE, Block.class);
    }
    @Override public void appendServerData(CompoundTag data, BlockAccessor accessor) {
        var pot = EssentiaStores.pot(accessor.getBlockEntity());
        if (pot == null) return;
        var view = EssentiaStores.crucible(pot).orElse(null);
        if (view == null) return;
        SteamRituals.profile(pot).ifPresent(profile -> {
            CompoundTag values = new CompoundTag();
            values.putInt("Total", EssentiaStores.total(view.essentiaContents(null)));
            values.putInt("Capacity", view.stableCapacity());
            values.putInt("Points", profile.points());
            values.putDouble("Decay", profile.decayMultiplier());
            values.putDouble("Water", profile.waterMultiplier());
            values.putBoolean("Paused", SteamRituals.managed(pot));
            data.put(DATA, values);
        });
    }
    @Override public void appendTooltip(ITooltip tooltip, BlockAccessor accessor, IPluginConfig config) {
        if (!accessor.getBlockState().is(ThaumcraftContent.block("crucible")) || !accessor.isServerConnected() || !accessor.getServerData().contains(DATA, Tag.TAG_COMPOUND)) return;
        CompoundTag values = accessor.getServerData().getCompound(DATA);
        if (!values.contains("Total", Tag.TAG_INT) || !values.contains("Capacity", Tag.TAG_INT)
                || !values.contains("Points", Tag.TAG_INT) || !values.contains("Decay", Tag.TAG_DOUBLE)
                || !values.contains("Water", Tag.TAG_DOUBLE)) return;
        int total = values.getInt("Total"), capacity = values.getInt("Capacity"), points = values.getInt("Points");
        double decay = values.getDouble("Decay"), water = values.getDouble("Water");
        if (total < 0 || capacity < 0 || points < 0 || !Double.isFinite(decay) || decay < 1
                || !Double.isFinite(water) || water < 0) return;
        tooltip.add(TemporalText.color(Component.translatable("jade.kaleidoscope_witchcraft.crucible.capacity",
                TemporalText.number(total, TemporalText.AMOUNT), TemporalText.number(capacity, TemporalText.CAPACITY),
                TemporalText.number(points, TemporalText.STAGE)), TemporalText.BODY));
        Component waterText = TemporalText.number(decimal(water), TemporalText.TIME);
        Component process = values.getBoolean("Paused")
                ? Component.translatable("jade.kaleidoscope_witchcraft.crucible.paused", waterText)
                : Component.translatable("jade.kaleidoscope_witchcraft.crucible.process",
                        TemporalText.number(decimal(decay), TemporalText.TIME), waterText);
        tooltip.add(TemporalText.color(process, TemporalText.BODY));
    }
    private static String decimal(double value) { return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString(); }
    @Override public ResourceLocation getUid() { return UID; }
    // No additional options/help text: the approved readout follows Jade's existing HUD visibility controls.
    @Override public boolean isRequired() { return true; }
    @Override public int getDefaultPriority() { return 5100; }
}
