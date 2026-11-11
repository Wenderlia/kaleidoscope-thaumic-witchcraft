package dev.senluo.witchcraft.slash;

import dev.senluo.witchcraft.accessory.ProjectileBarrier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.*;

/** Only the server selects a physical muzzle. Render projections can never choose hits. */
public final class SlashLaunch {
    private SlashLaunch() {}

    public static Vec3 position(SlashEntity slash, ServerPlayer player, Vec3 requested) {
        Vec3 eye = player.getEyePosition();
        Vec3 delta = requested.subtract(eye);
        double length = delta.length();
        if (!Double.isFinite(length) || length > 3 || length < 1.0E-6) return eye;
        if (!SlashCollision.loaded(player.serverLevel(), new AABB(eye, requested).inflate(.1))) return eye;
        var hit = player.level().clip(SlashBlockPassage.ray(eye, requested, ClipContext.Block.COLLIDER, player));
        double allowed = hit.getType() == HitResult.Type.MISS ? 1 : Math.max(0, eye.distanceTo(hit.getLocation()) / length - .01);
        // A hand offset is not a free teleport across someone else's barrier.
        double barrier = ProjectileBarrier.space(slash).segmentEntry(eye, requested, new Vec3(1, 0, 0), 0, 0, .075);
        if (barrier < 1) allowed = Math.min(allowed, Math.max(0, barrier - .01));
        return eye.add(delta.scale(allowed));
    }
}
