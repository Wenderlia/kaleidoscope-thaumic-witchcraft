package dev.senluo.witchcraft.compat.thaumcraft;

import dev.senluo.witchcraft.slash.*;
import dev.senluo.witchcraft.slash.drawing.*;
import dev.tc4port.thaumcraft.api.aspect.VisAction;
import dev.tc4port.thaumcraft.api.focus.*;
import dev.tc4port.thaumcraft.api.focus.behavior.*;
import dev.tc4port.thaumcraft.api.focus.action.*;
import dev.tc4port.thaumcraft.api.wand.pipeline.*;
import dev.tc4port.thaumcraft.api.wand.WandCapabilities;
import dev.tc4port.thaumcraft.api.wand.WandTool;
import dev.tc4port.thaumcraft.api.wand.WandView;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import dev.senluo.witchcraft.combat.CriticalRoll;
import dev.senluo.witchcraft.ritual.OwnershipRitual;
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
        boolean execute(Opening opening, StrokeSphere sphere, StrokeTrace trace, Vec3 target) {
            return cast(context, opening, sphere, trace, target);
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
        return hasSlashFocus(wand.getCapability(WandCapabilities.TOOL));
    }

    private static boolean hasSlashFocus(WandTool tool) {
        return tool != null && tool.view().flatMap(WandView::installedFocus)
                .filter(focus -> focus.focusId().equals(ID)).isPresent();
    }

    /** Full component-preserving carrier. TC's public state view exposes upgrades, not this stack. */
    public static ItemStack installedCarrier(ItemStack wand) {
        if (!installed(wand)) return ItemStack.EMPTY;
        ItemStack focus = FocusApi.installedStack(wand);
        return focus.is(SlashContent.FOCUS.get()) ? focus : ItemStack.EMPTY;
    }

    /** Optional dependency state comparison remains inside the TC bridge. */
    public static Object state(ItemStack wand) { return FocusApi.installedState(wand).orElse(null); }
    public static boolean sameCore(ItemStack wand, Object focus, long generation) {
        return catalogGeneration() == generation && java.util.Objects.equals(focus, state(wand));
    }
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
        var focus = FocusApi.installedState(wand).filter(s -> s.focusId().equals(ID)).orElse(null);
        if (focus == null) return false;
        var opening = new Opening(hand, wand, player.serverLevel(), focus, catalogGeneration());
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
                    .map(session -> session.execute(opening, sphere, trace, request.hasTargetOverride() ? request.hit().getLocation() : null))
                    .orElse(false);
            return committed[0] ? FocusUseResult.SUCCESS : FocusUseResult.FAIL;
        });
        return committed[0];
    }

    private record Opening(InteractionHand hand, ItemStack wand, ServerLevel world, FocusStateView focus, long generation) {
        boolean matches(ServerPlayer player) {
            return player.level() == world && player.getItemInHand(hand) == wand && sameCore(wand, focus, generation);
        }

        boolean eligible(ServerPlayer player) {
            if (!matches(player) || !player.isAlive() || player.isSpectator() || player.isUsingItem()
                    || player.containerMenu != player.inventoryMenu || OwnershipRitual.frozen(player)) return false;
            var tool = wand.getCapability(WandCapabilities.TOOL);
            var other = hand == InteractionHand.MAIN_HAND ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND;
            return tool != null && (!tool.requiresBothHands() || player.getItemInHand(other).isEmpty());
        }
    }

    private record Phase(SlashVis offer, SlashRules rules, FocusDamageModifiers damage, ItemStack tool, ItemStack weapon) {
        static Phase simulate(FocusServerContext<?, ?> context, Opening opening, StrokeTrace trace) {
            var player = context.player();
            var rules = SlashRules.bindings(player);
            if (trace == null && rules.chanting() && !SlashCriticalIntent.available(player, rules.chantTicks())) return null;
            var damage = FocusDamageModifiers.capture(player);
            var tool = opening.wand.copyWithCount(1);
            var weapon = FocusApi.installedStack(opening.wand);
            var offer = SlashVis.quote(context, opening.focus.upgradeLevels(), rules, trace);
            if (offer == null || !opening.eligible(player) || !context.pay(offer.price(), VisAction.SIMULATE).affordable()) return null;
            return new Phase(offer, rules, damage, tool, weapon);
        }

        SlashEfficacy.Flight flight(double power) { return offer.parameters().flight(power, rules.rangeMultiplier()); }

        void applyPolicy(SlashEntity slash, FocusStateView focus) {
            slash.restrained(rules.restrained());
            slash.upgrades(rules.destructive(), focus.level(SlashUpgrades.PENETRATION) > 0, focus.level(SlashUpgrades.FISSION) > 0);
            slash.weapon(weapon);
        }
    }

    /** Previews and final geometry use the exact same stroke and the phase's own range. */
    private static boolean radialGeometry(SlashEntity slash, StrokeSphere sphere, StrokeTrace trace, double range) {
        if (trace == null) return true;
        RadialOutline outline;
        try { outline = RadialOutline.from(sphere, trace, range); }
        catch (IllegalArgumentException invalid) { return false; }
        slash.radial(outline);
        return true;
    }

    private static boolean cast(FocusServerContext<?, ?> context, Opening opening, StrokeSphere sphere, StrokeTrace trace, Vec3 target) {
        ServerPlayer player = context.player();
        if (context.carrier() != opening.wand || context.hand() != opening.hand || !opening.eligible(player)
                || context.coolingDown()) return false;
        Vec3 tip = context.castOrigin();
        if (!opening.matches(player)) return false;
        var preview = Phase.simulate(context, opening, trace);
        if (preview == null || !opening.eligible(player)) return false;
        var pose = sphere == null ? SlashFormation.preview(player) : new SlashFormation.Pose(
                sphere.forward(), 0, sphere.forward(), player.server.getTickCount(), 0, player.level().dimension(), true);
        var slash = new SlashEntity(SlashContent.SLASH.get(), opening.world);
        boolean armed = false;
        try {
            var power = preview.offer.potential();
            var flight = preview.flight(power);
            slash.prepare(player, power, preview.damage.multiplier(), preview.damage.bonus(), preview.tool, pose,
                    flight.shape(), flight.speed(), trace == null ? tip : player.getEyePosition());
            preview.applyPolicy(slash, opening.focus);
            if (!radialGeometry(slash, sphere, trace, flight.shape().range())) return false;
            boolean admitted = WandPipelineApi.invoke(WandCastStages.SPAWN, new WandCastStages.Spawn(opening.wand, player, slash),
                    request -> request.entity() == slash && request.player() == player && request.carrier() == opening.wand
                            && opening.world.addFreshEntity(slash));
            if (!admitted || !slash.pendingAtOrigin(opening.world) || !opening.eligible(player)) return false;
            var phase = Phase.simulate(context, opening, trace);
            if (phase == null || !slash.pendingAtOrigin(opening.world) || !opening.eligible(player)) return false;
            var rules = phase.rules;
            try (var intent = SlashCriticalIntent.reserve(player, rules)) {
                if (trace == null && rules.chanting() && intent == null) return false;
                boolean rhythmic = opening.focus.level(SlashUpgrades.BLACK_FLASH) > 0;
                double chance = rhythmic ? SlashRhythm.chance(player, rules.criticalChance()) : rules.criticalChance();
                boolean critical = intent != null || CriticalRoll.roll(chance, player.getRandom()::nextDouble);
                double base = phase.offer.potential();
                try { power = critical ? SlashCritical.potential(base) : base; }
                catch (IllegalArgumentException unrepresentable) { return false; }
                flight = phase.flight(power);
                slash.configureFlight(power, phase.damage.multiplier(), phase.damage.bonus(), phase.tool, flight.shape(), flight.speed());
                phase.applyPolicy(slash, opening.focus);
                if (critical) slash.critical(base, phase.flight(base).speed());
                if (trace == null) {
                    var mode = SlashFlight.Mode.STANDARD;
                    if (critical) mode = player.getRandom().nextDouble() < SlashCritical.CURVED_SPIN_CHANCE
                            ? SlashFlight.Mode.CURVED_SPIN : SlashFlight.Mode.DIRECT;
                    slash.planFlight(player, pose, target, mode);
                } else if (!radialGeometry(slash, sphere, trace, flight.shape().range())) return false;
                if (!slash.pendingAtOrigin(opening.world) || !opening.eligible(player)) return false;
                if (!context.pay(phase.offer.price(), VisAction.EXECUTE).consumed()) return false;
                if (!slash.pendingAtOrigin(opening.world)) return true;
                slash.arm();
                armed = true;
                if (intent != null) intent.commit();
                if (rhythmic) SlashRhythm.committed(player, rules.criticalChance());
                if (trace == null) SlashFormation.commit(player, pose);
                player.swing(opening.hand, true);
                SlashSounds.release(slash, player);
                player.inventoryMenu.broadcastChanges();
                return true;
            }
        } finally {
            if (!armed && !slash.isRemoved()) slash.discard();
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
