package dev.senluo.witchcraft.brew;

import net.minecraft.server.level.ServerPlayer;

public final class AbilityCosts {
    public static final long DODGE=100_000, STEADY=200_000, SHIELD=300_000;
    private AbilityCosts() {}
    public static Reservation reserve(ServerPlayer player,int grade,double ticks,long nutrition) {
        BodyTime time=ExtraTime.existing(player);
        if(time==null || time.grade()<grade || !time.canSpend(ticks))return null;
        NutritionLedger ledger=player.getData(BrewContent.NUTRITION);
        NutritionLedger.Quote quote=ledger.quote(player.getFoodData(),nutrition);
        if(quote==null)return null;
        long saturation=Math.min(nutrition,Math.round((double)player.getFoodData().getSaturationLevel()*NutritionLedger.UNIT));
        BodyTime.Hold hold=time.hold(ticks);
        if(hold==null)return null;
        ledger.apply(player.getFoodData(),quote);
        return new Reservation(player,ledger,hold,saturation,nutrition-saturation);
    }
    public static final class Reservation {
        private final ServerPlayer player;
        private final NutritionLedger ledger;
        private final BodyTime.Hold hold;
        private final long saturation,hunger;
        private boolean closed;
        private Reservation(ServerPlayer player,NutritionLedger ledger,BodyTime.Hold hold,long saturation,long hunger) {
            this.player=player;this.ledger=ledger;this.hold=hold;this.saturation=saturation;this.hunger=hunger;
        }
        public void finish(boolean accepted) {
            finish(accepted,Double.POSITIVE_INFINITY);
        }
        public void finish(boolean accepted,double ticks) {
            if(closed)return;
            closed=true;
            hold.finish(accepted?ticks:0);
            if(!accepted || ticks<=0)ledger.refund(player.getFoodData(),saturation,hunger);
        }
    }
    public static boolean pay(ServerPlayer player,int grade,double baseTicks,long nutrition) {
        BodyTime time=ExtraTime.existing(player);
        if(time==null || time.grade()<grade || !time.canSpend(baseTicks))return false;
        NutritionLedger ledger=player.getData(BrewContent.NUTRITION);
        NutritionLedger.Quote quote=ledger.quote(player.getFoodData(),nutrition);
        if(quote==null)return false;
        // Server-thread transaction; no event or callback between the two commits.
        if(!time.spend(baseTicks))return false;
        ledger.apply(player.getFoodData(),quote);
        return true;
    }
}
