package dev.senluo.witchcraft.gametest;

import dev.senluo.witchcraft.time.*;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@PrefixGameTestTemplate(false)
public final class FruitGradeGameTests {
    @GameTest(templateNamespace="kaleidoscope_witchcraft",template="empty",timeoutTicks=60)
    public static void fifthFruitRequiresGreatTreeCoreAndPaidDevelopment(GameTestHelper h) {
        LivingEntity core=TreeGameTests.plant(h,true);
        FruitEntity ordinary=TreeGameTests.nodeFruit(h,core,4);
        h.assertTrue(ordinary.limit()==4,"The ordinary node fixes quality IV without a fifth-quality source");
        core.tissue.giant=true;GiantRoots.restore(core.tissue);
        FruitEntity fruit=TreeGameTests.nodeFruit(h,core,5);
        h.assertTrue(fruit.limit()==5 && fruit.source.equals(net.minecraft.core.BlockPos.ZERO)
                && ordinary.targetGrade==4, "The great core creates its own V site instead of promoting an existing IV fruit");
        h.assertTrue(fruit.remainingFee()==62_000*Tissue.TU && fruit.remainingTicks()==24_000,
                "A new V fruit pays the cumulative five-quality cost and duration");
        var world=TemporalWorld.get(h.getLevel());var group=world.colony(core);
        group.chooseMode(1,h.getLevel().getGameTime(),java.util.UUID.randomUUID());
        long reserve=FruitFeeding.reserve(group);core.tissue.setCoreQuantity(reserve);
        fruit.invested=TreeBalance.nodeFruitFee(5)-1;
        fruit.ticks=TreeBalance.nodeFruitTicks(5)-1;
        var saved=fruit.saveWithoutMetadata(h.getLevel().registryAccess());
        fruit.loadWithComponents(saved,h.getLevel().registryAccess());
        h.assertTrue(fruit.remainingFee()==1 && fruit.remainingTicks()==1,
                "Reload preserves partial funding and work");
        fruit.advanceDevelopment(1);world.production.flush(java.util.List.of());
        h.assertTrue(fruit.grade==0 && !fruit.canGrow(), "Unpaid work cannot complete V or spend restoration reserves");
        core.tissue.setCoreQuantity(core.tissue.coreQuantity()+1);
        fruit.advanceDevelopment(1);world.production.flush(java.util.List.of());
        h.assertTrue(fruit.grade==5 && fruit.invested==0 && fruit.ticks==0
                && group.quantity()==reserve
                && h.getLevel().getBlockState(fruit.getBlockPos()).getValue(FruitBlock.GRADE)==5,
                "The last paid milli-TU completes V and updates the model");
        h.assertTrue(FruitItem.grade(FruitHarvest.product(fruit,0))==5,"Harvest retains completed fifth quality");
        h.succeed();
    }

    @GameTest(templateNamespace="kaleidoscope_witchcraft",template="empty",timeoutTicks=60)
    public static void fifthFruitFoodAndEffectsSurviveInventoryReload(GameTestHelper h) {
        var registries=h.getLevel().registryAccess();
        for(int grade=1;grade<=5;grade++) {
            ItemStack authored=FruitItem.stack(grade);
            ItemStack decoded=ItemStack.parse(registries,authored.save(registries)).orElseThrow();
            h.assertTrue(ItemStack.isSameItemSameComponents(authored,decoded),"Every current quality remains stackable after decoding");
            var ingredient=new dev.senluo.witchcraft.brew.FruitGradeIngredient(grade);
            h.assertTrue(ingredient.test(decoded) && !ingredient.test(FruitItem.stack(grade,1)),
                    "Fermentation consumes only supported current fruit of its requested quality");
        }
        ItemStack fresh=FruitItem.stack(5);
        ItemStack loaded=ItemStack.parse(registries,fresh.save(registries)).orElseThrow();
        h.assertTrue(FruitItem.grade(loaded)==5 && ItemStack.isSameItemSameComponents(fresh,loaded),
                "Standard V fruit remains stackable after its effect supplier is decoded");
        var player=DevelopmentGameTests.player(h,TreeGameTests.plant(h,true));
        player.getFoodData().setFoodLevel(0);player.getFoodData().setSaturation(0);
        loaded.finishUsingItem(h.getLevel(),player);
        MobEffectInstance effect=player.getEffect(MobEffects.SATURATION);
        h.assertTrue(player.getFoodData().getFoodLevel()==20 && player.getFoodData().getSaturationLevel()==20
                && effect!=null && effect.getAmplifier()==0 && effect.getDuration()==1_800,
                "One V fruit grants 20 hunger, 20 saturation and exactly 90 seconds of Saturation I");
        ItemStack custom=fresh.copy();
        custom.set(DataComponents.FOOD,new FoodProperties.Builder().nutrition(20).saturationModifier(.5F)
                .effect(()->new MobEffectInstance(MobEffects.SATURATION,600,1),1F).build());
        ItemStack customLoaded=ItemStack.parse(registries,custom.save(registries)).orElseThrow();
        MobEffectInstance preserved=customLoaded.get(DataComponents.FOOD).effects().getFirst().effect();
        h.assertTrue(preserved.getDuration()==600 && preserved.getAmplifier()==1,
                "Canonical stacking never overwrites an externally customized food effect");
        h.succeed();
    }
}
