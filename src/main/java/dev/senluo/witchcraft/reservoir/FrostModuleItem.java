package dev.senluo.witchcraft.reservoir;

import dev.senluo.witchcraft.brew.FrostAbilities;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import java.util.function.BooleanSupplier;

/** Owns only prepaid fuel and its installed grant; ability implementations remain in brew. */
public final class FrostModuleItem extends ReservoirPluginItem {
    public static final int TICKS_PER_ESSENTIA=600;
    private static final String BALANCE="frost_ticks",ICE="thaumcraft:gelum";
    public FrostModuleItem(Properties properties){super(properties,FrostModuleItem::supply);}
    @Override public Phase phase(){return Phase.BEFORE_PLAYER;}
    @Override public boolean canToggle(){return true;}
    public static int remaining(ItemStack module){return Math.clamp(ReservoirData.read(module).getInt(BALANCE),0,TICKS_PER_ESSENTIA);}
    private static void supply(ServerPlayer player,ItemStack bag,int slot,ItemStack module){
        if(!FrostAbilities.acceptsModule(player))return;
        int ticks=remaining(module),fuelCell=-1;
        if(ticks==0){
            var data=ReservoirData.read(bag);
            for(int cell=0;cell<ReservoirData.cells(bag);cell++){
                var value=data.getCompound("cell_"+cell);
                String aspect=value.getString("aspect");
                if(ICE.equals(aspect) && value.getInt("amount")>0){fuelCell=cell;break;}
            }
            if(fuelCell<0)return;
            ticks=TICKS_PER_ESSENTIA;
        }
        int left=ticks-1,paidCell=fuelCell;
        ReservoirData.edit(module,d->d.putInt(BALANCE,left));
        var encoded=module.save(player.registryAccess());
        // Native item/chamber state, one commit, no external callback between debit and grant.
        ReservoirData.edit(bag,d->{
            if(paidCell>=0){var value=d.getCompound("cell_"+paidCell);int amount=value.getInt("amount")-1;
                value.putInt("amount",amount);if(amount==0)value.putString("aspect","");d.put("cell_"+paidCell,value);}
            d.put("slot_"+slot,encoded);
        });
        player.getInventory().setChanged();
        FrostAbilities.grantModule(player,new Installed(player,bag,slot,module));
    }
    /** Recheck only when host component identity changes; no repeated NBT decode per collision. */
    private static final class Installed implements BooleanSupplier {
        final ServerPlayer player;final ItemStack bag,expected;final int slot;
        CustomData observed;boolean valid=true;
        Installed(ServerPlayer p,ItemStack bag,int slot,ItemStack module){
            this.player=p;this.bag=bag;this.slot=slot;expected=module.copy();observed=bag.get(DataComponents.CUSTOM_DATA);
        }
        public boolean getAsBoolean(){
            if(!valid || ReservoirEquipment.worn(player)!=bag)return false;
            CustomData current=bag.get(DataComponents.CUSTOM_DATA);
            if(current!=observed){
                observed=current;
                valid=ReservoirData.supported(bag) && ItemStack.matches(expected,ReservoirData.slot(bag,slot,player.registryAccess()));
            }
            return valid;
        }
    }
}
