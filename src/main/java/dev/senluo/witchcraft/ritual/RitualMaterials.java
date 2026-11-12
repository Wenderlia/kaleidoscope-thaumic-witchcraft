package dev.senluo.witchcraft.ritual;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;

/** Eligibility shared by world-material consumers; another ritual's subject or reservation is not loose material. */
public final class RitualMaterials {
    /** A display's real inventory, supplied by an optional adapter. Reads must have no side effects. */
    public interface Display {
        ItemStack stack();
        boolean canTake();
        ItemStack takeOne();
        /** Return to an empty original display only; false leaves the actual item with the caller. */
        boolean returnOne(ItemStack stack);
    }
    private static final List<Function<BlockEntity, Display>> DISPLAYS = new ArrayList<>();

    private RitualMaterials() {}
    public static void registerDisplay(Function<BlockEntity, Display> adapter) {
        DISPLAYS.add(Objects.requireNonNull(adapter));
    }

    public static boolean available(ItemEntity item) {
        return item != null && item.isAlive() && !item.getItem().isEmpty()
                && !OwnershipRitual.occupied(item) && !OwnershipOfferings.held(item)
                && !RitualVial.reserved(item.getItem());
    }

    /** A short-lived quote on a real source, never a second inventory or a saved reservation. */
    public abstract static class Source {
        private final ItemStack expected;
        private Source(ItemStack stack) { expected = stack.copyWithCount(1); }
        public final ItemStack item() { return expected.copy(); }
        public abstract Vec3 position();
        protected abstract ItemStack current();
        protected abstract ItemStack removeOne();
        public boolean returnOne(ItemStack stack) { return false; }
        public final int count() {
            ItemStack stack = current();
            return stack != null && !stack.isEmpty() && !RitualVial.reserved(stack)
                    && ItemStack.isSameItemSameComponents(expected, stack) ? stack.getCount() : 0;
        }
        public final boolean available() { return count() > 0; }
        public final ItemStack takeOne() { return available() ? removeOne() : ItemStack.EMPTY; }
    }

    private static final class Loose extends Source {
        private final ItemEntity entity;
        private Loose(ItemEntity entity) { super(entity.getItem()); this.entity = entity; }
        @Override public Vec3 position() { return entity.position(); }
        @Override protected ItemStack current() {
            return RitualMaterials.available(entity) && entity.level() instanceof ServerLevel level
                    && level.shouldTickBlocksAt(entity.blockPosition()) && level.getEntity(entity.getUUID()) == entity
                    && StructuralRitualStore.get(level).reservationOwner(entity.getUUID()).isEmpty()
                    ? entity.getItem() : ItemStack.EMPTY;
        }
        @Override protected ItemStack removeOne() {
            ItemStack taken = entity.getItem().copyWithCount(1);
            ItemStack rest = entity.getItem().copy(); rest.shrink(1);
            if (rest.isEmpty()) entity.discard(); else entity.setItem(rest);
            return taken;
        }
    }

    private static final class Displayed extends Source {
        private final ServerLevel level;
        private final BlockEntity entity;
        private final BlockPos pos;
        private final Display display;
        private Displayed(ServerLevel level, BlockEntity entity, Display display, ItemStack stack) {
            super(stack); this.level = level; this.entity = entity; this.display = display;
            pos = entity.getBlockPos().immutable();
        }
        private boolean present() {
            return !entity.isRemoved() && level.hasChunkAt(pos) && level.shouldTickBlocksAt(pos)
                    && level.getBlockEntity(pos) == entity;
        }
        @Override public Vec3 position() { return Vec3.atBottomCenterOf(pos).add(0, 1.25, 0); }
        @Override protected ItemStack current() { return present() && display.canTake() ? display.stack() : ItemStack.EMPTY; }
        @Override protected ItemStack removeOne() { return display.takeOne(); }
        @Override public boolean returnOne(ItemStack stack) {
            return present() && !stack.isEmpty() && display.returnOne(stack.copy());
        }
    }

    public static boolean inArea(Source source, RitualOfferingArea area) {
        Vec3 pos = source.position();
        return area.containsHorizontal(pos) && pos.y >= area.bounds().minY && pos.y < area.bounds().maxY;
    }

    /** Loaded block-entity maps only, at most the chunks crossed by the actual ring; no voxel scan. */
    public static List<Source> find(ServerLevel level, RitualOfferingArea area, Predicate<ItemStack> wanted) {
        var result = new ArrayList<Source>();
        for (ItemEntity item : level.getEntitiesOfClass(ItemEntity.class, area.bounds(),
                entity -> available(entity) && wanted.test(entity.getItem()))) {
            Source source = new Loose(item);
            if (source.available() && inArea(source, area)) result.add(source);
        }
        if (DISPLAYS.isEmpty()) return result;
        var bounds = area.bounds();
        for (int x = Mth.floor(bounds.minX) >> 4; x <= Mth.floor(Math.nextDown(bounds.maxX)) >> 4; x++) {
            for (int z = Mth.floor(bounds.minZ) >> 4; z <= Mth.floor(Math.nextDown(bounds.maxZ)) >> 4; z++) {
                var chunk = level.getChunkSource().getChunkNow(x, z);
                if (chunk == null) continue;
                for (BlockEntity entity : chunk.getBlockEntities().values()) {
                    BlockPos pos = entity.getBlockPos();
                    Vec3 displayed = Vec3.atBottomCenterOf(pos).add(0, 1.25, 0);
                    if (entity.isRemoved() || !level.shouldTickBlocksAt(pos) || !area.containsHorizontal(displayed)
                            || displayed.y < bounds.minY || displayed.y >= bounds.maxY) continue;
                    for (var adapter : DISPLAYS) {
                        Display display = adapter.apply(entity);
                        if (display == null) continue;
                        ItemStack stack = display.stack();
                        if (stack != null && !stack.isEmpty() && wanted.test(stack)) {
                            Source source = new Displayed(level, entity, display, stack);
                            if (source.available()) result.add(source);
                        }
                        break;
                    }
                }
            }
        }
        return result;
    }
}
