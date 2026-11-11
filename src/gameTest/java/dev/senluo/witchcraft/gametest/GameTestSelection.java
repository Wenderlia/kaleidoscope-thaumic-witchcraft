package dev.senluo.witchcraft.gametest;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import net.minecraft.gametest.framework.GameTest;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;

/** Register only requested scenarios when a change calls for a narrow regression run. */
@EventBusSubscriber(modid="kaleidoscope_witchcraft", bus=EventBusSubscriber.Bus.MOD)
public final class GameTestSelection {
    private static final Set<String> THAUMCRAFT_RESEARCH_SCENARIOS = Set.of(
            "migratedFreezeGuardsPreserveEffectsAndHealth",
            "interruptedSustainedPreparationKeepsSampleAndUnspentOfferings",
            "sustainedRitualSurvivesSerializedReloadAndStopsWithCore",
            "newResearchGatePausesLegacyPreparationButKeepsPaidRun");

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        String filter=System.getProperty("witchcraft.gameTests", "").trim();
        boolean all=filter.isEmpty();
        boolean thaumcraft=net.neoforged.fml.ModList.get().isLoaded("thaumcraft");
        Set<String> requested=new HashSet<>();
        if (!all) Arrays.stream(filter.split(",")).map(String::trim).map(s -> s.toLowerCase(Locale.ROOT)).forEach(requested::add);
        if (requested.remove("benchmark")) {
            for (var method : ModBenchmark.class.getDeclaredMethods())
                if (method.isAnnotationPresent(GameTest.class)) event.register(method);
        }
        for(var method:dev.senluo.witchcraft.slash.SlashPassageGameTests.class.getDeclaredMethods())
            if(method.isAnnotationPresent(GameTest.class) && (all || requested.remove(method.getName().toLowerCase(Locale.ROOT))))event.register(method);
        for(var method:dev.senluo.witchcraft.brew.WineAbilityGameTests.class.getDeclaredMethods())
            if(method.isAnnotationPresent(GameTest.class) && (all || requested.remove(method.getName().toLowerCase(Locale.ROOT))))event.register(method);
        for (Class<?> type : new Class<?>[]{TurtleFlightGameTests.class, BoundaryPersistenceGameTests.class, TissueDataGameTests.class, TreeWoodGameTests.class, FruitNodeGameTests.class, EventMigrationGameTests.class, TemporalGameTests.class, SideRootGameTests.class, DevelopmentGameTests.class, LinkedDevelopmentGameTests.class, ItemPresentationGameTests.class, RootedFarmlandGameTests.class, TreeGameTests.class, FruitHarvestGameTests.class, FruitTimeGameTests.class, FruitGradeGameTests.class, FruitFeedingGameTests.class, BrewGameTests.class}) {
            // Captured Curios inventory becomes a synthetic method parameter; filter before reflection.
            if (type == TurtleFlightGameTests.class && !net.neoforged.fml.ModList.get().isLoaded("curios")) continue;
            for (var method : type.getDeclaredMethods()) {
                if (!thaumcraft && THAUMCRAFT_RESEARCH_SCENARIOS.contains(method.getName())) continue;
                if (method.isAnnotationPresent(GameTest.class) && (all || requested.remove(method.getName().toLowerCase(Locale.ROOT))))
                    event.register(method);
            }
        }
        for (var method : SlashGeometryGameTests.class.getDeclaredMethods()) {
            if (method.isAnnotationPresent(GameTest.class) && (all || requested.remove(method.getName().toLowerCase(Locale.ROOT)))) event.register(method);
        }
        for (var method : RuntimeCompatibilityGameTests.class.getDeclaredMethods()) {
            if (!method.isAnnotationPresent(GameTest.class)) continue;
            boolean compatible = !method.getName().startsWith("lithium") || net.neoforged.fml.ModList.get().isLoaded("lithium");
            if (all && compatible || requested.remove(method.getName().toLowerCase(Locale.ROOT))) event.register(method);
        }
        for (var type : new Class<?>[]{ProductionGameTests.class, RootProductionGameTests.class, SideInheritanceGameTests.class}) {
            for (var method : type.getDeclaredMethods())
                if (method.isAnnotationPresent(GameTest.class) && (all || requested.remove(method.getName().toLowerCase(Locale.ROOT)))) event.register(method);
        }
        for (var method : CropCompatGameTests.class.getDeclaredMethods()) {
            if (!method.isAnnotationPresent(GameTest.class)) continue;
            boolean compatible = !method.getName().equals("infusedCropCatalysisUsesNativeClock")
                    && !method.getName().equals("manaBeanCatalysisKeepsNativeHybridization")
                    || net.neoforged.fml.ModList.get().isLoaded("thaumictinkerer");
            if (all && compatible || requested.remove(method.getName().toLowerCase(Locale.ROOT))) event.register(method);
        }
        if (thaumcraft) for (var type : new Class<?>[]{dev.senluo.witchcraft.time.TreeBiomeGrowthGameTests.class, SteamRitualGameTests.class, SuctionPresentationGameTests.class, RitualOfferingGameTests.class, FocusCatalogGameTests.class, dev.senluo.witchcraft.slash.SlashProtectionGameTests.class}) {
            for (var method : type.getDeclaredMethods())
                if (method.isAnnotationPresent(GameTest.class) && (all || requested.remove(method.getName().toLowerCase(Locale.ROOT)))) event.register(method);
        }
        for (var type : new Class<?>[]{RitualFrameworkGameTests.class, SuctionRitualGameTests.class, ReservoirPickupGameTests.class, PlacementRollbackGameTests.class, dev.senluo.witchcraft.time.ScheduledDataGameTests.class, dev.senluo.witchcraft.ritual.GeasLedgerGameTests.class}) {
            for (var method : type.getDeclaredMethods()) {
                if (!thaumcraft && THAUMCRAFT_RESEARCH_SCENARIOS.contains(method.getName())) continue;
                if (method.isAnnotationPresent(GameTest.class) && (all || requested.remove(method.getName().toLowerCase(Locale.ROOT)))) event.register(method);
            }
        }
        if (!requested.isEmpty()) throw new IllegalArgumentException("Unknown GameTest selection: " + requested);
    }
}
