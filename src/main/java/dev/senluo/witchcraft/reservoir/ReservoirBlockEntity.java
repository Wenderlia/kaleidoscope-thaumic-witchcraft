package dev.senluo.witchcraft.reservoir;

import net.minecraft.core.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

public final class ReservoirBlockEntity extends BlockEntity {
    private ItemStack bag=ItemStack.EMPTY;
    private long lastPortTick=Long.MIN_VALUE;
    private boolean pickingUp;
    private java.util.List<ItemStack> pickupDrops;
    public ReservoirBlockEntity(BlockPos pos,BlockState state){super(ReservoirContent.ENTITY.get(),pos,state);}
    public ItemStack bag(){return bag;}
    public boolean available(){return !isRemoved() && level!=null && ReservoirContent.isBackpack(bag) && ReservoirData.supported(bag);}
    private final net.neoforged.neoforge.items.IItemHandler ports=new net.neoforged.neoforge.items.IItemHandler(){
        public int getSlots(){return 2;}
        public ItemStack getStackInSlot(int slot){return available() && slot>=0 && slot<2?ReservoirData.slot(bag,slot,level.registryAccess()):ItemStack.EMPTY;}
        public int getSlotLimit(int slot){return ReservoirData.PORT_LIMIT;}
        public boolean isItemValid(int slot,ItemStack stack){return slot==0 && ReservoirContent.bridge.container(stack);}
        public ItemStack insertItem(int slot,ItemStack stack,boolean simulate){
            if(!available() || stack.isEmpty() || !isItemValid(slot,stack) || (!simulate && level.isClientSide))return stack;
            ItemStack current=getStackInSlot(slot);
            if(!current.isEmpty() && !ItemStack.isSameItemSameComponents(current,stack))return stack;
            int moved=Math.min(stack.getCount(),Math.max(0,getSlotLimit(slot)-current.getCount()));
            if(moved==0)return stack;
            if(!simulate){ReservoirData.slot(bag,slot,stack.copyWithCount(current.getCount()+moved),level.registryAccess());changed();}
            return moved==stack.getCount()?ItemStack.EMPTY:stack.copyWithCount(stack.getCount()-moved);
        }
        public ItemStack extractItem(int slot,int amount,boolean simulate){
            if(!available() || slot!=1 || amount<=0 || (!simulate && level.isClientSide))return ItemStack.EMPTY;
            ItemStack current=getStackInSlot(slot);if(current.isEmpty())return ItemStack.EMPTY;
            int moved=Math.min(Math.min(amount,current.getCount()),current.getMaxStackSize());
            ItemStack result=current.copyWithCount(moved);
            if(!simulate){current.shrink(moved);ReservoirData.slot(bag,slot,current,level.registryAccess());changed();}
            return result;
        }
    };
    public net.neoforged.neoforge.items.IItemHandler ports(){return ports;}
    public void setBag(ItemStack value){bag=value;changed();}
    public ItemStack take(){var value=bag;bag=ItemStack.EMPTY;setChanged();return value;}
    /** Native removal owns the stock transfer, including when callbacks replace this block. */
    ItemStack removalDrop(){
        ItemStack value=take();
        if(pickupDrops==null)return value;
        if(!value.isEmpty())pickupDrops.add(value);
        return ItemStack.EMPTY;
    }
    private boolean canPickup(ServerPlayer player){
        return level==player.level() && !isRemoved() && level.hasChunkAt(worldPosition)
                && level.getBlockEntity(worldPosition)==this && !bag.isEmpty()
                && player.mayBuild() && !player.isSpectator() && player.isShiftKeyDown()
                && player.getMainHandItem().isEmpty() && player.canInteractWithBlock(worldPosition,1)
                && level.getWorldBorder().isWithinBounds(worldPosition) && level.mayInteract(player,worldPosition)
                && !player.blockActionRestricted(level,worldPosition,player.gameMode.getGameModeForPlayer())
                && !dev.senluo.witchcraft.ritual.OwnershipRitual.frozen(player)
                && !level.captureBlockSnapshots && !level.restoringBlockSnapshots;
    }
    public boolean pickup(ServerPlayer player){
        if(pickingUp || !canPickup(player))return false;
        pickingUp=true;
        try{
            BlockState before=getBlockState();
            var event=net.neoforged.neoforge.common.CommonHooks.fireBlockBreak(
                    level,player.gameMode.getGameModeForPlayer(),player,worldPosition,before);
            // Listeners may cancel, remove/replace this entity, or perform a nested pickup.
            if(event.isCanceled() || !canPickup(player) || level.getBlockState(worldPosition)!=before)return false;
            pickupDrops=new java.util.ArrayList<>();
            boolean removed=before.onDestroyedByPlayer(level,worldPosition,player,false,level.getFluidState(worldPosition));
            if(removed)before.getBlock().destroy(level,worldPosition,before);
            return !pickupDrops.isEmpty() && level.getBlockEntity(worldPosition)!=this;
        }finally{
            var results=pickupDrops;
            pickupDrops=null;
            pickingUp=false;
            // Settle even if a removal callback throws; there is always one owner of the stock.
            if(results!=null)for(ItemStack result:results){
                if(level.getBlockEntity(worldPosition)==this && !isRemoved() && bag.isEmpty())setBag(result);
                else if(!player.getInventory().add(result))player.drop(result,false);
            }
        }
    }
    public void changed(){setChanged();if(level!=null)level.sendBlockUpdated(worldPosition,getBlockState(),getBlockState(),3);}
    public static void serverTick(Level level,BlockPos pos,BlockState state,ReservoirBlockEntity entity){
        long tick=level.getGameTime();
        // Ports use game time, not extra machine ticks. No offline catch-up or duplicate pulse from accelerators.
        if(level.isClientSide || tick%5!=0 || entity.lastPortTick==tick || entity.isRemoved())return;
        entity.lastPortTick=tick;
        if(ReservoirContent.isBackpack(entity.bag) && ReservoirTransfers.processInput(level,entity.bag))entity.changed();
        if(entity.available())ReservoirContent.bridge.tickPlaced(entity);
    }
    public void open(ServerPlayer player){
        if(bag.isEmpty() || !ReservoirData.supported(bag))return;
        ItemStack bound=bag;
        player.openMenu(new SimpleMenuProvider((id,inventory,p)->new ReservoirMenu(id,inventory,bound,
                ()->!isRemoved() && bag==bound && p.level()==level && p.canInteractWithBlock(worldPosition,1),this::changed),bag.getHoverName()));
    }
    protected void saveAdditional(CompoundTag tag,HolderLookup.Provider provider){super.saveAdditional(tag,provider);tag.put("bag",bag.saveOptional(provider));}
    protected void loadAdditional(CompoundTag tag,HolderLookup.Provider provider){super.loadAdditional(tag,provider);bag=ItemStack.parseOptional(provider,tag.getCompound("bag"));}
    public CompoundTag getUpdateTag(HolderLookup.Provider provider){return saveWithoutMetadata(provider);}
    public ClientboundBlockEntityDataPacket getUpdatePacket(){return ClientboundBlockEntityDataPacket.create(this);}
}
