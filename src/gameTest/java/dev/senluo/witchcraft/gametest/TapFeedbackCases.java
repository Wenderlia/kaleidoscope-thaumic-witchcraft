package dev.senluo.witchcraft.gametest;

import com.github.ysbbbbbb.kaleidoscopetavern.block.brew.BarrelBlock;
import com.github.ysbbbbbb.kaleidoscopetavern.block.brew.TapBlock;
import com.github.ysbbbbbb.kaleidoscopetavern.blockentity.brew.BarrelBlockEntity;
import com.github.ysbbbbbb.kaleidoscopetavern.init.ModBlocks;
import com.github.ysbbbbbb.kaleidoscopetavern.init.ModItems;
import com.github.ysbbbbbb.kaleidoscopetavern.init.ModParticles;
import com.mojang.authlib.GameProfile;
import dev.senluo.witchcraft.brew.LiujinVodkaItem;
import dev.senluo.witchcraft.brew.WineParticles;
import dev.senluo.witchcraft.compat.tavern.TavernWine;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.PlayLevelSoundEvent;

/** Exercise the actual valve, scheduled extraction and outgoing particle packets. */
final class TapFeedbackCases {
    static void run(GameTestHelper h) {
        var level=h.getLevel();
        BlockPos origin=h.absolutePos(new BlockPos(4,2,4)), tap=origin.offset(0,1,-2);
        level.setBlock(origin,ModBlocks.BARREL.get().defaultBlockState(),3);
        var barrel=(BarrelBlockEntity)level.getBlockEntity(origin);
        ItemStack wine=LiujinVodkaItem.sample();wine.setCount(16);barrel.getOutput().setStackInSlot(0,wine);
        var tag=barrel.saveWithoutMetadata(level.registryAccess());
        tag.putString("recipe_id","kaleidoscope_witchcraft:barrel/liujin_vodka");
        tag.putInt("brew_level",6);tag.putBoolean("open",false);
        barrel.loadWithComponents(tag,level.registryAccess());
        level.setBlock(tap.south(),ModBlocks.BARREL.get().defaultBlockState()
                .setValue(BarrelBlock.FACING,Direction.NORTH).setValue(BarrelBlock.LAYER,AttachFace.WALL).setValue(BarrelBlock.INDEX,1),3);
        level.setBlock(tap,ModBlocks.TAP.get().defaultBlockState(),3);
        level.setBlock(tap.below(2),Blocks.STONE.defaultBlockState(),3);
        var behavior=new com.github.ysbbbbbb.kaleidoscopetavern.game.tap.impl.BarrelTapBehavior();
        for(var nativeWine:List.of(ModItems.VODKA.get(),ModItems.MOLOTOV.get())) {
            barrel.getOutput().setStackInSlot(0,new ItemStack(nativeWine));
            var particle=behavior.onStartExtract(level,null,tap,level.getBlockState(tap),level.getBlockState(tap.south()),level.getBlockState(tap.below()));
            var expected=nativeWine==ModItems.MOLOTOV.get()?ModParticles.LAVA_TAP_DRIP.get():ModParticles.WATER_TAP_DRIP.get();
            h.assertTrue(particle==expected,"Native wine and Molotov retain their own particles");
        }
        barrel.getOutput().setStackInSlot(0,wine);
        var observer=new FakePlayer(level,new GameProfile(UUID.randomUUID(),"tap-feedback"));
        observer.setPos(Vec3.atCenterOf(tap).add(2,0,0));
        List<ClientboundLevelParticlesPacket> particles=new ArrayList<>();
        List<SoundEvent> sounds=new ArrayList<>();
        observer.connection=new ServerGamePacketListenerImpl(level.getServer(),new Connection(PacketFlow.SERVERBOUND),observer,
                CommonListenerCookie.createInitial(observer.getGameProfile(),false)) {
            @Override public void send(Packet<?> packet) {
                if(packet instanceof ClientboundLevelParticlesPacket p)particles.add(p);
            }
        };
        Consumer<PlayLevelSoundEvent.AtPosition> listener=e->{
            if(e.getLevel()==level && e.getPosition().distanceToSqr(Vec3.atCenterOf(tap))<4 && e.getSound()!=null)
                sounds.add(e.getSound().value());
        };
        // A packet observer only: no player connection, entity tick or network listener is opened.
        level.players().add(observer);NeoForge.EVENT_BUS.addListener(listener);
        Runnable cleanup=()->{level.players().remove(observer);NeoForge.EVENT_BUS.unregister(listener);};
        Runnable open=()->{
            level.setBlock(tap.below(),((BlockItem)ModItems.EMPTY_BOTTLE.get()).getBlock().defaultBlockState(),3);
            ((TapBlock)ModBlocks.TAP.get()).useItemOn(ItemStack.EMPTY,level.getBlockState(tap),level,tap,observer,
                    InteractionHand.MAIN_HAND,new BlockHitResult(Vec3.atCenterOf(tap),Direction.NORTH,tap,false));
        };
        try {open.run();} catch(RuntimeException e){cleanup.run();throw e;}
        h.runAfterDelay(40,()->{
            try {
                verify(h,tap,particles,sounds,"without absorption");
                particles.clear();sounds.clear();
                DevelopmentGameTests.plant(h,2,1,false);
                open.run();
                h.runAfterDelay(40,()->{
                    try {
                        verify(h,tap,particles,sounds,"inside a hungry rooted plant's field");
                        h.assertTrue(barrel.getOutput().getStackInSlot(0).getCount()==14,"Both valve operations consume exactly one wine");
                        h.succeed();
                    } finally {cleanup.run();}
                });
            } catch(RuntimeException e){cleanup.run();throw e;}
        });
    }
    private static void verify(GameTestHelper h,BlockPos tap,List<ClientboundLevelParticlesPacket> particles,List<SoundEvent> sounds,String context) {
        long drips=particles.stream().filter(p->p.getParticle().getType()==WineParticles.TAP.get()).count();
        long finish=particles.stream().filter(p->p.getParticle().getType()==ParticleTypes.WAX_OFF && p.getCount()==10).count();
        long open=sounds.stream().filter(s->s==SoundEvents.IRON_TRAPDOOR_OPEN).count();
        long close=sounds.stream().filter(s->s==SoundEvents.IRON_TRAPDOOR_CLOSE).count();
        long fill=sounds.stream().filter(s->s==SoundEvents.BREWING_STAND_BREW).count();
        System.out.println("TAP_FEEDBACK "+context+": drips="+drips+", finish="+finish+", open="+open+", close="+close+", fill="+fill);
        h.assertTrue(h.getLevel().getBlockState(tap.below()).is(TavernWine.BOTTLE.get()),"Actual valve fills placed wine bottle "+context);
        h.assertTrue(drips==5 && finish==1,"Five gold wine drips and one completion burst "+context);
        h.assertTrue(particles.stream().noneMatch(p->p.getParticle().getType()==ModParticles.WATER_TAP_DRIP.get()),"Liujin does not emit blue water drips "+context);
        h.assertTrue(open==1 && close==1 && fill==1,"Native valve and completion sounds play exactly once "+context);
    }
}
