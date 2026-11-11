package dev.senluo.witchcraft.brew;

import java.util.List;
import java.util.UUID;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Regressions at the ability's state/storage interfaces, not duplicate algorithms. */
@net.neoforged.neoforge.gametest.PrefixGameTestTemplate(false)
public final class WineAbilityGameTests {
    @GameTest(templateNamespace="kaleidoscope_witchcraft",template="empty",timeoutTicks=20)
    public static void dawnStepFloorRespectsNativeMultipliers(GameTestHelper h) {
        var p=player(h);
        var step=p.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.STEP_HEIGHT);
        var base=net.minecraft.resources.ResourceLocation.parse("test:step_base");
        var total=net.minecraft.resources.ResourceLocation.parse("test:step_total");
        step.addTransientModifier(new net.minecraft.world.entity.ai.attributes.AttributeModifier(base,.25,
                net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation.ADD_MULTIPLIED_BASE));
        step.addTransientModifier(new net.minecraft.world.entity.ai.attributes.AttributeModifier(total,.2,
                net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
        WineAbilities.drink(p,AmberVintage.DAWN,4);
        p.tickCount=1;
        for(int i=0;i<8;i++) {
            net.neoforged.neoforge.common.NeoForge.EVENT_BUS.post(new net.neoforged.neoforge.event.tick.PlayerTickEvent.Post(p));
            h.assertTrue(Math.abs(step.getValue()-1.1)<1e-6,"Dawn supplies a stable 1.1 floor with native base/total modifiers: "+step.getValue());
        }
        h.assertTrue(step.hasModifier(base)&&step.hasModifier(total),"Foreign modifiers are not overwritten");
        var stride=net.minecraft.resources.ResourceLocation.parse("kaleidoscope_witchcraft:time_stride_step");
        StepHeightFloor.apply(step,stride,2);
        h.assertTrue(Math.abs(step.getValue()-2)<1e-6,"The same floor adapter honors the six-grade stride target");
        StepHeightFloor.apply(step,stride,0);
        h.assertTrue(Math.abs(step.getValue()-1.1)<1e-6,"Removing stride preserves the independent dawn contribution");
        var stronger=net.minecraft.resources.ResourceLocation.parse("test:stronger_step");
        step.addTransientModifier(new net.minecraft.world.entity.ai.attributes.AttributeModifier(stronger,2,
                net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation.ADD_VALUE));
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.post(new net.neoforged.neoforge.event.tick.PlayerTickEvent.Post(p));
        h.assertTrue(Math.abs(step.getValue()-3.9)<1e-6,"A stronger external step height is preserved without an extra dawn bonus");
        h.succeed();
    }
    @GameTest(templateNamespace="kaleidoscope_witchcraft",template="empty",timeoutTicks=20)
    public static void solarSafetyAndCompletion(GameTestHelper h){
        var p=player(h);var w=WineAbilities.state(p).sun;p.setPos(h.absolutePos(new net.minecraft.core.BlockPos(2,3,2)).getCenter());
        w.refresh(6,200,0);SolarFire.tick(p,w);
        h.assertTrue(w.remaining==0 && p.getHealth()==20 && p.getAbsorptionAmount()==0,"Without healing the fire stops without damage or finale shield");
        p.addEffect(new net.minecraft.world.effect.MobEffectInstance(net.minecraft.world.effect.MobEffects.HEAL,200,1));
        p.setHealth(6);w.refresh(6,200,0);SolarFire.tick(p,w);
        h.assertTrue(w.remaining==0 && p.getHealth()==6 && p.getAbsorptionAmount()==0,"Low health ends the fire without a finale");
        p.setHealth(20);p.tickCount=1;w.refresh(6,1,0);SolarFire.tick(p,w);
        h.assertTrue(w.remaining==0 && p.getAbsorptionAmount()==10,"Normal completion clears the window and grants half max-health shield: window="+w.remaining+", health="+p.getHealth()+", max="+p.getMaxHealth()+", shield="+p.getAbsorptionAmount()+", healing="+p.getEffect(net.minecraft.world.effect.MobEffects.HEAL));
        p.setAbsorptionAmount(0);SolarFire.tick(p,w);
        h.assertTrue(p.getAbsorptionAmount()==0,"Completed fire cannot issue another finale");h.succeed();
    }
    @GameTest(templateNamespace="kaleidoscope_witchcraft",template="empty",timeoutTicks=20)
    public static void solarDispelDuringDamage(GameTestHelper h){
        var p=player(h);p.setPos(h.absolutePos(new net.minecraft.core.BlockPos(2,3,2)).atY(200).getCenter());p.setNoGravity(true);
        // A newly constructed server player has sixty ticks of spawn protection.
        for(int i=0;i<60;i++)p.tick();p.setHealth(20);p.invulnerableTime=0;
        var w=WineAbilities.state(p).sun;p.tickCount=20;
        p.addEffect(new net.minecraft.world.effect.MobEffectInstance(net.minecraft.world.effect.MobEffects.HEAL,200,1));w.refresh(6,100,0);
        java.util.function.Consumer<net.neoforged.neoforge.event.entity.living.LivingDamageEvent.Post> dispel=e->{
            if(e.getEntity()==p && e.getSource().is(SolarFire.SELF))p.removeEffect(net.minecraft.world.effect.MobEffects.HEAL);
        };
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(dispel);
        try{SolarFire.tick(p,w);}finally{net.neoforged.neoforge.common.NeoForge.EVENT_BUS.unregister(dispel);}
        h.assertTrue(p.getHealth()==16 && w.remaining==0 && p.getAbsorptionAmount()==0,"An external damage callback dispel stops the same pulse, without finale or further effects: health="+p.getHealth()+", time="+w.remaining+", healing="+p.getEffect(net.minecraft.world.effect.MobEffects.HEAL));h.succeed();
    }
    private static net.minecraft.server.level.ServerPlayer player(GameTestHelper h){
        var p=new net.minecraft.server.level.ServerPlayer(h.getLevel().getServer(),h.getLevel(),
                new com.mojang.authlib.GameProfile(UUID.randomUUID(),"wine-test"),net.minecraft.server.level.ClientInformation.createDefault());
        p.connection=new net.minecraft.server.network.ServerGamePacketListenerImpl(h.getLevel().getServer(),
                new net.minecraft.network.Connection(net.minecraft.network.protocol.PacketFlow.SERVERBOUND),p,
                net.minecraft.server.network.CommonListenerCookie.createInitial(p.getGameProfile(),false)){
            @Override public void send(net.minecraft.network.protocol.Packet<?> packet){}
        };
        return p;
    }
    @GameTest(templateNamespace="kaleidoscope_witchcraft",template="empty",timeoutTicks=20)
    public static void frostOneWaySupport(GameTestHelper h){
        var pos=h.absolutePos(new net.minecraft.core.BlockPos(2,4,2));var block=FrostPath.BLOCK.get().defaultBlockState();
        var p=player(h);p.setPos(pos.getX()+.5,pos.getY()+1,pos.getZ()+.5);
        h.assertTrue(block.getCollisionShape(h.getLevel(),pos,net.minecraft.world.phys.shapes.CollisionContext.of(p)).isEmpty(),"A person without the ability is not stopped");
        WineAbilities.state(p).frost.refresh(4,600,150);
        h.assertTrue(!block.getCollisionShape(h.getLevel(),pos,net.minecraft.world.phys.shapes.CollisionContext.of(p)).isEmpty(),"Ability holder is supported from above");
        p.setShiftKeyDown(true);
        h.assertTrue(block.getCollisionShape(h.getLevel(),pos,net.minecraft.world.phys.shapes.CollisionContext.of(p)).isEmpty(),"Sneaking releases support");
        p.setShiftKeyDown(false);p.setPos(pos.getX()+.5,pos.getY()+.5,pos.getZ()+.5);
        h.assertTrue(block.getCollisionShape(h.getLevel(),pos,net.minecraft.world.phys.shapes.CollisionContext.of(p)).isEmpty(),"Side and underside have no blocking face");
        h.assertTrue(block.getCollisionShape(h.getLevel(),pos).isEmpty(),"Context-free projectile/block collision remains empty");h.succeed();
    }
    @GameTest(templateNamespace="kaleidoscope_witchcraft",template="empty",timeoutTicks=20)
    public static void frostHighSpeedResolvedRoute(GameTestHelper h){
        var start=h.absolutePos(new net.minecraft.core.BlockPos(2,12,2)).atY(200);var p=player(h);
        // Load the test fixture explicitly; the implementation must never load its own route.
        for(int x=0;x<=34;x++){
            h.getLevel().getChunkAt(start.east(x));
            for(int z=-1;z<=5;z++)for(int y=0;y<=3;y++)h.getLevel().setBlock(start.offset(x,y,z),net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(),3);
        }
        p.setPos(start.getX()+.5,start.getY()+1,start.getZ()+.5);
        var w=WineAbilities.state(p).frost;w.refresh(4,600,150);
        p.move(net.minecraft.world.entity.MoverType.SELF,new net.minecraft.world.phys.Vec3(32,-.08,0));
        h.assertTrue(Math.abs(p.getX()-(start.getX()+32.5))<1e-6 && Math.abs(p.getY()-(start.getY()+1))<1e-6,"A 32-block native movement stays supported without speed clamping: start="+start+", end="+p.position()+", quota="+w.credits+", first="+h.getLevel().getBlockState(start));
        for(int x=0;x<=32;x++)h.assertTrue(h.getLevel().getBlockState(start.east(x)).is(FrostPath.BLOCK.get()),"Crossed columns must form a continuous route");
        h.assertTrue(w.credits==117,"Only 33 distinct successful placements are charged");
        var wallStart=start.south(4);p.setPos(wallStart.getX()+.5,wallStart.getY()+1,wallStart.getZ()+.5);
        for(int y=1;y<=3;y++)h.getLevel().setBlock(wallStart.east(8).above(y),net.minecraft.world.level.block.Blocks.STONE.defaultBlockState(),3);
        p.move(net.minecraft.world.entity.MoverType.SELF,new net.minecraft.world.phys.Vec3(20,-.08,0));
        h.assertTrue(p.getX()<wallStart.getX()+8,"Native solid wall still stops movement");
        h.assertTrue(h.getLevel().getBlockState(wallStart.east(12)).isAir(),"Untravelled space beyond a wall must not consume a bridge quota");h.succeed();
    }
    @GameTest(templateNamespace="kaleidoscope_witchcraft",template="empty",timeoutTicks=20)
    public static void frostSpeedAdaptiveRoute(GameTestHelper h){
        var start=h.absolutePos(new net.minecraft.core.BlockPos(2,12,2)).atY(220);var p=player(h);
        for(int x=-2;x<=642;x++){
            h.getLevel().getChunkAt(start.east(x));
            for(int z=-1;z<=1;z++)for(int y=0;y<=3;y++)h.getLevel().setBlock(start.offset(x,y,z),net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(),3);
        }
        p.setPos(start.getX()+.5,start.getY()+1,start.getZ()+.5);
        var state=WineAbilities.state(p);state.frost.refresh(6,600,0);
        double travelled=0;
        for(double distance:new double[]{.25,7.25,32,600}){
            p.move(net.minecraft.world.entity.MoverType.SELF,new net.minecraft.world.phys.Vec3(distance,-.08,0));travelled+=distance;
            h.assertTrue(Math.abs(p.getX()-(start.getX()+.5+travelled))<1e-6 && Math.abs(p.getY()-(start.getY()+1))<1e-6,"Movement distance remains intact at each speed");
        }
        for(int x=0;x<=640;x++)for(int z=-1;z<=1;z++)
            h.assertTrue(h.getLevel().getBlockState(start.offset(x,0,z)).is(FrostPath.BLOCK.get()),"Entire route and quality-six side support survive cache overflow at "+x+","+z);
        h.assertTrue(state.footing.visited.size()<=512,"Long paths retain bounded memoization, not unbounded per-player state");
        h.assertTrue(state.footing.formations<=4,"Formation particles have a separate per-tick allowance");
        h.succeed();
    }
    @GameTest(templateNamespace="kaleidoscope_witchcraft",template="empty",timeoutTicks=20)
    public static void duskMarksPersistence(GameTestHelper h){
        var marks=new DuskEmbers.Marks();var owner=UUID.randomUUID();var other=UUID.randomUUID();
        var one=new DuskEmbers.Mark();one.quality=6;one.layers=4;one.energy=18.75;one.delay=12;one.life=62;marks.values.put(owner,one);
        var invalid=new DuskEmbers.Mark();invalid.energy=Double.NaN;marks.values.put(other,invalid);
        var loaded=new DuskEmbers.Marks();loaded.deserializeNBT(h.getLevel().registryAccess(),marks.serializeNBT(h.getLevel().registryAccess()));
        h.assertTrue(loaded.values.size()==1 && loaded.values.get(owner).energy==18.75 && loaded.values.get(owner).delay==12,"Owner-local charge and pending delay survive, nonfinite damage is rejected");
        var foreign=new CompoundTag();foreign.putInt("Version",1);foreign.putString("Marks","unknown");loaded.deserializeNBT(h.getLevel().registryAccess(),foreign);
        h.assertTrue(loaded.values.isEmpty() && loaded.serializeNBT(h.getLevel().registryAccess()).equals(foreign),"Unsupported mark schema remains inert and recoverable");h.succeed();
    }
    @GameTest(templateNamespace="kaleidoscope_witchcraft",template="empty",timeoutTicks=120)
    public static void frostBridgeRenewal(GameTestHelper h){
        var pos=h.absolutePos(new net.minecraft.core.BlockPos(2,2,2));
        h.getLevel().setBlock(pos,FrostPath.BLOCK.get().defaultBlockState(),3);
        var expiry=(FrostPath.Expiry)h.getLevel().getBlockEntity(pos);expiry.touch(60);
        h.runAtTickTime(40,()->expiry.touch(60));
        h.runAtTickTime(75,()->h.assertTrue(h.getLevel().getBlockState(pos).is(FrostPath.BLOCK.get()),"Renewal must survive the original scheduled expiry"));
        h.runAtTickTime(110,()->{h.assertTrue(h.getLevel().getBlockState(pos).isAir(),"Abandoned bridge expires without any player tick or refund");h.succeed();});
    }
    @GameTest(templateNamespace="kaleidoscope_witchcraft",template="empty",timeoutTicks=20)
    public static void frostBridgeProbeInvalidation(GameTestHelper h){
        var f=new FrostPath.Footing();var pos=new net.minecraft.core.BlockPos(2,2,2);var dim=h.getLevel().dimension();
        h.assertTrue(f.probe(pos,dim,100),"First position probes immediately");
        h.assertTrue(!f.probe(pos,dim,101),"Stationary renewal/failed placement does not post world callbacks every tick");
        h.assertTrue(f.probe(pos.east(),dim,102),"Movement does not wait for retry interval");
        f.reset();h.assertTrue(f.probe(pos,dim,103),"Ending descent renews support immediately");
        h.assertTrue(f.probe(pos,dim,113),"Stationary repair is retried within ten ticks");h.succeed();
    }
    @GameTest(templateNamespace="kaleidoscope_witchcraft",template="empty",timeoutTicks=20)
    public static void amberSealPersistence(GameTestHelper h){
        var state=new AmberVaults.State();state.quality=6;state.remaining=14400;state.charges=2;
        var copy=new AmberVaults.State();
        copy.deserializeNBT(h.getLevel().registryAccess(),state.serializeNBT(h.getLevel().registryAccess()));
        h.assertTrue(copy.remaining==14400 && copy.charges==2,"720-second opportunity must survive save/load intact");
        CompoundTag future=new CompoundTag();future.putInt("Version",99);future.putString("Payload","retain");
        copy.deserializeNBT(h.getLevel().registryAccess(),future);
        h.assertTrue(copy.remaining==0 && copy.serializeNBT(h.getLevel().registryAccess()).equals(future),"Unknown data stays inert and round-trips");
        copy.deserializeNBT(h.getLevel().registryAccess(),state.serializeNBT(h.getLevel().registryAccess()));
        h.assertTrue(copy.unsupported==null && copy.remaining==14400,"Repeated load resets transient/rejected state");h.succeed();
    }
    @GameTest(templateNamespace="kaleidoscope_witchcraft",template="empty",timeoutTicks=20)
    public static void amberVaultInventory(GameTestHelper h){
        var c=new AmberVaultContents(18,()->{},p->true);c.owner=UUID.randomUUID();c.quality=2;c.takeOnly=true;
        for(int i=0;i<18;i++)c.setItem(i,new ItemStack(Items.STONE,64));
        c.receiveDeath(List.of(new ItemStack(Items.DIAMOND,3)));
        h.assertTrue(c.hasOverflow() && !c.canPlaceItem(0,new ItemStack(Items.DIRT)),"Death storage retains overflow and enforces take-only");
        var saved=c.save(h.getLevel().registryAccess());var restored=new AmberVaultContents(18,()->{},p->true);
        restored.load(saved,h.getLevel().registryAccess());restored.removeItemNoUpdate(0);restored.refill();
        h.assertTrue(restored.getItem(0).is(Items.DIAMOND) && restored.getItem(0).getCount()==3 && !restored.hasOverflow(),"Overflow moves once after an inventory transaction");
        restored.refill();h.assertTrue(restored.getItem(0).getCount()==3,"Repeated wake does not duplicate contents");
        saved.putString("Overflow","unknown-format");restored.load(saved,h.getLevel().registryAccess());
        h.assertTrue(!restored.entirelyEmpty() && restored.save(h.getLevel().registryAccess()).equals(saved),"Malformed inventory data is retained, never silently discarded");h.succeed();
    }
}
