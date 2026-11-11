package dev.senluo.witchcraft.reservoir;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

/** Host specification shared by storage, menus, attachments and rendering; never stored in NBT. */
public enum ReservoirProfile {
    STANDARD(3,3,1,100,"backpack"),
    SILVERWOOD(6,6,2,50,"silverwood_backpack");

    public final int cells,plugins,conversionMultiplier,lossPercent;
    public final String model;
    ReservoirProfile(int cells,int plugins,int conversionMultiplier,int lossPercent,String model){
        this.cells=cells;this.plugins=plugins;this.conversionMultiplier=conversionMultiplier;
        this.lossPercent=lossPercent;this.model=model;
    }
    public int storageSlots(){return ReservoirData.FIRST_PLUGIN_SLOT+plugins;}
    public int chamberCapacity(){
        return switch(this){
            case STANDARD -> ReservoirConfig.CAPACITY.get();
            case SILVERWOOD -> ReservoirConfig.SILVERWOOD_CAPACITY.get();
        };
    }
    public static ReservoirProfile of(ItemStack stack){
        return stack.getItem() instanceof ReservoirItem item?item.profile():STANDARD;
    }
    /** The placed host is known before its inventory packet arrives. */
    public static ReservoirProfile of(BlockState state){
        return state.getBlock().asItem() instanceof ReservoirItem item?item.profile():STANDARD;
    }
    /** Fixed point retains odd configured loss percentages when the host halves them. */
    public record Conversion(int primalMicrovis,int retainedBasisPoints,int ventEvery,boolean emitsGas) {}
    public Conversion conversion(ReservoirTransducerItem.Rules device){
        int yield=Math.multiplyExact(device.primalMicrovis(),conversionMultiplier);
        int retention=10_000-(100-device.retainedPercent())*lossPercent;
        return new Conversion(yield,retention,device.ventEvery(),yield<4_000_000);
    }
}
