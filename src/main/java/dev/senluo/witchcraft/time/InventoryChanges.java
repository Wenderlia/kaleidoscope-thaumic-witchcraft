package dev.senluo.witchcraft.time;

import java.util.IdentityHashMap;
import java.util.Set;
import java.util.Collections;
import java.util.function.Predicate;
import net.minecraft.world.item.ItemStack;

/** One-shot dependency observation. No inventory polling, world lookup or harvesting in callbacks. */
public final class InventoryChanges {
    private InventoryChanges() {}
    private static final ThreadLocal<Observation> READING = new ThreadLocal<>();
    // Only the server thread opens observations; client/worker reads never join its scope.
    private static volatile boolean capturing;

    public interface Source {
        Listeners witchcraft$inventoryChanges(boolean create);
    }

    /** Allocated only for an observed source, not for every world item/list. */
    public static final class Listeners {
        private final Set<Observation> observers = Collections.newSetFromMap(new IdentityHashMap<>());
        public void changed() {
            if (observers.isEmpty()) return;
            // Invalidating one observation detaches it from all its sources, including this one.
            for (Observation observation : observers.toArray(Observation[]::new)) observation.invalidate();
        }
        public void replaced(Object before, Object after) {
            if (observers.isEmpty() || before == after) return;
            for (Observation observation : observers.toArray(Observation[]::new))
                if (before instanceof ItemStack old && observation.relevant.test(old)
                        || after instanceof ItemStack next && observation.relevant.test(next)) observation.invalidate();
        }
    }

    public static void read(Object source) {
        if (!capturing || !(source instanceof Source observed)) return;
        Observation observation = READING.get();
        if (observation != null && !observation.closed && observation.sources.add(observed))
            observed.witchcraft$inventoryChanges(true).observers.add(observation);
    }

    public static void readList(Object list, Object value) {
        // NonNullList is also used during registry bootstrap: do not resolve ItemStack before a real observation.
        if (capturing && value instanceof ItemStack) read(list);
    }

    public static Observation observe(Predicate<ItemStack> relevant, Runnable read) {
        Observation observation = new Observation(relevant);
        Observation previous = READING.get();
        READING.set(observation); capturing = true;
        try { read.run(); return observation; }
        catch (RuntimeException | Error failure) { observation.close(); throw failure; }
        finally {
            if (previous == null) READING.remove(); else READING.set(previous);
            capturing = previous != null;
        }
    }

    public static final class Observation implements AutoCloseable {
        private final Set<Source> sources = Collections.newSetFromMap(new IdentityHashMap<>());
        private final Predicate<ItemStack> relevant;
        private boolean invalidated, closed;
        private Observation(Predicate<ItemStack> relevant) { this.relevant = relevant; }
        public boolean invalidated() { return invalidated; }
        public void invalidate() { invalidated = true; close(); }
        @Override public void close() {
            if (closed) return;
            closed = true;
            for (Source source : sources) source.witchcraft$inventoryChanges(false).observers.remove(this);
            sources.clear();
        }
    }
}
