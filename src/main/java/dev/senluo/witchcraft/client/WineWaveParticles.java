package dev.senluo.witchcraft.client;

import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.senluo.witchcraft.KaleidoscopeWitchcraft;
import dev.senluo.witchcraft.brew.WineParticles;
import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.*;
import net.minecraft.core.particles.ParticleGroup;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterParticleProvidersEvent;
import org.joml.Quaternionf;
import java.util.Optional;

/** Native atlas particle: one burst packet, bounded lifetime, no world scan or custom shader. */
@EventBusSubscriber(modid=KaleidoscopeWitchcraft.MOD_ID,bus=EventBusSubscriber.Bus.MOD,value=Dist.CLIENT)
public final class WineWaveParticles {
    private static final Optional<ParticleGroup> GROUP=Optional.of(new ParticleGroup(64));
    @SubscribeEvent public static void register(RegisterParticleProvidersEvent e){
        e.registerSpriteSet(WineParticles.DUSK_WAVE.get(),s->(type,l,x,y,z,dx,dy,dz)->new Wave(l,x,y,z,dx,s,false));
        e.registerSpriteSet(WineParticles.SOLAR_WAVE.get(),s->(type,l,x,y,z,dx,dy,dz)->new Wave(l,x,y,z,dx,s,true));
    }
    private static final class Wave extends TextureSheetParticle {
        private final SpriteSet sprites;
        private final float opacity;
        private final Quaternionf up=new Quaternionf().rotationX(-(float)Math.PI/2),down=new Quaternionf().rotationX((float)Math.PI/2);
        Wave(ClientLevel level,double x,double y,double z,double radius,SpriteSet sprites,boolean solar){
            super(level,x,y,z);this.sprites=sprites;
            quadSize=(float)(Double.isFinite(radius)?Math.clamp(radius,.25,10):1);
            lifetime=solar?20:12;opacity=solar?.4F:.65F;hasPhysics=false;gravity=0;friction=1;
            setColor(1,solar?.87F:.58F,solar?.45F:.16F);setSpriteFromAge(sprites);
        }
        @Override public void tick(){super.tick();setSpriteFromAge(sprites);}
        @Override public void render(VertexConsumer v,Camera camera,float partial){
            float t=Math.clamp((age+partial)/lifetime,0,1);alpha=opacity*(1-t);
            // Exactly one face towards the eye; no coincident opposite quads.
            renderRotatedQuad(v,camera,camera.getPosition().y>=y?up:down,partial);
        }
        @Override public float getQuadSize(float partial){float t=Math.clamp((age+partial)/lifetime,0,1);return quadSize*(.12F+.88F*(1-(1-t)*(1-t)));}
        @Override public Optional<ParticleGroup> getParticleGroup(){return GROUP;}
        @Override protected int getLightColor(float partial){return 0xF000F0;}
        @Override public ParticleRenderType getRenderType(){return ParticleRenderType.PARTICLE_SHEET_TRANSLUCENT;}
    }
}
