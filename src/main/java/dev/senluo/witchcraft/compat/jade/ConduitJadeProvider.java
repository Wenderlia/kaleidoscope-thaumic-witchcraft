package dev.senluo.witchcraft.compat.jade;

import dev.senluo.witchcraft.KaleidoscopeWitchcraft;
import dev.senluo.witchcraft.compat.thaumcraft.conduit.ConduitBlock;
import dev.senluo.witchcraft.compat.thaumcraft.conduit.ConduitEntity;
import dev.tc4port.thaumcraft.api.aspect.AspectApi;
import dev.tc4port.thaumcraft.api.aspect.AspectId;
import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import snownee.jade.api.*;
import snownee.jade.api.config.IPluginConfig;

/** Native tube presentation for our mixed transport; native storage HUD owns the aspect rows. */
final class ConduitJadeProvider implements IBlockComponentProvider, IServerDataProvider<BlockAccessor> {
    private static final ConduitJadeProvider INSTANCE = new ConduitJadeProvider();
    private static final ResourceLocation UID = ResourceLocation.fromNamespaceAndPath(KaleidoscopeWitchcraft.MOD_ID, "essentia_conduit");
    private static final ResourceLocation NATIVE_OPTION = ResourceLocation.fromNamespaceAndPath("thaumcraft", "essentia_tube");
    private static final String DATA = UID.toString();

    static void register(IWailaCommonRegistration registration) {
        registration.registerBlockDataProvider(INSTANCE, ConduitEntity.class);
    }
    static void registerClient(IWailaClientRegistration registration) {
        registration.registerBlockComponent(INSTANCE, ConduitBlock.class);
    }
    @Override public void appendServerData(CompoundTag data, BlockAccessor accessor) {
        if (!(accessor.getBlockEntity() instanceof ConduitEntity pipe) || pipe.isRemoved()) return;
        var face = ConduitBlock.inspectionFace(accessor.getPosition(), accessor.getHitResult());
        CompoundTag values = new CompoundTag();
        values.putInt("Suction", Math.max(0, pipe.suctionAmount(face)));
        AspectId type = pipe.suctionType(face);
        if (type != null) values.putString("Type", type.toString());
        values.putBoolean("Open", pipe.canInputFrom(face) || pipe.canOutputTo(face));
        data.put(DATA, values);
    }
    @Override public void appendTooltip(ITooltip tooltip, BlockAccessor accessor, IPluginConfig config) {
        if (!accessor.isServerConnected() || !config.get(NATIVE_OPTION)
                || !accessor.getServerData().contains(DATA, Tag.TAG_COMPOUND)) return;
        CompoundTag values = accessor.getServerData().getCompound(DATA);
        if (!values.contains("Suction", Tag.TAG_INT) || values.getInt("Suction") < 0
                || !values.contains("Open", Tag.TAG_BYTE)) return;
        Component type = Component.translatable("tc.resonator3");
        if (values.contains("Type", Tag.TAG_STRING)) {
            final AspectId id;
            try { id = AspectId.parse(values.getString("Type")); }
            catch (IllegalArgumentException invalid) { return; }
            type = AspectApi.registry().get(id).<Component>map(definition ->
                    Component.translatable("jade.thaumcraft.aspect.name", definition.name(), definition.description())
                            .withStyle(style -> style.withColor(definition.color())))
                    .orElseGet(() -> Component.literal(id.toString()));
        }
        tooltip.add(Component.translatable("jade.thaumcraft.essentia.suction_type", type).withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("jade.thaumcraft.essentia.suction", values.getInt("Suction")).withStyle(ChatFormatting.GRAY));
        if (!values.getBoolean("Open"))
            tooltip.add(Component.translatable("jade.thaumcraft.essentia.flow_disabled").withStyle(ChatFormatting.RED));
    }
    @Override public ResourceLocation getUid() { return UID; }
    // Follow TC's existing tube toggle; no duplicate configuration name or new display wording.
    @Override public boolean isRequired() { return true; }
}
