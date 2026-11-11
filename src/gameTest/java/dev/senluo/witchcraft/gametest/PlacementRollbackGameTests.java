package dev.senluo.witchcraft.gametest;

import com.mojang.authlib.GameProfile;
import dev.senluo.witchcraft.time.*;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@PrefixGameTestTemplate(false)
public final class PlacementRollbackGameTests {
    public static final class Cancel {
        final FakePlayer player;
        int calls;
        Runnable beforeCancel=()->{};
        Cancel(FakePlayer player) { this.player=player; }
        @SubscribeEvent public void place(BlockEvent.EntityPlaceEvent event) {
            if(event.getEntity()==player) { calls++; beforeCancel.run(); event.setCanceled(true); }
        }
    }
    private static FakePlayer player(GameTestHelper h, BlockPos clicked, ItemStack stack) {
        var player=new FakePlayer(h.getLevel(),new GameProfile(UUID.randomUUID(),"placement-test"));
        player.setGameMode(GameType.SURVIVAL);
        player.moveTo(Vec3.atCenterOf(clicked).add(0,1,1));
        player.setItemInHand(InteractionHand.MAIN_HAND,stack);
        return player;
    }
    private static void use(FakePlayer player, BlockPos clicked) {
        // ItemStack is deliberate: Item.useOn alone bypasses NeoForge's placement transaction.
        player.getMainHandItem().useOn(new UseOnContext(player,InteractionHand.MAIN_HAND,
                new BlockHitResult(Vec3.atCenterOf(clicked),Direction.UP,clicked,false)));
    }
    @GameTest(templateNamespace="kaleidoscope_witchcraft",template="empty")
    public static void canceledTissuePlacementCannotRedistributeStock(GameTestHelper h) {
        var level=h.getLevel();
        BlockPos soil=h.absolutePos(new BlockPos(3,2,3)), neighbor=soil.west().above();
        level.setBlockAndUpdate(soil,TemporalContent.ROOTED_FARMLAND.get().defaultBlockState());
        level.setBlockAndUpdate(neighbor.below(),TemporalContent.ROOTED_FARMLAND.get().defaultBlockState());
        level.setBlockAndUpdate(neighbor,TemporalContent.RHIZOME.get().defaultBlockState());
        var existing=(LivingEntity)level.getBlockEntity(neighbor);
        existing.tissue.rootStage=4; existing.tissue.setCoreQuantity(0);
        var world=TemporalWorld.get(level);
        world.add(level.getBlockEntity(soil)); world.add(level.getBlockEntity(neighbor.below())); world.add(existing);
        Tissue incoming=new Tissue(); incoming.rootStage=4; incoming.setCoreQuantity(100*Tissue.TU);
        ItemStack original=TissueItem.stack(incoming);
        var player=player(h,soil,original.copy());
        var cancel=new Cancel(player);
        // A read-only integration may resolve connectivity while deciding whether placement is allowed.
        cancel.beforeCancel=()->world.colony(existing);
        NeoForge.EVENT_BUS.register(cancel);
        try { use(player,soil); } finally { NeoForge.EVENT_BUS.unregister(cancel); }
        h.assertTrue(cancel.calls==1 && level.getBlockState(soil.above()).isAir(),"Native placement event must cancel and restore air");
        h.assertTrue(ItemStack.matches(original,player.getMainHandItem()),"Canceled placement restores complete carried tissue");
        h.assertTrue(existing.tissue.stored()==0,"Rollback must not move carried stock into an existing colony");
        h.assertTrue(world.colony(existing).members.size()==1,"Canceled organism must not remain in the network");
        h.succeed();
    }
    @GameTest(templateNamespace="kaleidoscope_witchcraft",template="empty",timeoutTicks=30)
    public static void canceledRootPlacementRestoresSoilAndItem(GameTestHelper h) {
        var level=h.getLevel();
        BlockPos soil=h.absolutePos(new BlockPos(3,2,3));
        var farmland=Blocks.FARMLAND.defaultBlockState().setValue(net.minecraft.world.level.block.FarmBlock.MOISTURE,5);
        level.setBlockAndUpdate(soil,farmland);
        ItemStack original=RootItem.stack();
        var player=player(h,soil,original.copy());
        var cancel=new Cancel(player);
        NeoForge.EVENT_BUS.register(cancel);
        try { use(player,soil); } finally { NeoForge.EVENT_BUS.unregister(cancel); }
        h.assertTrue(cancel.calls==1 && level.getBlockState(soil).equals(farmland)
                && level.getBlockEntity(soil)==null,"Canceled root restores the exact soil state without a stale entity");
        h.assertTrue(ItemStack.matches(original,player.getMainHandItem()),"Canceled root retains its item");
        use(player,soil);
        h.assertTrue(player.getMainHandItem().isEmpty(),"Accepted root consumes one item");
        h.runAfterDelay(2,()->{
            h.assertTrue(level.getBlockEntity(soil) instanceof RootEntity,"Accepted root survives deferred loading");
            h.succeed();
        });
    }
    @GameTest(templateNamespace="kaleidoscope_witchcraft",template="empty",timeoutTicks=30)
    public static void corePlacementCommitsClaimsOnlyAfterAcceptance(GameTestHelper h) {
        var level=h.getLevel();
        BlockPos soil=h.absolutePos(new BlockPos(3,2,3));
        level.setBlockAndUpdate(soil,Blocks.STONE.defaultBlockState());
        Tissue tissue=new Tissue(); tissue.tree=true; tissue.rootStage=4; tissue.setCoreQuantity(100*Tissue.TU);
        ItemStack original=TissueItem.stack(tissue);
        var player=player(h,soil,original.copy());
        var claims=TreeClaims.get(level);
        CompoundTag before=claims.save(new CompoundTag(),level.registryAccess());
        var cancel=new Cancel(player);
        NeoForge.EVENT_BUS.register(cancel);
        try { use(player,soil); } finally { NeoForge.EVENT_BUS.unregister(cancel); }
        h.assertTrue(cancel.calls==1 && level.getBlockState(soil.above()).isAir(),"Core placement must be canceled through native event");
        h.assertTrue(before.equals(claims.save(new CompoundTag(),level.registryAccess())),"Rejected placement cannot leave a saved tree site");
        h.assertTrue(ItemStack.matches(original,player.getMainHandItem()),"Rejected core retains data and count");
        use(player,soil);
        h.assertTrue(player.getMainHandItem().isEmpty(),"Accepted placement consumes one core");
        h.runAfterDelay(2,()->{
            var core=(LivingEntity)level.getBlockEntity(soil.above());
            h.assertTrue(core!=null && core.tissue.identity.equals(tissue.identity),"Accepted core keeps portable identity");
            h.assertTrue(TemporalWorld.get(level).node(soil.above())==core,"Normal load commits the accepted core to the network");
            h.assertTrue(!before.equals(claims.save(new CompoundTag(),level.registryAccess())),"Accepted core registers its site");
            h.succeed();
        });
    }
}
