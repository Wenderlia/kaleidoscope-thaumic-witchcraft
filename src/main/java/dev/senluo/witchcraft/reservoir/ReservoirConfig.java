package dev.senluo.witchcraft.reservoir;

import net.neoforged.neoforge.common.ModConfigSpec;

public final class ReservoirConfig {
    public static final ModConfigSpec SPEC;
    public static final ModConfigSpec.IntValue CAPACITY, SILVERWOOD_CAPACITY, SOURCE_PERIOD, OUTPUT_PERIOD, BUFFER_VIS, PRIMAL_MICROVIS, COMPOUND_PERCENT;
    public static final ModConfigSpec.IntValue VOID_PRIMAL_MICROVIS, VOID_COMPOUND_PERCENT, VENT_EVERY, VOID_VENT_EVERY;
    static {
        var b=new ModConfigSpec.Builder();
        b.push("alchemy_backpack");
        CAPACITY=b.defineInRange("chamber_capacity",192,1,1_000_000);
        SILVERWOOD_CAPACITY=b.defineInRange("silverwood_chamber_capacity",256,1,1_000_000);
        SOURCE_PERIOD=b.defineInRange("conversion_ticks",20,1,1200);
        OUTPUT_PERIOD=b.defineInRange("delivery_ticks",5,1,1200);
        BUFFER_VIS=b.defineInRange("converter_buffer_vis",512,1,1_000_000);
        PRIMAL_MICROVIS=b.defineInRange("primal_microvis",1_000_000,1,100_000_000);
        COMPOUND_PERCENT=b.defineInRange("compound_retained_percent",80,1,100);
        VOID_PRIMAL_MICROVIS=b.defineInRange("void_primal_microvis",2_000_000,1,100_000_000);
        VOID_COMPOUND_PERCENT=b.defineInRange("void_compound_retained_percent",100,1,100);
        VENT_EVERY=b.defineInRange("gas_every_charges",20,1,1_000_000);
        VOID_VENT_EVERY=b.defineInRange("void_gas_every_charges",40,1,1_000_000);
        b.pop(); SPEC=b.build();
    }
    private ReservoirConfig(){}
}
