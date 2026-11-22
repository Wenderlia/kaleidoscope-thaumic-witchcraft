package dev.senluo.witchcraft.compat.thaumcraft;

import com.mojang.authlib.GameProfile;
import dev.senluo.witchcraft.reservoir.*;
import dev.tc4port.thaumcraft.api.Registration;
import dev.tc4port.thaumcraft.api.aspect.VisAction;
import dev.tc4port.thaumcraft.api.aspect.AspectId;
import dev.tc4port.thaumcraft.api.aspect.BuiltinAspects;
import dev.tc4port.thaumcraft.api.vis.VisChangePlan;
import dev.tc4port.thaumcraft.api.aspect.VisCost;
import dev.tc4port.thaumcraft.api.wand.*;
import dev.tc4port.thaumcraft.api.wand.pipeline.*;
import java.util.*;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Actual charging and native Vis callbacks; no reflection or replacement charging algorithm. */
@PrefixGameTestTemplate(false)
public final class ReservoirChargingGameTests {
    private static final long CENTIVIS = 10_000;
    private static final String AER = "thaumcraft:aer", IGNIS = "thaumcraft:ignis";

    private static final AspectId CALIBRATION = AspectId.parse("kaleidoscope_witchcraft:calibration");

    public static void registerTools(net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent event) {
        var tools = new net.minecraft.world.item.Item[]{net.minecraft.world.item.Items.GOLDEN_HOE, net.minecraft.world.item.Items.IRON_HOE};
        event.registerItem(WandCapabilities.TOOL, (stack, side) -> new WandToolEndpoint(stack), tools);
        event.registerItem(dev.tc4port.thaumcraft.api.vis.VisStorageCapabilities.ITEM,
                (stack, side) -> new WandVisStorage(stack), tools);
    }

    private static final class Setup {
        final FakePlayer player;
        final ItemStack bag = new ItemStack(ReservoirContent.BACKPACK.get());
        final ItemStack wand = WandApi.create(WandMaterialId.WOOD, WandMaterialId.IRON);
        final ReservoirWandCharging charging = new ReservoirWandCharging();
        final Set<ItemStack> wands = Collections.newSetFromMap(new IdentityHashMap<>());
        final int sourceTicks;
        final long conversionTick;

        Setup(GameTestHelper h, String aspect, int amount, long paidAer) {
            player = new FakePlayer(h.getLevel(), new GameProfile(UUID.randomUUID(), "reservoir-charge"));
            player.setGameMode(GameType.SURVIVAL);
            player.setItemSlot(EquipmentSlot.CHEST, bag);
            player.getInventory().selected = 0;
            player.getInventory().setItem(0, wand);
            wands.add(wand);
            ReservoirData.cell(bag, 0, aspect, amount);
            var device = new ItemStack(ReservoirContent.CONVERTER.get());
            ReservoirData.credit(device, Map.of(BuiltinAspects.AER.value(), paidAer));
            ReservoirData.slot(bag, ReservoirData.FIRST_PLUGIN_SLOT, device, player.registryAccess());
            sourceTicks = ReservoirContent.CONVERTER.get().throughput().sourceTicks();
            conversionTick = h.getLevel().getGameTime() + sourceTicks;
        }

        ItemStack device() {
            return ReservoirData.slot(bag, ReservoirData.FIRST_PLUGIN_SLOT, player.registryAccess());
        }

        void pulse() {
            charging.charge(player, bag, ReservoirData.FIRST_PLUGIN_SLOT, device());
        }

        ItemStack otherWand() {
            var other = WandApi.create(WandMaterialId.WOOD, WandMaterialId.IRON);
            player.getInventory().setItem(1, other);
            wands.add(other);
            return other;
        }
    }

    private static Registration processor(Setup s, WandPipelineHandler<WandVisStages.Change, VisChangePlan> handler) {
        return WandPipelineApi.aroundAll(ResourceLocation.fromNamespaceAndPath("kaleidoscope_witchcraft",
                "reservoir_test_" + UUID.randomUUID()), WandVisStages.CHANGE, 10_000, (context, next) -> {
            if (context.player() != s.player || !s.wands.contains(context.carrier())
                    || context.action() != VisAction.EXECUTE || !context.request().operation().equals(WandVisStages.INSERT))
                return next.proceed(context);
            // Keep the initial paid balance until conversion is also due, without changing config or clocks.
            if (s.player.serverLevel().getGameTime() < s.conversionTick) return VisChangePlan.none();
            return handler.handle(context, next);
        });
    }

    private static VisChangePlan oneCentivis(VisChangePlan plan) {
        var amounts = new LinkedHashMap<AspectId, Integer>();
        for (var channel : plan.deltasCentivis().keySet()) if (plan.delta(channel) > 0) amounts.put(channel, 1);
        return new VisChangePlan(amounts, amounts, plan.commitActions());
    }

    private static int vis(ItemStack wand, AspectId channel) {
        return WandCharging.storage(wand).snapshot().currentCentivis().getOrDefault(channel, 0);
    }

    private static int capacity(ItemStack wand, AspectId channel) {
        return WandCharging.storage(wand).snapshot().capacityCentivis().getOrDefault(channel, 0);
    }

    private static void fill(Setup s, ItemStack wand, AspectId channel) {
        WandCharging.insert(WandCharging.storage(wand), s.player, VisCost.ofCentivis(channel, capacity(wand, channel)), plan -> true);
    }

    private static void throughFirstConversion(GameTestHelper h, Setup s, Registration registration, Runnable assertions) {
        try { s.pulse(); }
        catch (RuntimeException | Error failure) { registration.close(); throw failure; }
        // charge() deliberately resets its schedule after skipped ticks; exercise its normal heartbeat.
        for (int delay = 1; delay <= s.sourceTicks; delay++) {
            boolean last = delay == s.sourceTicks;
            h.runAfterDelay(delay, () -> {
                try {
                    s.pulse();
                    if (last) {
                        try { assertions.run(); h.succeed(); }
                        finally { registration.close(); }
                    }
                } catch (RuntimeException | Error failure) { registration.close(); throw failure; }
            });
        }
    }

    @GameTest(templateNamespace="kaleidoscope_witchcraft", template="empty")
    public static void energizedNodeUsesDeclaredTypesAndSettlesRealSurplusOnce(GameTestHelper h) {
        var level = h.getLevel();
        var pos = h.absolutePos(new net.minecraft.core.BlockPos(1, 2, 1));
        level.setBlock(pos, dev.tc4port.thaumcraft.api.ThaumcraftContent.block("energized_aura_node").defaultBlockState(), 3);
        var node = level.getBlockEntity(pos);
        var types = dev.tc4port.thaumcraft.api.vis.VisTypes.block(node);
        var output = level.getCapability(dev.tc4port.thaumcraft.api.node.VisCapabilities.SOURCE, pos, null);
        h.assertTrue(types.equals(List.of(CALIBRATION, BuiltinAspects.AER)) && output instanceof NodeOutput,
                "The block declaration and native wireless source are active");
        var produced = new HashMap<AspectId, java.math.BigInteger>();
        produced.put(CALIBRATION, java.math.BigInteger.valueOf(7));
        NodeOutput.publish(node, types, produced);
        h.assertTrue(output.takeVis(CALIBRATION, 20, VisAction.SIMULATE, 2) == 5
                && output.takeVis(CALIBRATION, 20, VisAction.SIMULATE, 0) == 7,
                "Simulation subtracts reservations without spending");
        h.assertTrue(output.takeVis(CALIBRATION, 4, VisAction.EXECUTE, 0) == 4
                && output.takeVis(CALIBRATION, 20, VisAction.EXECUTE, 0) == 3
                && output.takeVis(CALIBRATION, 1, VisAction.EXECUTE, 0) == 0,
                "The real source pays each centivis only once");
        NodeOutput.clear(node);
        level.removeBlock(pos, false);
        h.succeed();
    }

    @GameTest(templateNamespace="kaleidoscope_witchcraft", template="empty", timeoutTicks=1300)
    public static void converterPaysCustomAspectAcrossReorderedWands(GameTestHelper h) {
        var s = new Setup(h, CALIBRATION.toString(), 1, 0);
        var converter = new ItemStack(ReservoirContent.VOID_CONVERTER.get());
        var first = new ItemStack(net.minecraft.world.item.Items.GOLDEN_HOE);
        var second = new ItemStack(net.minecraft.world.item.Items.IRON_HOE);
        var expectedFirst = List.of(BuiltinAspects.AER, CALIBRATION);
        var expectedSecond = List.of(CALIBRATION, BuiltinAspects.AER);
        h.assertTrue(dev.tc4port.thaumcraft.api.vis.VisTypes.item(converter).equals(expectedSecond)
                && List.copyOf(WandCharging.storage(first).snapshot().capacityCentivis().keySet()).equals(expectedFirst)
                && List.copyOf(WandCharging.storage(second).snapshot().capacityCentivis().keySet()).equals(expectedSecond),
                "Data pack gives the converter and two real capability providers different orders");
        var quote = ReservoirProfile.of(s.bag).conversion(ReservoirContent.VOID_CONVERTER.get().rules()).primalMicrovis();
        ReservoirData.slot(s.bag, ReservoirData.FIRST_PLUGIN_SLOT, converter, s.player.registryAccess());
        s.player.getInventory().setItem(0, first);
        // Leave precisely one centivis of capacity. The rest of the paid batch stays on the converter.
        WandCharging.insert(WandCharging.storage(first), s.player, VisCost.ofCentivis(CALIBRATION, capacity(first, CALIBRATION) - 1), plan -> true);
        int sourceTicks = ReservoirContent.VOID_CONVERTER.get().throughput().sourceTicks();
        int deliveryTicks = ReservoirContent.VOID_CONVERTER.get().throughput().deliveryTicks();
        s.pulse();
        for (int delay = 1; delay <= sourceTicks + deliveryTicks; delay++) {
            int tick = delay;
            h.runAfterDelay(delay, () -> {
                s.pulse();
                if (tick == sourceTicks) {
                    var balance = ReservoirData.balances(s.device()).getOrDefault(CALIBRATION.value(), 0L);
                    h.assertTrue(ReservoirData.amount(s.bag, 0) == 0 && balance == quote - CENTIVIS
                            && vis(first, CALIBRATION) == capacity(first, CALIBRATION) && vis(first, BuiltinAspects.AER) == 0,
                            "Declared compound is a terminal and only the accepted centivis leaves the source");
                    var encoded = (net.minecraft.nbt.CompoundTag)s.device().save(s.player.registryAccess());
                    var restored = ItemStack.parseOptional(s.player.registryAccess(), encoded);
                    ReservoirData.slot(s.bag, ReservoirData.FIRST_PLUGIN_SLOT, restored, s.player.registryAccess());
                    s.player.getInventory().setItem(0, second);
                }
                if (tick == sourceTicks + deliveryTicks) {
                    h.assertTrue(vis(second, CALIBRATION) == (quote - CENTIVIS) / CENTIVIS
                            && vis(second, BuiltinAspects.AER) == 0
                            && ReservoirData.balances(s.device()).getOrDefault(CALIBRATION.value(), 0L) == (quote - CENTIVIS) % CENTIVIS,
                            "A restored ID balance funds the same aspect in a different slot order");
                    h.succeed();
                }
            });
        }
    }

    @GameTest(templateNamespace="kaleidoscope_witchcraft", template="empty")
    public static void converterDeclarationsInvalidateOnlyFutureQuotes(GameTestHelper h) {
        var converter = new ReservoirConversion();
        converter.configure(List.of(CALIBRATION), 1_000_000, 8000);
        h.assertTrue(converter.quote(dev.tc4port.thaumcraft.api.aspect.AspectApi.registry(), CALIBRATION).orElseThrow().equals(Map.of(CALIBRATION.value(), 1_000_000L)),
                "Declared compound is paid at terminal value");
        converter.configure(List.of(BuiltinAspects.AER, BuiltinAspects.TERRA), 1_000_000, 8000);
        h.assertTrue(converter.quote(dev.tc4port.thaumcraft.api.aspect.AspectApi.registry(), CALIBRATION).orElseThrow().equals(Map.of(
                BuiltinAspects.AER.value(), 400_000L, BuiltinAspects.TERRA.value(), 400_000L)),
                "Changing only the declaration recomputes the grant with per-level retention");
        converter.configure(List.of(BuiltinAspects.AER), 1_000_000, 8000);
        h.assertTrue(converter.quote(dev.tc4port.thaumcraft.api.aspect.AspectApi.registry(), CALIBRATION).orElseThrow().equals(Map.of(BuiltinAspects.AER.value(), 400_000L)),
                "An unsupported leaf pays nothing and does not reallocate its share");
        h.succeed();
    }

    @GameTest(templateNamespace="kaleidoscope_witchcraft", template="empty", timeoutTicks=1300)
    public static void reservoirChargingRequotesAfterCommittedSourceReplacement(GameTestHelper h) {
        replacedSource(h, false);
    }

    @GameTest(templateNamespace="kaleidoscope_witchcraft", template="empty", timeoutTicks=1300)
    public static void reservoirChargingRequotesAfterImpureRejectedTransfer(GameTestHelper h) {
        replacedSource(h, true);
    }

    private static void replacedSource(GameTestHelper h, boolean reject) {
        var s = new Setup(h, AER, 1, CENTIVIS);
        boolean[] replaced = {false};
        var registration = processor(s, (context, next) -> {
            if (reject && !replaced[0]) {
                // Deliberately impure third-party planning: rejection is not proof that the source stayed unchanged.
                ReservoirData.cell(s.bag, 0, IGNIS, 1);
                replaced[0] = true;
                return VisChangePlan.none();
            }
            var plan = oneCentivis(next.proceed(context));
            if (!reject && !replaced[0]) return plan.afterCommit(result -> {
                // Legal committed observer changes a different inventory after the native settlement.
                ReservoirData.cell(s.bag, 0, IGNIS, 1);
                replaced[0] = true;
            });
            return plan;
        });
        throughFirstConversion(h, s, registration, () -> {
            h.assertTrue(replaced[0], "The real native transfer reached the source-changing callback");
            h.assertTrue(ReservoirData.amount(s.bag, 0) == 0, "Conversion consumes the replacement source once");
            h.assertTrue(vis(s.wand, BuiltinAspects.AER) * CENTIVIS + ReservoirData.balances(s.device()).getOrDefault(BuiltinAspects.AER.value(), 0L) == CENTIVIS,
                    "Old source quotes cannot mint additional Aer after either success or rejection");
            h.assertTrue(vis(s.wand, BuiltinAspects.IGNIS) > 0 || ReservoirData.balances(s.device()).getOrDefault(BuiltinAspects.IGNIS.value(), 0L) > 0,
                    "The newly observed Ignis source supplies the converted credit");
        });
    }

    @GameTest(templateNamespace="kaleidoscope_witchcraft", template="empty", timeoutTicks=1300)
    public static void reservoirPaidDeliveryIgnoresUnrelatedSourceDemand(GameTestHelper h) {
        var s = new Setup(h, IGNIS, 1, CENTIVIS);
        fill(s, s.wand, BuiltinAspects.AER);
        var other = s.otherWand();
        var registration = processor(s, (context, next) -> oneCentivis(next.proceed(context)));
        throughFirstConversion(h, s, registration, () -> {
            h.assertTrue(vis(other, BuiltinAspects.AER) == 1,
                    "Main-hand room for unconverted Ignis cannot block paid Aer delivery to another wand");
            h.assertTrue(ReservoirData.amount(s.bag, 0) == 0,
                    "Conversion still honors the main hand's separate demand for Ignis");
        });
    }

    @GameTest(templateNamespace="kaleidoscope_witchcraft", template="empty", timeoutTicks=1300)
    public static void reservoirSourceConversionIgnoresUnrelatedPaidBalance(GameTestHelper h) {
        var s = new Setup(h, IGNIS, 1, 2 * CENTIVIS);
        fill(s, s.wand, BuiltinAspects.IGNIS);
        s.otherWand();
        var registration = processor(s, (context, next) -> oneCentivis(next.proceed(context)));
        throughFirstConversion(h, s, registration, () -> {
            h.assertTrue(ReservoirData.amount(s.bag, 0) == 0,
                    "Remaining paid Aer in the main hand cannot hide another wand's source demand for Ignis");
            h.assertTrue(ReservoirData.balances(s.device()).getOrDefault(BuiltinAspects.IGNIS.value(), 0L) > 0,
                    "Converted Ignis remains paid while the second delivery prioritizes the main hand's Aer");
            h.assertTrue(vis(s.wand, BuiltinAspects.AER) == 2,
                    "Both old paid centivis retain normal concentrated delivery priority");
        });
    }

    @GameTest(templateNamespace="kaleidoscope_witchcraft", template="empty", timeoutTicks=1300)
    public static void reservoirLastSourceDeliversItsPaidBalanceImmediately(GameTestHelper h) {
        var s = new Setup(h, AER, 1, 0);
        long quote = ReservoirProfile.of(s.bag).conversion(ReservoirContent.CONVERTER.get().rules()).primalMicrovis();
        throughFirstConversion(h, s, Registration.empty(), () -> {
            h.assertTrue(ReservoirData.amount(s.bag, 0) == 0, "The final source unit is exhausted");
            h.assertTrue(vis(s.wand, BuiltinAspects.AER) == Math.min(capacity(s.wand, BuiltinAspects.AER), quote / CENTIVIS),
                    "A paid conversion delivers in the same pulse even with no source left");
        });
    }

    @GameTest(templateNamespace="kaleidoscope_witchcraft", template="empty")
    public static void reservoirSourceWithdrawalRejectsStaleIdentityAndExhaustion(GameTestHelper h) {
        var bag = new ItemStack(ReservoirContent.BACKPACK.get());
        ReservoirData.cell(bag, 0, AER, 2);
        String observed = ReservoirData.aspect(bag, 0);
        ReservoirData.cell(bag, 0, IGNIS, 2);
        var replacement = bag.copy();
        h.assertTrue(ReservoirData.withdrawFromCell(bag, 0, observed, 1) == 0 && ItemStack.matches(bag, replacement),
                "An old chamber identity cannot consume or overwrite replacement stock");
        h.assertTrue(ReservoirData.withdrawFromCell(bag, 0, IGNIS, 5) == 2 && ReservoirData.amount(bag, 0) == 0,
                "Withdrawal acknowledges only real current stock");
        var exhausted = bag.copy();
        h.assertTrue(ReservoirData.withdrawFromCell(bag, 0, IGNIS, 1) == 0 && ItemStack.matches(bag, exhausted),
                "An exhausted chamber cannot be consumed again or recreated by the old identity");
        h.succeed();
    }

    /** Dispatch through the real pre/post equipment hooks' shared plugin entry, including inactive ticks. */
    private static void wornTicks(GameTestHelper h, List<Setup> setups, int ticks,
                                  Registration cleanup, java.util.function.IntConsumer observe) {
        for (int d = 0; d <= ticks; d++) {
            final int tick = d;
            Runnable step = () -> {
                try {
                    for (var s : setups) {
                        ReservoirPlugins.tick(s.player, s.bag, ReservoirPluginItem.Phase.BEFORE_PLAYER);
                        ReservoirPlugins.tick(s.player, s.bag);
                    }
                    observe.accept(tick);
                    if (tick == ticks) { h.succeed(); cleanup.close(); }
                } catch (RuntimeException | Error failure) { cleanup.close(); throw failure; }
            };
            if (d == 0) step.run(); else h.runAfterDelay(d, step);
        }
    }

    private static ItemStack inventoryPair(Setup s) {
        var other = s.otherWand();
        s.player.getInventory().setItem(0, ItemStack.EMPTY);
        s.player.getInventory().setItem(1, s.wand);
        s.player.getInventory().setItem(2, other);
        return other;
    }

    @GameTest(templateNamespace="kaleidoscope_witchcraft", template="empty", timeoutTicks=1300)
    public static void paidDeliveryUsesRealReceiptsForFallthroughAndRefunds(GameTestHelper h) {
        var hands = new Setup(h, AER, 0, CENTIVIS);
        var off = hands.otherWand();
        hands.player.getInventory().setItem(1, ItemStack.EMPTY);
        hands.player.setItemSlot(EquipmentSlot.OFFHAND, off);
        var early = new Setup(h, AER, 0, CENTIVIS);
        var later = inventoryPair(early);
        var pool = new Setup(h, AER, 0, 4 * CENTIVIS);
        var blockedLast = inventoryPair(pool);
        var partial = new Setup(h, AER, 0, 4 * CENTIVIS);
        var untouched = partial.otherWand();
        partial.player.getInventory().setItem(1, ItemStack.EMPTY);
        partial.player.setItemSlot(EquipmentSlot.OFFHAND, untouched);
        var hook = WandPipelineApi.aroundAll(ResourceLocation.fromNamespaceAndPath("kaleidoscope_witchcraft",
                "receipt_fallback_" + UUID.randomUUID()), WandVisStages.CHANGE, 10_000, (c, next) -> {
            if (c.action() == VisAction.EXECUTE && c.request().operation().equals(WandVisStages.INSERT)) {
                if ((c.player() == hands.player && c.carrier() == hands.wand)
                        || (c.player() == early.player && c.carrier() == early.wand)
                        || (c.player() == pool.player && c.carrier() == blockedLast)) return VisChangePlan.none();
                if (c.player() == partial.player && c.carrier() == partial.wand) return oneCentivis(next.proceed(c));
            }
            return next.proceed(c);
        });
        int period = ReservoirContent.CONVERTER.get().throughput().deliveryTicks();
        wornTicks(h, List.of(hands, early, pool, partial), period, hook, tick -> {
            if (tick != period) return;
            h.assertTrue(vis(off, BuiltinAspects.AER) == 1 && vis(hands.wand, BuiltinAspects.AER) == 0
                    && vis(later, BuiltinAspects.AER) == 1 && vis(early.wand, BuiltinAspects.AER) == 0,
                    "A zero receipt falls through in both priority and inventory order");
            h.assertTrue(vis(pool.wand, BuiltinAspects.AER) == 4 && vis(blockedLast, BuiltinAspects.AER) == 0
                    && ReservoirData.balances(pool.device()).isEmpty(), "A late refusal returns the unpaid share to an earlier recipient");
            h.assertTrue(vis(partial.wand, BuiltinAspects.AER) == 1 && vis(untouched, BuiltinAspects.AER) == 0
                    && ReservoirData.balances(partial.device()).get(BuiltinAspects.AER.value()) == 3 * CENTIVIS,
                    "A partial hand receipt keeps priority and debits only the real unit");
        });
    }

    @GameTest(templateNamespace="kaleidoscope_witchcraft", template="empty", timeoutTicks=1300)
    public static void inventoryWandsShareAcrossUnequalAspectDomains(GameTestHelper h) {
        var first = new Setup(h, AER, 0, 2 * CENTIVIS);
        var second = new Setup(h, AER, 0, 2 * CENTIVIS);
        var full = new Setup(h, AER, 0, 4 * CENTIVIS);
        var b = inventoryPair(first);
        var d = inventoryPair(second);
        var f = inventoryPair(full);
        fill(first, b, BuiltinAspects.IGNIS);
        fill(second, second.wand, BuiltinAspects.IGNIS);
        fill(full, full.wand, BuiltinAspects.IGNIS);
        fill(full, f, BuiltinAspects.IGNIS);
        for (var s : List.of(first, second, full)) {
            var device = s.device();
            ReservoirData.credit(device, Map.of(BuiltinAspects.IGNIS.value(), (s == full ? 40 : 2) * CENTIVIS));
            ReservoirData.slot(s.bag, ReservoirData.FIRST_PLUGIN_SLOT, device, s.player.registryAccess());
        }
        int period = ReservoirContent.CONVERTER.get().throughput().deliveryTicks();
        wornTicks(h, List.of(first, second, full), period, () -> {}, tick -> {
            if (tick != period) return;
            h.assertTrue(vis(first.wand, BuiltinAspects.IGNIS) == 2 && vis(first.wand, BuiltinAspects.AER) == 0
                    && vis(b, BuiltinAspects.AER) == 2 && vis(second.wand, BuiltinAspects.AER) == 2
                    && vis(d, BuiltinAspects.IGNIS) == 2 && vis(d, BuiltinAspects.AER) == 0,
                    "Both slot orders give each wand 2; only the flexible wand receives fire");
            h.assertTrue(vis(full.wand, BuiltinAspects.AER) == 2 && vis(f, BuiltinAspects.AER) == 2
                    && ReservoirData.balances(full.device()).get(BuiltinAspects.IGNIS.value()) == 40 * CENTIVIS,
                    "Unused fire cannot inflate the split of 4 air");
        });
    }

    @GameTest(templateNamespace="kaleidoscope_witchcraft", template="empty", timeoutTicks=1300)
    public static void successfulRemaindersRotateAcrossPaidCycles(GameTestHelper h) {
        var s = new Setup(h, AER, 0, 3 * CENTIVIS);
        var other = inventoryPair(s);
        int period = ReservoirContent.CONVERTER.get().throughput().deliveryTicks();
        wornTicks(h, List.of(s), 2 * period + 1, () -> {}, tick -> {
            if (tick == period) {
                h.assertTrue(vis(s.wand, BuiltinAspects.AER) == 2 && vis(other, BuiltinAspects.AER) == 1,
                        "The first eligible slot gets the odd unit");
                var device = s.device();
                ReservoirData.credit(device, Map.of(BuiltinAspects.AER.value(), 3 * CENTIVIS));
                ReservoirData.slot(s.bag, ReservoirData.FIRST_PLUGIN_SLOT, device, s.player.registryAccess());
            }
            if (tick == 2 * period + 1) {
                h.assertTrue(vis(s.wand, BuiltinAspects.AER) == 3 && vis(other, BuiltinAspects.AER) == 3,
                        "The persisted receipt cursor gives the next odd unit to the other slot");
            }
        });
    }

    @GameTest(templateNamespace="kaleidoscope_witchcraft", template="empty", timeoutTicks=1300)
    public static void threeDifferentWandsRotateTheFinalRemainder(GameTestHelper h) {
        var s = new Setup(h, AER, 0, 0);
        var inventory = s.player.getInventory();
        inventory.setItem(0, ItemStack.EMPTY);
        var templates = new ArrayList<ItemStack>();
        for (int i = 0; i < 3; i++) {
            var wand = WandApi.create(WandMaterialId.WOOD, WandMaterialId.IRON);
            var stock = Map.of(BuiltinAspects.AER, capacity(wand, BuiltinAspects.AER) - (i == 0 ? 1 : 2),
                    BuiltinAspects.IGNIS, capacity(wand, BuiltinAspects.IGNIS) - (i == 0 ? 1 : 0));
            WandCharging.insert(WandCharging.storage(wand), s.player, VisCost.ofCentivis(stock), plan -> true);
            templates.add(wand);
        }
        Runnable refill = () -> {
            for (int i = 0; i < templates.size(); i++) inventory.setItem(i + 1, templates.get(i).copy());
            var device = s.device();
            ReservoirData.credit(device, Map.of(BuiltinAspects.AER.value(), 3 * CENTIVIS, BuiltinAspects.IGNIS.value(), CENTIVIS));
            ReservoirData.slot(s.bag, ReservoirData.FIRST_PLUGIN_SLOT, device, s.player.registryAccess());
        };
        refill.run();
        int period = ReservoirContent.CONVERTER.get().throughput().deliveryTicks();
        wornTicks(h, List.of(s), 3 * (period + 1) - 1, () -> {}, tick -> {
            if ((tick + 1) % (period + 1) != 0) return;
            int round = (tick + 1) / (period + 1) - 1;
            for (int i = 0; i < templates.size(); i++) {
                var wand = inventory.getItem(i + 1);
                var before = templates.get(i);
                int received = vis(wand, BuiltinAspects.AER) - vis(before, BuiltinAspects.AER)
                        + vis(wand, BuiltinAspects.IGNIS) - vis(before, BuiltinAspects.IGNIS);
                h.assertTrue(received == (round == i ? 2 : 1),
                        "The final extra must rotate to each physical slot even when air and fire are rerouted");
            }
            if (round < 2) refill.run();
        });
    }

    @GameTest(templateNamespace="kaleidoscope_witchcraft", template="empty")
    public static void sharedConversionSubgraphKeepsItsDepthInBothOrders(GameTestHelper h) {
        var registry = dev.tc4port.thaumcraft.api.aspect.AspectApi.registry();
        var definitions = new ArrayList<>(registry.definitions().values());
        var sample = registry.get(BuiltinAspects.AER).orElseThrow();
        java.util.function.BiFunction<String, List<AspectId>, dev.tc4port.thaumcraft.api.aspect.AspectDefinition> define =
                (name, parts) -> new dev.tc4port.thaumcraft.api.aspect.AspectDefinition(
                        AspectId.parse("witchcraft_test:" + name), 0x775555, parts, sample.texture(), sample.blendMode(), 0);
        var pad = define.apply("pad", List.of());
        definitions.add(pad);
        var shared = AspectId.parse("thaumcraft:lux");
        var head = shared;
        for (int i = 0; i < 15; i++) {
            var part = define.apply("part_" + i, List.of(head, pad.id()));
            definitions.add(part);
            head = part.id();
        }
        var pricing = new ReservoirConversion();
        for (int order = 0; order < 2; order++) {
            var root = define.apply("root", order == 0 ? List.of(shared, head) : List.of(head, shared));
            var data = new ArrayList<>(definitions);
            data.add(root);
            var snapshot = dev.tc4port.thaumcraft.aspect.AspectRegistrySnapshot.create(order + 1L, data);
            pricing.configure(List.of(BuiltinAspects.AER, BuiltinAspects.IGNIS), 1_000_000, 8_000);
            h.assertTrue(pricing.quote(snapshot, head).isPresent() && pricing.quote(snapshot, root.id()).isEmpty(),
                    "Depth 16 is legal, depth 17 must refuse either ordering of the same root");
        }
        h.succeed();
    }
}
