package dev.senluo.witchcraft.brew;

import dev.senluo.witchcraft.KaleidoscopeWitchcraft;
import net.minecraft.core.*;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.*;
import net.minecraft.tags.EntityTypeTags;
import net.minecraft.world.damagesource.*;
import net.minecraft.world.effect.*;
import net.minecraft.world.entity.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.CommonHooks;
import net.neoforged.neoforge.event.entity.living.*;
import org.joml.Vector3f;

/** One drink's finite solar window, independent of unrelated later healing. */
public final class SolarFire {
    private static final ResourceKey<DamageType> FIRE=ResourceKey.create(Registries.DAMAGE_TYPE,ResourceLocation.fromNamespaceAndPath(KaleidoscopeWitchcraft.MOD_ID,"solar_fire"));
    public static final ResourceKey<DamageType> SELF=ResourceKey.create(Registries.DAMAGE_TYPE,ResourceLocation.fromNamespaceAndPath(KaleidoscopeWitchcraft.MOD_ID,"solar_self"));
    private static final DustParticleOptions GOLD=new DustParticleOptions(new Vector3f(1,.82F,.25F),1.1F);
    private static final DustParticleOptions CORE=new DustParticleOptions(new Vector3f(1,.97F,.72F),.7F);
    private SolarFire(){}
    static void register(){
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST,SolarFire::death);
        NeoForge.EVENT_BUS.addListener(SolarFire::knockback);NeoForge.EVENT_BUS.addListener(SolarFire::effect);
        NeoForge.EVENT_BUS.addListener(SolarFire::freeze);NeoForge.EVENT_BUS.addListener(SolarFire::selfDamage);
    }
    static void start(ServerPlayer p,WineAbilities.Window w){
        p.serverLevel().playSound(null,p.blockPosition(),SoundEvents.BELL_RESONATE,SoundSource.PLAYERS,.6F,.65F);
        p.serverLevel().playSound(null,p.blockPosition(),SoundEvents.FIRECHARGE_USE,SoundSource.PLAYERS,.5F,.8F);
    }
    static void tick(ServerPlayer p,WineAbilities.Window w){
        var level=p.serverLevel();int revision=w.revision;
        if(!active(p,w,level,revision))return;
        if(w.quality>=6){p.setTicksFrozen(0);p.removeEffect(MobEffects.MOVEMENT_SLOWDOWN);}
        if(!active(p,w,level,revision))return;
        if(p.isUnderWater())p.setAirSupply(p.getMaxAirSupply());
        if(p.tickCount%5==0){
            int hurt=p.hurtTime,duration=p.hurtDuration;
            p.hurt(new DamageSource(p.registryAccess().registryOrThrow(Registries.DAMAGE_TYPE).getHolderOrThrow(SELF)),4);
            p.hurtTime=hurt;p.hurtDuration=duration;
            if(!active(p,w,level,revision))return;
            p.serverLevel().sendParticles(CORE,p.getX(),p.getY()+1,p.getZ(),4,.35,.55,.35,.015);
        }
        if(p.tickCount%10==0){
            double radius=w.quality>=6?8:w.quality>=4?6.5:5;
            float amount=new float[]{0,0,2,2,3,3,4}[w.quality];
            DamageSource source=new DamageSource(p.registryAccess().registryOrThrow(Registries.DAMAGE_TYPE).getHolderOrThrow(FIRE),p);
            for(LivingEntity enemy:WineTargets.enemies(p,p.position(),radius,16)){
                boolean undead=enemy.getType().is(EntityTypeTags.UNDEAD);
                boolean hit=enemy.hurt(source,amount*(undead?2:1));
                if(!active(p,w,level,revision))return;
                if(hit){
                    if(undead)enemy.igniteForSeconds(2);
                    enemy.addEffect(new MobEffectInstance(MobEffects.GLOWING,15,0,false,false));
                    enemy.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN,15,0,false,false));
                    if(!active(p,w,level,revision))return;
                    p.serverLevel().sendParticles(GOLD,enemy.getX(),enemy.getY()+.7,enemy.getZ(),2,.2,.4,.2,.01);
                }
            }
            ring(p,1.1,.12);
            if(p.tickCount%20==0){
                if(w.quality>=4){
                    int remaining=16;
                    for(LivingEntity ally:p.serverLevel().getEntitiesOfClass(LivingEntity.class,p.getBoundingBox().inflate(radius),
                            e->e.isAlive() && e.distanceToSqr(p)<=radius*radius && WineTargets.ally(p,e))){
                        ally.heal(w.quality>=6?2:1);ally.removeEffect(MobEffects.DARKNESS);ally.removeEffect(MobEffects.WITHER);ally.removeEffect(MobEffects.POISON);
                        if(!active(p,w,level,revision))return;
                        if(--remaining==0)break;
                    }
                }
                surface(p,w,level,revision);
                if(!active(p,w,level,revision))return;
                p.serverLevel().playSound(null,p.blockPosition(),SoundEvents.BEACON_AMBIENT,SoundSource.PLAYERS,.18F,1.6F);
            }
        }
        if(w.remaining==1){int quality=w.quality;w.clear();if(quality>=4)finale(p,true);}
    }
    /** Foreign hurt/effect/growth callbacks may kill, dispel, teleport or replace this drink. */
    private static boolean active(ServerPlayer p,WineAbilities.Window w,net.minecraft.server.level.ServerLevel level,int revision){
        if(w.remaining<=0 || w.revision!=revision || !p.isAlive() || p.serverLevel()!=level)return false;
        MobEffectInstance heal=p.getEffect(MobEffects.HEAL);
        if(heal==null || heal.getAmplifier()<(w.quality>=5?1:0) || p.getHealth()<=6){w.clear();return false;}
        return true;
    }
    private static void surface(ServerPlayer p,WineAbilities.Window w,net.minecraft.server.level.ServerLevel level,int revision){
        if(!p.mayBuild())return;boolean grew=false;
        // Only nearby surfaces that the player actually passes; no radius-wide block scan.
        for(BlockPos cursor:BlockPos.betweenClosed(p.blockPosition().offset(-1,-1,-1),p.blockPosition().offset(1,1,1))){
            if(!active(p,w,level,revision))return;
            BlockPos pos=cursor.immutable();var l=p.serverLevel();if(!l.hasChunkAt(pos))continue;
            BlockState before=l.getBlockState(pos),after=null;boolean crop=false;
            if(before.is(Blocks.SNOW))after=Blocks.AIR.defaultBlockState();
            else if(before.is(Blocks.ICE) || before.is(Blocks.FROSTED_ICE))after=l.dimensionType().ultraWarm()?Blocks.AIR.defaultBlockState():Blocks.WATER.defaultBlockState();
            else if(w.quality>=4 && !grew && !before.hasBlockEntity() && l.isAreaLoaded(pos,1) && before.canSurvive(l,pos)){
                after=DawnCultivation.nextStage(before);crop=after!=null;
                if(crop && !CommonHooks.canCropGrow(l,pos,before,true))after=null;
            }
            if(!active(p,w,level,revision))return;
            if(after!=null && DawnCultivation.commit(p,pos,before,after) && crop){grew=true;CommonHooks.fireCropGrowPost(l,pos,before);}
        }
    }
    private static void death(LivingDeathEvent e){
        if(!(e.getEntity() instanceof ServerPlayer p))return;
        WineAbilities.State s=WineAbilities.existing(p);if(s==null || s.unsupported!=null)return;
        int quality=s.sun.activeQuality();s.sun.clear();if(quality>=4)finale(p,false);
    }
    private static void finale(ServerPlayer p,boolean shield){
        if(shield && p.isAlive()){
            float amount=Math.max(p.getAbsorptionAmount(),p.getMaxHealth()*.5F);
            int amplifier=Math.max(0,(int)Math.ceil(amount/4)-1);
            p.addEffect(new MobEffectInstance(MobEffects.ABSORPTION,400,amplifier));
            // Native effect levels round the maximum up in four-point steps, not the award.
            if(p.hasEffect(MobEffects.ABSORPTION))p.setAbsorptionAmount(amount);
        }
        for(LivingEntity e:WineTargets.enemies(p,p.position(),10,16)){
            Vec3 away=e.position().subtract(p.position());e.knockback(1.1,-away.x,-away.z);
        }
        ring(p,9.6,.35);
        p.serverLevel().playSound(null,p.blockPosition(),SoundEvents.BELL_RESONATE,SoundSource.PLAYERS,.9F,1.35F);
    }
    private static void ring(ServerPlayer p,double radius,double height){
        // count=0 passes radius as dx to one native particle; client expands the geometry.
        p.serverLevel().sendParticles(WineParticles.SOLAR_WAVE.get(),p.getX(),p.getY()+height,p.getZ(),0,radius,0,0,1);
    }
    private static void knockback(LivingKnockBackEvent e){if(WineAbilities.sun(e.getEntity())>=6)e.setCanceled(true);}
    private static void effect(MobEffectEvent.Applicable e){if(WineAbilities.sun(e.getEntity())>=6 && e.getEffectInstance().is(MobEffects.MOVEMENT_SLOWDOWN))e.setResult(MobEffectEvent.Applicable.Result.DO_NOT_APPLY);}
    private static void freeze(LivingIncomingDamageEvent e){if(WineAbilities.sun(e.getEntity())>=6 && e.getSource().is(DamageTypes.FREEZE))e.setCanceled(true);}
    private static void selfDamage(LivingIncomingDamageEvent e){
        // Internal pulses neither grant fresh immunity nor remove an enemy's existing immunity window.
        if(e.getSource().is(SELF))e.setInvulnerabilityTicks(e.getEntity().invulnerableTime);
    }
}
