package dev.senluo.witchcraft.time;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@PrefixGameTestTemplate(false)
public final class ScheduledDataGameTests {
    @GameTest(templateNamespace="kaleidoscope_witchcraft", template="empty", timeoutTicks=20)
    public static void scheduledDelaysRetainQueuePhaseAndPriority(GameTestHelper h) {
        CompoundTag current = new CompoundTag();current.putInt("Version",1);
        ListTag pending = new ListTag();
        for (int state = 0; state < 3; state++) {
            CompoundTag entry = new CompoundTag();
            entry.putLong("Pos", new BlockPos(state, 90, -17).asLong());
            entry.putBoolean("Fluid", state == 2);
            entry.putString("Type", state == 2 ? "minecraft:water" : "minecraft:repeater");
            entry.putInt("Interval", 20); entry.putInt("Remaining", 7);
            entry.putInt("State", state); entry.putInt("Priority", -2);
            pending.add(entry);
        }
        current.put("Pending", pending);
        var data = ScheduledTime.load(current, h.getLevel().registryAccess());
        CompoundTag saved = data.save(new CompoundTag(), h.getLevel().registryAccess());
        h.assertTrue(saved.getInt("Version") == 1 && saved.getList("Pending", Tag.TAG_COMPOUND).equals(pending),
                "Saving must preserve native/held/released phases, remaining wait and ordering");
        h.assertTrue(ScheduledTime.load(saved, h.getLevel().registryAccess())
                .save(new CompoundTag(), h.getLevel().registryAccess()).equals(saved), "Current data round-trips unchanged");
        h.succeed();
    }

    @GameTest(templateNamespace="kaleidoscope_witchcraft", template="empty", timeoutTicks=20)
    public static void futureDelaysPreserveOpaquePayload(GameTestHelper h) {
        CompoundTag future = new CompoundTag();
        future.putInt("Version", 99); future.putString("FutureQueue", "work-not-understood-by-this-build");
        ListTag pending = new ListTag();
        CompoundTag entry = new CompoundTag(); entry.putInt("FutureField", 43); pending.add(entry);
        future.put("Pending", pending);
        CompoundTag expected = future.copy();
        var data = ScheduledTime.load(future, h.getLevel().registryAccess());
        future.putInt("Version", 1); // Loading must not retain caller-owned mutable NBT.
        data.tick();
        var saved = data.save(new CompoundTag(), h.getLevel().registryAccess());
        h.assertTrue(saved.equals(expected), "Unknown version must neither execute nor discard pending work");
        saved.remove("Pending");
        h.assertTrue(data.save(new CompoundTag(), h.getLevel().registryAccess()).equals(expected),
                "Saving must return a copy, preserving subsequent saves");
        h.succeed();
    }
}
