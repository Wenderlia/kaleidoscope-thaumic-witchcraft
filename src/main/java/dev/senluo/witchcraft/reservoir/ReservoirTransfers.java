package dev.senluo.witchcraft.reservoir;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/** Single-carrier transactions with identical local stacks and an explicit drained-input latch. */
public final class ReservoirTransfers {
    public enum Result {
        NONE, CONTAINER_ONLY, ESSENTIA;
        public boolean changed(){return this!=NONE;}
        public boolean movedEssentia(){return this==ESSENTIA;}
    }
    private ReservoirTransfers(){}
    public static boolean processInput(Level level,ItemStack bag){
        return processInputResult(level,bag).changed();
    }
    /** Distinguish real fluid commits from a previously drained carrier merely leaving the input. */
    public static Result processInputResult(Level level,ItemStack bag){
        if(level.isClientSide || !ReservoirData.supported(bag))return Result.NONE;
        var registries=level.registryAccess();
        ItemStack input=ReservoirData.slot(bag,0,registries),output=ReservoirData.slot(bag,1,registries);
        if(input.isEmpty() || input.getCount()>ReservoirData.PORT_LIMIT || !ReservoirContent.bridge.container(input))return Result.NONE;
        if(ReservoirData.read(bag).getBoolean("input_drained")){
            // Empty jars kept during a blockage must leave, not immediately refill themselves.
            if(!ReservoirContent.bridge.emptyContainer(input))return Result.NONE;
            int count=Math.min(input.getCount(),room(output,input));
            if(count<=0)return Result.NONE;
            output=append(output,input.copyWithCount(count));input.shrink(count);
            commit(level,bag,bag,input,output,!input.isEmpty());return Result.CONTAINER_ONLY;
        }
        boolean withdrawing=ReservoirContent.bridge.emptyContainer(input);
        ItemStack staged=bag.copy(),single=input.copyWithCount(1);
        boolean moved=withdrawing?ReservoirContent.bridge.extractToContainer(level,staged,single)
                :ReservoirContent.bridge.fill(level,staged,single);
        if(moved && (withdrawing || ReservoirContent.bridge.emptyContainer(single)) && room(output,single)>0){
            output=append(output,single);input.shrink(1);
            commit(level,bag,staged,input,output,false);return Result.ESSENTIA;
        }
        if(withdrawing)return Result.NONE;
        // No destination, or one partially drained carrier would differ from its input peers.
        // Restart from the originals; the failed single-carrier proposal was never committed.
        staged=bag.copy();
        ItemStack retained=ReservoirContent.bridge.drainInPlace(level,staged,input);
        if(retained.isEmpty() || retained.getCount()!=input.getCount() || !ItemStack.isSameItem(input,retained))return Result.NONE;
        input=retained;
        boolean drained=ReservoirContent.bridge.emptyContainer(input);
        if(drained){
            int count=Math.min(input.getCount(),room(output,input));
            if(count>0){output=append(output,input.copyWithCount(count));input.shrink(count);}
        }
        commit(level,bag,staged,input,output,drained && !input.isEmpty());return Result.ESSENTIA;
    }
    private static int room(ItemStack output,ItemStack result){
        if(result.isEmpty() || (!output.isEmpty() && !ItemStack.isSameItemSameComponents(output,result)))return 0;
        return Math.max(0,ReservoirData.PORT_LIMIT-output.getCount());
    }
    private static ItemStack append(ItemStack output,ItemStack result){
        return output.isEmpty()?result.copy():output.copyWithCount(output.getCount()+result.getCount());
    }
    private static void commit(Level level,ItemStack bag,ItemStack staged,ItemStack input,ItemStack output,boolean drained){
        var registries=level.registryAccess();
        var stagedData=ReservoirData.read(staged);
        var in=input.saveOptional(registries);var out=output.saveOptional(registries);
        ReservoirData.edit(bag,data->{
            for(int cell=0;cell<ReservoirData.cells(bag);cell++)data.put("cell_"+cell,stagedData.getCompound("cell_"+cell));
            if(input.isEmpty())data.remove("slot_0");else data.put("slot_0",in);
            if(output.isEmpty())data.remove("slot_1");else data.put("slot_1",out);
            if(drained)data.putBoolean("input_drained",true);else data.remove("input_drained");
        });
    }
}
