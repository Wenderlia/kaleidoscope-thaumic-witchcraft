package dev.senluo.witchcraft.time;

import dev.senluo.witchcraft.KaleidoscopeWitchcraft;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class TemporalContent {
    private static final String ID = KaleidoscopeWitchcraft.MOD_ID;
    private static final DeferredRegister<net.neoforged.neoforge.attachment.AttachmentType<?>> ATTACHMENTS =
            DeferredRegister.create(net.neoforged.neoforge.registries.NeoForgeRegistries.Keys.ATTACHMENT_TYPES, ID);
    public static final java.util.function.Supplier<net.neoforged.neoforge.attachment.AttachmentType<Boolean>> SLOW_ITEM =
            ATTACHMENTS.register("slow_item", () -> net.neoforged.neoforge.attachment.AttachmentType.builder(() -> false)
                    .sync(net.minecraft.network.codec.ByteBufCodecs.BOOL).build());
    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(ID);
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(ID);
    public static final DeferredRegister<BlockEntityType<?>> ENTITIES = DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, ID);
    public static final DeferredRegister<Feature<?>> FEATURES = DeferredRegister.create(Registries.FEATURE, ID);
    public static final DeferredRegister<CreativeModeTab> TABS = DeferredRegister.create(Registries.CREATIVE_MODE_TAB, ID);
    public static final DeferredBlock<LivingBlock> RHIZOME = BLOCKS.register("years_rhizome", () -> new LivingBlock(false,
            BlockBehaviour.Properties.of().strength(0.8F).sound(SoundType.AMETHYST).noCollission().noOcclusion()));
    public static final DeferredBlock<LivingBlock> AMBER = BLOCKS.register("amber_marrow", () -> new LivingBlock(true,
            BlockBehaviour.Properties.of().strength(3.0F, 3.0F).requiresCorrectToolForDrops().sound(SoundType.AMETHYST).lightLevel(s -> 3 + s.getValue(LivingBlock.AGE))));
    public static final DeferredBlock<RootBlock> ROOT = BLOCKS.register("living_root", () -> new RootBlock(
            BlockBehaviour.Properties.of().strength(0.6F).sound(SoundType.ROOTED_DIRT).noOcclusion()));
    public static final DeferredBlock<RootedFarmlandBlock> ROOTED_FARMLAND = BLOCKS.register("rooted_farmland", () -> new RootedFarmlandBlock(
            BlockBehaviour.Properties.ofFullCopy(Blocks.FARMLAND).noLootTable()));
    public static final DeferredBlock<LivingBlock> CORE = BLOCKS.register("years_core", () -> new LivingBlock(false, true,
            BlockBehaviour.Properties.of().strength(2.0F).sound(SoundType.WOOD).lightLevel(s -> 5).noLootTable()));
    public static final DeferredBlock<TreePartBlock> TIME_LOG = BLOCKS.register("years_log", () -> new TreePartBlock(
            BlockBehaviour.Properties.ofFullCopy(Blocks.OAK_LOG)));
    public static final DeferredBlock<TreePartBlock> STRIPPED_TIME_LOG = BLOCKS.register("stripped_years_log", () -> new TreePartBlock(
            BlockBehaviour.Properties.ofFullCopy(Blocks.STRIPPED_OAK_LOG)));
    public static final DeferredBlock<TreeLeavesBlock> TIME_LEAVES = BLOCKS.register("years_leaves", () -> new TreeLeavesBlock(
            BlockBehaviour.Properties.of().strength(0.2F).sound(SoundType.GRASS).lightLevel(s -> 5).randomTicks().noOcclusion().isViewBlocking((s,l,p) -> false).isSuffocating((s,l,p) -> false)));
    public static final DeferredBlock<net.minecraft.world.level.block.Block> TIME_PLANKS = BLOCKS.register("years_planks", () -> new TreeBuildingBlock(
            BlockBehaviour.Properties.ofFullCopy(Blocks.OAK_PLANKS)));
    public static final DeferredBlock<FruitBlock> FRUIT = BLOCKS.register("years_fruit", () -> new FruitBlock(
            BlockBehaviour.Properties.of().strength(0.0F).sound(SoundType.CROP).noOcclusion().noLootTable()));
    public static final DeferredItem<TissueItem> TISSUE = ITEMS.register("temporal_tissue", () -> new TissueItem(new Item.Properties().stacksTo(1)));
    public static final DeferredItem<TissueItem> CORE_ITEM = ITEMS.register("years_core", () -> new TissueItem(new Item.Properties().stacksTo(1)));
    public static final DeferredItem<BlockItem> LOG_ITEM = ITEMS.register("years_log", () -> new BlockItem(TIME_LOG.get(),new Item.Properties()));
    public static final DeferredItem<BlockItem> STRIPPED_LOG_ITEM = ITEMS.register("stripped_years_log", () -> new BlockItem(STRIPPED_TIME_LOG.get(),new Item.Properties()));
    public static final DeferredItem<BlockItem> LEAVES_ITEM = ITEMS.register("years_leaves", () -> new BlockItem(TIME_LEAVES.get(),new Item.Properties()));
    public static final DeferredItem<BlockItem> PLANKS_ITEM = ITEMS.register("years_planks", () -> new BlockItem(TIME_PLANKS.get(),new Item.Properties()));
    public static final DeferredItem<FruitItem> FRUIT_ITEM = ITEMS.register("years_fruit", () ->
            net.neoforged.fml.ModList.get().isLoaded("thaumcraft")
                    ? dev.senluo.witchcraft.compat.thaumcraft.ThaumcraftFruitItem.create(new Item.Properties())
                    : new FruitItem(new Item.Properties()));
    public static final DeferredItem<Item> LIUJIN_VODKA = ITEMS.register("liujin_vodka", () ->
            net.neoforged.fml.ModList.get().isLoaded("kaleidoscope_tavern")
                    ? dev.senluo.witchcraft.compat.tavern.TavernWine.createItem()
                    : new dev.senluo.witchcraft.brew.LiujinVodkaItem(new Item.Properties()));
    public static final DeferredItem<RootItem> ROOT_PIECE = ITEMS.register("root_segment", () -> new RootItem(new Item.Properties()));
    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> CREATIVE_TAB = TABS.register("witchcraft", () -> CreativeModeTab.builder()
            .title(Component.translatable("itemGroup.kaleidoscope_witchcraft"))
            .icon(() -> TISSUE.get().getDefaultInstance())
            .withTabsBefore(CreativeModeTabs.NATURAL_BLOCKS)
            .displayItems((parameters, output) -> {
                output.accept(TISSUE.get());
                output.accept(ROOT_PIECE.get());
                output.accept(CORE_ITEM.get());
                output.accept(LOG_ITEM.get());
                output.accept(STRIPPED_LOG_ITEM.get());
                for (var family : FruitWoods.FAMILIES) output.accept(family.nodeItem().get());
                output.accept(LEAVES_ITEM.get());
                output.accept(PLANKS_ITEM.get());
                YearsWoodContent.display(output);
                for (int grade = 1; grade <= TreeBalance.MAX_FRUIT_GRADE; grade++) output.accept(FruitItem.stack(grade));
                output.accept(dev.senluo.witchcraft.brew.LiujinVodkaItem.sample());
                output.accept(dev.senluo.witchcraft.brew.LiujinVodkaItem.gradeThirteenSample());
                for (var entry : dev.senluo.witchcraft.brew.AmberFerments.ENTRIES) {
                    output.accept(entry.bucket.get());
                    var sample=net.neoforged.fml.ModList.get().isLoaded("kaleidoscope_tavern")
                            ? dev.senluo.witchcraft.compat.tavern.AmberWineItem.sample(entry.wine.get())
                            : entry.wine.get().getDefaultInstance();
                    output.accept(sample);
                }
                output.accept(dev.senluo.witchcraft.ritual.RitualContent.RITUAL_DAGGER.get());
                output.accept(dev.senluo.witchcraft.ritual.RitualContent.BONE_SPIKE.get());
                output.accept(dev.senluo.witchcraft.ritual.RitualContent.TAGLOCK.get());
                output.accept(dev.senluo.witchcraft.ritual.RitualContent.CHALK.get());
                output.accept(dev.senluo.witchcraft.ritual.RitualContent.GOLDEN_CHALK.get());
                output.accept(dev.senluo.witchcraft.accessory.AccessoryContent.TURTLE_RUNE.get());
                if (net.neoforged.fml.ModList.get().isLoaded("thaumcraft"))
                    output.accept(dev.senluo.witchcraft.slash.SlashContent.FOCUS.get());
            }).build());
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<LivingEntity>> LIVING_ENTITY = ENTITIES.register("living_tissue",
            () -> BlockEntityType.Builder.of(LivingEntity::new, RHIZOME.get(), AMBER.get(), CORE.get()).build(null));
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<FruitEntity>> FRUIT_ENTITY = ENTITIES.register("years_fruit",
            () -> BlockEntityType.Builder.of(FruitEntity::new, FRUIT.get()).build(null));
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<RootEntity>> ROOT_ENTITY = ENTITIES.register("living_root",
            () -> BlockEntityType.Builder.of(RootEntity::new, ROOT.get(), ROOTED_FARMLAND.get()).build(null));
    public static final DeferredHolder<Feature<?>, AncientFeature> ANCIENT = FEATURES.register("ancient_tissue", AncientFeature::new);

    private TemporalContent() {}
    public static void register(IEventBus bus) {
        FruitWoods.bootstrap();
        YearsWoodContent.register(bus);
        ATTACHMENTS.register(bus); BLOCKS.register(bus); ITEMS.register(bus); ENTITIES.register(bus); FEATURES.register(bus); TABS.register(bus);
    }
}
