package dev.senluo.witchcraft.gametest;

import com.mojang.authlib.GameProfile;
import dev.senluo.witchcraft.time.*;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.living.LivingEntityUseItemEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import org.mangorage.tiab.common.api.ICommonTimeInABottleAPI;
import org.mangorage.tiab.common.api.impl.ITiabItem;

/** Optional API references stay outside the base test class so the no-TIAB launch is meaningful. */
final class BottleGameTestCases {
    private static final int STORED=200_000;
    private static final int TOTAL=300_000;

    private static final class RecordingPlayer extends Player {
        Component lastFeedback;
        RecordingPlayer(ServerLevel level) { super(level,BlockPos.ZERO,0,new GameProfile(UUID.randomUUID(),"test-bottle-player")); }
        @Override public boolean isSpectator() { return false; }
        @Override public boolean isCreative() { return false; }
        @Override public boolean isLocalPlayer() { return true; }
        @Override public void displayClientMessage(Component message, boolean actionBar) { if(actionBar) lastFeedback=message.copy(); }
    }

    private static LivingEntity plant(GameTestHelper h, int x, int stage, long stock) {
        BlockPos pos=h.absolutePos(new BlockPos(x,3,4));
        h.getLevel().setBlock(pos.below(),Blocks.FARMLAND.defaultBlockState(),3);
        h.getLevel().setBlock(pos,TemporalContent.RHIZOME.get().defaultBlockState(),3);
        LivingEntity e=(LivingEntity)h.getLevel().getBlockEntity(pos);
        e.tissue.rootStage=stage;e.tissue.setCoreQuantity(stock);e.changed();
        TemporalWorld.get(h.getLevel()).add(e);
        return e;
    }

    private static Player player(GameTestHelper h, LivingEntity target, int stored) {
        Player player=new RecordingPlayer(h.getLevel());
        var api=ICommonTimeInABottleAPI.COMMON_API.get();
        ItemStack bottle=new ItemStack(api.getRegistration().getTiabItem().asItem());
        bottle.set(api.getRegistration().getStoredTime(),api.createStoredTimeComponent(stored,TOTAL));
        player.setItemInHand(InteractionHand.MAIN_HAND,bottle);
        player.setShiftKeyDown(true);
        Vec3 position=Vec3.atCenterOf(target.getBlockPos()).add(0,0,-2);
        player.setPos(position.x,position.y,position.z);
        Vec3 delta=Vec3.atBottomCenterOf(target.getBlockPos()).add(0,0.15,0).subtract(player.getEyePosition());
        player.setYRot((float)Math.toDegrees(Math.atan2(-delta.x,delta.z)));
        player.setXRot((float)-Math.toDegrees(Math.atan2(delta.y,Math.sqrt(delta.x*delta.x+delta.z*delta.z))));
        return player;
    }

    private static PlayerInteractEvent.RightClickBlock click(Player player, LivingEntity target) {
        BlockPos pos=target.getBlockPos();
        return NeoForge.EVENT_BUS.post(new PlayerInteractEvent.RightClickBlock(player,InteractionHand.MAIN_HAND,pos,
                new BlockHitResult(Vec3.atCenterOf(pos),Direction.NORTH,pos,false)));
    }
    private static int stored(Player player) { return ((ITiabItem)player.getMainHandItem().getItem()).getStoredComponent(player.getMainHandItem()).stored(); }
    private static void useTick(Player player) { NeoForge.EVENT_BUS.post(new LivingEntityUseItemEvent.Tick(player,player.getMainHandItem(),player.getUseItemRemainingTicks())); }

    static void click(GameTestHelper h) {
        LivingEntity target=plant(h,4,0,0);
        Player player=player(h,target,STORED);
        var event=click(player,target);
        h.assertTrue(event.isCanceled() && event.getCancellationResult().consumesAction(),"The bottle action must be consumed before ordinary TIAB acceleration or harvesting");
        h.assertTrue(player.isUsingItem() && player.getUseItemRemainingTicks()==72000,"Normal hold/release state must be started on the player");
        h.assertTrue(target.tissue.coreQuantity()==Tissue.TU,"A single click must inject exactly one TU");
        int cost=(int)BottleFeedingRates.costTicks(0,1,20);
        h.assertTrue(stored(player)==STORED-cost,"The real TIAB component must pay the quoted duration");
        h.assertTrue(((ITiabItem)player.getMainHandItem().getItem()).getStoredComponent(player.getMainHandItem()).total()==TOTAL,"Spending must preserve lifetime bottle time");
        click(player,target);
        h.assertTrue(target.tissue.coreQuantity()==Tissue.TU,"Repeated interaction packets while using must not grant extra click doses");
        player.releaseUsingItem();useTick(player);
        h.assertTrue(target.tissue.coreQuantity()==Tissue.TU && !player.isUsingItem(),"Releasing must end the session without another dose");
        h.succeed();
    }

    static void hold(GameTestHelper h) {
        // A rooted organism has room for the ramp and two full seconds at the cap without an automatic advancement cost.
        LivingEntity target=plant(h,4,1,0);
        Tissue.Side side = new Tissue.Side(1);
        side.tissue.growth.invested = Tissue.SIDE_FEE[1]; side.tissue.growth.ticks = Tissue.SIDE_TIME[1];
        target.tissue.sides.add(side);
        int holdBudget=2_000_000;
        Player player=player(h,target,holdBudget);click(player,target);
        long[] samples=new long[3];
        for(int i=1;i<=240;i++) {
            int held=i;
            h.runAfterDelay(i,()-> {
                useTick(player);
                long q=target.tissue.stored();
                useTick(player);
                h.assertTrue(target.tissue.stored()==q,"Duplicate same-tick callbacks must not accelerate feeding");
                if(held==20) h.assertTrue(q==Tissue.TU,"The first second must preserve single-click size");
                if(held==200) samples[0]=q;
                if(held==220) samples[1]=q;
                if(held==240) samples[2]=q;
                if(held==240) {
                    h.assertTrue(samples[0]>20*Tissue.TU,"Holding must accelerate beyond the initial one TU");
                    h.assertTrue(samples[1]-samples[0]>512*Tissue.TU && samples[2]-samples[1]>samples[1]-samples[0],"Holding beyond ten seconds must keep increasing above the former 512 cap");
                    h.assertTrue(side.stored()>0,"Actual bottle income must fill side-root storage as well as the core");
                    h.assertTrue(holdBudget-stored(player)==BottleFeedingRates.costTicks(0,(int)(q/Tissue.TU),20),"Hold batching must charge the same integral as clicking");
                    player.releaseUsingItem();
                    h.runAfterDelay(5,()-> {useTick(player);h.assertTrue(target.tissue.stored()==q,"No transfers after mouse release");h.succeed();});
                }
            });
        }
    }

    static void boundaries(GameTestHelper h) {
        LivingEntity target=plant(h,4,0,0);
        int quote=(int)BottleFeedingRates.costTicks(0,1,20);
        Player poor=player(h,target,quote-1);click(poor,target);
        h.assertTrue(stored(poor)==quote-1 && target.tissue.coreQuantity()==0 && !poor.isUsingItem(),"An insufficient bottle must lose no time");
        target.tissue.bond=null;target.tissue.setCoreQuantity(target.tissue.capacity()-999);
        Player full=player(h,target,STORED);click(full,target);
        h.assertTrue(stored(full)==STORED && !full.isUsingItem(),"Less than a whole TU of room must reject the transfer without charging");
        target.tissue.setCoreQuantity(0);target.tissue.bond=dev.senluo.witchcraft.ritual.TreeBond.of(UUID.randomUUID(), "");
        Player foreign=player(h,target,STORED);click(foreign,target);
        h.assertTrue(stored(foreign)==STORED-quote && target.tissue.coreQuantity()==Tissue.TU,"Nonowners may inject time without changing the owner");
        target.tissue.bond=null;target.tissue.setCoreQuantity(0);
        Player lookingAway=player(h,target,STORED);click(lookingAway,target);
        lookingAway.setYRot(180);useTick(lookingAway);
        h.assertTrue(!lookingAway.isUsingItem() && target.tissue.coreQuantity()==Tissue.TU,"Looking away must stop holding immediately");
        h.succeed();
    }

    static void ownStock(GameTestHelper h) {
        LivingEntity a=plant(h,4,1,123456),b=plant(h,8,1,3000*Tissue.TU);
        for(int x=4;x<=8;x++) {
            BlockPos p=h.absolutePos(new BlockPos(x,1,4));
            h.getLevel().setBlock(p,TemporalContent.ROOT.get().defaultBlockState(),3);
            TemporalWorld.get(h.getLevel()).add(h.getLevel().getBlockEntity(p));
        }
        h.assertTrue(TemporalWorld.get(h.getLevel()).colony(a).members.size()==2,"The fixture must form a genuinely connected colony");
        Player player=player(h,a,STORED);
        a.tissue.productionMode=3;
        long before=a.tissue.coreQuantity();click(player,a);player.releaseUsingItem();
        h.assertTrue(a.tissue.coreQuantity()==before+Tissue.TU && b.tissue.coreQuantity()==3000*Tissue.TU,"Feeding must fill the clicked organism, not whichever colony member sorts first");
        h.assertTrue(STORED-stored(player)==BottleFeedingRates.costTicks(before,1,20),"The quote must use this organism's stock, not the colony sum");
        h.succeed();
    }

    static void equalEntityIds(GameTestHelper h) {
        LivingEntity a=plant(h,4,0,0),b=plant(h,8,0,123456);
        Player first=player(h,a,STORED),second=player(h,b,STORED);
        // In integrated play, the local and server player have the same network entity id.
        second.setId(first.getId());
        h.assertTrue(first!=second && first.equals(second),"The test must exercise real Minecraft entity equality across different objects");
        click(first,a);click(second,b);
        first.releaseUsingItem();
        Object[] feedback=((TranslatableContents)((RecordingPlayer)first).lastFeedback.getContents()).getArgs();
        h.assertTrue(((Component)feedback[0]).getString().equals("1") && ((Component)feedback[1]).getString().equals("8.95")
                && ((Component)feedback[2]).getString().equals("1"),
                "Feedback must retain this player's transferred amount, bottle cost and own server stock");
        h.assertTrue(((RecordingPlayer)first).lastFeedback.getStyle().getColor().getValue()==TemporalText.BODY
                && ((Component)feedback[0]).getStyle().getColor().getValue()==TemporalText.AMOUNT
                && ((Component)feedback[1]).getStyle().getColor().getValue()==TemporalText.TIME
                && ((Component)feedback[2]).getStyle().getColor().getValue()==TemporalText.AMOUNT
                && ((Component)feedback[3]).getStyle().getColor().getValue()==TemporalText.CAPACITY,
                "Bottle feedback shares the inventory, time and capacity colors with the rhizome HUD");
        for(int i=1;i<=80;i++) h.runAfterDelay(i,()->useTick(second));
        h.runAfterDelay(81,()-> {
            h.assertTrue(second.isUsingItem() && b.tissue.coreQuantity()>123456+5*Tissue.TU,"Stopping an equal-id player must not erase the other object's hold session");
            h.assertTrue(a.tissue.coreQuantity()==Tissue.TU,"The released side must remain stopped");
            h.assertTrue(STORED-stored(second)==BottleFeedingRates.costTicks(123456,(int)((b.tissue.coreQuantity()-123456)/Tissue.TU),20),"The surviving side must continue charging only its own bottle");
            second.releaseUsingItem();
            h.succeed();
        });
    }
}
