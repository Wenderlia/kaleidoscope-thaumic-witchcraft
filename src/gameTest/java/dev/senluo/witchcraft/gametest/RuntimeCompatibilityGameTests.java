package dev.senluo.witchcraft.gametest;

import com.google.common.collect.ImmutableList;
import com.mojang.serialization.Dynamic;
import dev.senluo.witchcraft.mixin.time.FurnaceAccess;
import dev.senluo.witchcraft.ritual.RitualFields;
import dev.senluo.witchcraft.ritual.SteamWork;
import dev.senluo.witchcraft.time.*;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.ai.behavior.Behavior;
import net.minecraft.world.entity.schedule.Activity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Executes the real transformed native schedulers; Lithium is optional except in its explicit case. */
@PrefixGameTestTemplate(false)
public final class RuntimeCompatibilityGameTests {
    private static final class ClockProbe extends Behavior<net.minecraft.world.entity.LivingEntity> {
        long started=Long.MIN_VALUE, ticked=Long.MIN_VALUE, stopped=Long.MIN_VALUE;
        ClockProbe() { super(Map.of(),1000); }
        @Override protected boolean canStillUse(ServerLevel level, net.minecraft.world.entity.LivingEntity entity, long time) { return true; }
        @Override protected void start(ServerLevel level, net.minecraft.world.entity.LivingEntity entity, long time) { started=time; }
        @Override protected void tick(ServerLevel level, net.minecraft.world.entity.LivingEntity entity, long time) { ticked=time; }
        @Override protected void stop(ServerLevel level, net.minecraft.world.entity.LivingEntity entity, long time) { stopped=time; }
    }

    @GameTest(templateNamespace="kaleidoscope_witchcraft",template="empty")
    public static void nativeBrainUsesLocalClockForStartTickAndStop(GameTestHelper h) {
        var level=h.getLevel();
        var entity=EntityType.PIG.create(level);
        var brainData=new CompoundTag();
        brainData.put("memories",new CompoundTag());
        var brain=Brain.<net.minecraft.world.entity.LivingEntity>provider(List.of(),List.of())
                .makeBrain(new Dynamic<>(NbtOps.INSTANCE,brainData));
        var probe=new ClockProbe();
        brain.addActivity(Activity.CORE,0,ImmutableList.of(probe));
        brain.setCoreActivities(Set.of(Activity.CORE));
        brain.setDefaultActivity(Activity.CORE);
        brain.useDefaultActivity();
        long worldTime=level.getGameTime();
        entity.getPersistentData().putLong("WitchcraftHeldTicks",7);
        brain.tick(level,entity);
        brain.stopAll(level,entity);
        h.assertTrue(probe.started==worldTime-7 && probe.ticked==worldTime-7 && probe.stopped==worldTime-7,
                "Native Brain start/tick/stop must all receive the local clock: "+probe.started+","+probe.ticked+","+probe.stopped+" world="+worldTime);
        h.assertTrue(level.getGameTime()==worldTime,"Local AI time must not modify the shared world clock");
        h.succeed();
    }

    @GameTest(templateNamespace="kaleidoscope_witchcraft",template="empty",timeoutTicks=30,batch="native-random")
    public static void nativeRandomSamplesPayOnceAndRestoreScope(GameTestHelper h) {
        var level=h.getLevel();
        var core=TreeGameTests.plant(h,true);
        core.tissue.productionMode=SupplyMode.PAUSED;
        var pos=core.getBlockPos().east();
        level.setBlockAndUpdate(pos.below(),Blocks.FARMLAND.defaultBlockState());
        level.setBlockAndUpdate(pos,Blocks.WHEAT.defaultBlockState());
        h.runAfterDelay(2,()->{
            var chunk=level.getChunkAt(pos);
            long slots=java.util.Arrays.stream(chunk.getSections()).filter(s->s.isRandomlyTicking()).count()*1000;
            long before=core.tissue.stored();
            level.tickChunk(chunk,1000);
            long gained=core.tissue.stored()-before;
            h.assertTrue(slots>0 && gained>0 && gained<=slots*220 && gained%220==0,
                    "Real empty random samples pay the tree's 0.22 TU quote: "+gained+" / "+slots);
            level.tickChunk(chunk,1000);
            h.assertTrue(core.tissue.stored()==before+gained,"Repeating the same native chunk frame cannot sell its sample slots twice");
            ExtraTicks.run(()->level.tickChunk(chunk,1000));
            h.assertTrue(core.tissue.stored()==before+gained && NativeTicks.SAMPLE.get()==null && !ExtraTicks.active(),
                    "Additional chunk callbacks do not create native sample income or leak their scope");
            h.succeed();
        });
    }

    @GameTest(templateNamespace="kaleidoscope_witchcraft",template="empty",timeoutTicks=30,batch="native-scheduled")
    public static void nativeScheduledButtonDefersAndReleasesOnce(GameTestHelper h) {
        var level=h.getLevel();
        var core=TreeGameTests.plant(h,true);
        core.tissue.giant=true;
        core.tissue.disabledSources=31 & ~(1<<3);
        core.tissue.productionMode=SupplyMode.PAUSED;
        TemporalWorld.get(level).invalidate();
        BlockPos pos=core.getBlockPos().east(3);
        level.setBlockAndUpdate(pos.below(),Blocks.STONE.defaultBlockState());
        level.setBlockAndUpdate(pos,Blocks.STONE_BUTTON.defaultBlockState()
                .setValue(ButtonBlock.FACE,AttachFace.FLOOR).setValue(ButtonBlock.POWERED,true));
        long before=core.tissue.stored();
        level.scheduleTick(pos,Blocks.STONE_BUTTON,4);
        h.runAfterDelay(5,()->h.assertTrue(level.getBlockState(pos).getValue(ButtonBlock.POWERED),
                "The native due callback must be deferred while the giant tree absorbs it"));
        h.runAfterDelay(12,()->{
            h.assertTrue(!level.getBlockState(pos).getValue(ButtonBlock.POWERED),"The deferred native button callback must eventually run");
            h.assertTrue(core.tissue.stored()-before==4*Tissue.TU,"Exactly one delayed opportunity pays 4 TU: "+(core.tissue.stored()-before));
            h.assertTrue(!level.getBlockTicks().hasScheduledTick(pos,Blocks.STONE_BUTTON),"Release must not leave a duplicate queued callback");
            h.succeed();
        });
    }

    @GameTest(templateNamespace="kaleidoscope_witchcraft",template="empty",timeoutTicks=40,batch="lithium-native")
    public static void lithiumNativeFurnaceSleepsWakesAndAdvances(GameTestHelper h) throws Exception {
        h.assertTrue(ModList.get().isLoaded("lithium"),"This case requires the actual Lithium mod to be discovered");
        h.assertTrue(ModList.get().getModContainerById("lithium").orElseThrow().getModInfo().getVersion().toString().equals("0.15.4+mc1.21.1"),
                "This case validates the reviewed Lithium 0.15.4 artifact");
        var level=h.getLevel();
        BlockPos pos=h.absolutePos(new BlockPos(5,3,5));
        level.setBlockAndUpdate(pos,Blocks.FURNACE.defaultBlockState());
        var furnace=(AbstractFurnaceBlockEntity)level.getBlockEntity(pos);
        var sleeping=Class.forName("net.caffeinemc.mods.lithium.common.block.entity.SleepingBlockEntity");
        h.assertTrue(sleeping.isInstance(furnace),"The real Lithium sleeping mixin must transform the furnace");
        var isSleeping=sleeping.getMethod("isSleeping");
        UUID field=UUID.randomUUID();
        // Persist for a few real Level ticker passes, including the transition to a null-position delegate.
        SteamWork.fields(level).put(new RitualFields.Field(field,pos,4,2));
        h.runAfterDelay(4,()->{
            try {
                h.assertTrue((boolean)isSleeping.invoke(furnace),"An idle native furnace must actually sleep");
                furnace.setItem(0,new ItemStack(Items.RAW_IRON,8));
                furnace.setItem(1,new ItemStack(Items.COAL,2));
                furnace.setChanged();
                h.assertTrue(!(boolean)isSleeping.invoke(furnace),"Inventory changes wake the real sleeping ticker");
            } catch (ReflectiveOperationException e) { throw new IllegalStateException(e); }
        });
        h.runAfterDelay(6,()->{
            var access=(FurnaceAccess)furnace;
            int progress=access.senluo$cookingProgress(), fuel=access.senluo$litTime();
            h.assertTrue(progress>0 && fuel>0,"The woken furnace resumes native cooking");
            h.runAfterDelay(1,()->{
                try {
                    h.assertTrue(access.senluo$cookingProgress()==progress+4 && access.senluo$litTime()==fuel-4,
                            "One real Level tick at 4x advances fuel and progress exactly four steps");
                    h.succeed();
                } finally { SteamWork.fields(level).remove(field); }
            });
        });
    }
}
