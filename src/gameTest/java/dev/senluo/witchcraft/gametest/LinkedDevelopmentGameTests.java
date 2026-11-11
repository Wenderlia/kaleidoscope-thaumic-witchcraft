package dev.senluo.witchcraft.gametest;

import dev.senluo.witchcraft.ritual.TreeBond;
import dev.senluo.witchcraft.time.*;
import java.util.UUID;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@PrefixGameTestTemplate(false)
public final class LinkedDevelopmentGameTests {
    @GameTest(templateNamespace="kaleidoscope_witchcraft", template="empty", timeoutTicks=60)
    public static void linkedFruitClockKeepsFeesAndCombinesAccelerationOnce(GameTestHelper h) {
        LivingEntity core = TreeGameTests.plant(h, true);
        FruitEntity fruit = TreeGameTests.fruit(h, core, TreeGameTests.side(4));
        DevelopmentClock clocks = TemporalWorld.get(h.getLevel()).development;
        core.tissue.setCoreQuantity(900*Tissue.TU);
        int[] modes = {0, 1, 2}, expected = {0, 1, 2};
        for (int i = 0; i < modes.length; i++) {
            TemporalWorld.get(h.getLevel()).colony(core).chooseMode(modes[i], h.getLevel().getGameTime(), UUID.randomUUID());
            fruit.ticks = 0; fruit.invested = 0; core.tissue.setCoreQuantity(900*Tissue.TU);
            FruitEntity.tick(fruit); DevelopmentTime.tick(core); clocks.flush();
            h.assertTrue(fruit.ticks == expected[i], "Supply policy must change development speed without extra native ticking");
        }
        TemporalWorld.get(h.getLevel()).colony(core).chooseMode(2, h.getLevel().getGameTime(), UUID.randomUUID());
        fruit.ticks = 0; fruit.invested = 0; core.tissue.setCoreQuantity(900*Tissue.TU);
        FruitEntity.tick(fruit); // Child ticks before its accelerated parent.
        for (int i = 0; i < 16; i++) DevelopmentTime.tick(core);
        clocks.flush();
        h.assertTrue(fruit.ticks == 32, "Sixteen parent ticks propagate through the concentrated policy's double rate");
        fruit.ticks = 0; fruit.invested = 0; core.tissue.setCoreQuantity(900*Tissue.TU);
        for (int i = 0; i < 16; i++) DevelopmentTime.tick(core);
        for (int i = 0; i < 24; i++) FruitEntity.tick(fruit);
        clocks.flush();
        h.assertTrue(fruit.ticks == 48, "Direct and inherited acceleration choose the faster clock, not their product or sum");
        h.assertTrue(core.tissue.stored() == 860*Tissue.TU && fruit.invested == 40*Tissue.TU && fruit.grade == 0,
                "The selected clock pays exactly its forty TU of work, without duplicated income");
        h.assertTrue(DevelopmentTime.hasRemaining(core), "A fully developed parent can accept a bottle for its growing fruit");
        h.succeed();
    }

    @GameTest(templateNamespace="kaleidoscope_witchcraft", template="empty", timeoutTicks=60)
    public static void removedParentCannotDeliverQueuedDevelopment(GameTestHelper h) {
        LivingEntity core = TreeGameTests.plant(h, true);
        FruitEntity fruit = TreeGameTests.fruit(h, core, TreeGameTests.side(4));
        fruit.grade = 1; fruit.ticks = 73; core.tissue.productionMode = 2;
        for (int i = 0; i < 16; i++) DevelopmentTime.tick(core);
        FruitEntity.tick(fruit);
        h.getLevel().removeBlock(core.getBlockPos(), false);
        TemporalWorld.get(h.getLevel()).development.flush();
        h.assertTrue(fruit.ticks == 73, "A removed parent cannot deliver queued clock debt to an orphaned fruit");
        h.succeed();
    }

    @GameTest(templateNamespace="kaleidoscope_witchcraft", template="empty", timeoutTicks=60)
    public static void playerAbsorptionRequiresForeignBoundGiantAndAvailableRoom(GameTestHelper h) {
        LivingEntity core = TreeGameTests.plant(h, true);
        var player = DevelopmentGameTests.player(h, core);
        core.tissue.bond = TreeBond.of(UUID.randomUUID(), "owner");
        h.assertTrue(PlayerActionTime.receiver(player) == null, "An ordinary owned tree cannot absorb players");
        core.tissue.giant = true; core.tissue.disabledSources = 0; TemporalWorld.get(h.getLevel()).invalidate();
        h.assertTrue(PlayerActionTime.receiver(player) == core, "A foreign bound giant may receive player action time");
        player.invulnerableTime = 19;
        PlayerActionTime.shortenImmunity(player, 20);
        h.assertTrue(player.invulnerableTime == 18 && core.tissue.stored() > 0,
                "One additional immunity tick is removed and only its captured time credited");
        long stored = core.tissue.stored();
        PlayerActionTime.shortenImmunity(player, 20);
        h.assertTrue(player.invulnerableTime == 18 && core.tissue.stored() == stored, "The same vanilla decrement cannot be charged twice");
        core.tissue.bond = TreeBond.of(player.getUUID(), "self");
        h.assertTrue(PlayerActionTime.receiver(player) == null, "The owner stays immune to their own field");
        core.tissue.bond = TreeBond.of(UUID.randomUUID(), "owner"); core.tissue.disabledSources |= 1 << 4;
        h.assertTrue(PlayerActionTime.receiver(player) == null, "The existing entity source switch also stops player capture");
        core.tissue.disabledSources = 0; core.tissue.setCoreQuantity(core.tissue.coreCapacity());
        h.assertTrue(PlayerActionTime.receiver(player) == null, "A full tree must stop absorbing players");
        h.succeed();
    }
}
