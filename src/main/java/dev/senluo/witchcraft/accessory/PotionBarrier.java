package dev.senluo.witchcraft.accessory;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/** Splash/cloud adapter. Admission occurs before effects, reapplication stamps and per-use cloud costs. */
public final class PotionBarrier {
    private record Application(Entity source, Vec3 origin, PropagationField field) {}
    private static final ThreadLocal<Application> CURRENT = new ThreadLocal<>();

    public static void apply(Entity source, Runnable original) {
        if (source.level().isClientSide) {
            BarrierVisuals.particles(source.level(), source.position(), original);
            return;
        }
        PropagationField field = TurtleField.propagation(source.level());
        Application previous = CURRENT.get();
        if (field.isEmpty()) {
            if (previous == null) { original.run(); return; }
            CURRENT.remove();
            try { original.run(); } finally { CURRENT.set(previous); }
            return;
        }
        CURRENT.set(new Application(source, source.position(), field));
        try { original.run(); }
        finally { if (previous == null) CURRENT.remove(); else CURRENT.set(previous); }
    }

    public static boolean permits(Entity source, Entity target) {
        return permits(source, target.getBoundingBox().getCenter());
    }

    public static boolean permits(Entity source, Vec3 target) {
        if (source.level().isClientSide) return true;
        Application application = CURRENT.get();
        Vec3 origin = source.position();
        PropagationField field;
        if (application != null && application.source == source) {
            origin = application.origin; field = application.field;
        } else field = TurtleField.propagation(source.level());
        if (field.isEmpty() || !field.blocks(origin, target)) return true;
        if (BarrierVisuals.canObserve(source)) BarrierVisuals.propagation(source.level(), field.hit(origin, target), .55F);
        return false;
    }

    public static <T extends Entity> List<T> filter(Entity source, List<T> candidates) {
        ArrayList<T> kept = null;
        for (int i = 0; i < candidates.size(); i++) {
            T target = candidates.get(i);
            if (!permits(source, target)) {
                if (kept == null) kept = new ArrayList<>(candidates.subList(0, i));
            } else if (kept != null) kept.add(target);
        }
        return kept == null ? candidates : kept;
    }

    private PotionBarrier() {}
}
