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
        // Keep the atlas-owned animated pixels at native size. Crop/tile instead
        // of stretching a whole frame whenever the amount changes. At default
        // 16px, each chamber uses at most eight small quads; no scissor or cache.
        int tw=sprite.contents().width(),th=sprite.contents().height();
        int aw=Math.round(tw/(sprite.getU1()-sprite.getU0()));
        int ah=Math.round(th/(sprite.getV1()-sprite.getV0()));
        float u=sprite.getU0()*aw,v=sprite.getV0()*ah;
        g.flush();RenderSystem.enableBlend();RenderSystem.defaultBlendFunc();
        RenderSystem.setShaderColor((color>>16&255)/255F,(color>>8&255)/255F,(color&255)/255F,1);
        try{
            int sourceY=Math.floorMod(-height,th);
            for(int yy=0;yy<height;){
                int h=Math.min(th-sourceY,height-yy);
                for(int xx=0;xx<width;xx+=tw)
                    g.blit(sprite.atlasLocation(),x+xx,y+yy,u,v+sourceY,Math.min(tw,width-xx),h,aw,ah);
                yy+=h;sourceY=0;
            }
        }finally{RenderSystem.setShaderColor(1,1,1,1);RenderSystem.disableBlend();}
    }
    private static AspectId id(String value){
        if(value==null || value.isEmpty())return null;
        try{return AspectId.parse(value);}catch(IllegalArgumentException ex){return null;}
    }
    public static void icon(GuiGraphics g,String value,int x,int y){
        var aspect=id(value);
        if(aspect==null)return;
        // One offset silhouette separates pale aspect ink from the paper label.
        // The actual foreground remains the core API's current texture and tint.
        var definition=AspectApi.registry().get(aspect).orElse(null);
        if(definition!=null){
            g.flush();RenderSystem.enableBlend();RenderSystem.defaultBlendFunc();
            g.setColor(.12F,.11F,.09F,.72F);
            try{g.blit(definition.texture(),x+1,y+1,16,16,0,0,32,32,32,32);}
            finally{g.setColor(1,1,1,1);RenderSystem.disableBlend();}
        }
        dev.tc4port.thaumcraft.api.client.ThaumcraftClientRenderApi.drawAspect(g,aspect,x,y,1);
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
