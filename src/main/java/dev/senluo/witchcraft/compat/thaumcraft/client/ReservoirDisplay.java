package dev.senluo.witchcraft.compat.thaumcraft.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.senluo.witchcraft.reservoir.ReservoirData;
import dev.tc4port.thaumcraft.api.aspect.*;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import java.util.*;

/** All TC display types stay behind the optional client boundary. */
public final class ReservoirDisplay {
    private static final ResourceLocation JAR=ResourceLocation.fromNamespaceAndPath("kaleidoscope_witchcraft","textures/reservoir/jar_glass.png");
    private static final ResourceLocation WOOD=ResourceLocation.fromNamespaceAndPath("thaumcraft","textures/block/planks_greatwood.png");
    static final ResourceLocation LIQUID=ResourceLocation.fromNamespaceAndPath("thaumcraft","block/animatedglow");
    private static final ResourceLocation SILVERWOOD=ResourceLocation.fromNamespaceAndPath("thaumcraft","textures/block/silverwoodside.png");
    public static ResourceLocation vesselTexture(){return JAR;}
    public static ResourceLocation woodTexture(boolean silver){return silver?SILVERWOOD:WOOD;}
    public static void liquid(GuiGraphics g,String aspect,int x,int y,int width,int height){
        if(width<=0 || height<=0)return;
        var sprite=Minecraft.getInstance().getTextureAtlas(InventoryMenu.BLOCK_ATLAS).apply(LIQUID);
        int color=color(aspect);
        // The atlas owns animation and resource reload; do not cache a frame or tint.
        g.blit(x,y,0,width,height,sprite,(color>>16&255)/255F,(color>>8&255)/255F,(color&255)/255F,1);
    }
    public static void glass(GuiGraphics g,int x,int y,int width,int height){
        // Same cleaned jar pane as the model (no cork or bottom interior marks).
        // 1.21.1's plain texture blit does not enable blending, while the colored
        // sprite blit above disables it on return. Explicitly own this one draw.
        g.flush();RenderSystem.enableBlend();RenderSystem.defaultBlendFunc();
        try{g.blit(JAR,x+2,y+9,width,height,3,4,10,12,16,16);}
        finally{RenderSystem.disableBlend();}
    }
    private static AspectId id(String value){
        if(value==null || value.isEmpty())return null;
        try{return AspectId.parse(value);}catch(IllegalArgumentException ex){return null;}
    }
    public static void icon(GuiGraphics g,String value,int x,int y){
        var aspect=id(value);
        if(aspect!=null)dev.tc4port.thaumcraft.api.client.ThaumcraftClientRenderApi.drawAspect(g,aspect,x,y,1);
    }
    public static void contents(ItemStack bag,PoseStack poses,MultiBufferSource buffers,int light){
        int count=ReservoirData.cells(bag),capacity=ReservoirData.capacity(bag);
        var data=ReservoirData.read(bag);
        List<ReservoirContentsRenderer.Chamber> cells=new ArrayList<>(count);
        for(int i=0;i<count;i++){
            var a=id(ReservoirData.aspect(data,i));var label=id(ReservoirData.label(data,i));
            cells.add(new ReservoirContentsRenderer.Chamber(a,ReservoirData.amount(data,i),capacity,label==null?a:label));
        }
        ReservoirContentsRenderer.render(new ReservoirContentsRenderer.Snapshot(cells,dev.senluo.witchcraft.reservoir.ReservoirProfile.of(bag)==dev.senluo.witchcraft.reservoir.ReservoirProfile.SILVERWOOD),poses,buffers,light);
    }
    public static int color(String value){var id=id(value);return id==null?0xFFFFFF:AspectApi.registry().get(id).map(AspectDefinition::color).orElse(0xFFFFFF);}
    public static net.minecraft.network.chat.Component name(String value){
        var aspect=id(value);
        return aspect==null?net.minecraft.network.chat.Component.literal(value):AspectApi.registry().get(aspect)
                .map(AspectDefinition::description).orElse(net.minecraft.network.chat.Component.literal(value));
    }
    public static ResourceLocation texture(String value){var id=id(value);return id==null?null:AspectApi.registry().get(id).map(AspectDefinition::texture).orElse(null);}
    private ReservoirDisplay(){}
}
