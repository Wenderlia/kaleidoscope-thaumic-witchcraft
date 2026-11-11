package dev.senluo.witchcraft.time;

import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/** The root itself is planting material, not a linking tool. */
public final class RootItem extends Item {
    public RootItem(Properties properties) { super(properties); }
    public static ItemStack stack() {
        return new ItemStack(TemporalContent.ROOT_PIECE.get());
    }
    @Override public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        TemporalTooltips.summary(stack, tooltip);
    }
    @Override public InteractionResult useOn(UseOnContext context) {
        var world = context.getLevel();
        var player = context.getPlayer();
        BlockPos clicked = context.getClickedPos();
        BlockState clickedState = world.getBlockState(clicked);
        BlockPos pos = TissueItem.rootSoil(clickedState) || clickedState.is(Blocks.FARMLAND) ? clicked : clicked.relative(context.getClickedFace());
        BlockState old = world.getBlockState(pos);
        if (player == null || !player.mayBuild() || !world.mayInteract(player, pos)
                || !player.mayUseItemAt(pos, context.getClickedFace(), context.getItemInHand())
                || !(old.isAir() || TissueItem.rootSoil(old) || old.is(Blocks.FARMLAND)) || world.getBlockEntity(pos) != null) return InteractionResult.FAIL;
        if (world.isClientSide) return InteractionResult.SUCCESS;
        BlockState placed = old.is(Blocks.FARMLAND) ? RootedFarmlandBlock.fromFarmland(old)
                : TemporalContent.ROOT.get().defaultBlockState().setValue(RootBlock.EMBEDDED, !old.isAir());
        if (!world.setBlock(pos, placed, 3)) return InteractionResult.FAIL;
        if (world.getBlockEntity(pos) instanceof RootEntity root) {
            root.ticks = 0;
            root.owner = null; // A placed conduit is not an ownership ceremony.
            root.distance = 1;
            root.setChanged();
            TemporalWorld.get((ServerLevel) world).add(root);
        }
        context.getItemInHand().consume(1, player);
        return InteractionResult.CONSUME;
    }
}
