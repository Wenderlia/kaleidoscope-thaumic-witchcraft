package dev.senluo.witchcraft.vishroom;

import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;

/** Effect policy only; the core owns scheduling, identity and lifetime. */
final class VishroomAura {
    private static final int REACH = 12;
    private VishroomAura() {}

    static void apply(ServerLevel level, BlockPos origin, UUID owner) {
        // Twelve blocks beyond each face of the core: a 25 x 25 x 25 block volume.
        AABB area = new AABB(origin).inflate(REACH);
        for (LivingEntity living : level.getEntitiesOfClass(LivingEntity.class, area,
                entity -> entity.isAlive() && !entity.isSpectator() && !owner.equals(entity.getUUID())))
            living.addEffect(new MobEffectInstance(MobEffects.CONFUSION, 100, 2));
    }
}
