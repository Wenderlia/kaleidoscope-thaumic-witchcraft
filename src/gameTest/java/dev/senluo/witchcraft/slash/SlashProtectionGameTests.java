package dev.senluo.witchcraft.slash;

import com.mojang.authlib.GameProfile;
import dev.senluo.witchcraft.accessory.ProjectileBarrier;
import dev.senluo.witchcraft.motion.BezierSpeed;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.entity.ProjectileImpactEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@PrefixGameTestTemplate(false)
public final class SlashProtectionGameTests {
    private static SlashEntity slash(GameTestHelper h, FakePlayer player) {
        var direction = new Vec3(0,0,1);
        var result = new SlashEntity(SlashContent.SLASH.get(), h.getLevel());
        result.prepare(player, 4, 1, 0, ItemStack.EMPTY,
                new SlashFormation.Pose(direction,0,direction,0,0,h.getLevel().dimension(),false),
                SlashShape.DEFAULT, new BezierSpeed(1,1,1,1,24), player.getEyePosition());
        result.upgrades(false,false,true);
        result.arm();
        return result;
    }
    private static void pass(SlashEntity slash, FakePlayer player, Vec3 centre) {
        SlashCollision.resolve(slash,player,List.of(new SlashGeometry.Sweep(centre.add(0,0,-1.5),
                centre.add(0,0,1.5),new Vec3(1,0,0),.25,.25)),
                new SlashCollision.Budget(ProjectileBarrier.space(slash)));
    }
    @GameTest(templateNamespace="kaleidoscope_witchcraft",template="empty",timeoutTicks=20,batch="slash-protection")
    public static void slashHonorsBlockProtectionBeforeDestruction(GameTestHelper h) {
        var player=new FakePlayer(h.getLevel(),new GameProfile(UUID.randomUUID(),"claim-break"));
        var at=h.absolutePos(new BlockPos(8,3,8));player.setPos(at.getCenter().add(4,0,0));
        h.getLevel().setBlock(at,Blocks.STONE.defaultBlockState(),3);
        var stopped=slash(h,player);var calls=new AtomicInteger();
        Consumer<BlockEvent.BreakEvent> veto=event->{if(event.getPlayer()==player&&event.getPos().equals(at)){
            calls.incrementAndGet();event.setCanceled(true);
        }};
        NeoForge.EVENT_BUS.addListener(veto);
        try { pass(stopped,player,at.getCenter()); } finally { NeoForge.EVENT_BUS.unregister(veto); }
        h.assertTrue(calls.get()==1&&h.getLevel().getBlockState(at).is(Blocks.STONE),"Claim veto sees the real caster and preserves the target");
        h.assertTrue(stopped.hitBlocks.isEmpty()&&stopped.potential().remaining()==4,"Canceled break commits no harvest or fission debit");
        var allowed=slash(h,player);pass(allowed,player,at.getCenter());
        h.assertTrue(h.getLevel().getBlockState(at).isAir()&&allowed.hitBlocks.contains(at.asLong()),"The same collision harvests normally after permission is restored");
        stopped.discard();allowed.discard();h.succeed();
    }
    @GameTest(templateNamespace="kaleidoscope_witchcraft",template="empty",timeoutTicks=20,batch="slash-protection")
    public static void slashHonorsImpactAndDamageProtection(GameTestHelper h) {
        var player=new FakePlayer(h.getLevel(),new GameProfile(UUID.randomUUID(),"claim-damage"));
        var at=h.absolutePos(new BlockPos(8,3,8));player.setPos(at.getCenter().add(4,0,0));
        var cow=EntityType.COW.create(h.getLevel());cow.setNoAi(true);cow.setPos(at.getCenter());
        cow.getAttribute(Attributes.MAX_HEALTH).setBaseValue(200);cow.setHealth(200);h.getLevel().addFreshEntity(cow);
        var impactStopped=slash(h,player);var damageStopped=slash(h,player);var calls=new AtomicInteger();
        Consumer<ProjectileImpactEvent> impact=event->{if(event.getEntity()==impactStopped)event.setCanceled(true);};
        NeoForge.EVENT_BUS.addListener(impact);
        try { pass(impactStopped,player,cow.getBoundingBox().getCenter()); } finally { NeoForge.EVENT_BUS.unregister(impact); }
        h.assertTrue(cow.getHealth()==200&&impactStopped.potential().remaining()==4,"Canceled projectile impact never reaches damage or fission");
        Consumer<LivingIncomingDamageEvent> veto=event->{if(event.getEntity()==cow){
            h.assertTrue(event.getSource().getEntity()==player,"Damage retains the caster for claim/PvP decisions");
            calls.incrementAndGet();event.setCanceled(true);
        }};
        NeoForge.EVENT_BUS.addListener(veto);
        try { pass(damageStopped,player,cow.getBoundingBox().getCenter()); } finally { NeoForge.EVENT_BUS.unregister(veto); }
        h.assertTrue(calls.get()==1&&cow.getHealth()==200&&damageStopped.potential().remaining()==4,
                "Canceled damage leaves health and potential intact and does not start fission");
        var allowed=slash(h,player);pass(allowed,player,cow.getBoundingBox().getCenter());
        h.assertTrue(cow.getHealth()<200,"The unprotected control receives native damage");
        cow.discard();allowed.discard();impactStopped.discard();damageStopped.discard();h.succeed();
    }
}
