package dev.senluo.witchcraft.brew;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.UUID;
import java.util.function.Predicate;

/** Inventory, owner and overflow belong to the vault, independently of its block/entity anchor. */
public final class AmberVaultContents extends SimpleContainer {
    UUID owner;
    boolean takeOnly;
    int quality;
    private final ArrayDeque<ItemStack> overflow = new ArrayDeque<>();
    private final Runnable dirty;
    private final Predicate<Player> present;
    private CompoundTag unsupported;
    private boolean refilling;

    public AmberVaultContents(int size, Runnable dirty, Predicate<Player> present) {
        super(size); this.dirty=dirty; this.present=present;
    }
    public boolean allowed(Player player) { return owner!=null && (owner.equals(player.getUUID()) || player.hasPermissions(2)); }
    @Override public boolean stillValid(Player player) { return unsupported==null && allowed(player) && present.test(player); }
    @Override public boolean canPlaceItem(int slot, ItemStack stack) { return !takeOnly && unsupported==null; }
    @Override public void setChanged() { super.setChanged(); if(dirty!=null)dirty.run(); }
    public boolean entirelyEmpty() { return unsupported==null && isEmpty() && overflow.isEmpty(); }
    public boolean hasOverflow() { return !overflow.isEmpty(); }

    /** No external callbacks; caller removes only the copies accepted after anchor creation. */
    public void receiveDeath(Collection<ItemStack> stacks) {
        for(ItemStack stack:stacks) if(!stack.isEmpty()) overflow.addLast(stack.copy());
        refill(); setChanged();
    }
    public void refill() {
        if(unsupported!=null || overflow.isEmpty() || refilling)return;
        refilling=true;
        // Only between inventory transactions; never refill a slot from removeItem/setItem.
        try{
            for(int slot=0;slot<getContainerSize() && !overflow.isEmpty();slot++)
                if(getItem(slot).isEmpty())setItem(slot,overflow.removeFirst());
        }finally{refilling=false;}
    }
    public CompoundTag save(HolderLookup.Provider provider) {
        if(unsupported!=null)return unsupported.copy();
        CompoundTag tag=new CompoundTag();tag.putInt("Version",1);tag.putInt("Size",getContainerSize());
        if(owner!=null)tag.putUUID("Owner",owner);
        tag.putBoolean("TakeOnly",takeOnly);tag.putInt("Quality",quality);
        ListTag items=new ListTag();
        for(int i=0;i<getContainerSize();i++)items.add(getItem(i).saveOptional(provider));
        tag.put("Items",items);ListTag queue=new ListTag();
        for(ItemStack stack:overflow)queue.add(stack.save(provider));
        tag.put("Overflow",queue);return tag;
    }
    public void load(CompoundTag tag, HolderLookup.Provider provider) {
        clearContent();overflow.clear();unsupported=null;owner=null;
        if(tag.getInt("Version")!=1 || !tag.hasUUID("Owner")
                || !compoundList(tag,"Items") || !compoundList(tag,"Overflow")) {unsupported=tag.copy();return;}
        owner=tag.getUUID("Owner");takeOnly=tag.getBoolean("TakeOnly");quality=Math.clamp(tag.getInt("Quality"),2,6);
        ListTag items=tag.getList("Items",10);
        for(int i=0;i<items.size();i++) {
            ItemStack stack=ItemStack.parseOptional(provider,items.getCompound(i));
            if(stack.isEmpty() && !items.getCompound(i).isEmpty()){unsupported=tag.copy();return;}
            if(i<getContainerSize())setItem(i,stack);else if(!stack.isEmpty())overflow.add(stack);
        }
        ListTag queue=tag.getList("Overflow",10);
        for(int i=0;i<queue.size();i++) {
            ItemStack stack=ItemStack.parseOptional(provider,queue.getCompound(i));
            if(stack.isEmpty() && !queue.getCompound(i).isEmpty()){unsupported=tag.copy();return;}
            if(!stack.isEmpty())overflow.add(stack);
        }
    }
    public static int size(CompoundTag tag) { return switch(tag.getInt("Size")){case 18->18;case 27->27;default->54;}; }
    private static boolean compoundList(CompoundTag tag,String key){
        if(!(tag.get(key) instanceof ListTag list))return false;
        return list.isEmpty() || list.getElementType()==net.minecraft.nbt.Tag.TAG_COMPOUND;
    }
}
