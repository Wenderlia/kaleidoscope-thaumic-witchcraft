package dev.senluo.witchcraft.compat.thaumcraft;

import dev.senluo.witchcraft.time.WorkSources;
import dev.tc4port.thaumcraft.api.ThaumcraftContent;
import dev.tc4port.thaumcraft.api.essentia.EssentiaApi;
import dev.tc4port.thaumcraft.api.essentia.FilledJarEssentiaApi;
import java.util.function.BiPredicate;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;

/** Admission only: each host retains its clock, resource transaction and notifications. */
final class SteamMachineWork {
    private SteamMachineWork() {}

    static void register() {
        register(ThaumcraftContent.blockEntity("thaumatorium"),
                (level, entity) -> entity instanceof Container slots && !slots.getItem(0).isEmpty());
        register(ThaumcraftContent.blockEntity("warded_jar"),
                (level, entity) -> pulling(level, entity, Direction.UP));
        register(ThaumcraftContent.blockEntity("essentia_reservoir"),
                (level, entity) -> pulling(level, entity, entity.getBlockState().getValue(BlockStateProperties.FACING)));

        var furnaceId = ResourceLocation.fromNamespaceAndPath("thaumicbases", "advanced_alchemical_furnace");
        BuiltInRegistries.BLOCK_ENTITY_TYPE.getOptional(furnaceId).ifPresent(type -> {
            var block = BuiltInRegistries.BLOCK.get(furnaceId);
            var filled = (BooleanProperty) block.getStateDefinition().getProperty("filled");
            register(type, (level, entity) -> {
                var state = entity.getBlockState();
                return state.getValue(BlockStateProperties.LIT) || state.getValue(filled);
            });
        });
        BuiltInRegistries.BLOCK_ENTITY_TYPE.getOptional(ResourceLocation.fromNamespaceAndPath(
                "thaumictinkerer", "essentia_funnel")).ifPresent(type -> register(type,
                (level, entity) -> entity instanceof Container slots
                        && FilledJarEssentiaApi.view(slots.getItem(0)).isPresent()));
    }

    private static boolean pulling(ServerLevel level, BlockEntity entity, Direction face) {
        var transport = EssentiaApi.transport(level, entity.getBlockPos(), face);
        // Empty containers must still pull; full void jars still consume excess input.
        return transport != null && transport.canInputFrom(face) && transport.suctionAmount(face) > 0;
    }

    private static void register(BlockEntityType<?> type, BiPredicate<ServerLevel, BlockEntity> admission) {
        WorkSources.registerProcess(type, new WorkSources.Process<>() {
            // Preserve the previous unknown-machine quote and supply eligibility.
            public double price(ServerLevel level, BlockEntity entity) { return WorkSources.UPDATE_PRICE; }
            public boolean outputWork(ServerLevel level, BlockEntity entity) { return true; }
            public boolean steamWork(ServerLevel level, BlockEntity entity) { return admission.test(level, entity); }
        });
    }
}
