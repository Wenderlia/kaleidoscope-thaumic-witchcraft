package dev.senluo.witchcraft.compat.thaumcraft;

import dev.senluo.witchcraft.accessory.CastBarrier;
import dev.senluo.witchcraft.mixin.thaumcraft.ClipContextAccess;
import dev.tc4port.thaumcraft.api.wand.pipeline.WandWorldStages;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.shapes.EntityCollisionContext;

/** Both the burst and each native ground-field candidate ask the world for a clear ray from the orb. */
public final class ShockFieldSight {
    private static final ThreadLocal<Projectile> ORB = new ThreadLocal<>();

    static <T> T during(WandWorldStages.Impact impact, Supplier<T> original) {
        var previous = ORB.get();
        var orb = !impact.isClientSide() && impact.focus().filter(ShockChain.SHOCK::equals).isPresent()
                && impact.entity() instanceof Projectile projectile && CastBarrier.active(projectile) && FocusRestraint.focusProjectile(projectile)
                ? projectile : null;
        if (orb == null && previous == null) return original.get();
        ORB.set(orb);
        try { return original.get(); }
        finally {
            if (previous == null) ORB.remove(); else ORB.set(previous);
        }
    }

    public static BlockHitResult clip(BlockGetter level, ClipContext ray, BlockHitResult original) {
        var orb = ORB.get();
        if (orb == null || original.getType() != HitResult.Type.MISS || orb.level() != level
                || !orb.position().equals(ray.getFrom())
                || !(((ClipContextAccess) ray).witchcraft$collisionContext() instanceof EntityCollisionContext collision)
                || collision.getEntity() != orb) return original;
        var hit = CastBarrier.contact(orb, ray.getTo());
        if (hit == null) return original;
        return new BlockHitResult(hit.point(), Direction.getNearest(-hit.direction().x, -hit.direction().y, -hit.direction().z),
                BlockPos.containing(hit.point()), false);
    }

    private ShockFieldSight() {}
}
