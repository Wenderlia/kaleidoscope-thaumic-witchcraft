package dev.senluo.witchcraft.ritual;

import dev.senluo.witchcraft.KaleidoscopeWitchcraft;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.SwordItem;
import net.minecraft.world.item.Tier;
import net.minecraft.world.item.crafting.Ingredient;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.SimpleTier;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class RitualContent {
    private static final DeferredRegister<SoundEvent> SOUNDS = DeferredRegister.create(
            Registries.SOUND_EVENT, KaleidoscopeWitchcraft.MOD_ID);
    public static final DeferredHolder<SoundEvent, SoundEvent> GOLD_OFFERING = SOUNDS.register(
            "ritual.gold_offering", () -> SoundEvent.createVariableRangeEvent(
                    ResourceLocation.fromNamespaceAndPath(KaleidoscopeWitchcraft.MOD_ID, "ritual.gold_offering")));
    public static final DeferredHolder<SoundEvent, SoundEvent> CHALK_SOUND = SOUNDS.register(
            "ritual.chalk", () -> SoundEvent.createVariableRangeEvent(
                    ResourceLocation.fromNamespaceAndPath(KaleidoscopeWitchcraft.MOD_ID, "ritual.chalk")));
    public static final DeferredHolder<SoundEvent, SoundEvent> HEART_CLICK = SOUNDS.register(
            "ritual.heart_click", () -> SoundEvent.createVariableRangeEvent(
                    ResourceLocation.fromNamespaceAndPath(KaleidoscopeWitchcraft.MOD_ID, "ritual.heart_click")));
    private static final DeferredRegister<net.neoforged.neoforge.attachment.AttachmentType<?>> ATTACHMENTS =
            DeferredRegister.create(net.neoforged.neoforge.registries.NeoForgeRegistries.Keys.ATTACHMENT_TYPES, KaleidoscopeWitchcraft.MOD_ID);
    public static final DeferredHolder<net.neoforged.neoforge.attachment.AttachmentType<?>, net.neoforged.neoforge.attachment.AttachmentType<ChalkMarks>> CHALK_MARKS =
            ATTACHMENTS.register("chalk_marks", () -> net.neoforged.neoforge.attachment.AttachmentType
                    .serializable(ChalkMarks::new).sync(ChalkMarks.STREAM_CODEC).build());
    public static final DeferredHolder<net.neoforged.neoforge.attachment.AttachmentType<?>, net.neoforged.neoforge.attachment.AttachmentType<RitualAnchorState>> RITUAL_ANCHOR =
            ATTACHMENTS.register("ritual_anchor", () -> net.neoforged.neoforge.attachment.AttachmentType
                    .serializable(RitualAnchorState::new).build());
    /** Distinguished units of real Herba, one per session. */
    public static final DeferredHolder<net.neoforged.neoforge.attachment.AttachmentType<?>, net.neoforged.neoforge.attachment.AttachmentType<Integer>> RITUAL_HERBA =
            ATTACHMENTS.register("ritual_herba", () -> net.neoforged.neoforge.attachment.AttachmentType
                    .builder(() -> 0).serialize(com.mojang.serialization.Codec.intRange(0, Integer.MAX_VALUE)).build());
    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(KaleidoscopeWitchcraft.MOD_ID);
    private static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(KaleidoscopeWitchcraft.MOD_ID);
    private static final DeferredRegister<net.minecraft.world.level.block.entity.BlockEntityType<?>> ENTITIES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, KaleidoscopeWitchcraft.MOD_ID);
    public static final net.neoforged.neoforge.registries.DeferredBlock<GoldenHeartBlock> GOLDEN_HEART =
            BLOCKS.register("golden_heart", () -> new GoldenHeartBlock(net.minecraft.world.level.block.state.BlockBehaviour.Properties
                    .of().noCollission().noOcclusion().instabreak().noLootTable()
                    .sound(net.minecraft.world.level.block.SoundType.CALCITE)
                    .pushReaction(net.minecraft.world.level.material.PushReaction.DESTROY)));
    public static final DeferredHolder<net.minecraft.world.level.block.entity.BlockEntityType<?>,
            net.minecraft.world.level.block.entity.BlockEntityType<GoldenHeartEntity>> GOLDEN_HEART_ENTITY =
            ENTITIES.register("golden_heart", () -> net.minecraft.world.level.block.entity.BlockEntityType.Builder
                    .of(GoldenHeartEntity::new, GOLDEN_HEART.get()).build(null));
    public static final net.neoforged.neoforge.registries.DeferredBlock<ChalkSurfaceBlock> CHALK_SURFACE =
            BLOCKS.register("chalk_surface", () -> new ChalkSurfaceBlock(net.minecraft.world.level.block.state.BlockBehaviour.Properties
                    .of().noCollission().noOcclusion().instabreak().noLootTable()
                    .sound(net.minecraft.world.level.block.SoundType.CALCITE)
                    .pushReaction(net.minecraft.world.level.material.PushReaction.DESTROY)));
    private static final DeferredRegister.DataComponents COMPONENTS = DeferredRegister.createDataComponents(
            Registries.DATA_COMPONENT_TYPE, KaleidoscopeWitchcraft.MOD_ID);
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<TaglockSample>> TAGLOCK_SAMPLE =
            COMPONENTS.registerComponentType("taglock_sample", builder -> builder
                    .persistent(TaglockSample.CODEC).networkSynchronized(TaglockSample.STREAM_CODEC));
    private static final Tier DAGGER_TIER = new SimpleTier(
            BlockTags.INCORRECT_FOR_STONE_TOOL, 160, 4.0F, 1.0F, 10,
            () -> Ingredient.of(Items.EMERALD));

    // Includes the player's base damage/speed: 4 damage, 2 attacks per second.
    public static final DeferredItem<RitualDaggerItem> RITUAL_DAGGER = ITEMS.register("ritual_dagger", () ->
            new RitualDaggerItem(DAGGER_TIER, new Item.Properties()
                    .attributes(SwordItem.createAttributes(DAGGER_TIER, 2, -2.0F))));
    public static final DeferredItem<Item> BONE_SPIKE = ITEMS.register("bone_spike", () ->
            new Item(new Item.Properties()));
    public static final DeferredItem<TaglockItem> TAGLOCK = ITEMS.register("taglock", () ->
            new TaglockItem(new Item.Properties().stacksTo(16)));

    public static final DeferredItem<RitualChalk.ChalkItem> CHALK = ITEMS.register("ritual_chalk", () ->
            new RitualChalk.ChalkItem(new Item.Properties()));
    public static final DeferredItem<GoldenChalkItem> GOLDEN_CHALK = ITEMS.register("golden_chalk", () ->
            new GoldenChalkItem(new Item.Properties()));

    public static void register(IEventBus bus) {
        GeasRecipePreview.register(bus);
        LandPeaceRitual.register();
        SOUNDS.register(bus);
        ATTACHMENTS.register(bus);
        bus.addListener(RitualPackets::register);
        COMPONENTS.register(bus);
        ITEMS.register(bus);
        BLOCKS.register(bus);
        ENTITIES.register(bus);
    }

    private RitualContent() {}
}
