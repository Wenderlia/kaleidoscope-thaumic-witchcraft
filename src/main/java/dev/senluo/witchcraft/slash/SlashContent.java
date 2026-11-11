package dev.senluo.witchcraft.slash;

import dev.senluo.witchcraft.KaleidoscopeWitchcraft;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.item.Item;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.registries.*;
import java.util.List;
import java.util.stream.IntStream;

public final class SlashContent {
    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(KaleidoscopeWitchcraft.MOD_ID);
    private static final DeferredRegister<EntityType<?>> ENTITIES = DeferredRegister.create(Registries.ENTITY_TYPE, KaleidoscopeWitchcraft.MOD_ID);
    private static final DeferredRegister.DataComponents COMPONENTS = DeferredRegister.createDataComponents(Registries.DATA_COMPONENT_TYPE, KaleidoscopeWitchcraft.MOD_ID);
    private static final DeferredRegister<SoundEvent> SOUNDS = DeferredRegister.create(Registries.SOUND_EVENT, KaleidoscopeWitchcraft.MOD_ID);
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<FocusMemory>> MEMORY = COMPONENTS.registerComponentType(
            "slash_focus", b -> b.persistent(FocusMemory.CODEC).networkSynchronized(ByteBufCodecs.fromCodec(FocusMemory.CODEC)));
    public static final DeferredItem<Item> FOCUS = ITEMS.register("slash_focus", () ->
            ModList.get().isLoaded("thaumcraft") ? dev.senluo.witchcraft.compat.thaumcraft.SlashFocus.createItem()
                    : new SlashFocusItem());
    public static final DeferredHolder<EntityType<?>, EntityType<SlashEntity>> SLASH = ENTITIES.register("flying_slash", () ->
            EntityType.Builder.<SlashEntity>of(SlashEntity::new, MobCategory.MISC).sized(.15F, .15F)
                    .noSummon().fireImmune().clientTrackingRange(32).updateInterval(1)
                    .build(id("flying_slash").toString()));
    public static final DeferredHolder<SoundEvent, SoundEvent> RELEASE = SOUNDS.register("slash.release",
            () -> SoundEvent.createVariableRangeEvent(id("slash.release")));
    public static final DeferredHolder<SoundEvent, SoundEvent> CONTACT = SOUNDS.register("slash.contact",
            () -> SoundEvent.createVariableRangeEvent(id("slash.contact")));
    public static final DeferredHolder<SoundEvent, SoundEvent> BLOCKED_STONE = SOUNDS.register("slash.blocked_stone",
            () -> SoundEvent.createVariableRangeEvent(id("slash.blocked_stone")));
    static final List<DeferredHolder<SoundEvent, SoundEvent>> BLOCKED_HARDNESS = IntStream
            .range(0, SlashImpactCurve.PROFILE_COUNT).mapToObj(profile -> {
                if (profile == 0) return BLOCKED_STONE;
                String name = "slash." + SlashImpactCurve.soundName(profile);
                return SOUNDS.register(name, () -> SoundEvent.createVariableRangeEvent(id(name)));
            }).toList();
    public static final DeferredHolder<SoundEvent, SoundEvent> BLOCKED_BEDROCK = SOUNDS.register("slash.blocked_bedrock",
            () -> SoundEvent.createVariableRangeEvent(id("slash.blocked_bedrock")));

    private SlashContent() {}
    public static ResourceLocation id(String path) { return ResourceLocation.fromNamespaceAndPath(KaleidoscopeWitchcraft.MOD_ID, path); }
    public static void register(IEventBus bus) {
        ITEMS.register(bus); COMPONENTS.register(bus); ENTITIES.register(bus); SOUNDS.register(bus);
        SlashRhythm.register(bus);
        bus.addListener(SlashRequests::register);
        bus.addListener(dev.senluo.witchcraft.slash.drawing.SlashDrawing::register);
    }
}
