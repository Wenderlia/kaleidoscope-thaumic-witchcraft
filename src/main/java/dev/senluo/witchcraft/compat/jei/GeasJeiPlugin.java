package dev.senluo.witchcraft.compat.jei;

import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.registration.*;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.fml.ModList;

/** Optional presentation only; TC-dependent code is reached only when the host is present. */
@JeiPlugin
public final class GeasJeiPlugin implements IModPlugin {
    public ResourceLocation getPluginUid() { return ResourceLocation.fromNamespaceAndPath("kaleidoscope_witchcraft","geas"); }
    @Override public void registerCategories(IRecipeCategoryRegistration r) {
        if (ModList.get().isLoaded("thaumcraft")) for (var rite : dev.senluo.witchcraft.ritual.GeasRites.ALL)
            r.addRecipeCategories(new InfusionGeasCategory(r.getJeiHelpers().getGuiHelper(), rite));
    }
    @Override public void registerRecipes(IRecipeRegistration r) {
        if (ModList.get().isLoaded("thaumcraft")) InfusionGeasCategory.registerRecipes(r);
    }
    @Override public void registerRecipeCatalysts(IRecipeCatalystRegistration r) {
        if (ModList.get().isLoaded("thaumcraft")) InfusionGeasCategory.registerCatalysts(r);
    }
}
