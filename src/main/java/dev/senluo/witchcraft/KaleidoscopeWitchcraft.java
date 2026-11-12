package dev.senluo.witchcraft;

import net.neoforged.fml.common.Mod;
import dev.senluo.witchcraft.time.TemporalContent;
import dev.senluo.witchcraft.time.TemporalWorld;
import dev.senluo.witchcraft.time.LivingInteractions;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.fml.event.lifecycle.FMLLoadCompleteEvent;
import net.neoforged.fml.ModList;
import dev.senluo.witchcraft.time.TavernWorkSource;
import dev.senluo.witchcraft.compat.tiab.TimeBottleFeeding;
import org.slf4j.LoggerFactory;

@Mod(KaleidoscopeWitchcraft.MOD_ID)
public final class KaleidoscopeWitchcraft {
    public static final String MOD_ID = "kaleidoscope_witchcraft";
    // Shared handshake also guards synced attachment layouts, not just explicitly registered payloads.
    public static final String NETWORK_VERSION = "48";

    public KaleidoscopeWitchcraft(IEventBus bus, net.neoforged.fml.ModContainer container) {
        container.registerConfig(net.neoforged.fml.config.ModConfig.Type.SERVER,
                dev.senluo.witchcraft.ritual.RitualConfig.SPEC);
        TemporalContent.register(bus);
        if (ModList.get().isLoaded("thaumcraft")) {
            bus.addListener(dev.senluo.witchcraft.compat.thaumcraft.ShockBolt::register);
            dev.senluo.witchcraft.compat.thaumcraft.NodeOutput.register(bus);
            dev.senluo.witchcraft.compat.thaumcraft.BoreRings.register(bus);
            dev.senluo.witchcraft.compat.thaumcraft.ReservoirThaumcraft.registerCapabilities(bus);
        }
        if (ModList.get().isLoaded("thaumcraft") && ModList.get().isLoaded("botania"))
            dev.senluo.witchcraft.compat.botania.NodeDaisy.register(bus);
        dev.senluo.witchcraft.vishroom.VishroomContent.register(bus);
        dev.senluo.witchcraft.reservoir.ReservoirContent.register(bus);
        if (ModList.get().isLoaded("thaumcraft")) dev.senluo.witchcraft.compat.thaumcraft.conduit.ConduitContent.register(bus);
        container.registerConfig(net.neoforged.fml.config.ModConfig.Type.SERVER,
                dev.senluo.witchcraft.reservoir.ReservoirConfig.SPEC, "kaleidoscope-thaumic-witchcraft-reservoir-server.toml");
        if (ModList.get().isLoaded("curios")) dev.senluo.witchcraft.compat.curios.ReservoirCurios.register(bus);
        bus.addListener(dev.senluo.witchcraft.time.PlantStorageObservation::register);
        NeoForge.EVENT_BUS.addListener(dev.senluo.witchcraft.time.PlantStorageObservation::tick);
        dev.senluo.witchcraft.accessory.AccessoryContent.register(bus);
        bus.addListener(dev.senluo.witchcraft.accessory.BarrierVisuals::register);
        if (ModList.get().isLoaded("curios")) dev.senluo.witchcraft.compat.curios.TurtleCurios.register(bus);
        dev.senluo.witchcraft.time.PlayerActionTime.register(bus);
        dev.senluo.witchcraft.ritual.RitualContent.register(bus);
        dev.senluo.witchcraft.ritual.GeasView.register(bus);
        dev.senluo.witchcraft.ritual.TntOrigins.register(bus);
        bus.addListener(dev.senluo.witchcraft.ritual.GeasRitualVisuals::register);
        dev.senluo.witchcraft.slash.SlashContent.register(bus);
        if (ModList.get().isLoaded("kaleidoscope_tavern")) dev.senluo.witchcraft.compat.tavern.TavernWine.register(bus);
        dev.senluo.witchcraft.brew.BrewContent.register(bus);
        dev.senluo.witchcraft.brew.AmberFerments.register(bus);
        dev.senluo.witchcraft.brew.DawnWalk.register(bus);
        dev.senluo.witchcraft.brew.AmberVaults.register(bus);
        dev.senluo.witchcraft.brew.WineAbilities.register(bus);
        dev.senluo.witchcraft.brew.StrideParticles.register(bus);
        NeoForge.EVENT_BUS.addListener(dev.senluo.witchcraft.brew.ExtraTime::tick);
        NeoForge.EVENT_BUS.addListener(dev.senluo.witchcraft.brew.ExtraTime::prepare);
        NeoForge.EVENT_BUS.addListener(dev.senluo.witchcraft.brew.ProjectileDodge::impact);
        NeoForge.EVENT_BUS.addListener(net.neoforged.bus.api.EventPriority.HIGHEST,
                dev.senluo.witchcraft.accessory.TurtleField::impact);
        NeoForge.EVENT_BUS.addListener(LivingInteractions::rightClick);
        bus.addListener((FMLCommonSetupEvent event) -> event.enqueueWork(() -> {
            dev.senluo.witchcraft.ritual.RitualCenters.registerRuntime();
            dev.senluo.witchcraft.time.WorkTicks.registerEffect(dev.senluo.witchcraft.ritual.SteamWork::affected, dev.senluo.witchcraft.ritual.SteamWork::afterNative);
            dev.senluo.witchcraft.time.AccelerationSources.registerObserver(dev.senluo.witchcraft.ritual.SteamWork::observeSources);
            dev.senluo.witchcraft.ritual.TemporalInfusion.register();
            if (ModList.get().isLoaded("thaumcraft")) {
            dev.senluo.witchcraft.compat.thaumcraft.ReservoirThaumcraft.register();
            dev.senluo.witchcraft.compat.thaumcraft.FocusBarrier.register();
            dev.senluo.witchcraft.compat.thaumcraft.FocusRestraint.register();
                dev.senluo.witchcraft.compat.thaumcraft.CrucibleRitualAdapter.register();
                dev.senluo.witchcraft.compat.thaumcraft.NodeRituals.register();
                dev.senluo.witchcraft.compat.thaumcraft.BoreRings.register();
                dev.senluo.witchcraft.compat.thaumcraft.DeepDrilling.register();
                dev.senluo.witchcraft.compat.thaumcraft.InfusionGeas.register();
                dev.senluo.witchcraft.compat.thaumcraft.SlashDrawingAction.register();
            }
            if (ModList.get().isLoaded("kaleidoscope_tavern")) TavernWorkSource.register();
            if (ModList.get().isLoaded("botania")) dev.senluo.witchcraft.compat.botania.EndoflameWork.register();
            if (ModList.get().isLoaded("tiab")) TimeBottleFeeding.register();
            if (ModList.get().isLoaded("tiab") && ModList.get().isLoaded("kaleidoscope_tavern"))
                dev.senluo.witchcraft.compat.tiab.BarrelBottleInteraction.register();
        }));
        bus.addListener((FMLLoadCompleteEvent event) -> event.enqueueWork(() -> {
            if (ModList.get().isLoaded("kaleidoscope_tavern"))
                dev.senluo.witchcraft.compat.tavern.WineTapBehavior.register();
        }));
        NeoForge.EVENT_BUS.addListener((LevelTickEvent.Post event) -> {
            if (event.getLevel() instanceof ServerLevel level) TemporalWorld.get(level).tick();
        });
        NeoForge.EVENT_BUS.addListener((LevelEvent.Unload event) -> {
            if (event.getLevel() instanceof ServerLevel level) {
                TemporalWorld.unload(level);
                dev.senluo.witchcraft.ritual.SteamWork.unload(level);
            }
            if (event.getLevel() instanceof net.minecraft.world.level.Level level)
                dev.senluo.witchcraft.accessory.TurtleField.unload(level);
        });
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.level.ChunkEvent.Load event) -> {
            if(event.getLevel() instanceof ServerLevel level)TemporalWorld.get(level).chunkChanged(event.getChunk().getPos());
        });
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.level.ChunkEvent.Unload event) -> {
            if(event.getLevel() instanceof ServerLevel level) {
                TemporalWorld.get(level).chunkChanged(event.getChunk().getPos());
                dev.senluo.witchcraft.ritual.SteamFire.unloadChunk(level,event.getChunk().getPos());
            }
        });
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.TagsUpdatedEvent event) -> {
            if (event.getUpdateCause() != net.neoforged.neoforge.event.TagsUpdatedEvent.UpdateCause.SERVER_DATA_LOAD) return;
            var server = net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer();
            if (server != null) server.execute(() -> server.getAllLevels().forEach(level -> {
                var world = TemporalWorld.get(level);
                world.invalidate();
                world.crops.clear();
            }));
        });
        LoggerFactory.getLogger(MOD_ID).info(
                "[KaleidoscopeWitchcraft] loaded");
    }
}
