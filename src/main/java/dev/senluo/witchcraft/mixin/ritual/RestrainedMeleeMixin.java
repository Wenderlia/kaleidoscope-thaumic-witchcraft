package dev.senluo.witchcraft.mixin.ritual;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.senluo.witchcraft.ritual.WorldRestraint;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;

/** Give native melee callbacks an origin; ordinary melee has no destructive proof and no bonus. */
@Mixin(Player.class)
public abstract class RestrainedMeleeMixin {
    @WrapMethod(method = "attack")
    private void witchcraft$action(Entity target, Operation<Void> original) {
        if (!((Object)this instanceof ServerPlayer player)) { original.call(target); return; }
        WorldRestraint.during(player, WorldRestraint.NONE, () -> { original.call(target); return null; });
    }
}
