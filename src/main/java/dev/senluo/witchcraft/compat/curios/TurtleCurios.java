package dev.senluo.witchcraft.compat.curios;

import dev.senluo.witchcraft.accessory.AccessoryContent;
import dev.senluo.witchcraft.accessory.TurtleField;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.common.NeoForge;
import top.theillusivec4.curios.api.CuriosApi;
import top.theillusivec4.curios.api.event.CurioChangeEvent;
import top.theillusivec4.curios.api.type.capability.ICurioItem;

/** Loaded only when Curios is present; the item and movement service have no Curios class references. */
public final class TurtleCurios {
    public static void register(IEventBus bus) {
        bus.addListener((FMLCommonSetupEvent event) -> event.enqueueWork(() -> {
            CuriosApi.registerCurio(AccessoryContent.TURTLE_RUNE.get(), new ICurioItem() {});
            TurtleField.equipmentQuery(TurtleCurios::isWearing);
        }));
        NeoForge.EVENT_BUS.addListener((CurioChangeEvent event) -> {
            if (event.getEntity() instanceof Player player) TurtleField.invalidate(player.level());
        });
    }

    private static boolean isWearing(Player player) {
        return CuriosApi.getCuriosInventory(player).map(inventory -> {
            // Curios returns obsolete charm-slot items via its normal invalid-stack recovery.
            // Only functional rune slots count; a resource-pack icon or cosmetic slot cannot enable it.
            var handler = inventory.getCurios().get("rune");
            if (handler == null) return false;
            var stacks = handler.getStacks();
            var active = handler.getActiveStates();
            for (int slot = 0; slot < stacks.getSlots(); slot++) {
                if ((slot >= active.size() || active.get(slot))
                        && stacks.getStackInSlot(slot).is(AccessoryContent.TURTLE_RUNE.get())) return true;
            }
            return false;
        }).orElse(false);
    }

    private TurtleCurios() {}
}
