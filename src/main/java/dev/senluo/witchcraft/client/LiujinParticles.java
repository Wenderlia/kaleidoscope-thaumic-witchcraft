package dev.senluo.witchcraft.client;

import dev.senluo.witchcraft.KaleidoscopeWitchcraft;
import dev.senluo.witchcraft.brew.WineParticles;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.DripParticle;
import net.minecraft.client.particle.SplashParticle;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterParticleProvidersEvent;

@EventBusSubscriber(modid=KaleidoscopeWitchcraft.MOD_ID, bus=EventBusSubscriber.Bus.MOD, value=Dist.CLIENT)
public final class LiujinParticles {
    // #DFB965: the bottle atlas's warm liquid gold, without biome water tint or full-bright light.
    private static final float RED=223/255F, GREEN=185/255F, BLUE=101/255F;
    @SubscribeEvent
    public static void register(RegisterParticleProvidersEvent event) {
        event.registerSprite(WineParticles.TAP.get(), (type,level,x,y,z,dx,dy,dz) -> new Drop(level,x,y,z,true));
        event.registerSprite(WineParticles.FALL.get(), (type,level,x,y,z,dx,dy,dz) -> new Drop(level,x,y,z,false));
        event.registerSprite(WineParticles.SPLASH.get(), (type,level,x,y,z,dx,dy,dz) -> new Splash(level,x,y,z,dx,dy,dz));
    }
    private static final class Drop extends DripParticle {
        private final boolean tap;
        private Drop(ClientLevel level,double x,double y,double z,boolean tap) {
            super(level,x,y,z,Fluids.WATER);
            this.tap=tap;
            setColor(RED,GREEN,BLUE);
            // Keep Tavern's 18-tick stream and vanilla falling-drip lifetime/gravity.
            lifetime=tap?18:(int)(64/(random.nextDouble()*.8+.2));
            if(tap)gravity*=.02F;
        }
        @Override protected void preMoveUpdate() {
            super.preMoveUpdate();
            if(tap)level.addParticle(WineParticles.FALL.get(),x,y,z,xd,yd,zd);
        }
        @Override protected void postMoveUpdate() {
            if(tap) {xd*=.02;yd*=.02;zd*=.02;}
            else if(onGround) {
                remove();
                level.addParticle(WineParticles.SPLASH.get(),x,y,z,0,0,0);
                level.playLocalSound(x,y,z,SoundEvents.POINTED_DRIPSTONE_DRIP_WATER,SoundSource.BLOCKS,
                        Mth.randomBetween(random,.3F,1F),1F,false);
            }
        }
    }
    private static final class Splash extends SplashParticle {
        private Splash(ClientLevel level,double x,double y,double z,double dx,double dy,double dz) {
            super(level,x,y,z,dx,dy,dz);setColor(RED,GREEN,BLUE);
        }
    }
}
