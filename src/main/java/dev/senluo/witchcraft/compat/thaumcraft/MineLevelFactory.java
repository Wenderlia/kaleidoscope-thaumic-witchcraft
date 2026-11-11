package dev.senluo.witchcraft.compat.thaumcraft;

import javax.annotation.Nullable;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.progress.ChunkProgressListener;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.status.ChunkStatus;

/** Server construction context, isolated from sampling policy and the drill's account. */
public interface MineLevelFactory {
    @Nullable ServerLevel witchcraft$mineLevel(ServerLevel source, ResourceKey<Level> key);

    /** Background mines have no spawn-loading screen or forced spawn preparation. */
    enum QuietProgress implements ChunkProgressListener {
        INSTANCE;
        public void updateSpawnPos(ChunkPos pos) {}
        public void onStatusChange(ChunkPos pos, @Nullable ChunkStatus status) {}
        public void start() {}
        public void stop() {}
    }
}
