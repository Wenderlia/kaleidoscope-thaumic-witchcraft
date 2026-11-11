package dev.senluo.witchcraft.mixin.brew;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.senluo.witchcraft.brew.CombatPoise;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemCooldowns;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(Player.class)
public abstract class ShieldPoiseMixin {
    @WrapOperation(method="disableShield",at=@At(value="INVOKE",
            target="Lnet/minecraft/world/item/ItemCooldowns;addCooldown(Lnet/minecraft/world/item/Item;I)V"))
    private void witchcraft$recover(ItemCooldowns cooldowns,Item item,int ticks,Operation<Void> original) {
        int adjusted=(Object)this instanceof ServerPlayer player?CombatPoise.shieldCooldown(player,ticks):ticks;
        original.call(cooldowns,item,adjusted);
    }
}
