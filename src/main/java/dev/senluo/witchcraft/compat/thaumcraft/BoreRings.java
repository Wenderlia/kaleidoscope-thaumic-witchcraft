package dev.senluo.witchcraft.compat.thaumcraft;

import dev.senluo.witchcraft.ritual.RingBonus;
import dev.senluo.witchcraft.ritual.BoreToolWear;
import dev.senluo.witchcraft.ritual.RitualPlane;
import dev.senluo.witchcraft.time.WorkSources;
import dev.tc4port.thaumcraft.api.ThaumcraftContent;
import dev.tc4port.thaumcraft.api.event.ArcaneBoreEvent;
import dev.tc4port.thaumcraft.api.event.ArcaneBoreOperationEvent;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.TriState;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.level.block.entity.BlockEntity;

/** Public operation policies plus addon-owned work; no implementation injected into the host. */
public final class BoreRings {
    private static final RingBonus WORK = RingBonus.multiplicative(1.25, 1.3, 1.4, 3);
    private static final RingBonus RANGE = RingBonus.multiplicative(1.4, 1.5, 1.6, 3);
    private static final RingBonus WEAR = RingBonus.multiplicative(2, 2, 2, 8);

    public static void register(net.neoforged.bus.api.IEventBus bus) {
        BoreStorage.register(bus);
    }

    public static State state(BlockEntity bore) { return bore.getData(BoreStorage.RUNTIME); }

    private static State state(Object view) {
        return view instanceof BlockEntity bore && !bore.isRemoved() ? state(bore) : null;
    }

    public static void register() {
        WorkSources.registerProcess(ThaumcraftContent.blockEntity("arcane_bore"), new WorkSources.Process<>() {
            public double price(ServerLevel level, BlockEntity bore) { return WorkSources.UPDATE_PRICE; }
            public void tick(ServerLevel level, BlockEntity bore, Runnable nativeTick) {
                state(bore).tick(bore, nativeTick);
            }
        });
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST, true, (ArcaneBoreEvent.Harvest event) ->
                BoreToolWear.expectHarvest(event.view(), event.targetLevel(), event.target(), () -> !event.isCanceled()));
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST, (ArcaneBoreOperationEvent.Scan event) ->
                BoreToolWear.nextTarget(event.view()));
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST, (ArcaneBoreEvent.Output event) ->
                BoreToolWear.nextTarget(event.view()));
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST, true, (PlayerInteractEvent.LeftClickBlock event) -> {
            if (BoreToolWear.active() && event.getAction() == PlayerInteractEvent.LeftClickBlock.Action.START
                    && event.getEntity() instanceof ServerPlayer player)
                BoreToolWear.startHarvest(player, event.getPos(),
                        () -> !event.isCanceled() && event.getUseItem() != TriState.FALSE);
        });
        // Compose the host's final delay, leaving later listeners free to veto or adjust it.
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST, BoreRings::target);
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, BoreRings::scan);
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, BoreRings::recharge);
        BoreCarts.register();
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, true, (ArcaneBoreEvent.Harvest event) -> {
            State state = state(event.view());
            if (state != null && state.excavation != null && event.view() instanceof BlockEntity bore)
                state.excavation.harvest(event, bore, state);
        });
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, (ArcaneBoreEvent.Harvested event) -> {
            State state = state(event.view());
            if (state != null && state.excavation != null && event.view() instanceof BlockEntity bore)
                state.excavation.harvested(event, bore, state.mine.samples);
        });
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, (ArcaneBoreEvent.Vacuum event) -> {
            State state = state(event.view());
            if (state != null && state.excavation != null) state.excavation.vacuum(event);
        });
    }

    private static void scan(ArcaneBoreOperationEvent.Scan event) {
        State state = state(event.view());
        if (state != null) { state.scan(event); state.scanned = event; }
    }

    private static void target(ArcaneBoreEvent.Target event) {
        State state = state(event.view());
        if (state != null) { state.target(event); state.selected = event; }
    }

    private static void recharge(ArcaneBoreOperationEvent.Recharge event) {
        State state = state(event.view());
        if (state != null) state.recharge(event);
    }

    /** Owned by the live bore, without a global index, per-tick scanning, or saved derived state. */
    public static final class State {
        private int mask;
        private double work = 1, range = 1;
        private BoreToolWear toolWear;
        private int wearDivisor = 1;
        private boolean running;
        private boolean nativeIdle;
        private ArcaneBoreOperationEvent.Scan scanned;
        private ArcaneBoreEvent.Target selected;
        private BoreStorage.Account account;
        private DeepMineStore.Mine mine;
        private BoreExcavation excavation;
        private int scanRadius, scanDepth;

        BoreStorage.Account account(BlockEntity bore) {
            if (account == null) account = BoreStorage.get(bore);
            return account;
        }

        void resetRepairClock() { if (account != null) account.repair.resetClock(); }

        public int displayedRadius(dev.tc4port.thaumcraft.api.machine.ArcaneBoreView view) {
            return scanRadius > 0 ? scanRadius : radius(view.radius(),
                    view.radius() - (int) HostParameters.value("machines/arcane_bore/base_radius"));
        }

        double work() { return work; }
        int depth() { return scanDepth > 0 ? scanDepth : Math.clamp(Math.round(
                HostParameters.value("machines/arcane_bore/search_depth") * work), 1, 256); }

        private void tick(BlockEntity bore, Runnable nativeTick) {
            if (running) return;
            if (!(bore instanceof Container inventory)) { nativeTick.run(); return; }
            // Actor name is part of ArcaneBoreEvent's public harvest contract; no private UUID/field access.
            if (toolWear == null) toolWear = new BoreToolWear(bore, inventory, 1, "FakeThaumcraftBore", () -> wearDivisor);
            running = true;
            try {
                scanned = null; selected = null;
                if (bore.getLevel() instanceof ServerLevel level) {
                    int actual = RitualPlane.find(level, bore.getBlockPos()).map(p -> p.rings().completeMask()).orElse(0);
                    if (actual != mask) {
                        setMask(actual);
                        scanRadius = scanDepth = 0;
                    }
                    var currentMine = DeepDrilling.mine(level, bore);
                    if (mine != currentMine) {
                        if (mine != null) mine.detach(bore);
                        mine = currentMine;
                        excavation = null;
                    }
                    if (mine != null) {
                        excavation = mine.bind(bore);
                        excavation.before(level, bore, this, mine.samples);
                    }
                    var requested = dev.tc4port.thaumcraft.api.machine.ArcaneBoreApi
                            .requestedTarget(level, bore.getBlockPos()).orElse(null);
                    if (requested != null && (excavation == null || !excavation.owns(requested))) nativeIdle = false;
                }
                toolWear.during(nativeTick);
                // isWorking() retains the last dig position in TC. Observe completed public operations instead.
                // On reload, wait for a fresh Scan before submitting addon targets.
                if (scanned != null) {
                    nativeIdle = true;
                    scanRadius = scanned.radius(); scanDepth = scanned.searchDepth();
                }
                if (selected != null && selected.outcome() == ArcaneBoreEvent.Target.Outcome.DIG) nativeIdle = false;
                if (!bore.isRemoved() && bore.getLevel() instanceof ServerLevel level) {
                    if (excavation != null)
                        excavation.after(level, bore, this, mine.samples,
                                nativeIdle && DeepDrilling.mine(level, bore) == mine);
                    BoreRepair.afterNative(level, bore, inventory, mask);
                }
            } finally { running = false; }
        }

        public int mask() { return mask; }

        /** Shared by the operation and the read-only displayed radius. */
        public int radius(int base, int focusRadius) {
            if (mask == 0) return base;
            int limit = (int) Math.min(Integer.MAX_VALUE, 32L + Math.max(0, focusRadius));
            return Math.clamp(Math.round(base * range), 1, limit);
        }

        private void scan(ArcaneBoreOperationEvent.Scan event) {
            if (mask == 0) return;
            event.setRadius(radius(event.radius(), event.focusRadius()));
            event.setSearchDepth(Math.clamp(Math.round(event.searchDepth() * work), 1, 256));
        }

        public void setMask(int mask) {
            this.mask = mask & 7;
            work = WORK.resolve(1, this.mask);
            range = RANGE.resolve(1, this.mask);
            wearDivisor = (int) WEAR.resolve(1, this.mask);
        }

        private void target(ArcaneBoreEvent.Target event) {
            if (mask == 0 || event.outcome() == ArcaneBoreEvent.Target.Outcome.SKIP) return;
            // The quote already includes hardness, upgrades and any lack-of-energy penalty.
            // Scale that quote once; do not reconstruct the host's private pricing algorithm.
            event.setDigDelay(Math.clamp((long) Math.ceil(event.digDelay() / work), 0, Integer.MAX_VALUE));
        }

        private void recharge(ArcaneBoreOperationEvent.Recharge event) {
            if (mask == 0) return;
            boolean vis = event.source() == ArcaneBoreOperationEvent.Recharge.Source.VIS_NETWORK;
            // The public contract uses integer conversion units. Approximation is intentional;
            // native settlement still owns paid energy, float credit and listener failure handling.
            // All ring products have at most three decimal places. Integer rounding keeps a
            // product such as 125 * 1.3 * 1.4 at 228 instead of losing the half to float noise.
            long factor = Math.round(work * 1_000);
            long numerator = vis ? (long) event.conversion() * 1_000 : event.conversion() * factor;
            long denominator = vis ? factor : 1_000;
            long conversion = (numerator + denominator / 2) / denominator;
            event.setConversion(Math.clamp(conversion, vis ? 1 : 0, vis ? 1_000_000 : 10_000));
        }
    }

    private BoreRings() {}
}
