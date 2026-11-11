package dev.senluo.witchcraft.mixin.ritual;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.senluo.witchcraft.ritual.TntOrigins;
import java.util.Map;
import java.util.function.BiConsumer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockBehaviour;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(BlockBehaviour.BlockStateBase.class)
public abstract class ExplodedBlockOriginMixin {
    @WrapMethod(method = "onExplosionHit")
    private void witchcraft$origin(Level level, BlockPos pos, Explosion explosion, BiConsumer<ItemStack, BlockPos> drops, Operation<Void> original) {
        var owner = level.getBlockState(pos).is(Blocks.TNT) ? TntOrigins.owner(level, pos) : null;
        if (owner == null) { original.call(level, pos, explosion, drops); return; }
        // Native onBlockExploded removes the block BEFORE wasExploded creates the primed entity.
        TntOrigins.during(level, Map.of(pos.immutable(), owner), () -> { original.call(level, pos, explosion, drops); return null; });
    }
}
