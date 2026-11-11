package dev.senluo.witchcraft.compat.thaumcraft;

import net.minecraft.world.level.block.entity.BlockEntity;
import dev.senluo.witchcraft.ritual.*;
import dev.tc4port.thaumcraft.api.aspect.AspectId;

import java.util.Optional;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

/** TC4R vessel adapter. The shared ledger owns identity/receipts; this class owns actual world resources. */
public final class SteamRituals {
    private static final AspectId HERBA = AspectId.parse("herba");
    private SteamRituals() {}
    public static boolean managed(BlockEntity pot) {
        return pot.hasData(RitualContent.RITUAL_ANCHOR) && CrucibleRitualHost.liveCount(pot) > 0;
    }
    /** Local lifecycle check only for marked vessels. Missing resources clear the distinction, not create a new unit. */
    public static void refreshHerba(BlockEntity pot) {
        if (!RitualHerba.marked(pot) || !(pot.getLevel() instanceof ServerLevel level)) return;
        var view = EssentiaStores.crucible(pot).orElse(null);
        if (view == null) return;
        int amount = view.essentiaContents(null).amount(HERBA);
        if (amount <= 0) { RitualHerba.set(pot, false); return; }
        var store = StructuralRitualStore.get(level);
        // An unavailable/unsupported ledger is not proof that the ritual has permanently ended.
        if (!store.supported() || !pot.getData(RitualContent.RITUAL_ANCHOR).supported()) return;
        RitualHerba.set(pot, Math.min(RitualHerba.count(pot), Math.min(amount, CrucibleRitualHost.liveCount(pot))));
    }
    /** Read-only policy snapshot. An unavailable environment is not a zero-point environment. */
    public static Optional<RitualStability.Profile> profile(BlockEntity pot) {
        if (!(pot.getLevel() instanceof ServerLevel level)) return Optional.empty();
        if (pot.hasData(RitualContent.RITUAL_ANCHOR) && !pot.getData(RitualContent.RITUAL_ANCHOR).supported())
            return Optional.empty();
        if (!CrucibleStabilizers.canInspect(level, pot.getBlockPos())) return Optional.empty();
        var found = RitualPlane.find(level, pot.getBlockPos());
        if (found.isEmpty()) return Optional.of(new RitualStability.Profile(RitualRings.Layout.NONE, 0));
        var plane = found.get();
        if (!plane.rings().fullyKnown()) return Optional.empty();
        var points = CrucibleStabilizers.score(level, plane);
        if (points.isEmpty()) return Optional.empty();
        return Optional.of(new RitualStability.Profile(plane.rings(), points.getAsInt()));
    }
    public static int stableCapacity(BlockEntity pot, int base) {
        // Unknown surroundings cannot authorize new intake; existing contents are paused by the event adapter.
        return profile(pot).map(value -> value.capacity(base)).orElse(0);
    }
    static Optional<RitualStability.WaterQuote> waterQuote(BlockEntity pot, int amount) {
        var profile = profile(pot);
        if (profile.isEmpty()) return Optional.empty();
        var data = pot.hasData(RitualContent.RITUAL_ANCHOR) ? pot.getData(RitualContent.RITUAL_ANCHOR) : null;
        if (data != null && !data.supported()) return Optional.empty();
        // With no ring, settle existing prepaid fractions without creating plain-alchemy identities.
        if (profile.get().rings().count() == 0 && (data == null || data.waterCredit() == 0)) return Optional.empty();
        return Optional.of(profile.get().water(Math.max(0, amount), data == null ? 0 : data.waterCredit()));
    }
    /** Item policy called from the native intake event. */
    public static Optional<Boolean> accept(BlockEntity pot, ItemEntity item) {
        return CrucibleRitualHost.accept(pot, item);
    }
    public static void tick(LevelTickEvent.Pre event) { CrucibleRitualHost.tick(event); }
}
