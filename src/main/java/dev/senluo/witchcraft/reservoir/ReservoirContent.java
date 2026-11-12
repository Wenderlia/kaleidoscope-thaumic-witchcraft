package dev.senluo.witchcraft.reservoir;

import dev.senluo.witchcraft.KaleidoscopeWitchcraft;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.*;

/** Registration and optional host bridge wiring; runtime work lives in ReservoirRuntime. */
public final class ReservoirContent {
    private static final String MOD=KaleidoscopeWitchcraft.MOD_ID;
    private static final DeferredRegister.Blocks BLOCKS=DeferredRegister.createBlocks(MOD);
    private static final DeferredRegister.Items ITEMS=DeferredRegister.createItems(MOD);
    private static final DeferredRegister<BlockEntityType<?>> BES=DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE,MOD);
    private static final DeferredRegister<MenuType<?>> MENUS=DeferredRegister.create(Registries.MENU,MOD);
    public static ReservoirBridge bridge=ReservoirBridge.NONE;
    public static final DeferredBlock<ReservoirBlock> BLOCK=BLOCKS.register("alchemy_backpack",()->new ReservoirBlock(BlockBehaviour.Properties.of().strength(1.5F).noOcclusion().noLootTable()));
    public static final DeferredItem<ReservoirItem> BACKPACK=ITEMS.register("alchemy_backpack",()->new ReservoirItem(BLOCK.get(),new Item.Properties().stacksTo(1),ReservoirProfile.STANDARD));
    public static final DeferredBlock<ReservoirBlock> SILVER_BLOCK=BLOCKS.register("silverwood_alchemy_backpack",()->new ReservoirBlock(BlockBehaviour.Properties.of().strength(1.5F).noOcclusion().noLootTable()));
    public static final DeferredItem<ReservoirItem> SILVER_BACKPACK=ITEMS.register("silverwood_alchemy_backpack",()->new ReservoirItem(SILVER_BLOCK.get(),new Item.Properties().stacksTo(1),ReservoirProfile.SILVERWOOD));
    public static final DeferredItem<ReservoirTransducerItem> CONVERTER=ITEMS.register("essentia_transducer",()->new ReservoirTransducerItem(new Item.Properties().stacksTo(64),ReservoirTransducerItem.Grade.STANDARD,
            (player,bag,slot,device)->bridge.charge(player,bag,slot,device)));
    public static final DeferredItem<ReservoirTransducerItem> VOID_CONVERTER=ITEMS.register("void_essentia_transducer",()->new ReservoirTransducerItem(new Item.Properties().stacksTo(64),ReservoirTransducerItem.Grade.VOID,
            (player,bag,slot,device)->bridge.charge(player,bag,slot,device)));
    public static final DeferredItem<FrostModuleItem> FROST_MODULE=ITEMS.register("frost_module",()->new FrostModuleItem(new Item.Properties().stacksTo(1)));
    public static final java.util.function.Supplier<BlockEntityType<ReservoirBlockEntity>> ENTITY=BES.register("alchemy_backpack",()->BlockEntityType.Builder.of(ReservoirBlockEntity::new,BLOCK.get(),SILVER_BLOCK.get()).build(null));
    public static final java.util.function.Supplier<MenuType<ReservoirMenu>> MENU=MENUS.register("alchemy_backpack",()->new MenuType<>((id,inventory)->new ReservoirMenu(id,inventory,ReservoirProfile.STANDARD),net.minecraft.world.flag.FeatureFlags.DEFAULT_FLAGS));
    public static final java.util.function.Supplier<MenuType<ReservoirMenu>> SILVER_MENU=MENUS.register("silverwood_alchemy_backpack",()->new MenuType<>((id,inventory)->new ReservoirMenu(id,inventory,ReservoirProfile.SILVERWOOD),net.minecraft.world.flag.FeatureFlags.DEFAULT_FLAGS));
    public static MenuType<ReservoirMenu> menu(ReservoirProfile profile){return (profile==ReservoirProfile.SILVERWOOD?SILVER_MENU:MENU).get();}
    public static boolean isBackpack(ItemStack stack){return stack.getItem() instanceof ReservoirItem;}
    private ReservoirContent(){}
    public static ResourceLocation id(String path){return ResourceLocation.fromNamespaceAndPath(MOD,path);}
    public static void register(IEventBus bus){
        BLOCKS.register(bus);ITEMS.register(bus);BES.register(bus);MENUS.register(bus);
        bus.addListener((net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent e)->
                e.registerBlockEntity(net.neoforged.neoforge.capabilities.Capabilities.ItemHandler.BLOCK,ENTITY.get(),(entity,side)->entity.ports()));
        bus.addListener((net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent e)->{
            if(e.getTab()==dev.senluo.witchcraft.time.TemporalContent.CREATIVE_TAB.get()){e.accept(BACKPACK.get());e.accept(SILVER_BACKPACK.get());e.accept(CONVERTER.get());e.accept(VOID_CONVERTER.get());e.accept(FROST_MODULE.get());}
        });
        ReservoirRuntime.register(bus);
    }
}
