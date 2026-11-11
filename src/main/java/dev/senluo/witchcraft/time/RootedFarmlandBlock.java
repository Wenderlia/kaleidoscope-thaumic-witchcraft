package dev.senluo.witchcraft.time;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.FarmBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.gameevent.GameEvent;

/** A real farmland block sharing the ordinary root's entity and connection rules. */
public final class RootedFarmlandBlock extends FarmBlock implements EntityBlock {
    public RootedFarmlandBlock(Properties properties) {
        super(properties);
        BlockState state = defaultBlockState().setValue(RootBlock.AGE, 0);
        for (Direction direction : Direction.values()) state = state.setValue(RootBlock.connection(direction), false);
        registerDefaultState(state);
    }

    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        super.createBlockStateDefinition(builder);
        builder.add(RootBlock.AGE);
        for (Direction direction : Direction.values()) builder.add(RootBlock.connection(direction));
    }

    public static BlockState fromFarmland(BlockState soil) {
        return TemporalContent.ROOTED_FARMLAND.get().defaultBlockState().setValue(MOISTURE, soil.getValue(MOISTURE));
    }

    public static BlockState fromRoot(BlockState root) {
        return TemporalContent.ROOTED_FARMLAND.get().defaultBlockState().setValue(RootBlock.AGE, root.getValue(RootBlock.AGE));
    }

    /** FarmBlock still decides when to dry, trample or flatten; only the resulting soil changes. */
    public static void turnToRootedDirt(Entity entity, BlockState state, Level level, BlockPos pos) {
        BlockState soil = TemporalContent.ROOT.get().defaultBlockState().setValue(RootBlock.EMBEDDED, true)
                .setValue(RootBlock.AGE, state.getValue(RootBlock.AGE));
        level.setBlockAndUpdate(pos, Block.pushEntitiesUp(state, soil, level, pos));
        level.gameEvent(GameEvent.BLOCK_CHANGE, pos, GameEvent.Context.of(entity, soil));
    }

    @Override public BlockEntity newBlockEntity(BlockPos pos, BlockState state) { return new RootEntity(pos, state); }

    @Override protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState previous, boolean moved) {
        super.onPlace(state, level, pos, previous, moved);
        if (!state.is(previous.getBlock())) RootBlock.refresh(level, pos);
    }

    @Override protected BlockState updateShape(BlockState state, Direction direction, BlockState neighbor,
                                               LevelAccessor level, BlockPos pos, BlockPos other) {
        return super.updateShape(state, direction, neighbor, level, pos, other)
                .setValue(RootBlock.connection(direction), RootConnections.connects(level, pos, direction));
    }

    @Override public ItemStack getCloneItemStack(LevelReader level, BlockPos pos, BlockState state) { return RootItem.stack(); }

    @Override protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState replacement, boolean moved) {
        if (level.restoringBlockSnapshots) {
            if (!RootConnections.isRoot(replacement)) super.onRemove(state, level, pos, replacement, moved);
            return;
        }
        if (RootConnections.isRoot(replacement)) {
            // Both registered blocks accept the same entity. Keep its age, owner and parent.
            if (level instanceof ServerLevel server) TemporalWorld.get(server).invalidateConnections();
            return;
        }
        if (level instanceof ServerLevel server) {
            if (level.getBlockEntity(pos) instanceof RootEntity) {
                popResource(level, pos, RootItem.stack());
                popResource(level, pos, new ItemStack(Items.DIRT));
            }
            TemporalWorld.get(server).remove(pos);
        }
        super.onRemove(state, level, pos, replacement, moved);
    }
}
