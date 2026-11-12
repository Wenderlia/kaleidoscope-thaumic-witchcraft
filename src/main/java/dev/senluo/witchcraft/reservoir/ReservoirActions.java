package dev.senluo.witchcraft.reservoir;

import dev.senluo.witchcraft.ritual.OwnershipRitual;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/** Server mutations return committed facts. Callers own host validation, dirtying, sync and feedback. */
public final class ReservoirActions {
    // Existing vanilla menu-button protocol: cells 0..5, plugin storage slots starting at 102.
    private static final int TOGGLE_PLUGIN=100;

    public enum Result {
        NONE, SELECTED, LABELED, PLUGIN_INSTALLED, PLUGIN_ENABLED, PLUGIN_DISABLED, FILLED;
        public boolean changed(){return this!=NONE;}
        public boolean pluginToggled(){return this==PLUGIN_ENABLED || this==PLUGIN_DISABLED;}
    }

    public static boolean canAct(Player player){
        return player.isAlive() && !player.isSpectator() && !OwnershipRitual.frozen(player);
    }

    public static boolean acceptsInput(ItemStack held){
        return ReservoirPlugins.accepts(held) || ReservoirContent.bridge.container(held);
    }

    public static int pluginButton(int index){return TOGGLE_PLUGIN+ReservoirData.FIRST_PLUGIN_SLOT+index;}

    static Result useHeld(ServerPlayer player,ItemStack bag,InteractionHand hand){
        if(!canAct(player) || !ReservoirData.supported(bag))return Result.NONE;
        ItemStack held=player.getItemInHand(hand);
        if(ReservoirPlugins.accepts(held)){
            int slot=ReservoirPlugins.firstEmpty(bag,player.registryAccess());
            if(slot<0)return Result.NONE;
            ReservoirData.slot(bag,slot,held.split(1),player.registryAccess());
            return Result.PLUGIN_INSTALLED;
        }
        if(!ReservoirContent.bridge.container(held))return Result.NONE;
        ItemStack single=held.copyWithCount(1);
        if(!ReservoirContent.bridge.fill(player.level(),bag,single))return Result.NONE;
        if(held.getCount()==1)player.setItemInHand(hand,single);
        else{
            held.shrink(1);
            if(!player.getInventory().add(single))player.drop(single,false);
        }
        return Result.FILLED;
    }

    static Result control(ServerPlayer player,ItemStack bag,int id,ItemStack carried){
        if(!ReservoirData.supported(bag))return Result.NONE;
        int slot=id-TOGGLE_PLUGIN;
        if(slot>=ReservoirData.FIRST_PLUGIN_SLOT && slot<ReservoirData.storageSlots(bag)){
            if(!carried.isEmpty())return Result.NONE;
            ItemStack plugin=ReservoirData.slot(bag,slot,player.registryAccess());
            if(plugin.getCount()!=1 || !ReservoirData.supported(plugin)
                    || !(plugin.getItem() instanceof ReservoirPluginItem item) || !item.canToggle())return Result.NONE;
            item.toggle(plugin);
            ReservoirData.slot(bag,slot,plugin,player.registryAccess());
            return item.enabled(plugin)?Result.PLUGIN_ENABLED:Result.PLUGIN_DISABLED;
        }
        int cells=ReservoirData.cells(bag);
        if(id<0 || id>=cells)return Result.NONE;
        var before=ReservoirData.read(bag);
        int selected=ReservoirData.selected(before,cells);
        String label=ReservoirData.label(before,id);
        if(!carried.isEmpty() && !ReservoirContent.bridge.label(player.level(),bag,id,carried))return Result.NONE;
        boolean labelChanged=!label.equals(ReservoirData.label(ReservoirData.read(bag),id));
        if(selected==id && !labelChanged)return Result.NONE;
        ReservoirData.select(bag,id);
        return labelChanged?Result.LABELED:Result.SELECTED;
    }

    private ReservoirActions(){}
}
