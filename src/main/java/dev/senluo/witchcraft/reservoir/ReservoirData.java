package dev.senluo.witchcraft.reservoir;

import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;

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
    private static final Map<ItemStack, View> VIEWS = Collections.synchronizedMap(new WeakHashMap<>());
    /** Immutable component view. Returned stacks are copies; writes always reread the actual item. */
    public static final class View {
        private final CustomData component;
        private final CompoundTag data;
        public static final class Slot {
            private final CompoundTag tag;
            private final HolderLookup.Provider registries;
            private final ItemStack template;

            private Slot(CompoundTag tag, HolderLookup.Provider registries) {
                this.tag = tag;
                this.registries = registries;
                this.template = ItemStack.parseOptional(registries, tag);
            }
            public ItemStack copy() { return template.copy(); }
            public boolean matches(ItemStack stack) { return ItemStack.matches(template, stack); }
            public net.minecraft.world.item.Item item() { return template.getItem(); }
            public boolean singleSupported() { return template.getCount() == 1 && ReservoirData.supported(template); }
        }
        private final Map<Integer, Slot> slots;
        private View(CustomData component, View before) {
            this.component = component;
            data = component.copyTag().getCompound(KEY);
            slots = before == null ? new HashMap<>() : new HashMap<>(before.slots);
        }
        public boolean supported() { return ReservoirData.supported(data); }
        public boolean contains(String key) { return data.contains(key); }
        public int integer(String key) { return data.getInt(key); }
        public long number(String key) { return data.getLong(key); }
        public boolean flag(String key) { return data.getBoolean(key); }
        public String string(String key) { return data.getString(key); }
        public ItemStack slot(int slot, HolderLookup.Provider registries) { return stored(slot, registries).copy(); }
        public Slot stored(int slot, HolderLookup.Provider registries) {
            CompoundTag tag = data.getCompound("slot_" + slot);
            Slot old = slots.get(slot);
            if (old == null || old.registries != registries || !old.tag.equals(tag)) {
                old = new Slot(tag, registries);
                if (slot >= 0 && slot < PORT_LIMIT) slots.put(slot, old);
            }
            return old;
        }
    }
    public static View view(ItemStack stack) {
        CustomData component = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY);
        View old = VIEWS.get(stack);
        if (old != null && old.component == component) return old;
        View next = new View(component, old);
        VIEWS.put(stack, next);
        return next;
    }
    public static void clearViews() { VIEWS.clear(); }
    private ReservoirData() {}
    public static CompoundTag read(ItemStack stack) {
        return view(stack).data.copy();
    }
    private static boolean supported(CompoundTag data) { return data.isEmpty() || data.getInt("version") == VERSION; }
    public static boolean supported(ItemStack stack) { return view(stack).supported(); }
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
    public static String aspect(ItemStack stack,int cell) { return aspect(view(stack).data,cell); }
    public static int amount(ItemStack stack,int cell) { return amount(view(stack).data,cell); }
    public static String label(ItemStack stack,int cell) { return label(view(stack).data,cell); }
    public static int capacity(ItemStack stack) { return ReservoirProfile.of(stack).chamberCapacity(); }
    public static int selected(CompoundTag data,int cells) { return Math.clamp(data.getInt("selected"),0,cells-1); }
    public static int selected(ItemStack stack) { return selected(view(stack).data,cells(stack)); }
    public static void select(ItemStack stack,int cell) {
        if(cell<0 || cell>=cells(stack))throw new IllegalArgumentException("Invalid chamber");
        edit(stack,data->data.putInt("selected",cell));
    }
    public static int room(ItemStack stack,String aspect) {
        return room(view(stack).data,aspect,cells(stack),capacity(stack));
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
    /** Consume the expected chamber's current stock; no callbacks occur between validation and write. */
    public static int withdrawFromCell(ItemStack stack,int cell,String aspect,int requested) {
        if(requested<=0 || aspect.isEmpty() || cell<0 || cell>=cells(stack))return 0;
        var data=read(stack);
        if(!supported(data) || !aspect.equals(aspect(data,cell)))return 0;
        int stored=amount(data,cell),taken=Math.min(stored,requested);
        if(taken>0)cell(stack,cell,aspect,stored-taken);
        return taken;
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
        return view(stack).slot(slot,registries);
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
    /** Paid microvis belongs to each detachable converter and is keyed by stable aspect ID. */
    public static Map<ResourceLocation, Long> balances(ItemStack converter) {
        var tag = view(converter).data.getCompound("vis_balances");
        var result = new HashMap<ResourceLocation, Long>();
        for (String key : tag.getAllKeys()) {
            var id = ResourceLocation.tryParse(key);
            long amount = tag.getLong(key);
            if (id != null && amount > 0) result.put(id, amount);
        }
        return result;
    }

    public static void credit(ItemStack converter, Map<ResourceLocation, Long> units) {
        edit(converter, data -> {
            var stock = data.getCompound("vis_balances");
            units.forEach((id, amount) -> {
                if (amount < 0) throw new IllegalArgumentException("Negative Vis credit");
                if (amount > 0) stock.putLong(id.toString(), Math.addExact(stock.getLong(id.toString()), amount));
            });
            data.put("vis_balances", stock);
        });
    }

    /** Validate the whole settlement before mutating the source. */
    public static boolean debit(ItemStack converter, Map<ResourceLocation, Long> units) {
        if (!supported(converter)) return false;
        var stock = view(converter).data.getCompound("vis_balances");
        for (var entry : units.entrySet()) {
            if (entry.getValue() <= 0 || stock.getLong(entry.getKey().toString()) < entry.getValue()) return false;
        }
        edit(converter, data -> {
            var current = data.getCompound("vis_balances");
            units.forEach((id, amount) -> {
                String key = id.toString();
                long left = current.getLong(key) - amount;
                if (left == 0) current.remove(key);
                else current.putLong(key, left);
            });
            data.put("vis_balances", current);
        });
        return true;
    }
}
