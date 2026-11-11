package dev.senluo.witchcraft.client;

import dev.senluo.witchcraft.KaleidoscopeWitchcraft;
import dev.senluo.witchcraft.accessory.BarrierVisuals;
import dev.senluo.witchcraft.accessory.MotionConstraints;
import dev.senluo.witchcraft.accessory.PropagationField;
import dev.senluo.witchcraft.accessory.TurtleField;
import dev.senluo.witchcraft.mixin.accessory.client.ParticleAlphaAccess;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent;

/** Source-attributed cosmetics only. Native simulation, sprites and render types remain in charge. */
@EventBusSubscriber(modid = KaleidoscopeWitchcraft.MOD_ID, value = Dist.CLIENT)
public final class PropagationParticles {
    private static final int MAX_TRACKED = 512, MAX_ADMITTED = 128, TTL = 200, FADE_TICKS = 12;
    private static final double APPROACH = 1.25;
    private record Emission(ClientLevel level, Vec3 origin) {}
    private static final class Trace {
        final Emission emission;
        final long born;
        int slowed;
        Trace(Emission emission, long born) { this.emission = emission; this.born = born; }
    }
    private static final Map<Particle, Trace> TRACKED = new IdentityHashMap<>();
    private static Emission emission;
    private static ClientLevel level;
    private static long frame = Long.MIN_VALUE;
    private static int admitted;
    private static PropagationField field = PropagationField.EMPTY;

    @EventBusSubscriber(modid = KaleidoscopeWitchcraft.MOD_ID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
    public static final class Registration {
        @SubscribeEvent public static void setup(FMLClientSetupEvent event) {
            event.enqueueWork(() -> BarrierVisuals.installParticles(PropagationParticles::emit));
        }
        @SubscribeEvent public static void reload(RegisterClientReloadListenersEvent event) {
            event.registerReloadListener((ResourceManagerReloadListener) resources -> clear());
        }
        private Registration() {}
    }

    private static void refresh() {
        ClientLevel current = Minecraft.getInstance().level;
        if (level != current) { TRACKED.clear(); frame = Long.MIN_VALUE; level = current; }
        if (current == null) { field = PropagationField.EMPTY; return; }
        long now = current.getGameTime();
        if (frame == now) return;
        frame = now; admitted = 0;
        // One per client tick, over cached wearers only. No particle/world enumeration to find fields.
        field = TurtleField.propagation(current);
        TRACKED.entrySet().removeIf(entry -> !entry.getKey().isAlive() || now < entry.getValue().born);
    }

    public static void emit(Level world, Vec3 origin, Runnable original) {
        refresh();
        Emission previous = emission;
        emission = world == level && !field.isEmpty() && Double.isFinite(origin.lengthSqr())
                ? new Emission(level, origin) : null;
        try { original.run(); }
        finally { emission = previous; }
    }

    public static boolean capturing() { return emission != null; }
    public static boolean tracks(Particle particle) { return !TRACKED.isEmpty() && TRACKED.containsKey(particle); }

    /** Called only at particle creation; ambient particles see a null scope and are unchanged. */
    public static Particle create(Vec3 position, Supplier<Particle> original) {
        Emission current = emission;
        if (current == null) return original.get();
        refresh();
        if (current.level != level || field.isEmpty()) return original.get();
        // Emitters can spawn a child directly behind the barrier instead of moving through it.
        if (field.blocks(current.origin, position) || TRACKED.size() >= MAX_TRACKED || admitted >= MAX_ADMITTED) return null;
        Particle particle = original.get();
        if (particle != null) { TRACKED.put(particle, new Trace(current, frame)); admitted++; }
        return particle;
    }

    public static void tick(Particle particle, Runnable original) {
        Trace trace = TRACKED.get(particle);
        if (trace == null) { original.run(); return; }
        refresh();
        if (trace.emission.level != level || frame - trace.born > TTL) {
            particle.remove(); TRACKED.remove(particle); return;
        }
        if (field.isEmpty()) { TRACKED.remove(particle); original.run(); return; }
        Vec3 start = particle.getPos();
        Emission previous = emission;
        emission = trace.emission;
        try { original.run(); }
        finally { emission = previous; }
        if (!particle.isAlive()) { TRACKED.remove(particle); return; }
        Vec3 delta = particle.getPos().subtract(start);
        double length = delta.length();
        if (!Double.isFinite(length)) { particle.remove(); TRACKED.remove(particle); return; }
        if (length > 1.0E-8) {
            PropagationField.Hit hit = field.hit(start, start.add(delta.scale((length + APPROACH) / length)));
            if (hit != null) {
                double gap = start.distanceTo(hit.point());
                double travel = Math.min(length, MotionConstraints.mapTravel(gap, length, APPROACH));
                if (travel < length * .995) {
                    Vec3 limited = start.add(delta.scale(travel / length));
                    particle.setPos(limited.x, limited.y, limited.z);
                    fade(particle, trace);
                }
            }
        } else if (field.blocks(trace.emission.origin, start)) fade(particle, trace);
    }

    private static void fade(Particle particle, Trace trace) {
        if (++trace.slowed >= FADE_TICKS) { particle.remove(); TRACKED.remove(particle); return; }
        ParticleAlphaAccess alpha = (ParticleAlphaAccess)particle;
        alpha.witchcraft$alpha(alpha.witchcraft$alpha() * .85F);
    }

    @SubscribeEvent public static void tick(ClientTickEvent.Post event) {
        refresh();
        TRACKED.entrySet().removeIf(entry -> {
            if (frame - entry.getValue().born <= TTL) return !entry.getKey().isAlive();
            entry.getKey().remove(); return true;
        });
    }

    @SubscribeEvent public static void logout(ClientPlayerNetworkEvent.LoggingOut event) {
        clear();
    }

    private static void clear() {
        TRACKED.clear(); level = null; emission = null; field = PropagationField.EMPTY; frame = Long.MIN_VALUE;
    }

    private PropagationParticles() {}
}
