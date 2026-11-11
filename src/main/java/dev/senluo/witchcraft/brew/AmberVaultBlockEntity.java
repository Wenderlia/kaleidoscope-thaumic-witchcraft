package dev.senluo.witchcraft.brew;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

public final class AmberVaultBlockEntity extends BlockEntity {
    AmberVaultContents contents=create(18);
    private long nextHum;
    public AmberVaultBlockEntity(BlockPos pos,BlockState state){super(AmberVaults.BLOCK_ENTITY.get(),pos,state);}
    AmberVaultContents create(int size){return new AmberVaultContents(size,this::changed,
            p->!isRemoved() && level!=null && p.level()==level && level.hasChunkAt(worldPosition)
                    && level.getBlockEntity(worldPosition)==this && p.distanceToSqr(worldPosition.getCenter())<=64);}
    private void changed(){setChanged();wake();}
    private void wake(){if(level!=null && !level.isClientSide && contents!=null && (contents.hasOverflow() || contents.quality>=6))
        level.scheduleTick(worldPosition,AmberVaults.BLOCK.get(),contents.hasOverflow()?20:100);}
    @Override public void onLoad(){super.onLoad();wake();}
    @Override protected void saveAdditional(CompoundTag tag,HolderLookup.Provider provider){super.saveAdditional(tag,provider);tag.put("Vault",contents.save(provider));}
    @Override protected void loadAdditional(CompoundTag tag,HolderLookup.Provider provider){super.loadAdditional(tag,provider);var data=tag.getCompound("Vault");contents=create(AmberVaultContents.size(data));contents.load(data,provider);}
    static void tick(Level level,BlockPos pos,BlockState state,AmberVaultBlockEntity vault){
        vault.contents.refill();
        if(vault.contents.quality>=6){
            if(level.getGameTime()>=vault.nextHum){AmberVaults.hum(level,pos.getCenter(),vault.contents.owner);vault.nextHum=level.getGameTime()+100;}
            level.scheduleTick(pos,AmberVaults.BLOCK.get(),100);
        }
    }
}
