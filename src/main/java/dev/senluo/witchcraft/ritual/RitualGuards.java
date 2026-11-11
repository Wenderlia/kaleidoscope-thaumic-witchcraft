package dev.senluo.witchcraft.ritual;

import dev.senluo.witchcraft.KaleidoscopeWitchcraft;
import dev.senluo.witchcraft.brew.AutomaticAttack;
import net.minecraft.world.InteractionResult;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityInvulnerabilityCheckEvent;
import net.neoforged.neoforge.event.entity.living.LivingHealEvent;
import net.neoforged.neoforge.event.entity.living.MobEffectEvent;
import net.neoforged.neoforge.event.entity.player.AttackEntityEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

@EventBusSubscriber(modid = KaleidoscopeWitchcraft.MOD_ID)
public final class RitualGuards {
    private RitualGuards() {}

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void invulnerability(EntityInvulnerabilityCheckEvent event) {
        if (OwnershipRitual.protectedFromDamage(event.getEntity())) event.setInvulnerable(true);
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void healing(LivingHealEvent event) {
        if (!event.getEntity().level().isClientSide && OwnershipRitual.frozen(event.getEntity()))
            event.setCanceled(true);
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void removeEffect(MobEffectEvent.Remove event) {
        if (!event.getEntity().level().isClientSide && OwnershipRitual.frozen(event.getEntity())
                && !OwnershipRitual.syncingDisplay()) event.setCanceled(true);
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void applyEffect(MobEffectEvent.Applicable event) {
        // Both addEffect and forceAddEffect pass this public gate before mutating effects.
        if (!event.getEntity().level().isClientSide && OwnershipRitual.frozen(event.getEntity())
                && !OwnershipRitual.syncingDisplay())
            event.setResult(MobEffectEvent.Applicable.Result.DO_NOT_APPLY);
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void attack(AttackEntityEvent event) {
        if (!OwnershipRitual.frozen(event.getEntity()) && SustainedRitual.active(event.getTarget())
                && event.getEntity().getAttackStrengthScale(0.5F) >= .9F
                && event.getTarget().isAttackable() && event.getEntity().canInteractWithEntity(event.getTarget(), 0)
                && event.getEntity().hasLineOfSight(event.getTarget())) {
            SustainedRitual.contact(event.getTarget(), event.getEntity(), SustainedRitual.Contact.ATTACK);
            if (OwnershipRitual.protectedFromDamage(event.getTarget()) && !AutomaticAttack.active(event.getEntity()))
                event.getEntity().resetAttackStrengthTicker();
        }
        if (OwnershipRitual.frozen(event.getEntity()) || OwnershipRitual.protectedFromDamage(event.getTarget())) event.setCanceled(true);
    }
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void entity(PlayerInteractEvent.EntityInteract event) {
        trade(event.getEntity(), event.getTarget());
        if (OwnershipRitual.frozen(event.getEntity()) || OwnershipRitual.frozen(event.getTarget())) {
            event.setCanceled(true); event.setCancellationResult(InteractionResult.FAIL);
        }
    }
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void specific(PlayerInteractEvent.EntityInteractSpecific event) {
        trade(event.getEntity(), event.getTarget());
        if (OwnershipRitual.frozen(event.getEntity()) || OwnershipRitual.frozen(event.getTarget())) {
            event.setCanceled(true); event.setCancellationResult(InteractionResult.FAIL);
        }
    }
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void block(PlayerInteractEvent.RightClickBlock event) {
        if (OwnershipRitual.frozen(event.getEntity())) { event.setCanceled(true); event.setCancellationResult(InteractionResult.FAIL); }
    }
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void item(PlayerInteractEvent.RightClickItem event) {
        if (OwnershipRitual.frozen(event.getEntity())) { event.setCanceled(true); event.setCancellationResult(InteractionResult.FAIL); }
    }
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void mining(PlayerInteractEvent.LeftClickBlock event) {
        if (OwnershipRitual.frozen(event.getEntity())) event.setCanceled(true);
    }
    private static void trade(net.minecraft.world.entity.player.Player player, net.minecraft.world.entity.Entity target) {
        if (!player.canInteractWithEntity(target, 0) || !player.hasLineOfSight(target)) return;
        if (target instanceof net.minecraft.world.item.trading.Merchant)
            SustainedRitual.contact(target, player, SustainedRitual.Contact.TRADE);
        if (target instanceof net.minecraft.world.entity.vehicle.Boat || target instanceof net.minecraft.world.entity.vehicle.AbstractMinecart)
            SustainedRitual.contact(target, player, SustainedRitual.Contact.VEHICLE);
    }
}
