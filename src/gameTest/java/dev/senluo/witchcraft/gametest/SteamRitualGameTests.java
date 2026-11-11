package dev.senluo.witchcraft.gametest;

import dev.senluo.witchcraft.compat.thaumcraft.NodeSteam;
import com.mojang.authlib.GameProfile;
import dev.senluo.witchcraft.ritual.*;
import dev.senluo.witchcraft.time.WorkTicks;
import dev.senluo.witchcraft.compat.thaumcraft.SteamRituals;
import dev.senluo.witchcraft.mixin.time.FurnaceAccess;
import dev.senluo.witchcraft.mixin.time.CampfireAccess;
import dev.tc4port.thaumcraft.api.aspect.*;
import dev.tc4port.thaumcraft.block.entity.CrucibleBlockEntity;
import dev.tc4port.thaumcraft.block.entity.AlchemyFurnaceBlockEntity;
import dev.tc4port.thaumcraft.block.entity.ArcaneAlembicBlockEntity;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.*;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.item.crafting.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.*;
import net.minecraft.world.phys.*;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@PrefixGameTestTemplate(false)
public final class SteamRitualGameTests {
    @GameTest(templateNamespace="kaleidoscope_witchcraft",template="empty",timeoutTicks=40,batch="steam-stability")
    public static void ringContributionsRemainIndependent(GameTestHelper h) {
        var yes=RitualShape.Match.VALID;var no=RitualShape.Match.INVALID;
        var combinations=List.of(new RitualRings.Layout(no,no,no),new RitualRings.Layout(yes,no,no),
                new RitualRings.Layout(no,yes,no),new RitualRings.Layout(yes,yes,no),
                new RitualRings.Layout(no,no,yes),new RitualRings.Layout(yes,no,yes),
                new RitualRings.Layout(no,yes,yes),new RitualRings.Layout(yes,yes,yes));
        int[] capacities={100,150,175,225,200,250,275,325};
        int[] intervals={1,2,3,4,5,6,7,8};int[] waterOverSixteen={16,12,12,8,12,8,8,4};
        for(int i=0;i<combinations.size();i++) {
            var p=new RitualStability.Profile(combinations.get(i),0);
            h.assertTrue(p.capacity(100)==capacities[i]&&p.nextDelay(101)==101*intervals[i],"Accepted ring-combination table row "+i);
            long paid=0;int credit=0;
            for(int pulse=0;pulse<16;pulse++){var q=p.water(1,credit);paid+=q.millibuckets();credit=q.nextCredit();}
            h.assertTrue(paid==waterOverSixteen[i]&&p.waterMultiplier()==waterOverSixteen[i]/16.0,
                    "Quoted water, displayed multiplier and cumulative payment agree for combination "+i);
        }
        var full=new RitualStability.Profile(combinations.get(7),40);
        h.assertTrue(full.capacity(100)==455&&full.nextDelay(101)==1132&&full.waterMultiplier()==.25,
                "Placed stabilizers multiply the sum once, without changing water");
        var first=full.water(1,0);
        var afterRemoval=new RitualStability.Profile(combinations.get(4),0).water(1,first.nextCredit());
        h.assertTrue(first.millibuckets()+afterRemoval.millibuckets()==1&&afterRemoval.nextCredit()==0,
                "Removing inner rings retains prepaid water: 0.25 plus 0.75 costs one, not two");
        var unknown=new RitualRings.Layout(yes,RitualShape.Match.UNLOADED,yes);
        boolean rejected=false;try{new RitualStability.Profile(unknown,0);}catch(IllegalArgumentException expected){rejected=true;}
        h.assertTrue(!unknown.fullyKnown()&&rejected,"Unknown middle ring cannot silently lower a price or threshold");
        h.succeed();
    }

    @GameTest(templateNamespace="kaleidoscope_witchcraft",template="empty",timeoutTicks=40,batch="steam-stability")
    public static void innerRingsChangeInfluenceWhileLargeRingRemains(GameTestHelper h) {
        var pot=pot(h);var level=h.getLevel();var anchor=pot.getBlockPos().below();
        ring(level,anchor,RitualShape.MEDIUM);ring(level,anchor,RitualShape.LARGE);
        h.assertTrue(SteamRituals.profile(pot).orElseThrow().capacity(100)==325,"All three contribute");
        for(var shape:List.of(RitualShape.SMALL,RitualShape.MEDIUM))
            level.removeBlock(anchor.offset(shape.marks().getFirst()).above(),false);
        var large=SteamRituals.profile(pot).orElseThrow();
        h.assertTrue(large.rings().count()==1&&large.rings().outermostRing()==3&&large.capacity(100)==200
                &&large.decayMultiplier()==5&&large.waterMultiplier()==.75,"A surviving large ring does not stand in for inner rings");
        ring(level,anchor,RitualShape.SMALL);
        var repaired=SteamRituals.profile(pot).orElseThrow();
        h.assertTrue(repaired.rings().count()==2&&repaired.capacity(100)==250&&repaired.decayMultiplier()==6,
                "Repairing the inner ring updates influence even though the outer footprint is unchanged");
        for(var shape:List.of(RitualShape.SMALL,RitualShape.MEDIUM,RitualShape.LARGE))for(var offset:shape.marks()){
            level.removeBlock(anchor.offset(offset).above(),false);level.removeBlock(anchor.offset(offset),false);
        }
        level.removeBlock(pot.getBlockPos(),false);h.succeed();
    }

    @GameTest(templateNamespace="kaleidoscope_witchcraft",template="empty",timeoutTicks=40,batch="steam-stability")
    public static void placedStabilizersShareCapacityAndDecayPolicy(GameTestHelper h) {
        var pot=pot(h);var level=h.getLevel();var anchor=pot.getBlockPos().below();
        h.assertTrue(!pot.hasData(RitualContent.RITUAL_ANCHOR),"Plain alchemy has no ritual identity");
        h.assertTrue(!SteamRituals.managed(pot)&&SteamRituals.profile(pot).orElseThrow().points()==0
                &&!pot.hasData(RitualContent.RITUAL_ANCHOR),"Read-only inspection does not create a ritual attachment");
        var a=anchor.offset(-1,7,0);var b=anchor.offset(1,7,0);var axis=anchor.above(7);
        var outside=anchor.offset(2,8,0);var corner=anchor.offset(3,7,3);
        level.setBlock(a,Blocks.SKELETON_SKULL.defaultBlockState(),3);
        h.assertTrue(SteamRituals.profile(pot).orElseThrow().points()==1,"Unpaired, placed skull is one point");
        level.setBlock(b,Blocks.SKELETON_SKULL.defaultBlockState(),3);
        level.setBlock(outside,Blocks.SKELETON_SKULL.defaultBlockState(),3);
        level.setBlock(corner,Blocks.SKELETON_SKULL.defaultBlockState(),3);
        h.assertTrue(SteamRituals.profile(pot).orElseThrow().points()==4,"Pair is four points; +8 height and square corner are outside");
        level.setBlock(axis,Blocks.SKELETON_SKULL.defaultBlockState(),3);
        h.assertTrue(SteamRituals.profile(pot).orElseThrow().points()==5,"Center-axis block cannot mirror itself");
        level.removeBlock(axis,false);level.removeBlock(b,false);
        h.assertTrue(SteamRituals.profile(pot).orElseThrow().points()==1,"Removal invalidates a cached pair immediately");
        level.setBlock(b,BuiltInRegistries.BLOCK.get(ResourceLocation.parse("thaumcraft:white_tallow_candle")).defaultBlockState(),3);
        var profile=SteamRituals.profile(pot).orElseThrow();
        h.assertTrue(profile.points()==4&&profile.capacity(100)==156,"Tag-based candle pairs with a skull through the same predicate");
        var overflow=new dev.tc4port.thaumcraft.api.event.CrucibleProcessEvent.Overflow(level,pot.getBlockPos(),160,pot.heat(),100,.25,5);
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.post(overflow);
        h.assertTrue(!overflow.isCanceled()&&overflow.capacity()==156&&overflow.nextCheckIn()==5,
                "Stable threshold changes without turning ordinary overflow into refusal or slowing its clock");
        var decay=new dev.tc4port.thaumcraft.api.event.CrucibleProcessEvent.Decomposition(level,pot.getBlockPos(),1,pot.heat(),101);
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.post(decay);
        h.assertTrue(!decay.isCanceled()&&decay.nextDelay()==211,"Only the independent future decay interval scales");
        var example=new RitualStability.Profile(new RitualRings.Layout(RitualShape.Match.VALID,RitualShape.Match.INVALID,RitualShape.Match.INVALID),40);
        h.assertTrue(example.capacity(100)==210&&example.nextDelay(101)==283&&example.waterMultiplier()==.75,
                "Ten pairs match the accepted example; fractional delay rounds up without affecting water");
        h.assertTrue(example.capacity(Integer.MAX_VALUE)==Integer.MAX_VALUE&&example.nextDelay(Integer.MAX_VALUE)==Integer.MAX_VALUE,
                "Changed upstream parameters cannot overflow signed event values");
        prepare(h,pot);
        decay=new dev.tc4port.thaumcraft.api.event.CrucibleProcessEvent.Decomposition(level,pot.getBlockPos(),1,pot.heat(),101);
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.post(decay);
        h.assertTrue(decay.isCanceled(),"Prepared ritual still completely pauses decomposition");
        for(var pos:List.of(a,b,axis,outside,corner,pot.getBlockPos()))level.removeBlock(pos,false);
        h.succeed();
    }
    @GameTest(templateNamespace="kaleidoscope_witchcraft",template="empty",timeoutTicks=10,batch="steam-fields")
    public static void steamFieldsRetainOwnerAndSpatialBoundaries(GameTestHelper h) {
        var fields=new RitualFields();var pos=BlockPos.ZERO;var at=Vec3.atCenterOf(pos);
        var owner=UUID.randomUUID();var id=UUID.randomUUID();var second=UUID.randomUUID();
        fields.put(new RitualFields.Field(id,pos,4,3,owner));
        fields.put(new RitualFields.Field(second,pos,4,1,owner));
        fields.put(new RitualFields.Field(UUID.randomUUID(),pos,4,2));
        h.assertTrue(fields.at(at).doublings()==6&&fields.ownersAt(at).equals(Set.of(owner)),
                "Overlaps multiply work while attribution deduplicates the same owner");
        h.assertTrue(fields.at(at.add(0,5,0)).doublings()==0&&fields.ownersAt(at.add(0,5,0)).isEmpty(),
                "A shared chunk is only a broad-phase candidate, not a field hit");
        fields.remove(id);fields.remove(second);
        h.assertTrue(fields.at(at).doublings()==2&&fields.ownersAt(at).isEmpty(),
                "Removing owned fields retains only the independent anonymous field");
        h.succeed();
    }

    @GameTest(templateNamespace="kaleidoscope_witchcraft",template="empty",timeoutTicks=10,batch="steam-nodes")
    public static void nodeGrowthUsesFractionalMultiplierAndCapsAtCertainty(GameTestHelper h) {
        var speed = java.math.BigInteger.valueOf(13);
        h.assertTrue(Math.abs(NodeSteam.growthProbability(0.1, speed, 4) - 0.325) < 1e-12,
                "13x work gives the fractional 3.25 growth multiplier");
        h.assertTrue(Math.abs(NodeSteam.growthProbability(0.1, speed, 6) - 0.4875) < 1e-12,
                "The ring scales the current probability");
        var huge = java.math.BigInteger.ONE.shiftLeft(2000);
        h.assertTrue(NodeSteam.growthProbability(0.1, huge, 4) == 1 && NodeSteam.growthProbability(0, huge, 4) == 0,
                "Extreme work rates saturate positive chances and preserve impossible outcomes");
        h.succeed();
    }

    @GameTest(templateNamespace="kaleidoscope_witchcraft",template="empty",timeoutTicks=140,batch="steam-nodes")
    public static void steamActivationLeavesUnrelatedAuraAndOfferings(GameTestHelper h) {
        var pot=pot(h);var level=h.getLevel();var pos=pot.getBlockPos();var id=prepare(h,pot);
        var store=StructuralRitualStore.get(level);var aura=AspectId.parse("auram");
        pot.addAspects(Map.of(V,8,O,8,P,32,aura,39));
        var coal=drop(level,pos.east(),new ItemStack(Items.COAL));
        var wood=drop(level,pos.west(),item("thaumcraft:silverwood_log",1));
        var mercury=drop(level,pos.south(),item("thaumcraft:quicksilver",1));
        var essence=drop(level,pos.north(),item("thaumcraft:wisp_essence",2));
        var trigger=drop(level,pos,item("thaumcraft:nitor",1));pot.attemptSmelt(trigger);
        h.assertTrue(!store.progress(id).orElseThrow().terms().contains("AuraGroups"),
                "Steam does not reserve node-promotion resources");
        h.runAtTickTime(110,()->{
            h.assertTrue(!trigger.isAlive()&&mercury.isAlive()&&pot.aspectAmount(aura)==39&&pot.aspectAmount(P)==0
                    &&essence.isAlive()&&essence.getItem().getCount()==2,
                    "Steam pays only its own range, energy and offerings");
            h.assertTrue(SteamWork.doublings(level,Vec3.atCenterOf(pos))==2,
                    "Paid energy produces fourfold steam");
            var restored=reload(h,store).progress(id).orElseThrow();
            h.assertTrue(restored.phase()==StructuralRitualStore.Phase.ACTIVE&&restored.committedSteps()==4
                    &&restored.terms().getInt("EnergyGroups")==1&&!restored.available(),
                    "Reload keeps committed payments and waits for fresh structure validation");
            level.removeBlock(pos,false);essence.discard();mercury.discard();h.succeed();
        });
    }
    private static final AspectId V=AspectId.parse("vacuos"), O=AspectId.parse("ordo"), A=AspectId.parse("aer"), P=AspectId.parse("potentia");
    private static ItemStack item(String id, int count) { return new ItemStack(BuiltInRegistries.ITEM.get(ResourceLocation.parse(id)), count); }
    private static ItemEntity drop(ServerLevel level, BlockPos pos, ItemStack stack) {
        var e = new ItemEntity(level, pos.getX()+0.5, pos.getY()+0.75, pos.getZ()+0.5, stack);
        e.setNoGravity(true); e.setDeltaMovement(Vec3.ZERO); e.setUnlimitedLifetime(); level.addFreshEntity(e); return e;
    }
    private static void ring(ServerLevel level, BlockPos support, RitualShape shape) {
        for (var offset:shape.marks()) {
            BlockPos at=support.offset(offset);
            level.setBlock(at, Blocks.STONE.defaultBlockState(), 3);
            level.setBlock(at.above(), RitualContent.CHALK_SURFACE.get().defaultBlockState(), 3);
        }
    }
    private static CrucibleBlockEntity pot(GameTestHelper h) {
        var level=h.getLevel(); BlockPos pos=h.absolutePos(new BlockPos(8,3,8));
        level.setBlock(pos.below(), Blocks.MAGMA_BLOCK.defaultBlockState(), 3);
        level.setBlock(pos, BuiltInRegistries.BLOCK.get(ResourceLocation.parse("thaumcraft:crucible")).defaultBlockState(), 3);
        var pot=(CrucibleBlockEntity)level.getBlockEntity(pos);
        pot.fillWater(1000); var tag=pot.saveWithoutMetadata(level.registryAccess()); tag.putShort("Heat", (short)200);
        pot.loadWithComponents(tag, level.registryAccess()); ring(level,pos.below(),RitualShape.SMALL);
        return pot;
    }
    private static UUID prepare(GameTestHelper h, CrucibleBlockEntity pot) {
        var stack=new ItemStack(RitualContent.TAGLOCK.get(),2);
        var player=researchedPlayer(h,"steam-test","STRUCTURAL_RITUALS","RITUAL_TRANSPIRATION");
        stack.set(RitualContent.TAGLOCK_SAMPLE.get(),new TaglockSample(1,player.getUUID(),ResourceLocation.withDefaultNamespace("player"),"steam-test",true));
        var sample=drop(h.getLevel(),pot.getBlockPos().above(),stack);
        int before=pot.totalAspects(), plants=pot.aspectAmount(AspectId.parse("herba"));
        pot.attemptSmelt(sample); // HANDLED means the listener consumed it, not the native smelter.
        h.assertTrue(pot.totalAspects()==before+1&&pot.aspectAmount(AspectId.parse("herba"))==plants+1,
                "Preparation adds exactly one Herba, not the vial's ordinary item aspects");
        h.assertTrue(RitualHerba.marked(pot),"Preparation distinguishes one real Herba unit for presentation");
        h.assertTrue(sample.getItem().getCount()==1,"Only one sample from a stack is consumed"); sample.discard();
        return StructuralRitualStore.get(h.getLevel()).at(RitualCenters.observe(pot).orElseThrow().reference()).orElseThrow();
    }
    static FakePlayer researchedPlayer(GameTestHelper h, String name, String... research) {
        var player=new FakePlayer(h.getLevel(),new GameProfile(UUID.randomUUID(),name));
        var knowledge=dev.tc4port.thaumcraft.data.PlayerKnowledgeData.initial(h.getLevel().random);
        for(var key:research)knowledge=knowledge.completeResearch(
                dev.tc4port.thaumcraft.api.research.ResearchKey.parse("kaleidoscope_witchcraft:"+key));
        player.setData(dev.tc4port.thaumcraft.state.TCObjectState.PLAYER_KNOWLEDGE.native$type,knowledge);
        try {
            var directory=h.getLevel().getServer().getWorldPath(net.minecraft.world.level.storage.LevelResource.PLAYER_DATA_DIR);
            java.nio.file.Files.createDirectories(directory);
            net.minecraft.nbt.NbtIo.writeCompressed(player.saveWithoutId(new CompoundTag()),directory.resolve(player.getUUID()+".dat"));
        } catch (java.io.IOException failure) { throw new AssertionError("Cannot save the test player's research",failure); }
        return player;
    }
    @GameTest(templateNamespace="kaleidoscope_witchcraft",template="empty",timeoutTicks=40,batch="steam-materials")
    public static void unresearchedSampleCannotPayForPreparation(GameTestHelper h) {
        var pot=pot(h);var level=h.getLevel();var player=researchedPlayer(h,"locked-ritual");
        var stack=new ItemStack(RitualContent.TAGLOCK.get(),2);
        stack.set(RitualContent.TAGLOCK_SAMPLE.get(),new TaglockSample(1,player.getUUID(),
                ResourceLocation.withDefaultNamespace("player"),"locked-ritual",true));
        var sample=drop(level,pot.getBlockPos().above(),stack);
        var store=StructuralRitualStore.get(level);var center=RitualCenters.observe(pot).orElseThrow().reference();
        int before=pot.totalAspects();
        pot.attemptSmelt(sample);
        h.assertTrue(sample.isAlive()&&sample.getItem().getCount()==2&&pot.totalAspects()==before,
                "Missing preparation research keeps the complete sample stack and all aspect balances unchanged");
        h.assertTrue(store.allAt(center).isEmpty()&&!RitualHerba.marked(pot),
                "Denied native input creates neither a ring claim nor a marked ritual leaf");
        sample.discard();level.removeBlock(pot.getBlockPos(),false);h.succeed();
    }
    private static StructuralRitualStore reload(GameTestHelper h, StructuralRitualStore store) {
        try {
            var method=StructuralRitualStore.class.getDeclaredMethod("load",CompoundTag.class,net.minecraft.core.HolderLookup.Provider.class);
            method.setAccessible(true);
            return (StructuralRitualStore)method.invoke(null,store.save(new CompoundTag(),h.getLevel().registryAccess()),h.getLevel().registryAccess());
        } catch (ReflectiveOperationException problem) { throw new AssertionError(problem); }
    }
    @GameTest(templateNamespace="kaleidoscope_witchcraft",template="empty",timeoutTicks=40,batch="steam-materials")
    public static void steamHerbaSurvivesPauseAndEndsWithoutDuplicatingContents(GameTestHelper h) {
        var pot=pot(h);var level=h.getLevel();var id=prepare(h,pot);var herba=AspectId.parse("herba");
        pot.addAspects(Map.of(herba,3));
        var saved=pot.saveWithoutMetadata(level.registryAccess());
        RitualHerba.set(pot,false);pot.loadWithComponents(saved,level.registryAccess());
        h.assertTrue(RitualHerba.marked(pot)&&pot.aspectAmount(herba)==4,
                "Native saved contents restore the marker without an additional inventory unit");
        BlockPos broken=pot.getBlockPos().below().offset(RitualShape.SMALL.marks().getFirst()).above();
        level.removeBlock(broken,false);SteamRituals.refreshHerba(pot);
        h.assertTrue(RitualHerba.marked(pot),"An incomplete circle pauses rather than clears the ritual leaf");
        StructuralRitualStore.get(level).end(level,id);StructuralRitualStore.get(level).finish(id);SteamRituals.refreshHerba(pot);
        h.assertTrue(!RitualHerba.marked(pot)&&pot.aspectAmount(herba)==4,
                "True termination returns to ordinary Herba without deleting or duplicating it");
        level.setBlock(broken,RitualContent.CHALK_SURFACE.get().defaultBlockState(),3);prepare(h,pot);
        dev.tc4port.thaumcraft.api.machine.CrucibleApi.removeAspect(level,pot.getBlockPos(),herba,pot.aspectAmount(herba));
        h.runAfterDelay(2, () -> {
            h.assertTrue(!RitualHerba.marked(pot),"The next center observation clears the extracted Herba marker");
            pot.addAspects(Map.of(herba,3));SteamRituals.refreshHerba(pot);
            h.assertTrue(!RitualHerba.marked(pot),"Ordinary later inputs cannot resurrect the distinguished leaf");
            level.removeBlock(pot.getBlockPos(),false);h.succeed();
        });
    }
    @GameTest(templateNamespace="kaleidoscope_witchcraft",template="empty",timeoutTicks=40,batch="steam-materials")
    public static void steamAdmissionKeepsTriggerAndBouncesOverflow(GameTestHelper h) {
        var pot=pot(h); var level=h.getLevel();
        var trigger=drop(level,pot.getBlockPos().above(),item("thaumcraft:nitor",1));
        int before=pot.totalAspects(); pot.attemptSmelt(trigger);
        h.assertTrue(trigger.isAlive()&&pot.totalAspects()==before,"Nitor before the sample is not ordinary alchemy"); trigger.discard();
        prepare(h,pot);
        var invalid=drop(level,pot.getBlockPos().above(),new ItemStack(RitualContent.TAGLOCK.get()));
        before=pot.totalAspects(); pot.attemptSmelt(invalid);
        h.assertTrue(invalid.isAlive()&&pot.totalAspects()==before,"An empty/second sample cannot replace the initiator"); invalid.discard();
        int capacity=SteamRituals.stableCapacity(pot,CrucibleBlockEntity.configuredStableAspects());
        ItemStack coal=new ItemStack(Items.COAL,5);
        int cost=AspectQueryApi.item(coal).amounts().values().stream().mapToInt(Integer::intValue).sum();
        h.assertTrue(cost>0,"Native coal aspects exist");
        pot.addAspects(Map.of(A,capacity-cost*2-pot.totalAspects()));
        var fuel=drop(level,pot.getBlockPos().above(),coal); pot.attemptSmelt(fuel);
        h.assertTrue(fuel.getItem().getCount()==3&&pot.totalAspects()==capacity,"Two complete units fit; three are bounced");
        h.assertTrue(fuel.getDeltaMovement().y>0,"Rejected material is visibly ejected");
        pot.attemptSmelt(fuel);
        h.assertTrue(fuel.getItem().getCount()==3&&pot.totalAspects()==capacity,"Repeated collisions cannot overfill"); fuel.discard();
        var overflow = new dev.tc4port.thaumcraft.api.event.CrucibleProcessEvent.Overflow(
                level,pot.getBlockPos(),pot.totalAspects(),pot.heat(),1,0.25,5);
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.post(overflow);
        h.assertTrue(overflow.isCanceled() && overflow.capacity()==SteamRituals.stableCapacity(pot,1),
                "A lower capacity after preparation preserves contents without automatic flux");
        var contents=Map.copyOf(pot.aspects());
        for(int i=0;i<150;i++) CrucibleBlockEntity.serverTick(level,pot.getBlockPos(),pot.getBlockState(),pot);
        h.assertTrue(pot.aspects().equals(contents),"Prepared contents do not decompose during boiling");
        level.removeBlock(pot.getBlockPos(),false); h.succeed();
    }
    @GameTest(templateNamespace="kaleidoscope_witchcraft",template="empty",timeoutTicks=180,batch="steam-sequence")
    public static void steamSequencePauseRepairAndReplacement(GameTestHelper h) {
        var pot=pot(h); var level=h.getLevel(); var pos=pot.getBlockPos(); var id=prepare(h,pot);
        var store=StructuralRitualStore.get(level); pot.addAspects(Map.of(V,16,O,16));
        var coal=drop(level,pos.east(),new ItemStack(Items.COAL,2));
        var wood=drop(level,pos.west(),item("thaumcraft:silverwood_log",2));
        var mercury=drop(level,pos.south(),item("thaumcraft:quicksilver",2));
        var trigger=drop(level,pos,item("thaumcraft:nitor",2)); pot.attemptSmelt(trigger);
        h.assertTrue(store.progress(id).orElseThrow().terms().getBoolean("Armed"),"Nitor arms a prepared valid circle");
        h.runAtTickTime(25,()->{
            h.assertTrue(coal.getItem().getCount()==1&&wood.getItem().getCount()==2,"Only coal is spent after first second");
            h.assertTrue(SteamWork.doublings(level,Vec3.atCenterOf(pos))==0,"Preparation cannot accelerate work");
        });
        h.runAtTickTime(45,()->h.assertTrue(wood.getItem().getCount()==1&&mercury.getItem().getCount()==2,"Wood is spent; mercury remains"));
        h.runAtTickTime(65,()->h.assertTrue(trigger.getItem().getCount()==2&&mercury.getItem().getCount()==2,"Resonance does not consume mercury or early Nitor"));
        BlockPos broken=pos.below().offset(RitualShape.SMALL.marks().getFirst()).above();
        var pig=EntityType.PIG.create(level);pig.setPos(Vec3.atCenterOf(pos.above(2)));pig.setNoGravity(true);pig.setNoAi(true);
        float health=pig.getHealth();
        h.runAtTickTime(85,()->{
            h.assertTrue(store.progress(id).orElseThrow().phase()==StructuralRitualStore.Phase.ACTIVE,"Fourth second activates the persisted ritual: "+store.progress(id));
            h.assertTrue(trigger.getItem().getCount()==1&&pot.aspectAmount(V)==0&&pot.aspectAmount(O)==0,"One Nitor and 4+4 aspects per radius are paid exactly once");
            h.assertTrue(SteamWork.doublings(level,Vec3.atCenterOf(pos))==1,"Active session registers exactly one field");
            var restored=reload(h,store);var progress=restored.progress(id).orElseThrow();
            h.assertTrue(progress.phase()==StructuralRitualStore.Phase.ACTIVE&&progress.committedSteps()==4&&!progress.available(),"Active save retains all payment receipts but requires fresh world validation");
            level.removeBlock(broken,false);
        });
        h.runAtTickTime(88,()->{
            h.assertTrue(SteamWork.doublings(level,Vec3.atCenterOf(pos))==0,"Damaged ring pauses the field");
            int added=CrucibleBlockEntity.configuredStableAspects()*2;pot.addAspects(Map.of(A,added));int stored=pot.totalAspects();
            for(int i=0;i<40;i++)CrucibleBlockEntity.serverTick(level,pos,level.getBlockState(pos),pot);
            h.assertTrue(pot.totalAspects()==stored,"A paused required ring cannot cause overflow or decay to destroy stored aspects");
            dev.tc4port.thaumcraft.api.machine.CrucibleApi.removeAspect(level,pos,A,added);
            level.setBlock(broken,RitualContent.CHALK_SURFACE.get().defaultBlockState(),3);
        });
        h.runAtTickTime(91,()->{
            h.assertTrue(SteamWork.doublings(level,Vec3.atCenterOf(pos))==1&&coal.getItem().getCount()==1,"Repair resumes without spending offerings again");
            pot.tank().drain(1000,net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction.EXECUTE);
            level.addFreshEntity(pig);
        });
        h.runAtTickTime(113,()->{
            h.assertTrue(pig.getHealth()==health&&SteamWork.doublings(level,Vec3.atCenterOf(pos))==0,"Dry vessel pauses steam damage and accelerated work without losing the session");
            pig.setHealth(health);pig.invulnerableTime=0;pot.fillWater(1000);
        });
        h.runAtTickTime(136,()->{
            h.assertTrue(pig.getHealth()<health&&pot.water()<1000,"Wet steam still hurts and running evaporation consumes water");
            pig.discard();
            level.removeBlock(pos,false);
            level.setBlock(pos,BuiltInRegistries.BLOCK.get(ResourceLocation.parse("thaumcraft:crucible")).defaultBlockState(),3);
        });
        h.runAtTickTime(139,()->{
            h.assertTrue(store.progress(id).isEmpty()&&SteamWork.doublings(level,Vec3.atCenterOf(pos))==0,"Replacement at the same address cannot inherit a live ritual");
            coal.discard();wood.discard();mercury.discard();trigger.discard();h.succeed();
        });
    }
    @GameTest(templateNamespace="kaleidoscope_witchcraft",template="empty",timeoutTicks=140,batch="steam-energy")
    public static void steamEnergyIsPaidOnceAndResumesAtBoundRate(GameTestHelper h) {
        var pot=pot(h);var level=h.getLevel();var pos=pot.getBlockPos();var id=prepare(h,pot);
        var store=StructuralRitualStore.get(level);pot.addAspects(Map.of(V,4,O,4,P,65));
        var coal=drop(level,pos.east(),new ItemStack(Items.COAL));
        var wood=drop(level,pos.west(),item("thaumcraft:silverwood_log",1));
        var mercury=drop(level,pos.south(),item("thaumcraft:quicksilver",1));
        var trigger=drop(level,pos,item("thaumcraft:nitor",1));pot.attemptSmelt(trigger);
        var bound=store.progress(id).orElseThrow().terms();
        h.assertTrue(bound.getInt("Format")==3&&bound.getInt("EnergyGroups")==2&&pot.aspectAmount(P)==65,
                "Reservation quotes two 32-point groups without early payment");
        // Intervening extraction must pause the final payment, not silently lower the quoted rate.
        h.runAtTickTime(65,()->dev.tc4port.thaumcraft.api.machine.CrucibleApi.removeAspect(level,pos,P,34));
        h.runAtTickTime(85,()->{
            h.assertTrue(store.progress(id).orElseThrow().phase()==StructuralRitualStore.Phase.PREPARING
                    &&pot.aspectAmount(V)==4&&pot.aspectAmount(O)==4&&pot.aspectAmount(P)==31&&trigger.isAlive(),
                    "Insufficient energy cannot partially spend range aspects or the trigger");
            h.assertTrue(SteamWork.doublings(level,Vec3.atCenterOf(pos))==0,"Unpaid multiplier grants no work");
            pot.addAspects(Map.of(P,34));
        });
        BlockPos broken=pos.below().offset(RitualShape.SMALL.marks().getFirst()).above();
        h.runAtTickTime(89,()->{
            h.assertTrue(store.progress(id).orElseThrow().phase()==StructuralRitualStore.Phase.ACTIVE
                    &&pot.aspectAmount(V)==0&&pot.aspectAmount(O)==0&&pot.aspectAmount(P)==1&&!trigger.isAlive(),
                    "Activation spends 64 energy and both range costs, leaving the unpaired energy");
            h.assertTrue(SteamWork.doublings(level,Vec3.atCenterOf(pos))==3,"Base 2x plus two groups produces 8x");
            var saved=reload(h,store).progress(id).orElseThrow();
            h.assertTrue(saved.committedSteps()==4&&saved.terms().getInt("EnergyGroups")==2&&!saved.available(),
                    "Reload retains the paid exponent and requires validation without replaying payment");
            pot.addAspects(Map.of(P,64));
            level.removeBlock(broken,false);
        });
        h.runAtTickTime(93,()->{
            h.assertTrue(SteamWork.doublings(level,Vec3.atCenterOf(pos))==0,"Broken structure removes the powered field");
            level.setBlock(broken,RitualContent.CHALK_SURFACE.get().defaultBlockState(),3);
        });
        h.runAtTickTime(97,()->{
            h.assertTrue(SteamWork.doublings(level,Vec3.atCenterOf(pos))==3&&pot.aspectAmount(P)==65,
                    "Repair restores the same 8x rate without recharging or counting later energy");
            level.removeBlock(pos,false);mercury.discard();coal.discard();wood.discard();h.succeed();
        });
    }
    @GameTest(templateNamespace="kaleidoscope_witchcraft",template="empty",timeoutTicks=150,batch="steam-energy-receipt")
    public static void steamMissingEnergyReceiptPausesUntilCorrected(GameTestHelper h) {
        var pot=pot(h);var level=h.getLevel();var pos=pot.getBlockPos();var id=prepare(h,pot);
        var store=StructuralRitualStore.get(level);pot.addAspects(Map.of(V,4,O,4,P,32));
        var coal=drop(level,pos.east(),new ItemStack(Items.COAL));
        var wood=drop(level,pos.west(),item("thaumcraft:silverwood_log",1));
        var mercury=drop(level,pos.south(),item("thaumcraft:quicksilver",1));
        var trigger=drop(level,pos,item("thaumcraft:nitor",1));pot.attemptSmelt(trigger);
        var terms=store.progress(id).orElseThrow().terms();terms.remove("EnergyGroups");
        h.assertTrue(store.prepareTerms(level,id,terms),"Inject incomplete new-format terms before payment");
        h.runAtTickTime(25,()->{
            h.assertTrue(store.progress(id).orElseThrow().elapsed()==0&&coal.isAlive()
                    &&SteamWork.doublings(level,Vec3.atCenterOf(pos))==0,
                    "A missing new energy receipt pauses rather than defaulting to a free multiplier");
            // Correct the current receipt; the first unpaid milestone may now resume.
            terms.putInt("EnergyGroups",1);store.prepareTerms(level,id,terms);
        });
        h.runAtTickTime(110,()->{
            h.assertTrue(store.progress(id).orElseThrow().phase()==StructuralRitualStore.Phase.ACTIVE
                    &&SteamWork.doublings(level,Vec3.atCenterOf(pos))==2&&pot.aspectAmount(P)==0,
                    "A corrected receipt pays once and activates the quoted multiplier");
            var restored=reload(h,store).progress(id).orElseThrow();
            h.assertTrue(restored.terms().getInt("Format")==4&&restored.committedSteps()==4,
                    "Current activation retains committed milestones across save/load");
            level.removeBlock(pos,false);mercury.discard();coal.discard();wood.discard();trigger.discard();h.succeed();
        });
    }
    @GameTest(templateNamespace="kaleidoscope_witchcraft",template="empty",timeoutTicks=40,batch="steam-storage")
    public static void steamLedgerReloadAndStability(GameTestHelper h) throws Exception {
        var pot=pot(h); var level=h.getLevel(); var id=prepare(h,pot); var store=StructuralRitualStore.get(level);
        CompoundTag saved=store.save(new CompoundTag(),level.registryAccess());
        var load=StructuralRitualStore.class.getDeclaredMethod("load",CompoundTag.class,net.minecraft.core.HolderLookup.Provider.class);load.setAccessible(true);
        var restored=(StructuralRitualStore)load.invoke(null,saved,level.registryAccess());
        h.assertTrue(restored.progress(id).orElseThrow().elapsed()==0&&!restored.progress(id).orElseThrow().available(),"Reload never grants offline work or trusts cached structure");
        var ref=RitualCenters.observe(pot).orElseThrow().reference();
        var paused=restored.advance(level,id,StructuralRitualStore.STEAM,StructuralRitualStore.Presence.UNLOADED,null,RitualShape.Match.UNLOADED,(t,d)->{throw new AssertionError("Unloaded payment");});
        h.assertTrue(!paused.mayRun()&&restored.progress(id).orElseThrow().elapsed()==0,"Unloaded ritual stays paused");
        h.assertTrue(RitualShape.SMALL.marks().size()==16&&RitualShape.MEDIUM.marks().size()==28&&RitualShape.LARGE.marks().size()==40,"Witchery circle sizes");
        h.assertTrue(RitualStability.steamRadius(3,99)==0&&RitualStability.steamRadius(8,20)==2&&RitualStability.steamRadius(999,999)==32,"No base radius; scarce aspect and design radius limit");
        for(int energy:new int[]{0,31,32,63,64,65}) {
            var quote=SteamCharge.quote(4,energy);
            h.assertTrue(quote.energyCost()==energy-energy%32&&quote.doublings()==1+energy/32
                    &&quote.rangeCost()==16&&quote.canPay(16,16,energy),"Whole energy groups share one range/payment quote");
        }
        var weighted=new RitualFields();UUID left=UUID.randomUUID(),right=UUID.randomUUID();
        weighted.put(new RitualFields.Field(left,pot.getBlockPos(),4,2));
        weighted.put(new RitualFields.Field(right,pot.getBlockPos(),4,3));
        h.assertTrue(weighted.at(Vec3.atCenterOf(pot.getBlockPos())).asLong().orElseThrow()==32,"4x and 8x fields multiply to 32x");
        weighted.remove(left);
        h.assertTrue(weighted.at(Vec3.atCenterOf(pot.getBlockPos())).asLong().orElseThrow()==8,"Removing one field leaves the other's paid rate");
        var field=new RitualFields(); for(int i=0;i<70;i++)field.put(new RitualFields.Field(UUID.randomUUID(),pot.getBlockPos(),4));
        h.assertTrue(field.at(Vec3.atCenterOf(pot.getBlockPos())).doublings()==70&&field.at(Vec3.atCenterOf(pot.getBlockPos())).asLong().isEmpty(),"Multipliers remain exact beyond machine-long range without a hidden 64x cap");
        level.removeBlock(pot.getBlockPos(),false);h.succeed();
    }
    private static TickingBlockEntity ticker(BlockEntity be,Runnable run) {
        return new TickingBlockEntity(){public void tick(){run.run();}public boolean isRemoved(){return be.isRemoved();}public BlockPos getPos(){return be.getBlockPos();}public String getType(){return "steam-test";}};
    }
    /** Models a scheduler wrapper whose delegate may change during tick(), without a Lithium dependency. */
    private static final class LifecycleTicker implements TickingBlockEntity {
        private BlockPos pos;
        private boolean removed;
        private int calls;
        private Runnable action = () -> {};
        private LifecycleTicker(BlockPos pos) { this.pos = pos; }
        public void tick() { calls++; action.run(); }
        public boolean isRemoved() { return removed; }
        public BlockPos getPos() { return pos; }
        public String getType() { return "lifecycle-test"; }
    }
    @GameTest(templateNamespace="kaleidoscope_witchcraft",template="empty",timeoutTicks=40,batch="steam-work")
    public static void scheduledWorkRejectsInvalidTargets(GameTestHelper h) {
        var level=h.getLevel(); BlockPos pos=h.absolutePos(new BlockPos(8,3,8));
        level.setBlock(pos,Blocks.FURNACE.defaultBlockState(),3);
        var expected=level.getBlockEntity(pos); var changing=new LifecycleTicker(pos);
        try {
            h.assertTrue(WorkTicks.scheduledTarget(level,changing)==expected,"Capture the original live target");
            changing.pos=null; // Lithium's sleeping delegate is not removed, but has no position.
            h.assertTrue(WorkTicks.scheduledTarget(level,changing)==null
                    && !WorkTicks.isScheduledTarget(level,changing,expected),"Sleeping tickers are opaque, not work targets");
            changing.pos=pos.above();
            h.assertTrue(!WorkTicks.isScheduledTarget(level,changing,expected),"A moved ticker cannot reuse its old receipt");
            changing.pos=pos; changing.removed=true;
            h.assertTrue(WorkTicks.scheduledTarget(level,changing)==null
                    && !WorkTicks.isScheduledTarget(level,changing,expected),"A removed ticker cannot run extra work");
            changing.removed=false; level.removeBlock(pos,false);
            h.assertTrue(WorkTicks.scheduledTarget(level,changing)==null
                    && !WorkTicks.isScheduledTarget(level,changing,expected),"Destruction invalidates the target");
        } finally { level.removeBlock(pos,false); }
        h.succeed();
    }
    @GameTest(templateNamespace="kaleidoscope_witchcraft",template="empty",timeoutTicks=40,batch="steam-work")
    public static void steamStopsAtSleepAndResumesAfterWake(GameTestHelper h) {
        var level=h.getLevel(); BlockPos pos=h.absolutePos(new BlockPos(8,3,8)); UUID field=UUID.randomUUID();
        level.setBlock(pos,Blocks.FURNACE.defaultBlockState(),3);
        var expected=level.getBlockEntity(pos); ((FurnaceAccess)expected).senluo$litTime(100);
        var changing=new LifecycleTicker(pos);
        SteamWork.fields(level).put(new RitualFields.Field(field,pos,4,2));
        try {
            var before=WorkTicks.scheduledTarget(level,changing);
            changing.action=()->changing.pos=null;
            changing.tick(); // Sleep during the original opportunity.
            SteamWork.afterNative(level,changing,before,changing::tick);
            h.assertTrue(changing.calls==1,"Sleeping after native work grants no extra calls");
            changing.pos=pos;
            SteamWork.afterNative(level,changing,before,changing::tick);
            h.assertTrue(changing.calls==2,"Sleeping during extra work stops the remaining batch");
            changing.pos=pos; changing.action=()->{};
            SteamWork.afterNative(level,changing,WorkTicks.scheduledTarget(level,changing),changing::tick);
            h.assertTrue(changing.calls==5,"Wake restores three extra calls at 4x without a stale sleep cache");
        } finally { SteamWork.fields(level).remove(field); level.removeBlock(pos,false); }
        h.succeed();
    }
    @GameTest(templateNamespace="kaleidoscope_witchcraft",template="empty",timeoutTicks=40,batch="steam-work")
    public static void steamDoesNotTransferWorkToReplacement(GameTestHelper h) {
        var level=h.getLevel(); BlockPos pos=h.absolutePos(new BlockPos(8,3,8)); UUID field=UUID.randomUUID();
        level.setBlock(pos,Blocks.FURNACE.defaultBlockState(),3);
        var expected=level.getBlockEntity(pos); ((FurnaceAccess)expected).senluo$litTime(100);
        var changing=new LifecycleTicker(pos);
        SteamWork.fields(level).put(new RitualFields.Field(field,pos,4,2));
        try {
            changing.action=()->{
                level.removeBlock(pos,false);
                level.setBlock(pos,Blocks.FURNACE.defaultBlockState(),3);
                ((FurnaceAccess)level.getBlockEntity(pos)).senluo$litTime(100);
            };
            changing.tick(); // Same position and type, different instance after the original call.
            SteamWork.afterNative(level,changing,expected,changing::tick);
            h.assertTrue(changing.calls==1,"Native replacement must not inherit the previous target's work");
            var replacement=WorkTicks.scheduledTarget(level,changing);
            h.assertTrue(replacement!=null && replacement!=expected,"Next opportunity can resolve the new target");
            SteamWork.afterNative(level,changing,replacement,changing::tick);
            h.assertTrue(changing.calls==2,"Replacement during extra work ends the batch too");
        } finally { SteamWork.fields(level).remove(field); level.removeBlock(pos,false); }
        h.succeed();
    }
    @GameTest(templateNamespace="kaleidoscope_witchcraft",template="empty",timeoutTicks=40,batch="steam-work")
    public static void steamNativeWorkAdvancesFuelAndDistillation(GameTestHelper h) throws Exception {
        var level=h.getLevel(); BlockPos pos=h.absolutePos(new BlockPos(8,3,8));
        var fields=SteamWork.fields(level); UUID one=UUID.randomUUID(),two=UUID.randomUUID();
        fields.put(new RitualFields.Field(one,pos,4,2)); // One powered field, not two baseline fields.
        level.setBlock(pos,Blocks.FURNACE.defaultBlockState(),3); var furnace=(FurnaceBlockEntity)level.getBlockEntity(pos);
        furnace.setItem(0,new ItemStack(Items.RAW_IRON,8)); furnace.setItem(1,new ItemStack(Items.COAL,2));
        Runnable run=()->AbstractFurnaceBlockEntity.serverTick(level,pos,level.getBlockState(pos),furnace);run.run();
        var a=(FurnaceAccess)furnace; int fuel=a.senluo$litTime(),cook=a.senluo$cookingProgress();
        SteamWork.afterNative(level,ticker(furnace,run),furnace,run);
        h.assertTrue(a.senluo$litTime()==fuel-3&&a.senluo$cookingProgress()==cook+3,"4x adds three native fuel and progress steps");
        level.setBlock(pos,BuiltInRegistries.BLOCK.get(ResourceLocation.parse("thaumcraft:alchemical_furnace")).defaultBlockState(),3);
        var alchemy=(AlchemyFurnaceBlockEntity)level.getBlockEntity(pos);alchemy.setItem(0,new ItemStack(Items.COAL,8));alchemy.setItem(1,new ItemStack(Items.COAL,2));
        Runnable distill=()->AlchemyFurnaceBlockEntity.serverTick(level,pos,level.getBlockState(pos),alchemy);distill.run();
        int burn=alchemy.burnTime(),progress=alchemy.cookTime();
        var count=AlchemyFurnaceBlockEntity.class.getDeclaredField("count");count.setAccessible(true);int transport=count.getInt(alchemy);
        SteamWork.afterNative(level,ticker(alchemy,distill),alchemy,distill);
        h.assertTrue(alchemy.burnTime()==burn-3&&alchemy.cookTime()==progress+3,"Distillation also charges native fuel");
        h.assertTrue(count.getInt(alchemy)==transport+3,"4x advances the furnace-owned distillation clock too");
        level.setBlock(pos,Blocks.CAMPFIRE.defaultBlockState(),3);var camp=(CampfireBlockEntity)level.getBlockEntity(pos);
        camp.placeFood(null,new ItemStack(Items.BEEF),600);Runnable cooking=()->CampfireBlockEntity.cookTick(level,pos,level.getBlockState(pos),camp);cooking.run();
        SteamWork.afterNative(level,ticker(camp,cooking),camp,cooking);
        h.assertTrue(((CampfireAccess)camp).senluo$progress()[0]==4,"Campfire cooking uses the same spatial scheduler");
        fields.remove(one);fields.remove(two);level.removeBlock(pos,false);h.succeed();
    }
    @GameTest(templateNamespace="kaleidoscope_witchcraft",template="empty",timeoutTicks=40,batch="steam-work")
    public static void steamDrainsAllFiveAlembicsAfterFuelEnds(GameTestHelper h) {
        var level=h.getLevel(); BlockPos pos=h.absolutePos(new BlockPos(8,3,8));
        UUID field=UUID.randomUUID();
        // Only the furnace is inside: the native distillation stack is one process.
        SteamWork.fields(level).put(new RitualFields.Field(field,pos,1));
        level.setBlock(pos,BuiltInRegistries.BLOCK.get(ResourceLocation.parse("thaumcraft:alchemical_furnace")).defaultBlockState(),3);
        var furnace=(AlchemyFurnaceBlockEntity)level.getBlockEntity(pos);
        String[] keys={"aer","terra","ignis","aqua","ordo","perditio"};
        var contents=new ListTag();
        for(int i=0;i<keys.length;i++) {
            var entry=new CompoundTag();entry.putString("key",keys[i]);entry.putInt("amount",2);contents.add(entry);
            level.setBlock(pos.above(i+1),BuiltInRegistries.BLOCK.get(ResourceLocation.parse("thaumcraft:arcane_alembic")).defaultBlockState(),3);
            var alembic=(ArcaneAlembicBlockEntity)level.getBlockEntity(pos.above(i+1));
            alembic.add(AspectId.parse(keys[i]),1);
        }
        var saved=furnace.saveWithoutMetadata(level.registryAccess());saved.put("Aspects",contents);
        furnace.loadWithComponents(saved,level.registryAccess());
        Runnable nativeTick=()->AlchemyFurnaceBlockEntity.serverTick(level,pos,furnace.getBlockState(),furnace);
        for(int i=0;i<20;i++) {
            nativeTick.run();SteamWork.afterNative(level,ticker(furnace,nativeTick),furnace,nativeTick);
        }
        for(int i=0;i<5;i++) h.assertTrue(((ArcaneAlembicBlockEntity)level.getBlockEntity(pos.above(i+1))).amount()==2,
                "Each of five occupied layers receives one unit in 20 real ticks at 2x");
        h.assertTrue(((ArcaneAlembicBlockEntity)level.getBlockEntity(pos.above(6))).amount()==1,
                "Native five-layer boundary remains intact");
        h.assertTrue(!furnace.isBurning()&&furnace.vis()==7,"Fuel-free residual distillation conserves all aspects");
        SteamWork.fields(level).remove(field);
        for(int i=6;i>=0;i--) level.removeBlock(pos.above(i),false);
        h.succeed();
    }
    @GameTest(templateNamespace="kaleidoscope_witchcraft",template="empty",timeoutTicks=40,batch="steam-fire")
    public static void steamCombustionKeepsOtherEntityTimeNative(GameTestHelper h) {
        var level=h.getLevel();BlockPos pos=h.absolutePos(new BlockPos(8,3,8));UUID one=UUID.randomUUID(),two=UUID.randomUUID();
        var fields=SteamWork.fields(level);fields.put(new RitualFields.Field(one,pos,4));fields.put(new RitualFields.Field(two,pos,4));
        var pig=EntityType.PIG.create(level);pig.setPos(Vec3.atCenterOf(pos));pig.setRemainingFireTicks(41);float health=pig.getHealth();
        pig.baseTick();
        h.assertTrue(pig.getRemainingFireTicks()==37&&pig.getHealth()==health-1,"Four burn ticks include the crossed fire damage boundary");
        h.assertTrue(pig.tickCount==0,"Burn advancement does not recursively tick AI or movement");
        fields.remove(one);fields.remove(two);
        pig.setRemainingFireTicks(41);pig.baseTick();h.assertTrue(pig.getRemainingFireTicks()==40,"Outside fields vanilla fire duration is unchanged");
        level.setBlock(pos.below(),Blocks.NETHERRACK.defaultBlockState(),3);level.setBlock(pos,Blocks.FIRE.defaultBlockState(),3);
        fields.put(new RitualFields.Field(one,pos,4));fields.put(new RitualFields.Field(two,pos,4));
        // Exercise the real FireBlock mixin; this must apply without an injection/dispatch failure.
        level.getBlockState(pos).tick(level,pos,level.random);
        h.assertTrue(level.getBlockTicks().hasScheduledTick(pos,Blocks.FIRE),"Accelerated native fire still schedules its next opportunity");
        fields.remove(one);fields.remove(two);level.removeBlock(pos,false);h.succeed();
    }
    @GameTest(templateNamespace="kaleidoscope_witchcraft",template="empty",timeoutTicks=40,batch="steam-chalk")
    public static void steamChalkCraftingAndCommittedWear(GameTestHelper h) {
        var level=h.getLevel();var input=CraftingInput.of(3,1,List.of(new ItemStack(Items.CALCITE),item("thaumcraft:salis_mundus",1),new ItemStack(Items.CLAY_BALL)));
        var recipe=level.getRecipeManager().getRecipeFor(RecipeType.CRAFTING,input,level).orElseThrow();
        ItemStack result=recipe.value().assemble(input,level.registryAccess());
        h.assertTrue(result.is(RitualContent.CHALK.get())&&result.getCount()==2&&result.getMaxStackSize()==64,
                "Recipe delivers two fresh sticks in the output slot");
        h.assertTrue(recipe.value().getRemainingItems(input).stream().allMatch(ItemStack::isEmpty),
                "No second chalk is returned into the calcite or another ingredient slot");
        var player=new FakePlayer(level,new GameProfile(UUID.randomUUID(),"steam-chalk"));player.setItemInHand(InteractionHand.MAIN_HAND,result);
        BlockPos at=h.absolutePos(new BlockPos(8,3,8));level.setBlock(at,Blocks.STONE.defaultBlockState(),3);
        var context=new UseOnContext(player,InteractionHand.MAIN_HAND,new BlockHitResult(Vec3.atCenterOf(at).add(0,0.5,0),Direction.UP,at,false));
        result.useOn(context);h.assertTrue(result.getDamageValue()==1&&result.getCount()==1&&result.getMaxStackSize()==1,
                "Committed drawing separates one native damageable stick");
        var spare=player.getInventory().items.stream().filter(s->s.is(RitualContent.CHALK.get())&&!s.isDamageableItem()).findFirst().orElseThrow();
        h.assertTrue(spare.getCount()==1,"The unused stick stays fresh in the inventory");
        var encoded=ItemStack.CODEC.encodeStart(level.registryAccess().createSerializationContext(net.minecraft.nbt.NbtOps.INSTANCE),result).getOrThrow();
        var restored=ItemStack.CODEC.parse(level.registryAccess().createSerializationContext(net.minecraft.nbt.NbtOps.INSTANCE),encoded).getOrThrow();
        h.assertTrue(restored.getDamageValue()==1&&restored.getMaxStackSize()==1,"Active chalk round trips through the native item codec");
        result.useOn(context);h.assertTrue(result.getDamageValue()==1,"Redrawing occupied mark is free");
        for(int i=1;i<64;i++){level.removeBlock(at.above(),false);result.useOn(context);}
        h.assertTrue(result.isEmpty()&&spare.getCount()==1&&!spare.isDamageableItem(),"64 successful marks consume only the active chalk");
        var goldenInput=CraftingInput.of(3,1,List.of(new ItemStack(Items.GOLD_INGOT),item("thaumcraft:salis_mundus",1),new ItemStack(Items.BLAZE_POWDER)));
        var goldenRecipe=level.getRecipeManager().getRecipeFor(RecipeType.CRAFTING,goldenInput,level).orElseThrow().value();
        var golden=goldenRecipe.assemble(goldenInput,level.registryAccess());
        h.assertTrue(golden.is(RitualContent.GOLDEN_CHALK.get())&&golden.getCount()==2
                &&goldenRecipe.getRemainingItems(goldenInput).stream().allMatch(ItemStack::isEmpty),"Golden chalk shares the native stacked output rule");
        player.setItemInHand(InteractionHand.MAIN_HAND,golden);level.removeBlock(at.above(),false);
        context=new UseOnContext(player,InteractionHand.MAIN_HAND,new BlockHitResult(Vec3.atCenterOf(at).add(0,0.5,0),Direction.UP,at,false));
        golden.useOn(context);h.assertTrue(golden.getCount()==1&&golden.getDamageValue()==1,"Golden heart drawing also separates and wears one stick");
        golden.useOn(context);h.assertTrue(golden.getDamageValue()==1,"Failed golden heart placement does not spend durability");
        h.succeed();
    }
}
