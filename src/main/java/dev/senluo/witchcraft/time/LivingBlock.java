package dev.senluo.witchcraft.time;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.tags.ItemTags;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

public final class LivingBlock extends Block implements EntityBlock, net.minecraft.world.level.block.BonemealableBlock {
    public static final IntegerProperty AGE = IntegerProperty.create("age", 0, 4);
    private final boolean amber;
    private final boolean core;
    public LivingBlock(boolean amber, Properties properties) {
        this(amber, false, properties);
    }
    public LivingBlock(boolean amber, boolean core, Properties properties) {
        super(properties); this.amber = amber; this.core = core;
        registerDefaultState(stateDefinition.any().setValue(AGE, 0));
    }
    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) { builder.add(AGE); }
    @Override public BlockEntity newBlockEntity(BlockPos pos, BlockState state) { return new LivingEntity(pos, state); }
    @Override public ItemStack getCloneItemStack(LevelReader level, BlockPos pos, BlockState state) {
        return core ? TemporalContent.CORE_ITEM.get().getDefaultInstance() : super.getCloneItemStack(level,pos,state);
    }
    @Override public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        return !level.isClientSide && type == TemporalContent.LIVING_ENTITY.get()
                ? (world, pos, blockState, entity) -> DevelopmentTime.tick((LivingEntity) entity) : null;
    }
    @Override protected VoxelShape getShape(BlockState state, BlockGetter world, BlockPos pos, CollisionContext context) {
        if (amber || core) return net.minecraft.world.phys.shapes.Shapes.block();
        int age = state.getValue(AGE);
        double radius = new double[]{2.5, 3.5, 5, 6, 7}[age];
        return Block.box(8-radius, 0, 8-radius, 8+radius, new int[]{5, 7, 9, 11, 12}[age], 8+radius);
    }
    @Override protected boolean canSurvive(BlockState state, LevelReader world, BlockPos pos) {
        return amber || core || validSoil(world.getBlockState(pos.below()));
    }
    private boolean validSoil(BlockState soil) { return TissueItem.coreSoil(soil) || soil.is(dev.senluo.witchcraft.time.TreeWood.LOGS); }
    @Override protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState previous, boolean moved) {
        super.onPlace(state, level, pos, previous, moved);
        if (!amber && !state.is(previous.getBlock())) {
            RootBlock.refresh(level, pos.below());
            RootBlock.refresh(level, pos.below(2));
        }
    }
    @Override protected BlockState updateShape(BlockState state, Direction direction, BlockState neighbor,
                                                LevelAccessor world, BlockPos pos, BlockPos other) {
        if (!amber && !core && direction == Direction.DOWN && !validSoil(neighbor)) return Blocks.AIR.defaultBlockState();
        return super.updateShape(state, direction, neighbor, world, pos, other);
    }
    @Override protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (!player.getMainHandItem().isEmpty() || !player.getOffhandItem().isEmpty()) return InteractionResult.PASS;
        if (amber && !player.isShiftKeyDown()) return InteractionResult.PASS;
        if (level.isClientSide) return InteractionResult.SUCCESS;
        if (!(level.getBlockEntity(pos) instanceof LivingEntity entity) || !canFeed(level, pos, player, entity))
            return InteractionResult.FAIL;
        Colony colony = TemporalWorld.get((ServerLevel) level).colony(entity);
        if (player.isShiftKeyDown()) {
            if (!canSpend(level, player, entity)) return InteractionResult.FAIL;
            colony.chooseMode(SupplyMode.next(colony.productionMode()), level.getGameTime(), java.util.UUID.randomUUID());
            tell(player, "mode." + colony.productionMode());
            return InteractionResult.CONSUME;
        }
        player.displayClientMessage(stockMessage(entity), true);
        return InteractionResult.CONSUME;
    }

    @Override protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos,
                                                       Player player, InteractionHand hand, BlockHitResult hit) {
        if (stack.is(TemporalContent.TISSUE.get()) && level.getBlockEntity(pos) instanceof LivingEntity mother)
            return TissueItem.graft(new UseOnContext(player, hand, hit), mother).consumesAction()
                    ? ItemInteractionResult.SUCCESS : ItemInteractionResult.FAIL;
        if (!stack.is(Items.LEVER) && !stack.is(Items.BONE_MEAL) && !stack.is(Items.GOLD_NUGGET)
                && !stack.is(Items.GOLD_INGOT) && !stack.is(Items.CLOCK) && !stack.is(ItemTags.SHOVELS))
            return stack.isEmpty() ? ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION
                    : ItemInteractionResult.SKIP_DEFAULT_BLOCK_INTERACTION;
        if (level.isClientSide) return ItemInteractionResult.SUCCESS;
        if (!(level.getBlockEntity(pos) instanceof LivingEntity entity)) return ItemInteractionResult.FAIL;
        boolean feed = stack.is(Items.BONE_MEAL)
                || stack.is(Items.CLOCK) && !player.isShiftKeyDown();
        if (!(feed ? canFeed(level, pos, player, entity) : canUse(level, pos, player, entity)))
            return ItemInteractionResult.FAIL;
        Colony colony = TemporalWorld.get((ServerLevel) level).colony(entity);
        if (stack.is(Items.LEVER)) {
            if (entity.amber()) return ItemInteractionResult.FAIL;
            Tissue tissue = entity.tissue;
            if (!sourceAvailable(entity, tissue.selectedSource)) tissue.selectedSource = 0;
            else if (!player.isShiftKeyDown()) {
                do { tissue.selectedSource = (tissue.selectedSource + 1) % 5; }
                while (!sourceAvailable(entity, tissue.selectedSource));
            }
            if (player.isShiftKeyDown()) tissue.disabledSources ^= 1 << tissue.selectedSource;
            entity.changed();
            tell(player, "source_state",
                    TemporalText.color(TemporalText.text("source." + tissue.selectedSource), TemporalText.ACTION),
                    TemporalText.color(TemporalText.text(tissue.sourceEnabled(tissue.selectedSource) ? "source.on" : "source.off"),
                            tissue.sourceEnabled(tissue.selectedSource) ? TemporalText.READY : TemporalText.PENDING));
            return ItemInteractionResult.SUCCESS;
        }
        if (stack.is(Items.BONE_MEAL)) {
            int advanced = DevelopmentTime.fertilize(entity);
            if (advanced > 0) {
                stack.consume(1, player);
                tell(player, "growth_hastened", TemporalText.number((advanced + 19) / 20, TemporalText.TIME));
            } else tell(player, "no_growth_time");
            return ItemInteractionResult.SUCCESS;
        }
        if (stack.is(Items.GOLD_INGOT)) {
            if (!canSpend(level, player, entity)) return ItemInteractionResult.FAIL;
            boolean selectedOnly = player.isShiftKeyDown();
            GrowthCatalysis.Quote quote = selectedOnly ? GrowthCatalysis.quoteSide(entity) : GrowthCatalysis.quote(entity);
            if (quote.ticks() == 0) tell(player, "no_growth_time");
            else if (!selectedOnly && !GrowthCatalysis.hasSpace(entity))
                player.displayClientMessage(stockMessage(entity).copy().append(TemporalText.message("tree_space_short")), true);
            else if (selectedOnly ? GrowthCatalysis.completeSide(entity) : GrowthCatalysis.complete(entity)) {
                stack.consume(1, player);
                tell(player, "growth_hastened", TemporalText.number((quote.ticks() + 19) / 20, TemporalText.TIME));
            } else tell(player, "need_time", TemporalText.number(formatTime(quote.milliTu()), TemporalText.AMOUNT));
            return ItemInteractionResult.SUCCESS;
        }
        if (stack.is(ItemTags.SHOVELS)) {
            int selectedIndex = entity.tissue.selectedSideIndex();
            Tissue picked = amber ? null : colony.harvest(entity);
            if (picked == null) player.displayClientMessage(sideFocusMessage(entity), true);
            else {
                give(player, TissueItem.stack(picked));
                stack.hurtAndBreak(1, player, hand == InteractionHand.MAIN_HAND ? EquipmentSlot.MAINHAND : EquipmentSlot.OFFHAND);
                if (entity.tissue.giant) tell(player, "tree_root_harvested");
                else tell(player, "harvested", TemporalText.number(selectedIndex + 1, TemporalText.AMOUNT));
            }
            return ItemInteractionResult.SUCCESS;
        }
        if (stack.is(Items.CLOCK)) {
            if (player.isShiftKeyDown() && !entity.tissue.giant && !entity.tissue.sides.isEmpty()) {
                entity.tissue.selectedSide = (entity.tissue.selectedSideIndex() + 1) % entity.tissue.sides.size();
                entity.changed();
                player.displayClientMessage(sideFocusMessage(entity), true);
            } else player.displayClientMessage(entity.tissue.giant ? sideFocusMessage(entity) : stockMessage(entity), true);
            return ItemInteractionResult.SUCCESS;
        }
        if (stack.is(Items.GOLD_NUGGET)) {
            boolean changed = player.isShiftKeyDown() ? colony.releaseSide(entity) : colony.chargeSide(entity);
            if (changed && !entity.tissue.giant) tell(player, player.isShiftKeyDown() ? "uncharged" : "charged",
                    TemporalText.number(entity.tissue.selectedSideIndex() + 1, TemporalText.AMOUNT));
            else player.displayClientMessage(sideFocusMessage(entity), true);
            entity.changed();
            return ItemInteractionResult.SUCCESS;
        }
        return ItemInteractionResult.SUCCESS;
    }

    private static boolean sourceAvailable(LivingEntity entity, int source) {
        return source >= 0 && source < 5 && !entity.amber()
                && (source == 0 || entity.tree() && (source == 1 || entity.tissue.giant));
    }

    private static void give(Player player, ItemStack stack) {
        if (!player.getInventory().add(stack)) player.drop(stack, false);
    }

    @Override public boolean isValidBonemealTarget(LevelReader level, BlockPos pos, BlockState state) {
        return level.getBlockEntity(pos) instanceof LivingEntity entity && !entity.hasOwner()
                && DevelopmentTime.canFertilize(entity);
    }

    @Override public boolean isBonemealSuccess(Level level, net.minecraft.util.RandomSource random, BlockPos pos, BlockState state) {
        return isValidBonemealTarget(level, pos, state);
    }

    @Override public void performBonemeal(ServerLevel level, net.minecraft.util.RandomSource random, BlockPos pos, BlockState state) {
        if (isValidBonemealTarget(level, pos, state) && level.getBlockEntity(pos) instanceof LivingEntity entity)
            DevelopmentTime.fertilize(entity);
    }

    /** Spending from connected tissue requires permission at every contributing organism. */
    public static boolean canSpend(Level level, Player player, LivingEntity entity) {
        if (!(level instanceof ServerLevel server)) return false;
        for (LivingEntity member : TemporalWorld.get(server).colony(entity).members)
            if (!canUse(level, member.getBlockPos(), player, member)) return false;
        return true;
    }

    public static boolean canUse(Level level, BlockPos pos, Player player, LivingEntity entity) {
        if (!canFeed(level, pos, player, entity)) return false;
        if (!dev.senluo.witchcraft.ritual.TreeOwnership.mayManage(player, entity.tissue)) {
            if (!level.isClientSide) dev.senluo.witchcraft.ritual.TreeOwnership.denied(player);
            return false;
        }
        return true;
    }
    public static boolean canFeed(Level level, BlockPos pos, Player player, LivingEntity entity) {
        if (!entity.tissue.supported()) {
            if (!level.isClientSide) tell(player, "data_unavailable");
            return false;
        }
        return player.mayBuild() && !player.isSpectator() && level.mayInteract(player, pos);
    }

    @Override public boolean onDestroyedByPlayer(BlockState state, Level level, BlockPos pos, Player player,
            boolean willHarvest, net.minecraft.world.level.material.FluidState fluid) {
        if (level.getBlockEntity(pos) instanceof LivingEntity body
                && !dev.senluo.witchcraft.ritual.TreeOwnership.mayManage(player, body.tissue)) {
            dev.senluo.witchcraft.ritual.TreeOwnership.restoreBody(player, body);
            return false;
        }
        return super.onDestroyedByPlayer(state, level, pos, player, willHarvest, fluid);
    }
    public static void tell(Player player, String key, Object... args) { player.displayClientMessage(TemporalText.notice(key, args), true); }
    public static String formatTime(long milliTu) { return TemporalText.exact(milliTu); }
    public static String formatHudTime(long milliTu) { return TemporalText.compact(milliTu); }
    public static Component stockMessage(LivingEntity entity) {
        if (!entity.tissue.supported()) return TemporalText.notice("data_unavailable");
        var text = TemporalText.message(entity.amber() ? "amber_stock" : entity.tree() ? "core_stock" : "stock",
                TemporalText.amount(entity.tissue.coreQuantity(), false), TemporalText.capacity(entity.tissue.coreCapacity(), false));
        if (entity.tissue.treeGrowing) text.append(TemporalText.message(entity.tissue.treeTicks >= TreeBalance.ENTRY_TICKS
                ? "tree_space_short" : "tree_time_short", TemporalText.duration(TreeBalance.remaining(entity.tissue))));
        if(entity.tissue.giantGrowing)text.append(TemporalText.message(entity.tissue.giantTicks>=TreeBalance.GIANT_TICKS?"tree_space_short":"tree_time_short",TemporalText.duration(TreeBalance.remaining(entity.tissue))));
        RootDisplay root = RootDisplay.selected(entity.tissue);
        if (root != null && !entity.amber() && (root.production() || !entity.tissue.treeGrowing)) text.append(root.summary());
        return text;
    }

    public static Component sideFocusMessage(LivingEntity entity) {
        if (entity.amber()) return stockMessage(entity);
        RootDisplay root = RootDisplay.selected(entity.tissue);
        if (root != null) return root.focus();
        if (!entity.tissue.established()) return growthMessage(entity.tissue.growth);
        return TemporalText.text("slots",
                TemporalText.number(entity.tissue.sides.size(), TemporalText.AMOUNT),
                TemporalText.number(entity.tissue.slots(), TemporalText.CAPACITY), TemporalText.number(0, TemporalText.READY));
    }

    public static Component growthMessage(Tissue.Growth growth) {
        return TemporalText.growth(growth);
    }

    @Override public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
        if (!level.isClientSide && level.getBlockEntity(pos) instanceof LivingEntity entity) {
            var silk = level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.SILK_TOUCH);
            entity.preserveSidesOnBreak = EnchantmentHelper.getItemEnchantmentLevel(silk, player.getMainHandItem()) > 0;
        }
        return super.playerWillDestroy(level, pos, state, player);
    }
    @Override protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState replacement, boolean moved) {
        if (!level.restoringBlockSnapshots && !state.is(replacement.getBlock()) && level instanceof ServerLevel server
                && level.getBlockEntity(pos) instanceof LivingEntity entity) {
            TemporalWorld world = TemporalWorld.get(server);
            world.colony(entity).equalize();
            if (core) {
                world.trees.beforeSnapshot(entity);
                for (FruitEntity fruit : world.trees.fruits(entity)) fruit.loseCore();
                world.trees.releaseSite(entity);
            }
            // Detach from the shared view before snapshots; a second split-time equalization
            // must not change balances after the item copies have already been created.
            world.remove(pos);
            if (!entity.transforming) {
                var drops = core || entity.preserveSidesOnBreak ? java.util.List.of(entity.tissue) : entity.tissue.detachAll();
                for (Tissue tissue : drops) popResource(level, pos, TissueItem.stack(tissue));
            }
            if (amber) world.restoreHost(pos, entity.host);
        }
        super.onRemove(state, level, pos, replacement, moved);
        if (!amber && !state.is(replacement.getBlock())) {
            RootBlock.refresh(level, pos.below());
            RootBlock.refresh(level, pos.below(2));
        }
    }
}
