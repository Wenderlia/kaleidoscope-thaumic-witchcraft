package dev.senluo.witchcraft.brew;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.food.FoodData;
import net.neoforged.neoforge.common.util.INBTSerializable;

/** Fractional food debt persists independently of the drink's lifetime. One unit is 10^-6 nutrition. */
public final class NutritionLedger implements INBTSerializable<CompoundTag> {
    public static final long UNIT=1_000_000;
    private long foodDebt;
    private CompoundTag unsupported;
    public boolean supported() { return unsupported==null; }
    public double foodDebt() {return foodDebt/(double)UNIT;}
    public Quote quote(FoodData food,long cost) {
        float saturation=food.getSaturationLevel();
        if(!supported() || cost<0 || !Float.isFinite(saturation) || saturation<0 || food.getFoodLevel()<0) return null;
        long stored=Math.round((double)saturation*UNIT);
        long available=stored+(long)food.getFoodLevel()*UNIT-foodDebt;
        if(available<cost)return null;
        long fromSaturation=Math.min(stored,cost);
        long debt=foodDebt+cost-fromSaturation;
        return new Quote((float)((stored-fromSaturation)/(double)UNIT),
                food.getFoodLevel()-(int)(debt/UNIT),debt%UNIT);
    }
    public void apply(FoodData food,Quote quote) {
        food.setSaturation(quote.saturation());food.setFoodLevel(quote.food());foodDebt=quote.debt();
    }
    /** Refund only escrowed nutrients, preserving intervening food use by damage callbacks. */
    public void refund(FoodData food,long saturationUnits,long hungerUnits) {
        if(!supported())return;
        long balance=(long)food.getFoodLevel()*UNIT-foodDebt+hungerUnits;
        balance=Math.max(0,Math.min(20*UNIT,balance));
        int level=(int)((balance+UNIT-1)/UNIT);
        food.setFoodLevel(level);
        foodDebt=(long)level*UNIT-balance;
        food.setSaturation(Math.min(level,food.getSaturationLevel()+saturationUnits/(float)UNIT));
    }
    public record Quote(float saturation,int food,long debt) {}
    @Override public CompoundTag serializeNBT(HolderLookup.Provider provider) {
        if(unsupported!=null)return unsupported.copy();
        CompoundTag tag=new CompoundTag();tag.putInt("Version",1);tag.putLong("FoodDebt",foodDebt);return tag;
    }
    @Override public void deserializeNBT(HolderLookup.Provider provider,CompoundTag tag) {
        foodDebt=0;unsupported=null;
        if(tag.getInt("Version")!=1){unsupported=tag.copy();return;}
        long debt=tag.getLong("FoodDebt");
        if(debt<0 || debt>=UNIT){unsupported=tag.copy();return;}
        foodDebt=debt;
    }
}
