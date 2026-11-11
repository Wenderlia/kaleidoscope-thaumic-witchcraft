package dev.senluo.witchcraft.mixin.brew;

import com.llamalad7.mixinextras.injector.wrapoperation.*;
import com.llamalad7.mixinextras.sugar.Local;
import dev.senluo.witchcraft.brew.FrostGather;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Block;
import net.neoforged.neoforge.common.CommonHooks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Actual post-event XP commit: retains cancellation, enchantments and final XP quantity. */
@Mixin(CommonHooks.class)
public abstract class FrostDropExperienceMixin {
    @WrapOperation(method="handleBlockDrops",at=@At(value="INVOKE",target="Lnet/minecraft/world/level/block/Block;popExperience(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/core/BlockPos;I)V"))
    private static void witchcraft$ownedXp(Block block,ServerLevel level,BlockPos pos,int amount,Operation<Void> original,@Local(argsOnly=true) Entity breaker){
        if(!FrostGather.experience(level,pos,amount,breaker))original.call(block,level,pos,amount);
    }
}
