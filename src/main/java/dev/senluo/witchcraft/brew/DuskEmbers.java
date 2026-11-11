package dev.senluo.witchcraft.brew;

import dev.senluo.witchcraft.KaleidoscopeWitchcraft;
import net.minecraft.core.*;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.*;
import net.minecraft.resources.*;
import net.minecraft.server.level.*;
import net.minecraft.sounds.*;
import net.minecraft.world.damagesource.*;
import net.minecraft.world.effect.*;
import net.minecraft.world.entity.*;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.*;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.INBTSerializable;
import net.neoforged.neoforge.event.entity.living.*;
import net.neoforged.neoforge.event.tick.*;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.registries.*;
import org.joml.Vector3f;
import java.util.*;
import java.util.function.Supplier;

/** Target-owned finite embers; bursts are detached before any damage callback. */
public final class DuskEmbers {
    private static final DeferredRegister<AttachmentType<?>> DATA=DeferredRegister.create(NeoForgeRegistries.ATTACHMENT_TYPES,KaleidoscopeWitchcraft.MOD_ID);
    private static final Supplier<AttachmentType<Marks>> MARKS=DATA.register("dusk_embers",()->AttachmentType.serializable(Marks::new).build());
    private static final Map<ServerLevel,ArrayDeque<Burst>> QUEUES=new WeakHashMap<>();
    private static final Map<ServerLevel,Set<LivingEntity>> DEATHS=new WeakHashMap<>();
    private static final DustParticleOptions EMBER=new DustParticleOptions(new Vector3f(1,.38F,.07F),.8F);
    static final ResourceKey<DamageType> DAMAGE=ResourceKey.create(Registries.DAMAGE_TYPE,ResourceLocation.fromNamespaceAndPath(KaleidoscopeWitchcraft.MOD_ID,"dusk_burst"));
    private DuskEmbers(){}
    static void register(IEventBus bus){
        DATA.register(bus);NeoForge.EVENT_BUS.addListener(DuskEmbers::hit);NeoForge.EVENT_BUS.addListener(DuskEmbers::tick);
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST,DuskEmbers::death);NeoForge.EVENT_BUS.addListener(DuskEmbers::flush);
        NeoForge.EVENT_BUS.addListener((ServerStoppedEvent e)->{QUEUES.clear();DEATHS.clear();});
    }
    private static void hit(LivingDamageEvent.Post e){
        if(!(e.getSource().getEntity() instanceof ServerPlayer p) || e.getSource().getDirectEntity()!=p
                || !e.getSource().is(DamageTypes.PLAYER_ATTACK) || e.getNewDamage()<=0 || !Float.isFinite(e.getNewDamage())
                || !WineTargets.hostile(p,e.getEntity()))return;
        WineAbilities.State s=WineAbilities.existing(p);if(s==null || s.unsupported!=null || s.dusk.activeQuality()<2)return;
        int q=s.dusk.quality;Marks marks=e.getEntity().getData(MARKS.get());if(marks.unsupported!=null)return;
        Mark m=marks.values.computeIfAbsent(p.getUUID(),id->new Mark());m.quality=Math.max(m.quality,q);m.layers++;
        double rate=q>=6?.25:q>=4?.20:.15;
        m.energy=Math.min(Float.MAX_VALUE/64.,m.energy+e.getNewDamage()*rate);m.delay=30;m.life=80;
        if(m.layers==1)p.serverLevel().playSound(null,e.getEntity().blockPosition(),SoundEvents.FIRE_AMBIENT,SoundSource.PLAYERS,.18F,1.35F);
        if(m.layers>=maximum(m.quality) || e.getEntity().isDeadOrDying())enqueue(p.serverLevel(),e.getEntity(),p.getUUID(),marks);
    }
    private static int maximum(int q){return q>=6?5:q>=4?4:3;}
    private static void tick(EntityTickEvent.Post e){
        if(!(e.getEntity() instanceof LivingEntity target) || !(target.level() instanceof ServerLevel level) || !target.hasData(MARKS.get()))return;
        Marks marks=target.getData(MARKS.get());if(marks.unsupported!=null || dev.senluo.witchcraft.ritual.OwnershipRitual.frozen(target))return;
        for(UUID id:new ArrayList<>(marks.values.keySet())){
            Mark m=marks.values.get(id);if(--m.life<=0){marks.values.remove(id);continue;}
            if(--m.delay<=0)enqueue(level,target,id,marks);
        }
        if(!marks.values.isEmpty() && target.tickCount%5==0){
            int layers=marks.values.values().stream().mapToInt(m->m.layers).max().orElse(1);
            int delay=marks.values.values().stream().mapToInt(m->m.delay).min().orElse(30);
            double spread=delay<=10?.08:.22;
            level.sendParticles(EMBER,target.getX(),target.getY()+target.getBbHeight()*.6,target.getZ(),Math.min(5,layers),spread,.18,spread,0);
            if(layers>=3)level.sendParticles(net.minecraft.core.particles.ParticleTypes.SMALL_FLAME,target.getX(),target.getY()+target.getBbHeight()*.6,target.getZ(),1,.1,.15,.1,0);
        }
        if(marks.values.isEmpty())target.removeData(MARKS.get());
    }
    private static void death(LivingDeathEvent e){
        if(!(e.getEntity().level() instanceof ServerLevel level) || !e.getEntity().hasData(MARKS.get()))return;
        // LivingDeath can precede the final LivingDamage.Post. Detach after both
        // callbacks so the finishing blow belongs to the same stored charge.
        DEATHS.computeIfAbsent(level,l->new HashSet<>()).add(e.getEntity());
    }
    private static void enqueue(ServerLevel level,LivingEntity target,UUID owner,Marks marks){
        Mark m=marks.values.remove(owner);if(m==null || m.energy<=0)return;
        QUEUES.computeIfAbsent(level,l->new ArrayDeque<>()).add(new Burst(owner,target.position().add(0,target.getBbHeight()*.5,0),m));
    }
    private record Burst(UUID owner,Vec3 position,Mark mark){}
    private static void flush(LevelTickEvent.Post event){
        if(!(event.getLevel() instanceof ServerLevel level))return;
        Set<LivingEntity> deaths=DEATHS.remove(level);
        if(deaths!=null)for(LivingEntity target:deaths){
            if(!target.isDeadOrDying() || !target.hasData(MARKS.get()))continue;
            Marks marks=target.getData(MARKS.get());
            for(UUID id:new ArrayList<>(marks.values.keySet()))enqueue(level,target,id,marks);
        }
        ArrayDeque<Burst> queue=QUEUES.get(level);if(queue==null)return;
        // Only this tick's generation: damage callbacks cannot recurse into the same batch.
        level.getProfiler().push("witchcraft_dusk_bursts");
        try{int size=queue.size();while(size-->0){Burst b=queue.removeFirst();ServerPlayer p=level.getServer().getPlayerList().getPlayer(b.owner);
            if(p!=null && p.level()==level)burst(p,b);}}
        finally{level.getProfiler().pop();}
        if(queue.isEmpty())QUEUES.remove(level);
    }
    private static void burst(ServerPlayer p,Burst b){
        ServerLevel l=p.serverLevel();Mark m=b.mark;double radius=m.quality>=4?3.5:2.5;boolean killed=false;
        DamageSource source=new DamageSource(l.registryAccess().registryOrThrow(Registries.DAMAGE_TYPE).getHolderOrThrow(DAMAGE),p);
        for(LivingEntity target:WineTargets.enemies(p,b.position,radius,12)){
            if(target.hurt(source,(float)m.energy)){
                killed|=target.isDeadOrDying();
                if(target.isAlive()){target.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN,20,2));target.addEffect(new MobEffectInstance(MobEffects.BLINDNESS,30));
                    Vec3 out=target.position().subtract(b.position);target.knockback(.25,-out.x,-out.z);}
            }
        }
        l.sendParticles(EMBER,b.position.x,b.position.y,b.position.z,18,.65,.4,.65,.08);
        l.sendParticles(WineParticles.DUSK_WAVE.get(),b.position.x,b.position.y,b.position.z,0,radius,0,0,1);
        l.sendParticles(net.minecraft.core.particles.ParticleTypes.SMALL_FLAME,b.position.x,b.position.y,b.position.z,8,.2,.3,.2,.035);
        l.playSound(null,BlockPos.containing(b.position),SoundEvents.FIRECHARGE_USE,SoundSource.PLAYERS,.65F,.65F);
        l.playSound(null,BlockPos.containing(b.position),SoundEvents.FIRE_EXTINGUISH,SoundSource.PLAYERS,.22F,.8F);
        if(m.quality>=4 && m.layers>=3 && p.isAlive()){
            p.heal(m.quality-2);p.removeEffect(MobEffects.MOVEMENT_SLOWDOWN);p.removeEffect(MobEffects.WEAKNESS);p.removeEffect(MobEffects.DIG_SLOWDOWN);
        }
        if(m.quality>=6 && killed && m.layers>=2){
            var recipients=WineTargets.enemies(p,b.position,4,12);
            double share=recipients.isEmpty()?0:m.energy*.5/recipients.size();
            for(LivingEntity e:recipients){
                Marks marks=e.getData(MARKS.get());if(marks.unsupported!=null)continue;
                Mark next=marks.values.computeIfAbsent(p.getUUID(),id->new Mark());
                next.energy=Math.min(Float.MAX_VALUE/64.,next.energy+share);next.layers=Math.min(5,next.layers+m.layers/2);
                next.quality=6;next.delay=30;next.life=80;
                if(next.layers>=maximum(next.quality))enqueue(l,e,p.getUUID(),marks);
            }
        }
    }
    static final class Mark {int quality,layers,delay,life;double energy;}
    static final class Marks implements INBTSerializable<CompoundTag> {
        final Map<UUID,Mark> values=new HashMap<>();CompoundTag unsupported;
        public CompoundTag serializeNBT(HolderLookup.Provider p){
            if(unsupported!=null)return unsupported.copy();CompoundTag t=new CompoundTag();t.putInt("Version",1);ListTag list=new ListTag();
            values.forEach((id,m)->{CompoundTag v=new CompoundTag();v.putUUID("Owner",id);v.putInt("Quality",m.quality);v.putInt("Layers",m.layers);
                v.putInt("Delay",m.delay);v.putInt("Life",m.life);v.putDouble("Energy",m.energy);list.add(v);});t.put("Marks",list);return t;
        }
        public void deserializeNBT(HolderLookup.Provider p,CompoundTag t){
            values.clear();unsupported=null;
            if(t.getInt("Version")!=1 || !(t.get("Marks") instanceof ListTag list) || !list.isEmpty() && list.getElementType()!=Tag.TAG_COMPOUND){unsupported=t.copy();return;}
            for(Tag entry:t.getList("Marks",Tag.TAG_COMPOUND)){CompoundTag v=(CompoundTag)entry;if(!v.hasUUID("Owner"))continue;
                Mark m=new Mark();m.quality=Math.clamp(v.getInt("Quality"),2,6);m.layers=Math.clamp(v.getInt("Layers"),1,5);
                m.delay=Math.clamp(v.getInt("Delay"),1,30);m.life=Math.clamp(v.getInt("Life"),1,80);m.energy=v.getDouble("Energy");
                if(Double.isFinite(m.energy) && m.energy>0 && m.energy<=Float.MAX_VALUE/64.)values.put(v.getUUID("Owner"),m);
            }
        }
    }
}
