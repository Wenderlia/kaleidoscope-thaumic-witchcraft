package dev.senluo.witchcraft.compat.thaumcraft;

import dev.senluo.witchcraft.time.TemporalWorld;
import dev.senluo.witchcraft.ritual.RuneStabilization;
import dev.tc4port.thaumcraft.api.ThaumcraftTags;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.AbstractSkullBlock;
import net.minecraft.world.level.block.state.BlockState;

/** Tags describe static categories; living wood needs its current world connection. */
public final class InfusionStabilizers {
    public static final RuneStabilization.Stabilizer RUNES = new RuneStabilization.Stabilizer() {
        @Override public boolean matches(ServerLevel level, BlockPos pos, BlockState state) {
            return InfusionStabilizers.matches(level, pos, state);
        }
        @Override public RuneStabilization.Evidence observe(ServerLevel level, BlockPos pos, BlockState state) {
            if (state.is(dev.senluo.witchcraft.time.TreeWood.LOGS)) {
                var proof = TemporalWorld.get(level).treeWoodConnection(pos);
                return new RuneStabilization.Evidence(proof.connected(), proof);
            }
            return RuneStabilization.Evidence.material(ordinary(state));
        }
    };
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
