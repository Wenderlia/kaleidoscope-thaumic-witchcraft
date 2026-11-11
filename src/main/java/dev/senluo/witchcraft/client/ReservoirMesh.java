package dev.senluo.witchcraft.client;

import com.mojang.blaze3d.vertex.*;
import dev.senluo.witchcraft.reservoir.ReservoirContent;
import net.minecraft.client.renderer.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.neoforged.fml.ModList;
import java.io.*;
import java.util.*;

/** Reload-owned, predecoded geometry. No file IO, texture sampling or mesh assembly in a draw. */
final class ReservoirMesh {
    private static volatile Map<String,Mesh> meshes=Map.of();
    private record Mesh(float[] vertices,byte[] caps){}
    private ReservoirMesh(){}
    static void reload(ResourceManager resources){
        var loaded=new HashMap<String,Mesh>();
        for(String name:List.of("backpack_static","backpack_wood","backpack_glass","silverwood_backpack_static","silverwood_backpack_wood","silverwood_backpack_glass","converter_static","converter_glass","converter_indicators","frost_static","frost_glass")){
            var path=ReservoirContent.id("models/reservoir/"+name+".rsv");
            try(var stream=resources.getResourceOrThrow(path).open();var in=new DataInputStream(new BufferedInputStream(stream))){
                if(in.readInt()!=0x52535631)throw new IOException("Invalid reservoir mesh version");
                int count=in.readInt();if(count<1 || count>8192)throw new IOException("Invalid triangle count");
                float[] vertices=new float[count*24];byte[] caps=new byte[count];
                for(int i=0;i<count;i++){
                    caps[i]=in.readByte();if((caps[i]&~63)!=0)throw new IOException("Invalid socket mask");
                    for(int j=0;j<24;j++){float v=in.readFloat();if(!Float.isFinite(v) || Math.abs(v)>64)throw new IOException("Invalid vertex");vertices[i*24+j]=v;}
                }
                if(in.read()!=-1)throw new IOException("Trailing mesh data");
                loaded.put(name,new Mesh(vertices,caps));
            }catch(IOException ex){com.mojang.logging.LogUtils.getLogger().error("Cannot load reservoir mesh {}",path,ex);}
        }
        meshes=Map.copyOf(loaded);
    }
    static void draw(String name,PoseStack poses,MultiBufferSource buffers,int light,int overlay,int hiddenCaps,int tint){
        draw(name,poses,buffers,light,overlay,hiddenCaps,tint,null);
    }
    static void draw(String name,PoseStack poses,MultiBufferSource buffers,int light,int overlay,int hiddenCaps,int tint,String material){
        Mesh mesh=meshes.get(name);if(mesh==null)return;
        boolean glass=name.endsWith("glass"),crystal=name.endsWith("indicators"),silver=name.startsWith("silverwood_backpack");
        ResourceLocation texture=ReservoirContent.id("textures/reservoir/"+(silver?"silverwood_backpack":name.startsWith("backpack")?"backpack":crystal?"crystal":"converter")+".png");
        if(material!=null)texture=ReservoirContent.id("textures/reservoir/"+material+".png");
        if(name.equals("backpack_glass") || silver && glass)texture=ModList.get().isLoaded("thaumcraft")
                ?dev.senluo.witchcraft.compat.thaumcraft.client.ReservoirDisplay.vesselTexture()
                :ReservoirContent.id("textures/reservoir/"+"jar_fallback"+".png");
        if(silver && name.endsWith("wood"))texture=ReservoirContent.id("textures/reservoir/silverwood_panel.png");
        else if(name.equals("backpack_wood"))texture=ModList.get().isLoaded("thaumcraft")
                ?dev.senluo.witchcraft.compat.thaumcraft.client.ReservoirDisplay.woodTexture(silver)
                :ReservoirContent.id("textures/reservoir/"+(silver?"silverwood_fallback":"wood_fallback")+".png");
        VertexConsumer out=buffers.getBuffer(glass || crystal?RenderType.entityTranslucent(texture):RenderType.entityCutoutNoCull(texture));
        var pose=poses.last();int[] order={0,1,2,2};
        for(int i=0;i<mesh.caps.length;i++){
            if((hiddenCaps & mesh.caps[i])!=0)continue;
            for(int corner:order){int at=i*24+corner*8;float[] v=mesh.vertices;
                out.addVertex(pose,v[at]/16,v[at+1]/16,v[at+2]/16).setColor(tint).setUv(v[at+3],v[at+4])
                        .setOverlay(overlay).setLight(light).setNormal(pose,v[at+5],v[at+6],v[at+7]);
            }
        }
    }
}
