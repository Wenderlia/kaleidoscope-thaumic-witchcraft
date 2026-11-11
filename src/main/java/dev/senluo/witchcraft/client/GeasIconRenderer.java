package dev.senluo.witchcraft.client;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.senluo.witchcraft.ritual.visual.GeasIconLayers;
import dev.senluo.witchcraft.ritual.visual.GeasGlyph;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;

/** GUI-only native sprite. World seals keep their separate texture and compositing path. */
public final class GeasIconRenderer {
    private static final ResourceLocation[] TEXTURES=java.util.Arrays.stream(GeasGlyph.values())
            .map(glyph->ResourceLocation.fromNamespaceAndPath("kaleidoscope_witchcraft",glyph.icon()))
            .toArray(ResourceLocation[]::new);
    public static ResourceLocation texture(GeasGlyph glyph) { return TEXTURES[glyph.ordinal()]; }
    public static void draw(GuiGraphics graphics,ResourceLocation texture,int x,int y,int size,double ticks) {
        graphics.flush();
        float[] prior=RenderSystem.getShaderColor().clone();
        RenderSystem.enableBlend();RenderSystem.defaultBlendFunc();
        try {
            GeasIconLayers.emit(ticks,size,(dx,dy,r,g,b,alpha)->{
                graphics.pose().pushPose();
                try {
                    graphics.pose().translate(x+dx,y+dy,0);
                    graphics.setColor(r,g,b,alpha);
                    graphics.blit(texture,0,0,size,size,0,0,16,16,16,16);
                    graphics.flush();
                } finally { graphics.pose().popPose(); }
            });
        } finally {
            graphics.setColor(prior[0],prior[1],prior[2],prior[3]);
            RenderSystem.defaultBlendFunc();RenderSystem.disableBlend();
        }
    }
    private GeasIconRenderer() {}
}
