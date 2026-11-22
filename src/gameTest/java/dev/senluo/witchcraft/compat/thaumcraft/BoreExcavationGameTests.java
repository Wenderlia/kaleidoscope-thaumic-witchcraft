package dev.senluo.witchcraft.compat.thaumcraft;

import dev.tc4port.thaumcraft.api.ThaumcraftContent;
import dev.tc4port.thaumcraft.api.event.ArcaneBoreEvent;
import dev.tc4port.thaumcraft.api.machine.ArcaneBoreApi;
import dev.tc4port.thaumcraft.api.machine.ArcaneBoreTarget;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Public native dig callbacks exercise the actual addon receipt and cancellation protocol. */
@PrefixGameTestTemplate(false)
public final class BoreExcavationGameTests {
    @GameTest(templateNamespace="kaleidoscope_witchcraft", template="empty", timeoutTicks=600, batch="bore-repair-price")
    public static void boreRingRepairsWithSavedBalancesAtNativePrices(GameTestHelper h) {
        var level = h.getLevel();
        var plane = h.absolutePos(new BlockPos(6, 3, 6));
        for (var offset : dev.senluo.witchcraft.ritual.RitualShape.SMALL.marks()) {
            var at = plane.offset(offset);
            level.setBlock(at, Blocks.STONE.defaultBlockState(), 3);
            level.setBlock(at.above(), dev.senluo.witchcraft.ritual.RitualContent.CHALK_SURFACE.get().defaultBlockState(), 3);
        }
        var pos = plane.above();
        level.setBlock(plane, ThaumcraftContent.block("arcane_bore_base").defaultBlockState(), 3);
        level.setBlock(pos, ThaumcraftContent.block("arcane_bore").defaultBlockState(), 3);
        var bore = level.getBlockEntity(pos);
        var inventory = (Container)bore;
        var tool = new ItemStack(ThaumcraftContent.item("void_pickaxe"));
        int damage = 25;
        tool.setDamageValue(damage);
        inventory.setItem(1, tool);
        bore.setChanged();
        var aspects = dev.tc4port.thaumcraft.api.aspect.AspectQueryApi.item(tool);
        var leaves = dev.tc4port.thaumcraft.api.aspect.AspectApi.reduceToPrimals(aspects);
        int[] price = RepairPrice.CURRENCIES.stream().mapToInt(id -> (int)Math.sqrt(2.0 * leaves.amount(id))).toArray();
        h.assertTrue(java.util.Arrays.stream(price).anyMatch(x -> x > 0), "The native void pickaxe has a payable price");
        var account = BoreRings.state(bore).account(bore).repair;
        var saved = new net.minecraft.nbt.CompoundTag();
        int[] balances = new int[price.length];
        java.util.Arrays.fill(balances, 1_000);
        saved.putIntArray("Centivis", balances);
        account.deserializeNBT(level.registryAccess(), saved);
        h.succeedWhen(() -> {
            var actual = account.serializeNBT(level.registryAccess()).getIntArray("Centivis");
            int units = 0;
            for (int i = 0; i < price.length; i++) {
                if (price[i] > 0) { units = (balances[i] - actual[i]) / price[i]; break; }
            }
            h.assertTrue(units > 0 && inventory.getItem(1).getDamageValue() <= damage - units, "Waiting for the actual bore repair pulse");
            for (int i = 0; i < price.length; i++)
                h.assertTrue(balances[i] - actual[i] == units * price[i], "Only the six quoted amounts leave the saved account");
        });
    }

    private static final class Setup implements AutoCloseable {
        final ServerLevel level;
        final BlockEntity bore;
        final BlockPos original, replacement;
        final DeepMines sessions;
        final UUID id = UUID.randomUUID();
        final BoreExcavation work;
        final ArcaneBoreTarget receipt;
        final ItemStack tool;

        Setup(GameTestHelper h) {
            level = h.getLevel();
            var pos = h.absolutePos(new BlockPos(4, 3, 4));
            level.setBlock(pos.below(), ThaumcraftContent.block("arcane_bore_base").defaultBlockState(), 3);
            level.setBlock(pos, ThaumcraftContent.block("arcane_bore").defaultBlockState(), 3);
            level.setBlock(pos.east(), Blocks.REDSTONE_BLOCK.defaultBlockState(), 3);
            bore = level.getBlockEntity(pos);
            h.assertTrue(bore instanceof Container, "The fixture has a powered, assembled native bore");
            var inventory = (Container) bore;
            inventory.setItem(0, new ItemStack(ThaumcraftContent.item("focus_excavation")));
            inventory.setItem(1, new ItemStack(Items.DIAMOND_PICKAXE));
            bore.setChanged();
            tool = inventory.getItem(1).copy();
            original = pos.above(2);
            replacement = original.east(2);
            level.setBlock(original, Blocks.STONE.defaultBlockState(), 3);
            level.setBlock(replacement, Blocks.STONE.defaultBlockState(), 3);
            sessions = DeepMines.get(level);
            work = sessions.ensure(id).bind(bore);
            try {
                h.assertTrue(work.submit(level, bore, level,
                                new MixedMine.Target(0L, original, level.getBlockState(original), original.getY(), 0), original),
                        "The addon owns a real queued native request before replacement begins");
                receipt = ArcaneBoreApi.requestedTarget(level, pos).orElseThrow();
                h.assertTrue(work.owns(receipt), "The addon retains the exact native request identity");
            } catch (RuntimeException | Error failure) {
                close();
                throw failure;
            }
        }

        @Override public void close() {
            sessions.remove(id);
            work.retryCancellation(bore);
            ArcaneBoreApi.cancelDig(level, bore.getBlockPos());
        }
    }

    @GameTest(templateNamespace="kaleidoscope_witchcraft", template="empty")
    public static void boreCancellationSurvivesARejectedExternalReplacement(GameTestHelper h) {
        externalReplacement(h, true);
    }

    @GameTest(templateNamespace="kaleidoscope_witchcraft", template="empty")
    public static void boreCancellationPreservesASuccessfulExternalReplacement(GameTestHelper h) {
        externalReplacement(h, false);
    }

    private static void externalReplacement(GameTestHelper h, boolean reject) {
        try (var s = new Setup(h)) {
            boolean[] called = {false};
            Consumer<ArcaneBoreEvent.Target> revoke = event -> {
                if (event.view() != s.bore || !event.target().equals(s.replacement)) return;
                called[0] = true;
                s.sessions.remove(s.id);
                h.assertTrue(s.sessions.find(s.id) == null, "Revocation removes the session and its bindings");
                h.assertTrue(s.work.owns(s.receipt) && !s.work.retryCancellation(s.bore),
                        "An external native busy scope cannot discard an unacknowledged cancellation receipt");
                if (reject) event.setOutcome(ArcaneBoreEvent.Target.Outcome.SKIP);
            };
            NeoForge.EVENT_BUS.addListener(revoke);
            var external = new ArcaneBoreTarget(s.level, s.replacement);
            try {
                h.assertTrue(ArcaneBoreApi.dig(s.level, s.bore.getBlockPos(), external, 0) == !reject,
                        "The real native Target event controls replacement admission");
            } finally {
                NeoForge.EVENT_BUS.unregister(revoke);
            }
            h.assertTrue(called[0], "The external request entered its real synchronous Target callback");
            var current = ArcaneBoreApi.requestedTarget(s.level, s.bore.getBlockPos()).orElseThrow();
            h.assertTrue(current == (reject ? s.receipt : external),
                    "A rejected replacement retains the old target; an accepted one owns its new receipt");
            h.assertTrue(s.work.retryCancellation(s.bore), "The next non-busy boundary resolves the old cancellation");
            var remaining = ArcaneBoreApi.requestedTarget(s.level, s.bore.getBlockPos()).orElse(null);
            h.assertTrue(reject ? remaining == null : remaining == external,
                    "Only the revoked addon request is canceled; another caller's accepted target survives");
            h.assertTrue(!s.work.owns(s.receipt), "The acknowledged cancellation releases its local receipt");
            h.assertTrue(s.level.getBlockState(s.original).is(Blocks.STONE)
                            && ItemStack.matches(s.tool, ((Container) s.bore).getItem(1)),
                    "Canceling an unharvested request neither mines its block nor charges its pickaxe");
        }
        h.succeed();
    }
}
