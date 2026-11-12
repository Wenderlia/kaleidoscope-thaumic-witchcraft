package dev.senluo.witchcraft.vishroom;

import com.mojang.authlib.GameProfile;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/** Owns stable identity, saved data and the loaded-core lifecycle shared by growth and effects. */
public final class VishroomEntity extends BlockEntity {
    private static final int VERSION = 1;
    private UUID owner;
    private String ownerName = "";
    private CompoundTag future;
    private final VishroomGrowth growth = new VishroomGrowth(this);

    public VishroomEntity(BlockPos pos, BlockState state) { super(VishroomContent.ENTITY.get(), pos, state); }
    public void bind(UUID subject, String name) {
        owner = java.util.Objects.requireNonNull(subject);
        ownerName = level instanceof ServerLevel server ? knownOwnerName(server, subject, name) : name;
        future = null;
        setChanged();
        if (level instanceof ServerLevel server) server.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 2);
    }

    static String knownOwnerName(ServerLevel level, UUID id, String fallback) {
        var server = level.getServer();
        var player = server.getPlayerList().getPlayer(id);
        if (player != null) return player.getGameProfile().getName();
        var cache = server.getProfileCache();
        return cache == null ? fallback : cache.get(id).map(GameProfile::getName).orElse(fallback);
    }

    @Override public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        return saveCustomOnly(registries);
    }
    @Override public net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket getUpdatePacket() {
        return net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket.create(this);
    }
    public CompoundTag organism() {
        if (future != null) return future.copy();
        var tag = new CompoundTag(); tag.putInt("Version", VERSION); tag.putInt("Form", 1);
        if (owner != null) {
            tag.putUUID("Owner", owner);
            if (ownerName.isEmpty() && level instanceof ServerLevel server)
                ownerName = knownOwnerName(server, owner, "");
            if (!ownerName.isEmpty()) tag.putString("OwnerName", ownerName);
        }
        return tag;
    }
    public void restore(CompoundTag tag) {
        owner = null; ownerName = ""; future = null; growth.resetCandidates();
        if (tag.getInt("Version") != VERSION || tag.getInt("Form") != 1) future = tag.copy();
        else if (tag.hasUUID("Owner")) {
            owner = tag.getUUID("Owner");
            ownerName = tag.getString("OwnerName");
        }
        setChanged();
    }
    @Override protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries); tag.put("Organism", organism());
    }
    @Override protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries); restore(tag.getCompound("Organism"));
    }
    @Override public void onLoad() {
        super.onLoad();
        if (level instanceof ServerLevel server) server.scheduleTick(worldPosition, getBlockState().getBlock(), 20);
    }
    /** A runtime permit; callers must ask again after an external placement callback. */
    UUID activeOwner(ServerLevel server) {
        return owner != null && future == null && !isRemoved() && server.hasChunkAt(worldPosition)
                && server.shouldTickBlocksAt(worldPosition) && server.getBlockEntity(worldPosition) == this
                && server.getBlockState(worldPosition).is(VishroomContent.CORE.get()) ? owner : null;
    }
    public boolean canGrow() {
        return level instanceof ServerLevel server && growth.canGrow(server);
    }
    public int grow(int maximum) {
        return level instanceof ServerLevel server ? growth.grow(server, maximum) : 0;
    }
    public void form() { grow(VishroomShape.CELLS.length); }
    public static boolean clearForCreation(ServerLevel level, BlockPos origin) {
        return VishroomGrowth.clearForCreation(level, origin);
    }
    public void affectNearby() {
        if (level instanceof ServerLevel server) {
            UUID subject = activeOwner(server);
            if (subject != null) VishroomAura.apply(server, worldPosition, subject);
        }
    }
}
