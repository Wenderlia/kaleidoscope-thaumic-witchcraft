package dev.senluo.witchcraft.mixin.biolith;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.senluo.witchcraft.compat.thaumcraft.MineLevelFactory;
import net.minecraft.server.level.ServerLevel;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

/** Biolith 3.0.10 already ignores duplicate dimension types; omit only that expected carrier warning. */
@Pseudo
@Mixin(targets = "com.terraformersmc.biolith.impl.biome.BiomeCoordinator", remap = false)
public abstract class BiolithMineLogMixin {
    @WrapOperation(method = "handleWorldStarting(Lnet/minecraft/server/level/ServerLevel;)V",
            at = @At(value = "INVOKE", target =
                    "Lorg/slf4j/Logger;warn(Ljava/lang/String;Ljava/lang/Object;Ljava/lang/Object;)V"), require = 0)
    private static void witchcraft$carrierWarning(Logger logger, String message, Object duplicate, Object originalWorld,
                                                  Operation<Void> original, ServerLevel level) {
        // Keep registration, error reporting, and every other world's warnings on Biolith's original path.
        boolean expected = "More than one Overworld dimension world created; cowardly ignoring '{}' in favor of '{}'".equals(message)
                || "More than one Nether dimension world created; cowardly ignoring '{}' in favor of '{}'".equals(message)
                || "More than one End dimension world created; cowardly ignoring '{}' in favor of '{}'".equals(message);
        if (expected && level.getServer() instanceof MineLevelFactory factory
                && factory.witchcraft$isCreatingMine(level.dimension())) return;
        original.call(logger, message, duplicate, originalWorld);
    }
}
