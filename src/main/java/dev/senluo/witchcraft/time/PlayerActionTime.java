package dev.senluo.witchcraft.time;

import dev.senluo.witchcraft.KaleidoscopeWitchcraft;
import dev.senluo.witchcraft.mixin.brew.StrideTeleportAccess;
import dev.senluo.witchcraft.mixin.time.EntityMotionAccess;
import dev.senluo.witchcraft.ritual.OwnershipRitual;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.RangedAttribute;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.EntityAttributeModificationEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.event.tick.EntityTickEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/** Player motion has client prediction; it must not use the non-player skipped-entity-tick path. */
public final class PlayerActionTime {
    private static final DeferredRegister<Attribute> ATTRIBUTES = DeferredRegister.create(Registries.ATTRIBUTE, KaleidoscopeWitchcraft.MOD_ID);
    public static final DeferredHolder<Attribute, Attribute> MOTION_RATE = ATTRIBUTES.register("player_motion_rate", () ->
            new RangedAttribute("attribute.kaleidoscope_witchcraft.player_motion_rate", 1, .5, 1).setSyncable(true));
    private static final ResourceLocation FIELD = ResourceLocation.fromNamespaceAndPath(KaleidoscopeWitchcraft.MOD_ID, "owned_tree_motion");
    private static final ThreadLocal<PacketStep> PACKET = new ThreadLocal<>();
    private static final java.util.Map<ServerPlayer, Integer> IMMUNITY_BEFORE = new java.util.WeakHashMap<>();

    private static final class PacketStep {
        final ServerPlayer player;
        final LivingEntity receiver;
        double captured;
        PacketStep(ServerPlayer player, LivingEntity receiver) { this.player = player; this.receiver = receiver; }
    }

    private PlayerActionTime() {}
    public static void register(IEventBus bus) {
        ATTRIBUTES.register(bus);
        bus.addListener((EntityAttributeModificationEvent event) -> event.add(EntityType.PLAYER, MOTION_RATE));
        NeoForge.EVENT_BUS.addListener(PlayerActionTime::prepare);
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, true, PlayerActionTime::beforeEntityTick);
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST, PlayerActionTime::afterEntityTick);
    }

    private static void beforeEntityTick(EntityTickEvent.Pre event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        // EntityTickEvent surrounds ServerPlayer.tick; PlayerTickEvent surrounds the
        // separate Player.tick called from doTick and is not the same clock.
        IMMUNITY_BEFORE.remove(player);
        if (!event.isCanceled() && player.invulnerableTime > 1)
            IMMUNITY_BEFORE.put(player, player.invulnerableTime);
    }

    private static void afterEntityTick(EntityTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        Integer before = IMMUNITY_BEFORE.remove(player);
        // No Post is emitted for canceled or exceptional ticks. The next Pre replaces
        // any abandoned snapshot; a weak key cannot keep a disconnected player alive.
        if (before != null) shortenImmunity(player, before);
    }

    public static boolean eligible(Player player) {
        return player.isAlive() && !player.isSpectator() && !player.isPassenger() && !OwnershipRitual.frozen(player);
    }

    public static LivingEntity receiver(ServerPlayer player) {
        if (!eligible(player)) return null;
        return TemporalWorld.get(player.serverLevel()).receiver(player.blockPosition(), 4,
                core -> core.hasOwner()
                        && !core.tissue.bond.belongsTo(player.getUUID()));
    }

    private static void prepare(PlayerTickEvent.Pre event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        var attribute = player.getAttribute(MOTION_RATE);
        if (attribute == null) return;
        boolean slowed = receiver(player) != null;
        if (slowed && !attribute.hasModifier(FIELD))
            attribute.addTransientModifier(new AttributeModifier(FIELD, -.5, AttributeModifier.Operation.ADD_VALUE));
        else if (!slowed && attribute.hasModifier(FIELD)) attribute.removeModifier(FIELD);
    }

    public static double rate(Player player) {
        var value = player.getAttribute(MOTION_RATE);
        return eligible(player) && value != null && value.hasModifier(FIELD) ? .5 : 1;
    }

    /** Scale displacement, leaving momentum and all other player clocks intact. */
    public static void move(Player player, MoverType type, Vec3 movement, java.util.function.Consumer<Vec3> original) {
        if (!eligible(player) || movement.lengthSqr() < 1.0E-12) {
            original.accept(movement); return;
        }
        PacketStep packet = PACKET.get();
        if (packet != null && packet.player == player) { original.accept(movement); return; } // Already predicted.
        if (player.level().isClientSide) {
            original.accept(player.isLocalPlayer() ? movement.scale(rate(player)) : movement); return;
        }
        if (!(player instanceof ServerPlayer server)) { original.accept(movement); return; }
        LivingEntity core = receiver(server);
        if (core == null) { original.accept(movement); return; }
        Vec3 before = player.position();
        int teleport = ((StrideTeleportAccess) server.connection).witchcraft$teleportSequence();
        Vec3 ordinary = ((EntityMotionAccess) player).senluo$collide(movement);
        double possibleLength = independentLength(server, ordinary);
        original.accept(movement.scale(.5));
        double actual = independentLength(server, player.position().subtract(before));
        double captured = Math.max(0, Math.min(actual, possibleLength - actual));
        if (captured > 0 && teleport == ((StrideTeleportAccess) server.connection).witchcraft$teleportSequence()
                && receiverStillValid(server, core)) credit(server, core, captured);
    }

    /** Run vanilla validation first; teleports, rejected packets and ridden vehicles never mint income. */
    public static void movementPacket(ServerPlayer player, Runnable original) {
        if (!player.server.isSameThread()) { original.run(); return; }
        PacketStep previous = PACKET.get();
        PacketStep step = new PacketStep(player, rate(player) < 1 ? receiver(player) : null);
        int teleport = ((StrideTeleportAccess) player.connection).witchcraft$teleportSequence();
        ServerLevel level = player.serverLevel();
        PACKET.set(step);
        try {
            original.run();
            if (step.captured > 0 && player.serverLevel() == level && eligible(player)
                    && teleport == ((StrideTeleportAccess) player.connection).witchcraft$teleportSequence()
                    && receiverStillValid(player, step.receiver)) credit(player, step.receiver, step.captured);
        } finally {
            if (previous == null) PACKET.remove(); else PACKET.set(previous);
        }
    }

    /** Observe only vanilla's collision-tested move, not the packet's unvalidated destination. */
    public static void packetStep(ServerPlayer player, Vec3 movement, Runnable original) {
        PacketStep step = PACKET.get();
        if (step == null || step.player != player || step.receiver == null) { original.run(); return; }
        Vec3 before = player.position();
        Vec3 possible = ((EntityMotionAccess) player).senluo$collide(movement.scale(2));
        double possibleLength = independentLength(player, possible);
        original.run();
        Vec3 actual = player.position().subtract(before);
        if (actual.lengthSqr() > 1.0E-12)
            step.captured += Math.max(0, Math.min(independentLength(player, actual), possibleLength - independentLength(player, actual)));
    }

    private static boolean receiverStillValid(ServerPlayer player, LivingEntity core) {
        return core != null && core.getLevel() == player.level() && !core.isRemoved() && core.tissue.giant
                && core.hasOwner() && !core.tissue.bond.belongsTo(player.getUUID())
                && core.tissue.sourceEnabled(4) && player.serverLevel().shouldTickBlocksAt(core.getBlockPos())
                && TemporalWorld.get(player.serverLevel()).node(core.getBlockPos()) == core;
    }

    private static double independentLength(ServerPlayer player, Vec3 displacement) {
        return FluidTime.independentMotion(player.serverLevel(), player, displacement).length();
    }

    private static double volume(Player player) {
        var box = player.getBoundingBox();
        return box.getXsize() * box.getYsize() * box.getZsize();
    }

    private static boolean credit(ServerPlayer player, LivingEntity core, double distance) {
        return TemporalWorld.get(player.serverLevel()).credit(core, 4, SourceIncomeRates.motion(volume(player), distance));
    }

    /** Spend a second immunity tick after vanilla's decrement, shortening this clock by half. */
    public static void shortenImmunity(ServerPlayer player, int before) {
        if (before <= 1 || player.invulnerableTime != before - 1) return;
        LivingEntity core = receiver(player);
        if (core != null && TemporalWorld.get(player.serverLevel()).credit(core, 4, SourceIncomeRates.clock(volume(player), 1.0 / 20)))
            player.invulnerableTime--;
    }
}
