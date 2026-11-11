package dev.senluo.witchcraft.brew;

import dev.senluo.witchcraft.KaleidoscopeWitchcraft;
import java.util.List;
import java.util.Arrays;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.*;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.Fluid;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.fluids.BaseFlowingFluid;
import net.neoforged.neoforge.fluids.FluidType;
import net.neoforged.neoforge.fluids.capability.wrappers.FluidBucketWrapper;
import net.neoforged.neoforge.registries.*;

/** Content registration only. Fermentation and storage remain owned by the host. */
public final class AmberFerments {
    private static final String NS=KaleidoscopeWitchcraft.MOD_ID;
    private static final DeferredRegister.Items ITEMS=DeferredRegister.createItems(NS);
    private static final DeferredRegister<FluidType> TYPES=DeferredRegister.create(NeoForgeRegistries.Keys.FLUID_TYPES, NS);
    private static final DeferredRegister<Fluid> FLUIDS=DeferredRegister.create(Registries.FLUID, NS);
    private static final DeferredRegister<net.neoforged.neoforge.common.crafting.IngredientType<?>> INGREDIENTS=
            DeferredRegister.create(NeoForgeRegistries.Keys.INGREDIENT_TYPES, NS);
    public static final DeferredHolder<net.neoforged.neoforge.common.crafting.IngredientType<?>, net.neoforged.neoforge.common.crafting.IngredientType<FruitGradeIngredient>> FRUIT_GRADE=
            INGREDIENTS.register("fruit_grade", () -> new net.neoforged.neoforge.common.crafting.IngredientType<>(FruitGradeIngredient.CODEC));
    public static final List<Entry> ENTRIES=Arrays.stream(AmberVintage.values()).map(Entry::new).toList();

    public static final class Entry {
        public final AmberVintage vintage;
        public final DeferredHolder<FluidType, FluidType> type;
        public final DeferredHolder<Fluid, BaseFlowingFluid.Source> source;
        public final DeferredHolder<Fluid, BaseFlowingFluid.Flowing> flowing;
        public final DeferredItem<Item> bucket, wine;
        private Entry(AmberVintage vintage) {
            this.vintage=vintage;
            type=TYPES.register(vintage.pulp, () -> new FluidType(FluidType.Properties.create()
                    .descriptionId("fluid_type."+NS+"."+vintage.pulp)));
            source=FLUIDS.register(vintage.pulp, () -> new BaseFlowingFluid.Source(properties()));
            flowing=FLUIDS.register("flowing_"+vintage.pulp, () -> new BaseFlowingFluid.Flowing(properties()));
            bucket=ITEMS.register(vintage.pulp+"_bucket", () -> new PulpBucket(source.get()));
            wine=ITEMS.register(vintage.wine, () -> ModList.get().isLoaded("kaleidoscope_tavern")
                    ? dev.senluo.witchcraft.compat.tavern.TavernWine.createAmberItem(vintage)
                    : new Item(new Item.Properties().stacksTo(16)) {
                        @Override public void appendHoverText(ItemStack stack, TooltipContext context,
                                java.util.List<net.minecraft.network.chat.Component> lines, net.minecraft.world.item.TooltipFlag flag) {
                            lines.add(vintage.lore());
                        }
                    });
        }
        private BaseFlowingFluid.Properties properties() {
            return new BaseFlowingFluid.Properties(type, source, flowing).bucket(bucket);
        }
    }

    private static final class PulpBucket extends BucketItem {
        private PulpBucket(Fluid fluid) { super(fluid, new Item.Properties().stacksTo(16).craftRemainder(Items.BUCKET)); }
        // A container ingredient, not a world-fluid placement or an implicit antidote drink.
        @Override public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
            return InteractionResultHolder.pass(player.getItemInHand(hand));
        }
        @Override public boolean emptyContents(Player player, Level level, net.minecraft.core.BlockPos pos,
                                               net.minecraft.world.phys.BlockHitResult hit) { return false; }
    }
    public static void register(IEventBus bus) {
        ITEMS.register(bus); TYPES.register(bus); FLUIDS.register(bus); INGREDIENTS.register(bus);
        bus.addListener((RegisterCapabilitiesEvent event) -> {
            for (Entry entry : ENTRIES)
                event.registerItem(Capabilities.FluidHandler.ITEM, (stack, context) -> new FluidBucketWrapper(stack), entry.bucket.get());
        });
    }
    private AmberFerments() {}
}
