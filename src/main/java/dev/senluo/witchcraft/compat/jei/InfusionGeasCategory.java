package dev.senluo.witchcraft.compat.jei;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.senluo.witchcraft.ritual.*;
import dev.senluo.witchcraft.client.GeasIconRenderer;
import dev.tc4port.thaumcraft.api.recipe.InfusionCraftingRecipe;
import dev.tc4port.thaumcraft.api.aspect.AspectId;
import dev.tc4port.thaumcraft.api.aspect.AspectApi;
import dev.tc4port.thaumcraft.api.aspect.AspectDefinition;
import dev.tc4port.thaumcraft.api.client.ThaumcraftClientRenderApi;
import java.util.*;
import mezz.jei.api.gui.builder.*;
import mezz.jei.api.gui.ingredient.IRecipeSlotsView;
import mezz.jei.api.helpers.IGuiHelper;
import mezz.jei.api.recipe.*;
import mezz.jei.api.recipe.category.AbstractRecipeCategory;
import mezz.jei.api.registration.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import static dev.senluo.witchcraft.compat.jei.GeasRecipeLayout.*;

/** Display of a player-side outcome, never a fake returned central item or an executable ritual. */
final class InfusionGeasCategory extends AbstractRecipeCategory<InfusionGeasCategory.Display> {
    record Display(ResourceLocation id,InfusionCraftingRecipe recipe,ItemStack central) {}
    private final GeasRites.Rite rite;
    private static RecipeType<Display> type(GeasRites.Rite rite) {
        return RecipeType.create(rite.id().getNamespace(), rite.id().getPath(), Display.class);
    }
    private static final ResourceLocation BOOK=ResourceLocation.fromNamespaceAndPath("thaumcraft","textures/gui/gui_researchbook_overlay.png");
    private static Component title(GeasRites.Rite rite) { return Component.translatable("jei.kaleidoscope_witchcraft."+rite.id().getPath()); }
    InfusionGeasCategory(IGuiHelper helper, GeasRites.Rite rite) {
        super(type(rite),title(rite),helper.drawableBuilder(GeasIconRenderer.texture(rite.glyph()),0,0,16,16).setTextureSize(16,16).build(),WIDTH,HEIGHT);
        this.rite = rite;
    }
    static void registerRecipes(IRecipeRegistration registration) {
        var mc=Minecraft.getInstance();
        if(mc.level==null)return;
        for (var rite : GeasRites.ALL) mc.level.getRecipeManager().byKey(rite.id()).ifPresent(holder -> {
            if(!(holder.value() instanceof InfusionCraftingRecipe recipe))return;
            ItemStack sample=GeasRecipePreview.sample();
            registration.addRecipes(type(rite),List.of(new Display(holder.id(),recipe,sample)));
        });
    }
    static void registerCatalysts(IRecipeCatalystRegistration registration) {
        BuiltInRegistries.ITEM.getOptional(ResourceLocation.fromNamespaceAndPath("thaumcraft","runic_matrix"))
                .ifPresent(item -> GeasRites.ALL.forEach(rite -> registration.addRecipeCatalysts(type(rite),item)));
    }
    @Override public ResourceLocation getRegistryName(Display display) { return display.id(); }
    @Override public void setRecipe(IRecipeLayoutBuilder builder,Display display,IFocusGroup focus) {
        builder.addInputSlot(CENTRE_X-8,CENTRE_Y-8).addItemStack(display.central().copy());
        // Index the player-side outcome as an output without drawing a second icon over the animated crest.
        builder.addInvisibleIngredients(RecipeIngredientRole.OUTPUT).addItemStack(GeasRecipePreview.outcome(rite));
        var parts=display.recipe().pedestalComponents(display.central());
        for(int i=0;i<parts.size();i++)builder.addInputSlot(componentX(i,parts.size()),componentY(i,parts.size())).addIngredients(parts.get(i));
    }
    private static List<Map.Entry<AspectId,Integer>> aspects(Display d) {
        return d.recipe().aspects(d.central()).entrySet().stream().sorted(Map.Entry.comparingByKey()).toList();
    }
    @Override public void draw(Display display,IRecipeSlotsView slots,GuiGraphics g,double mouseX,double mouseY) {
        g.flush();RenderSystem.enableBlend();RenderSystem.defaultBlendFunc();
        g.blit(BOOK,8,14,90,66,200,77,60,44,256,256);
        g.flush();RenderSystem.disableBlend();
        var mc=Minecraft.getInstance();
        double ticks=mc.level==null?0:mc.level.getGameTime()+mc.getTimer().getGameTimeDeltaPartialTick(false);
        GeasIconRenderer.draw(g,GeasIconRenderer.texture(rite.glyph()),CREST_X,CREST_Y,CREST_W,ticks);
        var aspects=aspects(display);var font=Minecraft.getInstance().font;
        for(int i=0;i<aspects.size();i++) {
            var entry=aspects.get(i);int x=aspectX(i,aspects.size());
            ThaumcraftClientRenderApi.drawAspect(g,entry.getKey(),x,ASPECT_Y,1);
            g.pose().pushPose();g.pose().translate(x+16,ASPECT_Y+12,10);g.pose().scale(.75f,.75f,1);
            String amount=Integer.toString(entry.getValue());g.drawString(font,amount,-font.width(amount),0,0xFFFFFF,true);
            g.pose().popPose();
        }
    }
    @Override public void getTooltip(ITooltipBuilder tooltip,Display display,IRecipeSlotsView slots,double x,double y) {
        if(x>=CREST_X&&x<CREST_X+CREST_W&&y>=CREST_Y&&y<CREST_Y+CREST_H)tooltip.add(title(rite));
        var aspects=aspects(display);
        for(int i=0;i<aspects.size();i++)if(x>=aspectX(i,aspects.size())&&x<aspectX(i,aspects.size())+16&&y>=ASPECT_Y&&y<ASPECT_Y+16)
            {
            var aspect = aspects.get(i);
            String name = AspectApi.registry().get(aspect.getKey()).map(AspectDefinition::name).orElse(aspect.getKey().serialized());
            tooltip.add(Component.literal(name + " ×" + aspect.getValue()));
        }
    }
}
