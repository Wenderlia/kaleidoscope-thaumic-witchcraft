package dev.senluo.witchcraft.reservoir;

import net.minecraft.core.HolderLookup;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** Bounded installed-item dispatch. No world scan, duplicate-type restriction or shared balance. */
public final class ReservoirPlugins {
    private static final Set<UUID> EXECUTING=new HashSet<>();
    private ReservoirPlugins(){}
    public static boolean accepts(ItemStack stack){return stack.getItem() instanceof ReservoirPluginItem;}
    public static int firstEmpty(ItemStack bag,HolderLookup.Provider registries){
        for(int slot=ReservoirData.FIRST_PLUGIN_SLOT;slot<ReservoirData.storageSlots(bag);slot++)
            if(ReservoirData.slot(bag,slot,registries).isEmpty())return slot;
        return -1;
    }
    public static void tick(ServerPlayer player,ItemStack bag){
        tick(player,bag,ReservoirPluginItem.Phase.AFTER_PLAYER);
    }
    public static void tick(ServerPlayer player,ItemStack bag,ReservoirPluginItem.Phase phase){
        if(!ReservoirData.supported(bag) || !EXECUTING.add(player.getUUID()))return;
        try{
            for(int slot=ReservoirData.FIRST_PLUGIN_SLOT;slot<ReservoirData.storageSlots(bag);slot++){
                if(ReservoirEquipment.worn(player)!=bag || !player.isAlive() || player.isSpectator()
                        || dev.senluo.witchcraft.ritual.OwnershipRitual.frozen(player))break;
                ItemStack plugin=ReservoirData.slot(bag,slot,player.registryAccess());
                if(plugin.getCount()==1 && ReservoirData.supported(plugin)
                        && plugin.getItem() instanceof ReservoirPluginItem item && item.phase()==phase && item.enabled(plugin))
                    item.tickInstalled(player,bag,slot,plugin);
            }
        }finally{EXECUTING.remove(player.getUUID());}
    }
}
