package dev.senluo.witchcraft.compat.tavern;

import com.github.ysbbbbbb.kaleidoscopetavern.block.brew.DrinkBlock;
import com.github.ysbbbbbb.kaleidoscopetavern.init.ModBlocks;
import com.github.ysbbbbbb.kaleidoscopetavern.item.BottleBlockItem;
import dev.senluo.witchcraft.KaleidoscopeWitchcraft;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.BlockEntityTypeAddBlocksEvent;
import net.neoforged.neoforge.registries.*;

/** Loaded only with Tavern; retains its drink item, block and persisted bottle inventory. */
public final class TavernWine {
    private static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(KaleidoscopeWitchcraft.MOD_ID);
    public static final DeferredBlock<DrinkBlock> BOTTLE = BLOCKS.register("liujin_vodka", () -> new DrinkBlock(4,
            shape(new double[][]{{0,0}}), shape(new double[][]{{-3.5,0},{3.5,0}}),
            shape(new double[][]{{-3.5,3.5},{3.5,3.5},{0,-3.5}}),
            shape(new double[][]{{-3.5,-3.5},{3.5,-3.5},{-3.5,3.5},{3.5,3.5}})));
    private static VoxelShape shape(double[][] offsets) {
        VoxelShape result = Shapes.empty();
        for (double[] offset : offsets) result = Shapes.or(result, Block.box(5+offset[0],0,5+offset[1],11+offset[0],16,11+offset[1]));
        return result;
    }
    public static Item createItem() { return new TavernWineItem(BOTTLE.get()); }
    private static final java.util.Map<dev.senluo.witchcraft.brew.AmberVintage, DeferredBlock<DrinkBlock>> AMBER = new java.util.EnumMap<>(dev.senluo.witchcraft.brew.AmberVintage.class);
    static {
        for (var vintage : dev.senluo.witchcraft.brew.AmberVintage.values())
            AMBER.put(vintage, BLOCKS.register(vintage.wine, () -> new DrinkBlock(4,
                    amberShape(vintage,new double[][]{{0,0}}), amberShape(vintage,new double[][]{{-3.5,0},{3.5,0}}),
                    amberShape(vintage,new double[][]{{-3.5,3.5},{3.5,3.5},{0,-3.5}}),
                    amberShape(vintage,new double[][]{{-3.5,-3.5},{3.5,-3.5},{-3.5,3.5},{3.5,3.5}}))));
    }
    private static VoxelShape amberShape(dev.senluo.witchcraft.brew.AmberVintage vintage, double[][] offsets) {
        VoxelShape result=Shapes.empty();
        for (double[] offset : offsets) result=Shapes.or(result,Block.box(vintage.minX+offset[0],0,vintage.minZ+offset[1],
                vintage.maxX+offset[0],vintage.height,vintage.maxZ+offset[1]));
        return result;
    }
    public static Item createAmberItem(dev.senluo.witchcraft.brew.AmberVintage vintage) {
        return new AmberWineItem(AMBER.get(vintage).get(), vintage);
    }
    public static void setQuality(ItemStack stack, int quality) { BottleBlockItem.setBrewLevel(stack, quality); }
    public static void register(IEventBus bus) {
        BLOCKS.register(bus);
        bus.addListener((BlockEntityTypeAddBlocksEvent event) -> event.modify(ModBlocks.DRINK_BE.get(), BOTTLE.get()));
        bus.addListener((BlockEntityTypeAddBlocksEvent event) -> AMBER.values().forEach(block -> event.modify(ModBlocks.DRINK_BE.get(), block.get())));
    }
    private TavernWine() {}
}
