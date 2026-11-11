package dev.senluo.witchcraft.gametest;

import com.mojang.authlib.GameProfile;
import dev.senluo.witchcraft.time.*;
import dev.senluo.witchcraft.ritual.TreeBond;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@PrefixGameTestTemplate(false)
public final class DevelopmentGameTests {
    static LivingEntity plant(GameTestHelper h, int x, int stage, boolean amber) {
        BlockPos pos=h.absolutePos(new BlockPos(x,3,4));
        h.getLevel().setBlock(pos.below(),Blocks.FARMLAND.defaultBlockState(),3);
        h.getLevel().setBlock(pos,(amber?TemporalContent.AMBER:TemporalContent.RHIZOME).get().defaultBlockState(),3);
        LivingEntity entity=(LivingEntity)h.getLevel().getBlockEntity(pos);
        entity.tissue.rootStage=stage;entity.changed();TemporalWorld.get(h.getLevel()).add(entity);
        return entity;
    }

    static FakePlayer player(GameTestHelper h, LivingEntity entity) {
        FakePlayer player=new FakePlayer(h.getLevel(),new GameProfile(UUID.randomUUID(),"development-user"));
        Vec3 p=Vec3.atCenterOf(entity.getBlockPos()).add(0,0,-2);
        player.setPos(p.x,p.y,p.z);
        return player;
    }

    static void use(GameTestHelper h, LivingEntity entity, FakePlayer player, ItemStack stack) {
        player.setItemInHand(InteractionHand.MAIN_HAND,stack);
        player.gameMode.useItemOn(player,h.getLevel(),stack,InteractionHand.MAIN_HAND,
                new BlockHitResult(Vec3.atCenterOf(entity.getBlockPos()),Direction.UP,entity.getBlockPos(),false));
    }

    @GameTest(templateNamespace="kaleidoscope_witchcraft",template="empty",timeoutTicks=80)
    public static void nativeDevelopmentAdvancesExactlyOncePerTick(GameTestHelper h) {
        LivingEntity entity=plant(h,4,1,false);
        Tissue.Side side=new Tissue.Side(1);side.tissue.growth.active=true;entity.tissue.sides.add(side);
        h.runAfterDelay(3,()-> {
            int before=side.tissue.growth.ticks;
            long worldTime=h.getLevel().getGameTime();
            h.runAfterDelay(40,()-> {
                h.assertTrue(side.tissue.growth.ticks-before==h.getLevel().getGameTime()-worldTime,
                        "The native ticker and world scheduler must not count development twice");
                h.assertTrue(entity.tissue.stored()==0 && side.tissue.growth.invested==0 && !side.mature(),
                        "Elapsed development time cannot create storage or pay the missing fee");
                h.succeed();
            });
        });
    }

    @GameTest(templateNamespace="kaleidoscope_witchcraft",template="empty",timeoutTicks=60)
    public static void goldCompletesExistingBatchAndRejectsUnpaidOrForeignUse(GameTestHelper h) {
        LivingEntity entity=plant(h,4,0,false);
        GiantRoots.restore(entity.tissue);
        entity.tissue.setCoreQuantity(480*Tissue.TU-1);
        FakePlayer player=player(h,entity);ItemStack gold=new ItemStack(Items.GOLD_INGOT,3);
        use(h,entity,player,gold);
        h.assertTrue(gold.getCount()==3 && entity.tissue.naturalTicks==0 && entity.tissue.coreQuantity()==480*Tissue.TU-1,
                "An unaffordable batch cannot partially consume gold, TU or progress");
        use(h,entity,player,new ItemStack(Items.BONE_MEAL));
        h.assertTrue(GrowthCatalysis.quote(entity).milliTu()==360*Tissue.TU,
                "Free bone-meal progress discounts the same six-root and plant work");
        use(h,entity,player,gold);
        h.assertTrue(gold.getCount()==2 && entity.tissue.sides.stream().allMatch(Tissue.Side::mature)
                && entity.tissue.maturePlant() && entity.tissue.stored()==120*Tissue.TU-1,
                "One ingot finishes the existing batch and debits only its remaining work");
        use(h,entity,player,gold);
        h.assertTrue(gold.getCount()==2,"A finished batch does not consume another catalyst");
        TemporalWorld.get(h.getLevel()).colony(entity).harvestBatch(entity);
        entity.tissue.bond=TreeBond.of(UUID.randomUUID(), "owner");
        entity.tissue.setCoreQuantity(1000*Tissue.TU);
        TemporalWorld.get(h.getLevel()).invalidate();
        use(h,entity,player,gold);
        h.assertTrue(gold.getCount()==2 && entity.tissue.coreQuantity()==1000*Tissue.TU
                && entity.tissue.sides.stream().noneMatch(Tissue.Side::mature),
                "Donating a catalyst does not authorize spending someone else's inventory");
        h.succeed();
    }

    @GameTest(templateNamespace="kaleidoscope_witchcraft",template="empty",timeoutTicks=60)
    public static void goldAmberCompletesOnlyCurrentAllowedStage(GameTestHelper h) {
        LivingEntity entity=plant(h,4,0,true);entity.tissue.setCoreQuantity(10_000*Tissue.TU);
        entity.host=Blocks.OBSIDIAN.defaultBlockState();
        FakePlayer player=player(h,entity);ItemStack gold=new ItemStack(Items.GOLD_INGOT,3);
        use(h,entity,player,new ItemStack(Items.BONE_MEAL));
        h.assertTrue(entity.tissue.amberStage==0 && entity.tissue.trainingTicks==0,
                "Fertilizer cannot replace mineral cultivation");
        use(h,entity,player,gold);
        h.assertTrue(gold.getCount()==2 && entity.tissue.amberStage==1 && entity.tissue.trainingTicks==0
                && entity.tissue.stored()==9400*Tissue.TU,
                "One catalyst pays and completes exactly one stage, even in a high-grade host");
        entity.host=Blocks.STONE.defaultBlockState();
        use(h,entity,player,gold);
        h.assertTrue(gold.getCount()==2 && entity.tissue.trainingTicks==0 && entity.tissue.amberStage==1,
                "Gold cannot bypass the next tier's host density");
        h.succeed();
    }

    @GameTest(templateNamespace="kaleidoscope_witchcraft",template="empty",timeoutTicks=60)
    public static void goldRejectsBlockedTreeAndLockedInventory(GameTestHelper h) {
        LivingEntity entity=plant(h,4,4,false);
        entity.tissue.naturalTicks=Tissue.ROOT_TICKS;
        entity.tissue.setCoreQuantity(10_000*Tissue.TU);
        h.getLevel().setBlock(entity.getBlockPos().below(),Blocks.DIRT.defaultBlockState(),3);
        h.getLevel().setBlock(entity.getBlockPos().above(),Blocks.STONE.defaultBlockState(),3);
        h.assertTrue(!GrowthCatalysis.complete(entity) && entity.tissue.coreQuantity()==10_000*Tissue.TU
                && entity.tissue.naturalTicks==Tissue.ROOT_TICKS,
                "Blocked tree space is checked before spending or skipping its age");
        h.getLevel().setBlock(entity.getBlockPos().below(),Blocks.FARMLAND.defaultBlockState(),3);
        GiantRoots.restore(entity.tissue);
        entity.tissue.setCoreQuantity(0);
        Tissue.Side locked=entity.tissue.sides.getFirst();
        locked.locked=true;locked.tissue.setCoreQuantity(1000*Tissue.TU);
        h.assertTrue(!GrowthCatalysis.complete(entity) && locked.tissue.coreQuantity()==1000*Tissue.TU
                && entity.tissue.sides.stream().noneMatch(Tissue.Side::mature),
                "Locked roots cannot finance a growth purchase");
        h.succeed();
    }

    @GameTest(templateNamespace="kaleidoscope_witchcraft",template="empty",timeoutTicks=60)
    public static void acceleratedRootsKeepConnectionAgeAndMotherLimits(GameTestHelper h) {
        LivingEntity source=plant(h,4,1,false);
        BlockPos connected=source.getBlockPos().below(2),cut=connected.south(3);
        for(BlockPos pos:new BlockPos[]{connected,cut}) {
            h.getLevel().setBlock(pos,TemporalContent.ROOT.get().defaultBlockState(),3);
            RootEntity root=(RootEntity)h.getLevel().getBlockEntity(pos);
            root.parent=source.getBlockPos();TemporalWorld.get(h.getLevel()).add(root);
        }
        RootEntity attached=(RootEntity)h.getLevel().getBlockEntity(connected),detached=(RootEntity)h.getLevel().getBlockEntity(cut);
        var ticker=source.getBlockState().getTicker(h.getLevel(),TemporalContent.LIVING_ENTITY.get());
        h.assertTrue(ticker!=null,"Living tissue must expose the ticker TIAB requires");
        for(int i=0;i<2401;i++) ticker.tick(h.getLevel(),source.getBlockPos(),source.getBlockState(),source);
        TemporalWorld.get(h.getLevel()).development.flush();
        h.assertTrue(attached.ticks==2400 && attached.getBlockState().getValue(RootBlock.AGE)==1,
                "Extra updates age connected roots while thickness stays capped by the mother");
        h.assertTrue(detached.ticks==0 && source.tissue.stored()==0,"Cut roots cannot receive acceleration and growth cannot create time");
        h.succeed();
    }

    @GameTest(templateNamespace="kaleidoscope_witchcraft",template="empty",timeoutTicks=60)
    public static void naturalRootsRefuseAirGapsAndStartAtBothUndergroundCollars(GameTestHelper h) {
        LivingEntity source=plant(h,4,1,false),target=plant(h,8,1,false);
        TemporalWorld world=TemporalWorld.get(h.getLevel());
        BlockPos first=source.getBlockPos().below(2),gap=first.east(2);
        for(int x=0;x<=4;x++) h.getLevel().setBlock(first.east(x),Blocks.DIRT.defaultBlockState(),3);
        h.getLevel().setBlock(gap,Blocks.AIR.defaultBlockState(),3);
        // Dirt next to the leaves must not become an alternative starting point.
        for(int x=0;x<=4;x++) h.getLevel().setBlock(source.getBlockPos().above(2).east(x),Blocks.DIRT.defaultBlockState(),3);
        for(int i=0;i<2400;i++) world.accelerateRoots(source);
        h.assertTrue(world.colony(source).roots.isEmpty() && h.getLevel().getBlockState(gap).isAir(),
                "No complete underground soil path means no first root, and acceleration cannot fill an air gap");
        h.getLevel().setBlock(gap,Blocks.DIRT.defaultBlockState(),3);
        for(int i=0;i<600;i++) world.accelerateRoots(source);
        h.assertTrue(h.getLevel().getBlockState(source.getBlockPos().below()).is(TemporalContent.ROOTED_FARMLAND.get()) && world.colony(source).roots.size()==1,
                "Restoring the soil permits exactly one first root inside the source farmland");
        for(int i=0;i<580;i++) world.accelerateRoots(source);
        h.assertTrue(world.colony(source).roots.size()==1,"Acceleration still has to accumulate the full segment maturation time");
        for(int i=0;i<20;i++) world.accelerateRoots(source);
        h.assertTrue(h.getLevel().getBlockState(first).is(TemporalContent.ROOT.get()),
                "A grown segment extends along the soil path after thirty simulated seconds");
        h.runAfterDelay(1,()-> {
            for(int i=0;i<3600;i++) world.accelerateRoots(source);
            h.assertTrue(world.colony(source).members.contains(target),"The far end must connect through the target's underground collar");
            for(BlockPos pos:world.colony(source).roots)
                h.assertTrue(pos.getY()<source.getBlockPos().getY()
                        && (h.getLevel().getBlockState(pos).is(TemporalContent.ROOTED_FARMLAND.get()) || h.getLevel().getBlockState(pos).getValue(RootBlock.EMBEDDED)),
                        "Natural segments cannot appear beside or above the crop");
            h.succeed();
        });
    }

    @GameTest(templateNamespace="kaleidoscope_witchcraft",template="empty",timeoutTicks=60)
    public static void naturalRootRouteJoinsAmberFromBelow(GameTestHelper h) {
        LivingEntity source=plant(h,4,1,false),target=plant(h,8,1,true);
        BlockPos first=source.getBlockPos().below(2);
        for(int x=0;x<=4;x++) h.getLevel().setBlock(first.east(x),Blocks.DIRT.defaultBlockState(),3);
        h.getLevel().setBlock(target.getBlockPos().below(),Blocks.DIRT.defaultBlockState(),3);
        TemporalWorld world=TemporalWorld.get(h.getLevel());
        for(int i=0;i<4200;i++) world.accelerateRoots(source);
        h.assertTrue(world.colony(source).members.contains(target),"Soil-grown roots must also connect to amber from below");
        h.assertTrue(h.getLevel().getBlockState(target.getBlockPos().below()).is(TemporalContent.ROOT.get()),
                "The amber anchor is immediately below its host block");
        h.assertTrue(source.tissue.stored()+target.tissue.stored()==0,"Root routing cannot grant stored time");
        h.succeed();
    }

    @GameTest(templateNamespace="kaleidoscope_witchcraft",template="empty",timeoutTicks=60)
    public static void bottleActuallyAcceleratesDevelopmentWithoutFundingIt(GameTestHelper h) {
        if(ModList.get().isLoaded("tiab")) BottleDevelopmentCases.timers(h);else h.succeed();
    }

    @GameTest(templateNamespace="kaleidoscope_witchcraft",template="empty",timeoutTicks=80)
    public static void bottleAcceleratesRootExpansionWithinOriginalConstraints(GameTestHelper h) {
        if(ModList.get().isLoaded("tiab")) BottleDevelopmentCases.roots(h);else h.succeed();
    }

    @GameTest(templateNamespace="kaleidoscope_witchcraft",template="empty",timeoutTicks=60)
    public static void bottleRejectsIdleOrForeignAccelerationTargets(GameTestHelper h) {
        if(ModList.get().isLoaded("tiab")) BottleDevelopmentCases.boundaries(h);else h.succeed();
    }
}
