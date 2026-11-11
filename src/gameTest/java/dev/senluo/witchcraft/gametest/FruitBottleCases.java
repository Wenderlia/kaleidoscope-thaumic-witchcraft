package dev.senluo.witchcraft.gametest;

import com.mojang.authlib.GameProfile;
import dev.senluo.witchcraft.ritual.TreeBond;
import dev.senluo.witchcraft.time.*;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import org.mangorage.tiab.common.api.ICommonTimeInABottleAPI;
import org.mangorage.tiab.common.api.impl.ITiabItem;

/** Optional TIAB linkage stays outside the always-registered test class. */
final class FruitBottleCases {
    private static final class RecordingPlayer extends Player {
        boolean feedback;
        RecordingPlayer(ServerLevel level) {
            super(level, BlockPos.ZERO, 0, new GameProfile(UUID.randomUUID(), "fruit-feeding"));
        }
        @Override public boolean isSpectator() { return false; }
        @Override public boolean isCreative() { return false; }
        @Override public boolean isLocalPlayer() { return true; }
        @Override public void displayClientMessage(Component message, boolean actionBar) { feedback = true; }
    }

    static void run(GameTestHelper h) {
        LivingEntity core = TreeGameTests.plant(h, true);
        FruitEntity fruit = TreeGameTests.fruit(h, core, TreeGameTests.side(4));
        core.tissue.bond = TreeBond.of(UUID.randomUUID(), "different-owner");
        fruit.invested = TreeBalance.FRUIT_FEE[1] - 250;
        fruit.ticks = 10;
        var api = ICommonTimeInABottleAPI.COMMON_API.get();
        int bottleSecond = api.getConfig().TICKS_CONST();
        RecordingPlayer player = new RecordingPlayer(h.getLevel());
        ItemStack bottle = new ItemStack(api.getRegistration().getTiabItem().asItem());
        bottle.set(api.getRegistration().getStoredTime(), api.createStoredTimeComponent(200_000, 300_000));
        player.setItemInHand(InteractionHand.MAIN_HAND, bottle);
        player.setShiftKeyDown(true);
        player.setPos(Vec3.atCenterOf(fruit.getBlockPos()).add(0, 0, -2));
        long quote = BottleFeedingRates.costMilliTuTicks(core.tissue.stored(), Tissue.TU, bottleSecond);
        var click = NeoForge.EVENT_BUS.post(new PlayerInteractEvent.RightClickBlock(player, InteractionHand.MAIN_HAND,
                fruit.getBlockPos(), new BlockHitResult(Vec3.atCenterOf(fruit.getBlockPos()), Direction.NORTH, fruit.getBlockPos(), false)));
        var after = ((ITiabItem) bottle.getItem()).getStoredComponent(bottle);
        h.assertTrue(click.isCanceled() && fruit.remainingFee() == 250 && fruit.ticks == 10 && fruit.grade == 0,
                "A visitor's injection stores time in the tree without buying fruit work");
        h.assertTrue(after.stored() == 200_000-quote && after.total() == 300_000 && !player.feedback,
                "The bottle spends the exact tree-stock quote and fruit feedback stays silent");
        h.assertTrue(core.tissue.stored() == Tissue.TU,
                "Clicking a fruit injects one TU into its real core without income authorization");
        player.releaseUsingItem();
        long combined = BottleFeedingRates.costMilliTuTicks(0, 1201*Tissue.TU, bottleSecond);
        long split = BottleFeedingRates.costMilliTuTicks(0, 1_199_375, bottleSecond)
                + BottleFeedingRates.costMilliTuTicks(1_199_375, 1_625, bottleSecond);
        h.assertTrue(combined == split, "Fractional payments telescope exactly across the curve's 1200 TU boundary");
        h.succeed();
    }
}
