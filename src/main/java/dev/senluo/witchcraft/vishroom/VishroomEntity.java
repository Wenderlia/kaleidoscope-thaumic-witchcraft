package dev.senluo.witchcraft.vishroom;

import com.mojang.authlib.GameProfile;
import dev.senluo.witchcraft.world.BlockPlacement;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.neoforged.neoforge.common.util.FakePlayerFactory;

/** Stable organism identity and a fixed growth form. No per-part BE, offline debt or forced chunks. */
public final class VishroomEntity extends BlockEntity {
    private static final int VERSION = 1;
    private static final int AURA_REACH = 12;
    private static final Set<UUID> PLACING = new HashSet<>();
    private UUID owner;
    private String ownerName = "";
    private CompoundTag future;
    private int next = -1;
    private long retryAt;
    private final long[] deniedUntil = new long[VishroomShape.CELLS.length];
    private boolean growing;

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
        owner = null; ownerName = ""; future = null; next = -1; retryAt = 0;
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
    private boolean present(ServerLevel server) {
        return !isRemoved() && server.hasChunkAt(worldPosition) && server.shouldTickBlocksAt(worldPosition)
                && server.getBlockEntity(worldPosition) == this && server.getBlockState(worldPosition).is(VishroomContent.CORE.get());
    }
    private BlockPos position(VishroomShape.Cell cell) { return worldPosition.offset(cell.x(), cell.y(), cell.z()); }
    private BlockState state(VishroomShape.Cell cell) {
        return VishroomContent.PART.get().defaultBlockState().setValue(VishroomPartBlock.PART, cell.part());
    }
    /** The chosen neighbor must belong to the authored form, not merely any nearby mushroom block. */
    private boolean attached(ServerLevel server, BlockPos pos) {
        for (Direction d : Direction.values()) {
            BlockPos near = pos.relative(d);
            if (near.equals(worldPosition)) return true;
            if (!server.hasChunkAt(near)) continue;
            var cell = VishroomShape.at(near.getX()-worldPosition.getX(), near.getY()-worldPosition.getY(), near.getZ()-worldPosition.getZ());
            if (cell != null && server.getBlockState(near) == state(cell)) return true;
        }
        return false;
    }
    private boolean eligible(ServerLevel server, int index) {
        var cell = VishroomShape.CELLS[index]; BlockPos pos = position(cell);
        if (server.getGameTime() < deniedUntil[index] || server.isOutsideBuildHeight(pos)
                || !server.hasChunkAt(pos) || !server.shouldTickBlocksAt(pos) || !server.getWorldBorder().isWithinBounds(pos)) return false;
        var before = server.getBlockState(pos);
        return before != state(cell) && (before.isAir() || before.canBeReplaced()) && before.getFluidState().isEmpty()
                && !before.hasBlockEntity() && attached(server, pos)
                && server.isUnobstructed(state(cell), pos, CollisionContext.empty());
    }
    public boolean canGrow() {
        if (!(level instanceof ServerLevel server) || owner == null || future != null || growing || !present(server)) return false;
        if (next >= 0 && eligible(server, next)) return true;
        next = -1;
        if (server.getGameTime() < retryAt) return false;
        for (int i = 0; i < VishroomShape.CELLS.length; i++) if (eligible(server, i)) { next = i; return true; }
        // Fully formed/obstructed structures are not repeatedly rescanned by every paid extra opportunity.
        retryAt = server.getGameTime() + 20;
        return false;
    }
    public int grow(int maximum) {
        if (maximum <= 0 || !canGrow() || !(level instanceof ServerLevel server) || !PLACING.add(owner)) return 0;
        int placed = 0;
        UUID actingOwner = owner;
        try {
            var actor = FakePlayerFactory.get(server, new GameProfile(owner, "[Vishroom]"));
            var previous = actor.position();
            try {
                actor.setPos(worldPosition.getX() + .5, worldPosition.getY(), worldPosition.getZ() + .5);
                // A caller may request only this native opportunity; no deferred catch-up is retained.
                for (int work = 0; work < maximum && canGrow(); work++) {
                    int chosen = next; next = -1;
                    var cell = VishroomShape.CELLS[chosen]; BlockPos pos = position(cell);
                    var before = server.getBlockState(pos); var after = state(cell);
                    growing = true;
                    boolean committed;
                    try { committed = BlockPlacement.commit(actor, pos, before, after); }
                    finally { growing = false; }
                    if (!present(server)) break;
                    if (committed) { placed++; retryAt = 0; }
                    else { deniedUntil[chosen] = server.getGameTime() + 100; retryAt = 0; }
                }
                if (placed > 0) server.levelEvent(1505, worldPosition, 0);
            } finally { actor.setPos(previous.x, previous.y, previous.z); }
        } finally { PLACING.remove(actingOwner); }
        return placed;
    }
    public void form() { grow(VishroomShape.CELLS.length); }
    public static boolean clearForCreation(ServerLevel level, BlockPos origin) {
        for (var cell : VishroomShape.CELLS) {
            BlockPos pos = origin.offset(cell.x(),cell.y(),cell.z());
            if (level.isOutsideBuildHeight(pos) || !level.hasChunkAt(pos) || !level.shouldTickBlocksAt(pos)
                    || !level.getWorldBorder().isWithinBounds(pos)) return false;
            var state = level.getBlockState(pos);
            if ((!state.isAir() && !state.canBeReplaced()) || !state.getFluidState().isEmpty() || state.hasBlockEntity()) return false;
        }
        return true;
    }
    public void affectNearby() {
        if (!(level instanceof ServerLevel server) || owner == null || future != null || !present(server)) return;
        // Twelve blocks beyond each face of the core: a 25 x 25 x 25 block volume.
        AABB area = new AABB(worldPosition).inflate(AURA_REACH);
        for (LivingEntity living : server.getEntitiesOfClass(LivingEntity.class, area,
                entity -> entity.isAlive() && !entity.isSpectator() && !owner.equals(entity.getUUID())))
            living.addEffect(new MobEffectInstance(MobEffects.CONFUSION, 100, 2));
    }
}
