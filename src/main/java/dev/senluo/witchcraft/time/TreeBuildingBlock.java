package dev.senluo.witchcraft.time;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;

/** Ordinary building material; delegates successful player edits to the shared tree structure. */
public final class TreeBuildingBlock extends Block {
    public TreeBuildingBlock(Properties properties){super(properties);}
    @Override public BlockState playerWillDestroy(Level level,BlockPos pos,BlockState state,Player player){
        if(level instanceof ServerLevel server){var trees=TemporalWorld.get(server).trees;trees.beforeWoodRemoval(pos);trees.preparePrune(pos,player);}
        return super.playerWillDestroy(level,pos,state,player);
    }
    @Override public boolean onDestroyedByPlayer(BlockState state,Level level,BlockPos pos,Player player,boolean harvest,FluidState fluid){
        var trees=level instanceof ServerLevel server?TemporalWorld.get(server).trees:null;
        var attempt=trees==null?null:trees.takePrune(pos,player);
        boolean removed=super.onDestroyedByPlayer(state,level,pos,player,harvest,fluid);
        if(removed&&trees!=null&&!level.getBlockState(pos).is(this))trees.finishPrune(attempt,player);
        return removed;
    }
    @Override public void setPlacedBy(Level level,BlockPos pos,BlockState state,net.minecraft.world.entity.LivingEntity placer,net.minecraft.world.item.ItemStack stack){
        super.setPlacedBy(level,pos,state,placer,stack);
        if(level instanceof ServerLevel server)TemporalWorld.get(server).trees.placedWood(pos,placer);
    }
}
