package dev.senluo.witchcraft.brew;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import dev.senluo.witchcraft.time.FruitItem;
import dev.senluo.witchcraft.time.TemporalContent;
import java.util.stream.Stream;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.common.crafting.ICustomIngredient;
import net.neoforged.neoforge.common.crafting.IngredientType;

/** Share the fruit's version/grade interpretation, without testing unrelated food or lore components. */
public record FruitGradeIngredient(int grade) implements ICustomIngredient {
    public static final MapCodec<FruitGradeIngredient> CODEC=Codec.intRange(1,5).fieldOf("grade")
            .xmap(FruitGradeIngredient::new, FruitGradeIngredient::grade);
    @Override public boolean test(ItemStack stack) {
        if (!stack.is(TemporalContent.FRUIT_ITEM.get())) return false;
        int version=FruitItem.economyVersion(stack);
        return version==2 && FruitItem.grade(stack)==grade;
    }
    @Override public Stream<ItemStack> getItems() { return Stream.of(FruitItem.stack(grade)); }
    @Override public boolean isSimple() { return false; }
    @Override public IngredientType<?> getType() { return AmberFerments.FRUIT_GRADE.get(); }
}
