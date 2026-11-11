package dev.senluo.witchcraft.slash;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.damagesource.DamageSource;

/** One standard weapon-hit path for every blade geometry; no per-enchantment names or copied formulas. */
final class SlashWeaponEffects {
    private SlashWeaponEffects() {}
    static boolean hit(SlashEntity slash, ServerPlayer player, Entity target) {
        var level = player.serverLevel();
        var weapon = slash.getWeaponItem();
        var source = slash.damageSource(player);
        float damage = EnchantmentHelper.modifyDamage(level, weapon, target, source, (float) slash.potential().damage());
        if (!Float.isFinite(damage) || damage <= 0 || !target.hurt(source, damage)) return false;
        afterHit(slash, player, target, source);
        return true;
    }
    static boolean fissionHit(SlashEntity slash, ServerPlayer player, Entity target, double potential, long blades) {
        var source = slash.damageSource(player);
        float damage = EnchantmentHelper.modifyDamage(player.serverLevel(), slash.getWeaponItem(), target, source,
                (float)(potential * 5));
        if (!Float.isFinite(damage) || damage <= 0) return false;
        // Flat native spell bonuses belong to EACH logical blade, before aggregation. Freeze
        // their public result and use an identity-priced source, avoiding a second application.
        var batch = dev.senluo.witchcraft.compat.thaumcraft.SlashFocus.fissionDamage(slash, player, source, damage, blades);
        return SlashFission.during(batch.source(), blades, () -> {
            if (batch.amount() <= 0 || !target.hurt(batch.source(), batch.amount())) return false;
            afterHit(slash, player, target, batch.source());
            return true;
        });
    }
    private static void afterHit(SlashEntity slash, ServerPlayer player, Entity target, DamageSource source) {
        var level = player.serverLevel();
        var weapon = slash.getWeaponItem();
        // Armor effectiveness is consumed by vanilla CombatRules through DamageSource#getWeaponItem.
        float knockback = EnchantmentHelper.modifyKnockback(level, weapon, target, source, 0);
        if (Float.isFinite(knockback) && knockback > 0 && target instanceof LivingEntity living) {
            var direction = target.position().subtract(slash.position());
            if (direction.horizontalDistanceSqr() < 1.0E-8) direction = slash.forward();
            // Native sword coefficient (.5), followed by the slash's existing half-knockback policy.
            living.knockback(knockback * .25, -direction.x, -direction.z);
        }
        EnchantmentHelper.doPostAttackEffectsWithItemSource(level, target, source, weapon);
    }
}
