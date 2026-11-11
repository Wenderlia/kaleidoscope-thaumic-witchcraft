package dev.senluo.witchcraft.mixin.time;

import net.minecraft.world.level.block.entity.BrewingStandBlockEntity;
import net.minecraft.world.item.Item;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(BrewingStandBlockEntity.class)
public interface BrewingAccess {
    @Accessor("brewTime") int senluo$brewTime();
    @Accessor("brewTime") void senluo$brewTime(int value);
    @Accessor("fuel") int senluo$fuel();
    @Accessor("ingredient") Item senluo$ingredient();
}
