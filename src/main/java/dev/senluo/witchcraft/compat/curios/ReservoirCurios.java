package dev.senluo.witchcraft.compat.curios;

import dev.senluo.witchcraft.reservoir.ReservoirContent;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import top.theillusivec4.curios.api.CuriosApi;
import top.theillusivec4.curios.api.type.capability.ICurioItem;

public final class ReservoirCurios {
    public static void register(IEventBus bus){
        bus.addListener((FMLCommonSetupEvent e)->e.enqueueWork(()->{
            CuriosApi.registerCurio(ReservoirContent.BACKPACK.get(),new ICurioItem(){});
            CuriosApi.registerCurio(ReservoirContent.SILVER_BACKPACK.get(),new ICurioItem(){});
            ReservoirContent.backSlot=p->CuriosApi.getCuriosInventory(p).map(inv->{
                var handler=inv.getCurios().get("back");if(handler==null)return ItemStack.EMPTY;
                var stacks=handler.getStacks();var active=handler.getActiveStates();
                for(int i=0;i<stacks.getSlots();i++)if((i>=active.size() || active.get(i)) && ReservoirContent.isBackpack(stacks.getStackInSlot(i)))return stacks.getStackInSlot(i);
                return ItemStack.EMPTY;
            }).orElse(ItemStack.EMPTY);
        }));
    }
    private ReservoirCurios(){}
}
