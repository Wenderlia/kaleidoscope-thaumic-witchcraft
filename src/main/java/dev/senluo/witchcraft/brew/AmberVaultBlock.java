package dev.senluo.witchcraft.brew;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

public final class AmberVaultBlock extends BaseEntityBlock {
    public static final BooleanProperty BRIGHT=BooleanProperty.create("bright");
    private static final MapCodec<AmberVaultBlock> CODEC=simpleCodec(AmberVaultBlock::new);
    public AmberVaultBlock(Properties properties){super(properties);registerDefaultState(defaultBlockState().setValue(BRIGHT,false));}
    @Override protected MapCodec<? extends BaseEntityBlock> codec(){return CODEC;}
    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block,BlockState> builder){builder.add(BRIGHT);}
    @Override protected RenderShape getRenderShape(BlockState state){return RenderShape.MODEL;}
    @Override public BlockEntity newBlockEntity(BlockPos pos,BlockState state){return new AmberVaultBlockEntity(pos,state);}
    private static final VoxelShape OUTLINE=box(2.10,.2,2.98,13.90,12.6,13.02);
    @Override protected VoxelShape getShape(BlockState state,BlockGetter level,BlockPos pos,CollisionContext context){return OUTLINE;}
    @Override public boolean canEntityDestroy(BlockState state,BlockGetter level,BlockPos pos,Entity entity){return false;}
    @Override protected InteractionResult useWithoutItem(BlockState state,Level level,BlockPos pos,Player player,BlockHitResult hit){
        if(!level.isClientSide && level.getBlockEntity(pos) instanceof AmberVaultBlockEntity vault) AmberVaults.open(player,vault.contents);
        return InteractionResult.sidedSuccess(level.isClientSide);
    }
    @Override protected void tick(BlockState state,net.minecraft.server.level.ServerLevel level,BlockPos pos,net.minecraft.util.RandomSource random){
        if(level.getBlockEntity(pos) instanceof AmberVaultBlockEntity vault)AmberVaultBlockEntity.tick(level,pos,state,vault);
    }
}
