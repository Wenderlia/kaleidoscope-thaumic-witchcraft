package dev.senluo.witchcraft.vishroom;

import com.mojang.authlib.GameProfile;
import dev.senluo.witchcraft.world.BlockPlacement;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.neoforged.neoforge.common.util.FakePlayerFactory;

/** Fixed-form eligibility, candidate lifetime and protected placement for one loaded core. */
final class VishroomGrowth {
    private static final Set<UUID> PLACING = new HashSet<>();
    private final VishroomEntity core;
    private final BlockPos worldPosition;
    private int next = -1;
    private long retryAt;
    private final long[] deniedUntil = new long[VishroomShape.CELLS.length];
    private boolean growing;

    VishroomGrowth(VishroomEntity core) {
        this.core = core;
        worldPosition = core.getBlockPos();
    }
    void resetCandidates() { next = -1; retryAt = 0; }
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
        var before = server.getBlockState(pos); var after = state(cell);
        return before != after && (before.isAir() || before.canBeReplaced()) && before.getFluidState().isEmpty()
                && !before.hasBlockEntity() && attached(server, pos) && after.canSurvive(server, pos)
                && server.isUnobstructed(after, pos, CollisionContext.empty());
    }
    boolean canGrow(ServerLevel server) {
        if (core.activeOwner(server) == null || growing) return false;
        if (next >= 0 && eligible(server, next)) return true;
        next = -1;
        if (server.getGameTime() < retryAt) return false;
        for (int i = 0; i < VishroomShape.CELLS.length; i++) if (eligible(server, i)) { next = i; return true; }
        // Fully formed/obstructed structures are not repeatedly rescanned by every paid extra opportunity.
        retryAt = server.getGameTime() + 20;
        return false;
    }
    int grow(ServerLevel server, int maximum) {
        UUID owner = core.activeOwner(server);
        if (maximum <= 0 || owner == null || !canGrow(server) || !PLACING.add(owner)) return 0;
        int placed = 0;
        UUID actingOwner = owner;
        try {
            var actor = FakePlayerFactory.get(server, new GameProfile(owner, "[Vishroom]"));
            var previous = actor.position();
            try {
                actor.setPos(worldPosition.getX() + .5, worldPosition.getY(), worldPosition.getZ() + .5);
                // A caller may request only this native opportunity; no deferred catch-up is retained.
                for (int work = 0; work < maximum && canGrow(server); work++) {
                    int chosen = next; next = -1;
                    var cell = VishroomShape.CELLS[chosen]; BlockPos pos = position(cell);
                    var before = server.getBlockState(pos); var after = state(cell);
                    growing = true;
                    boolean committed;
                    try { committed = BlockPlacement.commit(actor, pos, before, after); }
                    finally { growing = false; }
                    if (core.activeOwner(server) == null) break;
                    if (committed) { placed++; retryAt = 0; }
                    else { deniedUntil[chosen] = server.getGameTime() + 100; retryAt = 0; }
                }
                if (placed > 0) server.levelEvent(1505, worldPosition, 0);
            } finally { actor.setPos(previous.x, previous.y, previous.z); }
        } finally { PLACING.remove(actingOwner); }
        return placed;
    }
    static boolean clearForCreation(ServerLevel level, BlockPos origin) {
        for (var cell : VishroomShape.CELLS) {
            BlockPos pos = origin.offset(cell.x(),cell.y(),cell.z());
            if (level.isOutsideBuildHeight(pos) || !level.hasChunkAt(pos) || !level.shouldTickBlocksAt(pos)
                    || !level.getWorldBorder().isWithinBounds(pos)) return false;
            var state = level.getBlockState(pos);
            if ((!state.isAir() && !state.canBeReplaced()) || !state.getFluidState().isEmpty() || state.hasBlockEntity()) return false;
        }
        return true;
    }
}
