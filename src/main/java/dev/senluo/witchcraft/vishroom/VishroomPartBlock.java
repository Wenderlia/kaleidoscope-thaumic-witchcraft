package dev.senluo.witchcraft.vishroom;

import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.BlockItemStateProperties;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.phys.shapes.*;

/** Native baked block models; each cap underside selects its real 16px atlas region. */
public final class VishroomPartBlock extends Block {
    public static final IntegerProperty PART = IntegerProperty.create("part", 0, VishroomShape.PARTS - 1);
    private static final VoxelShape[] SHAPES = java.util.Arrays.stream(VishroomShape.BOXES).map(boxes -> {
        VoxelShape shape = Shapes.empty();
        for (double[] b : boxes) shape = Shapes.or(shape, Block.box(b[0],b[1],b[2],b[3],b[4],b[5]));
        return shape.optimize();
    }).toArray(VoxelShape[]::new);
    public VishroomPartBlock(Properties properties) {
        super(properties); registerDefaultState(stateDefinition.any().setValue(PART, VishroomShape.STEM));
    }
    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) { builder.add(PART); }
    @Override protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) { return SHAPES[state.getValue(PART)]; }
    @Override public ItemStack getCloneItemStack(LevelReader level, BlockPos pos, BlockState state) {
        var item = new ItemStack(VishroomContent.PART_ITEM.get());
        item.set(DataComponents.BLOCK_STATE, BlockItemStateProperties.EMPTY.with(PART, state));
        return item;
    }
    public static float itemVariant(ItemStack stack) {
        Integer value = stack.getOrDefault(DataComponents.BLOCK_STATE, BlockItemStateProperties.EMPTY).get(PART);
        return (value == null ? VishroomShape.STEM : value) / (float)(VishroomShape.PARTS - 1);
    }
    @Override public String getDescriptionId() { return "block.thaumcraft.vishroom"; }
}
