package dev.senluo.witchcraft.gametest;

import dev.senluo.witchcraft.time.*;
import dev.senluo.witchcraft.mixin.time.FurnaceAccess;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;

/** Narrow production regressions. Compiling these is not an in-world test result. */
@net.neoforged.neoforge.gametest.PrefixGameTestTemplate(false)
public final class ProductionGameTests {
    @GameTest(templateNamespace="kaleidoscope_witchcraft", template="empty", timeoutTicks=60)
    public static void machineBudgetExhaustionDoesNotSpendStock(GameTestHelper h) {
        LivingEntity core = TreeGameTests.plant(h, true);
        core.tissue.setCoreQuantity(10_000 * Tissue.TU);
        core.tissue.productionMode = SupplyMode.NORMAL;
        BlockPos log = core.getBlockPos().east(), pos = log.east();
        h.getLevel().setBlock(log, TemporalContent.TIME_LOG.get().defaultBlockState(), 3);
        h.getLevel().setBlock(pos, Blocks.FURNACE.defaultBlockState(), 3);
        AbstractFurnaceBlockEntity furnace = (AbstractFurnaceBlockEntity) h.getLevel().getBlockEntity(pos);
        furnace.setItem(0, new ItemStack(Items.IRON_ORE, 8));
        furnace.setItem(1, new ItemStack(Items.COAL));
        AbstractFurnaceBlockEntity.serverTick(h.getLevel(), pos, furnace.getBlockState(), furnace);
        FurnaceAccess access = (FurnaceAccess) furnace;
        int progress = access.senluo$cookingProgress(), fuel = access.senluo$litTime();
        TemporalWorld world = TemporalWorld.get(h.getLevel());
        world.invalidateConnections();
        h.assertTrue(world.machines.get(world.colony(core)).contains(pos), "A valid machine is required to exercise budget rejection");
        h.assertTrue(MachineWork.operation(h.getLevel(),pos)!=null && FruitFeeding.available(world.colony(core))>=12*Tissue.TU,
                "Stock must exceed tree repair reserves so this case reaches budget admission");
        // Run this directed regression in isolation: exhaust this server frame's shared allowance.
        dev.senluo.witchcraft.ritual.RitualWorkBudget.unload(h.getLevel());
        try {
            int limit = dev.senluo.witchcraft.ritual.RitualWorkBudget.snapshot(h.getLevel()).limit();
            int granted = dev.senluo.witchcraft.ritual.RitualWorkBudget.claim(h.getLevel(),
                    dev.senluo.witchcraft.ritual.RitualWorkBudget.Kind.MACHINE, pos, java.math.BigInteger.valueOf(limit));
            h.assertTrue(granted == limit, "Ritual updates consume the same world allowance");
            world.production.flush(List.of(core));
            h.assertTrue(core.tissue.stored() == 10_000 * Tissue.TU && access.senluo$cookingProgress() == progress
                    && access.senluo$litTime() == fuel, "Denied tree work cannot consume stock, fuel or advance progress");
            h.assertTrue(dev.senluo.witchcraft.ritual.RitualWorkBudget.snapshot(h.getLevel()).granted() == limit,
                    "Tree work cannot exceed the shared cap");
        } finally {
            dev.senluo.witchcraft.ritual.RitualWorkBudget.unload(h.getLevel());
        }
        h.succeed();
    }

    @GameTest(templateNamespace="kaleidoscope_witchcraft", template="empty", timeoutTicks=60)
    public static void connectedFurnaceSpendsStockAdvancesFuelAndStopsAfterDisconnect(GameTestHelper h) {
        LivingEntity core = TreeGameTests.plant(h, true);
        // Missing tree geometry reserves repair stock before machine production.
        core.tissue.setCoreQuantity(10_000 * Tissue.TU);
        core.tissue.productionMode = SupplyMode.NORMAL;
        BlockPos log = core.getBlockPos().east(), pos = log.east();
        h.getLevel().setBlock(log, TemporalContent.TIME_LOG.get().defaultBlockState(), 3);
        h.getLevel().setBlock(pos, Blocks.FURNACE.defaultBlockState(), 3);
        AbstractFurnaceBlockEntity furnace = (AbstractFurnaceBlockEntity) h.getLevel().getBlockEntity(pos);
        furnace.setItem(0, new ItemStack(Items.IRON_ORE, 8));
        furnace.setItem(1, new ItemStack(Items.COAL));
        AbstractFurnaceBlockEntity.serverTick(h.getLevel(), pos, furnace.getBlockState(), furnace);
        FurnaceAccess access = (FurnaceAccess) furnace;
        int progress = access.senluo$cookingProgress(), fuel = access.senluo$litTime();
        TemporalWorld world = TemporalWorld.get(h.getLevel());
        world.invalidateConnections();
        h.assertTrue(world.machines.get(world.colony(core)).contains(pos), "Only physical face contacts qualify");
        world.production.flush(List.of(core));
        h.assertTrue(core.tissue.stored() == 9_988 * Tissue.TU && access.senluo$cookingProgress() == progress + 4
                && access.senluo$litTime() == fuel - 4, "Four native ticks cost 12 TU and advance fuel and work together: stock="
                        + core.tissue.stored() + ", progress=" + (access.senluo$cookingProgress()-progress)
                        + ", fuel=" + (access.senluo$litTime()-fuel));
        h.getLevel().setBlock(log, Blocks.AIR.defaultBlockState(), 3);
        world.invalidateConnections();
        world.production.flush(List.of(core));
        h.assertTrue(core.tissue.stored() == 9_988 * Tissue.TU && access.senluo$cookingProgress() == progress + 4,
                "A disconnected machine receives no additional ticks or charges");
        h.succeed();
    }

    @GameTest(templateNamespace="kaleidoscope_witchcraft", template="empty", timeoutTicks=60)
    public static void reusedWalletTracksGraftingLocksAndCapacityChanges(GameTestHelper h) {
        LivingEntity core = TreeGameTests.plant(h, true);
        core.tissue.setCoreQuantity(10 * Tissue.TU);
        Colony group = TemporalWorld.get(h.getLevel()).colony(core);
        long capacity = group.capacity();
        Tissue child = new Tissue(); child.rootStage = 4; child.setCoreQuantity(7 * Tissue.TU);
        Tissue.Side side = Tissue.Side.graft(child); core.tissue.sides.add(side);
        h.assertTrue(group.capacity() == capacity + child.coreCapacity() && group.quantity() == 17 * Tissue.TU,
                "A cached view must include newly attached physical tissue");
        side.locked = true;
        h.assertTrue(group.spendable() == 10 * Tissue.TU && !group.spend(11 * Tissue.TU), "Precharge locks invalidate spending eligibility");
        side.locked = false;
        child.ritualCapacity += Tissue.TU;
        h.assertTrue(group.capacity() == capacity + child.coreCapacity() && group.spend(17 * Tissue.TU)
                && group.quantity() == 0, "Capacity changes rebuild the view without minting or losing balances");
        h.succeed();
    }

    @GameTest(templateNamespace="kaleidoscope_witchcraft", template="empty", timeoutTicks=60)
    public static void extraWorkHonorsOwnershipAndScopeAlwaysRestores(GameTestHelper h) {
        LivingEntity core = TreeGameTests.plant(h, true);
        var owner = java.util.UUID.randomUUID();
        core.tissue.bond = dev.senluo.witchcraft.ritual.TreeBond.of(owner, "source-owner");
        TemporalWorld world = TemporalWorld.get(h.getLevel());
        long before = core.tissue.stored();
        ExtraTicks.run(() -> {
            AccelerationSources.run(owner, () -> h.assertTrue(!world.absorbRandomSample(core.getBlockPos()),
                    "Same-owner extra opportunities are exempt from absorption"));
            h.assertTrue(world.absorbRandomSample(core.getBlockPos()),
                    "Unowned extra opportunities retain the current absorption rules");
            ExtraTicks.run(() -> h.assertTrue(ExtraTicks.active(), "Nested callbacks remain in the same provenance"));
            h.assertTrue(ExtraTicks.active(), "Leaving a nested callback keeps the outer scope");
        });
        h.assertTrue(!ExtraTicks.active() && !AccelerationSources.currentOwner(owner) && core.tissue.stored() > before,
                "Both scopes restore and only the eligible opportunity credits stock");
        h.succeed();
    }
}
