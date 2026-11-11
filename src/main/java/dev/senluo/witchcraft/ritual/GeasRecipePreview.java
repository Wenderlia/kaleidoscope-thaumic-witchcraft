package dev.senluo.witchcraft.ritual;

import dev.senluo.witchcraft.KaleidoscopeWitchcraft;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

/** Presentation tokens for native item-based recipe viewers, never the ritual's physical output. */
public final class GeasRecipePreview {
    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(KaleidoscopeWitchcraft.MOD_ID);
    private static final Map<ResourceLocation, DeferredItem<Item>> OUTCOMES = new LinkedHashMap<>();
    private static Supplier<Player> viewer = () -> null;
    static {
        for (var rite : GeasRites.ALL) OUTCOMES.put(rite.id(), ITEMS.register("geas_preview_" + rite.id().getPath(),
                () -> new Item(new Item.Properties()) {
                    @Override public Component getName(ItemStack stack) {
                        return Component.translatable("jei.kaleidoscope_witchcraft." + rite.id().getPath());
                    }
                }));
    }
    public static void register(IEventBus bus) { ITEMS.register(bus); }
    /** Client supplies a lookup, not a retained player or world. Dedicated-server previews stay anonymous. */
    public static void viewer(Supplier<Player> lookup) { viewer = java.util.Objects.requireNonNull(lookup); }
    public static ItemStack sample() {
        Player player = viewer.get();
        ItemStack stack = new ItemStack(RitualContent.TAGLOCK.get());
        stack.set(RitualContent.TAGLOCK_SAMPLE.get(), player != null ? TaglockSample.from(player)
                : new TaglockSample(TaglockSample.CURRENT_VERSION, new UUID(0, 0),
                        ResourceLocation.withDefaultNamespace("player"), "entity.minecraft.player", false));
        return stack;
    }
    public static ItemStack outcome(GeasRites.Rite rite) { return new ItemStack(OUTCOMES.get(rite.id()).get()); }
    private GeasRecipePreview() {}
}
