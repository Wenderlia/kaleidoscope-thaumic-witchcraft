package dev.senluo.witchcraft.combat;

import dev.senluo.witchcraft.KaleidoscopeWitchcraft;
import java.util.function.BooleanSupplier;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.damagesource.DamageContainer;
import net.neoforged.neoforge.entity.PartEntity;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;

/** Read-only accounting for one actual hurt call, never a speculative damage simulation. */
@EventBusSubscriber(modid = KaleidoscopeWitchcraft.MOD_ID)
public record DamageReceipt(boolean accepted, boolean observed, float healthBefore,
                            float healthDamage, float absorptionDamage) {
    private static final ThreadLocal<Attempt> CURRENT = new ThreadLocal<>();

    private static final class Attempt {
        final Entity recipient;
        final DamageSource source;
        final float healthBefore;
        int incoming, completed;
        float healthDamage, absorptionDamage;

        Attempt(Entity target, DamageSource source) {
            // The part remains the action's target; only its native living recipient is observed.
            recipient = target instanceof PartEntity<?> part ? part.getParent() : target;
            this.source = source;
            healthBefore = recipient instanceof LivingEntity living ? living.getHealth() : Float.NaN;
        }

        boolean matches(Entity target, DamageSource damageSource) {
            return recipient == target && source == damageSource;
        }
    }

    /** Keep post-attack enchantments outside this scope, so their damage is a separate action. */
    public static DamageReceipt capture(Entity target, DamageSource source, BooleanSupplier action) {
        Attempt previous = CURRENT.get();
        Attempt attempt = new Attempt(target, source);
        CURRENT.set(attempt);
        try {
            boolean accepted = action.getAsBoolean();
            // More than one matching sequence is ambiguous, including unscoped callback reentry.
            boolean observed = attempt.incoming == 1 && attempt.completed == 1;
            return new DamageReceipt(accepted, observed, attempt.healthBefore,
                    attempt.healthDamage, attempt.absorptionDamage);
        } finally {
            if (previous == null) CURRENT.remove(); else CURRENT.set(previous);
        }
    }

    @SubscribeEvent(receiveCanceled = true)
    public static void incoming(LivingIncomingDamageEvent event) {
        Attempt attempt = CURRENT.get();
        if (attempt != null && attempt.matches(event.getEntity(), event.getSource())) attempt.incoming++;
    }

    @SubscribeEvent
    public static void completed(LivingDamageEvent.Post event) {
        Attempt attempt = CURRENT.get();
        if (attempt == null || !attempt.matches(event.getEntity(), event.getSource())) return;
        attempt.completed++;
        attempt.healthDamage = event.getNewDamage();
        attempt.absorptionDamage = event.getReduction(DamageContainer.Reduction.ABSORPTION);
    }

    /**
     * Proportion of this real hit consumed by health and absorption. Overkill is surplus;
     * nonlethal hits consume all. This is not the inverse of nonlinear armor or arbitrary mods.
     * Totems and death cancellation retain their native result; no death is forced or replayed.
     */
    public double spentFraction() {
        if (!accepted) return 0;
        if (!observed || !Float.isFinite(healthBefore) || healthBefore < 0
                || !Float.isFinite(healthDamage) || !Float.isFinite(absorptionDamage)) return 1;
        double health = Math.max(0, healthDamage), absorption = Math.max(0, absorptionDamage);
        double applied = health + absorption;
        return applied <= 0 ? 0 : Math.clamp((Math.min(healthBefore, health) + absorption) / applied, 0, 1);
    }
}
