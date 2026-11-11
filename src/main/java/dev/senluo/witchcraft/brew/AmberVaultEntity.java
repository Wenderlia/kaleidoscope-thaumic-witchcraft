package dev.senluo.witchcraft.brew;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/** Non-colliding permanent fallback when no legal block anchor can be created. */
public final class AmberVaultEntity extends Entity {
    private static final net.minecraft.network.syncher.EntityDataAccessor<Boolean> BRIGHT=SynchedEntityData.defineId(AmberVaultEntity.class,net.minecraft.network.syncher.EntityDataSerializers.BOOLEAN);
    private boolean refillPending;
    AmberVaultContents contents=create(54);
    public AmberVaultEntity(EntityType<? extends AmberVaultEntity> type,Level level){super(type,level);setNoGravity(true);}
    AmberVaultContents create(int size){return new AmberVaultContents(size,()->refillPending=true,p->isAlive() && p.level()==level() && p.distanceToSqr(this)<=64);}
    @Override protected void defineSynchedData(SynchedEntityData.Builder builder){builder.define(BRIGHT,false);}
    public boolean bright(){return entityData.get(BRIGHT);}
    @Override protected void addAdditionalSaveData(CompoundTag tag){tag.put("Vault",contents.save(registryAccess()));}
    @Override protected void readAdditionalSaveData(CompoundTag tag){var data=tag.getCompound("Vault");contents=create(AmberVaultContents.size(data));contents.load(data,registryAccess());}
    @Override public boolean isPickable(){return true;}
    @Override public boolean isPushable(){return false;}
    @Override public boolean isPushedByFluid(){return false;}
    @Override public void move(MoverType type,Vec3 delta){}
    @Override public boolean canUsePortal(boolean passengers){return false;}
    @Override public boolean hurt(DamageSource source,float damage){
        if(!level().isClientSide && source.getEntity() instanceof Player player && player.isShiftKeyDown()
                && player.getMainHandItem().isEmpty() && contents.allowed(player) && contents.entirelyEmpty()){
            AmberVaults.dissolve(level(),blockPosition());discard();
        }
        return false;
    }
    @Override public InteractionResult interact(Player player,InteractionHand hand){
        if(!level().isClientSide)AmberVaults.open(player,contents);
        return InteractionResult.sidedSuccess(level().isClientSide);
    }
    @Override public void tick(){
        super.tick();setDeltaMovement(Vec3.ZERO);
        if(!level().isClientSide && refillPending){refillPending=false;contents.refill();entityData.set(BRIGHT,contents.quality>=6);}
    }
}
