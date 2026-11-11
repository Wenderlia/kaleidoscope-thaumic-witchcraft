package dev.senluo.witchcraft.client;

import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.senluo.witchcraft.KaleidoscopeWitchcraft;
import dev.senluo.witchcraft.brew.StrideParticles;
import java.util.Optional;
import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.client.particle.SpriteSet;
import net.minecraft.client.particle.TextureSheetParticle;
import net.minecraft.core.particles.ParticleGroup;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterParticleProvidersEvent;

@EventBusSubscriber(modid=KaleidoscopeWitchcraft.MOD_ID,bus=EventBusSubscriber.Bus.MOD,value=Dist.CLIENT)
public final class StrideRuneParticles {
    private static final Optional<ParticleGroup> GROUP = Optional.of(new ParticleGroup(192));
    @SubscribeEvent
    public static void register(RegisterParticleProvidersEvent event) {
        event.registerSpriteSet(StrideParticles.RUNE.get(),sprites->
                (type,level,x,y,z,dx,dy,dz)->new Rune(level,x,y,z,dx,dy,dz,sprites));
    }
    private static final class Rune extends TextureSheetParticle {
        private Rune(ClientLevel level,double x,double y,double z,double dx,double dy,double dz,SpriteSet sprites) {
            super(level,x,y,z);
            xd=dx;yd=dy;zd=dz;
            hasPhysics=false;gravity=0;friction=.84F;
            lifetime=10+random.nextInt(5);
            quadSize=.12F+random.nextFloat()*.025F;
            setColor(1F,.84F,.38F);
            alpha=0;
            // One crisp glyph per particle: do not animate through letters or spin them into noise.
            pickSprite(sprites);
            roll=oRoll=(random.nextFloat()-.5F)*.16F;
        }
        @Override public void render(VertexConsumer vertices, Camera camera, float partialTick) {
            float elapsed=age+partialTick;
            alpha=ParticleAppearance.runeAlpha(elapsed,lifetime);
            setColor(1,ParticleAppearance.runeGreen(elapsed,lifetime),ParticleAppearance.runeBlue(elapsed,lifetime));
            super.render(vertices,camera,partialTick);
        }
        @Override public float getQuadSize(float partialTick) {return quadSize*ParticleAppearance.runeScale(age+partialTick,lifetime);}
        @Override public Optional<ParticleGroup> getParticleGroup() {return GROUP;}
        @Override protected int getLightColor(float partialTick) {return 0xF000F0;}
        @Override public ParticleRenderType getRenderType() {return ParticleRenderType.PARTICLE_SHEET_TRANSLUCENT;}
    }
}
