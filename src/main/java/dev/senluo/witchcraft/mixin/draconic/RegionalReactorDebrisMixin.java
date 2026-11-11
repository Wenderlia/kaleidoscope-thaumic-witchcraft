package dev.senluo.witchcraft.mixin.draconic;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import dev.senluo.witchcraft.region.RegionProtection;
import net.minecraft.world.entity.item.FallingBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

/** Carry explosion provenance to delayed lava landings, even across save/reload. */
@Pseudo
@Mixin(targets = "com.brandon3055.draconicevolution.blocks.reactor.tileentity.TileReactorCore", remap = false)
public abstract class RegionalReactorDebrisMixin {
    @ModifyExpressionValue(method = "minimalBoom", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/item/FallingBlockEntity;fall(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;)Lnet/minecraft/world/entity/item/FallingBlockEntity;", remap = true))
    private FallingBlockEntity witchcraft$provenance(FallingBlockEntity entity) {
        entity.getPersistentData().putBoolean(RegionProtection.EXPLOSION_DEBRIS, true);
        return entity;
    }
}
