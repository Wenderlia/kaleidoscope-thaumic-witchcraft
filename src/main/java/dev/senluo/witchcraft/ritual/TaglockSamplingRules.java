package dev.senluo.witchcraft.ritual;

import dev.senluo.witchcraft.KaleidoscopeWitchcraft;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/** Read-only sampling eligibility and Witchery probability; never rolls, binds, or sends feedback. */
public final class TaglockSamplingRules {
    public static final TagKey<EntityType<?>> IMMUNE = entityTag("taglock_immune");
    public static final TagKey<EntityType<?>> REQUIRES_SNEAKING = entityTag("taglock_requires_sneaking");

    private TaglockSamplingRules() {}

    public static boolean canAttempt(Player player, InteractionHand hand, Entity target) {
        return !player.isSpectator() && emptyVial(player.getItemInHand(hand))
                && target instanceof LivingEntity && !(target instanceof ArmorStand)
                && target.isAlive() && !target.isSpectator()
                && !target.getType().is(IMMUNE)
                && (!target.getType().is(REQUIRES_SNEAKING) || player.isShiftKeyDown())
                && (player.level().isClientSide || aimedAt(player, target));
    }

    public static boolean emptyVial(ItemStack stack) {
        return stack.is(RitualContent.TAGLOCK.get()) && !RitualVial.reserved(stack)
                && !stack.has(RitualContent.TAGLOCK_SAMPLE.get());
    }

    /** Validate the actual forward ray on the server; a nearby entity is not enough. */
    private static boolean aimedAt(Player player, Entity target) {
        double reach = player.entityInteractionRange();
        if (!Double.isFinite(reach) || reach <= 0 || player.level() != target.level()) return false;
        Vec3 eye = player.getEyePosition(), direction = player.getViewVector(1.0F);
        if (target.getBoundingBox().getCenter().subtract(eye).dot(direction) <= 0) return false;
        // Small tolerance for the server's last movement packet; never extend the reach.
        var hit = target.getBoundingBox().inflate(target.getPickRadius() + .1)
                .clip(eye, eye.add(direction.scale(reach)));
        if (hit.isEmpty()) return false;
        HitResult block = player.pick(reach, 1.0F, false);
        return block.getType() == HitResult.Type.MISS
                || eye.distanceToSqr(hit.get()) < eye.distanceToSqr(block.getLocation());
    }

    public static double playerChance(Player collector, LivingEntity target) {
        float difference = Math.abs(Mth.wrapDegrees(collector.getYHeadRot() - target.getYHeadRot()));
        // Witchery 0.24.1 ItemTaglockKit.isSneakSuccessful compares head directions,
        // not positions. Invisibility only helps outside the aligned-heading branch.
        if (difference < 45.0F) return collector.isShiftKeyDown() ? 0.60 : 0.30;
        return (collector.isShiftKeyDown() ? 0.10 : 0.01) + (collector.isInvisible() ? 0.10 : 0);
    }

    private static TagKey<EntityType<?>> entityTag(String path) {
        return TagKey.create(Registries.ENTITY_TYPE, ResourceLocation.fromNamespaceAndPath(KaleidoscopeWitchcraft.MOD_ID, path));
    }
}
