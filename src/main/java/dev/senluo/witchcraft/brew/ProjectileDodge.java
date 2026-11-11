package dev.senluo.witchcraft.brew;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.phys.EntityHitResult;
import net.neoforged.neoforge.event.entity.ProjectileImpactEvent;

public final class ProjectileDodge {
    public static final int MIN_GRADE = 2;
    public static final int COST = 3 * 20;
    private static final Map<Projectile, Map<UUID, Long>> PASSES = new WeakHashMap<>();
    private ProjectileDodge() {}
    public static void impact(ProjectileImpactEvent event) {
        if (event.isCanceled() || !(event.getRayTraceResult() instanceof EntityHitResult hit)
                || !(hit.getEntity() instanceof ServerPlayer player)) return;
        Projectile projectile = event.getProjectile();
        if (projectile.level().isClientSide) return;
        long now = projectile.level().getGameTime();
        Map<UUID, Long> passes = PASSES.get(projectile);
        Long last = passes == null ? null : passes.get(player.getUUID());
        // A slow projectile can intersect the same body over several consecutive ticks.
        // Pay once for that passage, even if this payment exhausts the player's balance.
        boolean continuing = last != null && (now == last || now - last == 1
                && player.getBoundingBox().inflate(projectile.getBbWidth() / 2.0 + .3).contains(projectile.position()));
        if (!continuing) {
            if (!AbilityCosts.pay(player,MIN_GRADE,COST,AbilityCosts.DODGE)) return;
        }
        if (passes == null) { passes = new HashMap<>(); PASSES.put(projectile, passes); }
        passes.entrySet().removeIf(entry -> now - entry.getValue() > 1);
        passes.put(player.getUUID(), now);
        event.setCanceled(true);
    }
}
