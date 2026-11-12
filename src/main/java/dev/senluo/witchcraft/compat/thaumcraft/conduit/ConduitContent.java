package dev.senluo.witchcraft.compat.thaumcraft.conduit;

import dev.senluo.witchcraft.KaleidoscopeWitchcraft;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.registries.*;

/** Loaded only when TC4R is present; all transport goes through its public capability. */
public final class ConduitContent {
    private static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(KaleidoscopeWitchcraft.MOD_ID);
    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(KaleidoscopeWitchcraft.MOD_ID);
    private static final DeferredRegister<BlockEntityType<?>> ENTITIES = DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, KaleidoscopeWitchcraft.MOD_ID);
    public static final DeferredBlock<ConduitBlock> BLOCK = BLOCKS.register("essentia_conduit",
            () -> new ConduitBlock(BlockBehaviour.Properties.of().strength(.5F).sound(SoundType.METAL).noOcclusion().noLootTable()));
    public static final DeferredItem<BlockItem> ITEM = ITEMS.registerSimpleBlockItem(BLOCK);
    public static final java.util.function.Supplier<BlockEntityType<ConduitEntity>> ENTITY = ENTITIES.register("essentia_conduit",
            () -> BlockEntityType.Builder.of(ConduitEntity::new, BLOCK.get()).build(null));
    public static void register(IEventBus bus) {
        BLOCKS.register(bus); ITEMS.register(bus); ENTITIES.register(bus);
        ConduitBlock.registerWandInteraction();
        bus.addListener((net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent event) -> event.enqueueWork(() ->
            dev.senluo.witchcraft.time.WorkSources.registerProcess(ConduitEntity.class, new dev.senluo.witchcraft.time.WorkSources.Process<ConduitEntity>() {
                public double price(net.minecraft.server.level.ServerLevel level, ConduitEntity entity) { return 0; }
                public Runnable batch(net.minecraft.server.level.ServerLevel level, ConduitEntity entity, int steps) { return entity.batch(level, steps); }
                public boolean supportsSteam() { return true; }
                public boolean outputWork(net.minecraft.server.level.ServerLevel level, ConduitEntity entity) { return true; }
            })));
        bus.addListener((net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent event) -> {
            if (event.getTab() == dev.senluo.witchcraft.time.TemporalContent.CREATIVE_TAB.get()) event.accept(ITEM.get());
        });
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.tick.LevelTickEvent.Post event) -> {
            if (event.getLevel() instanceof net.minecraft.server.level.ServerLevel level) ConduitNetworks.tick(level);
        });
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.level.LevelEvent.Unload event) -> {
            if (event.getLevel() instanceof net.minecraft.server.level.ServerLevel level) ConduitNetworks.unload(level);
        });
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.level.ChunkEvent.Load event) -> {
            if (event.getLevel() instanceof net.minecraft.server.level.ServerLevel level) ConduitNetworks.chunkChanged(level, event.getChunk().getPos());
        });
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.level.ChunkEvent.Unload event) -> {
            if (event.getLevel() instanceof net.minecraft.server.level.ServerLevel level) ConduitNetworks.chunkChanged(level, event.getChunk().getPos());
        });
    }
    private ConduitContent() {}
}
