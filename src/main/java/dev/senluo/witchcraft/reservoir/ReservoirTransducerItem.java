package dev.senluo.witchcraft.reservoir;

/** Conversion grade belongs to the device; inventory, scheduling and targets remain shared. */
public final class ReservoirTransducerItem extends ReservoirPluginItem {
    public enum Grade { STANDARD, VOID }
    public record Rules(int primalMicrovis,int retainedPercent,int ventEvery) {}
    /** Per device: conversion clock, retry clock and total six-channel overflow capacity. */
    public record Throughput(int sourceTicks,int deliveryTicks,long bufferMicrovis) {}
    private final Grade grade;
    public ReservoirTransducerItem(Properties properties,Grade grade,Handler handler){super(properties,handler);this.grade=grade;}
    public Grade grade(){return grade;}
    public String material(){return grade==Grade.VOID?"void_converter":"converter";}
    public Throughput throughput(){
        return new Throughput(ReservoirConfig.SOURCE_PERIOD.get(),ReservoirConfig.OUTPUT_PERIOD.get(),
                ReservoirConfig.BUFFER_VIS.get()*1_000_000L);
    }
    public Rules rules(){
        return grade==Grade.VOID
                ?new Rules(ReservoirConfig.VOID_PRIMAL_MICROVIS.get(),ReservoirConfig.VOID_COMPOUND_PERCENT.get(),ReservoirConfig.VOID_VENT_EVERY.get())
                :new Rules(ReservoirConfig.PRIMAL_MICROVIS.get(),ReservoirConfig.COMPOUND_PERCENT.get(),ReservoirConfig.VENT_EVERY.get());
    }
}
