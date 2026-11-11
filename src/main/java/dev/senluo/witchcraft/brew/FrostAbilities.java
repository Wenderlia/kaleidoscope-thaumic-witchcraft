package dev.senluo.witchcraft.brew;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import java.util.function.BooleanSupplier;

/** Merge independent paid sources by capability, without modifying wine clocks or credits. */
public final class FrostAbilities {
    static final int MODULE_FLAG=1<<3;
    private FrostAbilities(){}
    public record View(boolean quiet,boolean gather,boolean support,boolean cross,boolean unlimited,int lifetime){}
    private static final View NONE=new View(false,false,false,false,false,0);
    public static View view(Entity entity){
        if(!entity.isAlive() || entity.isSpectator() || dev.senluo.witchcraft.ritual.OwnershipRitual.frozen(entity))return NONE;
        int wine=WineAbilities.frost(entity);
        boolean module=entity.level().isClientSide?WineAbilities.moduleFlag(entity)
                :entity instanceof ServerPlayer player && moduleActive(player);
        return new View(module || wine>=2,module || wine>=2,module || wine>=4,wine>=6,
                module || wine>=6,wine>=6?100:wine>=4?60:module?20:0);
    }
    /** Clear the preceding frame before installed sources receive their next billing opportunity. */
    public static void beginFrame(ServerPlayer player){
        var state=WineAbilities.existing(player);
        if(state!=null)state.moduleValidity=null;
    }
    public static boolean moduleActive(ServerPlayer player){
        var state=WineAbilities.existing(player);
        return state!=null && state.unsupported==null && state.moduleValidity!=null
                && player.level().getGameTime()<=state.moduleThrough && state.moduleValidity.getAsBoolean();
    }
    public static boolean acceptsModule(ServerPlayer player){
        return WineAbilities.state(player).unsupported==null && !moduleActive(player);
    }
    public static void grantModule(ServerPlayer player,BooleanSupplier stillInstalled){
        var state=WineAbilities.state(player);
        if(state.unsupported!=null)return;
        // Packet-driven movement may precede the next PlayerTick.Pre. The next frame
        // replaces this lease before billing, while removal invalidates it immediately.
        state.moduleValidity=stillInstalled;state.moduleThrough=player.level().getGameTime()+1;
    }
    public static void sync(ServerPlayer player){
        var state=WineAbilities.existing(player);if(state!=null)WineAbilities.sync(player,state);
    }
}
