package dev.senluo.witchcraft.accessory;

import dev.senluo.witchcraft.KaleidoscopeWitchcraft;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/** Bounded, disposable presentation of committed constraints. Never used to decide gameplay. */
public final class BarrierVisuals {
    public static final int MOB = 0, SONIC = 1, PROPAGATION = 2;
    private static final int INTERVAL = 10, MAX_EVENTS = 32, MAX_PER_SOURCE = 8, MAX_RECENT = 256;
    private static final Map<Level, Budget> BUDGETS = new WeakHashMap<>();
    private static Consumer<Contact> contacts = ignored -> {};
    private static BiConsumer<Entity, FlightPath> flights = (entity, path) -> {};
    @FunctionalInterface public interface Emission { void run(Level level, Vec3 origin, Runnable original); }
    private static Emission particles = (level, origin, original) -> original.run();

    public record Contact(ResourceLocation dimension, int entityId, int sourceId, int kind,
            Vec3 point, Vec3 normal, float strength, float radius) implements CustomPacketPayload {
        public static final Type<Contact> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(
                KaleidoscopeWitchcraft.MOD_ID, "barrier_contact"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Contact> CODEC = StreamCodec.of((buf, value) -> {
            buf.writeResourceLocation(value.dimension);
            buf.writeVarInt(value.entityId); buf.writeVarInt(value.sourceId); buf.writeByte(value.kind);
            buf.writeDouble(value.point.x); buf.writeDouble(value.point.y); buf.writeDouble(value.point.z);
            buf.writeFloat((float) value.normal.x); buf.writeFloat((float) value.normal.y); buf.writeFloat((float) value.normal.z);
            buf.writeFloat(value.strength); buf.writeFloat(value.radius);
        }, buf -> {
            Contact value = new Contact(buf.readResourceLocation(), buf.readVarInt(), buf.readVarInt(), buf.readUnsignedByte(),
                    new Vec3(buf.readDouble(), buf.readDouble(), buf.readDouble()),
                    new Vec3(buf.readFloat(), buf.readFloat(), buf.readFloat()), buf.readFloat(), buf.readFloat());
            if (!valid(value)) throw new IllegalArgumentException("Invalid barrier contact");
            return value;
        });
        @Override public Type<Contact> type() { return TYPE; }
    }

    private static final class Budget {
        final LinkedHashMap<Integer, Long> lastSent = new LinkedHashMap<>();
        final Map<Integer, Integer> sources = new HashMap<>();
        long window = Long.MIN_VALUE, swept = Long.MIN_VALUE;
        int events;

        void advance(long now) {
            if (window != now / INTERVAL) {
                window = now / INTERVAL; events = 0; sources.clear();
            }
            if (swept == Long.MIN_VALUE || now < swept || now - swept >= 20) {
                lastSent.values().removeIf(stamp -> now < stamp || now - stamp >= 40);
                swept = now;
            }
        }

        boolean permits(int entity, long now) {
            Long previous = lastSent.get(entity);
            return events < MAX_EVENTS && (previous == null || now - previous >= INTERVAL);
        }
    }

    /** Client setup supplies presentation callbacks; common/dedicated-server classes remain client-free. */
    public static void install(Consumer<Contact> contact, BiConsumer<Entity, FlightPath> flight) {
        contacts = java.util.Objects.requireNonNull(contact);
        flights = java.util.Objects.requireNonNull(flight);
    }

    public static void installParticles(Emission emission) { particles = java.util.Objects.requireNonNull(emission); }

    public static void particles(Level level, Vec3 origin, Runnable original) {
        if (level.isClientSide) particles.run(level, origin, original); else original.run();
    }

    public static void register(RegisterPayloadHandlersEvent event) {
        event.registrar(KaleidoscopeWitchcraft.NETWORK_VERSION).playToClient(Contact.TYPE, Contact.CODEC,
                (packet, context) -> context.enqueueWork(() -> contacts.accept(packet)));
    }

    static void flight(Entity entity, FlightPath path) {
        if (entity.level().isClientSide) flights.accept(entity, path);
    }

    public static void invalidate(Level level) {
        if (!level.isClientSide) BUDGETS.remove(level);
    }

    /** Skip even the optional result metadata when a target has just been reported. */
    static boolean canObserve(Entity entity) {
        if (!(entity.level() instanceof ServerLevel)) return false;
        Budget budget = BUDGETS.get(entity.level());
        if (budget == null) return true;
        budget.advance(entity.level().getGameTime());
        return budget.permits(entity.getId(), entity.level().getGameTime());
    }

    static void moved(Entity entity, Vec3 start, Vec3 requested, Vec3 allowed, MotionConstraints.Contact result) {
        MotionConstraints.Boundary boundary = result.boundary();
        if (boundary == null || boundary.sourceId() < 0 || requested.lengthSqr() <= 1.0E-12) return;
        double reduction = requested.subtract(allowed).length();
        double fraction = reduction / requested.length();
        if (reduction < 1.0E-5 || fraction < .025) return;
        Vec3 toward = boundary.nearest(start).subtract(start).normalize();
        if (toward.lengthSqr() < .5) toward = requested.normalize();
        // Grounding, lateral movement and departure do not produce a fake impact flash.
        if (requested.dot(toward) <= 1.0E-7) return;
        float radius = (float) Math.clamp(entity.getBbWidth() * .65, .45, .9);
        // Native collision and tree clocks may shorten the already-mapped move further.
        // Anchor presentation to the committed body, not its predicted destination.
        Vec3 point = entity.getBoundingBox().getCenter().add(toward.scale(Math.min(.45, entity.getBbWidth() * .4)));
        send(entity, boundary.sourceId(), MOB, point, toward, (float) Math.min(1, fraction), radius);
    }

    static void sonic(Entity warden, int sourceId, Vec3 point, Vec3 direction) {
        send(warden, sourceId, SONIC, point, direction, .8F, .7F);
    }

    static void travel(Entity entity, dev.senluo.witchcraft.motion.TravelStep quote) {
        travel(entity, quote, entity.getBoundingBox().getCenter());
    }

    static void travel(Entity entity, dev.senluo.witchcraft.motion.TravelStep quote, Vec3 center) {
        if (!canObserve(entity)) return;
        TurtleField.RaySource source = new TurtleField.RaySource();
        TurtleField.rayEntry(entity.level(), center, quote.direction().scale(quote.width() + quote.work()),
                ProjectileBarrier.owner(entity), source);
        float strength = (float) Math.clamp(1 - quote.distance() / quote.work(), 0, 1);
        send(entity, source.id, PROPAGATION, center, quote.direction(), strength, .55F);
    }

    static void propagation(Level level, PropagationField.Hit hit, float strength) {
        if (hit != null && level.getEntity(hit.sourceId()) instanceof net.minecraft.world.entity.player.Player wearer)
            send(wearer, hit.sourceId(), PROPAGATION, hit.point(), hit.direction(), strength, .9F);
    }

    private static void send(Entity target, int sourceId, int kind, Vec3 point, Vec3 normal, float strength, float radius) {
        if (!(target.level() instanceof ServerLevel level) || sourceId < 0 || target.isRemoved()) return;
        Contact contact = new Contact(level.dimension().location(), target.getId(), sourceId, kind, point, normal, strength, radius);
        if (!valid(contact)) return;
        long now = level.getGameTime();
        Budget budget = BUDGETS.computeIfAbsent(level, ignored -> new Budget());
        budget.advance(now);
        if (!budget.permits(target.getId(), now) || budget.sources.getOrDefault(sourceId, 0) >= MAX_PER_SOURCE) return;
        budget.lastSent.remove(target.getId());
        budget.lastSent.put(target.getId(), now);
        while (budget.lastSent.size() > MAX_RECENT) budget.lastSent.remove(budget.lastSent.keySet().iterator().next());
        budget.sources.merge(sourceId, 1, Integer::sum);
        budget.events++;
        // Recipients are bounded by visual distance, including a wearer not tracking the distant sonic source.
        PacketDistributor.sendToPlayersNear(level, null, point.x, point.y, point.z, 48, contact);
    }

    private static boolean valid(Contact value) {
        return value.entityId >= 0 && value.sourceId >= 0 && value.kind >= MOB && value.kind <= PROPAGATION
                && finitePosition(value.point) && Double.isFinite(value.normal.lengthSqr())
                && value.normal.lengthSqr() > .9 && value.normal.lengthSqr() < 1.1
                && Float.isFinite(value.strength) && value.strength >= 0 && value.strength <= 1
                && Float.isFinite(value.radius) && value.radius >= .1F && value.radius <= 1.5F;
    }

    private static boolean finitePosition(Vec3 value) {
        return Double.isFinite(value.lengthSqr()) && Math.abs(value.x) <= 6.0E7
                && Math.abs(value.y) <= 6.0E7 && Math.abs(value.z) <= 6.0E7;
    }

    private BarrierVisuals() {}
}
