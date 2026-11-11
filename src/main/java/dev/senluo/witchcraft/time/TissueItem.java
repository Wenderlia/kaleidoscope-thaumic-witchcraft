package dev.senluo.witchcraft.time;

import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.server.level.ServerLevel;

public final class TissueItem extends Item {
    public TissueItem(Properties properties) { super(properties); }
    public static ItemStack stack(Tissue tissue) {
        ItemStack stack = new ItemStack(tissue.tree ? TemporalContent.CORE_ITEM.get() : TemporalContent.TISSUE.get());
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(TissueTag.save(tissue)));
        return stack;
    }
    public static boolean soil(BlockState s) {
        return s.is(Blocks.FARMLAND) || s.is(TemporalContent.ROOTED_FARMLAND.get());
    }
    public static boolean rootSoil(BlockState s) {
        return s.is(Blocks.DIRT) || s.is(Blocks.GRASS_BLOCK) || s.is(Blocks.COARSE_DIRT) || s.is(Blocks.ROOTED_DIRT);
    }
    public static boolean coreSoil(BlockState s) {
        return soil(s) || rootSoil(s) || s.is(TemporalContent.ROOT.get()) && s.getValue(RootBlock.EMBEDDED);
    }
    public static boolean woodySoil(BlockState s) {
        return rootSoil(s) || s.is(TemporalContent.ROOT.get()) && s.getValue(RootBlock.EMBEDDED);
    }
    public static Tissue read(ItemStack stack) {
        Tissue tissue = TissueTag.load(stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag());
        if (stack.is(TemporalContent.CORE_ITEM.get())) {
            tissue.tree = true; tissue.rootStage = 4; tissue.treeGrowing = false; tissue.treeTicks = 0;
        }
        return tissue;
    }
    public static int stoneGrade(BlockState s) {
        if (s.is(Blocks.OBSIDIAN) || s.is(Blocks.CRYING_OBSIDIAN)) return 3;
        if (s.is(Blocks.DEEPSLATE) || s.is(Blocks.BASALT) || s.is(Blocks.SMOOTH_BASALT)) return 2;
        if (s.is(Blocks.STONE) || s.is(Blocks.ANDESITE) || s.is(Blocks.GRANITE) || s.is(Blocks.DIORITE) || s.is(Blocks.TUFF)) return 1;
        return 0;
    }
    @Override public InteractionResult useOn(UseOnContext context) {
        var level = context.getLevel();
        boolean core = context.getItemInHand().is(TemporalContent.CORE_ITEM.get());
        BlockPos clicked = context.getClickedPos();
        if (level.getBlockEntity(clicked) instanceof LivingEntity mother) return core ? InteractionResult.FAIL : graft(context, mother);
        BlockState host = level.getBlockState(clicked);
        boolean amber = !core && stoneGrade(host) > 0;
        BlockPos placed = amber ? clicked : core ? clicked.relative(context.getClickedFace()) : clicked.above();
        var player = context.getPlayer();
        if (player == null || !player.mayBuild() || !level.mayInteract(player, placed)
                || !player.mayUseItemAt(placed, context.getClickedFace(), context.getItemInHand())
                || !level.isInWorldBounds(placed) || !level.hasChunkAt(placed)
                || (!amber && ((!core && !coreSoil(host)) || !level.getBlockState(placed).isAir()))
                || !core && level.getBlockEntity(clicked) != null && !RootConnections.isRoot(host)) return InteractionResult.FAIL;
        if (level.isClientSide) return InteractionResult.SUCCESS;
        Tissue tissue = read(context.getItemInHand());
        if (!tissue.supported()) { LivingBlock.tell(player, "data_unavailable"); return InteractionResult.FAIL; }
        if (!dev.senluo.witchcraft.ritual.TreeOwnership.mayManage(player, tissue)) {
            dev.senluo.witchcraft.ritual.TreeOwnership.denied(player);
            return InteractionResult.FAIL;
        }
        BlockState state = (core ? TemporalContent.CORE : amber ? TemporalContent.AMBER : TemporalContent.RHIZOME).get().defaultBlockState()
                .setValue(LivingBlock.AGE, amber ? tissue.amberStage : tissue.harvestAge());
        TreeOrientation.place(tissue, context.getHorizontalDirection().getOpposite());
        if (!level.setBlock(placed, state, 3)) return InteractionResult.FAIL;
        if (level.getBlockEntity(placed) instanceof LivingEntity entity) {
            entity.tissue = tissue;
            if (amber) entity.host = host;
            else GiantRoots.restore(tissue);
            entity.changed();
            TemporalWorld.get((ServerLevel) level).add(entity);
            context.getItemInHand().shrink(1);
        }
        return InteractionResult.CONSUME;
    }

    static InteractionResult graft(UseOnContext context, LivingEntity mother) {
        var player = context.getPlayer();
        if (player == null || !player.mayBuild() || !context.getLevel().mayInteract(player, context.getClickedPos())
                || !player.mayUseItemAt(context.getClickedPos(), context.getClickedFace(), context.getItemInHand())) return InteractionResult.FAIL;
        if (context.getLevel().isClientSide) return InteractionResult.SUCCESS;
        if (!LivingBlock.canUse(context.getLevel(), context.getClickedPos(), player, mother)) return InteractionResult.FAIL;
        Tissue incoming = read(context.getItemInHand());
        if (!incoming.supported()) { LivingBlock.tell(player, "data_unavailable"); return InteractionResult.FAIL; }
        if (!dev.senluo.witchcraft.ritual.TreeOwnership.mayManage(player, incoming)
                || !dev.senluo.witchcraft.ritual.TreeOwnership.canGraft(mother.tissue, incoming)) {
            dev.senluo.witchcraft.ritual.TreeOwnership.denied(player);
            return InteractionResult.FAIL;
        }
        Colony colony = TemporalWorld.get((ServerLevel) context.getLevel()).colony(mother);
        var returned = new java.util.ArrayList<Tissue>();
        if (!colony.graft(mother, incoming, returned::add)) {
            LivingBlock.tell(player, mother.amber() ? "graft_rhizome_only" : mother.tissue.rootStage == 0 ? "too_young"
                    : mother.tissue.sides.size() >= mother.tissue.slots() ? "side_full" : "graft_complex");
            return InteractionResult.FAIL;
        }
        context.getItemInHand().shrink(1);
        for (Tissue previous : returned) {
            ItemStack drop = stack(previous);
            if (!player.getInventory().add(drop)) player.drop(drop, false);
        }
        player.displayClientMessage(LivingBlock.stockMessage(mother), true);
        return InteractionResult.CONSUME;
    }
    @Override public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        TemporalTooltips.summary(stack, tooltip);
    }
}
