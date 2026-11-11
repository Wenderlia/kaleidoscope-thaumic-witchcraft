package dev.senluo.witchcraft.mixin.accessory.client;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.senluo.witchcraft.client.PropagationParticles;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(LevelRenderer.class)
public abstract class PotionParticleSourceMixin {
    @Shadow private ClientLevel level;

    @WrapMethod(method = "levelEvent")
    private void witchcraft$potion(int id, BlockPos position, int data, Operation<Void> original) {
        if (id != 2002 && id != 2007) original.call(id, position, data);
        else PropagationParticles.emit(level, Vec3.atBottomCenterOf(position), () -> original.call(id, position, data));
    }
}
