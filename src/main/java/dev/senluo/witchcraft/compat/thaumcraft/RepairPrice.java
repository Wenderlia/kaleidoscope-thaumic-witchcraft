package dev.senluo.witchcraft.compat.thaumcraft;

import dev.tc4port.thaumcraft.api.aspect.*;
import java.util.HashMap;
import java.util.Map;
import java.util.List;

/** Per-durability CV quote. Only the public, generation-stamped aspect graph is cached. */
final class RepairPrice {
    private long generation = Long.MIN_VALUE;
    // The independent ring service bills these currencies in the saved ledger's original order.
    static final List<AspectId> CURRENCIES = List.of(BuiltinAspects.AER, BuiltinAspects.TERRA,
            BuiltinAspects.IGNIS, BuiltinAspects.AQUA, BuiltinAspects.ORDO, BuiltinAspects.PERDITIO);
    private final Map<AspectId, int[]> primals = new HashMap<>();

    int[] quote(AspectRegistryView registry, AspectAmounts aspects) {
        if (registry.generation() != generation) {
            primals.clear();
            generation = registry.generation();
        }
        int[] totals = new int[CURRENCIES.size()];
        for (var entry : aspects.amounts().entrySet()) {
            var id = entry.getKey();
            if (!primals.containsKey(id)) {
                if (registry.get(id).isEmpty()) return null;
                var leaves = AspectApi.reduceToPrimals(AspectAmounts.of(id, 1));
                int[] unit = CURRENCIES.containsAll(leaves.amounts().keySet()) && !leaves.amounts().isEmpty()
                        ? CURRENCIES.stream().mapToInt(leaves::amount).toArray() : null;
                primals.put(id, unit);
            }
            int[] unit = primals.get(id);
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
}
