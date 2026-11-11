package dev.senluo.witchcraft.gametest;

import dev.senluo.witchcraft.time.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.FurnaceBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@PrefixGameTestTemplate(false)
public final class TemporalGameTests {
    private static LivingEntity plant(GameTestHelper h, int x, int y, int z, boolean amber, int stage, long quantity) {
        BlockPos pos = h.absolutePos(new BlockPos(x, y, z));
        h.getLevel().setBlock(pos.below(), (amber ? Blocks.DIRT : Blocks.FARMLAND).defaultBlockState(), 3);
        h.getLevel().setBlock(pos, (amber ? TemporalContent.AMBER : TemporalContent.RHIZOME).get().defaultBlockState(), 3);
        LivingEntity e = (LivingEntity) h.getLevel().getBlockEntity(pos);
        e.tissue.rootStage = stage; e.tissue.setCoreQuantity(quantity); e.changed();
        TemporalWorld.get(h.getLevel()).add(e);
        return e;
    }
    private static RootEntity root(GameTestHelper h, BlockPos relative, LivingEntity parent) {
        BlockPos pos = h.absolutePos(relative);
        h.getLevel().setBlock(pos, TemporalContent.ROOT.get().defaultBlockState(), 3);
        RootEntity root = (RootEntity) h.getLevel().getBlockEntity(pos);
        root.parent = parent.getBlockPos(); root.ticks = 3600;
        TemporalWorld.get(h.getLevel()).add(root);
        return root;
    }
    private static void check(GameTestHelper h, boolean condition, String reason) { h.assertTrue(condition, reason); }

    @GameTest(templateNamespace="kaleidoscope_witchcraft", template = "empty", timeoutTicks = 60)
    public static void bottleCurvePreservesCalibrationAndClickSplits(GameTestHelper h) {
        long singles=0;
        for(int q=0;q<1200;q++) singles+=BottleFeedingRates.costTicks(q*Tissue.TU,1,20);
        check(h,singles==7200*20 && singles==BottleFeedingRates.costTicks(0,1200,20),"The 1200 TU reference fill must cost exactly two bottle hours, independent of batching");
        check(h,BottleFeedingRates.costTicks(0,600,20)==4500*20,"The first half must cost 75 minutes; the second half is more efficient");
        check(h,BottleFeedingRates.costTicks(600*Tissue.TU,600,20)==2700*20,"The second half must cost 45 minutes");
        for(long start:new long[]{1,123456,1199999,1200000,Tissue.MAX_VALUE}) {
            long parts=0;
            for(int i=0;i<17;i++) parts+=BottleFeedingRates.costTicks(start+i*Tissue.TU,1,20);
            check(h,parts==BottleFeedingRates.costTicks(start,17,20),"Fractional stocks and the curve boundary must not reward split clicks");
        }
        check(h,BottleFeedingRates.costTicks(Tissue.MAX_VALUE,1,20)==60,"Large stocks must retain a nonzero price floor without overflow");
        check(h,BottleFeedingRates.costTicks(0,1200,40)==7200*40,"Bottle seconds must use the upstream tick unit");
        check(h,BottleFeedingRates.holdMilliTuPerTick(20)==0,"A short click must not gain an automatic second dose");
        check(h,BottleFeedingRates.holdMilliTuPerTick(140)==3200 && BottleFeedingRates.holdMilliTuPerTick(160)==6400
                && BottleFeedingRates.holdMilliTuPerTick(180)==12800,"The hold curve must keep doubling each second after reaching the former cap");
        check(h,BottleFeedingRates.holdMilliTuPerTick(199)<25600 && BottleFeedingRates.holdMilliTuPerTick(200)==25600
                && BottleFeedingRates.holdMilliTuPerTick(418)<50_000_000
                && BottleFeedingRates.holdMilliTuPerTick(419)==50_000_000
                && BottleFeedingRates.holdMilliTuPerTick(Integer.MAX_VALUE)==50_000_000,"The old 512 rate is retained at ten seconds; the new 1M cap is reached at tick 419");
        long budget=BottleFeedingRates.costTicks(1199*Tissue.TU,12345,20);
        check(h,BottleFeedingRates.affordableTu(1199*Tissue.TU,50000,20,budget)==12345
                && BottleFeedingRates.affordableTu(1199*Tissue.TU,50000,20,budget-1)==12344,
                "Large affordability search must preserve exact integral boundaries");
        h.succeed();
    }

    @GameTest(templateNamespace="kaleidoscope_witchcraft", template = "empty", timeoutTicks = 60)
    public static void bottleClickTransfersOneAndChargesActualBottle(GameTestHelper h) {
        if(net.neoforged.fml.ModList.get().isLoaded("tiab")) BottleGameTestCases.click(h); else h.succeed();
    }

    @GameTest(templateNamespace="kaleidoscope_witchcraft", template = "empty", timeoutTicks = 260)
    public static void bottleHoldAcceleratesCapsAndStopsOnRelease(GameTestHelper h) {
        if(net.neoforged.fml.ModList.get().isLoaded("tiab")) BottleGameTestCases.hold(h); else h.succeed();
    }

    @GameTest(templateNamespace="kaleidoscope_witchcraft", template = "empty", timeoutTicks = 60)
    public static void bottleRejectsShortfallFullAndForeignTargets(GameTestHelper h) {
        if(net.neoforged.fml.ModList.get().isLoaded("tiab")) BottleGameTestCases.boundaries(h); else h.succeed();
    }

    @GameTest(templateNamespace="kaleidoscope_witchcraft", template = "empty", timeoutTicks = 60)
    public static void bottlePricesAndDepositsOnlyTheTargetStock(GameTestHelper h) {
        if(net.neoforged.fml.ModList.get().isLoaded("tiab")) BottleGameTestCases.ownStock(h); else h.succeed();
    }

    @GameTest(templateNamespace="kaleidoscope_witchcraft", template = "empty", timeoutTicks = 120)
    public static void bottleSessionsSurviveEqualEntityIdsOnSeparatePlayerObjects(GameTestHelper h) {
        if(net.neoforged.fml.ModList.get().isLoaded("tiab")) BottleGameTestCases.equalEntityIds(h); else h.succeed();
    }

    @GameTest(templateNamespace="kaleidoscope_witchcraft", template = "empty", timeoutTicks = 60)
    public static void seedlingCannotSelectOrAccumulateSupply(GameTestHelper h) {
        LivingEntity e=plant(h,4,3,4,false,0,123456);
        var player=h.makeMockPlayer(GameType.SURVIVAL);
        player.setShiftKeyDown(true);
        BlockHitResult hit=new BlockHitResult(Vec3.atCenterOf(e.getBlockPos()),Direction.UP,e.getBlockPos(),false);
        e.getBlockState().useWithoutItem(h.getLevel(),player,hit);
        check(h,e.tissue.productionMode==0,"An empty-hand crouch click must not enable seedling supply");
        Colony c=TemporalWorld.get(h.getLevel()).colony(e);
        e.tissue.productionMode=3; // Old saves must not authorize output before rooting.
        check(h,c.credit(900,e),"Seedling income must not create supply authorization");
        CompoundTag saved=e.saveWithoutMetadata(h.getLevel().registryAccess());
        e.loadWithComponents(saved,h.getLevel().registryAccess());
        check(h,e.tissue.productionMode==0,"Loading an old seedling mode must reset it to retention");
        check(h,LivingBlock.stockMessage(e).getSiblings().isEmpty(),"Seedlings must not mention unavailable side roots");
        check(h,LivingBlock.formatTime(123456).equals("123.456"),"Stock feedback must preserve fractional TU");
        e.tissue.rootStage=1;e.changed();
        e.getBlockState().useWithoutItem(h.getLevel(),player,hit);
        check(h,e.tissue.productionMode==1,"Rooting must unlock the same supply interaction");
        check(h,LivingBlock.stockMessage(e).getSiblings().isEmpty(),"A root without a differentiated side keeps its summary free of side details");
        h.succeed();
    }

    @GameTest(templateNamespace="kaleidoscope_witchcraft", template = "empty", timeoutTicks = 60)
    public static void prechargeReservesExistingTimeAndHarvestsItOnce(GameTestHelper h) {
        LivingEntity e=plant(h,4,3,4,false,1,3000*Tissue.TU);
        Tissue.Side s=new Tissue.Side(1);s.tissue.growth.invested=Tissue.SIDE_FEE[1];s.tissue.growth.ticks=Tissue.SIDE_TIME[1];e.tissue.sides.add(s);
        Colony c=TemporalWorld.get(h.getLevel()).colony(e);long total=c.quantity();
        check(h,c.chargeSide(e),"Precharge should allocate available time");
        check(h,c.quantity()==total && e.tissue.reserved()==1200*Tissue.TU,"Precharge must reserve, not create, time");
        check(h,c.spend(c.spendable()),"Only the unreserved remainder may be spent");
        Tissue child=c.harvest(e);
        check(h,child.coreQuantity()==1200*Tissue.TU && e.tissue.coreQuantity()==0,"Reserved time must travel with exactly one harvested side");
        h.succeed();
    }

    @GameTest(templateNamespace="kaleidoscope_witchcraft", template = "empty", timeoutTicks = 1420)
    public static void overlappingOrganismsGrowFreeYoungSoilRoots(GameTestHelper h) {
        LivingEntity a=plant(h,4,3,4,false,0,0);plant(h,8,3,4,false,0,0);
        for(int x=4;x<=8;x++) h.getLevel().setBlock(h.absolutePos(new BlockPos(x,1,4)),Blocks.DIRT.defaultBlockState(),3);
        h.runAfterDelay(590,()-> check(h,h.getLevel().getBlockState(a.getBlockPos().below(2)).is(Blocks.DIRT),
                "Natural budding must wait thirty seconds before creating its first segment"));
        h.runAfterDelay(700,()-> {
            BlockState state=h.getLevel().getBlockState(a.getBlockPos().below(2));
            check(h,state.is(TemporalContent.ROOT.get()),"Overlap must trigger a real root replacing soil");
            check(h,h.getLevel().getBlockState(a.getBlockPos().below()).is(Blocks.FARMLAND),"Root growth must leave the crop's farmland intact");
            check(h,state.getValue(RootBlock.AGE)==0 && state.getValue(RootBlock.EMBEDDED),"New soil root must start thin and embedded");
            check(h,a.tissue.coreQuantity()==0,"Root growth must not require time inventory");
        });
        h.runAfterDelay(1190,()-> check(h,h.getLevel().getBlockState(a.getBlockPos().below(2).east()).is(Blocks.DIRT),
                "A young first segment must wait another thirty seconds before extending"));
        h.runAfterDelay(1380,()-> {
            check(h,h.getLevel().getBlockState(a.getBlockPos().below(2).east()).is(TemporalContent.ROOT.get()),
                    "A mature first segment must extend after the thirty-second interval and bounded scheduler delay; roots="
                            +TemporalWorld.get(h.getLevel()).colony(a).roots);
            h.succeed();
        });
    }

    @GameTest(templateNamespace="kaleidoscope_witchcraft", template = "empty", timeoutTicks = 60)
    public static void unknownTickingEntityUsesGenericUpdatePrice(GameTestHelper h) {
        BlockPos pos=h.absolutePos(new BlockPos(4,3,4));h.getLevel().setBlock(pos,Blocks.HOPPER.defaultBlockState(),3);
        check(h,Math.abs(WorkSources.price(h.getLevel(),h.getLevel().getBlockEntity(pos))-1250.0/39)<1.0E-9,"Unadapted actual tickers should retain their original ratio under the common income multiplier");
        h.succeed();
    }

    @GameTest(templateNamespace="kaleidoscope_witchcraft", template = "empty", timeoutTicks = 60)
    public static void trainedCapacitySurvivesMovingAndTrainingCannotCompleteTwice(GameTestHelper h) {
        Tissue t=new Tissue();t.amberStage=1;t.training=true;t.trainingTicks=Tissue.AMBER_TIME[2]-20;
        t.train(1,20);check(h,t.amberStage==1,"Insufficiently dense host must pause training");
        t.train(2,20);long capacity=t.capacity();
        t.train(3,24000);check(h,t.amberStage==2 && t.capacity()==capacity,"Paid training completes once, without free next tier");
        Tissue moved=TissueTag.load(TissueTag.save(t));check(h,moved.capacity()==capacity,"Permanent trained capacity must survive transplant");
        h.succeed();
    }

    @GameTest(templateNamespace="kaleidoscope_witchcraft", template = "empty", timeoutTicks = 60)
    public static void harvestConservesAndDoesNotCopyMotherStage(GameTestHelper h) {
        LivingEntity e = plant(h, 4, 3, 4, false, 3, 0);
        Tissue.Side side = new Tissue.Side(3);
        side.tissue.growth.invested = Tissue.SIDE_FEE[3]; side.tissue.growth.ticks = Tissue.SIDE_TIME[3]; e.tissue.sides.add(side);
        e.tissue.setCoreQuantity(e.tissue.capacity()/2);
        long before = e.tissue.coreQuantity();
        Tissue child = TemporalWorld.get(h.getLevel()).colony(e).harvest(e);
        check(h, child != null && child.coreQuantity() == 2400*Tissue.TU, "Half-full side must carry half its own capacity");
        check(h, child.rootStage == 0 && e.tissue.rootStage == 3, "Mother and child stages must remain independent");
        check(h, e.tissue.coreQuantity() + child.coreQuantity() == before, "Harvest must conserve time");
        check(h, TemporalWorld.get(h.getLevel()).colony(e).harvest(e) == null, "A second click cannot duplicate a side");
        h.succeed();
    }

    @GameTest(templateNamespace="kaleidoscope_witchcraft", template = "empty", timeoutTicks = 60)
    public static void transplantSerializationPreservesCapacityAndLocks(GameTestHelper h) {
        Tissue t = new Tissue(); t.rootStage=2; t.amberStage=2; t.setCoreQuantity(3000*Tissue.TU);
        Tissue.Side side = new Tissue.Side(2); side.tissue.growth.invested=Tissue.SIDE_FEE[2]; side.tissue.growth.ticks=Tissue.SIDE_TIME[2];
        side.locked=true; side.tissue.setCoreQuantity(2000*Tissue.TU); t.sides.add(side);
        Tissue copy = TissueTag.load(TissueTag.save(t));
        check(h, copy.capacity() == t.capacity() && copy.stored() == t.stored() && copy.reserved() == side.stored(),
                "Transplant must keep growth, trained capacity and reserved time");
        CompoundTag bad = TissueTag.save(t); bad.putLong("Quantity", -1); bad.putInt("RootStage", 99);
        Tissue bounded = TissueTag.load(bad);
        check(h, bounded.coreQuantity() == 0 && bounded.reserved() == 2000*Tissue.TU && bounded.rootStage == 4, "Malformed core amounts must not erase legitimate independent side balances");
        h.succeed();
    }

    @GameTest(templateNamespace="kaleidoscope_witchcraft", template = "empty", timeoutTicks = 60)
    public static void rootsConnectAndCutWithoutDuplicatingTime(GameTestHelper h) {
        LivingEntity a=plant(h,4,3,4,false,1,3000*Tissue.TU), b=plant(h,8,3,4,true,0,0);
        for(int x=5;x<8;x++) root(h,new BlockPos(x,3,4),a);
        TemporalWorld world=TemporalWorld.get(h.getLevel());
        Colony joined=world.colony(a);
        check(h,joined.members.size()==2,"Only a real path should join members");
        long total=joined.quantity();
        h.getLevel().setBlock(h.absolutePos(new BlockPos(6,3,4)),Blocks.AIR.defaultBlockState(),3);
        check(h,world.colony(a).members.size()==1 && world.colony(b).members.size()==1,"Cut must split the colony immediately");
        check(h,a.tissue.coreQuantity()+b.tissue.coreQuantity()==total,"Split must conserve time");
        h.succeed();
    }

    @GameTest(templateNamespace="kaleidoscope_witchcraft", template = "empty", timeoutTicks = 100)
    public static void smallMotherCannotGrowThickRoots(GameTestHelper h) {
        LivingEntity a=plant(h,4,3,4,false,0,0);
        RootEntity r=root(h,new BlockPos(4,1,4),a);
        h.runAfterDelay(25,()-> {
            check(h,r.getBlockState().getValue(RootBlock.AGE)==0,"Old segment attached to a tiny mother must remain thin");
            h.succeed();
        });
    }

    @GameTest(templateNamespace="kaleidoscope_witchcraft", template = "empty", timeoutTicks = 100)
    public static void noOtherOrganismMeansNoNaturalRoots(GameTestHelper h) {
        LivingEntity a=plant(h,4,3,4,false,1,2000*Tissue.TU);
        h.runAfterDelay(80,()-> {
            check(h,h.getLevel().getBlockState(a.getBlockPos().below()).is(Blocks.FARMLAND),"A lone mother must keep its farmland");
            check(h,!h.getLevel().getBlockState(a.getBlockPos().below(2)).is(TemporalContent.ROOT.get()),"A lone mother must not root without an overlap partner");
            h.succeed();
        });
    }

    @GameTest(templateNamespace="kaleidoscope_witchcraft", template = "empty", timeoutTicks = 100)
    public static void fullColonyFundsExistingLegacySideBeforeSleeping(GameTestHelper h) {
        LivingEntity a=plant(h,4,3,4,false,1,6000*Tissue.TU);
        Tissue.Side existing=new Tissue.Side(1);existing.tissue.setCoreQuantity(Tissue.TU);
        a.tissue.sides.add(existing);a.changed();
        h.runAfterDelay(25,()-> {
            check(h,a.tissue.sides.size()==1 && existing.tissue.growth.invested==Tissue.SIDE_FEE[1],"Full storage must finish funding existing legacy tissue without requiring new income or creating another side");
            check(h,a.tissue.stored()==5851*Tissue.TU,"Legacy side investment must spend exactly the real development fee");
            h.succeed();
        });
    }

    @GameTest(templateNamespace="kaleidoscope_witchcraft", template = "empty", timeoutTicks = 100)
    public static void furnacePausesFuelAndProgressThenResumes(GameTestHelper h) {
        LivingEntity a=plant(h,4,3,4,false,1,0);
        BlockPos pos=h.absolutePos(new BlockPos(6,3,4));
        h.getLevel().setBlock(pos,Blocks.FURNACE.defaultBlockState(),3);
        FurnaceBlockEntity furnace=(FurnaceBlockEntity)h.getLevel().getBlockEntity(pos);
        furnace.setItem(0,new ItemStack(Items.IRON_ORE)); furnace.setItem(1,new ItemStack(Items.COAL));
        h.runAfterDelay(8,()-> {
            check(h,a.tissue.coreQuantity()>0,"A working furnace must supply time through the actual native ticker hook");
            check(h,furnace.getItem(1).getCount()==1 && furnace.saveWithoutMetadata(h.getLevel().registryAccess()).getShort("CookTime")==0,
                    "Generic pause must stop the whole update, including fuel consumption");
            a.tissue.sides.clear();
            for(int i=0;i<Tissue.SLOTS[1];i++) { Tissue.Side side=new Tissue.Side(1); side.tissue.growth.invested=Tissue.SIDE_FEE[1];side.tissue.growth.ticks=Tissue.SIDE_TIME[1];a.tissue.sides.add(side); }
            a.tissue.setCoreQuantity(a.tissue.capacity());
        });
        h.runAfterDelay(15,()-> {
            check(h,furnace.getItem(1).isEmpty(),"A truly full colony must allow the original fuel consumption again");
            check(h,furnace.saveWithoutMetadata(h.getLevel().registryAccess()).getShort("CookTime")>0,"Original work must resume when storage is full");
            h.succeed();
        });
    }

    @GameTest(templateNamespace="kaleidoscope_witchcraft", template = "empty", timeoutTicks = 60)
    public static void knownIdleAndBlockedFurnacesDoNotSupplyTime(GameTestHelper h) {
        plant(h,4,3,4,false,1,0);
        BlockPos pos=h.absolutePos(new BlockPos(6,3,4));h.getLevel().setBlock(pos,Blocks.FURNACE.defaultBlockState(),3);
        FurnaceBlockEntity furnace=(FurnaceBlockEntity)h.getLevel().getBlockEntity(pos);
        furnace.setItem(1,new ItemStack(Items.COAL));
        check(h,WorkSources.price(h.getLevel(),furnace)==0,"Empty firing must not pay");
        furnace.setItem(0,new ItemStack(Items.IRON_ORE));furnace.setItem(2,new ItemStack(Items.IRON_INGOT,64));
        check(h,WorkSources.price(h.getLevel(),furnace)==0,"Blocked output must not pay");
        h.succeed();
    }

    @GameTest(templateNamespace="kaleidoscope_witchcraft", template = "empty", timeoutTicks = 80)
    public static void nativeSamplesCreditAirAndDoNotDuplicateExtraCalls(GameTestHelper h) {
        LivingEntity a=plant(h,8,3,8,false,4,0);
        BlockPos crop=h.absolutePos(new BlockPos(10,3,8));
        h.getLevel().setBlock(crop.below(),Blocks.FARMLAND.defaultBlockState(),3);
        h.getLevel().setBlock(crop,Blocks.WHEAT.defaultBlockState(),3);
        h.getLevel().getGameRules().getRule(GameRules.RULE_RANDOMTICKING).set(3,h.getLevel().getServer());
        h.runAfterDelay(4,()-> {
            // GameTest has no nearby players. Invoke the real chunk routine explicitly;
            // randomTick callbacks alone deliberately do not carry native provenance.
            h.getLevel().tickChunk(h.getLevel().getChunkAt(crop), 64);
            long before=a.tissue.coreQuantity();
            check(h,before>=140,"A ticking section must pay for native samples, even when almost all sampled cells are air");
            for(int i=0;i<100;i++) h.getLevel().getBlockState(crop).randomTick(h.getLevel(),crop,h.getLevel().random);
            check(h,a.tissue.coreQuantity()==before,"Released or accelerated callbacks must not earn natural time");
            h.succeed();
        });
    }

    @GameTest(templateNamespace="kaleidoscope_witchcraft", template="empty", timeoutTicks=60)
    public static void everyRootedStageCapturesAllSamplesWithinItsActualRange(GameTestHelper h) {
        LivingEntity a=plant(h,8,3,8,false,0,0);
        TemporalWorld world=TemporalWorld.get(h.getLevel());
        BlockPos inside=a.getBlockPos();
        for(int stage=0;stage<=4;stage++) {
            a.tissue.rootStage=stage;a.tissue.setCoreQuantity(0);a.changed();world.invalidate();
            int radius=stage==4?12:4;
            check(h,world.absorbRandomSample(inside.above(radius)),"Plant accepts its inclusive vertical boundary");
            check(h,!world.absorbRandomSample(inside.above(radius+1)),"Plant excludes the next layer");
            check(h,a.tissue.coreQuantity()==(stage==4?140:20),"Natural sample pays current juvenile or mature price");
        }
        a.tissue.tree=true;a.tissue.setCoreQuantity(0);a.changed();world.invalidate();
        check(h,world.absorbRandomSample(inside.east(24)) && !world.absorbRandomSample(inside.east(25))
                && a.tissue.coreQuantity()==220,"Tree sample uses the current tree radius and price");
        a.tissue.tree=false;a.tissue.rootStage=2;a.tissue.setCoreQuantity(0);a.changed();world.invalidate();
        LivingEntity b=plant(h,9,3,8,false,4,0);
        check(h,world.absorbRandomSample(inside) && world.absorbRandomSample(inside)
                && a.tissue.stored()+b.tissue.stored()==280,"Overlapping samples each pay the strongest eligible rate once");
        h.succeed();
    }

    @GameTest(templateNamespace="kaleidoscope_witchcraft", template="empty", timeoutTicks=60)
    public static void naturalLastSampleFillsFractionalColonySpaceAndThenSleeps(GameTestHelper h) {
        LivingEntity a=plant(h,8,3,8,false,3,96_000*Tissue.TU-25);
        LivingEntity b=plant(h,9,3,8,false,3,96_000*Tissue.TU-28);
        a.tissue.productionMode=3;b.tissue.productionMode=3;
        TemporalWorld world=TemporalWorld.get(h.getLevel());
        for(LivingEntity plant:new LivingEntity[]{a,b}) {
            BlockPos soil=plant.getBlockPos().below();
            h.getLevel().setBlock(soil,TemporalContent.ROOTED_FARMLAND.get().defaultBlockState(),3);
            world.add(h.getLevel().getBlockEntity(soil));
        }
        Colony group=world.colony(a);
        check(h,group.room()==53,"Reproduce a fractional shared capacity gap smaller than the new sample quote");
        check(h,world.absorbRandomSample(a.getBlockPos()) && group.quantity()==group.capacity(),
                "The last natural sample must fill the remaining 0.053 TU without exceeding capacity");
        check(h,!world.absorbRandomSample(a.getBlockPos()),"A truly full group releases subsequent native updates");
        check(h,group.spend(25) && world.absorbRandomSample(a.getBlockPos()) && group.room()==0,
                "Even a one-core fractional spending gap should be fillable on the next opportunity");
        h.succeed();
    }

    @GameTest(templateNamespace="kaleidoscope_witchcraft", template="empty", timeoutTicks=60)
    public static void hungryDomainDoesNotLeakAfterFormerGlobalSampleBudget(GameTestHelper h) {
        LivingEntity a=plant(h,8,3,8,false,3,0);
        TemporalWorld world=TemporalWorld.get(h.getLevel());
        for(int i=0;i<5000;i++) check(h,world.absorbRandomSample(a.getBlockPos()),
                "A hungry covered domain must still capture after the former 4096-check limit");
        check(h,a.tissue.coreQuantity()==400*Tissue.TU,"Removing a work quota cannot duplicate income");
        h.succeed();
    }

    @GameTest(templateNamespace="kaleidoscope_witchcraft", template="empty", timeoutTicks=120)
    public static void nativeRandomSpeed100KeepsHungryDomainCropsFrozen(GameTestHelper h) {
        LivingEntity a=plant(h,8,3,8,false,3,0);
        BlockPos crop=a.getBlockPos().east((a.getBlockPos().getX()&15)==15?-1:1);
        h.getLevel().setBlock(crop.below(),Blocks.FARMLAND.defaultBlockState(),3);
        h.getLevel().setBlock(crop,Blocks.WHEAT.defaultBlockState(),3);
        h.getLevel().setBlock(crop.above(),Blocks.GLOWSTONE.defaultBlockState(),3);
        for(int delay=1;delay<=80;delay++) h.runAfterDelay(delay,()-> {
            var chunk=h.getLevel().getChunkAt(crop);
            long slots=java.util.Arrays.stream(chunk.getSections()).filter(s->s.isRandomlyTicking()).count()*100;
            long before=a.tissue.coreQuantity();
            h.getLevel().tickChunk(chunk,100);
            check(h,a.tissue.coreQuantity()-before==slots*80,"Every actual native sample must be credited at speed 100 using expansion-stage pricing");
            check(h,h.getLevel().getBlockState(crop).equals(Blocks.WHEAT.defaultBlockState()),
                    "Native randomTickSpeed 100 must not advance wheat while the entire chunk is absorbed");
            check(h,TemporalWorld.get(h.getLevel()).colony(a).room()>0,"This scenario must remain below capacity");
        });
        h.runAfterDelay(81,()-> {
            check(h,a.tissue.coreQuantity()>0,"The real native scheduler must actually pay for the absorbed sample slots");
            h.succeed();
        });
    }

    @GameTest(templateNamespace="kaleidoscope_witchcraft", template = "empty", timeoutTicks = 80)
    public static void validCropReleaseSpendsStoredTime(GameTestHelper h) {
        LivingEntity a=plant(h,4,3,4,true,0,2000*Tissue.TU);a.tissue.amberStage=3;a.tissue.productionMode=3;a.changed();
        root(h,new BlockPos(5,3,4),a);
        BlockPos crop=h.absolutePos(new BlockPos(6,5,4));
        h.getLevel().setBlock(crop.below(),Blocks.FARMLAND.defaultBlockState(),3);
        h.getLevel().setBlock(crop,Blocks.WHEAT.defaultBlockState(),3);
        h.runAfterDelay(10,()-> {
            check(h,a.tissue.coreQuantity()<2000*Tissue.TU,"Connected root outlet must spend existing amber time on crop updates");
            h.succeed();
        });
    }

    @GameTest(templateNamespace="kaleidoscope_witchcraft", template = "empty", timeoutTicks = 60)
    public static void tissuePlantsOnFarmlandButRejectsDirt(GameTestHelper h) {
        var player=h.makeMockPlayer(GameType.SURVIVAL);
        Tissue t=new Tissue();t.setCoreQuantity(123*Tissue.TU);
        player.setItemInHand(InteractionHand.MAIN_HAND,TissueItem.stack(t));
        BlockPos pos=h.absolutePos(new BlockPos(4,2,4));
        h.getLevel().setBlock(pos,Blocks.DIRT.defaultBlockState(),3);
        UseOnContext context=new UseOnContext(player,InteractionHand.MAIN_HAND,new BlockHitResult(Vec3.atCenterOf(pos),Direction.UP,pos,false));
        check(h,!TemporalContent.TISSUE.get().useOn(context).consumesAction(),"Ordinary dirt must reject the planting action");
        check(h,player.getMainHandItem().getCount()==1 && h.getLevel().getBlockState(pos.above()).isAir(),"Failed planting must preserve the item");
        h.getLevel().setBlock(pos,Blocks.FARMLAND.defaultBlockState(),3);
        check(h,TemporalContent.TISSUE.get().useOn(context).consumesAction(),"Farmland must accept the same item");
        var planted=(LivingEntity)h.getLevel().getBlockEntity(pos.above());
        check(h,planted!=null && planted.tissue.coreQuantity()==t.coreQuantity(),"Planting must preserve carried time");
        check(h,planted.getBlockState().getCollisionShape(h.getLevel(),pos.above()).isEmpty(),"Crop foliage must not act as a solid block");
        h.succeed();
    }

    @GameTest(templateNamespace="kaleidoscope_witchcraft", template = "empty", timeoutTicks = 60)
    public static void cropMaintainsDryFarmlandAndDropsIntactWhenUprooted(GameTestHelper h) {
        LivingEntity a=plant(h,4,3,4,false,0,123*Tissue.TU);
        BlockPos soil=a.getBlockPos().below();
        for(int i=0;i<20;i++) h.getLevel().getBlockState(soil).randomTick(h.getLevel(),soil,h.getLevel().random);
        check(h,h.getLevel().getBlockState(soil).is(Blocks.FARMLAND),"Dry farmland must recognize this crop and remain tilled");
        h.getLevel().setBlock(soil,Blocks.DIRT.defaultBlockState(),3);
        check(h,h.getLevel().getBlockState(a.getBlockPos()).isAir(),"Loss of farmland must uproot the crop");
        var drops=h.getLevel().getEntitiesOfClass(ItemEntity.class,new AABB(a.getBlockPos()).inflate(1),e->e.getItem().is(TemporalContent.TISSUE.get()));
        check(h,drops.size()==1,"Uprooting must drop exactly one intact organism");
        Tissue recovered=TissueTag.load(drops.getFirst().getItem().get(DataComponents.CUSTOM_DATA).copyTag());
        check(h,recovered.coreQuantity()==123*Tissue.TU,"Uprooting must preserve the organism's inventory");
        h.succeed();
    }

    @GameTest(templateNamespace="kaleidoscope_witchcraft", template = "empty", timeoutTicks = 60)
    public static void buriedRootsConnectThroughCropFarmlandAndCutImmediately(GameTestHelper h) {
        LivingEntity a=plant(h,4,3,4,false,1,1000*Tissue.TU),b=plant(h,8,3,4,false,1,0);
        for(int x=4;x<=8;x++) root(h,new BlockPos(x,1,4),a);
        TemporalWorld world=TemporalWorld.get(h.getLevel());
        check(h,world.colony(a).members.size()==2,"Buried roots must connect both root collars through intact farmland");
        BlockState root=h.getLevel().getBlockState(a.getBlockPos().below(2));
        check(h,root.getValue(RootBlock.connection(Direction.UP)),"Buried root must visibly connect upward to the crop collar");
        h.getLevel().setBlock(h.absolutePos(new BlockPos(6,1,4)),Blocks.AIR.defaultBlockState(),3);
        check(h,world.colony(a).members.size()==1 && world.colony(b).members.size()==1,"A buried cut must split the colony immediately");
        check(h,a.tissue.coreQuantity()+b.tissue.coreQuantity()==1000*Tissue.TU,"Buried split must conserve inventory");
        h.succeed();
    }

    @GameTest(templateNamespace="kaleidoscope_witchcraft", template = "empty", timeoutTicks = 60)
    public static void exposedCropPartsNeverJoinRootNetworks(GameTestHelper h) {
        LivingEntity a=plant(h,4,3,4,false,1,1000*Tissue.TU);
        LivingEntity b=plant(h,6,3,4,false,1,0),adjacent=plant(h,7,3,4,false,1,0);
        var exposed=new java.util.ArrayList<RootEntity>();
        for(Direction direction:Direction.values()) {
            if(direction==Direction.DOWN) continue;
            RootEntity segment=root(h,new BlockPos(4,3,4).relative(direction),a);
            segment.ticks=0;
            exposed.add(segment);
            check(h,!segment.getBlockState().getValue(RootBlock.connection(direction.getOpposite())),
                    "An exposed segment must not render an arm into the crop's sides or top");
            check(h,RootConnections.neighbor(h.getLevel(),segment.getBlockPos(),direction.getOpposite())==null
                    && RootConnections.neighbor(h.getLevel(),a.getBlockPos(),direction)==null,
                    "The blocked foliage edge must be rejected in both directions");
        }
        RootEntity buried=root(h,new BlockPos(4,1,4),a);buried.ticks=0;
        TemporalWorld world=TemporalWorld.get(h.getLevel());
        check(h,world.colony(a).members.size()==1 && world.colony(a).roots.size()==1
                && world.colony(b).members.size()==1 && world.colony(adjacent).members.size()==1,
                "Exposed bridges and touching crops cannot merge colonies or attach their roots");
        check(h,h.getLevel().getBlockState(buried.getBlockPos()).getValue(RootBlock.connection(Direction.UP))
                && a.getBlockPos().equals(RootConnections.neighbor(h.getLevel(),buried.getBlockPos(),Direction.UP))
                && buried.getBlockPos().equals(RootConnections.neighbor(h.getLevel(),a.getBlockPos(),Direction.DOWN)),
                "The real buried collar still connects symmetrically through farmland");
        for(int i=0;i<1200;i++) world.accelerateRoots(a);
        check(h,buried.ticks==1200 && exposed.stream().allMatch(r->r.ticks==0)
                && h.getLevel().getBlockState(buried.getBlockPos()).getValue(RootBlock.connection(Direction.UP)),
                "A stale parent reference cannot accelerate roots that only touch foliage");
        h.runAfterDelay(25,()-> {
            check(h,buried.ticks>1200 && exposed.stream().allMatch(r->r.ticks==0),
                    "Normal parent discovery must also reject foliage, while the buried root grows");
            h.succeed();
        });
    }

    @GameTest(templateNamespace="kaleidoscope_witchcraft", template = "empty", timeoutTicks = 60)
    public static void rootsOnlyShowRealConnectionsAndRefreshAfterCut(GameTestHelper h) {
        LivingEntity a=plant(h,4,3,4,true,0,0);
        root(h,new BlockPos(5,3,4),a);RootEntity b=root(h,new BlockPos(6,3,4),a);
        BlockPos p=h.absolutePos(new BlockPos(5,3,4));BlockState state=h.getLevel().getBlockState(p);
        check(h,state.getValue(RootBlock.connection(Direction.WEST)) && state.getValue(RootBlock.connection(Direction.EAST)),"A straight path must render its two connections");
        for(Direction d: new Direction[]{Direction.UP,Direction.DOWN,Direction.NORTH,Direction.SOUTH})
            check(h,!state.getValue(RootBlock.connection(d)),"No decorative branch may point into an unconnected direction");
        h.getLevel().setBlock(b.getBlockPos(),Blocks.AIR.defaultBlockState(),3);
        check(h,!h.getLevel().getBlockState(p).getValue(RootBlock.connection(Direction.EAST)),"Cutting a segment must remove the facing branch immediately");
        h.succeed();
    }

    @GameTest(templateNamespace="kaleidoscope_witchcraft", template = "empty", timeoutTicks = 60)
    public static void ownCreativeTabListsAllOwnItemsAndSupportsSearch(GameTestHelper h) {
        CreativeModeTab tab=TemporalContent.CREATIVE_TAB.get();
        tab.buildContents(new CreativeModeTab.ItemDisplayParameters(h.getLevel().enabledFeatures(),true,h.getLevel().registryAccess()));
        check(h,tab.shouldDisplay() && tab.getDisplayItems().size()==2,"The mod tab must be visible and contain both obtainable materials");
        check(h,tab.getDisplayItems().stream().anyMatch(s->s.is(TemporalContent.TISSUE.get())),"Tissue must be obtainable in the mod tab");
        check(h,tab.getDisplayItems().stream().anyMatch(s->s.is(TemporalContent.ROOT_PIECE.get())),"Root segments must be obtainable in the mod tab");
        check(h,tab.getSearchTabDisplayItems().size()==2,"Both items must also be discoverable through creative search");
        h.succeed();
    }
}
