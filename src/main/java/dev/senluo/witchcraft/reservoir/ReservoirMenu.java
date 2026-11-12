package dev.senluo.witchcraft.reservoir;

import net.minecraft.world.*;
import net.minecraft.world.entity.player.*;
import net.minecraft.world.inventory.*;
import net.minecraft.world.item.ItemStack;
import static dev.senluo.witchcraft.reservoir.ReservoirLayout.*;

/** Only real slots use inventory sync. The hidden read-only slot carries a compact chamber snapshot. */
@org.anti_ad.mc.ipn.api.IPNIgnore
public final class ReservoirMenu extends AbstractContainerMenu {
    private final Inventory playerInventory;
    private final ItemStack bag;
    private final ReservoirSession session;
    private final int snapshotSlot,playerStart,storageSlots;
    private final ReservoirLayout layout;
    public ReservoirLayout layout(){return layout;}
    // SimpleContainer otherwise clamps a received jar stack to the item's native limit on setItem.
    private final SimpleContainer storage,snapshot=new SimpleContainer(1);
    private boolean refreshing;
    // Screen-local visibility only: inventory sync, quick-move and plugin work keep their real slots.
    private boolean pluginsVisible=true;
    public ReservoirMenu(int id,Inventory inventory,ReservoirProfile profile){this(id,inventory,null,profile);}
    ReservoirMenu(int id,Inventory inventory,ReservoirSession session){
        this(id,inventory,session,ReservoirProfile.of(session.bag()));
    }
    private ReservoirMenu(int id,Inventory inventory,ReservoirSession session,ReservoirProfile profile){
        super(ReservoirContent.menu(profile),id);
        this.playerInventory=inventory;this.session=session;this.bag=session==null?ItemStack.EMPTY:session.bag();
        layout=new ReservoirLayout(profile);storageSlots=profile.storageSlots();snapshotSlot=storageSlots;playerStart=snapshotSlot+1;
        storage=new SimpleContainer(storageSlots){
            @Override public int getMaxStackSize(ItemStack stack){return ReservoirData.PORT_LIMIT;}
        };
        for(int i=0;i<storageSlots;i++){
            int x=i==0?INPUT_X:i==1?layout.outputX():PLUGIN_X;
            int y=i==0?INPUT_Y:i==1?OUTPUT_Y:pluginY(i-ReservoirData.FIRST_PLUGIN_SLOT);
            if(i<ReservoirData.FIRST_PLUGIN_SLOT)addSlot(new PortSlot(storage,i,x,y));
            else addSlot(new Slot(storage,i,x,y){
                public boolean mayPlace(ItemStack stack){return ReservoirPlugins.accepts(stack);}
                public int getMaxStackSize(){return 1;}
                public boolean isActive(){return pluginsVisible;}
            });
        }
        addSlot(new Slot(snapshot,0,-1000,-1000){
            public boolean mayPlace(ItemStack stack){return false;}
            public boolean mayPickup(Player player){return false;}
        });
        for(int row=0;row<3;row++)for(int col=0;col<9;col++)addPlayerSlot(inventory,col+row*9+9,layout.inventoryX()+col*18,INVENTORY_Y+row*18);
        for(int col=0;col<9;col++)addPlayerSlot(inventory,col,layout.inventoryX()+col*18,HOTBAR_Y);
        refresh();
        storage.addListener(ignored->{
            if(refreshing || session==null || !session.valid(inventory.player))return;
            for(int i=0;i<storageSlots;i++)ReservoirData.slot(bag,i,storage.getItem(i),inventory.player.registryAccess());
            session.changed();
        });
    }
    private void addPlayerSlot(Inventory inventory,int index,int x,int y){
        addSlot(new Slot(inventory,index,x,y){
            public boolean mayPickup(Player player){return getItem()!=bag;}
            public boolean mayPlace(ItemStack stack){return stack!=bag;}
        });
    }
    /** Extended stacks exist only inside the two ports. Every cursor/drop extraction stays native-sized. */
    private static final class PortSlot extends Slot {
        private final boolean input;
        PortSlot(SimpleContainer storage,int index,int x,int y){super(storage,index,x,y);input=index==0;}
        @Override public boolean mayPlace(ItemStack stack){return input && ReservoirContent.bridge.container(stack);}
        @Override public int getMaxStackSize(){return ReservoirData.PORT_LIMIT;}
        @Override public int getMaxStackSize(ItemStack stack){return ReservoirData.PORT_LIMIT;}
        @Override public java.util.Optional<ItemStack> tryRemove(int requested,int maximum,Player player){
            if(!mayPickup(player) || !hasItem())return java.util.Optional.empty();
            int count=Math.min(Math.min(requested,maximum),getItem().getMaxStackSize());
            if(count<=0)return java.util.Optional.empty();
            ItemStack removed=remove(count);
            if(removed.isEmpty())return java.util.Optional.empty();
            if(getItem().isEmpty())setByPlayer(ItemStack.EMPTY,removed);
            return java.util.Optional.of(removed);
        }
    }
    private void refresh(){
        if(bag.isEmpty())return;
        refreshing=true;
        try{for(int i=0;i<storageSlots;i++)storage.setItem(i,ReservoirData.slot(bag,i,playerInventory.player.registryAccess()));}
        finally{refreshing=false;}
    }
    public ItemStack snapshot(){return snapshot.getItem(0);}
    public boolean pluginsVisible(){return pluginsVisible;}
    public void setPluginsVisible(boolean visible){pluginsVisible=visible;}
    ReservoirSession session(){return session;}
    public boolean stillValid(Player player){
        return session!=null?session.valid(player)
                :player.isAlive() && !dev.senluo.witchcraft.ritual.OwnershipRitual.frozen(player);
    }
    @Override public void removed(Player player){
        super.removed(player);
        if(session!=null)session.close();
    }
    public void clicked(int slot,int button,ClickType type,Player player){
        if(!bag.isEmpty()){
            if(!stillValid(player))return;
            // A hotbar/offhand swap can otherwise move the open host out from under its slots.
            if(type==ClickType.SWAP && player.getInventory().getItem(button)==bag)return;
            if(slot>=0 && slot<slots.size() && slots.get(slot).getItem()==bag)return;
            refresh();
        }
        // Compare only user commits. Refresh and background charging must never sound like a click.
        ItemStack[] before=null;
        if(!bag.isEmpty()){
            before=new ItemStack[storageSlots];
            for(int i=0;i<storageSlots;i++)before[i]=storage.getItem(i).copy();
        }
        if(!portClick(slot,button,type,player))super.clicked(slot,button,type,player);
        if(before!=null){
            boolean ports=false,plugins=false;
            for(int i=0;i<storageSlots;i++)if(!ItemStack.matches(before[i],storage.getItem(i))){
                if(i<ReservoirData.FIRST_PLUGIN_SLOT)ports=true;else plugins=true;
            }
            if(plugins)ReservoirFeedback.play(player,ReservoirFeedback.Action.PLUGIN);
            else if(ports)ReservoirFeedback.play(player,ReservoirFeedback.Action.CONTAINER);
        }
    }
    private boolean portClick(int index,int button,ClickType type,Player player){
        if(index<0 || index>=ReservoirData.FIRST_PLUGIN_SLOT)return false;
        Slot slot=slots.get(index);ItemStack stored=slot.getItem();
        if(stored.isEmpty() || stored.getCount()<=stored.getMaxStackSize())return false;
        if(type==ClickType.SWAP && (button>=0 && button<9 || button==40)){
            ItemStack held=player.getInventory().getItem(button);
            if(!held.isEmpty() && !ItemStack.isSameItemSameComponents(stored,held))return true;
            int count=Math.min(stored.getCount(),stored.getMaxStackSize()-held.getCount());
            if(count>0 && slot.mayPickup(player)){
                ItemStack taken=slot.safeTake(count,count,player);
                if(!taken.isEmpty())player.getInventory().setItem(button,held.isEmpty()?taken:held.copyWithCount(held.getCount()+taken.getCount()));
            }
            return true;
        }
        // Vanilla's dissimilar cursor swap bypasses tryRemove and could export the whole oversized stack.
        return type==ClickType.PICKUP && !getCarried().isEmpty()
                && !ItemStack.isSameItemSameComponents(stored,getCarried());
    }
    public boolean clickMenuButton(Player player,int id){
        if(session==null)return false;
        var result=session.control(player,id,getCarried());
        if(!result.changed())return false;
        broadcastChanges();
        session.feedback(result);
        return true;
    }
    public void broadcastChanges(){
        if(session!=null)session.pollTransfers();
        if(session!=null && session.valid(playerInventory.player)){
            // Processing belongs to the holder's server tick; opening extra views never adds work pulses.
            refresh();
            var view=new ItemStack(bag.getItem());
            var data=ReservoirData.read(bag);
            ReservoirData.edit(view,d->{for(int i=0;i<layout.profile.cells;i++)d.put("cell_"+i,data.getCompound("cell_"+i));
                d.putInt("capacity",ReservoirData.capacity(bag));d.putInt("selected",ReservoirData.selected(data,layout.profile.cells));});
            if(!ItemStack.matches(view,snapshot.getItem(0)))snapshot.setItem(0,view);
        }
        super.broadcastChanges();
    }
    public ItemStack quickMoveStack(Player player,int index){
        if(index<0 || index>=slots.size() || index==snapshotSlot)return ItemStack.EMPTY;
        Slot slot=slots.get(index);if(!slot.hasItem() || !slot.mayPickup(player))return ItemStack.EMPTY;
        ItemStack source=slot.getItem(),before=source.copy();
        if(index<storageSlots){if(!moveItemStackTo(source,playerStart,slots.size(),true))return ItemStack.EMPTY;}
        else if(ReservoirPlugins.accepts(source)){if(!moveItemStackTo(source,ReservoirData.FIRST_PLUGIN_SLOT,storageSlots,false))return ItemStack.EMPTY;}
        else if(ReservoirContent.bridge.container(source)){
            // Vanilla quick-move skips merging when the source item is natively unstackable.
            int count=source.getCount();slots.get(0).safeInsert(source);
            if(source.getCount()==count)return ItemStack.EMPTY;
        }
        else return ItemStack.EMPTY;
        if(source.isEmpty())slot.setByPlayer(ItemStack.EMPTY);else slot.setChanged();
        slot.onTake(player,source);return before;
    }
}
