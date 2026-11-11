package dev.senluo.witchcraft.time;

import com.mojang.authlib.GameProfile;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.common.util.FakePlayerFactory;

/** A scoped owner identity for native item hooks and protection events, including offline owners. */
final class TreeFarmAction implements AutoCloseable {
    private static final Set<UUID> ACTIVE = new HashSet<>();
    final FakePlayer actor;
    private final UUID owner;
    private final ItemStack previous;

    static TreeFarmAction open(ServerLevel level, UUID owner, BlockPos pos, ItemStack item) {
        if (owner == null || level.captureBlockSnapshots || level.restoringBlockSnapshots || !ACTIVE.add(owner)) return null;
        try { return new TreeFarmAction(level, owner, pos, item); }
        catch (RuntimeException | Error error) { ACTIVE.remove(owner); throw error; }
    }
    private TreeFarmAction(ServerLevel level, UUID owner, BlockPos pos, ItemStack item) {
        this.owner = owner;
        actor = FakePlayerFactory.get(level, new GameProfile(owner, "[YearsTree]"));
        previous = actor.getMainHandItem();
        actor.getAbilities().instabuild = false;
        actor.setPos(pos.getX() + .5, pos.getY(), pos.getZ() + .5);
        actor.setItemInHand(InteractionHand.MAIN_HAND, item);
    }
    ItemStack remainder() { return actor.getMainHandItem(); }
    public void close() {
        actor.setItemInHand(InteractionHand.MAIN_HAND, previous);
        ACTIVE.remove(owner);
    }
}
