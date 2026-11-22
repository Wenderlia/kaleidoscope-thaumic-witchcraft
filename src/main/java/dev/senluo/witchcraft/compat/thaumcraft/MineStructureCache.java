package dev.senluo.witchcraft.compat.thaumcraft;

import javax.annotation.Nullable;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;

/** Main-thread reconciliation after native unload reports have drained. */
public interface MineStructureCache {
    void witchcraft$refreshStructureCache(ChunkPos pos, @Nullable ChunkAccess current);
}
