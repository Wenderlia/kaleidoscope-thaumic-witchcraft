package dev.senluo.witchcraft.reservoir;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/** Optional host boundary: persistent storage and equipment never link TC classes. */
public interface ReservoirBridge {
    boolean container(ItemStack stack);
    boolean emptyContainer(ItemStack stack);
    boolean fill(Level level,ItemStack backpack,ItemStack singleContainer);
    /** Drain identical reusable carriers equally without an output destination. Mutate only the staged
     * backpack; return independently owned carriers, or EMPTY to discard the proposal. Phials refuse. */
    default ItemStack drainInPlace(Level level,ItemStack backpack,ItemStack containers){return ItemStack.EMPTY;}
    boolean extractToContainer(Level level,ItemStack backpack,ItemStack emptyContainer);
    boolean label(Level level,ItemStack backpack,int cell,ItemStack label);
    void charge(ServerPlayer player,ItemStack backpack,int storageSlot,ItemStack plugin);
    /** Existing placed-backpack pulse; optional host transport operates on the same item stock. */
    default void tickPlaced(ReservoirBlockEntity entity){}
    ReservoirBridge NONE=new ReservoirBridge(){
        public boolean container(ItemStack s){return false;}
        public boolean emptyContainer(ItemStack s){return false;}
        public boolean fill(Level l,ItemStack b,ItemStack c){return false;}
        public boolean extractToContainer(Level l,ItemStack b,ItemStack c){return false;}
        public boolean label(Level l,ItemStack b,int c,ItemStack s){return false;}
        public void charge(ServerPlayer p,ItemStack b,int slot,ItemStack plugin){}
    };
}
