package dev.senluo.witchcraft.gametest;

import dev.senluo.witchcraft.time.*;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.FarmBlock;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@PrefixGameTestTemplate(false)
public final class RootedFarmlandGameTests {
    private static void use(GameTestHelper h, Player player, ItemStack stack, BlockPos pos) {
        player.setItemInHand(InteractionHand.MAIN_HAND, stack);
        stack.useOn(new UseOnContext(player, InteractionHand.MAIN_HAND,
                new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false)));
    }

    @GameTest(templateNamespace="kaleidoscope_witchcraft",template="empty",timeoutTicks=60)
    public static void manualFarmlandRootsKeepCropsMoistureAndMaterialCost(GameTestHelper h) {
        BlockPos pos=h.absolutePos(new BlockPos(4,2,4));
        h.getLevel().setBlock(pos,Blocks.FARMLAND.defaultBlockState().setValue(FarmBlock.MOISTURE,5),3);
        h.getLevel().setBlock(pos.above(),Blocks.WHEAT.defaultBlockState().setValue(CropBlock.AGE,3),3);
        h.getLevel().setBlock(pos.above(3),Blocks.SEA_LANTERN.defaultBlockState(),3);
        Player player=h.makeMockPlayer(GameType.SURVIVAL);ItemStack roots=new ItemStack(TemporalContent.ROOT_PIECE.get(),2);
        use(h,player,roots,pos);
        RootEntity root=(RootEntity)h.getLevel().getBlockEntity(pos);
        h.assertTrue(root!=null && root.ticks==0 && root.getBlockState().is(TemporalContent.ROOTED_FARMLAND.get())
                && root.getBlockState().getValue(FarmBlock.MOISTURE)==5 && roots.getCount()==1,
                "Manual conversion consumes one standard root, starts young, and preserves moisture");
        h.assertTrue(h.getLevel().getBlockState(pos.above()).is(Blocks.WHEAT)
                && h.getLevel().getBlockState(pos.above()).getValue(CropBlock.AGE)==3
                && h.getLevel().getBlockState(pos.above()).canSurvive(h.getLevel(),pos.above()),
                "Conversion keeps the existing crop and its growth state alive");
        use(h,player,roots,pos);
        h.assertTrue(roots.getCount()==1,"Repeated use on occupied rooted farmland cannot consume another segment");
        root.ticks=1700;
        h.getLevel().setBlock(pos.east(),Blocks.WATER.defaultBlockState(),3);
        h.getLevel().getBlockState(pos).randomTick(h.getLevel(),pos,h.getLevel().random);
        h.assertTrue(h.getLevel().getBlockState(pos).getValue(FarmBlock.MOISTURE)==7
                && h.getLevel().getBlockEntity(pos)==root && root.ticks==1700
                && h.getLevel().getBlockState(pos).isFertile(h.getLevel(),pos),
                "Native hydration and crop fertility retain the same root entity");
        h.getLevel().destroyBlock(pos,true);
        var drops=h.getLevel().getEntitiesOfClass(ItemEntity.class,new AABB(pos).inflate(1));
        int rootCount=drops.stream().filter(e->e.getItem().is(TemporalContent.ROOT_PIECE.get())).mapToInt(e->e.getItem().getCount()).sum();
        int dirtCount=drops.stream().filter(e->e.getItem().is(Items.DIRT)).mapToInt(e->e.getItem().getCount()).sum();
        h.assertTrue(rootCount==1 && dirtCount==1,"Breaking rooted farmland drops exactly one root material and one dirt");
        h.succeed();
    }

    @GameTest(templateNamespace="kaleidoscope_witchcraft",template="empty",timeoutTicks=60)
    public static void farmlandFlatteningAndRetillingRetainTheRoot(GameTestHelper h) {
        BlockPos pos=h.absolutePos(new BlockPos(4,2,4));
        h.getLevel().setBlock(pos,TemporalContent.ROOTED_FARMLAND.get().defaultBlockState().setValue(RootBlock.AGE,2),3);
        h.getLevel().setBlock(pos.above(2),Blocks.STONE.defaultBlockState(),3); // No rain reaches empty, dry soil.
        RootEntity root=(RootEntity)h.getLevel().getBlockEntity(pos);
        root.owner=UUID.randomUUID();root.parent=pos.west(2);root.ticks=2700;
        UUID owner=root.owner;BlockPos parent=root.parent;
        h.getLevel().getBlockState(pos).randomTick(h.getLevel(),pos,h.getLevel().random);
        h.assertTrue(h.getLevel().getBlockState(pos).is(TemporalContent.ROOT.get()) && h.getLevel().getBlockEntity(pos)==root
                && root.ticks==2700 && root.getBlockState().getValue(RootBlock.AGE)==2
                && owner.equals(root.owner) && parent.equals(root.parent),
                "Native dry-soil flattening keeps age, owner and parent in the same root entity");
        Player player=h.makeMockPlayer(GameType.SURVIVAL);use(h,player,new ItemStack(Items.IRON_HOE),pos);
        h.assertTrue(h.getLevel().getBlockState(pos).is(TemporalContent.ROOTED_FARMLAND.get())
                && h.getLevel().getBlockEntity(pos)==root && root.ticks==2700,
                "A real hoe restores farmland without replacing or rejuvenating its root");
        FarmBlock.turnToDirt(player,h.getLevel().getBlockState(pos),h.getLevel(),pos);
        h.assertTrue(h.getLevel().getBlockState(pos).is(TemporalContent.ROOT.get()) && h.getLevel().getBlockEntity(pos)==root
                && h.getLevel().getEntitiesOfClass(ItemEntity.class,new AABB(pos).inflate(1)).isEmpty(),
                "The shared native trample/flatten path cannot duplicate root or dirt items");
        h.succeed();
    }

    @GameTest(templateNamespace="kaleidoscope_witchcraft",template="empty",timeoutTicks=80)
    public static void naturalRootsRequireManualConversionOfOtherCropFarmland(GameTestHelper h) {
        LivingEntity a=DevelopmentGameTests.plant(h,4,1,false),b=DevelopmentGameTests.plant(h,8,1,false);
        Player player=h.makeMockPlayer(GameType.SURVIVAL);
        a.tissue.bond=b.tissue.bond=dev.senluo.witchcraft.ritual.TreeBond.of(player.getUUID(), "");
        TemporalWorld world=TemporalWorld.get(h.getLevel());world.invalidate();
        BlockPos start=a.getBlockPos().below();
        for(int x=1;x<=3;x++) {
            BlockPos pos=start.east(x);
            h.getLevel().setBlock(pos,Blocks.FARMLAND.defaultBlockState(),3);
            h.getLevel().setBlock(pos.above(),Blocks.WHEAT.defaultBlockState(),3);
        }
        for(int i=0;i<2400;i++) world.accelerateRoots(a);
        h.assertTrue(world.colony(a).roots.isEmpty(),"Ordinary crop farmland cannot complete an automatic root route");
        for(int x=1;x<=3;x++) {
            BlockPos pos=start.east(x);
            h.assertTrue(h.getLevel().getBlockState(pos).is(Blocks.FARMLAND),"Natural attempts cannot convert another crop's farmland");
            use(h,player,RootItem.stack(),pos);
        }
        for(int i=0;i<600;i++) world.accelerateRoots(a);
        h.assertTrue(h.getLevel().getBlockState(start).is(TemporalContent.ROOTED_FARMLAND.get())
                && world.colony(a).roots.size()==4 && world.colony(a).members.size()==1,
                "Once a real route exists, growth first converts the source's own farmland");
        h.runAfterDelay(25,()-> {
            for(int i=0;i<1200;i++) world.accelerateRoots(a);
            h.assertTrue(h.getLevel().getBlockState(b.getBlockPos().below()).is(TemporalContent.ROOTED_FARMLAND.get())
                    && world.colony(a).members.contains(b),"The route ends by converting the other rhizome's farmland");
            for(int x=1;x<=3;x++) h.assertTrue(h.getLevel().getBlockState(start.east(x).above()).is(Blocks.WHEAT),
                    "Hand-converted ordinary fields continue to support their crops");
            h.succeed();
        });
    }
}
