package dev.senluo.witchcraft.compat.thaumcraft;

import dev.senluo.witchcraft.accessory.CastBarrier;
import dev.senluo.witchcraft.slash.SlashContent;
import dev.tc4port.thaumcraft.api.focus.*;
import dev.tc4port.thaumcraft.api.focus.action.FocusGesture;
import dev.tc4port.thaumcraft.api.wand.WandApi;
import dev.tc4port.thaumcraft.api.wand.WandPartApi;
import dev.tc4port.thaumcraft.api.wand.pipeline.*;
import java.util.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.*;

/** Keep the native held-use transaction and first bolt. Take ownership of only its secondary hops. */
public final class ShockChain {
    public static final FocusId SHOCK = FocusId.parse("focus_shock");
    private static final FocusUpgradeId CHAIN = FocusUpgradeId.parse("chainlightning");
    private static final FocusUpgradeId POTENCY = FocusUpgradeId.parse("potency");
    private static final ThreadLocal<ShockChain> ACTIVE = new ThreadLocal<>();
    private final ServerPlayer caster;
    private final ItemStack wand;
    private LivingEntity first;
    private int hops;

    private ShockChain(ServerPlayer caster, ItemStack wand) {
        this.caster = caster;
        this.wand = wand;
    }

    static void register() {
        WandPipelineApi.around(SHOCK, SlashContent.id("chain_cast"), WandCastStages.EXECUTE, -800, (use, next) -> {
            var previous = ACTIVE.get();
            var cast = use.gesture() == FocusGesture.HOLD && use.player() instanceof ServerPlayer player
                    && CastBarrier.active(player) && FocusApi.upgradeLevel(use.carrier(), CHAIN) > 0 ? new ShockChain(player, use.carrier()) : null;
            if (cast == null && previous == null) return next.proceed(use);
            ACTIVE.set(cast);
            try {
                var result = next.proceed(use);
                if (cast != null && cast.hops > 0) cast.propagate();
                return result;
            } finally {
                if (previous == null) ACTIVE.remove(); else ACTIVE.set(previous);
            }
        });
        // Resolve the ordinary first-target search once, after other aim processors (including barriers).
        WandPipelineApi.around(SHOCK, SlashContent.id("chain_first"), WandCastStages.LIVING_AIM, -2000, (query, next) -> {
            var aim = next.proceed(query);
            var cast = ACTIVE.get();
            if (cast == null || aim.player() != cast.caster || aim.carrier() != cast.wand) return aim;
            var hit = aim.selectedTarget().orElseGet(() -> cast.firstHit(aim.range()));
            cast.first = hit instanceof EntityHitResult found && found.getEntity() instanceof LivingEntity living ? living : null;
            return aim.withTarget(hit);
        });
        // The native ray asks for this budget only after paying, resolving aim and striking its first target.
        // Retain the configured budget for our branch; the native branch then has zero hops.
        WandPipelineApi.around(SHOCK, SlashContent.id("chain_budget"), WandCastStages.PARAMETER, -2000, (query, next) -> {
            double value = next.proceed(query);
            var cast = ACTIVE.get();
            if (cast == null || cast.first == null || query.carrier() != cast.wand || !query.key().equals("chain_targets")) return value;
            if (!Double.isFinite(value) || value < 0 || value > FocusParameters.LIMIT) return value;
            cast.hops = (int)value;
            return 0.0;
        });
    }

    private HitResult firstHit(double range) {
        var start = caster.getEyePosition();
        var delta = caster.getLookAngle().scale(range);
        var end = start.add(delta);
        var search = caster.getBoundingBox().expandTowards(delta).inflate(1.1);
        LivingEntity nearest = null;
        double best = Double.MAX_VALUE;
        for (var entity : caster.level().getEntitiesOfClass(LivingEntity.class, search, e -> e != caster && e.isAlive())) {
            var point = entity.getBoundingBox().inflate(1.1).clip(start, end).orElse(null);
            if (point == null || start.distanceToSqr(point) >= best) continue;
            var ray = new ClipContext(start, point, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, caster);
            if (caster.level().clip(ray).getType() == HitResult.Type.MISS) {
                nearest = entity;
                best = start.distanceToSqr(point);
            }
        }
        return nearest == null ? BlockHitResult.miss(end, net.minecraft.core.Direction.getNearest(delta.x, delta.y, delta.z),
                net.minecraft.core.BlockPos.containing(end)) : new EntityHitResult(nearest);
    }

    private double parameter(String name) {
        var state = FocusApi.installedState(wand).orElseThrow();
        var upgrades = new HashMap<>(state.upgradeLevels());
        boolean runes = WandApi.view(wand).flatMap(view -> WandPartApi.rod(view.rod())).filter(rod -> rod.runes()).isPresent();
        upgrades.put(POTENCY, state.level(POTENCY) + (runes ? 1 : 0));
        return FocusApi.parameter(wand, state.focusId(), upgrades, name);
    }

    private void propagate() {
        var visited = new HashSet<LivingEntity>();
        var center = first;
        visited.add(center);
        for (int i = 0; i < hops; i++) {
            if (CastBarrier.blocks(center, true)) break;
            CastBarrier.origin(caster, center.getBoundingBox().getCenter());
            var area = center.getBoundingBox().inflate(parameter("chain_range"));
            var next = center.level().getEntitiesOfClass(LivingEntity.class, area,
                    target -> target != caster && !visited.contains(target) && target.isAlive() && !target.isRemoved()
                            && target.level() == caster.level() && caster.level().isLoaded(target.blockPosition())
                            && caster.level().getEntity(target.getId()) == target
                            && (!(target instanceof Player) || caster.server.isPvpAllowed()))
                    .stream().min(Comparator.comparingDouble(center::distanceToSqr)).orElse(null);
            if (next == null || CastBarrier.blocks(next, true)) break;
            ShockBolt.send(caster.serverLevel(), center, next);
            next.hurt(FocusDamageApi.source(caster, FocusDamageApi.DIRECT), (float)parameter("chain_damage"));
            visited.add(next);
            center = next;
        }
    }
}
