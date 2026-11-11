package dev.senluo.witchcraft.mixin.ritual;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.senluo.witchcraft.ritual.TntOrigins;
import java.util.*;
import net.minecraft.core.*;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.piston.*;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(PistonBaseBlock.class)
public abstract class PistonExplosiveOriginMixin {
    @WrapMethod(method = "moveBlocks")
    private boolean witchcraft$carry(Level level, BlockPos base, Direction facing, boolean extending, Operation<Boolean> original) {
        if (level.isClientSide) return original.call(level, base, facing, extending);
        var resolver = new PistonStructureResolver(level, base, facing, extending);
        if (!resolver.resolve()) return original.call(level, base, facing, extending);
        Map<BlockPos, UUID> from = new HashMap<>(), to = new HashMap<>();
        Direction movement = extending ? facing : facing.getOpposite();
        for (BlockPos pos : resolver.getToPush()) if (level.getBlockState(pos).is(Blocks.TNT)) {
            UUID owner = TntOrigins.owner(level, pos);
            if (owner != null) { from.put(pos.immutable(), owner); to.put(pos.relative(movement), owner); }
        }
        if (from.isEmpty()) return original.call(level, base, facing, extending);
        return TntOrigins.during(level, to, () -> {
            boolean moved = original.call(level, base, facing, extending);
            if (moved) {
                from.keySet().forEach(pos -> TntOrigins.put(level, pos, null));
                to.forEach((pos, owner) -> { if (TntOrigins.carries(level.getBlockState(pos))) TntOrigins.put(level, pos, owner); });
            }
            return moved;
        });
    }
}
