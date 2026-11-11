package dev.senluo.witchcraft.time;

import java.util.List;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredItem;

/** Four parallel fruits; visual channels share the ordinary wood membership and behavior. */
public final class FruitWoods {
    public record Family(int grade, DeferredBlock<FruitWoodBlock> node, DeferredBlock<FruitWoodBlock> channel,
                         DeferredItem<BlockItem> nodeItem, DeferredItem<BlockItem> channelItem) {}
    public static final List<Family> FAMILIES = List.of(register("dawn", 1), register("honey", 2),
            register("ice", 3), register("dusk", 4));
    private FruitWoods() {}
    private static Family register(String name, int grade) {
        var node = TemporalContent.BLOCKS.register(name + "_years_node", () ->
                new FruitWoodBlock(BlockBehaviour.Properties.ofFullCopy(Blocks.OAK_LOG), grade, true));
        var channel = TemporalContent.BLOCKS.register(name + "_years_channel", () ->
                new FruitWoodBlock(BlockBehaviour.Properties.ofFullCopy(Blocks.OAK_LOG), grade, false));
        var nodeItem = TemporalContent.ITEMS.register(name + "_years_node", () -> new BlockItem(node.get(), new Item.Properties()));
        var channelItem = TemporalContent.ITEMS.register(name + "_years_channel", () -> new BlockItem(channel.get(), new Item.Properties()));
        return new Family(grade, node, channel, nodeItem, channelItem);
    }
    public static void bootstrap() {} // Called before deferred registers join the mod bus.
    public static int nodeGrade(BlockState state) {
        return state.getBlock() instanceof FruitWoodBlock wood && wood.node() ? wood.fruit() : 0;
    }
    /** A harvested nurturing node leaves only its wood identity in the tree's repair plan. */
    public static BlockState regrowth(BlockState state) {
        int grade = nodeGrade(state);
        if (grade == 0) return state;
        return FAMILIES.get(grade - 1).channel().get().defaultBlockState()
                .setValue(net.minecraft.world.level.block.RotatedPillarBlock.AXIS,
                        state.getValue(net.minecraft.world.level.block.RotatedPillarBlock.AXIS));
    }
    public static boolean permits(BlockState state, int grade) {
        return state.is(TreeWood.LOGS) && (!(state.getBlock() instanceof FruitWoodBlock wood) || wood.fruit() == grade);
    }
    public static BlockState channel(BlockState state, int grade) {
        if (grade > 4 || !state.is(TemporalContent.TIME_LOG.get())) return state;
        return FAMILIES.get(grade - 1).channel().get().defaultBlockState()
                .setValue(net.minecraft.world.level.block.RotatedPillarBlock.AXIS,
                        state.getValue(net.minecraft.world.level.block.RotatedPillarBlock.AXIS));
    }
}
