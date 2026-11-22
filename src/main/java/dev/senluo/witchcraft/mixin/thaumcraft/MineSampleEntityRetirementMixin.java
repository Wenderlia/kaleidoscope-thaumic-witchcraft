package dev.senluo.witchcraft.mixin.thaumcraft;

import dev.senluo.witchcraft.compat.thaumcraft.MineWorkAreas;
import net.minecraft.world.level.entity.EntityPersistentStorage;
import net.minecraft.world.level.entity.PersistentEntitySectionManager;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(PersistentEntitySectionManager.class)
public abstract class MineSampleEntityRetirementMixin {
    @Shadow @Final private EntityPersistentStorage<?> permanentStorage;
    @Inject(method = "processChunkUnload", at = @At("RETURN"))
    private void witchcraft$retired(long chunk, CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValue() && permanentStorage instanceof MineWorkAreas.EntityWorld world)
            MineWorkAreas.entityRetired(world.witchcraft$entityWorld(), chunk);
    }
}
