package dev.senluo.witchcraft.ritual;

import dev.senluo.witchcraft.KaleidoscopeWitchcraft;
import dev.senluo.witchcraft.time.LivingEntity;
import dev.senluo.witchcraft.time.TemporalText;
import dev.senluo.witchcraft.time.TemporalWorld;
import dev.senluo.witchcraft.time.Tissue;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.BlockEvent;

@EventBusSubscriber(modid = KaleidoscopeWitchcraft.MOD_ID)
public final class TreeOwnership {
    private TreeOwnership() {}

    public static boolean mayManage(Player player, Tissue tissue) {
        return tissue.supported() && (tissue.bond == null || tissue.bond.belongsTo(player.getUUID()));
    }

    /** Grafting may pass ownership in either direction, but never overwrite another owner. */
    public static boolean canGraft(Tissue mother, Tissue incoming) {
        if (!mother.supported() || !incoming.supported()) return false;
        TreeBond bond = mother.bond != null ? mother.bond : incoming.bond;
        return bond == null || mother.acceptsBond(bond) && incoming.acceptsBond(bond);
    }

    public static boolean canBind(LivingEntity tree) {
        return tree.tissue.supported() && tree.tree() && tree.tissue.tree && tree.tissue.bond == null;
    }

    public static boolean bind(LivingEntity tree, ServerPlayer player) {
        TreeBond bond = TreeBond.of(player.getUUID(), player.getGameProfile().getName());
        if (!canBind(tree) || !tree.tissue.acceptsBond(bond)) return false;
        tree.tissue.inheritBond(bond);
        tree.changed();
        TemporalWorld.get(player.serverLevel()).invalidate();
        player.serverLevel().sendBlockUpdated(tree.getBlockPos(), tree.getBlockState(), tree.getBlockState(), 3);
        return true;
    }

    @SubscribeEvent
    public static void protectBody(BlockEvent.BreakEvent event) {
        if (event.getLevel().getBlockEntity(event.getPos()) instanceof LivingEntity body
                && !mayManage(event.getPlayer(), body.tissue)) {
            event.setCanceled(true);
            restoreBody(event.getPlayer(), body);
            if (!body.tissue.supported()) dev.senluo.witchcraft.time.LivingBlock.tell(event.getPlayer(), "data_unavailable");
            else denied(event.getPlayer());
        }
    }

    /** Restore client prediction without removing the authoritative core or creating drops. */
    public static void restoreBody(Player player, LivingEntity body) {
        if (player instanceof ServerPlayer serverPlayer) {
            serverPlayer.connection.send(new ClientboundBlockUpdatePacket(body.getBlockPos(), body.getBlockState()));
            var packet = body.getUpdatePacket();
            if (packet != null) serverPlayer.connection.send(packet);
        }
    }

    public static void denied(Player player) {
        player.displayClientMessage(TemporalText.color(Component.translatable(
                "text.kaleidoscope_witchcraft.ownership.owner_only"), TemporalText.PENDING), true);
    }
}
