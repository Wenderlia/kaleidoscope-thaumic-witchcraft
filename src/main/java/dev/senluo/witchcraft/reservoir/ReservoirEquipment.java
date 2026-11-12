package dev.senluo.witchcraft.reservoir;

import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import java.util.function.Function;

/** Current equipment discovery only. Returns the real stack, never a copied or cached inventory. */
public final class ReservoirEquipment {
    private static Function<Player,ItemStack> backSlot=player->ItemStack.EMPTY;

    public static void registerBackSlot(Function<Player,ItemStack> lookup){backSlot=lookup;}

    public static ItemStack worn(Player player){
        ItemStack back=backSlot.apply(player);
        if(ReservoirContent.isBackpack(back))return back;
        ItemStack chest=player.getItemBySlot(EquipmentSlot.CHEST);
        return ReservoirContent.isBackpack(chest)?chest:ItemStack.EMPTY;
    }

    public static ItemStack accessible(Player player){
        ItemStack worn=worn(player);
        if(!worn.isEmpty())return worn;
        if(ReservoirContent.isBackpack(player.getOffhandItem()))return player.getOffhandItem();
        if(ReservoirContent.isBackpack(player.getMainHandItem()))return player.getMainHandItem();
        return ItemStack.EMPTY;
    }

    private ReservoirEquipment(){}
}
