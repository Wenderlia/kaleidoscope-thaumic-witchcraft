package dev.senluo.witchcraft.brew;

import dev.senluo.witchcraft.time.TemporalText;
import java.util.List;
import java.util.Locale;
import net.minecraft.advancements.CriteriaTriggers;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stats;
import net.minecraft.world.*;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.*;
import net.minecraft.world.level.Level;

public final class LiujinVodkaItem extends Item {
    public LiujinVodkaItem(Properties properties) { super(properties.stacksTo(16)); }
    @Override public int getUseDuration(ItemStack stack, LivingEntity entity) { return 32; }
    @Override public UseAnim getUseAnimation(ItemStack stack) { return UseAnim.DRINK; }
    @Override public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        if (WineData.read(player.getItemInHand(hand)) == null) return InteractionResultHolder.fail(player.getItemInHand(hand));
        BodyTime ledger = ExtraTime.existing(player);
        if (ledger != null && !ledger.acceptsWine()) return InteractionResultHolder.fail(player.getItemInHand(hand));
        return ItemUtils.startUsingInstantly(level, player, hand);
    }
    @Override public ItemStack finishUsingItem(ItemStack stack, Level level, LivingEntity entity) {
        WineData wine = WineData.read(stack);
        if (wine == null || !(entity instanceof Player player)) return stack;
        if (player instanceof ServerPlayer server) {
            BodyTime time = server.getData(BrewContent.BODY_TIME);
            if (!time.acceptsWine()) return stack;
            CriteriaTriggers.CONSUME_ITEM.trigger(server, stack);
            server.awardStat(Stats.ITEM_USED.get(this));
            time.drink(wine); ExtraTime.sync(server, time);
        }
        if (player.getAbilities().instabuild) return stack;
        stack.shrink(1);
        Item empty = BuiltInRegistries.ITEM.get(ResourceLocation.fromNamespaceAndPath("kaleidoscope_tavern", "empty_bottle"));
        ItemStack bottle = new ItemStack(empty == Items.AIR ? Items.GLASS_BOTTLE : empty);
        if (stack.isEmpty()) return bottle;
        if (!level.isClientSide && !player.getInventory().add(bottle)) player.drop(bottle, false);
        return stack;
    }
    public static ItemStack sample() {
        ItemStack stack = new ItemStack(dev.senluo.witchcraft.time.TemporalContent.LIUJIN_VODKA.get()); new WineData(1200, 1, 1).write(stack); return stack;
    }
    public static ItemStack gradeThirteenSample() {
        ItemStack stack = new ItemStack(dev.senluo.witchcraft.time.TemporalContent.LIUJIN_VODKA.get());
        // Version 3 score is about 25,318, within grade 13; use real wine inputs and the normal writer.
        new WineData(120_000_000, 1, 1).write(stack);
        return stack;
    }
    @Override public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> lines, TooltipFlag flag) {
        WineData wine = WineData.read(stack);
        if (wine == null) { lines.add(text("unbrewed")); return; }
        lines.add(text("quality", TemporalText.number(wine.quality(), TemporalText.STAGE),
                TemporalText.number(String.format(Locale.ROOT, "%.2f", wine.score()), TemporalText.AMOUNT)));
        lines.add(text("effect", TemporalText.number(wine.grade(), TemporalText.STAGE),
                wine.grade() >= 13 ? text("free") : TemporalText.duration(wine.durationTicks())));
        lines.add(text("hint", TemporalText.key(false, false)));
    }
    public static Component text(String key, Object... values) {
        return Component.translatable("tooltip.kaleidoscope_witchcraft.wine." + key, values).withStyle(s -> s.withColor(TemporalText.BODY));
    }
}
