package dev.senluo.witchcraft.compat.thaumcraft;

import com.mojang.authlib.GameProfile;
import dev.tc4port.thaumcraft.api.event.GameplayHooks;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.vehicle.AbstractMinecartContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;

/** Entity-only bridge: newly dropped cart items enter the native bore's pending vacuum/output. */
final class BoreCarts {
    static final GameProfile ACTOR = new GameProfile(UUID.fromString("58c375d5-2c84-46d9-961a-75eacda90e8b"), "FakeThaumcraftBore");
    private record Capture(ServerLevel level, List<ItemEntity> items) {}
    private static final ThreadLocal<Capture> CURRENT = new ThreadLocal<>();

    static void register() {
        NeoForge.EVENT_BUS.addListener((EntityJoinLevelEvent event) -> {
            Capture capture = CURRENT.get();
            if (capture != null && event.getLevel() == capture.level() && !event.loadedFromDisk()
                    && event.getEntity() instanceof ItemEntity item) capture.items().add(item);
        });
    }

    static void collect(ServerLevel level, MixedMine.Target target) {
        int x = target.virtual().getX(), z = target.virtual().getZ();
        var carts = level.getEntitiesOfClass(AbstractMinecartContainer.class,
                new AABB(x, target.virtual().getY(), z, x + 1, target.top() + 1, z + 1),
                cart -> cart.getBlockX() == x && cart.getBlockZ() == z);
        if (carts.isEmpty()) return;
        var actor = FakePlayerFactory.get(level, ACTOR);
        Vec3 position = actor.position();
        ItemStack hand = actor.getMainHandItem();
        Capture previous = CURRENT.get(), capture = new Capture(level, new ArrayList<>());
        try {
            actor.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            CURRENT.set(capture);
            for (var cart : carts) {
                actor.setPos(cart.position());
                if (!cart.isRemoved() && level.mayInteract(actor, cart.blockPosition())
                        && !GameplayHooks.attackEntity(actor, cart).isCanceled())
                    cart.hurt(level.damageSources().playerAttack(actor), 100);
            }
        } finally {
            if (previous == null) CURRENT.remove(); else CURRENT.set(previous);
            actor.setItemInHand(InteractionHand.MAIN_HAND, hand);
            actor.setPos(position);
            // Move actual new drops into the current native vacuum, without duplicating or discarding them.
            // A later vacuum veto leaves these real items at the mined block.
            Vec3 outlet = Vec3.atCenterOf(target.virtual());
            for (ItemEntity item : capture.items()) if (!item.isRemoved() && level.getEntity(item.getId()) == item)
                item.setPos(outlet);
        }
    }

    private BoreCarts() {}
}
