package dev.senluo.witchcraft.mixin.brew;

import net.minecraft.world.food.FoodData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(FoodData.class)
public interface FoodRecoveryAccess {
    @Accessor("tickTimer") int witchcraft$recoveryTimer();
    @Accessor("tickTimer") void witchcraft$recoveryTimer(int ticks);
}
