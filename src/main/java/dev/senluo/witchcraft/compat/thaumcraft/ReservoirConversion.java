package dev.senluo.witchcraft.compat.thaumcraft;

import dev.tc4port.thaumcraft.api.aspect.*;
import java.math.BigInteger;
import net.minecraft.resources.ResourceLocation;
import java.util.*;

/** Exact graph quotes; fixed-point rounding occurs only after the full decomposition. */
final class ReservoirConversion {
    private long generation = Long.MIN_VALUE;
    private int base, retention;
    private Set<AspectId> terminals = Set.of();
    private final Map<AspectId, Optional<Map<ResourceLocation, Long>>> quotes = new HashMap<>();

    void configure(List<AspectId> types, int base, int retention) {
        if (this.base == base && this.retention == retention && terminals.size() == types.size()
                && terminals.containsAll(types)) return;
        this.base = base;
        this.retention = retention;
        terminals = Set.copyOf(types);
        quotes.clear();
    }

    Optional<Map<ResourceLocation, Long>> quote(AspectRegistryView registry, AspectId id) {
        if (generation != registry.generation()) {
            generation = registry.generation();
            quotes.clear();
        }
        return quotes.computeIfAbsent(id, key -> {
            try {
                var resolved = resolve(registry, key, new HashMap<>());
                var result = new LinkedHashMap<ResourceLocation, Long>();
                resolved.fractions.forEach((aspect, value) -> {
                    long amount = value.n.multiply(BigInteger.valueOf(base)).divide(value.d).longValueExact();
                    if (amount > 0) result.put(aspect.value(), amount);
                });
                return Optional.of(Map.copyOf(result));
            } catch (InvalidDefinition ex) {
                com.mojang.logging.LogUtils.getLogger().warn("Reservoir cannot convert {} at generation {}: {}", key, registry.generation(), ex.getMessage());
                return Optional.empty();
            }
        });
    }

    private record Decomposition(Map<AspectId, Fraction> fractions, int height) {}

    private Decomposition resolve(AspectRegistryView registry, AspectId id, Map<AspectId, Decomposition> resolved) throws InvalidDefinition {
        var cached = resolved.get(id);
        if (cached != null) return cached;
        var definition = registry.get(id).orElseThrow(() -> new InvalidDefinition("missing definition " + id));
        Decomposition value;
        if (terminals.contains(id)) value = new Decomposition(Map.of(id, Fraction.ONE), 0);
        else if (definition.primal()) value = new Decomposition(Map.of(), 0);
        else {
            // The installed TC registry has already validated the binary, acyclic graph.
            var left = resolve(registry, definition.components().get(0), resolved);
            var right = resolve(registry, definition.components().get(1), resolved);
            int height = 1 + Math.max(left.height, right.height);
            if (height > 16) throw new InvalidDefinition("depth exceeds 16");
            var fractions = new HashMap<>(left.fractions);
            right.fractions.forEach((aspect, fraction) -> fractions.merge(aspect, fraction, Fraction::add));
            fractions.replaceAll((aspect, fraction) -> fraction.multiply(retention, 20_000));
            value = new Decomposition(fractions, height);
        }
        if (resolved.size() >= 256) throw new InvalidDefinition("graph exceeds 256 nodes");
        resolved.put(id, value);
        return value;
    }

    private record Fraction(BigInteger n,BigInteger d) {
        static final Fraction ONE=new Fraction(BigInteger.ONE,BigInteger.ONE);
        Fraction {var gcd=n.gcd(d);n=n.divide(gcd);d=d.divide(gcd);}
        Fraction add(Fraction other){return new Fraction(n.multiply(other.d).add(other.n.multiply(d)),d.multiply(other.d));}
        Fraction multiply(int numerator,int denominator){return new Fraction(n.multiply(BigInteger.valueOf(numerator)),d.multiply(BigInteger.valueOf(denominator)));}
    }
    private static final class InvalidDefinition extends Exception {InvalidDefinition(String message){super(message);}}
}
