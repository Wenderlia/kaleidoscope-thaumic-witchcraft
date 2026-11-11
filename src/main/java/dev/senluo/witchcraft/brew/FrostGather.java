package dev.senluo.witchcraft.brew;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.BlockDropsEvent;
import net.neoforged.neoforge.event.tick.EntityTickEvent;
import java.util.UUID;

/** Provenance comes from the actual mining drop event, never a nearby-item scan. */
public final class FrostGather {
    private static final String KEY="witchcraft_frost_gather";
    private FrostGather(){}
    static void register(){NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST,FrostGather::drops);NeoForge.EVENT_BUS.addListener(FrostGather::tick);NeoForge.EVENT_BUS.addListener(FrostGather::target);}
    private static void target(net.neoforged.neoforge.entity.XpOrbTargetingEvent event){
        UUID id=owner(event.getXpOrb());
        if(id!=null && event.getXpOrb().level() instanceof ServerLevel level){
            var p=level.getServer().getPlayerList().getPlayer(id);
            event.setFollowingPlayer(p!=null && p.level()==level && FrostAbilities.view(p).gather()?p:null);
        }
    }
    private static void drops(BlockDropsEvent e){
        if(!(e.getBreaker() instanceof ServerPlayer p) || !FrostAbilities.view(p).gather())return;
        for(ItemEntity item:e.getDrops())mark(item,p);
    }
    private static void mark(Entity e,ServerPlayer p){
        CompoundTag t=new CompoundTag();t.putUUID("Owner",p.getUUID());t.putInt("Time",100);t.putBoolean("Gravity",e.isNoGravity());
        e.getPersistentData().put(KEY,t);
    }
    public static UUID owner(Entity e){CompoundTag t=e.getPersistentData().getCompound(KEY);return t.hasUUID("Owner")?t.getUUID("Owner"):null;}
    public static boolean experience(ServerLevel level,BlockPos pos,int amount,Entity breaker){
        if(!(breaker instanceof ServerPlayer p) || !FrostAbilities.view(p).gather())return false;
        if(!level.getGameRules().getBoolean(GameRules.RULE_DOBLOCKDROPS) || level.restoringBlockSnapshots)return true;
        // Vanilla denomination and pickup/mending behavior, without merging into unrelated XP.
        while(amount>0){int value=ExperienceOrb.getExperienceValue(amount);amount-=value;
            ExperienceOrb orb=new ExperienceOrb(level,pos.getX()+.5,pos.getY()+.5,pos.getZ()+.5,value);mark(orb,p);level.addFreshEntity(orb);}
        return true;
    }
    private static void tick(EntityTickEvent.Pre event){
        Entity e=event.getEntity();if(!(e.level() instanceof ServerLevel level) || !(e instanceof ItemEntity || e instanceof ExperienceOrb) || !e.getPersistentData().contains(KEY))return;
        CompoundTag t=e.getPersistentData().getCompound(KEY);int time=t.getInt("Time");UUID owner=owner(e);
        var p=owner==null?null:level.getServer().getPlayerList().getPlayer(owner);
        if(time<=0 || p==null || p.level()!=level || !p.isAlive() || !FrostAbilities.view(p).gather() || p.distanceToSqr(e)>256){
            e.setNoGravity(t.getBoolean("Gravity"));e.getPersistentData().remove(KEY);return;
        }
        if(dev.senluo.witchcraft.ritual.OwnershipRitual.frozen(p))return;
        t.putInt("Time",time-1);e.setNoGravity(true);
        Vec3 offset=p.position().add(0,.35,0).subtract(e.position());double distance=offset.length();
        if(distance>.05)e.setDeltaMovement(e.getDeltaMovement().scale(.55).add(offset.scale(Math.min(.14,distance*.12)/distance)));
        e.hasImpulse=true;
    }
}
