package dev.senluo.witchcraft.time;

import dev.senluo.witchcraft.brew.BodyTime;
import dev.senluo.witchcraft.brew.BrewContent;
import dev.senluo.witchcraft.brew.ExtraTime;
import dev.senluo.witchcraft.brew.VintageEffects;
import java.util.List;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;

public class FruitItem extends Item {
    public static final int FIFTH_SATURATION_TICKS = 90 * 20;
    private static final FoodProperties[] FOOD = {null, createFood(1), createFood(2), createFood(3), createFood(4), createFood(5)};

    public FruitItem(Properties properties) { super(properties.food(food(1)).component(DataComponents.CUSTOM_DATA, data(1, 2))); }
    private static FoodProperties food(int grade) { return FOOD[grade]; }

    private static FoodProperties createFood(int grade) {
        int nutrition=grade == 5 ? 100 : grade+1;
        float saturation=switch(grade) {case 1 -> 6; case 2 -> 10; case 3 -> 15; case 4 -> 20; default -> 100;};
        var builder = new FoodProperties.Builder().nutrition(nutrition).saturationModifier(saturation/(2*nutrition));
        if (grade == 5) builder.effect(() -> new MobEffectInstance(MobEffects.SATURATION,FIFTH_SATURATION_TICKS,0),1.0F);
        else builder.effect(() -> VintageEffects.fruitMarker(grade), 1.0F);
        return builder.build();
    }

    @Override public void verifyComponentsAfterLoad(ItemStack stack) {
        if (economyVersion(stack) != 2) return;
        FoodProperties current = stack.get(DataComponents.FOOD), standard = food(grade(stack));
        // NeoForge decodes effect suppliers with new identities. Canonicalize only equal current food,
        // not old recipes or externally customized nutrition, effects, containers or use times.
        if (current != standard && standardFood(current, standard)) stack.set(DataComponents.FOOD, standard);
    }

    private static boolean standardFood(FoodProperties current, FoodProperties standard) {
        if (current == null || current.nutrition() != standard.nutrition() || current.saturation() != standard.saturation()
                || current.canAlwaysEat() != standard.canAlwaysEat() || current.eatSeconds() != standard.eatSeconds()
                || current.usingConvertsTo().isPresent() || current.effects().size() != standard.effects().size()) return false;
        for (int i = 0; i < current.effects().size(); i++) {
            var effect = current.effects().get(i);
            var expected = standard.effects().get(i);
            if (effect.probability() != expected.probability() || !effect.effect().save().equals(expected.effect().save())) return false;
        }
        return true;
    }
    private static CustomData data(int quality, int version) {
        CompoundTag data = new CompoundTag();
        data.putInt("Quality", quality);
        data.putInt("EconomyVersion", version);
        return CustomData.of(data);
    }
    public static int economyVersion(ItemStack stack) {
        CompoundTag data = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        return data.getInt("EconomyVersion");
    }
    public static ItemStack stack(int grade) { return stack(grade, 2); }
    public static ItemStack stack(int grade, int version) {
        int quality = Math.clamp(grade, 1, TreeBalance.MAX_FRUIT_GRADE);
        ItemStack stack = new ItemStack(TemporalContent.FRUIT_ITEM.get());
        stack.set(DataComponents.CUSTOM_DATA, data(quality, version));
        stack.set(DataComponents.FOOD, food(quality));
        return stack;
    }
    public static int grade(ItemStack stack) {
        return Math.max(1,Math.min(TreeBalance.MAX_FRUIT_GRADE,stack.getOrDefault(DataComponents.CUSTOM_DATA,CustomData.EMPTY).copyTag().getInt("Quality")));
    }

    public static Component gradeName(int grade) {
        if (grade < 1 || grade > TreeBalance.MAX_FRUIT_GRADE)
            return Component.translatable("block.kaleidoscope_witchcraft.years_fruit");
        int color = switch (grade) {case 1 -> 0xB8D69A; case 2 -> 0xE4BB62; case 3 -> 0xA9DEF0; case 4 -> 0xD49370; default -> 0xFFD36C;};
        return Component.translatable("item.kaleidoscope_witchcraft.years_fruit.grade_" + grade)
                .withStyle(style -> style.withColor(color));
    }
    @Override public Component getName(ItemStack stack) { return gradeName(grade(stack)); }

    @Override public InteractionResultHolder<ItemStack> use(Level level,Player player,InteractionHand hand) {
        ItemStack stack=player.getItemInHand(hand);
        if (economyVersion(stack) != 2) return InteractionResultHolder.fail(stack);
        if(!player.isSpectator() && stack.getFoodProperties(player)!=null && canRefill(player)) {
            player.startUsingItem(hand);
            return InteractionResultHolder.consume(stack);
        }
        return super.use(level,player,hand);
    }

    private static boolean canRefill(Player player) {
        if(player.level().isClientSide) {
            // The display effect predicts the animation only; the server checks the actual ledger and capacity.
            MobEffectInstance display=player.getEffect(BrewContent.YEARS_ABUNDANCE);
            return display!=null && display.getAmplifier()<12;
        }
        BodyTime time=ExtraTime.existing(player);
        return time!=null && time.acceptsWine() && time.active() && !time.free() && time.baseTicks()<time.capacity();
    }

    @Override public InteractionResult useOn(UseOnContext context) {
        var level=context.getLevel();
        var player=context.getPlayer();
        var pos=context.getClickedPos();
        if (!(level.getBlockEntity(pos) instanceof LivingEntity target) || target.amber()) return InteractionResult.PASS;
        if (player==null || player.isSpectator()) return InteractionResult.FAIL;
        if (level.isClientSide) return InteractionResult.SUCCESS;
        if (!LivingBlock.canFeed(level,pos,player,target)) return InteractionResult.FAIL;
        long amount=FruitTime.plantAmount(grade(context.getItemInHand()), economyVersion(context.getItemInHand()));
        if (amount <= 0) return InteractionResult.FAIL;
        // Credit the actual trunk first. Existing colony equalization may redistribute it on a later native update.
        if (target.tissue.coreCapacity()-target.tissue.coreQuantity()<amount) {
            LivingBlock.tell(player,"fruit_trunk_room");
            return InteractionResult.CONSUME;
        }
        target.tissue.setCoreQuantity(target.tissue.coreQuantity() + amount);
        target.received(amount,false);
        target.changed();
        context.getItemInHand().consume(1,player);
        level.levelEvent(1505,pos,0);
        player.displayClientMessage(LivingBlock.stockMessage(target),true);
        return InteractionResult.CONSUME;
    }

    @Override public ItemStack finishUsingItem(ItemStack stack,Level level,net.minecraft.world.entity.LivingEntity entity) {
        int quality=grade(stack), version=economyVersion(stack);
        if (version != 2) return stack;
        boolean edible=!stack.isEmpty() && stack.getFoodProperties(entity)!=null;
        ItemStack remaining=super.finishUsingItem(stack,level,entity);
        if(edible && entity instanceof ServerPlayer player) {
            VintageEffects.eatFruit(player, quality);
            BodyTime time=ExtraTime.existing(player);
            if(time!=null && time.replenish(FruitTime.personalTicks(quality, version, time.grade()))>0) ExtraTime.sync(player,time);
        }
        return remaining;
    }
    @Override public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> lines, TooltipFlag flag) {
        FoodProperties properties = stack.get(DataComponents.FOOD);
        if (properties != null && !properties.effects().isEmpty())
            PotionContents.addPotionTooltip(properties.effects().stream().map(FoodProperties.PossibleEffect::effect).toList(),
                    lines::add,1.0F,context.tickRate());
        lines.add(VintageEffects.fruitVintage(grade(stack)).lore());
    }
}
