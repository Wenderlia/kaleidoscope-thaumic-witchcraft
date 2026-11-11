package dev.senluo.witchcraft.brew;

import dev.senluo.witchcraft.KaleidoscopeWitchcraft;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.RangedAttribute;
import net.neoforged.neoforge.event.entity.EntityAttributeModificationEvent;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.registries.*;

public final class BrewContent {
    private static final DeferredRegister<MobEffect> EFFECTS = DeferredRegister.create(Registries.MOB_EFFECT, KaleidoscopeWitchcraft.MOD_ID);
    private static final DeferredRegister<Attribute> ATTRIBUTES = DeferredRegister.create(Registries.ATTRIBUTE, KaleidoscopeWitchcraft.MOD_ID);
    public static final DeferredHolder<Attribute,Attribute> SLOW_RESISTANCE=ATTRIBUTES.register("temporal_slow_resistance",
            ()->new RangedAttribute("attribute.kaleidoscope_witchcraft.temporal_slow_resistance",0,0,1).setSyncable(true));
    private static final DeferredRegister<AttachmentType<?>> ATTACHMENTS = DeferredRegister.create(NeoForgeRegistries.ATTACHMENT_TYPES, KaleidoscopeWitchcraft.MOD_ID);
    public static final DeferredHolder<MobEffect, MobEffect> YEARS_ABUNDANCE = EFFECTS.register("years_abundance", DisplayEffect::new);
    public static final DeferredHolder<AttachmentType<?>, AttachmentType<BodyTime>> BODY_TIME = ATTACHMENTS.register("body_time",
            () -> AttachmentType.serializable(BodyTime::new).build());
    public static final DeferredHolder<AttachmentType<?>, AttachmentType<NutritionLedger>> NUTRITION = ATTACHMENTS.register("nutrition_ledger",
            () -> AttachmentType.serializable(NutritionLedger::new).build());
    public static void register(IEventBus bus) {
        EFFECTS.register(bus);ATTACHMENTS.register(bus);ATTRIBUTES.register(bus);WineParticles.register(bus);
        bus.addListener((EntityAttributeModificationEvent event)->event.add(EntityType.PLAYER,SLOW_RESISTANCE));
    }
    private static final class DisplayEffect extends MobEffect {
        private DisplayEffect() { super(MobEffectCategory.BENEFICIAL, 0xDFB967); }
    }
    private BrewContent() {}
}
