package dev.senluo.witchcraft.reservoir;

import dev.senluo.witchcraft.brew.FrostAbilities;
import dev.senluo.witchcraft.ritual.OwnershipRitual;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

/** One server viewer bound to one real host. No global viewer map or serialized feedback state. */
public final class ReservoirSession {
    private final ServerPlayer player;
    private final ItemStack bag;
    private final BooleanSupplier hostValid;
    private final Runnable markChanged;
    private final LongSupplier transferRevision;
    private long seenTransferRevision,lastPourTick=Long.MIN_VALUE;
    private boolean closed;

    private ReservoirSession(ServerPlayer player,ItemStack bag,BooleanSupplier hostValid,
                             Runnable markChanged,LongSupplier transferRevision){
        this.player=player;this.bag=bag;this.hostValid=hostValid;
        this.markChanged=markChanged;this.transferRevision=transferRevision;
        seenTransferRevision=transferRevision.getAsLong();
    }

    public static void openHeld(ServerPlayer player,ItemStack bag){
        if(player.containerMenu!=player.inventoryMenu)return;
        open(new ReservoirSession(player,bag,
                ()->ReservoirEquipment.worn(player)==bag || player.getMainHandItem()==bag || player.getOffhandItem()==bag,
                ()->player.getInventory().setChanged(),()->0));
    }

    static void openPlaced(ServerPlayer player,ReservoirBlockEntity block){
        ItemStack bound=block.bag();
        open(new ReservoirSession(player,bound,
                ()->!block.isRemoved() && block.bag()==bound && player.level()==block.getLevel()
                        && player.canInteractWithBlock(block.getBlockPos(),1),
                block::changed,block::transferRevision));
    }

    private static void open(ReservoirSession session){
        if(!ReservoirActions.canAct(session.player) || !ReservoirContent.isBackpack(session.bag)
                || !ReservoirData.supported(session.bag) || !session.valid(session.player))return;
        var opened=session.player.openMenu(new SimpleMenuProvider(
                (id,inventory,actor)->new ReservoirMenu(id,inventory,session),session.bag.getHoverName()));
        if(opened.isPresent())ReservoirFeedback.play(session.player,ReservoirFeedback.Action.OPEN);
    }

    ItemStack bag(){return bag;}

    boolean valid(Player actor){
        return !closed && actor==player && hostValid.getAsBoolean()
                && player.isAlive() && !OwnershipRitual.frozen(player);
    }

    void changed(){markChanged.run();}

    ReservoirActions.Result control(Player actor,int id,ItemStack carried){
        if(!valid(actor))return ReservoirActions.Result.NONE;
        var result=ReservoirActions.control(player,bag,id,carried);
        if(result.changed()){
            changed();
            if(result.pluginToggled())FrostAbilities.sync(player);
        }
        return result;
    }

    void feedback(ReservoirActions.Result result){ReservoirFeedback.committed(player,result);}

    void pollTransfers(){
        long revision=transferRevision.getAsLong();
        if(revision!=seenTransferRevision){seenTransferRevision=revision;pour();}
    }

    /** Carried hosts deliver the transaction result directly; placed hosts expose a transient revision. */
    static void transferred(ServerPlayer player,ItemStack bag,ReservoirTransfers.Result result){
        if(!result.movedEssentia() || !(player.containerMenu instanceof ReservoirMenu menu))return;
        var session=menu.session();
        if(session!=null && session.bag==bag)session.pour();
    }

    private void pour(){
        if(!valid(player))return;
        long now=player.level().getGameTime();
        if(lastPourTick==Long.MIN_VALUE || now-lastPourTick>=10){
            lastPourTick=now;ReservoirFeedback.play(player,ReservoirFeedback.Action.POUR);
        }
    }

    void close(){
        if(closed)return;
        closed=true;
        ReservoirFeedback.play(player,ReservoirFeedback.Action.CLOSE);
    }
}
