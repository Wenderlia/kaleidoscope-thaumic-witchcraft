package dev.senluo.witchcraft.gametest;

import dev.senluo.witchcraft.time.*;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.mangorage.tiab.common.api.ICommonTimeInABottleAPI;

final class TreeBottleCases {
    static void run(GameTestHelper h) {
        var api=ICommonTimeInABottleAPI.COMMON_API.get();
        ItemStack bottle=new ItemStack(api.getRegistration().getTiabItem().asItem());
        bottle.set(api.getRegistration().getStoredTime(),api.createStoredTimeComponent(200_000,300_000));
        LivingEntity crop=TreeGameTests.plant(h,false);crop.tissue.treeGrowing=true;
        h.getLevel().setBlock(crop.getBlockPos().below(),net.minecraft.world.level.block.Blocks.DIRT.defaultBlockState(),3);
        var player=DevelopmentGameTests.player(h,crop);DevelopmentGameTests.use(h,crop,player,bottle);
        var accelerator=api.getEntities(h.getLevel(),new AABB(crop.getBlockPos())).stream().findFirst().orElse(null);
        h.assertTrue(accelerator!=null,"A paid first-tree clock must accept the real TIAB item");
        int rate=accelerator.getTimeRate();for(int i=0;i<10;i++)accelerator.asEntity().tick();
        TemporalWorld.get(h.getLevel()).development.flush();
        h.assertTrue(crop.tissue.treeTicks==10*rate && crop.tissue.stored()==0,"Tree acceleration changes only paid development time");
        accelerator.asEntity().discard();crop.tissue.treeTicks=TreeBalance.ENTRY_TICKS;
        TemporalWorld.get(h.getLevel()).trees.form(crop);
        LivingEntity core=(LivingEntity)h.getLevel().getBlockEntity(crop.getBlockPos());
        FruitEntity fruit=TreeGameTests.fruit(h,core,TreeGameTests.side(4));
        core.tissue.setCoreQuantity(TreeBalance.FRUIT_FEE[1]);
        player.setPos(Vec3.atCenterOf(fruit.getBlockPos()).add(0,0,-2));
        player.gameMode.useItemOn(player,h.getLevel(),bottle,InteractionHand.MAIN_HAND,
                new BlockHitResult(Vec3.atCenterOf(fruit.getBlockPos()),Direction.NORTH,fruit.getBlockPos(),false));
        var fruitAccelerator=api.getEntities(h.getLevel(),new AABB(fruit.getBlockPos())).stream().findFirst().orElse(null);
        h.assertTrue(fruitAccelerator!=null,"A growing supported fruit accepts actual bottle acceleration");
        int fruitRate=fruitAccelerator.getTimeRate();for(int i=0;i<10;i++)fruitAccelerator.asEntity().tick();
        TemporalWorld.get(h.getLevel()).development.flush();
        TemporalWorld.get(h.getLevel()).production.flush(java.util.List.of());
        h.assertTrue(fruit.ticks==10*fruitRate && fruit.invested>0 && fruit.grade==0
                && core.tissue.stored()+fruit.invested==TreeBalance.FRUIT_FEE[1],
                "Real bottle callbacks advance paid fruit work while conserving the tree's stock");
        Colony group=TemporalWorld.get(h.getLevel()).colony(core);
        h.assertTrue(group.spend(group.spendable()),"Fixture spends all accessible accounts, including attached roots");
        int paidTicks=fruit.ticks;long paid=fruit.invested;
        for(int i=0;i<10;i++)fruitAccelerator.asEntity().tick();
        TemporalWorld.get(h.getLevel()).development.flush();
        TemporalWorld.get(h.getLevel()).production.flush(java.util.List.of());
        h.assertTrue(fruit.ticks==paidTicks && fruit.invested==paid && core.tissue.stored()==0,
                "An existing accelerator cannot turn unpaid fruit work into progress");
        fruitAccelerator.asEntity().discard();h.succeed();
    }
}
