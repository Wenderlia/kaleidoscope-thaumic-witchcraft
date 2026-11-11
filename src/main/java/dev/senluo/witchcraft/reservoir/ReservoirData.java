package dev.senluo.witchcraft.reservoir;

import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

/** Item-owned state. Every edit rereads the current component, never a menu's old snapshot. */
public final class ReservoirData {
    private static final String KEY = "kw_reservoir";
    // v2 preserves extended port stacks and the drained-input latch; older readers must refuse edits.
    private static final int VERSION = 2;
    public static int cells(ItemStack stack){return ReservoirProfile.of(stack).cells;}
    public static final int PORT_LIMIT = 64;
    // Input and output own the first two slots; removable plugins follow them.
    public static final int FIRST_PLUGIN_SLOT=2;
    public static int storageSlots(ItemStack stack){return ReservoirProfile.of(stack).storageSlots();}
    private ReservoirData() {}
    public static CompoundTag read(ItemStack stack) {
        return stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag().getCompound(KEY);
    }
    private static boolean supported(CompoundTag data) { return data.isEmpty() || data.getInt("version") == VERSION; }
    public static boolean supported(ItemStack stack) { return supported(read(stack)); }
    public static void edit(ItemStack stack, java.util.function.Consumer<CompoundTag> edit) {
        CompoundTag root=stack.getOrDefault(DataComponents.CUSTOM_DATA,CustomData.EMPTY).copyTag();
        CompoundTag data=root.getCompound(KEY);
        if(!supported(data)) return;
        edit.accept(data); data.putInt("version",VERSION); root.put(KEY,data);
        stack.set(DataComponents.CUSTOM_DATA,CustomData.of(root));
    }
    // Bulk readers take one detached snapshot, rather than copying plugin/port NBT per chamber field.
    // A snapshot is local to one query; it must not survive an external callback or become write authority.
    public static String aspect(CompoundTag data,int cell) { return data.getCompound("cell_"+cell).getString("aspect"); }
    public static int amount(CompoundTag data,int cell) { return Math.max(0,data.getCompound("cell_"+cell).getInt("amount")); }
    public static String label(CompoundTag data,int cell) { return data.getCompound("cell_"+cell).getString("label"); }
    public static String aspect(ItemStack stack,int cell) { return aspect(read(stack),cell); }
    public static int amount(ItemStack stack,int cell) { return amount(read(stack),cell); }
    public static String label(ItemStack stack,int cell) { return label(read(stack),cell); }
    public static int capacity(ItemStack stack) { return ReservoirProfile.of(stack).chamberCapacity(); }
    public static int selected(CompoundTag data,int cells) { return Math.clamp(data.getInt("selected"),0,cells-1); }
    public static int selected(ItemStack stack) { return selected(read(stack),cells(stack)); }
    public static void select(ItemStack stack,int cell) {
        if(cell<0 || cell>=cells(stack))throw new IllegalArgumentException("Invalid chamber");
        edit(stack,data->data.putInt("selected",cell));
    }
    public static int room(ItemStack stack,String aspect) {
        return room(read(stack),aspect,cells(stack),capacity(stack));
    }
    private static int room(CompoundTag data,String aspect,int cells,int capacity) {
        if(aspect.isEmpty() || !supported(data))return 0;
        long room=0;
        for(int cell=0;cell<cells;cell++)if(accepts(data,cell,aspect))room+=Math.max(0,capacity-amount(data,cell));
        return (int)Math.min(Integer.MAX_VALUE,room);
    }
    private static boolean accepts(CompoundTag data,int cell,String aspect) {
        String label=label(data,cell);
        return (label.isEmpty() || label.equals(aspect)) && (amount(data,cell)==0 || aspect(data,cell).equals(aspect));
    }
    public static void deposit(ItemStack stack,String aspect,int amount) {
        if(amount<=0 || room(stack,aspect)<amount) throw new IllegalArgumentException("Insufficient reservoir capacity");
        int cells=cells(stack),capacity=capacity(stack);
        edit(stack,data->deposit(data,aspect,amount,cells,capacity));
    }
    private static void deposit(CompoundTag data,String aspect,int amount,int cells,int capacity) {
        int left=amount,selected=selected(data,cells);
        // Selected first, then matching stock, then empty chambers. Labels remain hard filters.
        for(int pass=0;pass<3 && left>0;pass++)for(int cell=0;cell<cells && left>0;cell++){
            int stored=amount(data,cell);
            boolean eligible=pass==0?cell==selected:cell!=selected && (pass==1?stored>0:stored==0);
            if(!eligible || !accepts(data,cell,aspect))continue;
            int moved=Math.min(left,Math.max(0,capacity-stored));
            if(moved>0){cell(data,cell,aspect,stored+moved);left-=moved;}
        }
    }
    public static void cell(ItemStack stack,int cell,String aspect,int amount) {
        if(cell<0 || cell>=cells(stack) || amount<0) throw new IllegalArgumentException("Invalid chamber");
        edit(stack,data->cell(data,cell,aspect,amount));
    }
    private static void cell(CompoundTag data,int cell,String aspect,int amount) {
        CompoundTag value=data.getCompound("cell_"+cell);value.putString("aspect",amount==0?"":aspect);
        value.putInt("amount",amount);data.put("cell_"+cell,value);
    }
    /** One local mutation; selected chamber first. No host callbacks run between reads and writes. */
    public static int withdraw(ItemStack stack,String aspect,int requested) {
        if(requested<=0 || aspect.isEmpty())return 0;
        int cells=cells(stack);int[] moved={0};
        edit(stack,data->moved[0]=withdraw(data,aspect,requested,cells));
        return moved[0];
    }
    private static int withdraw(CompoundTag data,String aspect,int requested,int cells) {
        int selected=selected(data,cells),left=requested;
        for(int n=0;n<cells && left>0;n++){
            int cell=(selected+n)%cells;
            if(!aspect.equals(aspect(data,cell)))continue;
            int stored=amount(data,cell),take=Math.min(stored,left);
            if(take>0){cell(data,cell,aspect,stored-take);left-=take;}
        }
        return requested-left;
    }
    public static ItemStack slot(ItemStack stack,int slot,HolderLookup.Provider registries) {
        return slot(read(stack),slot,registries);
    }
    public static ItemStack slot(CompoundTag data,int slot,HolderLookup.Provider registries) {
        return ItemStack.parseOptional(registries,data.getCompound("slot_"+slot));
    }
    public static void slot(ItemStack stack,int slot,ItemStack value,HolderLookup.Provider registries) {
        boolean resetDrained=slot==0 && (value.isEmpty() || !ItemStack.isSameItemSameComponents(slot(stack,0,registries),value));
        edit(stack,data->{
            if(value.isEmpty())data.remove("slot_"+slot);else data.put("slot_"+slot,value.save(registries));
            if(resetDrained)data.remove("input_drained");
        });
    }
    /** Each plugin is an actual removable item, including its paid balances and fractional carry. */
    public static long balance(ItemStack converter,int channel) { return Math.max(0,read(converter).getLong("vis_"+channel)); }
    public static void credit(ItemStack converter,long[] units) {
        edit(converter,data->{for(int i=0;i<6;i++)data.putLong("vis_"+i,Math.addExact(Math.max(0,data.getLong("vis_"+i)),units[i]));});
    }
    public static void debit(ItemStack converter,int channel,long units) {
        long before=balance(converter,channel);
        if(units<0 || units>before)throw new IllegalArgumentException("Vis overdraft");
        edit(converter,data->data.putLong("vis_"+channel,before-units));
    }
}
