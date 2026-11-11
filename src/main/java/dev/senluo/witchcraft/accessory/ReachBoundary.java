package dev.senluo.witchcraft.accessory;

import dev.senluo.witchcraft.mixin.accessory.MobReachAccess;
import java.util.List;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Read live attack geometry and the final reach attribute, never a weapon-name/range lookup table. */
public final class ReachBoundary {
    private static final double MINIMUM = 2;
    private static final double MARGIN = .25;

    public static void append(List<MotionConstraints.Boundary> boundaries, LivingEntity attacker, Player wearer) {
        Vec3 center = attacker.getBoundingBox().getCenter();
        Vec3 body = wearer.getBoundingBox().getCenter();
        boundaries.add(new MotionConstraints.Boundary(body, MINIMUM, wearer.getId()));
        AABB hitbox = wearer.getHitbox();
        AABB reach = attacker instanceof Mob mob ? ((MobReachAccess) mob).witchcraft$attackBounds() : attacker.getBoundingBox();
        if (finite(reach) && finite(hitbox)) {
            // Forbidden BODY-CENTER positions: native attack box intersects the target hitbox.
            // Keep the Minkowski box itself; its height must not enlarge horizontal reach.
            AABB forbidden = new AABB(
                    hitbox.minX - (reach.maxX - center.x), hitbox.minY - (reach.maxY - center.y),
                    hitbox.minZ - (reach.maxZ - center.z), hitbox.maxX - (reach.minX - center.x),
                    hitbox.maxY - (reach.minY - center.y), hitbox.maxZ - (reach.minZ - center.z));
            boundaries.add(new MotionConstraints.Boundary(forbidden, MARGIN,
                    Math.max(MINIMUM, Math.max(forbidden.getXsize(), forbidden.getZsize()) * .5 + MARGIN), wearer.getId()));
        }
        if (attacker instanceof Creeper) {
            // SwellGoal starts at feet-to-feet distance < 3, independently of melee geometry.
            // Translate that sphere into body-center coordinates instead of enlarging its radius.
            boundaries.add(new MotionConstraints.Boundary(
                    wearer.position().add(center.subtract(attacker.position())), 3 + MARGIN, wearer.getId()));
        }
        var interaction = attacker.getAttribute(Attributes.ENTITY_INTERACTION_RANGE);
        if (interaction != null) {
            double value = interaction.getValue();
            if (Double.isFinite(value) && value >= 0 && finite(hitbox)) {
                // eye = body center + offset; distance(eye, hitbox) is the native range metric.
                // Shift the hitbox by -offset, then use a rounded box, not a diagonal-sized sphere.
                AABB eyeTarget = hitbox.move(center.subtract(attacker.getEyePosition()));
                boundaries.add(new MotionConstraints.Boundary(eyeTarget, value + MARGIN,
                        Math.max(MINIMUM, value + MARGIN + Math.max(hitbox.getXsize(), hitbox.getZsize()) * .5), wearer.getId()));
            }
        }
    }

    private static boolean finite(AABB box) {
        return Double.isFinite(box.minX) && Double.isFinite(box.minY) && Double.isFinite(box.minZ)
                && Double.isFinite(box.maxX) && Double.isFinite(box.maxY) && Double.isFinite(box.maxZ);
    }

    private ReachBoundary() {}
}
