package dev.senluo.witchcraft.gametest;

import dev.senluo.witchcraft.time.*;
import java.util.List;
import java.util.UUID;
import com.mojang.authlib.GameProfile;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.neoforge.common.util.FakePlayer;

@PrefixGameTestTemplate(false)
public final class SideRootGameTests {
    private static LivingEntity plant(GameTestHelper h, int stage, long quantity, boolean amber) {
        BlockPos p = h.absolutePos(new BlockPos(4,3,4));
        h.getLevel().setBlock(p.below(), Blocks.FARMLAND.defaultBlockState(), 3);
        h.getLevel().setBlock(p, (amber ? TemporalContent.AMBER : TemporalContent.RHIZOME).get().defaultBlockState(), 3);
        LivingEntity entity = (LivingEntity) h.getLevel().getBlockEntity(p);
        entity.tissue.rootStage = stage; entity.tissue.setCoreQuantity(quantity); entity.changed();
        TemporalWorld.get(h.getLevel()).add(entity);
        return entity;
    }
    private static Colony colony(GameTestHelper h, LivingEntity entity) { return TemporalWorld.get(h.getLevel()).colony(entity); }
    private static Tissue.Side mature(int stage) {
        Tissue.Side side = new Tissue.Side(stage);
        side.tissue.growth.invested = Tissue.SIDE_FEE[stage]; side.tissue.growth.ticks = Tissue.SIDE_TIME[stage];
        return side;
    }
    private static ItemInteractionResult use(GameTestHelper h, LivingEntity entity, Player player, ItemStack stack) {
        player.setItemInHand(InteractionHand.MAIN_HAND, stack);
        BlockPos p = entity.getBlockPos();
        return entity.getBlockState().useItemOn(stack, h.getLevel(), player, InteractionHand.MAIN_HAND,
                new BlockHitResult(Vec3.atCenterOf(p), Direction.UP, p, false));
    }
    private static List<Tissue> drops(GameTestHelper h, BlockPos pos) {
        return h.getLevel().getEntitiesOfClass(ItemEntity.class, new AABB(pos).inflate(2)).stream()
                .map(ItemEntity::getItem).filter(s -> s.is(TemporalContent.TISSUE.get()))
                .map(s -> TissueTag.load(s.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag())).toList();
    }
    private static void breakBlock(GameTestHelper h, LivingEntity entity, Player player) {
        var state = entity.getBlockState();
        state.getBlock().playerWillDestroy(h.getLevel(), entity.getBlockPos(), state, player);
        h.getLevel().destroyBlock(entity.getBlockPos(), true, player);
    }

    @GameTest(templateNamespace="kaleidoscope_witchcraft", template="empty", timeoutTicks=100)
    public static void clockCultivationPaysOnceAndHarvestWaitsForMaturity(GameTestHelper h) {
        LivingEntity e = plant(h, 2, 1000*Tissue.TU, false);
        Player player = h.makeMockPlayer(GameType.SURVIVAL);
        ItemStack clock = new ItemStack(Items.CLOCK,2);
        use(h,e,player,clock);
        Tissue.Side side = e.tissue.selectedSide();
        h.assertTrue(side != null && side.active() && side.remainingFee()==0 && side.remainingTicks()==9000, "Clock pays development without skipping its time");
        h.assertTrue(e.tissue.stored()==700*Tissue.TU && clock.getCount()==1, "Creating one side consumes one clock and the shared development fee");
        for(int i=0;i<5;i++) use(h,e,player,clock);
        h.assertTrue(e.tissue.sides.size()==1 && clock.getCount()==1 && e.tissue.stored()==700*Tissue.TU && !side.mature(), "Continuing cultivation cannot consume another clock, charge twice or mature a side instantly");
        h.assertTrue(colony(h,e).harvest(e)==null, "A fully paid but immature side cannot be picked");
        side.tissue.growth.ticks=8980;
        h.runAfterDelay(25,()-> {
            h.assertTrue(side.mature() && side.stored()<side.capacity(), "Development completes independently of how full the side is");
            h.assertTrue(e.tissue.selectedSide()==side, "The cultivated side must remain selected; index="+e.tissue.selectedSide+", sides="+e.tissue.sides.size());
            long total=e.tissue.stored();
            Tissue picked=colony(h,e).harvest(e);
            h.assertTrue(picked!=null && picked.stored()+e.tissue.stored()==total, "Mature partial storage can be picked without loss or duplication: before="+total+", remaining="+e.tissue.stored()+", picked="+(picked==null?"null":picked.stored()));
            h.succeed();
        });
    }

    @GameTest(templateNamespace="kaleidoscope_witchcraft", template="empty", timeoutTicks=80)
    public static void nonNaturalIncomeFundsSideStorageAndDevelopment(GameTestHelper h) {
        LivingEntity e=plant(h,1,0,false);e.tissue.productionMode=3;
        ItemStack clock=new ItemStack(Items.CLOCK);
        use(h,e,h.makeMockPlayer(GameType.SURVIVAL),clock);
        h.assertTrue(clock.isEmpty() && e.tissue.sides.size()==1,"A clock creates the side before later income supplies it");
        h.assertTrue(e.tissue.credit(1000*Tissue.TU), "Manual source must enter the physical balance pool");
        e.received(1000*Tissue.TU,false);
        h.runAfterDelay(25,()-> {
            Tissue.Side side=e.tissue.sides.getFirst();
            h.assertTrue(side.remainingFee()==0 && side.stored()>0, "Non-natural income must fund growth and share current side capacity");
            h.assertTrue(e.tissue.stored()==850*Tissue.TU, "Only the real 150 TU development fee is spent; manual income grants no crop-output quota");
            h.succeed();
        });
    }

    @GameTest(templateNamespace="kaleidoscope_witchcraft", template="empty", timeoutTicks=60)
    public static void graftAndHarvestPreserveNestedStagesAndBalances(GameTestHelper h) {
        LivingEntity e=plant(h,2,2222*Tissue.TU,false);
        Tissue incoming=new Tissue();incoming.rootStage=1;incoming.amberStage=1;incoming.training=true;incoming.trainingTicks=1234;incoming.setCoreQuantity(400*Tissue.TU);
        Tissue.Side inner=mature(1);inner.locked=true;inner.tissue.setCoreQuantity(17*Tissue.TU);incoming.sides.add(inner);
        long capacity=incoming.capacity();
        long total=e.tissue.stored()+incoming.stored();
        Player player=h.makeMockPlayer(GameType.SURVIVAL);
        ItemStack item=TissueItem.stack(incoming);
        use(h,e,player,item);
        h.assertTrue(item.isEmpty() && e.tissue.sides.size()==1 && e.tissue.stored()==total, "Whole graft consumes one item and moves its balance exactly once");
        Tissue roundTrip=TissueTag.load(TissueTag.save(e.tissue));e.tissue=roundTrip;
        Tissue detached=colony(h,e).harvest(e);
        h.assertTrue(detached!=null && detached.rootStage==1 && detached.amberStage==1 && detached.trainingTicks==1234, "Grafted development and amber training must survive attachment, saving and detachment");
        h.assertTrue(detached.capacity()==capacity && detached.sides.size()==1 && detached.reserved()==17*Tissue.TU, "Nested structures and their reservations survive as a complete tissue");
        h.assertTrue(e.tissue.stored()+detached.stored()==total, "Grafting changes ownership, not total time or natural-output quota");
        h.succeed();
    }

    @GameTest(templateNamespace="kaleidoscope_witchcraft", template="empty", timeoutTicks=60)
    public static void graftTransferDoesNotBecomeNewCultivationIncome(GameTestHelper h) {
        // Local-stage absorption keeps this accounting check independent of other tests'
        // explicit chunk ticks. An unfinished graft exposes accidental cultivation income.
        LivingEntity e=plant(h,1,1000*Tissue.TU,false);
        Tissue incoming=new Tissue.Side(1).tissue;incoming.setCoreQuantity(400*Tissue.TU);
        incoming.growth.invested=75*Tissue.TU;incoming.growth.ticks=3000;
        h.assertTrue(colony(h,e).graft(e,incoming), "An empty side slot accepts an existing tissue");
        h.runAfterDelay(25,()-> {
            h.assertTrue(e.tissue.sides.size()==1 && e.tissue.stored()==1400*Tissue.TU && incoming.growth.invested==75*Tissue.TU,
                    "Grafting existing storage must not spend it as new cultivation income: stock="+e.tissue.stored()+", invested="+incoming.growth.invested);
            h.succeed();
        });
    }

    @GameTest(templateNamespace="kaleidoscope_witchcraft", template="empty", timeoutTicks=60)
    public static void crouchingVanillaItemsReachTheRealBlockUseRoute(GameTestHelper h) {
        LivingEntity e=plant(h,2,4000*Tissue.TU,false);
        Tissue.Side existing=mature(1);e.tissue.sides.add(existing);
        Tissue.Side second=mature(1);e.tissue.sides.add(second);
        FakePlayer player=new FakePlayer(h.getLevel(),new GameProfile(UUID.randomUUID(),"side-root-user"));
        BlockPos p=e.getBlockPos();
        player.setPos(p.getX()+2,p.getY(),p.getZ()+2);
        BlockHitResult hit=new BlockHitResult(Vec3.atCenterOf(p),Direction.UP,p,false);
        ItemStack clock=new ItemStack(Items.CLOCK,2);
        player.setItemInHand(InteractionHand.MAIN_HAND,clock);
        player.gameMode.useItemOn(player,h.getLevel(),clock,InteractionHand.MAIN_HAND,hit);
        h.assertTrue(clock.getCount()==2 && e.tissue.sides.size()==2 && e.tissue.selectedSide()==existing,
                "Inspecting a mature side with a clock cannot create another side or consume a clock while a free slot exists");
        player.setShiftKeyDown(true);
        player.gameMode.useItemOn(player,h.getLevel(),clock,InteractionHand.MAIN_HAND,hit);
        h.assertTrue(e.tissue.selectedSide()==second && e.tissue.selectedSideIndex()==1,
                "Crouching selects the second existing root, never a synthetic empty slot");
        var focus=(TranslatableContents)LivingBlock.sideFocusMessage(e).getContents();
        h.assertTrue(((Component)focus.getArgs()[0]).getString().equals("2"), "Selection feedback names the root just selected");
        player.setShiftKeyDown(false);
        player.gameMode.useItemOn(player,h.getLevel(),clock,InteractionHand.MAIN_HAND,hit);
        h.assertTrue(e.tissue.sides.size()==2 && e.tissue.selectedSide()==second, "Clock inspection cannot create or cultivate tissue");
        player.setShiftKeyDown(true);
        player.gameMode.useItemOn(player,h.getLevel(),clock,InteractionHand.MAIN_HAND,hit);
        h.assertTrue(e.tissue.selectedSide()==existing,"Crouching must cycle back to the mature side");
        ItemStack gold=new ItemStack(Items.GOLD_NUGGET);
        player.setItemInHand(InteractionHand.MAIN_HAND,gold);player.setShiftKeyDown(false);
        player.gameMode.useItemOn(player,h.getLevel(),gold,InteractionHand.MAIN_HAND,hit);
        h.assertTrue(existing.locked,"Normal gold use precharges the selected mature side");
        player.setShiftKeyDown(true);
        player.gameMode.useItemOn(player,h.getLevel(),gold,InteractionHand.MAIN_HAND,hit);
        h.assertTrue(!existing.locked && clock.getCount()==2 && gold.getCount()==1,
                "Selection and precharge control must not consume another clock or gold nugget");
        player.setItemInHand(InteractionHand.MAIN_HAND,ItemStack.EMPTY);
        player.setItemInHand(InteractionHand.OFF_HAND,new ItemStack(Items.STICK));
        player.gameMode.useItemOn(player,h.getLevel(),ItemStack.EMPTY,InteractionHand.MAIN_HAND,hit);
        h.assertTrue(e.tissue.productionMode==1,"An occupied off hand must not trigger the two-empty-hands shortcut");
        h.succeed();
    }

    @GameTest(templateNamespace="kaleidoscope_witchcraft", template="empty", timeoutTicks=60)
    public static void emptyHandShowsOnlyCoreAndSelectedSideState(GameTestHelper h) {
        LivingEntity e=plant(h,1,200*Tissue.TU,false);
        Tissue.Side side=mature(1);side.tissue.setCoreQuantity(100*Tissue.TU);e.tissue.sides.add(side);
        FakePlayer player=new FakePlayer(h.getLevel(),new GameProfile(UUID.randomUUID(),"root-inspect"));
        BlockPos p=e.getBlockPos();
        player.gameMode.useItemOn(player,h.getLevel(),ItemStack.EMPTY,InteractionHand.MAIN_HAND,
                new BlockHitResult(Vec3.atCenterOf(p),Direction.UP,p,false));
        h.assertTrue(e.tissue.sides.size()==1 && e.tissue.stored()==300*Tissue.TU,"Reading with an empty hand cannot harvest or move stored time");
        Component summary=LivingBlock.stockMessage(e);
        var rootText=(TranslatableContents)summary.getContents();
        h.assertTrue(((Component)rootText.getArgs()[0]).getString().equals("200") && ((Component)rootText.getArgs()[1]).getString().equals("6000"),"Summary must show the core, not aggregate side storage");
        h.assertTrue(summary.getSiblings().size()==1,"Summary includes only the selected side's short state");
        var sideText=(TranslatableContents)summary.getSiblings().getFirst().getContents();
        Component status=(Component)sideText.getArgs()[1];
        h.assertTrue(status.getStyle().getColor().getValue()==ChatFormatting.GREEN.getColor(),"Maturity must be visible in green");
        side.tissue.growth.invested=0;
        h.assertTrue(((TranslatableContents)((Component)((TranslatableContents)LivingBlock.stockMessage(e).getSiblings().getFirst().getContents()).getArgs()[1]).getContents()).getKey().endsWith("side_unfunded"),"A side lacking investment must say it needs time");
        h.assertTrue(LivingBlock.formatHudTime(480000*Tissue.TU).equals("480k") && LivingBlock.formatHudTime(123456).equals("123.456"),"HUD abbreviates large stocks without erasing small balances");
        h.succeed();
    }

    @GameTest(templateNamespace="kaleidoscope_witchcraft", template="empty", timeoutTicks=80)
    public static void fullStorageCannotDifferentiateWithoutAClock(GameTestHelper h) {
        LivingEntity e=plant(h,1,6000*Tissue.TU,false);e.received(1000*Tissue.TU,false);
        h.runAfterDelay(25,()-> {
            h.assertTrue(e.tissue.sides.isEmpty() && e.tissue.stored()==6000*Tissue.TU,"Full storage and new income cannot create a side or spend its fee automatically");
            ItemStack clock=new ItemStack(Items.CLOCK);
            use(h,e,h.makeMockPlayer(GameType.SURVIVAL),clock);
            h.assertTrue(clock.isEmpty() && e.tissue.sides.size()==1 && e.tissue.stored()==5850*Tissue.TU,"Clock use creates exactly one side and pays once");
            h.succeed();
        });
    }

    @GameTest(templateNamespace="kaleidoscope_witchcraft", template="empty", timeoutTicks=60)
    public static void rejectedClockUseNeverConsumesTheClock(GameTestHelper h) {
        LivingEntity e=plant(h,0,0,false);Player player=h.makeMockPlayer(GameType.SURVIVAL);
        ItemStack clock=new ItemStack(Items.CLOCK,2);
        use(h,e,player,clock);
        e.tissue.rootStage=1;e.tissue.sides.add(mature(1));e.changed();
        use(h,e,player,clock);
        e.tissue.rootStage=2;e.tissue.bond=dev.senluo.witchcraft.ritual.TreeBond.of(UUID.randomUUID(), "");e.changed();
        use(h,e,player,clock);
        h.assertTrue(clock.getCount()==2 && e.tissue.sides.size()==1,"Too-young, full-slot and ownership failures must preserve clocks");
        h.succeed();
    }

    @GameTest(templateNamespace="kaleidoscope_witchcraft", template="empty", timeoutTicks=60)
    public static void shovelHarvestsOnlyMatureSidesAndUsesOneDurability(GameTestHelper h) {
        LivingEntity e=plant(h,1,100*Tissue.TU,false);Tissue.Side side=new Tissue.Side(1);e.tissue.sides.add(side);
        FakePlayer player=new FakePlayer(h.getLevel(),new GameProfile(UUID.randomUUID(),"root-harvest"));
        ItemStack shovel=new ItemStack(Items.IRON_SHOVEL);player.setItemInHand(InteractionHand.MAIN_HAND,shovel);
        BlockPos p=e.getBlockPos();BlockHitResult hit=new BlockHitResult(Vec3.atCenterOf(p),Direction.UP,p,false);
        player.gameMode.useItemOn(player,h.getLevel(),shovel,InteractionHand.MAIN_HAND,hit);
        h.assertTrue(e.tissue.sides.size()==1 && shovel.getDamageValue()==0,"Immature sides cannot be harvested and do not wear the shovel");
        side.tissue.growth.invested=Tissue.SIDE_FEE[1];side.tissue.growth.ticks=Tissue.SIDE_TIME[1];
        player.setShiftKeyDown(true);
        player.gameMode.useItemOn(player,h.getLevel(),shovel,InteractionHand.MAIN_HAND,hit);
        ItemStack picked=player.getInventory().items.stream().filter(s->s.is(TemporalContent.TISSUE.get())).findFirst().orElse(ItemStack.EMPTY);
        h.assertTrue(e.tissue.sides.isEmpty() && shovel.getDamageValue()==1 && !picked.isEmpty(),"A shovel harvests one mature, partially filled side and loses one durability");
        Tissue tissue=TissueTag.load(picked.getOrDefault(DataComponents.CUSTOM_DATA,CustomData.EMPTY).copyTag());
        h.assertTrue(e.tissue.stored()+tissue.stored()==100*Tissue.TU,"Harvesting preserves time and does not require a full side");
        player.gameMode.useItemOn(player,h.getLevel(),shovel,InteractionHand.MAIN_HAND,hit);
        h.assertTrue(shovel.getDamageValue()==1,"An empty slot cannot wear the shovel again");
        h.succeed();
    }

    @GameTest(templateNamespace="kaleidoscope_witchcraft", template="empty", timeoutTicks=60)
    public static void immatureAmberCannotSpendOnTrainingThatWouldBeLostOnReload(GameTestHelper h) {
        LivingEntity e=plant(h,0,1000*Tissue.TU,true);
        e.tissue.growth=new Tissue.Growth(1);e.tissue.growth.invested=75*Tissue.TU;e.tissue.growth.ticks=3000;
        Player player=h.makeMockPlayer(GameType.SURVIVAL);
        use(h,e,player,new ItemStack(Items.BONE_MEAL));
        Tissue saved=TissueTag.load(TissueTag.save(e.tissue));
        h.assertTrue(!e.tissue.training && !saved.training && saved.stored()==1000*Tissue.TU,
                "An unfinished tissue must complete its own growth before paying for permanent amber capacity");
        h.succeed();
    }

    @GameTest(templateNamespace="kaleidoscope_witchcraft", template="empty", timeoutTicks=80)
    public static void supplySpendsExistingStockAndKeepsIdleIncome(GameTestHelper h) {
        LivingEntity e=plant(h,4,1000*Tissue.TU,false);
        e.tissue.productionMode=dev.senluo.witchcraft.time.SupplyMode.NORMAL;
        BlockPos crop=e.getBlockPos().east();
        h.getLevel().setBlock(crop.below(),Blocks.FARMLAND.defaultBlockState(),3);
        h.getLevel().setBlock(crop,Blocks.WHEAT.defaultBlockState(),3);
        TemporalWorld world=TemporalWorld.get(h.getLevel());world.invalidateConnections();
        Colony group=world.colony(e);
        for(int i=0;i<10;i++)world.production.flush(java.util.List.of(e));
        h.assertTrue(e.tissue.stored()==997*Tissue.TU,"One paid attempt uses older stock without income authorization");
        h.getLevel().setBlock(crop,Blocks.AIR.defaultBlockState(),3);
        h.assertTrue(group.credit(12*Tissue.TU,e),"New income is stored exactly once");
        for(int i=0;i<20;i++)world.production.flush(java.util.List.of(e));
        h.assertTrue(e.tissue.stored()==1009*Tissue.TU,"No target means no spending or lost stock");
        h.succeed();
    }

    @GameTest(templateNamespace="kaleidoscope_witchcraft", template="empty", timeoutTicks=65)
    public static void amberSupplyChargesPerAttemptAndLeavesUnaffordableStock(GameTestHelper h) {
        LivingEntity e=plant(h,0,9*Tissue.TU,true);e.tissue.productionMode=3;
        BlockPos crop=e.getBlockPos().east();
        h.getLevel().setBlock(crop.below(),Blocks.FARMLAND.defaultBlockState(),3);
        h.getLevel().setBlock(crop,Blocks.WHEAT.defaultBlockState(),3);
        h.getLevel().setBlock(crop.above(),Blocks.GLOWSTONE.defaultBlockState(),3);
        h.runAfterDelay(25,()-> {
            h.assertTrue(e.tissue.stored()==Tissue.TU,"Untrained amber spends 8 TU for one attempt and preserves an unaffordable remainder");
            e.tissue.amberStage=3;e.tissue.setCoreQuantity(3*Tissue.TU);e.changed();
        });
        h.runAfterDelay(30,()-> {
            h.assertTrue(e.tissue.stored()==Tissue.TU,"Trained amber spends 2 TU per attempt even at the higher output rate");
            h.getLevel().setBlock(crop,Blocks.AIR.defaultBlockState(),3);e.tissue.setCoreQuantity(10*Tissue.TU);
        });
        h.runAfterDelay(55,()-> {
            h.assertTrue(e.tissue.stored()==10*Tissue.TU,"No eligible crop means no charge, even with available output tokens");
            h.succeed();
        });
    }

    @GameTest(templateNamespace="kaleidoscope_witchcraft", template="empty", timeoutTicks=60)
    public static void fullOrForeignGraftDoesNotConsumeAndOrdinaryItemsDoNotHarvest(GameTestHelper h) {
        LivingEntity e=plant(h,1,600*Tissue.TU,false);e.tissue.sides.add(mature(1));
        Player player=h.makeMockPlayer(GameType.SURVIVAL);
        ItemStack item=TissueItem.stack(new Tissue());
        use(h,e,player,item);
        h.assertTrue(item.getCount()==1 && e.tissue.sides.size()==1 && e.tissue.stored()==600*Tissue.TU, "Full slots reject grafts without consuming tissue or harvesting a side");
        e.tissue.bond=dev.senluo.witchcraft.ritual.TreeBond.of(UUID.randomUUID(), "");
        use(h,e,player,new ItemStack(Items.CLOCK));
        h.assertTrue(e.tissue.stored()==600*Tissue.TU, "Clock cultivation cannot bypass ownership");
        e.tissue.bond=dev.senluo.witchcraft.ritual.TreeBond.of(player.getUUID(), "");
        var result=use(h,e,player,new ItemStack(Items.STICK));
        h.assertTrue(result==ItemInteractionResult.SKIP_DEFAULT_BLOCK_INTERACTION && e.tissue.sides.size()==1, "Holding an unrelated item must not fall through to empty-hand harvesting");
        h.succeed();
    }

    @GameTest(templateNamespace="kaleidoscope_witchcraft", template="empty", timeoutTicks=60)
    public static void normalBreakSplitsEveryPartAndKeepsImmatureProgress(GameTestHelper h) {
        LivingEntity e=plant(h,2,1000*Tissue.TU,false);
        Tissue.Side side=mature(1);side.tissue.sides.add(new Tissue.Side(1));e.tissue.sides.add(side);
        side.tissue.sides.getFirst().tissue.growth.invested=40*Tissue.TU;
        side.tissue.sides.getFirst().tissue.growth.ticks=1200;
        long total=e.tissue.stored(), capacity=e.tissue.capacity();BlockPos pos=e.getBlockPos();
        breakBlock(h,e,h.makeMockPlayer(GameType.SURVIVAL));
        List<Tissue> dropped=drops(h,pos);
        h.assertTrue(dropped.size()==3 && dropped.stream().allMatch(t -> t.sides.isEmpty()), "Ordinary breaking drops all cores and nested sides separately");
        h.assertTrue(dropped.stream().mapToLong(Tissue::stored).sum()==total && dropped.stream().mapToLong(Tissue::capacity).sum()==capacity, "Splitting drops conserves inventory and actual capacity");
        Tissue immature=dropped.stream().filter(t -> !t.established()).findFirst().orElseThrow();
        h.assertTrue(immature.growth.invested==40*Tissue.TU && immature.growth.ticks==1200 && immature.coreCapacity()<1200*Tissue.TU, "Breaking cannot turn unfinished development into a mature seed");
        h.succeed();
    }

    @GameTest(templateNamespace="kaleidoscope_witchcraft", template="empty", timeoutTicks=60)
    public static void breakingAConnectedMotherConservesFractionalBalances(GameTestHelper h) {
        LivingEntity e=plant(h,1,13,false);e.tissue.sides.add(mature(1));
        BlockPos neighbor=e.getBlockPos().east();
        h.getLevel().setBlock(neighbor.below(),Blocks.FARMLAND.defaultBlockState(),3);
        h.getLevel().setBlock(neighbor,TemporalContent.RHIZOME.get().defaultBlockState(),3);
        LivingEntity other=(LivingEntity)h.getLevel().getBlockEntity(neighbor);
        other.tissue.rootStage=1;other.changed();TemporalWorld.get(h.getLevel()).add(other);
        Colony group=colony(h,e);
        h.assertTrue(group.members.size()==2 && group.quantity()==13,"Both physical plants must participate in the same fractional balance");
        BlockPos pos=e.getBlockPos();breakBlock(h,e,h.makeMockPlayer(GameType.SURVIVAL));
        List<Tissue> dropped=drops(h,pos);
        h.assertTrue(dropped.size()==2 && dropped.stream().mapToLong(Tissue::stored).sum()+other.tissue.stored()==13,
                "Removing a connected mother must not redistribute rounded balances after its drops were copied");
        h.succeed();
    }

    @GameTest(templateNamespace="kaleidoscope_witchcraft", template="empty", timeoutTicks=60)
    public static void silkTouchKeepsWholeTreeInOneDrop(GameTestHelper h) {
        LivingEntity e=plant(h,2,900*Tissue.TU,false);e.tissue.sides.add(mature(1));e.tissue.sides.getFirst().tissue.sides.add(new Tissue.Side(1));
        Player player=h.makeMockPlayer(GameType.SURVIVAL);ItemStack pick=new ItemStack(Items.IRON_PICKAXE);
        pick.enchant(h.getLevel().registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.SILK_TOUCH),1);
        player.setItemInHand(InteractionHand.MAIN_HAND,pick);BlockPos pos=e.getBlockPos();long total=e.tissue.stored();
        breakBlock(h,e,player);
        List<Tissue> dropped=drops(h,pos);
        h.assertTrue(dropped.size()==1 && dropped.getFirst().nodeCount()==3 && dropped.getFirst().stored()==total, "Silk Touch preserves the entire tree in exactly one drop");
        h.succeed();
    }

    @GameTest(templateNamespace="kaleidoscope_witchcraft", template="empty", timeoutTicks=60)
    public static void amberMiningMatchesIronOreButBareHandsStillDropTissue(GameTestHelper h) {
        LivingEntity e=plant(h,0,321*Tissue.TU,true);Player player=h.makeMockPlayer(GameType.SURVIVAL);
        var amber=e.getBlockState();var iron=Blocks.IRON_ORE.defaultBlockState();
        for(ItemStack tool:List.of(ItemStack.EMPTY,new ItemStack(Items.WOODEN_PICKAXE),new ItemStack(Items.STONE_PICKAXE),new ItemStack(Items.IRON_PICKAXE))) {
            player.setItemInHand(InteractionHand.MAIN_HAND,tool);
            h.assertTrue(player.hasCorrectToolForDrops(amber)==player.hasCorrectToolForDrops(iron), "Amber must use iron ore's correct-tool tier");
            h.assertTrue(Math.abs(amber.getDestroyProgress(player,h.getLevel(),e.getBlockPos())-iron.getDestroyProgress(player,h.getLevel(),e.getBlockPos()))<0.000001F, "Each tested tool must mine amber at iron ore's speed");
        }
        player.setItemInHand(InteractionHand.MAIN_HAND,ItemStack.EMPTY);BlockPos pos=e.getBlockPos();breakBlock(h,e,player);
        var dropped=drops(h,pos);
        h.assertTrue(dropped.size()==1 && dropped.getFirst().stored()==321*Tissue.TU, "Wrong-tool and bare-hand breaking still return the stored amber tissue");
        h.runAfterDelay(2,()-> {h.assertTrue(h.getLevel().getBlockState(pos).is(Blocks.DEEPSLATE),"The host stone is restored exactly once");h.succeed();});
    }

    @GameTest(templateNamespace="kaleidoscope_witchcraft", template="empty", timeoutTicks=60)
    public static void releasingOuterPrechargePreservesInnerReservations(GameTestHelper h) {
        LivingEntity e=plant(h,2,4000*Tissue.TU,false);
        Tissue child=new Tissue();child.rootStage=1;Tissue.Side inner=mature(1);inner.locked=true;inner.tissue.setCoreQuantity(17*Tissue.TU);child.sides.add(inner);
        Colony c=colony(h,e);c.graft(e,child);long total=c.quantity();
        h.assertTrue(c.chargeSide(e) && c.quantity()==total,"Outer precharge moves existing balances only");
        h.assertTrue(c.releaseSide(e) && c.quantity()==total && e.tissue.reserved()==17*Tissue.TU,"Unlocking the selected branch preserves independent inner locks and total time");
        h.succeed();
    }
}
