package dev.senluo.witchcraft.reservoir;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Player;

/** Successful actions only, addressed to the operator. Specialized sounds replace the fallback. */
public final class ReservoirFeedback {
    public enum Action { OPEN, CLOSE, SELECT, LABEL, TOGGLE_ON, TOGGLE_OFF, PLUGIN, CONTAINER, POUR }
    static void committed(Player player,ReservoirActions.Result result){
        if(!result.changed())return;
        play(player,switch(result){
            case SELECTED -> Action.SELECT;
            case LABELED -> Action.LABEL;
            case PLUGIN_INSTALLED -> Action.PLUGIN;
            case PLUGIN_ENABLED -> Action.TOGGLE_ON;
            case PLUGIN_DISABLED -> Action.TOGGLE_OFF;
            case FILLED -> Action.POUR;
            case NONE -> throw new IllegalArgumentException("Uncommitted action");
        });
    }
    public static void play(Player player,Action action){
        if(!(player instanceof ServerPlayer server))return;
        SoundEvent sound;float volume=.25F,pitch=1;
        switch(action){
            case OPEN -> {sound=SoundEvents.BUNDLE_INSERT;pitch=.85F;}
            case CLOSE -> {sound=SoundEvents.BUNDLE_REMOVE_ONE;pitch=.85F;}
            case SELECT -> {sound=SoundEvents.UI_BUTTON_CLICK.value();volume=.18F;pitch=1.15F;}
            case LABEL -> {sound=SoundEvents.BOOK_PAGE_TURN;volume=.3F;}
            case TOGGLE_ON -> {sound=SoundEvents.LEVER_CLICK;pitch=.65F;}
            case TOGGLE_OFF -> {sound=SoundEvents.LEVER_CLICK;pitch=.5F;}
            case PLUGIN -> {sound=SoundEvents.ARMOR_EQUIP_IRON.value();volume=.3F;}
            case CONTAINER -> {sound=optional("jar",SoundEvents.BOTTLE_FILL);volume=.15F;}
            case POUR -> {sound=optional("magic.essentia.pour",SoundEvents.BOTTLE_EMPTY);volume=.18F;}
            default -> throw new IllegalArgumentException("Unknown reservoir feedback");
        }
        server.playNotifySound(sound,SoundSource.PLAYERS,volume,pitch);
    }
    private static SoundEvent optional(String path,SoundEvent fallback){
        return BuiltInRegistries.SOUND_EVENT.getOptional(ResourceLocation.fromNamespaceAndPath("thaumcraft",path)).orElse(fallback);
    }
    private ReservoirFeedback(){}
}
