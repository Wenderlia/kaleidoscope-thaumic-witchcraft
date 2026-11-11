package dev.senluo.witchcraft.brew;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

public final class ExtraTime {
    private ExtraTime() {}
    public static BodyTime existing(Player player) {
        if (dev.senluo.witchcraft.ritual.OwnershipRitual.frozen(player)) return null;
        return player.hasData(BrewContent.BODY_TIME) ? player.getData(BrewContent.BODY_TIME) : null;
    }
    public static void prepare(PlayerTickEvent.Pre event) {
        if(event.getEntity() instanceof ServerPlayer player)AutomaticFood.tryEat(player,existing(player),false);
    }
    public static void tick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (dev.senluo.witchcraft.ritual.OwnershipRitual.frozen(player)) return;
        BodyTime time = existing(player);
        boolean striding=TimeStride.update(player,time);
        boolean resisting=TemporalMobility.update(player,time);
        if (time == null) return;
        time.tick(striding || resisting?2:1);
        InstantRecovery.recover(player,time);
        if(AutomaticFood.tryEat(player,time,true))InstantRecovery.recover(player,time);
        AutomaticAttack.tick(player,time);
        if(!time.active()) {TimeStride.clear(player);TemporalMobility.clear(player);}
        if (!time.active() || player.tickCount % 20 == 0 || !player.hasEffect(BrewContent.YEARS_ABUNDANCE)) sync(player, time);
    }
    public static void sync(ServerPlayer player, BodyTime time) {
        if (!time.active()) { player.removeEffect(BrewContent.YEARS_ABUNDANCE); return; }
        // Display is derived from the ledger; clearing this effect cannot delete stored time.
        player.forceAddEffect(new MobEffectInstance(BrewContent.YEARS_ABUNDANCE, time.displayTicks(),
                Math.min(255, time.grade() - 1), false, false, true), null);
    }
}
