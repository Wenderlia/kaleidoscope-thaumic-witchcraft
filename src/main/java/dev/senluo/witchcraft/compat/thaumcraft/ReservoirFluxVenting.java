package dev.senluo.witchcraft.compat.thaumcraft;

import dev.tc4port.thaumcraft.api.flux.FluxApi;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import dev.tc4port.thaumcraft.api.ThaumcraftContent;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import java.util.Optional;

/** Bounded, loaded-cell outlet discovery. Never overwrite terrain or replace existing gas. */
final class ReservoirFluxVenting {
    private static final ResourceLocation GAS = ResourceLocation.fromNamespaceAndPath("thaumcraft", "flux_gas");
    private static boolean partial(FluidState state, Fluid gas) {
        return state.getType() == gas && !state.isSource();
    }
    record Outlet(BlockPos pos,boolean existing) {}
    static Optional<Outlet> outlet(ServerPlayer player){
        var level=player.serverLevel();var gas = BuiltInRegistries.FLUID.getOptional(GAS).orElse(null);
        if (gas == null) return Optional.empty();
        BlockPos torso=player.blockPosition().above();Direction back=player.getDirection().getOpposite();
        BlockPos[] candidates={torso.relative(back),torso,torso.above(),torso.north(),torso.south(),torso.west(),torso.east()};
        for(BlockPos pos:candidates){
            if(!level.hasChunkAt(pos) || level.isOutsideBuildHeight(pos) || !level.getWorldBorder().isWithinBounds(pos))continue;
            var state=level.getBlockState(pos);
            if(state.isAir())return Optional.of(new Outlet(pos,false));
            if(partial(state.getFluidState(), gas))return Optional.of(new Outlet(pos,true));
        }
        return Optional.empty();
    }
    static boolean emit(ServerPlayer player,Outlet outlet){
        var level=player.serverLevel();
        if (!level.hasChunkAt(outlet.pos()) || level.isOutsideBuildHeight(outlet.pos())
                || !level.getWorldBorder().isWithinBounds(outlet.pos())) return false;
        var gas = BuiltInRegistries.FLUID.getOptional(GAS).orElse(null);
        if (gas == null) return false;
        var state=level.getBlockState(outlet.pos());
        boolean placed=outlet.existing()
                ?partial(state.getFluidState(), gas) && FluxApi.add(level,outlet.pos(),1)
                :state.isAir() && FluxApi.place(level,outlet.pos(),gas,1);
        if(placed)level.playSound(null,outlet.pos(),ThaumcraftContent.sound("block.infusion_matrix.flux_gas"),SoundSource.PLAYERS,.3F,1F);
        return placed;
    }
    private ReservoirFluxVenting(){}
}
