package dev.senluo.witchcraft.mixin.brew;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.senluo.witchcraft.brew.MeleeWindow;
import dev.senluo.witchcraft.brew.TimeMelee;
import dev.senluo.witchcraft.combat.DamageContext;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(LivingEntity.class)
public abstract class MeleeWindowMixin implements MeleeWindow, DamageContext {
    @Unique private int witchcraft$window=20;
    @Unique private long witchcraft$serial;
    @Unique private boolean witchcraft$personalAttack;
    @Unique private int witchcraft$hurtDepth;
    @Unique private DamageSource witchcraft$source;
    @Override public int witchcraft$windowStart() {return witchcraft$window;}
    @Override public long witchcraft$windowSerial() {return witchcraft$serial;}
    @Override public DamageSource witchcraft$currentDamageSource() {return witchcraft$source;}
    @WrapMethod(method="hurt")
    private boolean witchcraft$damageScope(DamageSource source,float damage,Operation<Boolean> original) {
        boolean before=witchcraft$personalAttack;
        DamageSource previousSource=witchcraft$source;
        witchcraft$source=source;
        witchcraft$hurtDepth++;
        witchcraft$personalAttack=witchcraft$hurtDepth==1 && source.is(DamageTypes.PLAYER_ATTACK)
                && TimeMelee.bypass((LivingEntity)(Object)this,source);
        try {return original.call(source,damage);}
        finally {witchcraft$personalAttack=before;witchcraft$source=previousSource;witchcraft$hurtDepth--;}
    }
    @ModifyExpressionValue(method="hurt",at=@At(value="INVOKE",
            target="Lnet/neoforged/neoforge/common/damagesource/DamageContainer;getPostAttackInvulnerabilityTicks()I"))
    private int witchcraft$rememberWindow(int ticks) {witchcraft$window=ticks;witchcraft$serial++;return ticks;}
    @ModifyExpressionValue(method="hurt",at=@At(value="FIELD",opcode=Opcodes.GETFIELD,
            target="Lnet/minecraft/world/entity/LivingEntity;invulnerableTime:I"))
    private int witchcraft$personalWindow(int ticks) {
        LivingEntity self=(LivingEntity)(Object)this;
        if(witchcraft$personalAttack && ticks>10 && TimeMelee.bypass(self,witchcraft$source)) {
            TimeMelee.usedBypass(self);
            return 10;
        }
        return ticks;
    }
}
