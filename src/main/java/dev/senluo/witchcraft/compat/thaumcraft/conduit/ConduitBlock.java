package dev.senluo.witchcraft.compat.thaumcraft.conduit;

import com.mojang.serialization.MapCodec;
import dev.tc4port.thaumcraft.api.essentia.EssentiaApi;
import dev.tc4port.thaumcraft.api.focus.behavior.FocusUseResult;
import dev.tc4port.thaumcraft.api.wand.pipeline.WandPipelineApi;
import dev.tc4port.thaumcraft.api.wand.pipeline.WandUseStages;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.core.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.*;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.*;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.*;
import net.minecraft.world.level.block.state.*;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.*;
import net.minecraft.world.phys.shapes.*;

/** Native-sized tube parts, with ordinary chunk-mesh rendering and sided wand controls. */
public final class ConduitBlock extends BaseEntityBlock {
    private static final ResourceLocation RESONATOR = ResourceLocation.fromNamespaceAndPath("thaumcraft", "resonator");
    private static final ResourceLocation TOOL_SOUND = ResourceLocation.fromNamespaceAndPath("thaumcraft", "tool");
    public static final MapCodec<ConduitBlock> CODEC = simpleCodec(ConduitBlock::new);
    enum Arm implements StringRepresentable {
        CLOSED("false"), OPEN("true"), EXTENDED("extended");
        private final String name;
        Arm(String name) { this.name = name; }
        @Override public String getSerializedName() { return name; }
    }
    private static final java.util.List<EnumProperty<Arm>> CONNECTIONS = java.util.Arrays.stream(Direction.values())
            .map(d -> EnumProperty.create(d.getName(), Arm.class)).toList();
    private static final VoxelShape CORE = Block.box(5.5, 5.5, 5.5, 10.5, 10.5, 10.5);
    private static final VoxelShape[] SHAPES = new VoxelShape[64];
    private static long nextConnectionWarning;
    static {
        for (int mask = 0; mask < 64; mask++) {
            VoxelShape shape = CORE;
            for (Direction d : Direction.values()) if ((mask & 1 << d.ordinal()) != 0) {
                double[] a = {6.72, 6.72, 6.72}, b = {9.28, 9.28, 9.28};
                int axis = d.getAxis().ordinal();
                a[axis] = d.getAxisDirection() == Direction.AxisDirection.NEGATIVE ? 0 : 8;
                b[axis] = d.getAxisDirection() == Direction.AxisDirection.POSITIVE ? 16 : 8;
                shape = Shapes.or(shape, Block.box(a[0], a[1], a[2], b[0], b[1], b[2]));
            }
            SHAPES[mask] = shape;
        }
    }
    public ConduitBlock(Properties properties) {
        super(properties); BlockState state = stateDefinition.any();
        for (var key : CONNECTIONS) state = state.setValue(key, Arm.CLOSED);
        registerDefaultState(state);
    }
    @Override protected MapCodec<? extends BaseEntityBlock> codec() { return CODEC; }
    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) { CONNECTIONS.forEach(key -> builder.add(key)); }
    @Override public BlockEntity newBlockEntity(BlockPos pos, BlockState state) { return new ConduitEntity(pos, state); }
    @Override protected RenderShape getRenderShape(BlockState state) { return RenderShape.MODEL; }
    @Override public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        return level.isClientSide ? null : createTickerHelper(type, ConduitContent.ENTITY.get(), ConduitEntity::tick);
    }
    @Override public BlockState getStateForPlacement(BlockPlaceContext context) {
        return connections(context.getLevel(), context.getClickedPos(), defaultBlockState());
    }
    @Override protected BlockState updateShape(BlockState state, Direction side, BlockState next, LevelAccessor level, BlockPos pos, BlockPos neighbor) {
        return connections(level, pos, state);
    }
    static BlockState connections(BlockGetter level, BlockPos pos, BlockState state) {
        var own = level.getBlockEntity(pos);
        for (Direction d : Direction.values()) {
            BlockPos next = pos.relative(d);
            Arm arm = Arm.CLOSED;
            if ((!(own instanceof ConduitEntity pipe) || pipe.open(d))
                    && (!(level instanceof LevelAccessor access) || access.hasChunk(next.getX() >> 4, next.getZ() >> 4))) {
                try {
                    var other = EssentiaApi.transport(level, next, d.getOpposite());
                    if (other != null && other.isConnectable(d.getOpposite()))
                        arm = other.renderExtendedTube() ? Arm.EXTENDED : Arm.OPEN;
                } catch (RuntimeException failure) {
                    connectionFailed(next, d.getOpposite(), failure);
                }
            }
            state = state.setValue(CONNECTIONS.get(d.ordinal()), arm);
        }
        return state;
    }
    private static synchronized void connectionFailed(BlockPos pos, Direction face, RuntimeException failure) {
        long now = System.nanoTime();
        if (now < nextConnectionWarning) return;
        nextConnectionWarning = now + 60_000_000_000L;
        org.slf4j.LoggerFactory.getLogger(ConduitBlock.class).warn(
                "Essentia connection query failed at {} face {}; repeated shape-query reports throttled for 60s", pos, face, failure);
    }
    static void refresh(ServerLevel level, BlockPos pos) {
        if (!level.hasChunkAt(pos) || !(level.getBlockState(pos).getBlock() instanceof ConduitBlock)) return;
        var old = level.getBlockState(pos); var next = connections(level, pos, old);
        if (old != next) level.setBlock(pos, next, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
    }
    @Override protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        int mask = 0;
        for (Direction d : Direction.values()) if (state.getValue(CONNECTIONS.get(d.ordinal())) != Arm.CLOSED) mask |= 1 << d.ordinal();
        return SHAPES[mask];
    }
    /** An arm's axis is not necessarily the normal of the narrow face the ray hit. */
    public static Direction inspectionFace(BlockPos pos, BlockHitResult hit) {
        Vec3 local = hit.getLocation().subtract(Vec3.atCenterOf(pos));
        double halfCore = 2.5 / 16.0 + 1.0E-6;
        return Math.max(Math.abs(local.x), Math.max(Math.abs(local.y), Math.abs(local.z))) <= halfCore
                ? hit.getDirection() : Direction.getNearest(local.x, local.y, local.z);
    }
    @Override protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos,
            Player player, InteractionHand hand, BlockHitResult hit) {
        if (!player.isSpectator() && BuiltInRegistries.ITEM.getKey(stack.getItem()).equals(RESONATOR)) {
            var probeHit = new BlockHitResult(hit.getLocation(), inspectionFace(pos, hit), pos, hit.isInside());
            var result = stack.getItem().useOn(new UseOnContext(player, hand, probeHit));
            if (result.consumesAction()) return ItemInteractionResult.sidedSuccess(level.isClientSide);
        }
        return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
    }
    /** Native WANDABLE owns this input. Block activation must not toggle the face a second time. */
    static void registerWandInteraction() {
        WandPipelineApi.aroundAll(dev.senluo.witchcraft.slash.SlashContent.id("conduit_wand"),
                WandUseStages.WANDABLE, 0, (use, next) -> {
                    var player = use.player();
                    var level = player.level();
                    if (!(use.hit() instanceof BlockHitResult hit) || !level.hasChunkAt(hit.getBlockPos())
                            || !(level.getBlockState(hit.getBlockPos()).getBlock() instanceof ConduitBlock))
                        return next.proceed(use);
                    if (player.isSpectator() || !player.mayBuild() || !level.mayInteract(player, hit.getBlockPos()))
                        return FocusUseResult.FAIL;
                    if (!(level.getBlockEntity(hit.getBlockPos()) instanceof ConduitEntity pipe)) return FocusUseResult.FAIL;
                    if (!level.isClientSide) {
                        pipe.toggle(inspectionFace(hit.getBlockPos(), hit));
                        BuiltInRegistries.SOUND_EVENT.getOptional(TOOL_SOUND).ifPresent(sound ->
                                level.playSound(null, hit.getBlockPos(), sound, SoundSource.BLOCKS,
                                        .5F, .9F + level.random.nextFloat() * .2F));
                    }
                    return FocusUseResult.SUCCESS;
                });
    }
    @Override public boolean onDestroyedByPlayer(BlockState state, Level level, BlockPos pos, Player player,
            boolean willHarvest, FluidState fluid) {
        ConduitEntity pipe = level.getBlockEntity(pos) instanceof ConduitEntity value ? value : null;
        boolean previous = pipe != null && pipe.suppressDrop;
        if (pipe != null && player.isCreative()) pipe.suppressDrop = true;
        try { return super.onDestroyedByPlayer(state, level, pos, player, willHarvest, fluid); }
        finally { if (pipe != null) pipe.suppressDrop = previous; }
    }
    @Override protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState next, boolean moving) {
        if (!level.restoringBlockSnapshots && !state.is(next.getBlock()) && !level.isClientSide && level.getBlockEntity(pos) instanceof ConduitEntity pipe && !pipe.suppressDrop)
            Block.popResource(level, pos, pipe.takeItem());
        super.onRemove(state, level, pos, next, moving);
    }
}
