package dev.senluo.witchcraft.gametest;

import dev.senluo.witchcraft.time.*;
import dev.senluo.witchcraft.ritual.TreeBond;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import org.mangorage.tiab.common.api.ICommonTimeInABottleAPI;
import org.mangorage.tiab.common.api.impl.ITiabItem;
import org.mangorage.tiab.common.api.impl.ITimeAcceleratorEntity;

/** Tests use TIAB's real item route and accelerator, rather than a simulated multiplier. */
final class BottleDevelopmentCases {
    private static ItemStack bottle() {
        var api=ICommonTimeInABottleAPI.COMMON_API.get();
        ItemStack stack=new ItemStack(api.getRegistration().getTiabItem().asItem());
        stack.set(api.getRegistration().getStoredTime(),api.createStoredTimeComponent(200_000,300_000));
        return stack;
    }

    private static int stored(ItemStack stack) { return ((ITiabItem)stack.getItem()).getStoredComponent(stack).stored(); }
    private static ITimeAcceleratorEntity accelerator(GameTestHelper h,LivingEntity entity) {
        return ICommonTimeInABottleAPI.COMMON_API.get().getEntities(h.getLevel(),new AABB(entity.getBlockPos())).stream().findFirst().orElse(null);
    }

    static void timers(GameTestHelper h) {
        LivingEntity entity=DevelopmentGameTests.plant(h,4,2,false);entity.tissue.setCoreQuantity(1000*Tissue.TU);
        Tissue.Side first=new Tissue.Side(1),second=new Tissue.Side(2);
        entity.tissue.sides.add(first);entity.tissue.sides.add(second);
        Tissue.Side nested=new Tissue.Side(1);second.tissue.sides.add(nested);
        var player=DevelopmentGameTests.player(h,entity);ItemStack bottle=bottle();
        DevelopmentGameTests.use(h,entity,player,bottle);
        ITimeAcceleratorEntity accelerator=accelerator(h,entity);
        h.assertTrue(accelerator!=null && stored(bottle)<200_000,"Normal right-click must start TIAB's actual acceleration and spend bottle time");
        DevelopmentGameTests.use(h,entity,player,bottle);
        int rate=accelerator.getTimeRate();
        int before=stored(bottle);
        for(int i=0;i<10;i++) accelerator.asEntity().tick();
        TemporalWorld.get(h.getLevel()).development.flush();
        h.assertTrue(!accelerator.asEntity().isRemoved() && first.tissue.growth.ticks==10*rate && second.tissue.growth.ticks==10*rate,
                "The real accelerator must remain active and advance the plant's direct development clocks");
        h.assertTrue(entity.tissue.stored()==1000*Tissue.TU && first.tissue.growth.invested==0 && second.tissue.growth.invested==0
                        && nested.tissue.growth.ticks==0 && stored(bottle)==before,
                "Accelerated callbacks must not pay fees, mint time, grant output, awaken attached subtrees or charge twice");
        first.tissue.growth.ticks=5999;accelerator.asEntity().tick();
        TemporalWorld.get(h.getLevel()).development.flush();
        h.assertTrue(first.tissue.growth.remainingTicks()==0 && !first.mature(),"Finishing growth time cannot bypass missing cultivation fees");
        accelerator.asEntity().discard();h.succeed();
    }

    static void roots(GameTestHelper h) {
        LivingEntity source=DevelopmentGameTests.plant(h,4,1,false),target=DevelopmentGameTests.plant(h,10,1,false);
        for(int x=4;x<=10;x++) h.getLevel().setBlock(h.absolutePos(new BlockPos(x,1,4)),Blocks.DIRT.defaultBlockState(),3);
        var player=DevelopmentGameTests.player(h,source);
        source.tissue.bond=target.tissue.bond=TreeBond.of(player.getUUID(), "");
        ItemStack bottle=bottle();DevelopmentGameTests.use(h,source,player,bottle);
        ITimeAcceleratorEntity accelerator=accelerator(h,source);
        h.assertTrue(accelerator!=null,"Overlapping same-species plants must accept acceleration even without side-root timers");
        accelerator.setTimeRate(256);accelerator.setRemainingTime(100);
        for(int i=0;i<7;i++) accelerator.asEntity().tick();
        TemporalWorld.get(h.getLevel()).development.flush();
        h.assertTrue(TemporalWorld.get(h.getLevel()).colony(source).members.size()==1,"Root growth must still respect age and per-tick search limits");
        h.runAfterDelay(24,()-> {
            h.assertTrue(TemporalWorld.get(h.getLevel()).colony(source).members.contains(target),
                    "Actual TIAB updates must complete a legal root connection faster than its unaccelerated age gates");
            for(BlockPos pos:TemporalWorld.get(h.getLevel()).colony(source).roots)
                h.assertTrue(pos.getY()==source.getBlockPos().getY()-2
                        && h.getLevel().getBlockState(pos).getValue(RootBlock.EMBEDDED),
                        "Even accelerated natural roots must remain embedded below the farmland");
            h.assertTrue(source.tissue.stored()+target.tissue.stored()==0,"Faster root expansion cannot supply free TU");
            accelerator.asEntity().discard();h.succeed();
        });
    }

    static void boundaries(GameTestHelper h) {
        LivingEntity entity=DevelopmentGameTests.plant(h,4,0,false);
        var player=DevelopmentGameTests.player(h,entity);ItemStack bottle=bottle();
        DevelopmentGameTests.use(h,entity,player,bottle);
        h.assertTrue(stored(bottle)==200_000 && accelerator(h,entity)==null,"An isolated plant with no timer must not take bottle time");
        entity.tissue.rootStage=1;entity.tissue.sides.add(new Tissue.Side(1));entity.tissue.bond=TreeBond.of(UUID.randomUUID(), "");entity.changed();
        DevelopmentGameTests.use(h,entity,player,bottle);
        var acceleration=accelerator(h,entity);
        h.assertTrue(stored(bottle)<200_000 && acceleration!=null,"Nonowners may feed development time without gaining management rights");
        if(acceleration!=null) acceleration.asEntity().discard();
        h.succeed();
    }
}
