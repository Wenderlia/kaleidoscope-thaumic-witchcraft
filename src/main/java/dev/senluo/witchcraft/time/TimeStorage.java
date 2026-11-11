package dev.senluo.witchcraft.time;

import java.util.ArrayList;
import java.util.List;

/** One balance per physical core. Colonies and attached side roots only provide views over them. */
public final class TimeStorage {
    private record Part(Tissue tissue, long capacity, boolean locked) {}
    private final List<Part> parts = new ArrayList<>();
    private final long[] shares;
    private final long capacity, freeCapacity;
    private boolean observing;
    private long totalQuantity, freeQuantity;

    private TimeStorage(List<Tissue> tissues) {
        for (Tissue tissue : tissues) {
            collect(tissue, tissue.coreCapacity(), false);
        }
        shares = new long[parts.size()];
        long total = 0, unlocked = 0;
        for (Part part : parts) { total += part.capacity; if (!part.locked) unlocked += part.capacity; }
        capacity = total; freeCapacity = unlocked;
    }

    public static TimeStorage of(Tissue tissue) { return new TimeStorage(List.of(tissue)); }
    public static TimeStorage of(List<Tissue> tissues) { return new TimeStorage(tissues); }
    static TimeStorage track(List<Tissue> tissues) {
        TimeStorage view = new TimeStorage(tissues);
        for (Part part : view.parts) {
            long balance = part.tissue.coreQuantity();
            view.totalQuantity += balance;
            if (!part.locked) view.freeQuantity += balance;
            part.tissue.watchBalance(view, part.locked);
        }
        view.observing = true;
        return view;
    }
    void close() {
        if (!observing) return;
        for (Part part : parts) part.tissue.unwatchBalance(this);
        observing = false;
    }
    void balanceChanged(long delta, int totalCopies, int unlockedCopies) {
        totalQuantity += delta * totalCopies;
        freeQuantity += delta * unlockedCopies;
    }

    /** Reuse a view only while its physical ownership, locks and capacity still match. Balances stay live. */
    boolean current(List<Tissue> tissues) {
        int next = 0;
        for (Tissue tissue : tissues) {
            next = match(tissue, false, next);
            if (next < 0) return false;
        }
        return next == parts.size();
    }
    private int match(Tissue tissue, boolean locked, int index) {
        if (!tissue.supported()) return index;
        if (index >= parts.size()) return -1;
        Part part = parts.get(index++);
        if (part.tissue != tissue || part.capacity != tissue.coreCapacity() || part.locked != locked) return -1;
        for (Tissue.Side side : tissue.sides) {
            index = match(side.tissue, locked || side.locked, index);
            if (index < 0) return -1;
        }
        return tissue.rootProduction == null ? index
                : match(tissue.rootProduction.output().tissue, locked || tissue.rootProduction.output().locked, index);
    }

    private void collect(Tissue tissue, long coreCapacity, boolean locked) {
        if (!tissue.supported()) return;
        parts.add(new Part(tissue, coreCapacity, locked));
        for (Tissue.Side side : tissue.sides) {
            collect(side.tissue, side.tissue.coreCapacity(), locked || side.locked);
        }
        if (tissue.rootProduction != null) {
            Tissue.Side output = tissue.rootProduction.output();
            collect(output.tissue, output.tissue.coreCapacity(), locked || output.locked);
        }
    }

    public long quantity() {
        if (observing) return totalQuantity;
        long total = 0;
        for (Part part : parts) total += part.tissue.coreQuantity();
        return total;
    }
    public long capacity() { return capacity; }
    public long reserved() {
        if (observing) return totalQuantity - freeQuantity;
        long total = 0;
        for (Part part : parts) if (part.locked) total += part.tissue.coreQuantity();
        return total;
    }
    public long spendable() {
        if (observing) return freeQuantity;
        long total = 0;
        for (Part part : parts) if (!part.locked) total += part.tissue.coreQuantity();
        return total;
    }
    public long freeCapacity() { return freeCapacity; }
    public long room() { return Math.max(0, freeCapacity() - spendable()); }

    public boolean credit(long amount) {
        if (amount < 0) return false;
        long available = spendable();
        if (Math.max(0, freeCapacity - available) < amount) return false;
        distribute(available + amount);
        return true;
    }

    public boolean spend(long amount) {
        if (amount == 0) return true;
        long available = spendable();
        if (amount < 0 || available < amount) return false;
        distribute(available - amount);
        return true;
    }

    public boolean equalize() {
        return distribute(spendable());
    }

    private boolean distribute(long available) {
        long capacity = freeCapacity();
        long left = available;
        for (int i = 0; i < parts.size(); i++) {
            Part part = parts.get(i);
            if (!part.locked) {
                shares[i] = Tissue.proportional(part.capacity, available, capacity);
                left -= shares[i];
            }
        }
        Part first = null;
        boolean changed = false;
        for (int i = 0; i < parts.size(); i++) {
            Part part = parts.get(i);
            if (part.locked) continue;
            if (first == null) first = part;
            long share = shares[i];
            long extra = Math.min(left, Math.max(0, part.capacity - share));
            left -= extra;
            long finalShare = share + extra;
            if (part == first && available > capacity) {
                // Keep the existing overflow policy on the first unlocked part.
                long surplus = Math.min(left, available - capacity);
                finalShare += surplus;
                left -= surplus;
            }
            if (part.tissue.coreQuantity() != finalShare) {
                part.tissue.setCoreQuantity(finalShare);
                changed = true;
            }
        }
        return changed;
    }
}
