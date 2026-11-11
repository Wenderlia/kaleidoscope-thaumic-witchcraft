package dev.senluo.witchcraft.mixin.brew.client;

import dev.senluo.witchcraft.brew.BrewContent;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.effect.MobEffects;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LocalPlayer.class)
public abstract class StrideContinuityMixin {
    @Unique private boolean witchcraft$wasSprinting;
    @Unique private boolean witchcraft$wasColliding;
    @Unique private boolean witchcraft$flightSprint;
    @Unique private boolean witchcraft$wasAirborne;
    @Unique private boolean witchcraft$wasFlying;
    @Inject(method="aiStep",at=@At(value="INVOKE",target="Lnet/minecraft/client/player/AbstractClientPlayer;aiStep()V"))
    private void witchcraft$flightSprintKey(CallbackInfo ci) {
        LocalPlayer self=(LocalPlayer)(Object)this;
        var effect=self.getEffect(BrewContent.YEARS_ABUNDANCE);
        boolean flight=self.getAbilities().flying || self.isFallFlying();
        boolean enabled=flight && effect!=null && effect.getAmplifier()>=2 && self.isAlive()
                && !self.isSpectator() && !self.isPassenger() && !self.isInWaterOrBubble() && !self.isInLava();
        if(enabled) {
            // Uses the configured sprint binding (including vanilla toggle mode), with
            // normal START/STOP_SPRINTING packets. Gliding needs no forward-key input.
            self.setSprinting(Minecraft.getInstance().options.keySprint.isDown()
                    && (!self.input.shiftKeyDown || self.getAbilities().flying));
        } else if(witchcraft$flightSprint && flight)self.setSprinting(false);
        witchcraft$flightSprint=enabled;
    }
    @Inject(method="aiStep",at=@At("HEAD"))
    private void witchcraft$rememberSprint(CallbackInfo ci) {
        LocalPlayer self=(LocalPlayer)(Object)this;
        witchcraft$wasSprinting=self.isSprinting();
        witchcraft$wasColliding=self.horizontalCollision;
        witchcraft$wasAirborne=!self.onGround();
        witchcraft$wasFlying=self.getAbilities().flying || self.isFallFlying();
    }
    @Inject(method="aiStep",at=@At("TAIL"))
    private void witchcraft$continueThroughCollision(CallbackInfo ci) {
        LocalPlayer self=(LocalPlayer)(Object)this;
        boolean landed=witchcraft$wasAirborne && self.onGround();
        boolean leftFlight=witchcraft$wasFlying && !self.getAbilities().flying && !self.isFallFlying();
        if(self.isSprinting() || (!witchcraft$wasColliding && !self.horizontalCollision && !landed && !leftFlight))return;
        var effect=self.getEffect(BrewContent.YEARS_ABUNDANCE);
        if(effect==null || effect.getAmplifier()<2 || !self.isAlive() || self.isSpectator()
                || self.isPassenger() || self.isFallFlying() || self.isInWaterOrBubble() || self.isInLava()
                || self.isUsingItem() || self.isShiftKeyDown() || self.input.shiftKeyDown
                || self.input.forwardImpulse<.8F || self.hasEffect(MobEffects.BLINDNESS)
                || (!self.getAbilities().mayfly && self.getFoodData().getFoodLevel()<=6))return;
        // Respect released forward input and sneaking. Only bridge collision/landing-driven sprint loss,
        // using the normal sprint state/network path; never synthesize movement or key presses.
        if(witchcraft$wasSprinting || Minecraft.getInstance().options.keySprint.isDown())self.setSprinting(true);
    }
}
