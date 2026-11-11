package dev.senluo.witchcraft.mixin.ritual;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.senluo.witchcraft.ritual.TntOrigins;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(BlockItem.class)
public abstract class PlacedExplosiveBlockMixin {
    @WrapMethod(method = "placeBlock")
    private boolean witchcraft$placer(BlockPlaceContext use, BlockState state, Operation<Boolean> original) {
        if (!state.is(Blocks.TNT) || !(use.getPlayer() instanceof ServerPlayer player)) return original.call(use, state);
        var level = use.getLevel(); var pos = use.getClickedPos();
        var previous = TntOrigins.owner(level, pos);
        // Set before onPlace: a powered position can prime TNT synchronously during setBlock.
        TntOrigins.put(level, pos, player.getUUID());
        boolean committed = false;
        try { committed = original.call(use, state); return committed; }
        finally {
            if (!committed) TntOrigins.put(level, pos, previous);
            else if (!TntOrigins.carries(level.getBlockState(pos))) TntOrigins.put(level, pos, null);
        }
    }
}
