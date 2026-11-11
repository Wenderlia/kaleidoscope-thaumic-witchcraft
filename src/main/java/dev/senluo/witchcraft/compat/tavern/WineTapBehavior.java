package dev.senluo.witchcraft.compat.tavern;

import com.github.ysbbbbbb.kaleidoscopetavern.api.blockentity.ITapBehavior;
import com.github.ysbbbbbb.kaleidoscopetavern.block.brew.BarrelBlock;
import com.github.ysbbbbbb.kaleidoscopetavern.block.brew.TapBlock;
import com.github.ysbbbbbb.kaleidoscopetavern.game.tap.TapBehaviorManager;
import com.github.ysbbbbbb.kaleidoscopetavern.init.ModBlocks;
import dev.senluo.witchcraft.KaleidoscopeWitchcraft;
import dev.senluo.witchcraft.brew.WineParticles;
import dev.senluo.witchcraft.time.TemporalContent;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;
import org.slf4j.LoggerFactory;

/** Compose the registered behavior; never replace its matching or extraction logic. */
public final class WineTapBehavior implements ITapBehavior {
    private final ITapBehavior delegate;

    private WineTapBehavior(ITapBehavior delegate) { this.delegate = delegate; }

    /** Called after common setup, where Tavern registers its native tap behaviors. */
    public static void register() {
        var block = ModBlocks.BARREL.get();
        ITapBehavior current = TapBehaviorManager.get(block.defaultBlockState());
        if (current instanceof WineTapBehavior) return;
        if (current == null) {
            LoggerFactory.getLogger(KaleidoscopeWitchcraft.MOD_ID).warn(
                    "No registered barrel tap behavior; leaving wine particle compatibility inactive");
            return;
        }
        TapBehaviorManager.register(block, new WineTapBehavior(current));
    }

    @Override
    public boolean isMatch(Level level, @Nullable Player player, BlockPos tapPos, BlockState tapState,
                           BlockState sourceState, BlockState destinationState) {
        return delegate.isMatch(level, player, tapPos, tapState, sourceState, destinationState);
    }

    @Override
    public @Nullable ParticleOptions onStartExtract(Level level, @Nullable Player player, BlockPos tapPos,
            BlockState tapState, BlockState sourceState, BlockState destinationState) {
        ParticleOptions particle = delegate.onStartExtract(level, player, tapPos, tapState, sourceState, destinationState);
        if (particle == null || !(sourceState.getBlock() instanceof BarrelBlock)) return particle;
        BlockPos source = tapPos.relative(tapState.getValue(TapBlock.FACING).getOpposite());
        var barrel = BarrelBlock.getBarrelEntity(level, source, sourceState);
        return barrel != null && barrel.getOutput().getStackInSlot(0).is(TemporalContent.LIUJIN_VODKA.get())
                ? WineParticles.TAP.get() : particle;
    }

    @Override
    public void onEndExtract(Level level, BlockPos tapPos, BlockState tapState,
                             BlockState sourceState, BlockState destinationState) {
        delegate.onEndExtract(level, tapPos, tapState, sourceState, destinationState);
    }
}
