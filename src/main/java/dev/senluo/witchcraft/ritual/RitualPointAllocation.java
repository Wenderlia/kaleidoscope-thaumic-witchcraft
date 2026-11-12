package dev.senluo.witchcraft.ritual;

import java.util.*;

/** Integral shared capacity. A request receives its whole cost or no capacity. No world access. */
public final class RitualPointAllocation {
    public record Request(UUID id, int cost, long origin, long order, List<Long> pools, boolean funded) {
        public Request {
            Objects.requireNonNull(id);
            if (cost < 1 || cost > 3) throw new IllegalArgumentException("Invalid ritual cost");
            pools = List.copyOf(new LinkedHashSet<>(pools));
        }
    }
    public record Loss(long pool, int points) {}
    public record Result(Map<UUID, Map<Long, Integer>> grants, Set<UUID> paused) {}
    public record Decision(Map<UUID, Map<Long, Integer>> reservations, Set<UUID> pending) {}
    private record Attempt(Map<UUID, Map<Long, Integer>> grants, Set<UUID> blocked) {}

    /** Retention and execution use the same policy, with different evidence bounds. */
    public static Decision observe(Map<Long, Integer> confirmed, Map<Long, Integer> possible,
                                   List<Request> requests, List<Loss> losses) {
        var retained = allocate(possible, requests, losses).grants();
        if (confirmed.equals(possible)) return new Decision(retained, Set.of());
        // Unknown evidence is not a loss event. Stable waiting order must survive cold rebuilds.
        // All retained receipts own reservations now, including those restored in this observation.
        var runnable = allocate(confirmed, requests.stream().filter(r -> retained.containsKey(r.id()))
                .map(r -> new Request(r.id, r.cost, r.origin, r.order, r.pools, true)).toList(), List.of()).grants();
        var pending = new HashSet<>(retained.keySet());
        pending.removeAll(runnable.keySet());
        return new Decision(retained, Set.copyOf(pending));
    }

    public static Result allocate(Map<Long, Integer> capacities, List<Request> requests, List<Loss> losses) {
        var ordered = requests.stream().sorted(Comparator.comparingLong(Request::order).thenComparing(Request::id)).toList();
        var admitted = new ArrayList<>(ordered.stream().filter(Request::funded).toList());
        var remainingLosses = new ArrayDeque<>(losses);
        Attempt attempt = fit(capacities, admitted);
        while (!attempt.blocked.isEmpty()) {
            Set<UUID> blocked = attempt.blocked;
            Loss loss = remainingLosses.stream().filter(l -> admitted.stream()
                    .anyMatch(r -> blocked.contains(r.id) && r.pools.contains(l.pool))).findFirst().orElse(null);
            if (loss != null) remainingLosses.remove(loss);
            Comparator<Request> priority = Comparator
                    .comparingInt((Request r) -> loss != null && r.cost == loss.points ? 0 : 1)
                    .thenComparingInt(r -> loss != null && r.origin == loss.pool ? 0 : 1)
                    .thenComparingInt(Request::cost)
                    .thenComparing(Comparator.comparingLong(Request::order).reversed())
                    .thenComparing(Request::id);
            Request victim = admitted.stream().filter(r -> blocked.contains(r.id)).min(priority).orElseThrow();
            admitted.remove(victim);
            attempt = fit(capacities, admitted);
        }
        // Restore waiting receipts in durable order; they do not evict an already admitted receipt.
        var paused = new LinkedHashSet<UUID>();
        for (Request request : ordered) {
            if (admitted.contains(request)) continue;
            // A receipt suspended by this loss must not immediately displace its priority decision.
            if (request.funded) { paused.add(request.id); continue; }
            admitted.add(request);
            Attempt trial = fit(capacities, admitted);
            if (trial.blocked.isEmpty()) attempt = trial;
            else { admitted.removeLast(); paused.add(request.id); }
        }
        return new Result(attempt.grants, Set.copyOf(paused));
    }

    /** Unit-capacity augmenting paths can reroute other requests without dropping their paid units. */
    private static Attempt fit(Map<Long, Integer> capacities, List<Request> requests) {
        var byId = new LinkedHashMap<UUID, Request>();
        for (Request r : requests) if (byId.put(r.id, r) != null) throw new IllegalArgumentException("Duplicate ritual");
        var slots = new LinkedHashMap<Long, ArrayList<UUID>>();
        capacities.forEach((pool, capacity) -> {
            if (capacity < 0) throw new IllegalArgumentException("Negative pool capacity");
            slots.put(pool, new ArrayList<>());
        });
        for (Request request : requests) for (int unit = 0; unit < request.cost; unit++) {
            var queue = new ArrayDeque<UUID>();
            var seen = new LinkedHashSet<UUID>();
            var parents = new HashMap<UUID, Long>();
            var via = new LinkedHashMap<Long, UUID>();
            queue.add(request.id); seen.add(request.id);
            Long free = null;
            while (!queue.isEmpty() && free == null) {
                UUID current = queue.removeFirst();
                for (long pool : byId.get(current).pools) {
                    if (via.putIfAbsent(pool, current) != null) continue;
                    var occupants = slots.get(pool);
                    if (occupants == null) continue;
                    if (occupants.size() < capacities.get(pool)) { free = pool; break; }
                    for (UUID occupant : occupants) if (seen.add(occupant)) {
                        parents.put(occupant, pool); queue.addLast(occupant);
                    }
                }
            }
            if (free == null) return new Attempt(Map.of(), Set.copyOf(seen));
            long pool = free;
            while (true) {
                UUID owner = via.get(pool);
                slots.get(pool).add(owner);
                if (owner.equals(request.id)) break;
                long previous = parents.get(owner);
                slots.get(previous).remove(owner);
                pool = previous;
            }
        }
        var grants = new LinkedHashMap<UUID, Map<Long, Integer>>();
        for (Request r : requests) grants.put(r.id, new LinkedHashMap<>());
        slots.forEach((pool, occupants) -> occupants.forEach(id -> grants.get(id).merge(pool, 1, Integer::sum)));
        grants.replaceAll((id, pools) -> Map.copyOf(pools));
        return new Attempt(Map.copyOf(grants), Set.of());
    }
    private RitualPointAllocation() {}
}
