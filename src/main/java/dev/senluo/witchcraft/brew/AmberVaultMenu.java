package dev.senluo.witchcraft.brew;

import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/** Uses the vanilla chest screen and slot protocol, with authoritative take-only slots. */
final class AmberVaultMenu extends ChestMenu {
    private final AmberVaultContents contents;
    AmberVaultMenu(int id, Inventory player, AmberVaultContents contents) {
        super(switch(contents.getContainerSize()){case 18->MenuType.GENERIC_9x2;case 27->MenuType.GENERIC_9x3;default->MenuType.GENERIC_9x6;},
                id,player,contents,contents.getContainerSize()/9);
        this.contents=contents;
        for(int i=0;i<contents.getContainerSize();i++) {
            Slot old=slots.get(i);
            Slot replacement=new Slot(contents,i,old.x,old.y) {
                @Override public boolean mayPlace(ItemStack stack){return contents.canPlaceItem(getContainerSlot(),stack);}
            };
            replacement.index=i;slots.set(i,replacement);
        }
    }
    @Override public ItemStack quickMoveStack(net.minecraft.world.entity.player.Player player,int index){
        if(contents.takeOnly && index>=contents.getContainerSize())return ItemStack.EMPTY;
        return super.quickMoveStack(player,index);
    }
}
