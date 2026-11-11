package dev.senluo.witchcraft.ritual;

import dev.senluo.witchcraft.KaleidoscopeWitchcraft;
import dev.senluo.witchcraft.time.LivingBlock;
import dev.senluo.witchcraft.time.LivingEntity;
import dev.senluo.witchcraft.time.TemporalContent;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

@EventBusSubscriber(modid = KaleidoscopeWitchcraft.MOD_ID)
public final class RitualChalk {
    private static final Map<ServerLevel, Set<Long>> DIRTY = new WeakHashMap<>();
    private RitualChalk() {}

    public static LevelChunk chunk(Level level, BlockPos pos) {
        return level.getChunkSource().getChunk(pos.getX() >> 4, pos.getZ() >> 4, ChunkStatus.FULL, false)
                instanceof LevelChunk chunk ? chunk : null;
    }
    public static boolean wood(BlockState state) {
        return state.is(BlockTags.LOGS) || state.is(BlockTags.PLANKS)
                || state.is(dev.senluo.witchcraft.time.TreeWood.LOGS) || state.is(TemporalContent.CORE.get());
    }
    public static double surface(BlockGetter level, BlockPos pos, BlockState state) {
        if (!state.getFluidState().isEmpty()) return -1;
        // Keep full geometry, and honor blocks that deliberately expose no supporting face (e.g. crystals).
        return Block.isShapeFullBlock(state.getCollisionShape(level, pos))
                && Block.isShapeFullBlock(state.getShape(level, pos))
                && state.isFaceSturdy(level, pos, Direction.UP) ? 1 : -1;
    }
    static boolean permitted(Player player, Level level, BlockPos pos, ItemStack held) {
        return player != null && player.mayBuild() && !player.isSpectator() && level.mayInteract(player, pos)
                && player.mayUseItemAt(pos, Direction.UP, held)
                && (!(level.getBlockEntity(pos) instanceof LivingEntity body) || TreeOwnership.mayManage(player, body.tissue))
                && mayMarkTree(player, level, pos);
    }
    private static boolean mayMarkTree(Player player, Level level, BlockPos support) {
        if (!(level instanceof ServerLevel server) || !wood(level.getBlockState(support))) return true;
        if (level.getBlockEntity(support) instanceof LivingEntity core && !TreeOwnership.mayManage(player, core.tissue)) return false;
        // Include a newly connected log even when it was placed by automation before the next scheduler tick.
        dev.senluo.witchcraft.time.TemporalWorld.get(server).trees.beforeWoodRemoval(support);
        return dev.senluo.witchcraft.time.TreeClaims.get(server).mayInscribe(server, support, player);
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void protectChalk(net.neoforged.neoforge.event.level.BlockEvent.BreakEvent event) {
        if (event.getLevel() instanceof ServerLevel level
                && (event.getState().is(RitualContent.CHALK_SURFACE.get())
                    || event.getState().is(RitualContent.GOLDEN_HEART.get()))
                && !mayMarkTree(event.getPlayer(), level, event.getPos().below())) event.setCanceled(true);
    }
    static InteractionResult draw(UseOnContext context) {
        return inscribe(context, false);
    }
    static InteractionResult carve(UseOnContext context) {
        return inscribe(context, true);
    }
    private static InteractionResult inscribe(UseOnContext context, boolean knife) {
        Level level = context.getLevel();
        BlockPos clicked = context.getClickedPos();
        boolean clickedChalk = level.getBlockState(clicked).is(RitualContent.CHALK_SURFACE.get());
        // A selectable chalk layer and its support resolve to the same inscription.
        BlockPos pos = clickedChalk ? clicked.below() : clicked;
        // Unsupported knife targets keep their ordinary item/block interaction.
        // New carving is specific to years logs; broad wood recognition below remains
        // necessary for reading and preserving inscriptions in existing saves.
        if (knife && (clickedChalk || context.getClickedFace() != Direction.UP
                || !level.getBlockState(pos).is(dev.senluo.witchcraft.time.TreeWood.LOGS)))
            return InteractionResult.PASS;
        if (context.getClickedFace() != Direction.UP || !permitted(context.getPlayer(), level, pos, context.getItemInHand())
                || surface(level, pos, level.getBlockState(pos)) < 0) return InteractionResult.FAIL;
        LevelChunk chunk = chunk(level, pos);
        if (chunk == null) return InteractionResult.FAIL;
        if (level.isClientSide) return InteractionResult.SUCCESS;
        ChalkMarks marks = chunk.getData(RitualContent.CHALK_MARKS);
        if (!marks.supported()) { LivingBlock.tell(context.getPlayer(), "data_unavailable"); return InteractionResult.FAIL; }
        var existing = marks.get(pos);
        BlockPos abovePos = pos.above();
        BlockState above = level.getBlockState(abovePos);
        boolean thin = above.is(RitualContent.CHALK_SURFACE.get());
        boolean redraw;
        int rotation = ChalkPattern.rotationFor(context.getHorizontalDirection());
        if (!knife) {
            if (existing != null) return InteractionResult.CONSUME; // Chalk cannot overwrite an engraving.
            if ((!thin && !level.isEmptyBlock(abovePos))
                    || !permitted(context.getPlayer(), level, abovePos, context.getItemInHand())) return InteractionResult.FAIL;
            redraw = thin;
            int previous = thin ? above.getValue(ChalkSurfaceBlock.DESIGN) : -1;
            BlockState target = thin ? above : RitualContent.CHALK_SURFACE.get().defaultBlockState()
                    .setValue(ChalkSurfaceBlock.ROTATION, rotation);
            if (!level.setBlock(abovePos, target.setValue(ChalkSurfaceBlock.DESIGN,
                    ChalkPattern.nextChalkDesign(level.random, previous)), 3)) return InteractionResult.FAIL;
        } else {
            if (thin || existing != null && existing.medium() == 0) return InteractionResult.CONSUME;
            redraw = existing != null;
            if (!redraw && marks.entries().size() >= ChalkMarks.LIMIT) return InteractionResult.FAIL;
            var mark = new ChalkMarks.Mark(0, redraw ? existing.rotation() : rotation, 1,
                    ChalkPattern.nextCarvingDesign(level.random, redraw ? existing.design() : -1));
            if (!(redraw ? marks.replace(pos, mark) : marks.put(pos, mark))) return InteractionResult.FAIL;
            dev.senluo.witchcraft.time.TemporalWorld.get((ServerLevel) level).trees.rememberInscription(pos, mark);
        }
        changed((ServerLevel) level, chunk);
        // Cosmetic rerolls retain the existing no-wear repeat interaction.
        if (!knife && !redraw) ChalkUsage.consume(context);
        if (knife) level.playSound(null, pos, SoundEvents.AXE_STRIP, SoundSource.BLOCKS, .35F, 1.2F);
        else RitualSounds.chalk(level, pos);
        return InteractionResult.CONSUME;
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void erase(PlayerInteractEvent.RightClickBlock event) {
        Player player = event.getEntity();
        if (event.getUseBlock() == net.neoforged.neoforge.common.util.TriState.FALSE
                || event.getHand() != InteractionHand.MAIN_HAND || !player.isShiftKeyDown()
                || !player.getMainHandItem().isEmpty() || event.getFace() != Direction.UP
                || !permitted(player, event.getLevel(), event.getPos(), ItemStack.EMPTY)) return;
        Level level = event.getLevel();
        BlockPos clicked = event.getPos();
        boolean thin = level.getBlockState(clicked).is(RitualContent.CHALK_SURFACE.get());
        BlockPos support = thin ? clicked.below() : clicked;
        if (!permitted(player, level, support, ItemStack.EMPTY)) return;
        if (!thin) return; // Engravings are part of the wood and cannot be rubbed away.
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.sidedSuccess(level.isClientSide));
        if (level instanceof ServerLevel server) {
            level.removeBlock(clicked, false);
            level.playSound(null, clicked, SoundEvents.GRAVEL_HIT, SoundSource.BLOCKS, .3F, 1.4F);
        }
    }

    /** Physical chalk and an engraved host expose the same logical symbol to rituals. */
    static ChalkMarks.Mark mark(ServerLevel level, BlockPos support) {
        var chunk = chunk(level, support);
        if (chunk == null) return null;
        var above = level.getBlockState(support.above());
        if (above.is(RitualContent.CHALK_SURFACE.get())) return new ChalkMarks.Mark(0,
                above.getValue(ChalkSurfaceBlock.ROTATION), 0, above.getValue(ChalkSurfaceBlock.DESIGN));
        var records = chunk.getExistingDataOrNull(RitualContent.CHALK_MARKS);
        return records != null && records.supported() ? records.get(support) : null;
    }

    public static void restoreCarving(ServerLevel level, BlockPos pos, ChalkMarks.Mark mark) {
        if (mark == null || mark.medium() != 1 || !wood(level.getBlockState(pos))) return;
        var chunk = chunk(level, pos);
        if (chunk == null || level.getBlockState(pos.above()).is(RitualContent.CHALK_SURFACE.get())) return;
        var records = chunk.getData(RitualContent.CHALK_MARKS);
        if (records.get(pos) == null && records.put(pos, mark)) changed(level, chunk);
    }

    /** Quote preserving a real thin layer on its original supporting block, without editing it. */
    public static ChalkMarks.Mark coveringCarving(ServerLevel level, BlockPos layer) {
        if (!level.hasChunkAt(layer) || !level.hasChunkAt(layer.below())) return null;
        BlockState state = level.getBlockState(layer);
        if (!state.is(RitualContent.CHALK_SURFACE.get())) return null;
        LevelChunk chunk = chunk(level, layer.below());
        if (chunk == null) return null;
        ChalkMarks.Mark carving = new ChalkMarks.Mark(0, state.getValue(ChalkSurfaceBlock.ROTATION),
                0, state.getValue(ChalkSurfaceBlock.DESIGN)).asCarving();
        var records = chunk.getExistingDataOrNull(RitualContent.CHALK_MARKS);
        if (records != null && (!records.supported()
                || records.get(layer.below()) != null && !records.get(layer.below()).equals(carving)
                || records.get(layer.below()) == null && records.entries().size() >= ChalkMarks.LIMIT)) return null;
        return carving;
    }

    /** Publish the engraving before covering the layer, so callbacks never observe a missing array mark. */
    public static boolean coverChalk(ServerLevel level, BlockPos layer, BlockState replacement) {
        BlockPos support = layer.below();
        var mark = coveringCarving(level, layer);
        if (mark == null || !level.getBlockState(support).is(dev.senluo.witchcraft.time.TreeWood.LOGS)) return false;
        var chunk = chunk(level, support);
        var records = chunk.getData(RitualContent.CHALK_MARKS);
        boolean added = records.get(support) == null;
        if (added && !records.put(support, mark)) return false;
        boolean covered = false;
        try {
            covered = level.setBlock(layer, replacement, 3);
            return covered;
        } finally {
            // A notification may throw after setBlock has committed. Do not erase the surviving engraving.
            boolean committed = covered || level.getBlockState(layer).is(replacement.getBlock());
            if (committed && level.getBlockState(support).is(dev.senluo.witchcraft.time.TreeWood.LOGS)
                    && mark.equals(records.get(support)))
                dev.senluo.witchcraft.time.TemporalWorld.get(level).trees.rememberInscription(support, mark);
            else if (added && mark.equals(records.get(support))) records.remove(support);
            changed(level, chunk);
        }
    }

    /** Called after a successful world mutation, through the existing narrow geometry hook. */
    public static void blockChanged(ServerLevel level, BlockPos pos, BlockState before, BlockState after) {
        RitualShapeCache.changed(level, net.minecraft.world.level.ChunkPos.asLong(pos));
        LevelChunk chunk = chunk(level, pos);
        ChalkMarks marks = chunk == null ? null : chunk.getExistingDataOrNull(RitualContent.CHALK_MARKS);
        if (marks == null || marks.get(pos) == null) return;
        after = level.getBlockState(pos);
        var mark = marks.get(pos);
        if (wood(before) && mark.medium() != 0) {
            var carving = mark.asCarving();
            dev.senluo.witchcraft.time.TemporalWorld.get(level).trees.rememberInscription(pos, carving);
        }
        if (before.is(after.getBlock()) && surface(level, pos, after) >= 0) return;
        if (marks.remove(pos)) changed(level, chunk);
    }

    private static void changed(ServerLevel level, LevelChunk chunk) {
        dev.senluo.witchcraft.region.RegionRules.chunkChanged(level, chunk.getPos().toLong());
        RitualShapeCache.changed(level, chunk.getPos().toLong());
        chunk.setUnsaved(true);
        DIRTY.computeIfAbsent(level, key -> new LinkedHashSet<>()).add(chunk.getPos().toLong());
    }
    @SubscribeEvent
    public static void flush(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        Set<Long> changed = DIRTY.remove(level);
        if (changed == null) return;
        for (long key : changed) {
            var pos = new net.minecraft.world.level.ChunkPos(key);
            var chunk = level.getChunkSource().getChunkNow(pos.x, pos.z);
            if (chunk != null) chunk.syncData(RitualContent.CHALK_MARKS);
        }
    }
    @SubscribeEvent
    public static void unload(LevelEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel level) { DIRTY.remove(level); }
    }

    public static final class ChalkItem extends ChalkUsage.Item {
        public ChalkItem(Properties properties) { super(properties); }
        @Override public InteractionResult useOn(UseOnContext context) { return draw(context); }
        @Override public void appendHoverText(ItemStack stack, TooltipContext context,
                java.util.List<net.minecraft.network.chat.Component> lines, net.minecraft.world.item.TooltipFlag flag) {
            lines.add(dev.senluo.witchcraft.time.TemporalText.color(
                    dev.senluo.witchcraft.time.TemporalText.text("chalk.controls"), dev.senluo.witchcraft.time.TemporalText.ACTION));
        }
    }
}
