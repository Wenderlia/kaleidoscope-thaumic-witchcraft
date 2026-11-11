package dev.senluo.witchcraft.compat.thaumcraft;

import dev.senluo.witchcraft.time.TemporalWorld;
import dev.tc4port.thaumcraft.api.ThaumcraftTags;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.AbstractSkullBlock;
import net.minecraft.world.level.block.state.BlockState;

/** Tags describe static categories; living wood needs its current world connection. */
public final class InfusionStabilizers {
    private InfusionStabilizers() {}
    public static boolean ordinary(BlockState state) {
        return state.getBlock() instanceof AbstractSkullBlock || state.is(ThaumcraftTags.INFUSION_STABILIZERS);
    }
    public static boolean matches(ServerLevel level, BlockPos pos, BlockState state) {
        // An external broad tag must not turn disconnected time wood into a permanent stabilizer.
        if (state.is(dev.senluo.witchcraft.time.TreeWood.LOGS)) return TemporalWorld.get(level).connectedTreeWood(pos);
        return ordinary(state);
    }
}
