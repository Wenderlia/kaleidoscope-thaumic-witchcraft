package dev.senluo.witchcraft.compat.thaumcraft;

import java.util.*;
import net.minecraft.resources.ResourceLocation;

/** A single inventory delivery plan. Only proposals on these edges are transferable. */
final class ReservoirAllocation {
    record Split(long share, long remainder) {
        static Split of(Collection<Long> limits, long budget) {
            long left = budget;
            for (long limit : limits) {
                if (limit > left) { left = -1; break; }
                left -= limit;
            }
            if (left >= 0) return new Split(Long.MAX_VALUE, 0);
            int open = limits.size();
            for (long limit : limits.stream().mapToLong(Long::longValue).sorted().toArray()) {
                if (limit > budget / open) break;
                budget -= limit;
                open--;
            }
            return new Split(budget / open, budget % open);
        }
    }

    static final class Share {
        final Map<ResourceLocation, Integer> room;
        final Map<ResourceLocation, Integer> request = new LinkedHashMap<>();
        final List<ResourceLocation> order;
        long total;
        int next;
        ResourceLocation last, tail;

        Share(Map<ResourceLocation, Integer> room, long alreadyReceived, ResourceLocation after) {
            this.room = room;
            this.total = alreadyReceived;
            this.order = new ArrayList<>(room.keySet());
            int index = after == null ? -1 : order.indexOf(after);
            if (index >= 0) Collections.rotate(order, -index - 1);
        }

        private int grant(ResourceLocation id) { return request.getOrDefault(id, 0); }
        private int space(ResourceLocation id) { return room.getOrDefault(id, 0) - grant(id); }

        private void move(ResourceLocation id, int amount) {
            int value = Math.addExact(grant(id), amount);
            if (value == 0) request.remove(id);
            else request.put(id, value);
            total += amount;
        }

        private long offer(Map<ResourceLocation, Long> available, long quota) {
            var limits = new LinkedHashMap<ResourceLocation, Long>();
            for (int n = 0; n < order.size(); n++) {
                var id = order.get((next + n) % order.size());
                long limit = Math.min(available.getOrDefault(id, 0L), space(id));
                if (limit > 0) limits.put(id, limit);
            }
            if (limits.isEmpty()) return 0;
            var split = Split.of(limits.values(), quota);
            long remainder = split.remainder, granted = 0;
            ResourceLocation extra = null;
            for (var entry : limits.entrySet()) {
                var id = entry.getKey();
                long amount = Math.min(entry.getValue(), split.share);
                if (entry.getValue() > split.share && remainder > 0) {
                    amount++;
                    remainder--;
                    extra = id;
                }
                if (amount == 0) continue;
                move(id, Math.toIntExact(amount));
                available.put(id, available.get(id) - amount);
                granted += amount;
                last = id;
            }
            if (extra != null) last = extra;
            next = (order.indexOf(last) + 1) % order.size();
            return granted;
        }
    }

    /** Stock is expressed in centivis; the caller owns fractional balances. */
    static void plan(List<Share> shares, Map<ResourceLocation, Long> stock) {
        var available = new HashMap<ResourceLocation, Long>();
        for (var target : shares) target.room.forEach((id, limit) -> {
            long balance = stock.getOrDefault(id, 0L);
            if (limit > 0 && balance > 0)
                available.compute(id, (key, sum) -> Math.min(balance, (sum == null ? 0 : sum) + limit));
        });
        if (available.isEmpty()) return;
        var queue = new ArrayDeque<>(shares);
        long left = available.values().stream().mapToLong(Long::longValue).sum();
        while (left > 0 && !queue.isEmpty()) {
            int count = queue.size();
            long share = Math.max(1, left / count);
            for (int i = 0; i < count && left > 0; i++) {
                var target = queue.removeFirst();
                long grant = target.offer(available, Math.min(left, share));
                left -= grant;
                if (grant == share) queue.addLast(target);
            }
        }
        if (shares.size() > 1) balance(shares);
    }

    /** Transfer proposals along alternating paths until no feasible peer can improve its share.
     * A one-unit tie belongs to the earlier slot in the caller's rotated order. */
    private static void balance(List<Share> shares) {
        while (true) {
            long low = Long.MAX_VALUE, high = 0;
            boolean tieInOrder = true;
            long previous = Long.MAX_VALUE;
            for (var share : shares) {
                share.tail = null;
                low = Math.min(low, share.total);
                high = Math.max(high, share.total);
                if (share.total > previous) tieInOrder = false;
                previous = share.total;
            }
            if (high == low) return;
            boolean alreadyFair = high - low == 1 && tieInOrder;
            boolean moved = false;
            for (int i = 0; i < shares.size(); i++) {
                if (shares.get(i).total >= high) continue;
                if (alreadyFair && !unmarkedRemainder(shares, high)) break;
                if (rebalanceTo(shares, i)) { moved = true; break; }
            }
            if (!moved) return;
        }
    }

    private static boolean unmarkedRemainder(List<Share> shares, long high) {
        for (var share : shares)
            if (share.total == high && share.tail == null && !share.request.isEmpty()) return true;
        return false;
    }

    private static boolean rebalanceTo(List<Share> shares, int recipient) {
        int count = shares.size();
        int[] parent = new int[count], queue = new int[count];
        Arrays.fill(parent, -1);
        var lane = new ResourceLocation[count];
        var explored = new HashSet<ResourceLocation>();
        int tail = 1, donor = -1;
        queue[0] = recipient;
        parent[recipient] = recipient;
        long low = shares.get(recipient).total;
        for (int head = 0; head < tail; head++) {
            int to = queue[head];
            var target = shares.get(to);
            for (var id : target.order) {
                if (target.space(id) <= 0 || !explored.add(id)) continue;
                for (int from = 0; from < count; from++) {
                    var source = shares.get(from);
                    if (parent[from] >= 0 || source.grant(id) == 0) continue;
                    parent[from] = to;
                    lane[from] = id;
                    queue[tail++] = from;
                    long gap = source.total - low;
                    // This last unit could go to the later slot on a different rotation.
                    if (gap == 1 && from < recipient && source.tail == null) source.tail = id;
                    if ((gap >= 2 || gap == 1 && from > recipient)
                            && (donor < 0 || source.total > shares.get(donor).total)) donor = from;
                }
            }
        }
        if (donor < 0) return false;
        long amount = Math.max(1, (shares.get(donor).total - low) / 2);
        for (int from = donor; from != recipient; from = parent[from]) {
            var id = lane[from];
            amount = Math.min(amount, Math.min(shares.get(from).grant(id), shares.get(parent[from]).space(id)));
        }
        for (int from = donor; from != recipient; from = parent[from]) {
            var id = lane[from];
            shares.get(from).move(id, -(int) amount);
            shares.get(parent[from]).move(id, (int) amount);
        }
        return true;
    }

    private ReservoirAllocation() {}
}
