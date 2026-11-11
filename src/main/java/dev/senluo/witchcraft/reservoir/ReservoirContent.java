package dev.senluo.witchcraft.reservoir;

import dev.senluo.witchcraft.KaleidoscopeWitchcraft;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.*;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.registries.*;
import java.util.function.Function;

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
    public static Function<Player,ItemStack> backSlot=p->ItemStack.EMPTY;
    private ReservoirContent(){}
    public static ResourceLocation id(String path){return ResourceLocation.fromNamespaceAndPath(MOD,path);}
    public static void register(IEventBus bus){
        BLOCKS.register(bus);ITEMS.register(bus);BES.register(bus);MENUS.register(bus);
        bus.addListener(ReservoirContent::packets);
        bus.addListener((net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent e)->
                e.registerBlockEntity(net.neoforged.neoforge.capabilities.Capabilities.ItemHandler.BLOCK,ENTITY.get(),(entity,side)->entity.ports()));
        bus.addListener((net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent e)->{
            if(e.getTab()==dev.senluo.witchcraft.time.TemporalContent.CREATIVE_TAB.get()){e.accept(BACKPACK.get());e.accept(SILVER_BACKPACK.get());e.accept(CONVERTER.get());e.accept(VOID_CONVERTER.get());e.accept(FROST_MODULE.get());}
        });
        NeoForge.EVENT_BUS.addListener(net.neoforged.bus.api.EventPriority.HIGH,ReservoirContent::beforeTick);
        NeoForge.EVENT_BUS.addListener(ReservoirContent::tick);
        NeoForge.EVENT_BUS.addListener(ReservoirContent::rightClick);
        NeoForge.EVENT_BUS.addListener(ReservoirContent::rightClickBlock);
        NeoForge.EVENT_BUS.addListener(ReservoirContent::rightClickEntity);
        NeoForge.EVENT_BUS.addListener(ReservoirContent::rightClickEntitySpecific);
    }
    public static ItemStack worn(Player player){
        ItemStack back=backSlot.apply(player);
        if(isBackpack(back))return back;
        ItemStack chest=player.getItemBySlot(EquipmentSlot.CHEST);
        return isBackpack(chest)?chest:ItemStack.EMPTY;
    }
    public static ItemStack accessible(Player player){
        ItemStack worn=worn(player);if(!worn.isEmpty())return worn;
        if(isBackpack(player.getOffhandItem()))return player.getOffhandItem();
        if(isBackpack(player.getMainHandItem()))return player.getMainHandItem();
        return ItemStack.EMPTY;
    }
    static boolean acceptsInput(ItemStack held) { return ReservoirPlugins.accepts(held) || bridge.container(held); }
    public static boolean interact(ServerPlayer player,ItemStack bag,InteractionHand hand,Runnable changed){
        ItemStack held=player.getItemInHand(hand);
        if(!player.isAlive() || player.isSpectator() || dev.senluo.witchcraft.ritual.OwnershipRitual.frozen(player) || !ReservoirData.supported(bag))return false;
        if(ReservoirPlugins.accepts(held)){
            int slot=ReservoirPlugins.firstEmpty(bag,player.registryAccess());
            if(slot<0)return false;
            ReservoirData.slot(bag,slot,held.split(1),player.registryAccess());changed.run();return true;
        }
        if(!bridge.container(held))return false;
        ItemStack single=held.copyWithCount(1);
        if(!bridge.fill(player.level(),bag,single))return false;
        if(held.getCount()==1)player.setItemInHand(hand,single);
        else {held.shrink(1);if(!player.getInventory().add(single))player.drop(single,false);}
        changed.run();return true;
    }
    private static void rightClick(PlayerInteractEvent.RightClickItem event){
        if(event.getHand()!=InteractionHand.MAIN_HAND || !isBackpack(event.getEntity().getOffhandItem()))return;
        var player=event.getEntity();var held=event.getItemStack();
        if(!ReservoirPlugins.accepts(held) && !bridge.container(held))return;
        if(player instanceof ServerPlayer server)interact(server,player.getOffhandItem(),event.getHand(),()->player.getInventory().setChanged());
        // Full / incompatible chambers still consume this fill attempt on both sides;
        // falling through would place the jar or open the offhand pack instead.
        event.setCancellationResult(InteractionResult.CONSUME);event.setCanceled(true);
    }
    private static void rightClickBlock(PlayerInteractEvent.RightClickBlock event){
        var actor=event.getEntity();
        // Let the clicked block handle its normal interaction before ReservoirItem.useOn opens the bag.
        if(event.getHand()!=InteractionHand.MAIN_HAND || !isBackpack(actor.getOffhandItem())
                || event.getLevel().getBlockEntity(event.getPos()) instanceof ReservoirBlockEntity)return;
        var player=event.getEntity();var held=event.getItemStack();
        if(!ReservoirPlugins.accepts(held) && !bridge.container(held))return;
        if(!player.level().isClientSide)interact((ServerPlayer)player,player.getOffhandItem(),event.getHand(),()->player.getInventory().setChanged());
        event.setCancellationResult(InteractionResult.CONSUME);event.setCanceled(true);
    }
    private static void rightClickEntity(PlayerInteractEvent.EntityInteract event){
        if(isBackpack(event.getItemStack()) && !event.getEntity().isSecondaryUseActive()){
            event.setCancellationResult(ReservoirItem.openHeld(event.getEntity(),event.getItemStack()));event.setCanceled(true);
        }
    }
    private static void rightClickEntitySpecific(PlayerInteractEvent.EntityInteractSpecific event){
        if(isBackpack(event.getItemStack()) && !event.getEntity().isSecondaryUseActive()){
            event.setCancellationResult(ReservoirItem.openHeld(event.getEntity(),event.getItemStack()));event.setCanceled(true);
        }
    }
    private static void beforeTick(PlayerTickEvent.Pre event){
        if(!(event.getEntity() instanceof ServerPlayer player))return;
        dev.senluo.witchcraft.brew.FrostAbilities.beginFrame(player);
        if(!player.isAlive() || player.isSpectator() || dev.senluo.witchcraft.ritual.OwnershipRitual.frozen(player))return;
        ItemStack bag=worn(player);
        if(!bag.isEmpty())ReservoirPlugins.tick(player,bag,ReservoirPluginItem.Phase.BEFORE_PLAYER);
    }
    private static void tick(PlayerTickEvent.Post event){
        if(!(event.getEntity() instanceof ServerPlayer player) || !player.isAlive() || player.isSpectator()
                || dev.senluo.witchcraft.ritual.OwnershipRitual.frozen(player))return;
        ItemStack bag=worn(player);
        if(player.tickCount%5==0){
            // Inventory includes armor and offhand. Curios may expose the same stack, so deduplicate by identity.
            var seen=java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<ItemStack,Boolean>());
            boolean changed=false;
            var inventory=player.getInventory();
            for(int slot=0;slot<inventory.getContainerSize();slot++){
                ItemStack carried=inventory.getItem(slot);
                if(isBackpack(carried) && seen.add(carried))changed|=ReservoirTransfers.processInput(player.level(),carried);
            }
            if(!bag.isEmpty() && seen.add(bag))changed|=ReservoirTransfers.processInput(player.level(),bag);
            if(changed)inventory.setChanged();
        }
        if(!bag.isEmpty())ReservoirPlugins.tick(player,bag);
    }
    public static void open(ServerPlayer player,ItemStack bag){
        if(!isBackpack(bag) || !ReservoirData.supported(bag) || !player.isAlive() || player.isSpectator()
                || player.containerMenu!=player.inventoryMenu || dev.senluo.witchcraft.ritual.OwnershipRitual.frozen(player))return;
        player.openMenu(new SimpleMenuProvider((id,inventory,p)->new ReservoirMenu(id,inventory,bag,
                ()->worn(p)==bag || p.getMainHandItem()==bag || p.getOffhandItem()==bag,()->p.getInventory().setChanged()),bag.getHoverName()));
    }
    public record Open() implements CustomPacketPayload {
        public static final Type<Open> TYPE=new Type<>(id("open_reservoir"));
        public static final StreamCodec<RegistryFriendlyByteBuf,Open> CODEC=StreamCodec.unit(new Open());
        public Type<Open> type(){return TYPE;}
    }
    private static void packets(RegisterPayloadHandlersEvent event){
        event.registrar(KaleidoscopeWitchcraft.NETWORK_VERSION).playToServer(Open.TYPE,Open.CODEC,(p,c)->c.enqueueWork(()->{
            if(c.player() instanceof ServerPlayer player && player.containerMenu==player.inventoryMenu && player.isAlive() && !player.isSpectator()
                    && !dev.senluo.witchcraft.ritual.OwnershipRitual.frozen(player)){
                var bag=accessible(player);if(!bag.isEmpty())open(player,bag);
            }
        }));
    }
}
