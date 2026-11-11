package dev.senluo.witchcraft.time;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockSetType;
import net.minecraft.world.level.material.FluidState;

/** Thin native block adapters: the tree module owns edits, vanilla owns each building form. */
public final class YearsBuildingBlocks {
    private YearsBuildingBlocks() {}

    public static final class Stairs extends StairBlock implements YearsBuildingEdits.Part {
        public Stairs(Properties properties) { super(TemporalContent.TIME_PLANKS.get().defaultBlockState(), properties); }
        @Override public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
            YearsBuildingEdits.before(level, pos, player);
            return super.playerWillDestroy(level, pos, state, player);
        }
        @Override public boolean onDestroyedByPlayer(BlockState state, Level level, BlockPos pos, Player player, boolean harvest, FluidState fluid) {
            var attempt = YearsBuildingEdits.take(level, pos, player);
            boolean removed = super.onDestroyedByPlayer(state, level, pos, player, harvest, fluid);
            YearsBuildingEdits.finished(level, pos, state, player, removed, attempt);
            return removed;
        }
        @Override public void setPlacedBy(Level level, BlockPos pos, BlockState state, net.minecraft.world.entity.LivingEntity placer, ItemStack stack) {
            super.setPlacedBy(level, pos, state, placer, stack);
            YearsBuildingEdits.placed(level, pos, state, placer);
        }
    }

    public static final class Slab extends SlabBlock implements YearsBuildingEdits.Part {
        public Slab(Properties properties) { super(properties); }
        @Override public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
            YearsBuildingEdits.before(level, pos, player);
            return super.playerWillDestroy(level, pos, state, player);
        }
        @Override public boolean onDestroyedByPlayer(BlockState state, Level level, BlockPos pos, Player player, boolean harvest, FluidState fluid) {
            var attempt = YearsBuildingEdits.take(level, pos, player);
            boolean removed = super.onDestroyedByPlayer(state, level, pos, player, harvest, fluid);
            YearsBuildingEdits.finished(level, pos, state, player, removed, attempt);
            return removed;
        }
        @Override public void setPlacedBy(Level level, BlockPos pos, BlockState state, net.minecraft.world.entity.LivingEntity placer, ItemStack stack) {
            super.setPlacedBy(level, pos, state, placer, stack);
            YearsBuildingEdits.placed(level, pos, state, placer);
        }
    }

    public static final class Fence extends FenceBlock implements YearsBuildingEdits.Part {
        public Fence(Properties properties) { super(properties); }
        @Override public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
            YearsBuildingEdits.before(level, pos, player);
            return super.playerWillDestroy(level, pos, state, player);
        }
        @Override public boolean onDestroyedByPlayer(BlockState state, Level level, BlockPos pos, Player player, boolean harvest, FluidState fluid) {
            var attempt = YearsBuildingEdits.take(level, pos, player);
            boolean removed = super.onDestroyedByPlayer(state, level, pos, player, harvest, fluid);
            YearsBuildingEdits.finished(level, pos, state, player, removed, attempt);
            return removed;
        }
        @Override public void setPlacedBy(Level level, BlockPos pos, BlockState state, net.minecraft.world.entity.LivingEntity placer, ItemStack stack) {
            super.setPlacedBy(level, pos, state, placer, stack);
            YearsBuildingEdits.placed(level, pos, state, placer);
        }
    }

    public static final class Gate extends FenceGateBlock implements YearsBuildingEdits.Part {
        public Gate(Properties properties) { super(YearsWoodContent.WOOD_TYPE, properties); }
        @Override public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
            YearsBuildingEdits.before(level, pos, player);
            return super.playerWillDestroy(level, pos, state, player);
        }
        @Override public boolean onDestroyedByPlayer(BlockState state, Level level, BlockPos pos, Player player, boolean harvest, FluidState fluid) {
            var attempt = YearsBuildingEdits.take(level, pos, player);
            boolean removed = super.onDestroyedByPlayer(state, level, pos, player, harvest, fluid);
            YearsBuildingEdits.finished(level, pos, state, player, removed, attempt);
            return removed;
        }
        @Override public void setPlacedBy(Level level, BlockPos pos, BlockState state, net.minecraft.world.entity.LivingEntity placer, ItemStack stack) {
            super.setPlacedBy(level, pos, state, placer, stack);
            YearsBuildingEdits.placed(level, pos, state, placer);
        }
    }

    public static final class Button extends ButtonBlock implements YearsBuildingEdits.Part {
        public Button(Properties properties) { super(BlockSetType.OAK, 30, properties); }
        @Override public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
            YearsBuildingEdits.before(level, pos, player);
            return super.playerWillDestroy(level, pos, state, player);
        }
        @Override public boolean onDestroyedByPlayer(BlockState state, Level level, BlockPos pos, Player player, boolean harvest, FluidState fluid) {
            var attempt = YearsBuildingEdits.take(level, pos, player);
            boolean removed = super.onDestroyedByPlayer(state, level, pos, player, harvest, fluid);
            YearsBuildingEdits.finished(level, pos, state, player, removed, attempt);
            return removed;
        }
        @Override public void setPlacedBy(Level level, BlockPos pos, BlockState state, net.minecraft.world.entity.LivingEntity placer, ItemStack stack) {
            super.setPlacedBy(level, pos, state, placer, stack);
            YearsBuildingEdits.placed(level, pos, state, placer);
        }
    }

    public static final class Plate extends PressurePlateBlock implements YearsBuildingEdits.Part {
        public Plate(Properties properties) { super(BlockSetType.OAK, properties); }
        @Override public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
            YearsBuildingEdits.before(level, pos, player);
            return super.playerWillDestroy(level, pos, state, player);
        }
        @Override public boolean onDestroyedByPlayer(BlockState state, Level level, BlockPos pos, Player player, boolean harvest, FluidState fluid) {
            var attempt = YearsBuildingEdits.take(level, pos, player);
            boolean removed = super.onDestroyedByPlayer(state, level, pos, player, harvest, fluid);
            YearsBuildingEdits.finished(level, pos, state, player, removed, attempt);
            return removed;
        }
        @Override public void setPlacedBy(Level level, BlockPos pos, BlockState state, net.minecraft.world.entity.LivingEntity placer, ItemStack stack) {
            super.setPlacedBy(level, pos, state, placer, stack);
            YearsBuildingEdits.placed(level, pos, state, placer);
        }
    }

    public static final class Door extends DoorBlock implements YearsBuildingEdits.Part {
        public Door(Properties properties) { super(BlockSetType.OAK, properties); }
        @Override public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
            YearsBuildingEdits.before(level, pos, player);
            return super.playerWillDestroy(level, pos, state, player);
        }
        @Override public boolean onDestroyedByPlayer(BlockState state, Level level, BlockPos pos, Player player, boolean harvest, FluidState fluid) {
            var attempt = YearsBuildingEdits.take(level, pos, player);
            boolean removed = super.onDestroyedByPlayer(state, level, pos, player, harvest, fluid);
            YearsBuildingEdits.finished(level, pos, state, player, removed, attempt);
            return removed;
        }
        @Override public void setPlacedBy(Level level, BlockPos pos, BlockState state, net.minecraft.world.entity.LivingEntity placer, ItemStack stack) {
            super.setPlacedBy(level, pos, state, placer, stack);
            YearsBuildingEdits.placed(level, pos, state, placer);
        }
    }

    public static final class Trapdoor extends TrapDoorBlock implements YearsBuildingEdits.Part {
        public Trapdoor(Properties properties) { super(BlockSetType.OAK, properties); }
        @Override public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
            YearsBuildingEdits.before(level, pos, player);
            return super.playerWillDestroy(level, pos, state, player);
        }
        @Override public boolean onDestroyedByPlayer(BlockState state, Level level, BlockPos pos, Player player, boolean harvest, FluidState fluid) {
            var attempt = YearsBuildingEdits.take(level, pos, player);
            boolean removed = super.onDestroyedByPlayer(state, level, pos, player, harvest, fluid);
            YearsBuildingEdits.finished(level, pos, state, player, removed, attempt);
            return removed;
        }
        @Override public void setPlacedBy(Level level, BlockPos pos, BlockState state, net.minecraft.world.entity.LivingEntity placer, ItemStack stack) {
            super.setPlacedBy(level, pos, state, placer, stack);
            YearsBuildingEdits.placed(level, pos, state, placer);
        }
    }

    public static final class Sign extends StandingSignBlock implements YearsBuildingEdits.Part {
        public Sign(Properties properties) { super(YearsWoodContent.WOOD_TYPE, properties); }
        @Override public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
            YearsBuildingEdits.before(level, pos, player);
            return super.playerWillDestroy(level, pos, state, player);
        }
        @Override public boolean onDestroyedByPlayer(BlockState state, Level level, BlockPos pos, Player player, boolean harvest, FluidState fluid) {
            var attempt = YearsBuildingEdits.take(level, pos, player);
            boolean removed = super.onDestroyedByPlayer(state, level, pos, player, harvest, fluid);
            YearsBuildingEdits.finished(level, pos, state, player, removed, attempt);
            return removed;
        }
        @Override public void setPlacedBy(Level level, BlockPos pos, BlockState state, net.minecraft.world.entity.LivingEntity placer, ItemStack stack) {
            super.setPlacedBy(level, pos, state, placer, stack);
            YearsBuildingEdits.placed(level, pos, state, placer);
        }
    }

    public static final class WallSign extends WallSignBlock implements YearsBuildingEdits.Part {
        public WallSign(Properties properties) { super(YearsWoodContent.WOOD_TYPE, properties); }
        @Override public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
            YearsBuildingEdits.before(level, pos, player);
            return super.playerWillDestroy(level, pos, state, player);
        }
        @Override public boolean onDestroyedByPlayer(BlockState state, Level level, BlockPos pos, Player player, boolean harvest, FluidState fluid) {
            var attempt = YearsBuildingEdits.take(level, pos, player);
            boolean removed = super.onDestroyedByPlayer(state, level, pos, player, harvest, fluid);
            YearsBuildingEdits.finished(level, pos, state, player, removed, attempt);
            return removed;
        }
        @Override public void setPlacedBy(Level level, BlockPos pos, BlockState state, net.minecraft.world.entity.LivingEntity placer, ItemStack stack) {
            super.setPlacedBy(level, pos, state, placer, stack);
            YearsBuildingEdits.placed(level, pos, state, placer);
        }
    }

    public static final class HangingSign extends CeilingHangingSignBlock implements YearsBuildingEdits.Part {
        public HangingSign(Properties properties) { super(YearsWoodContent.WOOD_TYPE, properties); }
        @Override public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
            YearsBuildingEdits.before(level, pos, player);
            return super.playerWillDestroy(level, pos, state, player);
        }
        @Override public boolean onDestroyedByPlayer(BlockState state, Level level, BlockPos pos, Player player, boolean harvest, FluidState fluid) {
            var attempt = YearsBuildingEdits.take(level, pos, player);
            boolean removed = super.onDestroyedByPlayer(state, level, pos, player, harvest, fluid);
            YearsBuildingEdits.finished(level, pos, state, player, removed, attempt);
            return removed;
        }
        @Override public void setPlacedBy(Level level, BlockPos pos, BlockState state, net.minecraft.world.entity.LivingEntity placer, ItemStack stack) {
            super.setPlacedBy(level, pos, state, placer, stack);
            YearsBuildingEdits.placed(level, pos, state, placer);
        }
    }

    public static final class WallHangingSign extends WallHangingSignBlock implements YearsBuildingEdits.Part {
        public WallHangingSign(Properties properties) { super(YearsWoodContent.WOOD_TYPE, properties); }
        @Override public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
            YearsBuildingEdits.before(level, pos, player);
            return super.playerWillDestroy(level, pos, state, player);
        }
        @Override public boolean onDestroyedByPlayer(BlockState state, Level level, BlockPos pos, Player player, boolean harvest, FluidState fluid) {
            var attempt = YearsBuildingEdits.take(level, pos, player);
            boolean removed = super.onDestroyedByPlayer(state, level, pos, player, harvest, fluid);
            YearsBuildingEdits.finished(level, pos, state, player, removed, attempt);
            return removed;
        }
        @Override public void setPlacedBy(Level level, BlockPos pos, BlockState state, net.minecraft.world.entity.LivingEntity placer, ItemStack stack) {
            super.setPlacedBy(level, pos, state, placer, stack);
            YearsBuildingEdits.placed(level, pos, state, placer);
        }
    }
}

