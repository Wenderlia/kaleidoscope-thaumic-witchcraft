package dev.senluo.witchcraft.client;

import dev.senluo.witchcraft.KaleidoscopeWitchcraft;
import dev.senluo.witchcraft.brew.WineAbilities;
import net.minecraft.client.Minecraft;
import net.minecraft.core.particles.DustParticleOptions;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import org.joml.Vector3f;

/** Local vanilla particles and a thin screen-edge warmth. No shader, lights or world writes. */
@EventBusSubscriber(modid=KaleidoscopeWitchcraft.MOD_ID,value=Dist.CLIENT)
public final class SolarWineVisuals {
    private static final DustParticleOptions LIGHT=new DustParticleOptions(new Vector3f(1,.91F,.54F),.8F);
    private SolarWineVisuals(){}
    @SubscribeEvent public static void tick(ClientTickEvent.Post event){
        var mc=Minecraft.getInstance();if(mc.level==null || mc.player==null || mc.isPaused())return;
        if((mc.level.getGameTime()&1)!=0)return;
        // Only the local player's tracked player list; no world entity/block scan.
        int budget=32;
        for(var p:mc.level.players()){
            if(WineAbilities.sun(p)<2 || p.distanceToSqr(mc.player)>4096)continue;
            double angle=p.tickCount*.23;
            for(int i=0;i<2;i++){
                double a=angle+i*Math.PI;
                mc.level.addParticle(LIGHT,p.getX()+Math.cos(a)*.48,p.getY()+.3+(p.tickCount%20)*.065,p.getZ()+Math.sin(a)*.48,0,.025,0);
                if(--budget==0)return;
            }
        }
    }
    @SubscribeEvent public static void edge(RenderGuiEvent.Post event){
        var mc=Minecraft.getInstance();if(mc.player==null || WineAbilities.sun(mc.player)<2 || !mc.options.getCameraType().isFirstPerson() || mc.options.hideGui)return;
        var g=event.getGuiGraphics();int width=g.guiWidth(),height=g.guiHeight(),edge=Math.max(2,Math.min(width,height)/70);
        for(int i=0;i<4;i++){
            int color=((9-i*2)<<24)|0xffc966;
            g.fill(i*edge,0,(i+1)*edge,height,color);g.fill(width-(i+1)*edge,0,width-i*edge,height,color);
        }
    }
}
