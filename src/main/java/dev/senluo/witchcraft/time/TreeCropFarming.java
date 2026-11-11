package dev.senluo.witchcraft.time;

import dev.senluo.witchcraft.compat.HangingCrop;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.BlockEvent;

/** Native-second orchestration only; adapters own crop semantics, inventory owns recovery, search owns spacing. */
final class TreeCropFarming {
    private long lastTick = Long.MIN_VALUE;
    private int harvestCursor;
    private final SpacedPlanting planting = new SpacedPlanting();
    private boolean running;

    private boolean active(ServerLevel level, LivingEntity core) {
        return !core.isRemoved() && core.tree() && core.tissue.supported() && core.tissue.giant && core.hasOwner()
                && level.hasChunkAt(core.getBlockPos()) && level.getBlockEntity(core.getBlockPos()) == core
                && level.tickRateManager().runsNormally() && level.shouldTickBlocksAt(core.getBlockPos());
    }

    void tick(LivingEntity core) {
        if (!(core.getLevel() instanceof ServerLevel level) || running || !active(level, core)) return;
        long now = level.getGameTime();
        if (lastTick != Long.MIN_VALUE && now >= lastTick && now - lastTick < 20) return;
        lastTick = now; running = true;
        try {
            List<AdjacentInventory> inventories = AdjacentInventory.around(core);
            if (inventories.isEmpty()) return;
            TemporalWorld world = TemporalWorld.get(level);
            Colony group = world.colony(core);
            if (core.shears.awake()) harvest(level, world, core, group, inventories);
            if (active(level, core) && world.colony(core) == group) plant(level, world, core, group, inventories);
        } finally { running = false; }
    }

    private void harvest(ServerLevel level, TemporalWorld world, LivingEntity core, Colony group, List<AdjacentInventory> inventories) {
        List<BlockPos> crops = world.crops.get(group);
        for (int visit = 0; visit < Math.min(32, crops.size()); visit++) {
            BlockPos pos = crops.get(Math.floorMod(harvestCursor++, crops.size()));
            if (!level.hasChunkAt(pos) || !level.shouldTickBlocksAt(pos) || !world.crops.hangsFrom(group, pos)) continue;
            var state = level.getBlockState(pos);
            HangingCrop crop = HangingCrop.forCrop(state);
            if (crop == null || !crop.mature(state) || !state.canSurvive(level, pos)) continue;
            for (AdjacentInventory receiver : inventories) if (cut(level, core, group, pos, crop, receiver)) return;
            return; // One ripe candidate per second; a full receiver does not stall other crops forever.
        }
    }

    private boolean cut(ServerLevel level, LivingEntity core, Colony group, BlockPos pos, HangingCrop crop, AdjacentInventory receiver) {
        var items = receiver.inventory;
        for (int slot = 0; slot < items.getSlots() && receiver.present(); slot++) {
            ItemStack stored = items.getStackInSlot(slot);
            if (!core.shears.usable(stored) || !items.isItemValid(slot, stored)) continue;
            ItemStack expected = items.extractItem(slot, 1, true).copy();
            if (expected.getCount() != 1 || !ItemStack.isSameItemSameComponents(expected, stored) || !core.shears.usable(expected)) continue;
            core.shears.foundUsable();
            ItemStack tool = receiver.take(slot, expected);
            if (tool.isEmpty()) continue;
            try (TreeFarmAction action = TreeFarmAction.open(level, core.owner(), pos, tool)) {
                if (action == null) return false;
                try {
                    if (!level.hasChunkAt(pos) || !level.shouldTickBlocksAt(pos)) return false;
                    var before = level.getBlockState(pos);
                    var entity = level.getBlockEntity(pos);
                    if (!crop.mature(before) || !level.mayInteract(action.actor, pos)
                            || NeoForge.EVENT_BUS.post(new BlockEvent.BreakEvent(level, pos, before, action.actor)).isCanceled()) return false;
                    if (!active(level, core) || TemporalWorld.get(level).colony(core) != group || !receiver.present()
                            || !action.actor.getUUID().equals(core.owner()) || !core.shears.usable(action.remainder())
                            || action.remainder() != tool || !level.hasChunkAt(pos)
                            || level.getBlockState(pos) != before || level.getBlockEntity(pos) != entity) return false;
                    List<ItemStack> products = crop.drops(level, pos, before, action.actor, tool);
                    // Reserve the shears' return space as well as the products.
                    if (products.isEmpty() || !receiver.fits(withTool(products, tool)) || !receiver.present()
                            || !level.hasChunkAt(pos)
                            || level.getBlockState(pos) != before || level.getBlockEntity(pos) != entity) return false;
                    if (!active(level, core) || !action.actor.getUUID().equals(core.owner()) || TemporalWorld.get(level).colony(core) != group
                            || action.remainder() != tool || !core.shears.usable(tool)) return false;
                    if (!level.setBlock(pos, crop.afterHarvest(before), Block.UPDATE_ALL)) return false;
                    // Same block type keeps its BE and aspect. Do not remove/recreate the pod or call destroyBlock.
                    tool.hurtAndBreak(1, level, (ServerPlayer) null, item -> level.playSound(null, receiver.pos,
                            SoundEvents.ITEM_BREAK, SoundSource.BLOCKS, .5F, 1));
                    for (ItemStack product : products) receiver.receive(product);
                    level.playSound(null, pos, SoundEvents.SHEEP_SHEAR, SoundSource.BLOCKS, .5F, 1);
                    return true;
                } finally { tool = action.remainder(); }
            } finally { receiver.returnTo(slot, tool); }
        }
        return false;
    }

    private static List<ItemStack> withTool(List<ItemStack> products, ItemStack tool) {
        var all = new java.util.ArrayList<ItemStack>(products.size() + 1);
        all.add(tool.copy()); all.addAll(products); return all;
    }

    private void plant(ServerLevel level, TemporalWorld world, LivingEntity core, Colony group, List<AdjacentInventory> inventories) {
        for (AdjacentInventory receiver : inventories) {
            var items = receiver.inventory;
            for (int slot = 0; slot < items.getSlots() && receiver.present(); slot++) {
                ItemStack expected = items.extractItem(slot, 1, true).copy();
                HangingCrop crop = HangingCrop.forSeed(expected);
                if (expected.getCount() != 1 || crop == null) continue;
                BlockPos pos = planting.find(world.crops.hangingSites(group), candidate -> level.shouldTickBlocksAt(candidate)
                        && crop.canPlant(level, candidate), candidate -> spaced(level, candidate));
                if (pos == null) return;
                ItemStack seed = receiver.take(slot, expected);
                if (seed.isEmpty()) return;
                try (TreeFarmAction action = TreeFarmAction.open(level, core.owner(), pos, seed)) {
                    if (action == null) return;
                    try {
                        if (!active(level, core) || world.colony(core) != group || !receiver.present()
                                || !crop.canPlant(level, pos) || !level.mayInteract(action.actor, pos)) return;
                        BlockPos support = pos.above();
                        if (!level.mayInteract(action.actor, support)) return;
                        var hit = new BlockHitResult(new Vec3(support.getX() + .5, support.getY(), support.getZ() + .5),
                                Direction.DOWN, support, false);
                        // ItemStack, not Item: NeoForge captures placement, permissions, cancellation and restoration.
                        seed.useOn(new UseOnContext(action.actor, InteractionHand.MAIN_HAND, hit));
                        if (level.hasChunkAt(pos) && crop.matches(level.getBlockState(pos))) planting.placed();
                    } finally { seed = action.remainder(); }
                } finally { receiver.returnTo(slot, seed); }
                return; // At most one native placement per core per second, including cancelled attempts.
            }
        }
    }

    private static boolean spaced(ServerLevel level, BlockPos pos) {
        if (!level.isAreaLoaded(pos, 1)) return false;
        for (BlockPos near : BlockPos.betweenClosed(pos.offset(-1, -1, -1), pos.offset(1, 1, 1)))
            if (!near.equals(pos) && HangingCrop.forCrop(level.getBlockState(near)) != null) return false;
        return true;
    }
}
