package dev.senluo.witchcraft.brew;

import dev.senluo.witchcraft.KaleidoscopeWitchcraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.PushReaction;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.INBTSerializable;
import net.neoforged.neoforge.event.entity.living.LivingDropsEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.registries.*;
import java.util.*;
import java.util.function.Supplier;

/** Server-side ability policy. The vault owns persistent contents; packets only carry held input. */
public final class AmberVaults {
    private static final String MOD=KaleidoscopeWitchcraft.MOD_ID;
    private static final DeferredRegister.Blocks BLOCKS=DeferredRegister.createBlocks(MOD);
    private static final DeferredRegister<BlockEntityType<?>> BES=DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE,MOD);
    private static final DeferredRegister<EntityType<?>> ENTITIES=DeferredRegister.create(Registries.ENTITY_TYPE,MOD);
    private static final DeferredRegister<AttachmentType<?>> DATA=DeferredRegister.create(NeoForgeRegistries.ATTACHMENT_TYPES,MOD);
    public static final DeferredBlock<AmberVaultBlock> BLOCK=BLOCKS.register("amber_vault",()->new AmberVaultBlock(
            BlockBehaviour.Properties.of().strength(-1,3600000).noCollission().noOcclusion().noLootTable()
                    .pushReaction(PushReaction.BLOCK).sound(SoundType.AMETHYST).lightLevel(s->s.getValue(AmberVaultBlock.BRIGHT)?12:0)));
    public static final Supplier<BlockEntityType<AmberVaultBlockEntity>> BLOCK_ENTITY=BES.register("amber_vault",
            ()->BlockEntityType.Builder.of(AmberVaultBlockEntity::new,BLOCK.get()).build(null));
    public static final Supplier<EntityType<AmberVaultEntity>> ENTITY=ENTITIES.register("amber_vault",
            ()->EntityType.Builder.<AmberVaultEntity>of(AmberVaultEntity::new,MobCategory.MISC).sized(.75F,.85F)
                    .clientTrackingRange(8).updateInterval(20).fireImmune().build(MOD+":amber_vault"));
    private static final Supplier<AttachmentType<State>> STATE=DATA.register("amber_seal",()->AttachmentType.serializable(State::new).build());
    private static final Supplier<AttachmentType<Boolean>> READY=DATA.register("amber_seal_ready",
            ()->AttachmentType.builder(()->false).sync(ByteBufCodecs.BOOL).build());
    private AmberVaults(){}
    public static void register(IEventBus bus){
        BLOCKS.register(bus);BES.register(bus);ENTITIES.register(bus);DATA.register(bus);
        bus.addListener(AmberVaults::packets);
        NeoForge.EVENT_BUS.addListener(AmberVaults::tick);
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST,AmberVaults::drops);
        NeoForge.EVENT_BUS.addListener(AmberVaults::breakBlock);
        NeoForge.EVENT_BUS.addListener(AmberVaults::leftClick);
    }
    public static boolean ready(Player player){
        return player!=null && player.hasData(READY.get()) && player.getData(READY.get());
    }
    private static void ready(ServerPlayer player,boolean ready){
        if(ready(player)!=ready)player.setData(READY.get(),ready);
    }
    public static void drink(ServerPlayer player,int quality){
        if(quality<2 || quality>6)return;
        State s=player.getData(STATE.get());if(s.unsupported!=null)return;
        s.quality=Math.max(s.quality,quality);
        s.remaining=VintageEffects.align(player,AmberVintage.HONEYED,Math.max(s.remaining,State.duration(quality)));
        s.charges=Math.max(s.charges,quality==6?2:1);ready(player,true);
    }
    public record Held(boolean down) implements CustomPacketPayload {
        public static final Type<Held> TYPE=new Type<>(ResourceLocation.fromNamespaceAndPath(MOD,"amber_seal_input"));
        public static final StreamCodec<RegistryFriendlyByteBuf,Held> CODEC=StreamCodec.of((b,p)->b.writeBoolean(p.down),b->new Held(b.readBoolean()));
        public Type<Held> type(){return TYPE;}
    }
    private static void packets(RegisterPayloadHandlersEvent event){
        event.registrar(KaleidoscopeWitchcraft.NETWORK_VERSION).playToServer(Held.TYPE,Held.CODEC,(packet,context)->context.enqueueWork(()->{
            if(!(context.player() instanceof ServerPlayer player) || !player.hasData(STATE.get()))return;
            State s=player.getData(STATE.get());long now=player.serverLevel().getGameTime();
            if(!packet.down){s.target=null;s.started=-1;s.finished=false;return;}
            if(s.lastInput!=Long.MIN_VALUE && now>=s.lastInput && now-s.lastInput<5)return;
            s.lastInput=now;
            if(!eligible(player,s) || s.finished)return;
            if(s.target==null){s.target=target(player);s.started=now;}
        }));
    }
    private static boolean eligible(ServerPlayer player,State state){return state.remaining>0 && state.charges>0 && !state.committing && state.unsupported==null
            && player.isAlive() && !player.isSpectator() && player.mayBuild() && player.isShiftKeyDown()
            && player.getMainHandItem().isEmpty() && player.getOffhandItem().isEmpty()
            && player.containerMenu==player.inventoryMenu && !dev.senluo.witchcraft.ritual.OwnershipRitual.frozen(player);}
    private record Target(BlockPos pos,boolean gather,net.minecraft.resources.ResourceKey<Level> dimension){}
    private static Target target(ServerPlayer player){
        Vec3 from=player.getEyePosition(),to=from.add(player.getLookAngle().scale(player.blockInteractionRange()));
        var hit=player.serverLevel().clip(new ClipContext(from,to,ClipContext.Block.OUTLINE,ClipContext.Fluid.ANY,player));
        double distance=hit.getType()==HitResult.Type.MISS?from.distanceToSqr(to):from.distanceToSqr(hit.getLocation());
        ItemEntity nearest=null;
        for(ItemEntity item:player.serverLevel().getEntitiesOfClass(ItemEntity.class,new AABB(from,to).inflate(.25),
                item->item.isAlive() && item.getOwner()==player)) {
            var clipped=item.getBoundingBox().inflate(.15).clip(from,to);
            if(clipped.isPresent() && from.distanceToSqr(clipped.get())<distance){nearest=item;distance=from.distanceToSqr(clipped.get());}
        }
        if(nearest!=null)return new Target(nearest.blockPosition(),true,player.level().dimension());
        if(hit.getType()!=HitResult.Type.BLOCK || player.serverLevel().getBlockState(hit.getBlockPos()).is(BLOCK.get()))return null;
        return new Target(hit.getBlockPos().relative(hit.getDirection()),false,player.level().dimension());
    }
    private static void tick(PlayerTickEvent.Post event){
        if(!(event.getEntity() instanceof ServerPlayer player) || !player.hasData(STATE.get()))return;
        State s=player.getData(STATE.get());if(s.unsupported!=null)return;
        if(!player.isAlive() || s.remaining<=0){ready(player,false);player.removeData(STATE.get());return;}
        if(dev.senluo.witchcraft.ritual.OwnershipRitual.frozen(player)){s.target=null;s.started=-1;return;}
        s.remaining--;ready(player,s.charges>0 && s.remaining>0);
        if(s.target==null)return;
        long now=player.serverLevel().getGameTime();
        if(!eligible(player,s) || now-s.lastInput>15 || !s.target.dimension.equals(player.level().dimension())){s.target=null;s.started=-1;return;}
        // The held input is sampled every ten ticks. Revalidate aim at five-tick
        // intervals and at commit, rather than ray-casting/scanning every tick.
        if((now-s.started)%5==0 || now-s.started>=30)
            if(!s.target.equals(target(player))){s.target=null;s.started=-1;return;}
        if(now-s.started<30){
            if((now-s.started)%5==0)AmberVaultEffects.forming(player.serverLevel(),s.target.pos.getCenter(),(now-s.started)/30F);
            return;
        }
        Target target=s.target;s.target=null;s.finished=true;
        Placement placement=commitPlacement(player,s,target.pos,false);
        if(placement==null)return;
        AmberVaultContents contents=placement.contents;
        ready(player,s.charges>0);
        if(target.gather) for(ItemEntity item:player.serverLevel().getEntitiesOfClass(ItemEntity.class,new AABB(target.pos).inflate(3),
                item->item.isAlive() && item.getOwner()==player && item.distanceToSqr(target.pos.getCenter())<=9)) {
            ItemStack remainder=contents.addItem(item.getItem().copy());
            if(remainder.isEmpty())item.discard();else item.setItem(remainder);
        }
        open(player,contents);AmberVaultEffects.sealed(player.serverLevel(),placement.center);
    }
    private static int size(int quality){return quality>=6?54:quality>=4?27:18;}
    /** Reserve one opportunity across world callbacks; failed placement refunds only that reservation. */
    private record Placement(AmberVaultContents contents,Vec3 center){}
    private static Placement commitPlacement(ServerPlayer player,State state,BlockPos pos,boolean death){
        if(state.committing || state.charges<=0)return null;
        state.committing=true;state.charges--;boolean committed=false;
        try{
            Placement placement=place(player,pos,state.quality,death);
            committed=placement!=null;return placement;
        }finally{
            if(!committed)state.charges++;
            state.committing=false;
        }
    }
    private static void initialize(AmberVaultContents contents,ServerPlayer player,int quality,boolean death){
        contents.owner=player.getUUID();contents.quality=quality;contents.takeOnly=death && quality<4;contents.setChanged();
    }
    private static Placement place(ServerPlayer player,BlockPos requested,int quality,boolean death){
        ServerLevel level=player.serverLevel();
        if(!level.hasChunkAt(requested) || !level.getWorldBorder().isWithinBounds(requested))return null;
        BlockPos pos=requested.atY(Math.clamp(requested.getY(),level.getMinBuildHeight()+1,level.getMaxBuildHeight()-1));
        while(pos.getY()<level.getMaxBuildHeight()-1 && !level.getBlockState(pos).isAir())pos=pos.above();
        if(!player.mayBuild() || !level.mayInteract(player,pos) || level.captureBlockSnapshots || level.restoringBlockSnapshots)return null;
        var before=level.getBlockState(pos);
        if(before.isAir()){
            var state=BLOCK.get().defaultBlockState().setValue(AmberVaultBlock.BRIGHT,quality>=6);
            if(dev.senluo.witchcraft.world.BlockPlacement.commit(player,pos,before,state) && level.getBlockEntity(pos) instanceof AmberVaultBlockEntity vault){
                vault.contents=vault.create(size(quality));initialize(vault.contents,player,quality,death);
                return level.getBlockEntity(pos)==vault && !vault.isRemoved()?new Placement(vault.contents,pos.getCenter()):null;
            }
            return null; // A rejected placement must not fall through to an entity at the same site.
        }
        AmberVaultEntity fallback=ENTITY.get().create(level);if(fallback==null)return null;
        fallback.setPos(pos.getX()+.5,pos.getY(),pos.getZ()+.5);
        fallback.contents=fallback.create(size(quality));initialize(fallback.contents,player,quality,death);
        return level.addFreshEntity(fallback) && !fallback.isRemoved()?new Placement(fallback.contents,pos.getCenter()):null;
    }
    private static void drops(LivingDropsEvent event){
        if(!(event.getEntity() instanceof ServerPlayer player) || !player.hasData(STATE.get()) || event.getDrops().isEmpty())return;
        State state=player.getData(STATE.get());
        if(state.unsupported!=null || state.remaining<=0 || state.charges<=0)return;
        List<ItemEntity> drops=event.getDrops().stream().filter(item->!item.getItem().isEmpty()).toList();
        if(drops.isEmpty())return;
        var level=player.serverLevel();BlockPos anchor=player.blockPosition();
        Placement placement=commitPlacement(player,state,anchor,true);
        if(placement==null)return;
        placement.contents.receiveDeath(drops.stream().map(ItemEntity::getItem).toList());
        event.getDrops().removeAll(drops);ready(player,false);AmberVaultEffects.sealed(level,placement.center);
    }
    public static void open(Player player,AmberVaultContents contents){
        if(!(player instanceof ServerPlayer server))return;
        if(!contents.stillValid(player)){
            player.level().playSound(null,player.blockPosition(),SoundEvents.AMETHYST_BLOCK_HIT,SoundSource.BLOCKS,.4F,.85F);return;
        }
        contents.refill();var opened=server.openMenu(new SimpleMenuProvider((id,inventory,p)->new AmberVaultMenu(id,inventory,contents),
                Component.translatable("block.kaleidoscope_witchcraft.amber_vault")));
        if(opened.isPresent())server.playNotifySound(SoundEvents.AMETHYST_BLOCK_CHIME,SoundSource.BLOCKS,.25F,1.3F);
    }
    private static void breakBlock(BlockEvent.BreakEvent event){
        if(!event.getState().is(BLOCK.get()))return;
        event.setCanceled(true);
    }
    private static void leftClick(net.neoforged.neoforge.event.entity.player.PlayerInteractEvent.LeftClickBlock event){
        var player=event.getEntity();
        if(event.getLevel().getBlockEntity(event.getPos()) instanceof AmberVaultBlockEntity vault){
            event.setCanceled(true);
            if(!event.getLevel().isClientSide && player.isShiftKeyDown() && player.getMainHandItem().isEmpty()
                    && vault.contents.allowed(player) && vault.contents.entirelyEmpty()
                    && event.getLevel().removeBlock(event.getPos(),false))dissolve(event.getLevel(),event.getPos());
        }
    }
    static void hum(Level level,Vec3 pos,UUID owner){
        if(owner==null || !(level instanceof ServerLevel server))return;
        var player=server.getPlayerByUUID(owner);
        if(player!=null && player.distanceToSqr(pos)<=16)player.playNotifySound(SoundEvents.AMETHYST_BLOCK_CHIME,SoundSource.BLOCKS,.08F,.85F);
    }
    static void dissolve(Level level,BlockPos pos){
        level.playSound(null,pos,SoundEvents.AMETHYST_BLOCK_BREAK,SoundSource.BLOCKS,.5F,1.15F);
        if(level instanceof ServerLevel server)AmberVaultEffects.dissolved(server,pos.getCenter());
    }
    static final class State implements INBTSerializable<CompoundTag>{
        int quality,remaining,charges;long lastInput=Long.MIN_VALUE,started=-1;Target target;boolean finished,committing;CompoundTag unsupported;
        static int duration(int quality){return VintageEffects.duration(AmberVintage.HONEYED,quality);}
        public CompoundTag serializeNBT(HolderLookup.Provider provider){
            if(unsupported!=null)return unsupported.copy();
            CompoundTag tag=new CompoundTag();tag.putInt("Version",1);tag.putInt("Quality",quality);tag.putInt("Remaining",remaining);tag.putInt("Charges",charges);return tag;
        }
        public void deserializeNBT(HolderLookup.Provider provider,CompoundTag tag){
            quality=remaining=charges=0;lastInput=Long.MIN_VALUE;started=-1;target=null;finished=committing=false;unsupported=null;
            if(tag.getInt("Version")!=1){unsupported=tag.copy();return;}
            quality=Math.clamp(tag.getInt("Quality"),0,6);remaining=Math.max(tag.getInt("Remaining"),0);charges=Math.clamp(tag.getInt("Charges"),0,2);
        }
    }
}
