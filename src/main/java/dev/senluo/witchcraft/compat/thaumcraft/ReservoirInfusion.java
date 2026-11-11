package dev.senluo.witchcraft.compat.thaumcraft;

import dev.senluo.witchcraft.reservoir.ReservoirContent;
import dev.tc4port.thaumcraft.api.event.InfusionEvent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;

/** Backpack upgrade eligibility and carrier preservation; TC still owns the infusion transaction. */
public final class ReservoirInfusion {
    private static final ResourceLocation SILVER=ReservoirContent.id("silverwood_alchemy_backpack");
    private ReservoirInfusion() {}
    static void start(InfusionEvent.Start event){
        if(!event.isCanceled() && SILVER.equals(event.recipeId())
                && InfusionRings.find(event.level(),event.matrix()).isEmpty())event.setCanceled(true);
    }
    /** Only at a native work pulse, including externally accelerated pulses. Unloaded rings pause. */
    static boolean allowPulse(InfusionEvent.Work event) {
        return !SILVER.equals(event.recipeId()) || InfusionRings.lookup(event.level(), event.matrix()).isPresent();
    }
    static void upgraded(InfusionEvent.Output event){
        ItemStack central=event.central(),output=event.output();
        boolean converter=event.recipeId().equals(ReservoirContent.id("void_essentia_transducer"))
                && central.is(ReservoirContent.CONVERTER.get()) && output.is(ReservoirContent.VOID_CONVERTER.get());
        boolean backpack=SILVER.equals(event.recipeId()) && central.is(ReservoirContent.BACKPACK.get())
                && output.is(ReservoirContent.SILVER_BACKPACK.get());
        if(!converter && !backpack)return;
        // A fresh output retains its new item defaults and receives every source override/removal.
        // Existing cell/slot numbers, plugin paid balances, names and gas debt remain unchanged.
        output.applyComponents(central.getComponentsPatch());event.setOutput(output);
    }
}
