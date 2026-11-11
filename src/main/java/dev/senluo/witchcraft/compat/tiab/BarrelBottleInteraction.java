package dev.senluo.witchcraft.compat.tiab;

import com.github.ysbbbbbb.kaleidoscopetavern.block.brew.BarrelBlock;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.TriState;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import org.mangorage.tiab.common.api.impl.ITiabItem;

/** Loaded only with both mods; route bottle use through the normal client/server item path. */
public final class BarrelBottleInteraction {
    private BarrelBottleInteraction() {}

    public static void register() {
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, BarrelBottleInteraction::interact);
    }

    private static void interact(PlayerInteractEvent.RightClickBlock event) {
        if (event.isCanceled() || event.getEntity().isSpectator()
                || !(event.getItemStack().getItem() instanceof ITiabItem)
                || !(event.getLevel().getBlockState(event.getPos()).getBlock() instanceof BarrelBlock)) return;
        // Leave item permission, cooldowns, useOn, charging and accelerator creation to their owners.
        // Never un-cancel protection events or force useItem to TRUE.
        event.setUseBlock(TriState.FALSE);
    }
}
