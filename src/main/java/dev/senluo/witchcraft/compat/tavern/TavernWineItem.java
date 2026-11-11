package dev.senluo.witchcraft.compat.tavern;

import com.github.ysbbbbbb.kaleidoscopetavern.item.DrinkBlockItem;
import dev.senluo.witchcraft.brew.*;
import java.util.List;
import java.util.Locale;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.*;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.*;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;

public final class TavernWineItem extends DrinkBlockItem {
    public TavernWineItem(Block block) { super(block); }
    private static WineData syncQuality(ItemStack stack) {
        WineData wine = WineData.read(stack);
        if (wine != null && getBrewLevel(stack) != wine.quality()) TavernWine.setQuality(stack, wine.quality());
        return wine;
    }
    @Override public void inventoryTick(ItemStack stack, Level level, net.minecraft.world.entity.Entity entity, int slot, boolean selected) {
        super.inventoryTick(stack, level, entity, slot, selected);
        if (!level.isClientSide) syncQuality(stack);
    }
    @Override public InteractionResult useOn(net.minecraft.world.item.context.UseOnContext context) {
        syncQuality(context.getItemInHand());
        return super.useOn(context);
    }
    private boolean drinkable(ItemStack stack, LivingEntity entity) {
        if (WineData.read(stack) == null) return false;
        BodyTime time = entity instanceof Player player ? ExtraTime.existing(player) : null;
        return time == null || time.acceptsWine();
    }
    @Override public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        if (!drinkable(player.getItemInHand(hand), player)) return InteractionResultHolder.fail(player.getItemInHand(hand));
        return super.use(level, player, hand);
    }
    @Override public ItemStack finishUsingItem(ItemStack stack, Level level, LivingEntity entity) {
        syncQuality(stack);
        return drinkable(stack, entity) ? super.finishUsingItem(stack, level, entity) : stack;
    }
    @Override protected void addDrinkEffect(ItemStack stack, Level level, LivingEntity entity) {
        // Tavern owns consumption, statistics and containers; the body ledger owns this drink's effect.
        WineData current = WineData.read(stack);
        if (current != null && current.quality() == 1) {
            var data = com.github.ysbbbbbb.kaleidoscopetavern.datamap.resources.DrinkEffectDataReloadListener.INSTANCE.get(stack.getItem());
            if (!level.isClientSide && data != null && !data.effects().isEmpty())
                for (var entry : data.effects().getFirst()) {
                    if (level.random.nextFloat() >= entry.probability()) continue;
                    if (entry.effect().value().isInstantenous())
                        entry.effect().value().applyInstantenousEffect(entity, entity, entity, entry.amplifier(), 1);
                    else entity.addEffect(new MobEffectInstance(entry.effect(), effectDuration(entry, current), entry.amplifier()));
                }
        } else super.addDrinkEffect(stack, level, entity);
        if (entity instanceof ServerPlayer player) {
            WineData wine = WineData.read(stack);
            if (wine != null) {
                BodyTime time = player.getData(BrewContent.BODY_TIME);
                time.drink(wine); ExtraTime.sync(player, time);
            }
        }
    }
    private static int effectDuration(com.github.ysbbbbbb.kaleidoscopetavern.datamap.data.DrinkEffectData.Entry entry, WineData wine) {
        return wine.quality() == 1
                && entry.effect().equals(net.minecraft.world.effect.MobEffects.CONFUSION)
                && entry.duration() == 30 && entry.amplifier() == 0 ? 100 : entry.duration() * 20;
    }

    @Override public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> lines, TooltipFlag flag) {
        WineData wine = syncQuality(stack);
        super.appendHoverText(stack, context, lines, flag);
        if (wine == null) { lines.add(LiujinVodkaItem.text("unbrewed")); return; }
        int qualityLine = -1;
        for (int i=0;i<lines.size();i++) {
            if (lines.get(i).getContents() instanceof net.minecraft.network.chat.contents.TranslatableContents text
                    && text.getKey().equals("tooltip.kaleidoscope_tavern.bottle_block.brew_level")) qualityLine=i;
        }
        if (qualityLine >= 0 && wine.quality() == 1) {
            var data = com.github.ysbbbbbb.kaleidoscopetavern.datamap.resources.DrinkEffectDataReloadListener.INSTANCE.get(stack.getItem());
            if (data != null && !data.effects().isEmpty()) {
                lines.subList(qualityLine + 1, lines.size()).clear();
                var effects = data.effects().getFirst().stream().filter(entry -> entry.probability() >= 1)
                        .map(entry -> new MobEffectInstance(entry.effect(), effectDuration(entry, wine), entry.amplifier())).toList();
                if (!effects.isEmpty()) {
                    lines.add(CommonComponents.space());
                    PotionContents.addPotionTooltip(effects, lines::add, 1, context.tickRate());
                }
            }
        }
        boolean nativeEffects = qualityLine >= 0 && lines.size() > qualityLine + 1;
        lines.add(qualityLine < 0 ? lines.size() : qualityLine + 1,
                Component.translatable("tooltip.kaleidoscope_witchcraft.wine.score", String.format(Locale.ROOT,"%.2f",wine.score())).withStyle(ChatFormatting.GRAY));
        if (!nativeEffects) lines.add(CommonComponents.space());
        int duration = wine.durationTicks();
        if (wine.grade()>0) PotionContents.addPotionTooltip(List.of(new MobEffectInstance(BrewContent.YEARS_ABUNDANCE,duration,Math.min(255,wine.grade()-1))),lines::add,1.0F,context.tickRate());
    }
}
