package dev.senluo.witchcraft.gametest;

import com.mojang.authlib.GameProfile;
import dev.senluo.witchcraft.brew.*;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.entity.living.LivingKnockBackEvent;

final class CombatPoiseCases {
    static void run(GameTestHelper h) {
        FakePlayer p=new FakePlayer(h.getLevel(),new GameProfile(UUID.randomUUID(),"combat-poise"));
        BodyTime time=p.getData(BrewContent.BODY_TIME);time.drink(100);
        p.invulnerableTime=17;p.setOnGround(false);
        knock(p);
        h.assertTrue(near(p.getDeltaMovement().x,-.4) && time.baseTicks()==6000,"Grade one retains native knockback without charging");
        shield(p);
        for(int i=0;i<50;i++)p.getCooldowns().tick();
        h.assertTrue(p.getCooldowns().isOnCooldown(Items.SHIELD) && time.baseTicks()==6000,"Grade one keeps full shield disable time");
        p.getCooldowns().removeCooldown(Items.SHIELD);

        time.clear();time.drink(120);knock(p);
        h.assertTrue(near(p.getDeltaMovement().x,-.2) && near(time.baseTicks(),7200-20*(11.0/12)),"Grade two halves knockback strength for one base second");
        shield(p);
        h.assertTrue(near(time.baseTicks(),7200-70*(11.0/12)) && !p.isUsingItem(),"Shield recovers 50 of 100 ticks, charging exactly the 50 skipped ticks and retaining interrupted use");
        for(int i=0;i<49;i++)p.getCooldowns().tick();
        h.assertTrue(p.getCooldowns().isOnCooldown(Items.SHIELD),"Shield remains disabled before shortened duration ends");
        p.getCooldowns().tick();
        h.assertTrue(!p.getCooldowns().isOnCooldown(Items.SHIELD),"Shield is usable exactly at the shortened duration");
        h.assertTrue(p.invulnerableTime==17,"Both interventions preserve post-hit invulnerability");

        double before=time.baseTicks();
        p.getAttribute(Attributes.KNOCKBACK_RESISTANCE).setBaseValue(1);knock(p);
        h.assertTrue(p.getDeltaMovement().equals(Vec3.ZERO) && time.baseTicks()==before,"Full resistance has no effective knockback and costs nothing");
        p.getAttribute(Attributes.KNOCKBACK_RESISTANCE).setBaseValue(0);
        p.knockback(0,1,0);
        h.assertTrue(time.baseTicks()==before,"Zero strength costs nothing");
        Consumer<LivingKnockBackEvent> cancel=e->{if(e.getEntity()==p)e.setCanceled(true);};
        NeoForge.EVENT_BUS.addListener(cancel);
        try {knock(p);h.assertTrue(time.baseTicks()==before && p.getDeltaMovement().equals(Vec3.ZERO),"Canceled knockback costs nothing");}
        finally {NeoForge.EVENT_BUS.unregister(cancel);}

        time.drainActual(time.baseTicks()-30);shield(p);
        h.assertTrue(time.baseTicks()==30,"Shield does not partially charge when its own budget is insufficient");
        for(int i=0;i<50;i++)p.getCooldowns().tick();
        h.assertTrue(p.getCooldowns().isOnCooldown(Items.SHIELD),"Insufficient budget keeps native shield cooldown");
        knock(p);
        h.assertTrue(near(time.baseTicks(),30-20*(11.0/12)) && near(p.getDeltaMovement().x,-.2),"Knockback independently fits a smaller budget");
        knock(p);
        h.assertTrue(near(time.baseTicks(),30-20*(11.0/12)) && near(p.getDeltaMovement().x,-.4),"Insufficient knockback budget preserves full native strength");
        p.getCooldowns().addCooldown(Items.ENDER_PEARL,100);
        h.assertTrue(near(time.baseTicks(),30-20*(11.0/12)),"Unrelated item cooldowns are not shortened or charged");

        time.clear();time.drink(200);knock(p);
        h.assertTrue(near(p.getDeltaMovement().x,-.1) && near(time.baseTicks(),12000-20*(10.0/12)),"Grade three retains a quarter of native knockback for one base second");
        p.getCooldowns().removeCooldown(Items.SHIELD);shield(p);
        h.assertTrue(p.getCooldowns().isOnCooldown(Items.SHIELD) && near(time.baseTicks(),12000-95*(10.0/12)),"Grade three retains 25 of 100 shield cooldown ticks and pays for the 75 skipped ticks");
        for(int i=0;i<24;i++)p.getCooldowns().tick();
        h.assertTrue(p.getCooldowns().isOnCooldown(Items.SHIELD),"Grade three retains a full 25 ticks");
        p.getCooldowns().tick();
        h.assertTrue(!p.getCooldowns().isOnCooldown(Items.SHIELD),"Grade three shield recovers at 25 ticks");
        h.assertTrue(p.invulnerableTime==17,"Grade three also preserves damage invulnerability");
        time.drainActual(time.baseTicks()-62);shield(p);
        h.assertTrue(p.getCooldowns().isOnCooldown(Items.SHIELD) && time.baseTicks()==62,"Grade three needs the discounted 75 skipped ticks; insufficient funds keep native disable time");
        var last=time.serializeNBT(p.registryAccess());last.putDouble("BaseTicks",20*(10.0/12));
        time.deserializeNBT(p.registryAccess(),last);knock(p);
        h.assertTrue(near(p.getDeltaMovement().x,-.1) && !time.active(),"Last payment retains the grade three strength even after the ledger clears");

        time.clear();time.drink(20000);knock(p);
        p.getCooldowns().removeCooldown(Items.SHIELD);shield(p);
        for(int i=0;i<50;i++)p.getCooldowns().tick();
        h.assertTrue(time.free() && time.baseTicks()==0 && near(p.getDeltaMovement().x,-.0125)
                && !p.getCooldowns().isOnCooldown(Items.SHIELD),"Grade thirteen retains both benefits without cost");
        h.succeed();
    }
    private static void knock(FakePlayer p) {p.setDeltaMovement(Vec3.ZERO);p.knockback(.4,1,0);}
    private static void shield(FakePlayer p) {
        p.setItemInHand(InteractionHand.OFF_HAND,new ItemStack(Items.SHIELD));
        p.startUsingItem(InteractionHand.OFF_HAND);p.disableShield();
    }
    private static boolean near(double actual,double expected) {return Math.abs(actual-expected)<.00001;}
}
