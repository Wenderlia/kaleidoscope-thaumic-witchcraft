package dev.senluo.witchcraft.slash;

import dev.senluo.witchcraft.accessory.ProjectileBarrier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;

/** Projectile classification/policy is separate from the blade's geometric contact solver. */
final class SlashInterception {
    private SlashInterception() {}

    static boolean target(Entity entity, ServerPlayer player) {
        if (!entity.isAlive() || !ProjectileBarrier.accepts(entity)) return false;
        var ownerId = ProjectileBarrier.owner(entity);
        if (player.getUUID().equals(ownerId)) return false;
        Entity owner = ownerId == null ? null : player.serverLevel().getEntity(ownerId);
        return owner == null || !owner.isAlliedTo(player)
                && !(owner instanceof Player other && !player.canHarmPlayer(other));
    }

    static boolean remove(Entity entity, ServerPlayer player) {
        if (!target(entity, player)) return false;
        // Do not invoke onHit: cutting a fireball or potion must not trigger its payload.
        entity.discard();
        return entity.isRemoved();
    }
}
