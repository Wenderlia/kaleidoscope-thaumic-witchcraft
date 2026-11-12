package dev.senluo.witchcraft.reservoir;

import dev.senluo.witchcraft.KaleidoscopeWitchcraft;
import dev.senluo.witchcraft.brew.FrostAbilities;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import java.util.Collections;
import java.util.IdentityHashMap;

/** Native event/packet adapter. Keeps discovery, click priority and tick scheduling out of storage. */
public final class ReservoirRuntime {
    static void register(IEventBus bus){
        bus.addListener(ReservoirRuntime::packets);
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGH,ReservoirRuntime::beforeTick);
        NeoForge.EVENT_BUS.addListener(ReservoirRuntime::tick);
        NeoForge.EVENT_BUS.addListener(ReservoirRuntime::rightClick);
        NeoForge.EVENT_BUS.addListener(ReservoirRuntime::rightClickBlock);
        NeoForge.EVENT_BUS.addListener(ReservoirRuntime::rightClickEntity);
        NeoForge.EVENT_BUS.addListener(ReservoirRuntime::rightClickEntitySpecific);
    }

    static boolean useHeld(ServerPlayer player,ItemStack bag,InteractionHand hand,Runnable changed){
        var result=ReservoirActions.useHeld(player,bag,hand);
        if(!result.changed())return false;
        changed.run();
        ReservoirFeedback.committed(player,result);
        return true;
    }

    private static boolean usesOffhandBag(PlayerInteractEvent event){
        return event.getHand()==InteractionHand.MAIN_HAND
                && ReservoirContent.isBackpack(event.getEntity().getOffhandItem())
                && ReservoirActions.acceptsInput(event.getItemStack());
    }

    private static void useOffhandBag(PlayerInteractEvent event){
        var player=event.getEntity();
        if(player instanceof ServerPlayer server)
            useHeld(server,player.getOffhandItem(),event.getHand(),()->player.getInventory().setChanged());
    }

    private static void rightClick(PlayerInteractEvent.RightClickItem event){
        if(!usesOffhandBag(event))return;
        useOffhandBag(event);
        // A failed fill still consumes this attempt on both sides; do not place the jar or open the pack.
        event.setCancellationResult(InteractionResult.CONSUME);
        event.setCanceled(true);
    }

    private static void rightClickBlock(PlayerInteractEvent.RightClickBlock event){
        // A placed backpack owns its interaction; otherwise the offhand fill keeps its existing priority.
        if(!usesOffhandBag(event) || event.getLevel().getBlockEntity(event.getPos()) instanceof ReservoirBlockEntity)return;
        useOffhandBag(event);
        event.setCancellationResult(InteractionResult.CONSUME);
        event.setCanceled(true);
    }

    private static InteractionResult openHeldOnEntity(PlayerInteractEvent event){
        return ReservoirContent.isBackpack(event.getItemStack()) && !event.getEntity().isSecondaryUseActive()
                ?ReservoirItem.openHeld(event.getEntity(),event.getItemStack()):InteractionResult.PASS;
    }

    private static void rightClickEntity(PlayerInteractEvent.EntityInteract event){
        var result=openHeldOnEntity(event);
        if(result==InteractionResult.PASS)return;
        event.setCancellationResult(result);event.setCanceled(true);
    }
    private static void rightClickEntitySpecific(PlayerInteractEvent.EntityInteractSpecific event){
        var result=openHeldOnEntity(event);
        if(result==InteractionResult.PASS)return;
        event.setCancellationResult(result);event.setCanceled(true);
    }

    private static void beforeTick(PlayerTickEvent.Pre event){
        if(!(event.getEntity() instanceof ServerPlayer player))return;
        FrostAbilities.beginFrame(player);
        if(!ReservoirActions.canAct(player))return;
        ItemStack bag=ReservoirEquipment.worn(player);
        if(!bag.isEmpty())ReservoirPlugins.tick(player,bag,ReservoirPluginItem.Phase.BEFORE_PLAYER);
    }

    private static void tick(PlayerTickEvent.Post event){
        if(!(event.getEntity() instanceof ServerPlayer player) || !ReservoirActions.canAct(player))return;
        ItemStack bag=ReservoirEquipment.worn(player);
        if(player.tickCount%5==0){
            // The native inventory includes armor/offhand; Curios may return that same real stack.
            var seen=Collections.newSetFromMap(new IdentityHashMap<ItemStack,Boolean>());
            boolean changed=false;
            var inventory=player.getInventory();
            for(int slot=0;slot<inventory.getContainerSize();slot++){
                ItemStack carried=inventory.getItem(slot);
                if(ReservoirContent.isBackpack(carried) && seen.add(carried))changed|=processInput(player,carried);
            }
            if(!bag.isEmpty() && seen.add(bag))changed|=processInput(player,bag);
            if(changed)inventory.setChanged();
        }
        if(!bag.isEmpty())ReservoirPlugins.tick(player,bag);
    }

    private static boolean processInput(ServerPlayer player,ItemStack bag){
        var result=ReservoirTransfers.processInputResult(player.level(),bag);
        ReservoirSession.transferred(player,bag,result);
        return result.changed();
    }

    public record Open() implements CustomPacketPayload {
        public static final Type<Open> TYPE=new Type<>(ReservoirContent.id("open_reservoir"));
        public static final StreamCodec<RegistryFriendlyByteBuf,Open> CODEC=StreamCodec.unit(new Open());
        public Type<Open> type(){return TYPE;}
    }

    private static void packets(RegisterPayloadHandlersEvent event){
        event.registrar(KaleidoscopeWitchcraft.NETWORK_VERSION).playToServer(Open.TYPE,Open.CODEC,(payload,context)->context.enqueueWork(()->{
            if(context.player() instanceof ServerPlayer player && player.containerMenu==player.inventoryMenu
                    && ReservoirActions.canAct(player)){
                var bag=ReservoirEquipment.accessible(player);
                if(!bag.isEmpty())ReservoirSession.openHeld(player,bag);
            }
        }));
    }

    private ReservoirRuntime(){}
}
