package dev.senluo.witchcraft.accessory;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityAttachment;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.warden.Warden;
import net.minecraft.world.phys.Vec3;

/** One spatial quote governs both the native visual ray and this strike's direct damage. */
public final class SonicBarrier {
    public record Trace(ServerLevel level, LivingEntity target, Vec3 start, Vec3 direction,
            double stopDistance, double tailStart, boolean blocksDamage, int sourceId) {
        public boolean permits(Vec3 particle) {
            return particle.subtract(start).dot(direction) < tailStart;
        }
    }
    private static final ThreadLocal<Trace> CURRENT = new ThreadLocal<>();

    public static Trace trace(Warden warden, ServerLevel level, LivingEntity target) {
        Vec3 start = warden.position().add(warden.getAttachments().get(EntityAttachment.WARDEN_CHEST, 0, warden.getYRot()));
        Vec3 delta = target.getEyePosition().subtract(start), direction = delta.normalize();
        double targetDistance = delta.length();
        Vec3 visualRay = direction.scale(targetDistance + 6);
        TurtleField.RaySource source = new TurtleField.RaySource();
        double entry = TurtleField.rayEntry(level, start, visualRay, null, source);
        if (!Double.isFinite(entry)) return null;
        double stop = visualRay.length() * entry;
        return new Trace(level, target, start, direction, stop, Math.max(0, stop - 2.5),
                stop <= targetDistance + MotionConstraints.EPSILON, source.id);
    }

    public static void strike(Warden warden, ServerLevel level, LivingEntity target, Runnable original) {
        Trace previous = CURRENT.get(), trace = trace(warden, level, target);
        if (trace == null) CURRENT.remove(); else CURRENT.set(trace);
        try {
            original.run();
            if (trace != null) {
                tail(trace);
                BarrierVisuals.sonic(warden, trace.sourceId,
                        trace.start.add(trace.direction.scale(trace.stopDistance)), trace.direction);
            }
        } finally {
            if (previous == null) CURRENT.remove(); else CURRENT.set(previous);
        }
    }

    public static boolean permitsParticle(ServerLevel level, Vec3 position) {
        Trace trace = CURRENT.get();
        return trace == null || trace.level != level || trace.permits(position);
    }

    public static boolean blocksDamage(LivingEntity target) {
        Trace trace = CURRENT.get();
        return trace != null && trace.target == target && trace.blocksDamage;
    }

    private static void tail(Trace trace) {
        // Four finite particles replace the last native rings. No server tick task, stored attack or world scan.
        int count = trace.stopDistance > MotionConstraints.EPSILON ? 4 : 1;
        Vec3 limit = trace.start.add(trace.direction.scale(trace.stopDistance));
        for (int i = 0; i < count; i++) {
            double distance = trace.tailStart + (trace.stopDistance - trace.tailStart) * i / count;
            Vec3 from = trace.start.add(trace.direction.scale(distance));
            Vec3 path = limit.subtract(from);
            trace.level.sendParticles(AccessoryContent.CONVERGING_SONIC.get(), from.x, from.y, from.z,
                    0, path.x, path.y, path.z, 1);
        }
    }

    private SonicBarrier() {}
}
