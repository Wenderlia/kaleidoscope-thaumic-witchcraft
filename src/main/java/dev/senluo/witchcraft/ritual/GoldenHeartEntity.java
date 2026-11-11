package dev.senluo.witchcraft.ritual;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/** Physical anchor and interaction feedback; ritual scheduling belongs to the common level clock. */
public final class GoldenHeartEntity extends BlockEntity {
    private long nextInteractionSound;
    public GoldenHeartEntity(BlockPos pos, BlockState state) { super(RitualContent.GOLDEN_HEART_ENTITY.get(), pos, state); }
    public RitualCenters.Center center(ServerLevel level) {
        var data = getData(RitualContent.RITUAL_ANCHOR);
        return new RitualCenters.Center(new RitualCenters.Reference(level.dimension().location(),
                worldPosition, data.identity()), worldPosition.below(), data.supported());
    }
    public InteractionResult activate(ServerLevel level, Player player) {
        var result = player instanceof net.minecraft.server.level.ServerPlayer serverPlayer
                ? RitualCenters.activate(serverPlayer, this) : InteractionResult.PASS;
        // An allowed empty-hand click is a start attempt, even when no recipe or array matches.
        if (result == InteractionResult.PASS) result = InteractionResult.FAIL;
        long now = level.getGameTime();
        if ((result.consumesAction() || result == InteractionResult.FAIL) && now >= nextInteractionSound) {
            nextInteractionSound = now + 10; // Shared per heart; holding use cannot flood audio packets.
            RitualSounds.heartInteraction(level, worldPosition, result == InteractionResult.FAIL);
        }
        return result;
    }
    public void removed(ServerLevel level) {
        RitualCenters.removed(level, center(level).reference());
    }
}
