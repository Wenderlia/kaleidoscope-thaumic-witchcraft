package dev.senluo.witchcraft.compat.thaumcraft;

import net.minecraft.world.level.block.entity.BlockEntity;
import dev.senluo.witchcraft.ritual.RitualContent;
import dev.senluo.witchcraft.ritual.RitualStability;
import dev.tc4port.thaumcraft.api.aspect.AspectId;
import dev.tc4port.thaumcraft.api.event.CrucibleItemEvent;
import dev.tc4port.thaumcraft.api.event.CrucibleProcessEvent;
import dev.tc4port.thaumcraft.api.event.CrucibleWaterEvent;
import dev.tc4port.thaumcraft.api.machine.CrucibleWaterUse;

import java.util.Map;
import java.util.Objects;
import java.util.WeakHashMap;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.common.NeoForge;

/** TC4R 20742 process hooks; ritual identity, resources and scheduling stay in our host. */
final class CrucibleEvents {
    // Server-thread, transient receipts only. Weak keys never keep an unloaded crucible alive.
    // There is no upstream transaction token: nested/replaced quotes are conservatively skipped.
    private static final Map<BlockEntity, WaterPayment> WATER_PAYMENTS = new WeakHashMap<>();

    private record WaterPayment(CrucibleWaterUse use, @Nullable ResourceLocation recipe,
                                @Nullable AspectId aspect, RitualStability.WaterQuote quote) {}

    private CrucibleEvents() {}

    static void register() {
        NeoForge.EVENT_BUS.addListener(CrucibleEvents::intake);
        NeoForge.EVENT_BUS.addListener(CrucibleEvents::overflow);
        NeoForge.EVENT_BUS.addListener(CrucibleEvents::decomposition);
        NeoForge.EVENT_BUS.addListener(CrucibleEvents::quoteWater);
        NeoForge.EVENT_BUS.addListener(CrucibleEvents::drainedWater);
    }

    private static void intake(CrucibleItemEvent.Intake event) {
        // Never take an item already rejected or claimed by another listener.
        if (event.outcome() != CrucibleItemEvent.Intake.Outcome.PASS) return;
        var pot = loadedPot(event.level(), event.crucible());
        if (pot == null) return;
        SteamRituals.accept(pot, event.item()).ifPresent(consumed ->
                event.setOutcome(CrucibleItemEvent.Intake.Outcome.HANDLED));
    }

    private static void overflow(CrucibleProcessEvent.Overflow event) {
        var pot = loadedPot(event.level(), event.crucible());
        if (pot == null) return;
        var profile = SteamRituals.profile(pot);
        if (profile.isEmpty()) {
            // Unknown surroundings pause processing; they do not replace the last known client
            // capacity with the unmodified baseline supplied by this new upstream event.
            EssentiaStores.crucible(pot).ifPresent(view -> event.setCapacity(view.stableCapacity()));
            event.setCanceled(true); return;
        }
        event.setCapacity(profile.get().capacity(event.capacity()));
        // Pause the action, not the displayed capacity. Admission still uses the real ring limit.
        if (SteamRituals.managed(pot)) event.setCanceled(true);
    }

    private static void decomposition(CrucibleProcessEvent.Decomposition event) {
        var pot = loadedPot(event.level(), event.crucible());
        if (pot == null) return;
        if (SteamRituals.managed(pot)) { event.setCanceled(true); return; }
        var profile = SteamRituals.profile(pot);
        if (profile.isEmpty()) { event.setCanceled(true); return; }
        event.setNextDelay(profile.get().nextDelay(event.nextDelay()));
    }

    private static void quoteWater(CrucibleWaterEvent.Quote event) {
        var pot = loadedPot(event.level(), event.crucible());
        if (pot == null) return;
        if (!event.preview()) WATER_PAYMENTS.remove(pot);
        // This API supplies a net cost, not other listeners' modifier provenance. Preserve an
        // already replaced price instead of guessing how to combine its fractional ledger.
        if (event.cost() != event.defaultCost()) return;
        SteamRituals.waterQuote(pot, event.defaultCost()).ifPresent(quote -> {
            event.setCost((int) quote.millibuckets());
            if (!event.preview()) WATER_PAYMENTS.put(pot, new WaterPayment(event.use(),
                    event.recipeId().orElse(null), event.aspect().orElse(null), quote));
        });
        // Neither quote changes inventory, persistent credit, or creates an attachment.
    }

    private static void drainedWater(CrucibleWaterEvent.Drained event) {
        var pot = loadedPot(event.level(), event.crucible());
        if (pot == null) return;
        WaterPayment payment = WATER_PAYMENTS.remove(pot);
        // Equal prices alone do not prove this listener contributed the quoted amount.
        if (payment == null || payment.use() != event.use()
                || !Objects.equals(payment.recipe(), event.recipeId().orElse(null))
                || !Objects.equals(payment.aspect(), event.aspect().orElse(null))
                || event.drained() != event.quotedCost()
                || payment.quote().millibuckets() != event.quotedCost()) return;
        if (pot.getData(RitualContent.RITUAL_ANCHOR).commitWater(payment.quote())) pot.setChanged();
    }

    @Nullable
    private static BlockEntity loadedPot(ServerLevel level, BlockPos pos) {
        return level.hasChunkAt(pos) ? EssentiaStores.pot(level.getBlockEntity(pos)) : null;
    }
}
