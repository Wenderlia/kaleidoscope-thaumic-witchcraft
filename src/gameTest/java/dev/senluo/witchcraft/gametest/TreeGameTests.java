package dev.senluo.witchcraft.gametest;

import dev.senluo.witchcraft.time.*;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.fml.ModList;

@PrefixGameTestTemplate(false)
public final class TreeGameTests {
    @GameTest(templateNamespace="kaleidoscope_witchcraft",template="empty",timeoutTicks=60)
    public static void yearsLeavesUseVanillaHarvestGate(GameTestHelper h) {
        var state=TemporalContent.TIME_LEAVES.get().defaultBlockState();
        var pos=h.absolutePos(new BlockPos(5,3,5));
        ItemStack shears=new ItemStack(Items.SHEARS);
        ItemStack silk=new ItemStack(Items.IRON_HOE);
        silk.enchant(h.getLevel().registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.ENCHANTMENT)
                .getOrThrow(net.minecraft.world.item.enchantment.Enchantments.SILK_TOUCH),1);
        for (ItemStack tool:List.of(shears,silk)) {
            var drops=net.minecraft.world.level.block.Block.getDrops(state,h.getLevel(),pos,null,null,tool);
            h.assertTrue(drops.size()==1 && drops.getFirst().is(TemporalContent.LEAVES_ITEM.get()) && drops.getFirst().getCount()==1,
                    "Shears and Silk Touch each drop exactly one leaf block");
        }
        ItemStack fortune=new ItemStack(Items.DIAMOND_HOE);
        fortune.enchant(h.getLevel().registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.ENCHANTMENT)
                .getOrThrow(net.minecraft.world.item.enchantment.Enchantments.FORTUNE),3);
        for(ItemStack tool:List.of(ItemStack.EMPTY,new ItemStack(Items.IRON_HOE),fortune)) {
            var drops=net.minecraft.world.level.block.Block.getDrops(state,h.getLevel(),pos,null,null,tool);
            h.assertTrue(drops.stream().allMatch(s->s.is(Items.STICK) && s.getCount()>=1 && s.getCount()<=2),
                    "Ordinary and Fortune harvesting cannot drop leaves or an unrelated sapling/fruit");
        }
        h.assertTrue(shears.getDestroySpeed(state)==shears.getDestroySpeed(Blocks.OAK_LEAVES.defaultBlockState()),
                "Shears mine these leaves at vanilla leaf speed");
        h.succeed();
    }
    static LivingEntity plant(GameTestHelper h, boolean core) {
        h.getLevel().getGameRules().getRule(GameRules.RULE_RANDOMTICKING).set(0,h.getLevel().getServer());
        BlockPos pos = h.absolutePos(new BlockPos(5,3,5));
        h.getLevel().setBlock(pos.below(),Blocks.FARMLAND.defaultBlockState(),3);
        h.getLevel().setBlock(pos,(core?TemporalContent.CORE:TemporalContent.RHIZOME).get().defaultBlockState(),3);
        LivingEntity entity=(LivingEntity)h.getLevel().getBlockEntity(pos);
        entity.tissue.rootStage=4; entity.tissue.tree=core; entity.changed();
        TemporalWorld.get(h.getLevel()).add(entity);
        return entity;
    }
    static Tissue.Side side(int stage) {
        Tissue tissue=new Tissue(); tissue.rootStage=stage;
        return Tissue.Side.graft(tissue);
    }
    static void crown(GameTestHelper h, LivingEntity core) {
        for(var part:TreeLogic.shape(core.getBlockPos()).entrySet()) h.getLevel().setBlock(part.getKey(),part.getValue(),3);
    }
    static FruitEntity fruit(GameTestHelper h,LivingEntity core,Tissue.Side side) {
        core.tissue.sides.add(side);
        return nodeFruit(h,core,1);
    }
    static FruitEntity nodeFruit(GameTestHelper h,LivingEntity core,int grade) {
        var trees=TemporalWorld.get(h.getLevel()).trees;
        if (!h.getLevel().getBlockState(core.getBlockPos().above()).is(TreeWood.LOGS)) crown(h,core);
        BlockPos source=BlockPos.ZERO;
        if (grade < 5) {
            source=null;
            for(int y=1;y<=3;y++) {
                BlockPos candidate=new BlockPos(0,y,0);
                var state=h.getLevel().getBlockState(core.getBlockPos().offset(candidate));
                int existing=FruitWoods.nodeGrade(state);
                if ((existing==0 || existing==grade) && trees.fruits(core).stream().noneMatch(f->candidate.equals(f.source))) {
                    source=candidate; break;
                }
            }
            h.assertTrue(source!=null,"Fixture needs a free source node");
            h.getLevel().setBlock(core.getBlockPos().offset(source),FruitWoods.FAMILIES.get(grade-1).node().get().defaultBlockState(),3);
        }
        // Connection discovery publishes node facts during flush; the next tick queues their fruit search.
        for(int i=0;i<40;i++) { trees.tick(core); trees.flush(); }
        BlockPos expected=source;
        return trees.fruits(core).stream().filter(f->expected.equals(f.source) && f.targetGrade==grade)
                .findFirst().orElseThrow(()->new AssertionError("No live fruit for current node "+expected+" grade "+grade));
    }

    @GameTest(templateNamespace="kaleidoscope_witchcraft",template="empty",timeoutTicks=80)
    public static void naturalTreeWaitsForSpaceAndPreservesTissue(GameTestHelper h) {
        LivingEntity crop = plant(h, false);
        var level = h.getLevel();
        var trees = TemporalWorld.get(level).trees;
        var saved = crop.tissue;
        saved.setCoreQuantity(100 * Tissue.TU);
        Tissue.Side attached = side(1);
        saved.sides.add(attached);
        level.setBlock(crop.getBlockPos().below(), Blocks.DIRT.defaultBlockState(), 3);
        saved.naturalTicks = TreeBalance.ENTRY_TICKS - 1;
        h.assertFalse(trees.begin(crop), "An immature woody crop must wait for natural age");
        DevelopmentTime.advanceStructure(crop, 1);
        h.assertTrue(trees.begin(crop) && trees.begin(crop) && saved.stored() == 100 * Tissue.TU,
                "Natural age starts one transition without charging the organism");
        BlockPos obstacle = crop.getBlockPos().above(3).east();
        level.setBlock(obstacle, Blocks.STONE.defaultBlockState(), 3);
        h.assertFalse(trees.form(crop), "First formation waits until its footprint is clear");
        h.assertTrue(level.getBlockState(obstacle).is(Blocks.STONE)
                && TissueItem.read(TissueItem.stack(saved)).treeTicks == TreeBalance.ENTRY_TICKS,
                "The occupied block and waiting progress survive");
        level.removeBlock(obstacle, false);
        h.assertTrue(trees.form(crop), "Removing the obstruction permits the same transition");
        LivingEntity core = (LivingEntity) level.getBlockEntity(crop.getBlockPos());
        h.assertTrue(core.tree() && core.tissue == saved && saved.sides.contains(attached)
                && saved.stored() == 100 * Tissue.TU, "Formation keeps the organism, side and balance");
        h.assertTrue(level.getEntitiesOfClass(ItemEntity.class, new AABB(core.getBlockPos()).inflate(2)).isEmpty(),
                "Transforming must not drop a second tissue");
        h.succeed();
    }

    @GameTest(templateNamespace="kaleidoscope_witchcraft",template="empty",timeoutTicks=80)
    public static void treeSkipsSealedRoomsAndRegrowsThroughOpenings(GameTestHelper h) {
        LivingEntity core = plant(h, true);
        crown(h, core);
        core.tissue.setCoreQuantity(1000 * Tissue.TU);
        var level = h.getLevel();
        BlockPos room = core.getBlockPos().offset(1,3,0);
        h.assertTrue(level.getBlockState(room).is(TemporalContent.TIME_LEAVES.get()), "Use a natural leaf");
        long balance = core.tissue.stored();
        h.startSequence()
                .thenExecuteAfter(4, () -> {
                    for (Direction face : Direction.values()) level.setBlock(room.relative(face), Blocks.STONE.defaultBlockState(), 3);
                    level.removeBlock(room, false);
                })
                .thenExecuteAfter(4, () -> {
                    h.assertTrue(level.getBlockState(room).isAir() && core.tissue.stored() == balance,
                            "A closed room stays empty and costs nothing");
                    level.removeBlock(room.west(), false);
                })
                .thenWaitUntil(() -> {
                    h.assertTrue(level.getBlockState(room).is(TemporalContent.TIME_LEAVES.get())
                            && level.getBlockState(room.west()).is(TreeWood.LOGS), "Reopening admits the log and leaf");
                    h.assertTrue(core.tissue.stored() == balance - TreeBalance.LOG_REPAIR - TreeBalance.LEAF_REPAIR,
                            "Only the restored log and leaf are charged");
                }).thenSucceed();
    }

    @GameTest(templateNamespace="kaleidoscope_witchcraft",template="empty",timeoutTicks=80)
    public static void amberFruitResumesOnlyItsReturnedCore(GameTestHelper h) {
        LivingEntity core = plant(h, true);
        FruitEntity fruit = fruit(h, core, side(4));
        fruit.grade = 1;
        var level = h.getLevel();
        var player = DevelopmentGameTests.player(h, core);
        BlockPos pos = core.getBlockPos();
        level.destroyBlock(pos, true, player);
        var drops = level.getEntitiesOfClass(ItemEntity.class, new AABB(pos).inflate(2),
                item -> item.getItem().is(TemporalContent.CORE_ITEM.get()));
        h.assertTrue(drops.size() == 1 && fruit.orphaned, "Mining drops the original core and suspends its ripe fruit");
        ItemStack original = drops.getFirst().getItem().copy();
        drops.getFirst().discard();
        level.setBlock(pos, TemporalContent.CORE.get().defaultBlockState(), 3);
        h.startSequence()
                .thenExecuteAfter(3, () -> {
                    h.assertTrue(fruit.orphaned && fruit.limit() == 0, "A different core cannot take over");
                    level.removeBlock(pos, false);
                    player.setItemInHand(InteractionHand.MAIN_HAND, original);
                    player.gameMode.useItemOn(player, level, original, InteractionHand.MAIN_HAND,
                            new BlockHitResult(Vec3.atCenterOf(pos.below()), Direction.UP, pos.below(), false));
                    h.assertTrue(original.isEmpty(), "The original core item was replanted");
                })
                .thenWaitUntil(() -> {
                    h.assertTrue(level.getBlockEntity(fruit.getBlockPos()) == fruit && !fruit.orphaned
                            && fruit.core() != null && fruit.core() == level.getBlockEntity(pos),
                            "The original fruit recognizes its replanted core");
                    h.assertTrue(fruit.limit() == 1 && fruit.grade == 1 && fruit.ticks == 0 && fruit.invested == 0,
                            "The same ripe fruit recovers its authorized node and its settled account");
                }).thenSucceed();
    }

    @GameTest(templateNamespace="kaleidoscope_witchcraft",template="empty",timeoutTicks=80)
    public static void treeCoreDropsWholeAndReplantsWithoutEntryFee(GameTestHelper h) {
        LivingEntity core=plant(h,true); core.tissue.setCoreQuantity(1000*Tissue.TU);
        Tissue.Side attached=side(2);attached.locked=true;attached.tissue.setCoreQuantity(37*Tissue.TU);
        attached.tissue.sides.add(side(0));core.tissue.sides.add(attached);crown(h,core);
        UUID id=core.tissue.identity;long total=core.tissue.stored();BlockPos pos=core.getBlockPos();
        var player=DevelopmentGameTests.player(h,core);
        h.getLevel().destroyBlock(pos,true,player);
        List<ItemEntity> drops=h.getLevel().getEntitiesOfClass(ItemEntity.class,new AABB(pos).inflate(2));
        h.assertTrue(drops.size()==1 && drops.getFirst().getItem().is(TemporalContent.CORE_ITEM.get()),"Ordinary mining must drop exactly one complete core");
        ItemStack item=drops.getFirst().getItem().copy();drops.getFirst().discard();Tissue copy=TissueItem.read(item);
        h.assertTrue(copy.tree && copy.identity.equals(id) && copy.sides.size()==1 && copy.sides.getFirst().tissue.sides.size()==1
                && copy.sides.getFirst().locked && copy.stored()==total,"Core snapshots preserve nested tissue, locks, identity and balance");
        BlockPos leaf=pos.offset(1,3,0);h.getLevel().destroyBlock(leaf,true);
        TemporalWorld.get(h.getLevel()).trees.flush();
        h.assertTrue(h.getLevel().getBlockState(leaf).isAir(),"A removed core cannot regrow its old leaves");
        h.getLevel().setBlock(pos.below(),Blocks.DIRT.defaultBlockState(),3);
        player.setItemInHand(InteractionHand.MAIN_HAND,item);
        player.gameMode.useItemOn(player,h.getLevel(),item,InteractionHand.MAIN_HAND,
                new BlockHitResult(Vec3.atCenterOf(pos.below()),Direction.UP,pos.below(),false));
        h.assertTrue(item.isEmpty() && h.getLevel().getBlockEntity(pos) instanceof LivingEntity,"A core can be replanted on dirt");
        LivingEntity replanted=(LivingEntity)h.getLevel().getBlockEntity(pos);
        h.succeedWhen(() -> {
            h.assertTrue(h.getLevel().getBlockState(leaf).is(TemporalContent.TIME_LEAVES.get()),
                    "The replanted core regrows its missing leaf through the native server tick");
            h.assertTrue(replanted.tissue.stored() == total - TreeBalance.LEAF_REPAIR,
                    "Only the missing leaf costs TU after replanting; existing wood and the entry fee are not charged");
        });
    }

    @GameTest(templateNamespace="kaleidoscope_witchcraft",template="empty",timeoutTicks=80)
    public static void treeMaterialsRepairOnceAndWaitForBudgetOrSpace(GameTestHelper h) {
        LivingEntity core = plant(h, true);
        crown(h, core);
        var level = h.getLevel();
        BlockPos log = core.getBlockPos().above();
        core.tissue.setCoreQuantity(TreeBalance.LOG_REPAIR);
        h.startSequence()
                .thenExecuteAfter(4, () -> level.destroyBlock(log, true))
                .thenWaitUntil(() -> h.assertTrue(level.getBlockState(log).is(TemporalContent.TIME_LOG.get())
                        && core.tissue.stored() == 0, "The first restored log costs exactly one repair"))
                .thenExecute(() -> level.destroyBlock(log, true))
                .thenExecuteAfter(3, () -> {
                    h.assertTrue(level.getBlockState(log).isAir() && core.tissue.stored() == 0,
                            "A second repair waits for stock");
                    core.tissue.setCoreQuantity(TreeBalance.LOG_REPAIR);
                    level.setBlock(log, Blocks.STONE.defaultBlockState(), 3);
                })
                .thenExecuteAfter(3, () -> {
                    h.assertTrue(level.getBlockState(log).is(Blocks.STONE) && core.tissue.stored() == TreeBalance.LOG_REPAIR,
                            "An obstruction is preserved without charging for a failed repair");
                    level.removeBlock(log, false);
                })
                .thenWaitUntil(() -> h.assertTrue(level.getBlockState(log).is(TemporalContent.TIME_LOG.get())
                        && core.tissue.stored() == 0, "Cleared space and restored stock resume regeneration"))
                .thenSucceed();
    }

    @GameTest(templateNamespace="kaleidoscope_witchcraft",template="empty",timeoutTicks=60)
    public static void boneMealGrowsWholePlantWithoutBuyingAStage(GameTestHelper h) {
        LivingEntity crop=plant(h,false);
        crop.tissue=new Tissue();crop.tissue.setCoreQuantity(17*Tissue.TU);
        GiantRoots.restore(crop.tissue);
        h.getLevel().setBlock(crop.getBlockPos().below(),Blocks.FARMLAND.defaultBlockState(),3);
        var player=DevelopmentGameTests.player(h,crop);
        ItemStack meal=new ItemStack(Items.BONE_MEAL,5);
        for(int i=0;i<4;i++) DevelopmentGameTests.use(h,crop,player,meal);
        h.assertTrue(meal.getCount()==1 && crop.tissue.maturePlant()
                && crop.tissue.sides.stream().allMatch(Tissue.Side::mature) && crop.tissue.stored()==17*Tissue.TU,
                "Four fertilizers mature the existing plant and all six roots without touching inventory");
        DevelopmentGameTests.use(h,crop,player,meal);
        crop.tissue=TissueItem.read(TissueItem.stack(crop.tissue));
        h.assertTrue(meal.getCount()==1 && GrowthCatalysis.quote(crop).ticks()==0,
                "A completed saved batch has no hidden extra growth or duplicate consumption");
        h.succeed();
    }

    @GameTest(templateNamespace="kaleidoscope_witchcraft",template="empty",timeoutTicks=80)
    public static void treeFruitQualityNeedsFeesAndTimeAndHarvestsCompletedGrade(GameTestHelper h) {
        LivingEntity core=plant(h,true);Tissue.Side side=side(4);FruitEntity fruit=fruit(h,core,side);
        var group=TemporalWorld.get(h.getLevel()).colony(core);
        group.chooseMode(0,h.getLevel().getGameTime(),UUID.randomUUID());
        core.tissue.setCoreQuantity(TreeBalance.FRUIT_FEE[1]);
        { FruitEntity.tick(fruit); TemporalWorld.get(h.getLevel()).development.flush(); TemporalWorld.get(h.getLevel()).production.flush(List.of()); }
        h.assertTrue(fruit.invested==0 && core.tissue.stored()==TreeBalance.FRUIT_FEE[1],"Native development must not bypass the supply mode and drain stock");
        group.chooseMode(1,h.getLevel().getGameTime(),UUID.randomUUID());
        TemporalWorld world=TemporalWorld.get(h.getLevel());world.invalidateConnections();
        fruit.advanceDevelopment(TreeBalance.FRUIT_TICKS[1]-5);
        world.production.flush(List.of());
        h.assertTrue(fruit.grade==0 && fruit.ticks==TreeBalance.FRUIT_TICKS[1]-5
                && fruit.invested+core.tissue.stored()==TreeBalance.FRUIT_FEE[1],
                "Paid progress conserves TU, with only the remaining work left for gold");
        var player=DevelopmentGameTests.player(h,core);ItemStack gold=new ItemStack(Items.GOLD_INGOT,2);
        player.setItemInHand(InteractionHand.MAIN_HAND,gold);
        player.gameMode.useItemOn(player,h.getLevel(),gold,InteractionHand.MAIN_HAND,
                new BlockHitResult(Vec3.atCenterOf(fruit.getBlockPos()),Direction.UP,fruit.getBlockPos(),false));
        h.assertTrue(gold.getCount()==1 && fruit.grade==1 && fruit.ticks==0 && fruit.invested==0,"One ingot finishes only the current grade and never spills extra time into the next");
        fruit.advanceDevelopment(100_000);world.production.flush(List.of());
        h.assertTrue(fruit.grade==1 && fruit.remainingTicks()==0 && fruit.remainingFee()==0,
                "A completed fixed-quality fruit cannot advance into a different quality");
        player.setItemInHand(InteractionHand.MAIN_HAND,ItemStack.EMPTY);
        player.gameMode.useItemOn(player,h.getLevel(),ItemStack.EMPTY,InteractionHand.MAIN_HAND,
                new BlockHitResult(Vec3.atCenterOf(fruit.getBlockPos()),Direction.UP,fruit.getBlockPos(),false));
        h.assertTrue(h.getLevel().getBlockEntity(fruit.getBlockPos())==fruit,"Empty right click inspects without harvesting");
        ItemStack shears=new ItemStack(Items.SHEARS);
        player.setItemInHand(InteractionHand.MAIN_HAND,shears);
        player.gameMode.useItemOn(player,h.getLevel(),shears,InteractionHand.MAIN_HAND,
                new BlockHitResult(Vec3.atCenterOf(fruit.getBlockPos()),Direction.UP,fruit.getBlockPos(),false));
        var drops=h.getLevel().getEntitiesOfClass(ItemEntity.class,new AABB(fruit.getBlockPos()).inflate(1),e->e.getItem().is(TemporalContent.FRUIT_ITEM.get()));
        h.assertTrue(h.getLevel().getBlockState(fruit.getBlockPos()).isAir() && shears.getDamageValue()==1,"Shears cut the fruit and consume one durability");
        h.assertTrue(drops.size()==1 && drops.getFirst().getItem().getCount()==1 && FruitItem.grade(drops.getFirst().getItem())==1,
                "Cutting awards exactly one fruit of the highest completed grade");
        h.succeed();
    }

    @GameTest(templateNamespace="kaleidoscope_witchcraft",template="empty",timeoutTicks=80)
    public static void treeFruitPausesOnLostNodeAndResumesItsOwnSite(GameTestHelper h) {
        LivingEntity core=plant(h,true);Tissue.Side side=side(4);FruitEntity fruit=fruit(h,core,side);
        fruit.invested=TreeBalance.FRUIT_FEE[1];fruit.ticks=100;
        var data=fruit.saveWithoutMetadata(h.getLevel().registryAccess());
        fruit.loadWithComponents(data,h.getLevel().registryAccess());
        var trees=TemporalWorld.get(h.getLevel()).trees;
        BlockPos node=core.getBlockPos().offset(fruit.source);
        h.getLevel().removeBlock(node,false);trees.tick(core);for(int i=0;i<40;i++)trees.flush();
        fruit.advanceDevelopment(20);TemporalWorld.get(h.getLevel()).production.flush(List.of());
        h.assertTrue(fruit.ticks==100 && fruit.invested==TreeBalance.FRUIT_FEE[1] && fruit.limit()==0,
                "Losing the nurturing node pauses the fixed site and retains paid work");
        h.getLevel().setBlock(node,FruitWoods.FAMILIES.getFirst().node().get().defaultBlockState(),3);
        for(int i=0;i<40;i++){trees.tick(core);trees.flush();}
        fruit.advanceDevelopment(1);TemporalWorld.get(h.getLevel()).production.flush(List.of());
        h.assertTrue(fruit.ticks==101 && fruit.limit()==1,
                "Returning the same node restores its existing fruit instead of creating another");
        h.getLevel().destroyBlock(core.getBlockPos(),true);{ FruitEntity.tick(fruit); TemporalWorld.get(h.getLevel()).development.flush(); TemporalWorld.get(h.getLevel()).production.flush(List.of()); }
        h.assertTrue(h.getLevel().getBlockState(fruit.getBlockPos()).isAir(),"Removing the core immediately removes an unripe fruit");
        h.assertTrue(h.getLevel().getEntitiesOfClass(ItemEntity.class,new AABB(fruit.getBlockPos()).inflate(2),e->e.getItem().is(TemporalContent.FRUIT_ITEM.get())).isEmpty(),"Unripe fruit cannot drop a product");
        h.succeed();
    }

    @GameTest(templateNamespace="kaleidoscope_witchcraft",template="empty",timeoutTicks=80)
    public static void juvenileFruitKeepsRecordedSupportWhileRepairsWait(GameTestHelper h) {
        LivingEntity core=plant(h,true);FruitEntity fruit=nodeFruit(h,core,1);
        BlockPos pos=fruit.getBlockPos(), leaf=pos.above();var trees=TemporalWorld.get(h.getLevel()).trees;
        fruit.ticks=73;fruit.invested=17*Tissue.TU;core.tissue.setCoreQuantity(0);
        h.getLevel().removeBlock(leaf,false);
        for(int i=0;i<8;i++){trees.flush();FruitEntity.tick(fruit);}
        h.assertTrue(h.getLevel().getBlockState(leaf).isAir() && !fruit.isRemoved()
                && fruit.ticks==73 && fruit.invested==17*Tissue.TU,
                "A recorded leaf retains its fruit and paid work even when there are no repair funds");
        h.getLevel().setBlock(leaf,Blocks.STONE.defaultBlockState(),3);fruit.checkSupport();
        h.assertTrue(!fruit.isRemoved(),"A temporary obstruction does not erase the intact-tree support");
        trees.unregister(core.getBlockPos());fruit.checkSupport();
        h.assertTrue(!fruit.isRemoved(),"An unavailable tree plan is unknown, not an absent leaf");
        core.tissue=TissueItem.read(TissueItem.stack(core.tissue));trees.register(core);fruit.checkSupport();
        h.assertTrue(trees.plannedLeaf(core,leaf)==TreeLogic.LeafSupport.PRESENT && !fruit.isRemoved(),
                "The recorded support survives plan reconstruction while its real leaf is still missing");
        h.getLevel().removeBlock(leaf,false);core.tissue.setCoreQuantity(1000*Tissue.TU);
        // Leaf distance and the nurturing scheduler advance on native ticks.
        h.succeedWhen(()->{
            h.assertTrue(h.getLevel().getBlockState(leaf).is(TemporalContent.TIME_LEAVES.get()),"The missing fruit support regrows");
            h.assertTrue(h.getLevel().getBlockEntity(pos)==fruit && fruit.limit()==1,
                    "The original attached fruit regains its feeding route");
            h.assertTrue(h.getLevel().getEntitiesOfClass(ItemEntity.class,new AABB(pos).inflate(1),
                    e->e.getItem().is(TemporalContent.FRUIT_ITEM.get())).isEmpty(),"Temporary damage must not settle a fruit");
        });
    }

    private static void pruneFruitLeaf(GameTestHelper h,LivingEntity core,FruitEntity fruit) {
        var player=DevelopmentGameTests.player(h,core);
        core.tissue.bond=dev.senluo.witchcraft.ritual.TreeBond.of(player.getUUID(),"owner");
        player.setShiftKeyDown(true);player.setItemInHand(InteractionHand.MAIN_HAND,ItemStack.EMPTY);
        BlockPos leaf=fruit.getBlockPos().above();player.gameMode.destroyBlock(leaf);
        h.assertTrue(core.tissue.shapeSpace.pruned(leaf.subtract(core.getBlockPos()).asLong()),
                "Successful owner leaf pruning commits a permanent plan exclusion");
    }

    @GameTest(templateNamespace="kaleidoscope_witchcraft",template="empty",timeoutTicks=80)
    public static void prunedLeafSettlesItsFruitOnlyAfterRemovalCommits(GameTestHelper h) {
        LivingEntity core=plant(h,true);FruitEntity fruit=nodeFruit(h,core,1);
        BlockPos pos=fruit.getBlockPos();pruneFruitLeaf(h,core,fruit);
        FruitEntity.tick(fruit);
        h.assertTrue(h.getLevel().getBlockState(pos).isAir() && fruit.isRemoved(),
                "The post-removal pruning commit clears a juvenile even though its core is alive");
        h.assertTrue(h.getLevel().getEntitiesOfClass(ItemEntity.class,new AABB(pos).inflate(1),
                e->e.getItem().is(TemporalContent.FRUIT_ITEM.get())).isEmpty(),"Pruning a juvenile grants no mature product");
        var trees=TemporalWorld.get(h.getLevel()).trees;
        core.tissue=TissueItem.read(TissueItem.stack(core.tissue));trees.register(core);
        h.assertTrue(trees.plannedLeaf(core,pos.above())==TreeLogic.LeafSupport.ABSENT,
                "Reloading the intact-tree plan cannot recreate a pruned support");
        h.succeed();
    }

    @GameTest(templateNamespace="kaleidoscope_witchcraft",template="empty",timeoutTicks=80)
    public static void missingLeafWaitsForItsCorePlanBeforeSettling(GameTestHelper h) {
        LivingEntity core=plant(h,true);FruitEntity fruit=nodeFruit(h,core,2);fruit.grade=2;
        BlockPos pos=fruit.getBlockPos(),origin=fruit.corePos;
        fruit.corePos=new BlockPos(28000000,64,28000000);
        h.assertTrue(!h.getLevel().hasChunkAt(fruit.corePos),"Remote core is unloaded");
        fruit.checkSupport();
        h.assertTrue(h.getLevel().getBlockEntity(pos)==fruit && !fruit.orphaned,"Leaf support survives an unloaded core");
        var leaf=h.getLevel().getBlockState(pos.above());
        h.getLevel().setBlock(pos.above(),Blocks.AIR.defaultBlockState(),2); // No neighbor notification.
        FruitEntity.tick(fruit);fruit.checkSupport();FruitEntity.tick(fruit);
        h.assertTrue(h.getLevel().getBlockEntity(pos)==fruit && !fruit.orphaned,
                "A missing real leaf is not proof of permanent loss when the core is unloaded");
        h.assertTrue(!h.getLevel().hasChunkAt(fruit.corePos),"Support lookup must not load the core chunk");
        fruit.corePos=origin;fruit.checkSupport();
        h.assertTrue(!fruit.isRemoved(),"The returning intact-tree plan confirms this leaf can regrow");
        h.getLevel().setBlock(pos.above(),leaf,3);pruneFruitLeaf(h,core,fruit);
        fruit.checkSupport();FruitEntity.tick(fruit);
        var drops=h.getLevel().getEntitiesOfClass(ItemEntity.class,new AABB(pos).inflate(1),e->e.getItem().is(TemporalContent.FRUIT_ITEM.get()));
        h.assertTrue(h.getLevel().getBlockState(pos).isAir() && drops.size()==1
                && drops.getFirst().getItem().getCount()==1 && FruitItem.grade(drops.getFirst().getItem())==2,
                "Only confirmed pruning settles the ripe fruit, exactly once");
        h.succeed();
    }

    @GameTest(templateNamespace="kaleidoscope_witchcraft",template="empty",timeoutTicks=80)
    public static void unsupportedFruitDetachesWithoutDiscardingItsData(GameTestHelper h) {
        LivingEntity core=plant(h,true);FruitEntity fruit=nodeFruit(h,core,1);
        var registries=h.getLevel().registryAccess();
        var saved=fruit.saveWithoutMetadata(registries);saved.putInt("NurturingVersion",99);
        saved.putString("FutureMarker","keep-me");fruit.loadWithComponents(saved,registries);
        fruit.checkSupport();
        h.assertTrue(!fruit.supported() && !fruit.isRemoved(),"Unknown data stays untouched while attached");
        BlockPos pos=fruit.getBlockPos();pruneFruitLeaf(h,core,fruit);fruit.checkSupport();
        var drops=h.getLevel().getEntitiesOfClass(ItemEntity.class,new AABB(pos).inflate(1),e->e.getItem().is(TemporalContent.FRUIT_ITEM.get()));
        h.assertTrue(h.getLevel().getBlockState(pos).isAir() && drops.size()==1,"Unknown fruit is detached once");
        var preserved=drops.getFirst().getItem().getOrDefault(net.minecraft.core.component.DataComponents.CUSTOM_DATA,
                net.minecraft.world.item.component.CustomData.EMPTY).copyTag().getCompound("UnsupportedFruit");
        h.assertTrue(preserved.equals(saved),"Detachment preserves the full opaque record instead of migrating or erasing it");
        h.succeed();
    }

    @GameTest(templateNamespace="kaleidoscope_witchcraft",template="empty",timeoutTicks=80)
    public static void giantFruitCannotCreateItsOwnRecordedLeaf(GameTestHelper h) {
        LivingEntity core=plant(h,true);core.tissue.giant=true;
        BlockPos pos=core.getBlockPos().offset(15,1,15);
        var site=core.tissue.fruitSites.ensure(BlockPos.ZERO,5);site.fruit=pos.subtract(core.getBlockPos());
        h.getLevel().setBlock(pos,TemporalContent.FRUIT.get().defaultBlockState(),3);
        FruitEntity fruit=(FruitEntity)h.getLevel().getBlockEntity(pos);fruit.bindNode(core,BlockPos.ZERO,site);
        var trees=TemporalWorld.get(h.getLevel()).trees;
        trees.unregister(core.getBlockPos());trees.register(core);
        h.assertTrue(trees.plannedLeaf(core,pos.above())==TreeLogic.LeafSupport.ABSENT,
                "A loaded fruit cannot add an otherwise absent leaf to its giant tree's plan");
        fruit.checkSupport();
        h.assertTrue(fruit.isRemoved(),"A fruit outside the recorded canopy is removed with a live core");
        h.succeed();
    }

    @GameTest(templateNamespace="kaleidoscope_witchcraft",template="empty",timeoutTicks=80)
    public static void amberFruitCoreLossKeepsRipeUntilLeafRemoval(GameTestHelper h) {
        LivingEntity core=plant(h,true);FruitEntity fruit=nodeFruit(h,core,3);fruit.grade=3;
        BlockPos pos=fruit.getBlockPos();
        h.getLevel().destroyBlock(core.getBlockPos(),true);
        h.assertTrue(fruit.orphaned && h.getLevel().getBlockEntity(pos)==fruit,"Ripe fruit stays suspended after core removal");
        var tag=fruit.saveWithoutMetadata(h.getLevel().registryAccess());
        fruit.loadWithComponents(tag,h.getLevel().registryAccess());
        h.assertTrue(fruit.orphaned,"Core loss persists across fruit save/load");
        h.getLevel().removeBlock(pos.above(),false);
        h.assertTrue(h.getLevel().getBlockState(pos).isAir(),"Removing the supporting leaf immediately releases orphaned fruit");
        { FruitEntity.tick(fruit); TemporalWorld.get(h.getLevel()).development.flush(); TemporalWorld.get(h.getLevel()).production.flush(List.of()); }
        var drops=h.getLevel().getEntitiesOfClass(ItemEntity.class,new AABB(pos).inflate(1),e->e.getItem().is(TemporalContent.FRUIT_ITEM.get()));
        h.assertTrue(drops.size()==1 && drops.getFirst().getItem().getCount()==1 && FruitItem.grade(drops.getFirst().getItem())==3,
                "Leaf loss drops exactly one fruit with its completed quality");
        h.succeed();
    }

    @GameTest(templateNamespace="kaleidoscope_witchcraft",template="empty",timeoutTicks=80)
    public static void amberFruitBreakDropsOnceAndUnloadedCoreDoesNotOrphan(GameTestHelper h) {
        LivingEntity core=plant(h,true);FruitEntity fruit=nodeFruit(h,core,2);fruit.grade=2;
        BlockPos pos=fruit.getBlockPos();
        fruit.corePos=new BlockPos(28000000,64,28000000);
        h.assertTrue(!h.getLevel().hasChunkAt(fruit.corePos),"Test core position must be unloaded");
        { FruitEntity.tick(fruit); TemporalWorld.get(h.getLevel()).development.flush(); TemporalWorld.get(h.getLevel()).production.flush(List.of()); }
        h.assertTrue(!fruit.orphaned && h.getLevel().getBlockEntity(pos)==fruit,"An unloaded core suspends growth without declaring core loss");
        h.getLevel().destroyBlock(pos,true,DevelopmentGameTests.player(h,core));
        var drops=h.getLevel().getEntitiesOfClass(ItemEntity.class,new AABB(pos).inflate(1),e->e.getItem().is(TemporalContent.FRUIT_ITEM.get()));
        h.assertTrue(drops.size()==1 && drops.getFirst().getItem().getCount()==1 && FruitItem.grade(drops.getFirst().getItem())==2,
                "Breaking a fruit awards its completed grade once");
        h.succeed();
    }

    @GameTest(templateNamespace="kaleidoscope_witchcraft",template="empty",timeoutTicks=60)
    public static void treeFruitUsesNodeIdentityAndExcludesItselfFromIncome(GameTestHelper h) {
        LivingEntity core=plant(h,true);Tissue.Side direct=side(0);direct.tissue.sides.add(side(4));
        FruitEntity first=fruit(h,core,direct);
        long count=TemporalWorld.get(h.getLevel()).trees.fruits(core).size();
        h.assertTrue(count==1 && first.limit()==1,"Only the actual node creates a fruit; nested tissue does not change its quality");
        h.assertTrue(WorkSources.price(h.getLevel(),first)==0,"The tree must not swallow its fruit ticker as machine income");
        long total=core.tissue.stored();for(int i=0;i<120;i++){ FruitEntity.tick(first); TemporalWorld.get(h.getLevel()).development.flush(); TemporalWorld.get(h.getLevel()).production.flush(List.of()); }
        h.assertTrue(first.invested==0 && first.grade==0 && core.tissue.stored()==total,"Extra development updates cannot manufacture TU or pay fees");
        h.succeed();
    }

    @GameTest(templateNamespace="kaleidoscope_witchcraft",template="empty",timeoutTicks=60)
    public static void treeCoreConnectsToRootPortAndAdvancesAttachedGrowth(GameTestHelper h) {
        LivingEntity core=plant(h,true);BlockPos below=core.getBlockPos().below(),beside=core.getBlockPos().east();
        h.getLevel().setBlock(below,TemporalContent.ROOT.get().defaultBlockState().setValue(RootBlock.EMBEDDED,true),3);
        h.getLevel().setBlock(beside,TemporalContent.ROOT.get().defaultBlockState(),3);
        h.assertTrue(RootConnections.neighbor(h.getLevel(),core.getBlockPos(),Direction.DOWN).equals(below)
                && RootConnections.neighbor(h.getLevel(),beside,Direction.WEST)==null,"Only the tree's underground base is a root port");
        Tissue.Side direct=new Tissue.Side(1);Tissue.Side nested=new Tissue.Side(1);direct.tissue.sides.add(nested);core.tissue.sides.add(direct);
        long before=core.tissue.stored();
        DevelopmentTime.advanceStructure(core,30);
        h.assertTrue(direct.tissue.growth.ticks==30 && nested.tissue.growth.ticks==30 && core.tissue.stored()==before,
                "The current development clock advances attached tissue without creating stock");
        h.succeed();
    }

    @GameTest(templateNamespace="kaleidoscope_witchcraft",template="empty",timeoutTicks=60)
    public static void treeCreativeCoreAndCurrentDataHaveValidDefaults(GameTestHelper h) {
        BlockPos pos=h.absolutePos(new BlockPos(5,3,5));
        h.getLevel().setBlock(pos.below(),Blocks.DIRT.defaultBlockState(),3);
        h.getLevel().setBlock(pos,TemporalContent.CORE.get().defaultBlockState(),3);
        LivingEntity core=(LivingEntity)h.getLevel().getBlockEntity(pos);
        h.assertTrue(core.tissue.tree && core.tissue.rootStage==4 && core.tissue.coreQuantity()==0,"Directly created cores start in the tree form with no free stock");
        ItemStack picked=core.getBlockState().getBlock().getCloneItemStack(h.getLevel(),pos,core.getBlockState());
        h.assertTrue(picked.is(TemporalContent.CORE_ITEM.get()) && TissueItem.read(picked).tree,"Creative pick returns a usable core item");
        core.tissue.setCoreQuantity(core.tissue.coreCapacity()-17);
        Tissue large=TissueItem.read(TissueItem.stack(core.tissue));
        h.assertTrue(large.coreQuantity()>Integer.MAX_VALUE && large.coreQuantity()==core.tissue.coreQuantity()
                && large.coreCapacity()==core.tissue.coreCapacity(),"Large tree balances survive item serialization beyond the 32-bit boundary");
        h.assertTrue(TissueTag.load(TissueTag.save(large)).identity.equals(large.identity),
                "Current core identity survives repeated save/load");
        h.succeed();
    }

    @GameTest(templateNamespace="kaleidoscope_witchcraft",template="empty",timeoutTicks=60)
    public static void timeBottleAcceleratesStructureAndPaidFruitWork(GameTestHelper h) {
        if(ModList.get().isLoaded("tiab")) TreeBottleCases.run(h); else h.succeed();
    }
}
