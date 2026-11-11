package dev.senluo.witchcraft.gametest;

import com.mojang.authlib.GameProfile;
import dev.senluo.witchcraft.brew.*;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.common.util.FakePlayer;

final class FlightStrideCases {
    private static FakePlayer player(GameTestHelper h) {
        var p=new FakePlayer(h.getLevel(),new GameProfile(UUID.randomUUID(),"flight-stride"));
        p.setPos(h.absolutePos(new BlockPos(4,8,4)).getCenter());
        p.setOnGround(false);
        p.getData(BrewContent.BODY_TIME).drink(1000);
        return p;
    }
    static void eligibility(GameTestHelper h) {
        var p=player(h);
        var speed=p.getAttribute(Attributes.MOVEMENT_SPEED);
        speed.addTransientModifier(new AttributeModifier(ResourceLocation.fromNamespaceAndPath("kaleidoscope_witchcraft","time_stride"),
                15,AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
        p.getAbilities().flying=true;p.setSprinting(true);
        h.assertTrue(TimeStride.flightMultiplier(p)==16,"Creative flight uses the authorized six-grade ramp");
        p.setShiftKeyDown(true);
        h.assertTrue(TimeStride.flightMultiplier(p)==16,"Creative descent is not a ground sneak cancellation");
        p.setShiftKeyDown(false);p.getAbilities().flying=false;
        h.assertTrue(TimeStride.flightMultiplier(p)==1,"Ordinary airborne jumps do not gain flight displacement");
        p.setItemSlot(EquipmentSlot.CHEST,new ItemStack(Items.ELYTRA));p.startFallFlying();
        h.assertTrue(TimeStride.flightMultiplier(p)==16,"Gliding shares the same ramp");
        p.setSprinting(false);
        h.assertTrue(TimeStride.flightMultiplier(p)==1,"Releasing flight sprint immediately removes displacement boost");
        p.setSprinting(true);p.getData(BrewContent.BODY_TIME).clear();
        h.assertTrue(TimeStride.flightMultiplier(p)==1,"A leftover modifier cannot authorize flight after the ledger expires");
        TimeStride.clear(p);h.succeed();
    }
    static void ramp(GameTestHelper h) {
        var p=player(h);var time=p.getData(BrewContent.BODY_TIME);
        p.setOnGround(true);p.setSprinting(true);TimeStride.update(p,time);
        h.runAfterDelay(1,()->{
            p.setPos(p.position().add(.3,0,0));
            double before=time.baseTicks();ExtraTime.tick(new net.neoforged.neoforge.event.tick.PlayerTickEvent.Post(p));
            h.assertTrue(Math.abs(before-time.baseTicks()-2*time.compression()*time.costFactor())<1e-7,
                    "Ground movement pays the discounted double maintenance");
        });
        h.runAfterDelay(2,()->{
            p.setOnGround(false);p.getAbilities().flying=true;
            h.assertTrue(TimeStride.flightMultiplier(p)>1,"Takeoff immediately inherits the ground ramp");
            double previous=TimeStride.flightMultiplier(p),before=time.baseTicks();
            p.setPos(p.position().add(0,.3,0));ExtraTime.tick(new net.neoforged.neoforge.event.tick.PlayerTickEvent.Post(p));
            h.assertTrue(TimeStride.flightMultiplier(p)>previous,"Pure vertical creative motion continues the inherited ramp");
            h.assertTrue(Math.abs(before-time.baseTicks()-2*time.compression()*time.costFactor())<1e-7,
                    "Flight pays the same discounted double maintenance");
            h.assertTrue(p.getAttribute(Attributes.STEP_HEIGHT).getModifier(ResourceLocation.fromNamespaceAndPath(
                    "kaleidoscope_witchcraft","time_stride_step"))==null,"Flight does not add ground step height");
        });
        h.runAfterDelay(3,()->{
            double before=TimeStride.flightMultiplier(p);
            p.getAbilities().flying=false;p.setItemSlot(EquipmentSlot.CHEST,new ItemStack(Items.ELYTRA));p.startFallFlying();
            p.setPos(p.position().add(.3,0,0));TimeStride.update(p,time);
            h.assertTrue(TimeStride.flightMultiplier(p)>before,"Gliding continues the airborne ramp without requiring ground contact");
            TimeStride.clear(p);h.succeed();
        });
    }
}
