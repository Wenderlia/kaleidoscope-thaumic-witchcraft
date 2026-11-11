package dev.senluo.witchcraft.slash;

import dev.senluo.witchcraft.motion.BezierSpeed;
import dev.senluo.witchcraft.motion.MotionPath;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/** Release-time choices belong to the spell; the reusable path knows neither players nor targets. */
public final class SlashFlight {
    /** The cast chooses once; path construction and rotation must consume the same decision. */
    public enum Mode { STANDARD, DIRECT, CURVED_SPIN }
    // 15 -> 40 blocks/second, smooth endpoints, mean 27.5; integrate exactly to the range.
    static final BezierSpeed SPEED = new BezierSpeed(.75, .75, 2, 2, SlashGeometry.RANGE / 1.375);
    private SlashFlight() {}

    static MotionPath plan(ServerPlayer player, SlashFormation.Pose pose, Vec3 origin, double range, Vec3 override, Mode mode) {
        // Aim remains the eye ray; only the actual emission point is shifted to the hand.
        Vec3 target = (override == null ? target(player, pose.aim(), range) : override).subtract(origin);
        if (!MotionPath.finite(target)) throw new IllegalArgumentException("Non-finite slash target");
        double distance = target.length();
        if (distance < 1.0E-6) return MotionPath.line(pose.direction());
        Vec3 axis = target.scale(1 / distance);
        // Keep the small continuous-cast fan after correcting hand/eye parallax.
        Vec3 straight = axis.add(pose.direction().subtract(pose.aim())).normalize();
        if (mode == Mode.DIRECT || distance < 2 || distance > range
                || target.lengthSqr() > MotionPath.MAX_CONTROL_DISTANCE * MotionPath.MAX_CONTROL_DISTANCE
                || mode == Mode.STANDARD && (pose.gestureDirected() || player.getRandom().nextDouble() >= .25))
            return MotionPath.line(straight);
        Vec3 side = biasedSide(player, axis);
        double angle = Math.toRadians(25 + player.getRandom().nextDouble() * 20);
        Vec3 second = target.subtract(axis.scale(distance * .35));
        // Control-polygon length bounds the curve from above. Near maximum range,
        // reduce the departure angle before payment rather than forcing a late arrival.
        double low = angle;
        Vec3 initial = departure(axis, side, angle, distance);
        if (initial.length() + initial.distanceTo(second) + second.distanceTo(target) > range) {
            low = 0;
            double high = angle;
            for (int i = 0; i < 12; i++) {
                double candidate = (low + high) * .5;
                Vec3 first = departure(axis, side, candidate, distance);
                double upperLength = first.length() + first.distanceTo(second) + second.distanceTo(target);
                if (upperLength <= range) low = candidate; else high = candidate;
            }
        }
        if (low < Math.toRadians(.1)) return MotionPath.line(axis);
        return MotionPath.bezier(departure(axis, side, low, distance), second, target);
    }

    private static Vec3 biasedSide(ServerPlayer player, Vec3 axis) {
        Vec3 side = SlashGeometry.blade(axis, player.getRandom().nextFloat() * (float) (2 * Math.PI));
        Vec3 right = player.getLookAngle().cross(new Vec3(0, 1, 0));
        if (right.lengthSqr() < 1.0E-8) {
            double yaw = Math.toRadians(player.getYRot());
            right = new Vec3(-Math.cos(yaw), 0, -Math.sin(yaw));
        }
        if (player.getMainArm() == net.minecraft.world.entity.HumanoidArm.LEFT) right = right.scale(-1);
        // Three quarters favor the casting side; elevation and opposite-side departures remain varied.
        boolean preferHand = player.getRandom().nextFloat() < .75;
        return (side.dot(right) >= 0) == preferHand ? side : side.scale(-1);
    }

    private static Vec3 departure(Vec3 axis, Vec3 side, double angle, double distance) {
        return axis.scale(Math.cos(angle)).add(side.scale(Math.sin(angle))).scale(distance * .45);
    }

    static Vec3 target(ServerPlayer player, Vec3 aim, double range) {
        // Curves target a bounded local ray; the remaining straight continuation can use the full range.
        double sight = Math.min(range, 120);
        Vec3 from = player.getEyePosition(), end = from.add(aim.scale(sight));
        AABB bounds = new AABB(from, end).inflate(1);
        if (!SlashCollision.loaded(player.serverLevel(), bounds)) return from.add(aim.scale(Math.min(12, sight)));
        HitResult block = player.level().clip(SlashBlockPassage.ray(from, end, ClipContext.Block.OUTLINE, player));
        Vec3 closest = block.getType() == HitResult.Type.MISS ? null : block.getLocation();
        double limit = closest == null ? sight * sight : from.distanceToSqr(closest);
        List<Entity> candidates = new ArrayList<>();
        player.serverLevel().getEntities(EntityTypeTest.forClass(Entity.class), bounds,
                e -> e != player && e.isPickable() && !e.isSpectator(), candidates, SlashEntity.MAX_TARGETS + 1);
        if (candidates.size() <= SlashEntity.MAX_TARGETS) {
            for (Entity entity : candidates) {
                AABB box = entity.getBoundingBox().inflate(entity.getPickRadius());
                Vec3 contact = box.contains(from) ? from : box.clip(from, end).orElse(null);
                if (contact != null && from.distanceToSqr(contact) < limit) {
                    closest = contact; limit = from.distanceToSqr(contact);
                }
            }
        }
        return closest == null ? from.add(aim.scale(Math.min(12, sight))) : closest;
    }
}
