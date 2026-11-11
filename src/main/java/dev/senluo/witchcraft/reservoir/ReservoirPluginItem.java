package dev.senluo.witchcraft.reservoir;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/** A real removable plugin; its handler owns behavior, not the menu or slot number. */
public class ReservoirPluginItem extends Item {
    public enum Phase { BEFORE_PLAYER, AFTER_PLAYER }
    @FunctionalInterface
    public interface Handler {
        void tick(ServerPlayer player,ItemStack backpack,int storageSlot,ItemStack plugin);
    }
    private final Handler handler;
    public ReservoirPluginItem(Properties properties,Handler handler){super(properties);this.handler=handler;}
    public Phase phase(){return Phase.AFTER_PLAYER;}
    public boolean canToggle(){return false;}
    public boolean enabled(ItemStack plugin){return !canToggle() || !ReservoirData.read(plugin).getBoolean("disabled");}
    public void toggle(ItemStack plugin){if(canToggle())ReservoirData.edit(plugin,d->d.putBoolean("disabled",enabled(plugin)));}
    public void tickInstalled(ServerPlayer player,ItemStack bag,int slot,ItemStack plugin){handler.tick(player,bag,slot,plugin);}
}
