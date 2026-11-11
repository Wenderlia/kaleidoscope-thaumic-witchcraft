package dev.senluo.witchcraft.vishroom;

import dev.senluo.witchcraft.compat.CropGrowth;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/** Growth uses native random opportunities; scheduled sensing is not a work ticker. */
public final class VishroomCoreBlock extends Block implements EntityBlock, BonemealableBlock, CropGrowth.Readiness {
    public VishroomCoreBlock(Properties properties) { super(properties); }
    @Override public String getDescriptionId() { return "block.thaumcraft.vishroom"; }
    @Override public BlockEntity newBlockEntity(BlockPos pos, BlockState state) { return new VishroomEntity(pos, state); }
    @Override protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState previous, boolean moved) {
        super.onPlace(state, level, pos, previous, moved);
        if (!level.isClientSide && !state.is(previous.getBlock())) level.scheduleTick(pos, this, 20);
    }
    @Override public void setPlacedBy(Level level, BlockPos pos, BlockState state, LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (!level.isClientSide && level.getBlockEntity(pos) instanceof VishroomEntity entity) {
            var data = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
            if (data.contains("Organism")) entity.restore(data.getCompound("Organism"));
            else if (placer != null) entity.bind(placer.getUUID(), placer instanceof net.minecraft.world.entity.player.Player player ? player.getGameProfile().getName() : "");
        }
    }
    @Override public ItemStack getCloneItemStack(LevelReader level, BlockPos pos, BlockState state) {
        ItemStack item = new ItemStack(VishroomContent.CORE_ITEM.get());
        if (level.getBlockEntity(pos) instanceof VishroomEntity entity) {
            var data = new net.minecraft.nbt.CompoundTag(); data.put("Organism", entity.organism());
            item.set(DataComponents.CUSTOM_DATA, CustomData.of(data));
        }
        return item;
    }
    @Override protected void tick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        if (level.getBlockEntity(pos) instanceof VishroomEntity entity) entity.affectNearby();
        if (level.getBlockState(pos).is(this)) level.scheduleTick(pos, this, 20);
    }
    @Override protected void randomTick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        if (level.getBlockEntity(pos) instanceof VishroomEntity entity) entity.grow(1);
    }
    @Override public boolean canGrow(ServerLevel level, BlockPos pos, BlockState state) {
        return level.getBlockEntity(pos) instanceof VishroomEntity entity && entity.canGrow();
    }
    @Override public boolean isValidBonemealTarget(LevelReader level, BlockPos pos, BlockState state) {
        return level.isClientSide() || level.getBlockEntity(pos) instanceof VishroomEntity entity && entity.canGrow();
    }
    @Override public boolean isBonemealSuccess(Level level, RandomSource random, BlockPos pos, BlockState state) { return true; }
    @Override public void performBonemeal(ServerLevel level, RandomSource random, BlockPos pos, BlockState state) {
        if (!(level.getBlockEntity(pos) instanceof VishroomEntity entity)) return;
        int count = random.nextInt(3);
        if (count == 0) return;
        if (level.captureBlockSnapshots) {
            // Player item use owns this snapshot batch. Grow after it closes so each
            // new part can run its own placement protection and rollback.
            var server = level.getServer();
            server.tell(new net.minecraft.server.TickTask(server.getTickCount(), () -> entity.grow(count)));
        } else entity.grow(count);
    }
}
