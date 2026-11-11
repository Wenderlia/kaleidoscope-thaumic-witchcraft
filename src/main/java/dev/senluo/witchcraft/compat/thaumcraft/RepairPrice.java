package dev.senluo.witchcraft.compat.thaumcraft;

import dev.tc4port.thaumcraft.api.aspect.*;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Per-durability CV quote. Only the public, generation-stamped aspect graph is cached. */
final class RepairPrice {
    private long generation = Long.MIN_VALUE;
    private final Map<AspectId, int[]> primals = new HashMap<>();

    int[] quote(AspectRegistryView registry, AspectAmounts aspects) {
        if (registry.generation() != generation) {
            primals.clear();
            generation = registry.generation();
        }
        int[] totals = new int[6];
        for (var entry : aspects.amounts().entrySet()) {
            int[] unit = primals.containsKey(entry.getKey()) ? primals.get(entry.getKey())
                    : expand(registry, entry.getKey(), new HashSet<>());
            // Unknown or non-Vis primals cannot silently become a free repair.
            if (unit == null) return null;
            for (int i = 0; i < totals.length; i++)
                totals[i] = (int) Math.min(Integer.MAX_VALUE, totals[i] + (long) unit[i] * entry.getValue());
        }
        boolean priced = false;
        for (int i = 0; i < totals.length; i++) {
            totals[i] = (int) Math.sqrt(2.0 * totals[i]);
            priced |= totals[i] > 0;
        }
        return priced ? totals : null;
    }

    private int[] expand(AspectRegistryView registry, AspectId id, Set<AspectId> visiting) {
        if (primals.containsKey(id)) return primals.get(id);
        var definition = registry.get(id).orElse(null);
        if (definition == null || !visiting.add(id)) return null;
        int[] result = new int[6];
        if (definition.primal()) {
            var channel = definition.visChannel().orElse(null);
            if (channel == null) result = null;
            else result[channel.index()] = 1;
        } else {
            for (var child : definition.components()) {
                int[] expanded = expand(registry, child, visiting);
                if (expanded == null) { result = null; break; }
                for (int i = 0; i < result.length; i++)
                    result[i] = (int) Math.min(Integer.MAX_VALUE, (long) result[i] + expanded[i]);
            }
        }
        visiting.remove(id);
        primals.put(id, result);
        return result;
    }
}
