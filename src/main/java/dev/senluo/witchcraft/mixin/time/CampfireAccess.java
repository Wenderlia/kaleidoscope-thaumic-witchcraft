package dev.senluo.witchcraft.mixin.time;
import net.minecraft.world.level.block.entity.CampfireBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
@Mixin(CampfireBlockEntity.class)
public interface CampfireAccess {
    @Accessor("cookingProgress") int[] senluo$progress();
}
