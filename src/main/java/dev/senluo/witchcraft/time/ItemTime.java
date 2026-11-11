package dev.senluo.witchcraft.time;

import dev.senluo.witchcraft.mixin.time.EntityMotionAccess;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.phys.Vec3;

/** Continuous local half-steps. Only state transitions are synchronized, never per-frame positions. */
public final class ItemTime {
    private ItemTime() {}
    public static boolean slow(ItemEntity item) {
        return Boolean.TRUE.equals(item.getExistingDataOrNull(TemporalContent.SLOW_ITEM));
    }
    public static void setSlow(ItemEntity item, boolean slow) {
        if (slow(item) != slow) item.setData(TemporalContent.SLOW_ITEM, slow);
    }
    public static boolean pauseClock(ItemEntity item) {
        return slow(item) && ((item.level().getGameTime() + item.getId()) & 1) == 0;
    }
    private static double volume(ItemEntity item) {
        var box = item.getBoundingBox();
        return box.getXsize() * box.getYsize() * box.getZsize();
    }
    public static boolean begin(ItemEntity item, LivingEntity receiver) {
        boolean active = receiver != null;
        setSlow(item, active);
        return active;
    }
    public static boolean retainAge(ItemEntity item) {
        if (!pauseClock(item)) return false;
        if (item.level() instanceof ServerLevel level) {
            TemporalWorld world = TemporalWorld.get(level);
            if (!world.credit(world.receiver(item.blockPosition(), 4), 4, SourceIncomeRates.clock(volume(item), .05))) {
                setSlow(item, false);
                return false;
            }
        }
        return true;
    }
    public static Vec3 movement(ItemEntity item, Vec3 requested) {
        if (!slow(item)) return requested;
        if (item.level() instanceof ServerLevel level && requested.lengthSqr() > 1.0E-12) {
            var possible = ((EntityMotionAccess)item).senluo$collide(requested);
            var independent = FluidTime.independentMotion(level, item, possible);
            double quote = SourceIncomeRates.motion(volume(item), independent.length() * .5);
            if (quote > 0) {
                TemporalWorld world = TemporalWorld.get(level);
                if (!world.credit(world.receiver(item.blockPosition(), 4), 4, quote)) {
                    setSlow(item, false);
                    return requested;
                }
            }
        }
        return requested.scale(.5);
    }
}
