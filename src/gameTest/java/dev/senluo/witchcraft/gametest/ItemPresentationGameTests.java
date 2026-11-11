package dev.senluo.witchcraft.gametest;

import dev.senluo.witchcraft.time.*;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.util.Unit;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@PrefixGameTestTemplate(false)
public final class ItemPresentationGameTests {
    @GameTest(templateNamespace="kaleidoscope_witchcraft",template="empty",timeoutTicks=60)
    public static void editedTooltipsKeepBrightKeysAndMinimalFruit(GameTestHelper h) {
        Tissue tissue=new Tissue();tissue.rootStage=1;
        var lines=tooltip(TissueItem.stack(tissue));
        var hint=find(lines,"expand");
        Object[] keys=((TranslatableContents)hint.getContents()).getArgs();
        h.assertTrue(((Component)keys[0]).getStyle().getColor().getValue()==0xFFAA00
                && ((Component)keys[1]).getStyle().getColor().getValue()==0x55FFFF
                && TemporalTooltips.source().getStyle().getColor().getValue()==ChatFormatting.BLUE.getColor(),
                "Key hints use bright gold/aqua and the source uses conventional mod-name blue");
        TemporalTooltips.expand(TissueItem.stack(tissue),lines,true,false,true);
        h.assertTrue(find(lines,"controls_heading").getStyle().getColor().getValue()==TemporalText.SHIFT
                && find(lines,"controls.unfinished")==null && find(lines,"controls.rooting")==null,
                "User-deleted controls stay removed and the expanded heading retains its key color");
        var root=tooltip(RootItem.stack());
        h.assertTrue(find(root,"root.reset")==null,"Deleted root summary stays absent");
        List<Component> fruit=new ArrayList<>();
        FruitItem.stack(3).getItem().appendHoverText(FruitItem.stack(3),Item.TooltipContext.of(h.getLevel()),fruit,TooltipFlag.NORMAL);
        h.assertTrue(fruit.size()==2 && find(fruit,"fruit.quality")!=null
                && find(fruit,"fruit.time")==fruit.getLast()
                && fruit.getLast().getStyle().getColor().getValue()==TemporalText.LORE
                && fruit.getLast().getStyle().isItalic(),
                "Fruit keeps its quality and one purple lore line without a new expanded instruction list");
        h.succeed();
    }
    private static void place(GameTestHelper h, Player player, ItemStack stack, BlockPos clicked) {
        player.setItemInHand(InteractionHand.MAIN_HAND, stack);
        stack.useOn(new UseOnContext(player, InteractionHand.MAIN_HAND,
                new BlockHitResult(Vec3.atCenterOf(clicked), Direction.UP, clicked, false)));
    }

    @GameTest(templateNamespace="kaleidoscope_witchcraft", template="empty", timeoutTicks=60)
    public static void rootsDropAsOneMaterialAndReplantYoung(GameTestHelper h) {
        Player player = h.makeMockPlayer(GameType.SURVIVAL);
        for (int i = 0; i < 2; i++) {
            BlockPos pos = h.absolutePos(new BlockPos(3 + 3 * i, 2, 3));
            h.getLevel().setBlock(pos, TemporalContent.ROOT.get().defaultBlockState().setValue(RootBlock.AGE, 3), 3);
            RootEntity root = (RootEntity) h.getLevel().getBlockEntity(pos);
            root.ticks = i == 0 ? 1861 : 3600;
            CompoundTag saved = root.saveWithoutMetadata(h.getLevel().registryAccess());
            root.loadWithComponents(saved, h.getLevel().registryAccess());
            h.assertTrue(root.ticks == (i == 0 ? 1861 : 3600), "Placed roots retain exact age across reload");
            h.assertTrue(ItemStack.isSameItemSameComponents(TemporalContent.ROOT.get().getCloneItemStack(h.getLevel(), pos, root.getBlockState()), RootItem.stack()),
                    "Creative pick-block uses the same standard material as drops");
            h.getLevel().destroyBlock(pos, true);
            List<ItemEntity> drops = h.getLevel().getEntitiesOfClass(ItemEntity.class, new AABB(pos).inflate(1.5)).stream()
                    .filter(e -> e.getItem().is(TemporalContent.ROOT_PIECE.get())).toList();
            h.assertTrue(drops.size() == 1, "Breaking each root produces exactly one root material");
            ItemStack drop = drops.getFirst().getItem().copy();
            h.assertTrue(ItemStack.isSameItemSameComponents(drop, RootItem.stack()), "Every root age matches the creative/default material");
            player.getInventory().add(drop);
            drops.getFirst().discard();
        }
        List<ItemStack> roots = player.getInventory().items.stream().filter(s -> s.is(TemporalContent.ROOT_PIECE.get())).toList();
        h.assertTrue(roots.size() == 1 && roots.getFirst().getCount() == 2, "Different-age drops merge in the actual inventory");
        BlockPos soil = h.absolutePos(new BlockPos(4, 2, 6));
        h.getLevel().setBlock(soil, Blocks.DIRT.defaultBlockState(), 3);
        ItemStack material = roots.getFirst();
        place(h, player, material, soil);
        RootEntity planted = (RootEntity) h.getLevel().getBlockEntity(soil);
        h.assertTrue(planted != null && planted.ticks == 0 && planted.getBlockState().getValue(RootBlock.AGE) == 0
                && material.getCount() == 1, "Replanting spends one material and starts as a young root");
        h.succeed();
    }

    @GameTest(templateNamespace="kaleidoscope_witchcraft", template="empty", timeoutTicks=60)
    public static void rootPlacementStartsYoungAndRespectsCreativeItems(GameTestHelper h) {
        BlockPos soil = h.absolutePos(new BlockPos(3, 2, 3));
        h.getLevel().setBlock(soil, Blocks.DIRT.defaultBlockState(), 3);
        Player player = h.makeMockPlayer(GameType.SURVIVAL);
        ItemStack material = RootItem.stack().copyWithCount(2);
        place(h, player, material, soil);
        RootEntity root = (RootEntity) h.getLevel().getBlockEntity(soil);
        h.assertTrue(root != null && root.ticks == 0 && material.getCount() == 1 && !material.has(DataComponents.CUSTOM_DATA),
                "Placing one current root material starts it young");
        BlockPos blocked = h.absolutePos(new BlockPos(6, 2, 3));
        h.getLevel().setBlock(blocked, Blocks.STONE.defaultBlockState(), 3);
        h.getLevel().setBlock(blocked.above(), Blocks.STONE.defaultBlockState(), 3);
        place(h, player, material, blocked);
        h.assertTrue(material.getCount() == 1 && h.getLevel().getBlockState(blocked.above()).is(Blocks.STONE),
                "An invalid placement never consumes the material");
        Player creative = h.makeMockPlayer(GameType.CREATIVE);
        // The helper overrides isCreative(), but leaves abilities at survival defaults.
        GameType.CREATIVE.updatePlayerAbilities(creative.getAbilities());
        ItemStack infinite = RootItem.stack();
        BlockPos airBase = h.absolutePos(new BlockPos(3, 2, 6));
        h.getLevel().setBlock(airBase, Blocks.STONE.defaultBlockState(), 3);
        place(h, creative, infinite, airBase);
        h.assertTrue(h.getLevel().getBlockEntity(airBase.above()) instanceof RootEntity,
                "Creative placement can create an exposed root");
        h.assertTrue(infinite.getCount() == 1, "Creative placement follows the infinite-item rule");
        h.succeed();
    }

    private static List<Component> tooltip(ItemStack stack) {
        return new ArrayList<>(stack.getTooltipLines(Item.TooltipContext.EMPTY, null, TooltipFlag.NORMAL));
    }

    private static Component find(List<Component> lines, String suffix) {
        return lines.stream().filter(c -> c.getContents() instanceof TranslatableContents t
                && t.getKey().equals("tooltip.kaleidoscope_witchcraft." + suffix)).findFirst().orElse(null);
    }

    @GameTest(templateNamespace="kaleidoscope_witchcraft", template="empty", timeoutTicks=60)
    public static void tooltipSnapshotsSeparateStorageFundingAndMaturity(GameTestHelper h) {
        Tissue tissue = new Tissue(); tissue.rootStage = 2; tissue.setCoreQuantity(123456);
        Tissue.Side side = new Tissue.Side(1); tissue.sides.add(side);
        side.tissue.setCoreQuantity(50 * Tissue.TU); side.tissue.growth.invested = Tissue.SIDE_FEE[1];
        side.tissue.growth.ticks = Tissue.SIDE_TIME[1] - 1;
        ItemStack stack = TissueItem.stack(tissue);
        CustomData before = stack.get(DataComponents.CUSTOM_DATA);
        List<Component> lines = tooltip(stack);
        Object[] total = ((TranslatableContents)find(lines, "total").getContents()).getArgs();
        h.assertTrue(((Component) total[0]).getString().equals("173.456"), "Inventory summary counts the core plus its side, exactly once");
        h.assertTrue(find(lines, "total").getStyle().getColor().getValue() == TemporalText.BODY
                && ((Component) total[0]).getStyle().getColor().getValue() == TemporalText.AMOUNT
                && ((Component) total[1]).getStyle().getColor().getValue() == TemporalText.CAPACITY,
                "Labels, current storage and capacity have distinct shared semantic colors");
        TemporalTooltips.expand(stack, lines, false, true, false);
        Object[] core = ((TranslatableContents)find(lines, "core").getContents()).getArgs();
        h.assertTrue(((Component) core[0]).getString().equals("123.456") && find(lines, "investment") != null && find(lines, "growth_time") != null,
                "Core storage, spent investment and simulated growth are distinct facts");
        h.assertTrue(find(lines, "harvest_ready") == null && before.equals(stack.get(DataComponents.CUSTOM_DATA)),
                "A last unfinished growth tick prevents harvest; displaying must not mutate the item");
        side.tissue.growth.ticks++;
        stack = TissueItem.stack(tissue); lines = tooltip(stack);
        TemporalTooltips.expand(stack, lines, false, true, false);
        h.assertTrue(find(lines, "harvest_ready") != null && find(lines, "harvest_ready").getStyle().getColor().getValue() == ChatFormatting.GREEN.getColor(),
                "Maturity uses the harvest predicate and does not require full storage");
        List<Component> fresh = tooltip(TissueItem.stack(new Tissue()));
        h.assertTrue(find(fresh, "total") != null && find(fresh, "sides") == null, "Zero inventory remains visible; absent sides do not add noise");
        side.tissue.growth.invested = 0;
        Component missing = LivingBlock.growthMessage(side.tissue.growth);
        Component cost = (Component)((TranslatableContents)missing.getContents()).getArgs()[0];
        h.assertTrue(missing.getStyle().getColor().getValue() == TemporalText.BODY
                && cost.getStyle().getColor().getValue() == TemporalText.CAPACITY
                && TemporalText.state(side.tissue).getStyle().getColor().getValue() == TemporalText.PENDING,
                "The HUD uses the same target color for unpaid costs and yellow only for the pending state");
        h.succeed();
    }

    @GameTest(templateNamespace="kaleidoscope_witchcraft", template="empty", timeoutTicks=60)
    public static void tooltipLayersKeepLoreAndRespectHiddenInformation(GameTestHelper h) {
        Tissue tissue = new Tissue(); tissue.rootStage = 1;
        ItemStack stack = TissueItem.stack(tissue);
        stack.set(DataComponents.LORE, new ItemLore(List.of(Component.literal("retained lore"))));
        List<Component> lines = tooltip(stack); lines.add(Component.literal("other mod"));
        TemporalTooltips.expand(stack, lines, true, true, false);
        h.assertTrue(find(lines, "core") != null && find(lines, "controls.clock") == null,
                "Ctrl details replace the hint and take precedence over Shift controls");
        h.assertTrue(lines.stream().anyMatch(c -> c.getString().equals("retained lore"))
                && lines.stream().anyMatch(c -> c.getString().equals("other mod")), "Unowned lines must survive expansion");
        lines = tooltip(stack); TemporalTooltips.expand(stack, lines, true, false, false);
        h.assertTrue(find(lines, "controls.clock") != null && find(lines, "controls.bottle") == null,
                "Controls are discoverable while absent integrations stay hidden");
        List<Component> hidden = tooltip(stack);
        stack.set(DataComponents.HIDE_ADDITIONAL_TOOLTIP, Unit.INSTANCE);
        int count = hidden.size(); TemporalTooltips.expand(stack, hidden, true, true, true);
        h.assertTrue(hidden.size() == count && find(tooltip(stack), "total") == null, "Hidden additional tooltips must not be reintroduced by the detail layer");
        h.succeed();
    }
}
