package dev.senluo.witchcraft.mixin.time;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.senluo.witchcraft.time.PlayerActionTime;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(Entity.class)
public abstract class PlayerMotionMixin {
    @WrapMethod(method = "move")
    private void witchcraft$ownedFieldMotion(MoverType type, Vec3 movement, Operation<Void> original) {
        if ((Object) this instanceof Player player)
            PlayerActionTime.move(player, type, movement, value -> original.call(type, value));
        else original.call(type, movement);
    }
}
