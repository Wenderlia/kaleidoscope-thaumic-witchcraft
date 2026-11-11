package dev.senluo.witchcraft.mixin.ritual;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.senluo.witchcraft.ritual.ChantProxy;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.common.CommonHooks;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(CommonHooks.class)
public abstract class ChantChatMixin {
    // Substitute BEFORE event dispatch: canceled-event listeners must never see a real-owner first pass.
    // The original connection still owns filtering, spam control, signature and the one broadcast.
    @WrapMethod(method = "onServerChatSubmittedEvent", remap = false)
    private static Component witchcraft$actor(ServerPlayer player, String text, Component decorated,
            Operation<Component> original) {
        if (player == null) return original.call(null, text, decorated);
        if (!player.server.isSameThread())
            return player.server.submit(() -> original.call(ChantProxy.actor(player), text, decorated)).join();
        return original.call(ChantProxy.actor(player), text, decorated);
    }
}
