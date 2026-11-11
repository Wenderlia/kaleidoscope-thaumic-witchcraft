package dev.senluo.witchcraft.mixin.time;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.senluo.witchcraft.time.TemporalWorld;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
@Mixin(Level.class)
public abstract class TreeGeometryMixin {
    @WrapMethod(method="setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z")
    private boolean senluo$geometry(BlockPos pos,BlockState state,int flags,int recursion,Operation<Boolean> original) {
        if(!((Object)this instanceof ServerLevel level))return original.call(pos,state,flags,recursion);
        BlockState before=level.getBlockState(pos);
        dev.senluo.witchcraft.time.TreeSignMemory.beforeReplacement(level,pos,before,state);
        boolean changed=original.call(pos,state,flags,recursion);
        if(changed){
            dev.senluo.witchcraft.region.RegionRules.blockChanged(level,pos);
            state = level.getBlockState(pos);
            if (before.getBlock() != state.getBlock()) dev.senluo.witchcraft.ritual.SteamFire.replaced(level,pos);
            dev.senluo.witchcraft.ritual.RitualChalk.blockChanged(level,pos,before,state);
            if (before != state && net.neoforged.fml.ModList.get().isLoaded("thaumcraft"))
                dev.senluo.witchcraft.compat.thaumcraft.CrucibleRitualAdapter.blockChanged(level, pos, before, state);
            TemporalWorld world = TemporalWorld.get(level);
            world.blockChanged(pos,before,state);
            world.trees.geometryChanged(pos,before,state);
            dev.senluo.witchcraft.time.FluidTime.changed(level);
        }
        return changed;
    }
}
