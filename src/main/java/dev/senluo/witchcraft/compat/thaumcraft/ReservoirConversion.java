package dev.senluo.witchcraft.compat.thaumcraft;

import dev.tc4port.thaumcraft.api.aspect.*;
import java.math.BigInteger;
import java.util.*;

/** Exact graph quotes; fixed-point rounding occurs only after the full decomposition. */
final class ReservoirConversion {
    private long generation=Long.MIN_VALUE;
    private int base,retention;
    private final Map<AspectId,Optional<long[]>> quotes=new HashMap<>();
    Optional<long[]> quote(AspectId id,int b,int r) {
        var registry=AspectApi.registry();
        if(generation!=registry.generation() || base!=b || retention!=r){
            generation=registry.generation();base=b;retention=r;quotes.clear();
        }
        return quotes.computeIfAbsent(id,key->{
            try {
                Fraction[] value=resolve(key,new HashSet<>(),new HashMap<>(),0,new int[]{0});
                long[] result=new long[6];
                for(int i=0;i<6;i++)result[i]=value[i].n.multiply(BigInteger.valueOf(base)).divide(value[i].d).longValueExact();
                return Optional.of(result);
            } catch(InvalidDefinition ex) {
                com.mojang.logging.LogUtils.getLogger().warn("Reservoir cannot convert {} at generation {}: {}",key,generation,ex.getMessage());
                return Optional.empty();
            }
        });
    }
    private Fraction[] resolve(AspectId id,Set<AspectId> path,Map<AspectId,Fraction[]> done,int depth,int[] visited) throws InvalidDefinition {
        if(depth>16)throw new InvalidDefinition("depth exceeds 16");
        if(done.containsKey(id))return done.get(id);
        if(!path.add(id))throw new InvalidDefinition("cyclic components");
        if(++visited[0]>256)throw new InvalidDefinition("graph exceeds 256 nodes");
        var definition=AspectApi.registry().get(id).orElseThrow(()->new InvalidDefinition("missing definition "+id));
        Fraction[] value=new Fraction[6];Arrays.fill(value,Fraction.ZERO);
        if(definition.visChannel().isPresent()) value[definition.visChannel().get().index()]=Fraction.ONE;
        else {
            var parts=definition.components();
            if(parts.size()!=2)throw new InvalidDefinition("unmapped terminal "+id);
            Fraction[] a=resolve(parts.get(0),path,done,depth+1,visited),b=resolve(parts.get(1),path,done,depth+1,visited);
            for(int i=0;i<6;i++)value[i]=a[i].add(b[i]).multiply(retention,20_000);
        }
        path.remove(id);done.put(id,value);return value;
    }
    private record Fraction(BigInteger n,BigInteger d) {
        static final Fraction ZERO=new Fraction(BigInteger.ZERO,BigInteger.ONE),ONE=new Fraction(BigInteger.ONE,BigInteger.ONE);
        Fraction {var gcd=n.gcd(d);n=n.divide(gcd);d=d.divide(gcd);}
        Fraction add(Fraction other){return new Fraction(n.multiply(other.d).add(other.n.multiply(d)),d.multiply(other.d));}
        Fraction multiply(int numerator,int denominator){return new Fraction(n.multiply(BigInteger.valueOf(numerator)),d.multiply(BigInteger.valueOf(denominator)));}
    }
    private static final class InvalidDefinition extends Exception {InvalidDefinition(String message){super(message);}}
}
