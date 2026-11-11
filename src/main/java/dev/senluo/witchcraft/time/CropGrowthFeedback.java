package dev.senluo.witchcraft.time;

import dev.senluo.witchcraft.compat.CropGrowth;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;

/** Bounded, transient observations of paid work. Queries neither spend TU nor record failures. */
final class CropGrowthFeedback {
    private record Observation(BlockState state, int pressure) {}
    private final Map<BlockPos, Observation> observations = new LinkedHashMap<>();

    int attempts(BlockPos pos, BlockState state) {
        if (CropGrowth.progress(state) < 0) return 1;
        Observation prior = observations.get(pos);
        return prior == null || prior.state() != state ? 1 : 1 << Math.min(4, prior.pressure() / 2);
    }

    void completed(BlockPos pos, BlockState before, BlockState after) {
        if (before != after) {
            // The successful setBlock notification usually arrives inside the native callback.
            // Reuse the same transition path, never count that success twice.
            changed(pos, before, after);
            return;
        }
        if (CropGrowth.progress(after) < 0) {
            observations.remove(pos);
            return;
        }
        Observation prior = observations.get(pos);
        int pressure = prior != null && prior.state() == before ? prior.pressure() : 0;
        remember(pos, after, Math.min(12, pressure + 1));
    }

    private void remember(BlockPos pos, BlockState state, int pressure) {
        observations.remove(pos);
        observations.put(pos.immutable(), new Observation(state, pressure));
        if (observations.size() > 4096) observations.remove(observations.keySet().iterator().next());
    }

    void changed(BlockPos pos, BlockState before, BlockState after) {
        Observation prior = observations.get(pos);
        if (prior == null) return;
        // Preserve experience only for this plant's forward age transition, with all other
        // properties unchanged. Harvest, replacement and environmental state changes reset it.
        var age = before.getBlock().getStateDefinition().getProperty("age");
        boolean advanced = before.getBlock() == after.getBlock()
                && age instanceof net.minecraft.world.level.block.state.properties.IntegerProperty property
                && CropGrowth.progress(before) >= 0 && CropGrowth.progress(after) > CropGrowth.progress(before)
                && before.setValue(property, after.getValue(property)) == after;
        if (!advanced) observations.remove(pos);
        else if (prior.state() == before) remember(pos, after, Math.max(0, prior.pressure() - 4));
        else if (prior.state() != after) observations.remove(pos);
    }
    void chunkChanged(long chunk) { observations.keySet().removeIf(pos -> ChunkPos.asLong(pos) == chunk); }
}
