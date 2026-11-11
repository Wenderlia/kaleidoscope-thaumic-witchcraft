package dev.senluo.witchcraft.client;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import dev.senluo.witchcraft.KaleidoscopeWitchcraft;
import dev.senluo.witchcraft.ritual.visual.GeasGlyph;
import java.io.IOException;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterShadersEvent;

/** The native-reference contract is geometry + sampler + blend, not geometry alone. */
@EventBusSubscriber(modid=KaleidoscopeWitchcraft.MOD_ID,value=Dist.CLIENT,bus=EventBusSubscriber.Bus.MOD)
public final class GeasRenderTypes extends RenderType {
    private static ShaderInstance sigil,shortTrail,longTrail,lightBand,ringHalo,ringMist;
    private static final TransparencyStateShard ADDITIVE=new TransparencyStateShard("geas_additive",()->{
        RenderSystem.enableBlend();
        RenderSystem.blendFuncSeparate(GlStateManager.SourceFactor.SRC_ALPHA,GlStateManager.DestFactor.ONE,
                GlStateManager.SourceFactor.ZERO,GlStateManager.DestFactor.ONE);
    },()->{RenderSystem.disableBlend();RenderSystem.defaultBlendFunc();});
    private static final RenderType[] SHADE=new RenderType[GeasGlyph.values().length];
    private static final RenderType[] LIGHT=new RenderType[GeasGlyph.values().length];
    public static final RenderType ORBIT_SHORT=make("orbit_short","glow",true,new ShaderStateShard(()->shortTrail),ADDITIVE,false);
    public static final RenderType ORBIT_LONG=make("orbit_long","glow",true,new ShaderStateShard(()->longTrail),ADDITIVE,false);
    public static final RenderType ORBIT_HEAD=make("orbit_head","glow",true,RENDERTYPE_EYES_SHADER,ADDITIVE,false);
    public static final RenderType RING_BODY=make("ring_body","glow",true,new ShaderStateShard(()->lightBand),ADDITIVE,false);
    public static final RenderType RING_HALO=make("ring_halo","glow",true,new ShaderStateShard(()->ringHalo),ADDITIVE,false);
    public static final RenderType RING_MIST=make("ring_mist","glow",true,new ShaderStateShard(()->ringMist),ADDITIVE,false);
    public static final RenderType ACCEPT_RAY=make("accept_ray","ribbon",true,RENDERTYPE_EYES_SHADER,ADDITIVE,false);
    static {
        for(GeasGlyph glyph:GeasGlyph.values()) {
            SHADE[glyph.ordinal()]=make(glyph.crest()+"_shade",glyph.crest(),false,new ShaderStateShard(()->sigil),TRANSLUCENT_TRANSPARENCY,true);
            LIGHT[glyph.ordinal()]=make(glyph.crest()+"_light",glyph.crest(),false,new ShaderStateShard(()->sigil),ADDITIVE,false);
        }
    }
    @SubscribeEvent public static void register(RegisterShadersEvent event)throws IOException {
        event.registerShader(new ShaderInstance(event.getResourceProvider(),id("geas_sigil"),DefaultVertexFormat.NEW_ENTITY),s->sigil=s);
        event.registerShader(new ShaderInstance(event.getResourceProvider(),id("geas_trail_short"),DefaultVertexFormat.NEW_ENTITY),s->shortTrail=s);
        event.registerShader(new ShaderInstance(event.getResourceProvider(),id("geas_trail_long"),DefaultVertexFormat.NEW_ENTITY),s->longTrail=s);
        event.registerShader(new ShaderInstance(event.getResourceProvider(),id("geas_light_band"),DefaultVertexFormat.NEW_ENTITY),s->lightBand=s);
        event.registerShader(new ShaderInstance(event.getResourceProvider(),id("geas_ring_halo"),DefaultVertexFormat.NEW_ENTITY),s->ringHalo=s);
        event.registerShader(new ShaderInstance(event.getResourceProvider(),id("geas_ring_mist"),DefaultVertexFormat.NEW_ENTITY),s->ringMist=s);
    }
    public static RenderType shade(GeasGlyph glyph){return SHADE[glyph.ordinal()];}
    public static RenderType light(GeasGlyph glyph){return LIGHT[glyph.ordinal()];}
    public static RenderType[] materials() {
        RenderType[] all=new RenderType[SHADE.length+LIGHT.length+7];
        System.arraycopy(SHADE,0,all,0,SHADE.length);System.arraycopy(LIGHT,0,all,SHADE.length,LIGHT.length);
        all[all.length-7]=ACCEPT_RAY;
        all[all.length-6]=RING_BODY;all[all.length-5]=RING_HALO;all[all.length-4]=RING_MIST;
        all[all.length-3]=ORBIT_LONG;all[all.length-2]=ORBIT_SHORT;all[all.length-1]=ORBIT_HEAD;return all;
    }
    private static RenderType make(String name,String texture,boolean blur,ShaderStateShard shader,TransparencyStateShard blend,boolean sort) {
        return create("geas_"+name,DefaultVertexFormat.NEW_ENTITY,VertexFormat.Mode.QUADS,1536,false,sort,
                CompositeState.builder().setShaderState(shader)
                .setTextureState(new TextureStateShard(id("textures/effect/restraint/"+texture+".png"),blur,false))
                .setTransparencyState(blend).setCullState(NO_CULL).setDepthTestState(LEQUAL_DEPTH_TEST)
                .setWriteMaskState(COLOR_WRITE).createCompositeState(false));
    }
    private static ResourceLocation id(String name){return ResourceLocation.fromNamespaceAndPath(KaleidoscopeWitchcraft.MOD_ID,name);}
    private GeasRenderTypes(){super("geas_factory",DefaultVertexFormat.NEW_ENTITY,VertexFormat.Mode.QUADS,1536,false,false,()->{},()->{});}
}
