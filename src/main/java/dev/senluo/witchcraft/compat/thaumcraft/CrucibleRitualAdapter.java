package dev.senluo.witchcraft.compat.thaumcraft;

import dev.senluo.witchcraft.ritual.OfferingSounds;
import dev.senluo.witchcraft.ritual.SteamWork;
import net.minecraft.sounds.SoundEvents;
import dev.tc4port.thaumcraft.api.ThaumcraftContent;
import dev.tc4port.thaumcraft.api.event.AdvancedAlchemyFurnaceEvent;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.server.level.ServerLevel;
import dev.senluo.witchcraft.time.WorkSources;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.common.NeoForge;

/** Optional TC4R adapter. No dependency on research UI, player NBT internals, or global parameter overrides. */
public final class CrucibleRitualAdapter {
    public static final ResourceLocation RESEARCH = ResourceLocation.fromNamespaceAndPath("thaumcraft", "research");
    private CrucibleRitualAdapter() {}
    public static void blockChanged(net.minecraft.server.level.ServerLevel level, net.minecraft.core.BlockPos pos,
            net.minecraft.world.level.block.state.BlockState before, net.minecraft.world.level.block.state.BlockState after) {
        BellowsUpdates.changed(level, pos, before, after);
        if (before.getBlock() == after.getBlock()) return;
        if (before.hasBlockEntity() || after.hasBlockEntity()) SuctionEndpointIndex.changed(level, pos);
        boolean receiverChanged = dev.senluo.witchcraft.ritual.WaterReceivers.candidate(before)
                || dev.senluo.witchcraft.ritual.WaterReceivers.candidate(after);
        if (before.hasBlockEntity() || after.hasBlockEntity() || receiverChanged)
            RitualWaterIndex.changed(level, pos, before.hasBlockEntity() || after.hasBlockEntity(), receiverChanged);
        if (CrucibleStabilizers.matches(before) != CrucibleStabilizers.matches(after))
            CrucibleStabilizers.changed(level, net.minecraft.world.level.ChunkPos.asLong(pos));
    }
    public static void register() {
        OfferingSounds.register("thaumcraft:silverwood_log",
                new OfferingSounds.Voice(SoundEvents.WOOD_BREAK, .3F, .9F),
                new OfferingSounds.Voice(SoundEvents.AMETHYST_BLOCK_CHIME, .2F, .8F));
        OfferingSounds.register("thaumcraft:quicksilver",
                new OfferingSounds.Voice(SoundEvents.BUCKET_EMPTY, .25F, 1.4F),
                new OfferingSounds.Voice(SoundEvents.AMETHYST_BLOCK_RESONATE, .15F, 1.2F));
        OfferingSounds.register("thaumcraft:wisp_essence",
                new OfferingSounds.Voice(SoundEvents.AMETHYST_BLOCK_CHIME, .5F, 1.4F));
        CrucibleEvents.register();
        PedestalOfferings.register();
        ApothecaryWaterReceiver.registerIfAvailable();
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.level.ChunkEvent.Load event) -> RitualWaterIndex.load(event));
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.level.ChunkEvent.Unload event) -> RitualWaterIndex.unload(event));
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.level.LevelEvent.Unload event) -> RitualWaterIndex.unload(event));
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(RitualWaterIndex::tags);
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(CrucibleStabilizers::load);
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.level.ChunkEvent.Unload event) -> CrucibleStabilizers.unload(event));
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.level.LevelEvent.Unload event) -> CrucibleStabilizers.unload(event));
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(CrucibleStabilizers::tags);
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(SteamRituals::tick);
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.level.ChunkEvent.Load event) -> SuctionEndpointIndex.load(event));
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.level.ChunkEvent.Unload event) -> SuctionEndpointIndex.unload(event));
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.level.LevelEvent.Unload event) -> SuctionEndpointIndex.unload(event));
        var filled = (BooleanProperty) ThaumcraftContent.block("alchemical_furnace").getStateDefinition().getProperty("filled");
        WorkSources.registerProcess(ThaumcraftContent.blockEntity("alchemy_furnace"), new WorkSources.Process<>() {
            public double price(ServerLevel level, BlockEntity furnace) {
                return furnace.getBlockState().getValue(BlockStateProperties.LIT)
                        && dev.tc4port.thaumcraft.api.machine.AlchemyFurnaceApi.canSmelt(level, furnace.getBlockPos()) ? WorkSources.UPDATE_PRICE : 0;
            }
            public boolean steamWork(ServerLevel level, BlockEntity furnace) {
                var state = furnace.getBlockState();
                return state.getValue(BlockStateProperties.LIT) || state.getValue(filled);
            }
            public boolean outputWork(ServerLevel level, BlockEntity furnace) {
                return furnace.getBlockState().getValue(filled) || price(level, furnace) > 0;
            }
        });
        // Compose with earlier cooldown modifiers; native intake already paid and committed the item.
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, CrucibleRitualAdapter::advancedFurnaceProcessed);
        EssentiaTubeWork.register();
        SteamMachineWork.register();
        BellowsUpdates.register();
        WorkSources.registerProcess(ThaumcraftContent.blockEntity("infernal_furnace"), new WorkSources.Process<>() {
            // Retain the previous default work quote. The native core owns its queue and vis reserve.
            public double price(ServerLevel level, BlockEntity furnace) { return WorkSources.UPDATE_PRICE; }
            public boolean steamWork(ServerLevel level, BlockEntity furnace) { return true; }
        });
        WorkSources.registerProcess(ThaumcraftContent.blockEntity("crucible"), new WorkSources.Process<>() {
            public double price(ServerLevel level, BlockEntity entity) { return 0; }
        });
        RitualKnowledge.register();
    }

    private static void advancedFurnaceProcessed(AdvancedAlchemyFurnaceEvent.Processed event) {
        int cooldown = event.cooldown();
        if (cooldown <= 1) return;
        long doublings = SteamWork.doublings(event.level(), Vec3.atCenterOf(event.furnace()));
        if (doublings == 0) return;
        // ceil(cooldown / 2^n), without allocating the multiplier or overflowing an int.
        event.setCooldown(doublings >= 31 ? 1 : ((cooldown - 1) >>> (int) doublings) + 1);
    }
}
