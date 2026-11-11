package dev.senluo.witchcraft.brew;

import dev.senluo.witchcraft.mixin.brew.SlowMovementAccess;
import dev.senluo.witchcraft.mixin.brew.MovementIntentAccess;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/** Server-authorized resistance is synchronized through an attribute for client movement prediction. */
public final class TemporalMobility {
    private static final ResourceLocation RESIST=ResourceLocation.fromNamespaceAndPath("kaleidoscope_witchcraft","slow_resistance");
    private TemporalMobility() {}
    public static double resistance(LivingEntity entity) {
        if(WineAbilities.sun(entity)>=6)return 1;
        if(!(entity instanceof Player) || !entity.isAlive() || entity.isSpectator() || entity.isPassenger() || entity.isFallFlying())return 0;
        var attribute=entity.getAttribute(BrewContent.SLOW_RESISTANCE);
        return attribute==null?0:attribute.getValue();
    }
    public static double restoreFactor(LivingEntity entity,double factor) {
        return factor>=0 && factor<1?factor+(1-factor)*resistance(entity):factor;
    }
    public static boolean activeUp(LivingEntity entity) {
        if(entity.isShiftKeyDown())return false;
        boolean climb=entity.onClimbable(), fluid=entity.isInWaterOrBubble() || entity.isInLava();
        if(!climb && !fluid)return false;
        return ((MovementIntentAccess)entity).witchcraft$jumping()
                || climb && entity.horizontalCollision && entity.zza>0
                || fluid && entity.isSwimming() && entity.zza>0 && entity.getLookAngle().y>0;
    }
    public static double restoreLimit(LivingEntity entity,double limited,double original) {
        if(!Double.isFinite(limited) || !Double.isFinite(original) || Math.abs(original)<=Math.abs(limited))return limited;
        return limited+(original-limited)*resistance(entity);
    }
    public static boolean slowed(ServerPlayer player) {
        if(player.onClimbable() || player.isInWaterOrBubble() || player.isInLava())return true;
        SlowMovementAccess access=(SlowMovementAccess)player;
        if(access.witchcraft$blockSpeedFactor()<1)return true;
        Vec3 stuck=access.witchcraft$stuckMultiplier();
        if(stuck.lengthSqr()>1e-7 && (stuck.x<1 || stuck.y<1 || stuck.z<1))return true;
        // Inside-block callbacks can clear the multiplier before the server's post-tick observation.
        return player.level().getBlockStatesIfLoaded(player.getBoundingBox().deflate(1e-6))
                .anyMatch(state->state.is(Blocks.COBWEB) || state.is(Blocks.SWEET_BERRY_BUSH) || state.is(Blocks.POWDER_SNOW));
    }
    public static boolean update(ServerPlayer player,BodyTime time) {
        boolean enabled=time!=null && time.active() && time.grade()>=4 && player.isAlive() && !player.isSpectator()
                && !player.isPassenger() && !player.isFallFlying() && (time.free() || time.canSpend(time.compression()*2));
        setResistance(player,enabled ? (time.grade()>=6?.75:time.grade()>=5?.5:.25) : 0);
        return enabled && slowed(player);
    }
    public static void clear(ServerPlayer player) {setResistance(player,0);}
    private static void setResistance(ServerPlayer player,double value) {
        var attribute=player.getAttribute(BrewContent.SLOW_RESISTANCE);
        if(attribute==null)return;
        var existing=attribute.getModifier(RESIST);
        if(existing!=null && existing.amount()==value)return;
        if(existing!=null)attribute.removeModifier(RESIST);
        if(value>0)attribute.addTransientModifier(new AttributeModifier(RESIST,value,AttributeModifier.Operation.ADD_VALUE));
    }
}
