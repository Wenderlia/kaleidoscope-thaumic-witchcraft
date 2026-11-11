package dev.senluo.witchcraft.mixin.time;

import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.item.crafting.AbstractCookingRecipe;
import net.minecraft.world.item.crafting.RecipeType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(AbstractFurnaceBlockEntity.class)
public interface FurnaceAccess {
    @Accessor("litTime") int senluo$litTime();
    @Accessor("litTime") void senluo$litTime(int value);
    @Accessor("cookingProgress") int senluo$cookingProgress();
    @Accessor("cookingProgress") void senluo$cookingProgress(int value);
    @Accessor("recipeType") RecipeType<? extends AbstractCookingRecipe> senluo$recipeType();
}
