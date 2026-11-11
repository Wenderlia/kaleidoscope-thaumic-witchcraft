package dev.senluo.witchcraft.time;

import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

public final class RootEntity extends BlockEntity {
    public BlockPos parent;
    public UUID owner;
    public int ticks;
    public int distance;

    public RootEntity(BlockPos pos, BlockState state) { super(TemporalContent.ROOT_ENTITY.get(), pos, state); }
    @Override public void onLoad() {
        super.onLoad();
        if (level instanceof ServerLevel server) {
            TemporalWorld.get(server).add(this);
            RootBlock.refresh(server, worldPosition);
        }
    }
    @Override public void setRemoved() {
        if (level instanceof ServerLevel server) TemporalWorld.get(server).remove(worldPosition);
        super.setRemoved();
    }
    @Override protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        if (parent != null) tag.putLong("Parent", parent.asLong());
        if (owner != null) tag.putUUID("Owner", owner);
        tag.putInt("Ticks", ticks);
        tag.putInt("Distance", distance);
    }
    @Override protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        parent = tag.contains("Parent") ? BlockPos.of(tag.getLong("Parent")) : null;
        if (parent != null && worldPosition.distSqr(parent) > 4096) parent = null;
        owner = tag.hasUUID("Owner") ? tag.getUUID("Owner") : null;
        ticks = Tissue.bounded(tag.getInt("Ticks"), 3600);
        distance = Math.max(1, Tissue.bounded(tag.getInt("Distance"), 32));
    }
}
