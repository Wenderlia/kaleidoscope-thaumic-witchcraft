package dev.senluo.witchcraft.time;

import dev.senluo.witchcraft.KaleidoscopeWitchcraft;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.properties.*;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.event.BlockEntityTypeAddBlocksEvent;
import net.neoforged.neoforge.registries.*;

/** Standard building forms; native blocks own placement, collision and redstone. */
public final class YearsWoodContent {
    public static final WoodType WOOD_TYPE = WoodType.register(new WoodType(
            KaleidoscopeWitchcraft.MOD_ID + ":years", BlockSetType.OAK));
    private static final List<DeferredItem<? extends Item>> ITEMS = new ArrayList<>();
    public static final DeferredBlock<TreePartBlock> WOOD = block("years_wood",
            () -> new TreePartBlock(copy(Blocks.OAK_WOOD)));
    public static final DeferredBlock<TreePartBlock> STRIPPED_WOOD = block("stripped_years_wood",
            () -> new TreePartBlock(copy(Blocks.STRIPPED_OAK_WOOD)));
    public static final DeferredBlock<YearsBuildingBlocks.Stairs> STAIRS = block("years_stairs",
            () -> new YearsBuildingBlocks.Stairs(copy(Blocks.OAK_STAIRS)));
    public static final DeferredBlock<YearsBuildingBlocks.Slab> SLAB = block("years_slab",
            () -> new YearsBuildingBlocks.Slab(copy(Blocks.OAK_SLAB)));
    public static final DeferredBlock<YearsBuildingBlocks.Fence> FENCE = block("years_fence",
            () -> new YearsBuildingBlocks.Fence(copy(Blocks.OAK_FENCE)));
    public static final DeferredBlock<YearsBuildingBlocks.Gate> GATE = block("years_fence_gate",
            () -> new YearsBuildingBlocks.Gate(copy(Blocks.OAK_FENCE_GATE)));
    public static final DeferredBlock<YearsBuildingBlocks.Button> BUTTON = block("years_button",
            () -> new YearsBuildingBlocks.Button(copy(Blocks.OAK_BUTTON)));
    public static final DeferredBlock<YearsBuildingBlocks.Plate> PLATE = block("years_pressure_plate",
            () -> new YearsBuildingBlocks.Plate(copy(Blocks.OAK_PRESSURE_PLATE)));
    public static final DeferredBlock<YearsBuildingBlocks.Door> DOOR = block("years_door",
            () -> new YearsBuildingBlocks.Door(copy(Blocks.OAK_DOOR)), DoubleHighBlockItem::new);
    public static final DeferredBlock<YearsBuildingBlocks.Trapdoor> TRAPDOOR = block("years_trapdoor",
            () -> new YearsBuildingBlocks.Trapdoor(copy(Blocks.OAK_TRAPDOOR)));
    public static final DeferredBlock<YearsBuildingBlocks.Sign> SIGN = TemporalContent.BLOCKS.register("years_sign",
            () -> new YearsBuildingBlocks.Sign(copy(Blocks.OAK_SIGN)));
    public static final DeferredBlock<YearsBuildingBlocks.WallSign> WALL_SIGN = TemporalContent.BLOCKS.register("years_wall_sign",
            () -> new YearsBuildingBlocks.WallSign(copy(Blocks.OAK_WALL_SIGN).dropsLike(SIGN.get())));
    public static final DeferredBlock<YearsBuildingBlocks.HangingSign> HANGING_SIGN = TemporalContent.BLOCKS.register("years_hanging_sign",
            () -> new YearsBuildingBlocks.HangingSign(copy(Blocks.OAK_HANGING_SIGN)));
    public static final DeferredBlock<YearsBuildingBlocks.WallHangingSign> WALL_HANGING_SIGN = TemporalContent.BLOCKS.register("years_wall_hanging_sign",
            () -> new YearsBuildingBlocks.WallHangingSign(copy(Blocks.OAK_WALL_HANGING_SIGN).dropsLike(HANGING_SIGN.get())));
    public static final DeferredItem<SignItem> SIGN_ITEM = TemporalContent.ITEMS.register("years_sign",
            () -> new SignItem(new Item.Properties().stacksTo(16), SIGN.get(), WALL_SIGN.get()));
    public static final DeferredItem<HangingSignItem> HANGING_SIGN_ITEM = TemporalContent.ITEMS.register("years_hanging_sign",
            () -> new HangingSignItem(HANGING_SIGN.get(), WALL_HANGING_SIGN.get(), new Item.Properties().stacksTo(16)));

    private YearsWoodContent() {}
    private static BlockBehaviour.Properties copy(Block source) { return BlockBehaviour.Properties.ofFullCopy(source); }
    private static <B extends Block> DeferredBlock<B> block(String name, Supplier<B> factory) {
        return block(name,factory,BlockItem::new);
    }
    private static <B extends Block> DeferredBlock<B> block(String name, Supplier<B> factory,
            java.util.function.BiFunction<Block,Item.Properties,BlockItem> itemFactory) {
        var block = TemporalContent.BLOCKS.register(name, factory);
        ITEMS.add(TemporalContent.ITEMS.register(name, () -> itemFactory.apply(block.get(), new Item.Properties())));
        return block;
    }
    public static void display(CreativeModeTab.Output output) {
        ITEMS.forEach(item -> output.accept(item.get()));
        output.accept(SIGN_ITEM.get()); output.accept(HANGING_SIGN_ITEM.get());
    }
    public static void register(IEventBus bus) {
        bus.addListener((BlockEntityTypeAddBlocksEvent event) -> {
            event.modify(BlockEntityType.SIGN, SIGN.get(), WALL_SIGN.get());
            event.modify(BlockEntityType.HANGING_SIGN, HANGING_SIGN.get(), WALL_HANGING_SIGN.get());
        });
        bus.addListener((FMLCommonSetupEvent event) -> event.enqueueWork(() -> {
            FireBlock fire = (FireBlock) Blocks.FIRE;
            for (var block : List.of(TemporalContent.TIME_LOG, TemporalContent.STRIPPED_TIME_LOG, WOOD, STRIPPED_WOOD))
                fire.setFlammable(block.get(), 5, 5);
            for (var block : List.of(TemporalContent.TIME_PLANKS, STAIRS, SLAB, FENCE, GATE))
                fire.setFlammable(block.get(), 5, 20);
        }));
    }
}
