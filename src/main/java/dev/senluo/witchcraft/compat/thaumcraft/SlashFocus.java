package dev.senluo.witchcraft.compat.thaumcraft;

import dev.senluo.witchcraft.slash.*;
import dev.senluo.witchcraft.slash.drawing.*;
import dev.tc4port.thaumcraft.api.aspect.VisAction;
import dev.tc4port.thaumcraft.api.focus.*;
import dev.tc4port.thaumcraft.api.focus.behavior.*;
import dev.tc4port.thaumcraft.api.focus.action.*;
import dev.tc4port.thaumcraft.api.wand.pipeline.*;
import dev.tc4port.thaumcraft.api.wand.WandApi;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.network.chat.Component;
import java.util.List;

/** Optional TC adapter. Native state/payment/rendering are retained; the internal origin fallback is isolated here. */
public final class SlashFocus {
    public static final FocusId ID = FocusId.of(SlashContent.id("slash_focus"));
    private static final java.util.Set<ServerPlayer> CASTING = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
    private SlashFocus() {}

    public static Item createItem() { SlashEfficacy.requireParameters(); return new Carrier(); }

    private static final class Carrier extends SlashFocusItem implements FocusIdentityItem {
        @Override public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
            FocusApi.appendFocusTooltip(stack, tooltip);
        }
        @Override public FocusId focusId(ItemStack stack) { return ID; }
        @Override public FocusStateView focusState(ItemStack stack) {
            FocusMemory memory = stack.getOrDefault(SlashContent.MEMORY.get(), FocusMemory.EMPTY);
            return new FocusStateView(ID, memory.upgrades().stream().map(FocusUpgradeId::of).toList(), memory.properties());
        }
        @Override public ItemStack withFocusState(ItemStack stack, FocusStateView state) {
            if (!state.focusId().equals(ID)) throw new IllegalArgumentException("Cannot replace focus identity");
            ItemStack copy = stack.copyWithCount(1);
            copy.set(SlashContent.MEMORY.get(), new FocusMemory(
                    state.upgradeSlots().stream().map(FocusUpgradeId::value).toList(), state.properties()));
            return copy;
        }
    }

    private static final net.minecraft.resources.ResourceLocation CAST_MODULE = SlashContent.id("slash_cast");
    private record CastSession(FocusServerContext<com.mojang.datafixers.util.Unit, com.mojang.datafixers.util.Unit> context)
            implements FocusServerSession<com.mojang.datafixers.util.Unit, com.mojang.datafixers.util.Unit> {
        boolean execute(StrokeSphere sphere, StrokeTrace trace, net.minecraft.world.phys.Vec3 target) {
            return cast(context.player(), context.hand(), sphere, trace, target, context.castOrigin());
        }
    }

    static void registerCast() {
        FocusBehaviorApi.register(CAST_MODULE, new FocusBehavior<com.mojang.datafixers.util.Unit, com.mojang.datafixers.util.Unit>() {
            public FocusSyncedValue<com.mojang.datafixers.util.Unit> serverState() { return FocusSyncedValue.none(); }
            public FocusSyncedValue<com.mojang.datafixers.util.Unit> clientState() { return FocusSyncedValue.none(); }
            public CastSession open(FocusServerContext<com.mojang.datafixers.util.Unit, com.mojang.datafixers.util.Unit> context) {
                return new CastSession(context);
            }
        });
    }

    public static boolean installed(ItemStack wand) {
        // installedState also accepts a loose FocusIdentityItem. Input ownership
        // requires a real wand carrier, otherwise ordinary item actions get cancelled.
        return wand.getItem() instanceof dev.tc4port.thaumcraft.api.wand.WandCarrierItem
                && FocusApi.installedState(wand).map(s -> s.focusId().equals(ID)).orElse(false);
    }

    /** Full component-preserving carrier. TC's public state view exposes upgrades, not this stack. */
    public static ItemStack installedCarrier(ItemStack wand) {
        if (!(wand.getItem() instanceof dev.tc4port.thaumcraft.api.wand.WandCarrierItem)) return ItemStack.EMPTY;
        if (!installed(wand)) return ItemStack.EMPTY;
        ItemStack focus = FocusApi.installedStack(wand);
        return focus.is(SlashContent.FOCUS.get()) ? focus : ItemStack.EMPTY;
    }

    /** Optional dependency state comparison remains inside the TC bridge. */
    public static Object state(ItemStack wand) { return FocusApi.installedState(wand).orElse(null); }
    public static long catalogGeneration() { return FocusApi.registry().generation(); }

    public static boolean castDrawing(ServerPlayer player, InteractionHand hand, StrokeSphere sphere, StrokeTrace trace) {
        return request(player, hand, sphere, trace);
    }

    public static boolean cast(ServerPlayer player) {
        return request(player, InteractionHand.MAIN_HAND, null, null);
    }

    /** Our ordered click/release protocol supplies intent; the host can still veto execution. */
    private static boolean request(ServerPlayer player, InteractionHand hand, StrokeSphere sphere, StrokeTrace trace) {
        if (!player.server.isSameThread() || !CASTING.add(player)) return false;
        try { return requestOnce(player, hand, sphere, trace); }
        finally { CASTING.remove(player); }
    }

    private static boolean requestOnce(ServerPlayer player, InteractionHand hand, StrokeSphere sphere, StrokeTrace trace) {
        var wand = player.getItemInHand(hand);
        var hit = player.pick(player.blockInteractionRange(), 1, false);
        var context = new FocusUseContext.Snapshot(player, hand, wand,
                trace == null ? FocusGesture.PRESS : FocusGesture.RELEASE,
                FocusTargetKind.AIR, hit, trace == null ? 0 : trace.ticks(), FocusEndReason.RELEASE,
                java.util.Optional.of(ID), false, false);
        boolean[] committed = {false};
        WandPipelineApi.invoke(WandCastStages.EXECUTE, context, request -> {
            // This terminal owns a server-authored drawing/click, not a replacement actor or carrier.
            if (request.player() != player || request.carrier() != wand || request.hand() != hand)
                return FocusUseResult.FAIL;
            if (trace != null && request.hasTargetOverride()) return FocusUseResult.FAIL;
            if (request.hasTargetOverride() && !dev.senluo.witchcraft.motion.MotionPath.finite(request.hit().getLocation()))
                return FocusUseResult.FAIL;
            committed[0] = FocusBehaviorApi.session(player, request.hand(), CAST_MODULE, CastSession.class)
                    .map(session -> session.execute(sphere, trace, request.hasTargetOverride() ? request.hit().getLocation() : null))
                    .orElse(false);
            return committed[0] ? FocusUseResult.SUCCESS : FocusUseResult.FAIL;
        });
        return committed[0];
    }

    private static boolean cast(ServerPlayer player, InteractionHand hand, StrokeSphere sphere, StrokeTrace trace, net.minecraft.world.phys.Vec3 target, net.minecraft.world.phys.Vec3 origin) {
        ItemStack wand = player.getItemInHand(hand);
        var otherHand = hand == InteractionHand.MAIN_HAND ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND;
        var view = WandApi.view(wand);
        if (view.isEmpty() || !installed(wand) || FocusApi.registry().profile(ID).isEmpty()
                || player.isUsingItem()
                || WandApi.requiresBothHands(wand) && !player.getItemInHand(otherHand).isEmpty()) return false;
        if (WandPipelineApi.invoke(WandCastStages.COOLING_DOWN, new WandCastStages.Ready(wand, player),
                request -> request.player().getCooldowns().isOnCooldown(request.carrier().getItem()))) return false;
        double potential = SlashVis.potential(wand, player, view.get());
        if (!Double.isFinite(potential)) return false;
        double scale = trace == null ? 1 : trace.multiplier(SlashEfficacy.flight(wand, potential).shape().initialLength());
        SlashRules rules = SlashRules.bindings(player);
        if (trace == null && rules.chanting() && !SlashCriticalIntent.available(player, rules.chantTicks())) return false;
        boolean restrained = rules.restrained();
        // Drawing quotation uses the unbound blade length. The binding never discounts/inflates Vis.
        potential *= (trace == null ? 1 : rules.drawingPower(scale)) * rules.bindingPower();
        if (!Double.isFinite(potential) || potential <= 0 || potential > 1_000_000
                || !SlashVis.pay(wand, player, VisAction.SIMULATE, scale)) return false;
        FocusDamageModifiers modifiers = FocusDamageModifiers.capture(player);
        SlashFormation.Pose pose = sphere == null ? SlashFormation.preview(player) : new SlashFormation.Pose(
                sphere.forward(), 0, sphere.forward(), player.server.getTickCount(), 0, player.level().dimension(), true);
        SlashEntity slash = new SlashEntity(SlashContent.SLASH.get(), player.serverLevel());
        var flight = SlashEfficacy.flight(wand, potential, rules.rangeMultiplier());
        RadialOutline radial;
        try { radial = trace == null ? null : RadialOutline.from(sphere, trace, flight.shape().range()); }
        catch (IllegalArgumentException invalid) { return false; }
        slash.prepare(player, potential, modifiers.multiplier(), modifiers.bonus(), wand.copyWithCount(1), pose,
                flight.shape(), flight.speed(), trace == null ? origin : player.getEyePosition());
        slash.restrained(restrained);
        slash.upgrades(rules.destructive(), SlashUpgrades.level(wand, SlashUpgrades.PENETRATION) > 0,
                    SlashUpgrades.level(wand, SlashUpgrades.FISSION) > 0);
        slash.weapon(installedCarrier(wand));
        if (radial != null) slash.radial(radial);
        // Inert until payment succeeds. A canceled entity-join event must not consume Vis.
        if (!dev.tc4port.thaumcraft.api.wand.pipeline.WandPipelineApi.invoke(
                dev.tc4port.thaumcraft.api.wand.pipeline.WandCastStages.SPAWN,
                new dev.tc4port.thaumcraft.api.wand.pipeline.WandCastStages.Spawn(wand, player, slash),
                request -> request.entity() == slash && request.player() == player && request.carrier() == wand
                        && player.serverLevel().addFreshEntity(slash))) {
            slash.discard(); return false;
        }
        // Join callbacks may change the wand or actor. Revalidate against the state actually being paid.
        var current = WandApi.view(wand);
        if (slash.isRemoved() || !player.isAlive() || player.isSpectator() || player.level() != slash.level()
                || player.getItemInHand(hand) != wand || !installed(wand) || current.isEmpty()
                || player.containerMenu != player.inventoryMenu || player.isUsingItem()
                || dev.senluo.witchcraft.ritual.OwnershipRitual.frozen(player)
                || WandApi.requiresBothHands(wand) && !player.getItemInHand(otherHand).isEmpty()) {
            slash.discard(); return false;
        }
        potential = SlashVis.potential(wand, player, current.get());
        if (!Double.isFinite(potential)) { slash.discard(); return false; }
        scale = trace == null ? 1 : trace.multiplier(SlashEfficacy.flight(wand, potential).shape().initialLength());
        rules = SlashRules.bindings(player);
        restrained = rules.restrained();
        potential *= (trace == null ? 1 : rules.drawingPower(scale)) * rules.bindingPower();
        if (!Double.isFinite(potential) || potential <= 0 || potential > 1_000_000
                || !SlashVis.pay(wand, player, VisAction.SIMULATE, scale)) {
            slash.discard(); return false;
        }
        modifiers = FocusDamageModifiers.capture(player);
        // One authoritative draw after join callbacks and final affordability validation.
        // Quotes and individual collisions never roll; a failed payment discards this inert entity.
        double criticalBase = potential;
        try (var intent = dev.senluo.witchcraft.slash.SlashCriticalIntent.reserve(player)) {
            if (trace == null && rules.chanting() && intent == null) { slash.discard(); return false; }
            boolean rhythmic = SlashUpgrades.level(wand, SlashUpgrades.BLACK_FLASH) > 0;
            double chance = rhythmic ? SlashRhythm.chance(player, rules.criticalChance()) : rules.criticalChance();
            boolean critical = intent != null || dev.senluo.witchcraft.combat.CriticalRoll.roll(
                    chance, player.getRandom()::nextDouble);
            if (critical) {
                try { potential = dev.senluo.witchcraft.slash.SlashCritical.potential(criticalBase); }
                catch (IllegalArgumentException invalid) { slash.discard(); return false; }
            }
            var flightMode = dev.senluo.witchcraft.slash.SlashFlight.Mode.STANDARD;
            if (critical && trace == null) flightMode = player.getRandom().nextDouble()
                    < dev.senluo.witchcraft.slash.SlashCritical.CURVED_SPIN_CHANCE
                    ? dev.senluo.witchcraft.slash.SlashFlight.Mode.CURVED_SPIN
                    : dev.senluo.witchcraft.slash.SlashFlight.Mode.DIRECT;
            flight = SlashEfficacy.flight(wand, potential, rules.rangeMultiplier());
            try { radial = trace == null ? null : RadialOutline.from(sphere, trace, flight.shape().range()); }
            catch (IllegalArgumentException invalid) { slash.discard(); return false; }
            slash.prepare(player, potential, modifiers.multiplier(), modifiers.bonus(), wand.copyWithCount(1), pose,
                    flight.shape(), flight.speed(), trace == null ? origin : player.getEyePosition());
            slash.restrained(restrained);
            slash.upgrades(rules.destructive(), SlashUpgrades.level(wand, SlashUpgrades.PENETRATION) > 0,
                    SlashUpgrades.level(wand, SlashUpgrades.FISSION) > 0);
            slash.weapon(installedCarrier(wand));
            if (critical) slash.critical(criticalBase, SlashEfficacy.flight(wand, criticalBase, rules.rangeMultiplier()).speed());
            if (radial == null) slash.planFlight(player, pose, target, flightMode); else slash.radial(radial);
            if (!SlashVis.pay(wand, player, VisAction.EXECUTE, scale)) { slash.discard(); return false; }
            slash.arm();
            if (intent != null) intent.commit();
            if (rhythmic) SlashRhythm.committed(player, rules.criticalChance());
            if (radial == null) SlashFormation.commit(player, pose);
            // Clicks have no cooldown. Held-repeat cadence belongs to SlashRequests, not the whole wand item.
            player.swing(hand, true);
            SlashSounds.release(slash, player);
            player.inventoryMenu.broadcastChanges();
            return true;
        }
    }

    public static DamageSource damageSource(SlashEntity slash, ServerPlayer player, double multiplier, double bonus) {
        // A traveling carrier is not a projectile damage classification. Keep the carrier for scoped
        // knockback/i-frame policy and the caster for attribution; native direct focus damage owns defenses.
        return FocusDamageApi.sourceWithModifiers(slash.level(), FocusDamageApi.DIRECT, slash, player,
                new FocusDamageModifiers(multiplier, bonus));
    }
    public record FissionDamage(DamageSource source, float amount) {}
    public static FissionDamage fissionDamage(SlashEntity slash, ServerPlayer player, DamageSource quoted,
                                               float singleDamage, long blades) {
        if (blades < 1) throw new IllegalArgumentException("Empty fission batch");
        if (blades == 1) return new FissionDamage(quoted, singleDamage);
        double single = FocusDamageApi.modifiers(quoted).apply(singleDamage);
        float amount = (float)Math.min(Float.MAX_VALUE, single * blades);
        return new FissionDamage(damageSource(slash, player, 1, 0), amount);
    }
}
