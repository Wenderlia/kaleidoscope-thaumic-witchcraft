package dev.senluo.witchcraft.accessory;

import dev.senluo.witchcraft.KaleidoscopeWitchcraft;
import net.minecraft.core.registries.Registries;
import net.minecraft.core.particles.ParticleType;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.world.item.Item;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

public final class AccessoryContent {
    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(KaleidoscopeWitchcraft.MOD_ID);
    private static final DeferredRegister<AttachmentType<?>> ATTACHMENTS = DeferredRegister.create(
            NeoForgeRegistries.Keys.ATTACHMENT_TYPES, KaleidoscopeWitchcraft.MOD_ID);
    private static final DeferredRegister<ParticleType<?>> PARTICLES = DeferredRegister.create(
            Registries.PARTICLE_TYPE, KaleidoscopeWitchcraft.MOD_ID);

    public static final DeferredItem<Item> TURTLE_RUNE = ITEMS.register("turtle_rune",
            () -> new Item(new Item.Properties().stacksTo(1)));
    // A live constraint, never saved into an entity or copied through death/dimension changes.
    public static final DeferredHolder<AttachmentType<?>, AttachmentType<FlightPath>> FLIGHT_PATH =
            ATTACHMENTS.register("turtle_flight_path", () -> AttachmentType.builder(() -> FlightPath.FREE)
                    .sync(FlightPath.STREAM_CODEC).build());
    public static final DeferredHolder<ParticleType<?>, SimpleParticleType> CONVERGING_SONIC =
            PARTICLES.register("converging_sonic", () -> new SimpleParticleType(false));

    public static void register(IEventBus bus) {
        ITEMS.register(bus);
        ATTACHMENTS.register(bus);
        PARTICLES.register(bus);
    }

    private AccessoryContent() {}
}
