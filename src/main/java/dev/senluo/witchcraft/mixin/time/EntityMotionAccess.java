package dev.senluo.witchcraft.mixin.time;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;
@Mixin(Entity.class)
public interface EntityMotionAccess {
    @Invoker("collide") Vec3 senluo$collide(Vec3 movement);
}
