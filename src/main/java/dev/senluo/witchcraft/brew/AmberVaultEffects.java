package dev.senluo.witchcraft.brew;

import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

/** Momentary presentation only; no inventory access, persistent emitter or world light writes. */
final class AmberVaultEffects {
    private static final DustParticleOptions HONEY=new DustParticleOptions(new Vector3f(1,.65F,.12F),.65F);
    private AmberVaultEffects(){}
    static void forming(ServerLevel level,Vec3 center,float progress){
        double radius=.65-.35*progress;
        for(int i=0;i<5;i++){
            double a=i*Math.PI*2/5+progress*.8;
            level.sendParticles(HONEY,center.x+Math.cos(a)*radius,center.y-.3+progress*.3,center.z+Math.sin(a)*radius,1,0,0,0,0);
        }
        if(progress<=.17F)level.playSound(null,center.x,center.y,center.z,SoundEvents.AMETHYST_BLOCK_RESONATE,SoundSource.PLAYERS,.25F,1.25F);
    }
    static void sealed(ServerLevel level,Vec3 center){
        level.sendParticles(HONEY,center.x,center.y,center.z,16,.25,.32,.25,0);
        level.sendParticles(ParticleTypes.END_ROD,center.x,center.y,center.z,4,.15,.22,.15,.015);
        level.playSound(null,center.x,center.y,center.z,SoundEvents.AMETHYST_BLOCK_PLACE,SoundSource.PLAYERS,.8F,.9F);
        level.playSound(null,center.x,center.y,center.z,SoundEvents.AMETHYST_BLOCK_CHIME,SoundSource.PLAYERS,.5F,1.1F);
    }
    static void dissolved(ServerLevel level,Vec3 center){
        level.sendParticles(HONEY,center.x,center.y,center.z,12,.3,.3,.3,0);
        level.sendParticles(ParticleTypes.END_ROD,center.x,center.y,center.z,3,.2,.2,.2,.02);
    }
}
