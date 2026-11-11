package dev.senluo.witchcraft.reservoir;

import net.minecraft.world.item.ItemStack;

/** Persistent per-device byproduct accounting, independent of the world placement API. */
public final class ReservoirEmission {
    private ReservoirEmission(){}
    public static void charged(ItemStack device,int every){
        ReservoirData.edit(device,d->{
            long cycles=Math.max(0,d.getLong("charge_cycles"));
            cycles=Math.min(cycles,Long.MAX_VALUE-1)+1;
            long pending=Math.max(0,d.getLong("pending_gas")),added=cycles/every;
            d.putLong("charge_cycles",cycles%every);
            d.putLong("pending_gas",pending>Long.MAX_VALUE-added?Long.MAX_VALUE:pending+added);
        });
    }
    public static boolean pending(ItemStack device){return ReservoirData.read(device).getLong("pending_gas")>0;}
    public static void reserve(ItemStack device){ReservoirData.edit(device,d->d.putLong("pending_gas",Math.max(0,d.getLong("pending_gas")-1)));}
    public static void refund(ItemStack device){ReservoirData.edit(device,d->{long n=Math.max(0,d.getLong("pending_gas"));d.putLong("pending_gas",n==Long.MAX_VALUE?n:n+1);});}
}
