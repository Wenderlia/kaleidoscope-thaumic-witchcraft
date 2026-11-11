package dev.senluo.witchcraft.compat.tavern;

import com.github.ysbbbbbb.kaleidoscopetavern.item.DrinkBlockItem;
import com.github.ysbbbbbb.kaleidoscopetavern.datamap.resources.DrinkEffectDataReloadListener;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;

/** Native placement/quality/container semantics, without Vodka's separate wine ledger. */
public final class AmberWineItem extends DrinkBlockItem {
    private final dev.senluo.witchcraft.brew.AmberVintage vintage;
    public AmberWineItem(Block block, dev.senluo.witchcraft.brew.AmberVintage vintage) {
        super(block);
        this.vintage=vintage;
    }
    /** Creative samples follow authored effect data; unfinished varieties remain display-only. */
    public static ItemStack sample(Item item) {
        ItemStack stack=item.getDefaultInstance();
        var data=DrinkEffectDataReloadListener.INSTANCE.get(item);
        if (data!=null && !data.effects().isEmpty()) setBrewLevel(stack,Math.min(6,data.effects().size()));
        return stack;
    }
    private boolean drinkable(ItemStack stack) {
        var data=DrinkEffectDataReloadListener.INSTANCE.get(this);
        return getBrewLevel(stack)>0 && data!=null && !data.effects().isEmpty();
    }
    @Override public void appendHoverText(ItemStack stack, TooltipContext context,
            java.util.List<net.minecraft.network.chat.Component> lines, net.minecraft.world.item.TooltipFlag flag) {
        super.appendHoverText(stack, context, lines, flag);
        lines.add(vintage.lore());
    }
    @Override public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        return drinkable(player.getItemInHand(hand)) ? super.use(level, player, hand)
                : InteractionResultHolder.pass(player.getItemInHand(hand));
    }
    @Override public ItemStack finishUsingItem(ItemStack stack, Level level, LivingEntity entity) {
        return drinkable(stack) ? super.finishUsingItem(stack, level, entity) : stack;
    }
    @Override protected void addDrinkEffect(ItemStack drink, Level level, LivingEntity entity) {
        if (vintage != dev.senluo.witchcraft.brew.AmberVintage.EVERDAY) {
            super.addDrinkEffect(drink,level,entity);
            if (entity instanceof net.minecraft.server.level.ServerPlayer player)
                dev.senluo.witchcraft.brew.WineAbilities.drink(player,vintage,Math.clamp(getBrewLevel(drink),0,6));
            return;
        }
        if (level.isClientSide) return;
        var data=DrinkEffectDataReloadListener.INSTANCE.get(this);
        if (data==null || data.effects().isEmpty() || getBrewLevel(drink)<=0) return;
        int quality=Math.min(getBrewLevel(drink),data.effects().size());
        // Tavern normally executes instant effects once. Retain their authored duration here;
        // vanilla owns ticking, merging, persistence and removal, without an extra scheduler.
        for (var effect : getEffectInstances(level,quality)) {
            // Tavern's data codec stores whole seconds. Preserve the approved 170-tick
            // grade-V window locally; vanilla duration text also floors to whole seconds.
            if (quality==5 && effect.is(net.minecraft.world.effect.MobEffects.HEAL) && effect.getDuration()==160)
                effect=new net.minecraft.world.effect.MobEffectInstance(effect.getEffect(),170,effect.getAmplifier(),effect.isAmbient(),effect.isVisible(),effect.showIcon());
            entity.addEffect(effect);
        }
        if (entity instanceof net.minecraft.server.level.ServerPlayer player)
            dev.senluo.witchcraft.brew.WineAbilities.drink(player,vintage,quality);
    }
}
