package dev.senluo.witchcraft.ritual;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

/** Frozen look direction, including interpolation endpoints and independent head/body yaw. */
public record RitualPose(float yaw, float pitch, float head, float body) {
    public RitualPose {
        if (!Float.isFinite(yaw) || !Float.isFinite(pitch) || !Float.isFinite(head) || !Float.isFinite(body))
            throw new IllegalArgumentException("Invalid ritual pose");
    }
    public static RitualPose capture(Entity entity) {
        return new RitualPose(entity.getYRot(), entity.getXRot(),
                entity instanceof LivingEntity living ? living.yHeadRot : entity.getYRot(),
                entity instanceof LivingEntity living ? living.yBodyRot : entity.getYRot());
    }
    public void apply(Entity entity) {
        entity.setYRot(yaw); entity.yRotO = yaw;
        entity.setXRot(pitch); entity.xRotO = pitch;
        if (entity instanceof LivingEntity living) {
            living.yHeadRot = head; living.yHeadRotO = head;
            living.yBodyRot = body; living.yBodyRotO = body;
        }
    }
}
