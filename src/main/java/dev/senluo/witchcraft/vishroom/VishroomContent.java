package dev.senluo.witchcraft.vishroom;

import dev.senluo.witchcraft.KaleidoscopeWitchcraft;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.*;

/** One organism at the base; ordinary parts neither tick nor store an owner. */
public final class VishroomContent {
    private static final String ID = KaleidoscopeWitchcraft.MOD_ID;
    private static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(ID);
    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(ID);
    private static final DeferredRegister<BlockEntityType<?>> ENTITIES = DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, ID);
    public static final TagKey<Block> SEED = TagKey.create(Registries.BLOCK, ResourceLocation.fromNamespaceAndPath(ID, "vishroom_seed"));
    public static final TagKey<Item> MUSHROOM_MATERIAL = TagKey.create(Registries.ITEM, ResourceLocation.fromNamespaceAndPath(ID, "vishroom_ritual_mushrooms"));
    public static final DeferredBlock<VishroomCoreBlock> CORE = BLOCKS.register("vishroom_core", () -> new VishroomCoreBlock(
            BlockBehaviour.Properties.ofFullCopy(Blocks.MUSHROOM_STEM).randomTicks()));
    public static final DeferredBlock<VishroomPartBlock> PART = BLOCKS.register("vishroom_part", () -> new VishroomPartBlock(
            BlockBehaviour.Properties.ofFullCopy(Blocks.RED_MUSHROOM_BLOCK)));
    public static final DeferredItem<BlockItem> CORE_ITEM = ITEMS.register("vishroom_core", () -> new VishroomCoreItem(CORE.get(), new Item.Properties()));
    public static final DeferredItem<BlockItem> PART_ITEM = ITEMS.registerSimpleBlockItem(PART);
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<VishroomEntity>> ENTITY = ENTITIES.register("vishroom_core",
            () -> BlockEntityType.Builder.of(VishroomEntity::new, CORE.get()).build(null));
    private VishroomContent() {}
    public static void register(IEventBus bus) { BLOCKS.register(bus); ITEMS.register(bus); ENTITIES.register(bus); }
}
