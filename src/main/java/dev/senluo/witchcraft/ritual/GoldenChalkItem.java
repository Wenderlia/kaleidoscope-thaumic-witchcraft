package dev.senluo.witchcraft.ritual;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.context.UseOnContext;

/** The center is its own host; drawing it never changes an ordinary rune or an engraving. */
public final class GoldenChalkItem extends ChalkUsage.Item {
    public GoldenChalkItem(Properties properties) { super(properties); }
    @Override public InteractionResult useOn(UseOnContext context) {
        var level = context.getLevel();
        BlockPos support = context.getClickedPos(), layer = support.above();
        if (context.getClickedFace() != Direction.UP || !level.hasChunkAt(support) || !level.hasChunkAt(layer)
                || !RitualChalk.permitted(context.getPlayer(), level, support, context.getItemInHand())
                || !RitualChalk.permitted(context.getPlayer(), level, layer, context.getItemInHand())
                || RitualChalk.surface(level, support, level.getBlockState(support)) < 0
                || !level.isEmptyBlock(layer)) return InteractionResult.FAIL;
        var chunk = RitualChalk.chunk(level, support);
        if (chunk == null) return InteractionResult.FAIL;
        var marks = chunk.getExistingDataOrNull(RitualContent.CHALK_MARKS);
        if (marks != null && (!marks.supported() || marks.get(support) != null)) return InteractionResult.FAIL;
        if (level.isClientSide) return InteractionResult.SUCCESS;
        var state = RitualContent.GOLDEN_HEART.get().defaultBlockState()
                .setValue(GoldenHeartBlock.ROTATION, ChalkPattern.rotationFor(context.getHorizontalDirection()));
        if (!level.setBlock(layer, state, 3)) return InteractionResult.FAIL;
        ChalkUsage.consume(context);
        RitualSounds.chalk(level, layer);
        return InteractionResult.CONSUME;
    }
}
