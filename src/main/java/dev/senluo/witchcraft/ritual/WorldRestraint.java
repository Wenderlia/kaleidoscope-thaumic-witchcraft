package dev.senluo.witchcraft.ritual;

import dev.senluo.witchcraft.KaleidoscopeWitchcraft;
import java.util.*;
import java.util.function.Predicate;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.TraceableEntity;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;

/** Per-action conversion of world destruction into living damage. Ordinary damage has no policy.
 * Destructive native operations supply proof; adapters can supply it before an early damage call.
 */
@EventBusSubscriber(modid = KaleidoscopeWitchcraft.MOD_ID)
public final class WorldRestraint {
    public static final float LIVING_DAMAGE_MULTIPLIER = 2.0F;
    public record Action(boolean constrained) {
        public float damage(float original) { return constrained && original > 0 ? Math.min(Float.MAX_VALUE, original * LIVING_DAMAGE_MULTIPLIER) : original; }
    }
    private static final class Active {
        final Entity carrier;
        final boolean eligible;
        boolean constrained;
        Active(Entity carrier, boolean eligible, Action action) {
            this.carrier = carrier; this.eligible = eligible; constrained = action.constrained;
        }
    }
    public static final Action NONE = new Action(false);
    private static final String KEY = "witchcraft_world_restraint";
    private static final ThreadLocal<Active> ACTIVE = new ThreadLocal<>();
    private static final ThreadLocal<DamageSource> PRICED_DAMAGE = new ThreadLocal<>();
    private static final List<Predicate<Projectile>> CAPABILITIES = new ArrayList<>();

    /** Common setup only. Dynamic qualifications (e.g. an ignition upgrade) belong in the predicate. */
    public static void registerCapability(Predicate<Projectile> capability) { CAPABILITIES.add(Objects.requireNonNull(capability)); }
    private static boolean declaredDestruction(Projectile projectile) {
        for (var capability : CAPABILITIES) if (capability.test(projectile)) return true;
        return false;
    }
    public static boolean bound(Entity actor) {
        for (int depth = 0; actor != null && depth < 8; depth++) {
            if (actor instanceof ServerPlayer player) return !player.isFakePlayer() && GeasBindings.has(player, GeasBindings.RESTRAINT);
            if (actor instanceof TraceableEntity traceable) actor = traceable.getOwner();
            else if (actor instanceof OwnableEntity ownable && actor.level() instanceof ServerLevel level)
                return ownable.getOwnerUUID() != null && GeasBindings.has(level.getServer(), ownable.getOwnerUUID(), GeasBindings.RESTRAINT);
            else return false;
        }
        return false;
    }
    public static Action quote(Entity actor, boolean worldDestructionEnabled) {
        return worldDestructionEnabled && bound(actor) ? new Action(true) : NONE;
    }
    public static void placed(Entity carrier, Entity placer) {
        if (!(placer instanceof ServerPlayer player) || player.isFakePlayer()) return;
        CompoundTag data = carrier.getPersistentData().getCompound(KEY);
        data.putUUID("PlacedBy", placer.getUUID());
        carrier.getPersistentData().put(KEY, data);
    }
    public static Action placedQuote(Entity carrier, Entity activator, boolean capability) {
        if (!capability || !(carrier.level() instanceof ServerLevel level)) return NONE;
        if (bound(activator)) return new Action(true);
        CompoundTag data = carrier.getPersistentData().getCompound(KEY);
        return data.hasUUID("PlacedBy") && GeasBindings.has(level.getServer(), data.getUUID("PlacedBy"), GeasBindings.RESTRAINT)
                ? new Action(true) : NONE;
    }
    public static void capture(Entity carrier, Action action) {
        // Explicit activation of an already known destructive action (TNT/ignited creeper).
        CompoundTag data = carrier.getPersistentData().getCompound(KEY);
        data.putBoolean("OwnerBound", action.constrained());
        data.putBoolean("Captured", true); data.putBoolean("Constrained", action.constrained());
        carrier.getPersistentData().put(KEY, data);
    }
    public static Action captured(Entity carrier) {
        if (carrier == null) return null;
        CompoundTag data = carrier.getPersistentData().getCompound(KEY);
        return data.getBoolean("Captured") ? new Action(data.getBoolean("Constrained")) : null;
    }
    public static Action projectile(Projectile projectile) {
        Action saved = captured(projectile);
        if (saved != null) return saved;
        boolean eligible = ownerBound(projectile, projectile.getOwner());
        Action result = new Action(eligible && declaredDestruction(projectile));
        // Negative preflight means no advance proof, NOT proof that no destructive call can follow.
        CompoundTag data = projectile.getPersistentData().getCompound(KEY);
        data.putBoolean("Captured", true); data.putBoolean("Constrained", result.constrained());
        projectile.getPersistentData().put(KEY, data);
        return result;
    }
    /** Native reflection/deflection establishes a new source. Do not reuse the previous shot's quote. */
    public static void ownerChanged(Projectile projectile) {
        if (projectile.level().isClientSide) return;
        CompoundTag data = projectile.getPersistentData().getCompound(KEY);
        data.remove("OwnerBound"); data.remove("Captured"); data.remove("Constrained");
        if (data.isEmpty()) projectile.getPersistentData().remove(KEY);
    }
    @SubscribeEvent public static void joined(EntityJoinLevelEvent event) {
        if (event.getLevel() instanceof ServerLevel && event.getEntity() instanceof Projectile projectile && projectile.getOwner() != null)
            projectile(projectile);
    }
    /** Cache the binding on the action carrier, not by entity type or globally by player. */
    private static boolean ownerBound(Entity carrier, Entity actor) {
        // A player is not a one-shot attack carrier. Each attack gets a fresh scope.
        if (carrier instanceof ServerPlayer) return bound(actor);
        if (carrier == null) return bound(actor);
        CompoundTag data = carrier.getPersistentData().getCompound(KEY);
        if (data.contains("OwnerBound")) return data.getBoolean("OwnerBound");
        boolean eligible = bound(actor) || placedQuote(carrier, null, true).constrained();
        data.putBoolean("OwnerBound", eligible);
        carrier.getPersistentData().put(KEY, data);
        return eligible;
    }
    public static Action explosion(Entity carrier, DamageSource source, boolean capability) {
        if (!capability) return NONE;
        // The native constructor's destruction/fire settings are direct evidence. No entity list.
        Action action = new Action(ownerBound(carrier, source.getEntity() == null ? carrier : source.getEntity()));
        if (action.constrained()) observed(carrier);
        return action;
    }
    /** A narrowly delimited attack callback. Nested callbacks restore the previous policy in finally. */
    public static <T> T during(Entity carrier, Action action, Supplier<T> body) {
        Objects.requireNonNull(carrier, "An action scope requires its actual carrier");
        Active previous = ACTIVE.get();
        // Public focus IMPACT may nest inside native projectile impact. They are one action;
        // a nested Player.attack, even by the same player, is a separate action instead.
        if (previous != null && previous.carrier == carrier && !(carrier instanceof ServerPlayer)) {
            previous.constrained |= action.constrained();
            return body.get();
        }
        ACTIVE.set(new Active(carrier, ownerBound(carrier, carrier), action));
        try { return body.get(); }
        finally { if (previous == null) ACTIVE.remove(); else ACTIVE.set(previous); }
    }
    public static boolean blocksWorldEdit(Level level) {
        Active active = ACTIVE.get();
        return !level.isClientSide && active != null && active.constrained && active.carrier.level() == level;
    }
    /** Called at an actual destructive operation before drops/writes, not at a class-name guess. */
    public static boolean denyDestructiveChange(Level level) {
        Active active = ACTIVE.get();
        if (level.isClientSide || active == null || !active.eligible || active.carrier.level() != level) return false;
        observed(active.carrier);
        return true;
    }
    public static boolean denyWrite(Level level, BlockPos pos, BlockState next) {
        Active active = ACTIVE.get();
        if (level.isClientSide || active == null || !active.eligible || active.carrier.level() != level) return false;
        BlockState old = level.getBlockState(pos);
        if (old == next) return false;
        // Priming consumes the explosive itself. Its resulting explosion has its own traced policy.
        if (old.is(Blocks.TNT) && next.isAir()) return false;
        boolean destructive = next.is(BlockTags.FIRE) || !old.isAir() && !old.is(next.getBlock());
        return (active.constrained || destructive) && denyDestructiveChange(level);
    }
    private static void observed(Entity carrier) {
        Active active = ACTIVE.get();
        if (active != null && active.carrier == carrier) active.constrained = true;
        if (carrier != null && !(carrier instanceof ServerPlayer)) {
            CompoundTag data = carrier.getPersistentData().getCompound(KEY);
            if (!data.getBoolean("Constrained")) {
                data.putBoolean("Captured", true); data.putBoolean("Constrained", true);
                carrier.getPersistentData().put(KEY, data);
            }
        }
    }
    /** Explosion damage is already priced at the native hurt call, regardless of its damage tag. */
    public static <T> T pricedDamage(DamageSource source, Supplier<T> body) {
        DamageSource previous = PRICED_DAMAGE.get(); PRICED_DAMAGE.set(source);
        try { return body.get(); }
        finally { if (previous == null) PRICED_DAMAGE.remove(); else PRICED_DAMAGE.set(previous); }
    }
    @SubscribeEvent public static void damage(LivingIncomingDamageEvent event) {
        Active active = ACTIVE.get();
        // Native explosions apply their own local multiplier. Retaliation/other nested damage cannot inherit it.
        if (!event.getEntity().level().isClientSide && active != null && active.constrained
                && event.getSource().getDirectEntity() == active.carrier && PRICED_DAMAGE.get() != event.getSource())
            event.setAmount(new Action(true).damage(event.getAmount()));
    }
    private WorldRestraint() {}
}
