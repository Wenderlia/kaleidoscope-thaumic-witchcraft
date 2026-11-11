package dev.senluo.witchcraft.slash;

import dev.senluo.witchcraft.KaleidoscopeWitchcraft;
import dev.senluo.witchcraft.combat.DamageContext;
import net.minecraft.world.damagesource.DamageSource;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.living.LivingKnockBackEvent;

/** Native damage-policy events, scoped to the slash carrier, independent of its damage-type tags. */
@EventBusSubscriber(modid = KaleidoscopeWitchcraft.MOD_ID)
public final class SlashCombat {
    private SlashCombat() {}

    private static boolean slash(DamageSource source) {
        return source != null && source.getDirectEntity() instanceof SlashEntity;
    }

    /** Adjust the armor input once, retaining the host's toughness/enchantment calculation. */
    public static float effectiveArmor(DamageSource source, float armor) {
        return source != null && source.getDirectEntity() instanceof SlashEntity blade
                ? armor * (blade.penetrating() ? .125F : .25F) : armor;
    }

    @SubscribeEvent
    public static void incoming(LivingIncomingDamageEvent event) {
        if (!event.getEntity().level().isClientSide && slash(event.getSource())) {
            // Do not create a new window, or erase one granted by another attack.
            // Vanilla still decides whether an existing window rejects this hit.
            event.setInvulnerabilityTicks(Math.max(0, event.getEntity().invulnerableTime));
        }
    }

    @SubscribeEvent(priority = EventPriority.LOW)
    public static void knockback(LivingKnockBackEvent event) {
        if (!event.getEntity().level().isClientSide
                && event.getEntity() instanceof DamageContext context
                && slash(context.witchcraft$currentDamageSource())) {
            // The existing hurt scope restores nested sources in finally; reflected or unrelated hits
            // cannot accidentally inherit this reduction, and other movement is never scaled.
            event.setStrength(event.getStrength() * .5F);
        }
    }
}
