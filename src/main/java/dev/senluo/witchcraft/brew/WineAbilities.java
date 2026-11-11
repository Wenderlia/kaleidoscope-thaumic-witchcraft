package dev.senluo.witchcraft.brew;

import dev.senluo.witchcraft.KaleidoscopeWitchcraft;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.INBTSerializable;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.registries.*;
import java.util.function.Supplier;

/** Player-owned clocks. World bridges and target embers have their own lifetimes. */
public final class WineAbilities {
    private static final DeferredRegister<AttachmentType<?>> DATA=DeferredRegister.create(NeoForgeRegistries.ATTACHMENT_TYPES,KaleidoscopeWitchcraft.MOD_ID);
    private static final Supplier<AttachmentType<State>> STATE=DATA.register("wine_abilities",()->AttachmentType.serializable(State::new).build());
    private static final Supplier<AttachmentType<Integer>> FLAGS=DATA.register("wine_ability_flags",()->AttachmentType.builder(()->0).sync(ByteBufCodecs.VAR_INT).build());
    private WineAbilities(){}
    public static void register(IEventBus bus){
        DATA.register(bus);FrostPath.register(bus);FrostGather.register();DuskEmbers.register(bus);SolarFire.register();
        NeoForge.EVENT_BUS.addListener(WineAbilities::tick);
    }
    static State state(ServerPlayer player){return player.getData(STATE.get());}
    static State existing(ServerPlayer player){return player.hasData(STATE.get())?player.getData(STATE.get()):null;}
    public static int frost(Entity e){return quality(e,0);}
    public static int sun(Entity e){return quality(e,6);}
    static boolean moduleFlag(Entity e){return e.hasData(FLAGS.get()) && (e.getData(FLAGS.get())&FrostAbilities.MODULE_FLAG)!=0;}
    private static int quality(Entity e,int shift){
        if(!e.isAlive() || e.isSpectator())return 0;
        if(e.level().isClientSide)return e.hasData(FLAGS.get())?(e.getData(FLAGS.get())>>shift)&7:0;
        if(!(e instanceof ServerPlayer p) || !p.hasData(STATE.get()))return 0;
        State s=p.getData(STATE.get());if(s.unsupported!=null)return 0;
        return (shift==0?s.frost:s.sun).activeQuality();
    }
    public static void drink(ServerPlayer p,AmberVintage vintage,int q){
        if(q<2 || q>6)return;
        // One drinking entry; each ability retains its own persistence and world objects.
        // These two do not use the Frost/Dusk/Sun clocks or their synchronization flags.
        if(vintage==AmberVintage.DAWN){DawnWalk.drink(p,q);return;}
        if(vintage==AmberVintage.HONEYED){AmberVaults.drink(p,q);return;}
        State s=state(p);if(s.unsupported!=null)return;
        switch(vintage){
            case FROST -> {s.frost.refresh(q,VintageEffects.duration(vintage,q),q==4?150:q==5?250:0);
                s.frost.remaining=VintageEffects.align(p,vintage,s.frost.remaining);s.footing.reset();FrostPath.start(p);}
            case AGED -> {s.dusk.refresh(q,VintageEffects.duration(vintage,q),0);s.dusk.remaining=VintageEffects.align(p,vintage,s.dusk.remaining);}
            case EVERDAY -> {s.sun.refresh(q,VintageEffects.duration(vintage,q),0);SolarFire.start(p,s.sun);}
            default -> {}
        }
        sync(p,s);
    }
    static void sync(ServerPlayer p,State s){
        int value=s.frost.activeQuality()|(s.sun.activeQuality()<<6)
                |(p.isAlive() && !p.isSpectator() && FrostAbilities.moduleActive(p)?FrostAbilities.MODULE_FLAG:0);
        if((!p.hasData(FLAGS.get()) && value!=0) || p.hasData(FLAGS.get()) && p.getData(FLAGS.get())!=value)p.setData(FLAGS.get(),value);
    }
    private static void tick(PlayerTickEvent.Pre event){
        if(!(event.getEntity() instanceof ServerPlayer p) || !p.hasData(STATE.get()))return;
        State s=state(p);if(s.unsupported!=null)return;
        if(!p.isAlive()){s.frost.clear();s.dusk.clear();s.sun.clear();sync(p,s);return;}
        if(dev.senluo.witchcraft.ritual.OwnershipRitual.frozen(p))return;
        if(!p.isSpectator()){
            if(FrostAbilities.view(p).support()){
                p.serverLevel().getProfiler().push("witchcraft_frost_path");
                try{FrostPath.tick(p,s.frost,s.footing);}finally{p.serverLevel().getProfiler().pop();}
            }
            if(s.sun.remaining>0){
                p.serverLevel().getProfiler().push("witchcraft_solar_fire");
                try{SolarFire.tick(p,s.sun);}finally{p.serverLevel().getProfiler().pop();}
            }
        }
        s.frost.advance();s.dusk.advance();s.sun.advance();sync(p,s);
    }
    static final class Window {
        int remaining,quality,credits,revision;
        int activeQuality(){return remaining>0?quality:0;}
        void refresh(int q,int time,int count){revision++;quality=Math.max(quality,q);remaining=Math.max(remaining,time);credits=Math.max(credits,count);}
        void advance(){if(remaining>0 && --remaining==0)clear();}
        void clear(){revision++;remaining=quality=credits=0;}
        CompoundTag save(){CompoundTag t=new CompoundTag();t.putInt("Time",remaining);t.putInt("Quality",quality);t.putInt("Credits",credits);return t;}
        void load(CompoundTag t,int max){revision++;remaining=Math.clamp(t.getInt("Time"),0,max);quality=Math.clamp(t.getInt("Quality"),0,6);credits=Math.clamp(t.getInt("Credits"),0,250);if(quality<2 || remaining==0)clear();}
    }
    static final class State implements INBTSerializable<CompoundTag> {
        final Window frost=new Window(),dusk=new Window(),sun=new Window();CompoundTag unsupported;
        final FrostPath.Footing footing=new FrostPath.Footing();
        java.util.function.BooleanSupplier moduleValidity;long moduleThrough;
        @Override public CompoundTag serializeNBT(HolderLookup.Provider p){
            if(unsupported!=null)return unsupported.copy();CompoundTag t=new CompoundTag();t.putInt("Version",1);
            t.put("Frost",frost.save());t.put("Dusk",dusk.save());t.put("Sun",sun.save());return t;
        }
        @Override public void deserializeNBT(HolderLookup.Provider p,CompoundTag t){
            frost.clear();dusk.clear();sun.clear();footing.reset();unsupported=null;moduleValidity=null;
            if(t.getInt("Version")!=1){unsupported=t.copy();return;}
            frost.load(t.getCompound("Frost"),Integer.MAX_VALUE);dusk.load(t.getCompound("Dusk"),Integer.MAX_VALUE);sun.load(t.getCompound("Sun"),200);
        }
    }
}
