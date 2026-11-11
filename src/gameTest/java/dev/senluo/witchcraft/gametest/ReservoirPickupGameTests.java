package dev.senluo.witchcraft.gametest;

import com.mojang.authlib.GameProfile;
import dev.senluo.witchcraft.reservoir.*;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@PrefixGameTestTemplate(false)
public final class ReservoirPickupGameTests {
    public static final class Listener {
        private final Consumer<BlockEvent.BreakEvent> callback;
        Listener(Consumer<BlockEvent.BreakEvent> callback){this.callback=callback;}
        @SubscribeEvent public void onBreak(BlockEvent.BreakEvent event){callback.accept(event);}
    }
    private record Setup(BlockPos pos, ReservoirBlockEntity block, FakePlayer player, ItemStack bag) {}
    private static Setup setup(GameTestHelper h){
        var level=h.getLevel();
        var pos=h.absolutePos(new BlockPos(2,2,2));
        level.setBlockAndUpdate(pos,ReservoirContent.BLOCK.get().defaultBlockState());
        var block=(ReservoirBlockEntity)level.getBlockEntity(pos);
        var bag=new ItemStack(ReservoirContent.BACKPACK.get());
        bag.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME,net.minecraft.network.chat.Component.literal("retained-data"));
        ReservoirData.deposit(bag,"aer",17);
        var converter=new ItemStack(ReservoirContent.CONVERTER.get());
        ReservoirData.credit(converter,new long[]{150,0,0,0,0,75});
        ReservoirData.slot(bag,ReservoirData.FIRST_PLUGIN_SLOT,converter,level.registryAccess());
        block.setBag(bag);
        var player=new FakePlayer(level,new GameProfile(UUID.randomUUID(),"pickup-test"));
        player.setGameMode(GameType.SURVIVAL);
        player.moveTo(Vec3.atCenterOf(pos).add(0,0,1));
        player.setShiftKeyDown(true);
        return new Setup(pos,block,player,bag);
    }
    private static int bags(Setup s){return s.player.getInventory().countItem(ReservoirContent.BACKPACK.get());}
    @GameTest(templateNamespace="kaleidoscope_witchcraft",template="empty")
    public static void placedBackpackProfileExistsBeforeContentsArrive(GameTestHelper h){
        var registries=h.getLevel().registryAccess();
        for(var item:java.util.List.of(ReservoirContent.BACKPACK.get(),ReservoirContent.SILVER_BACKPACK.get())){
            for(var facing:net.minecraft.core.Direction.Plane.HORIZONTAL){
                var state=item.getBlock().defaultBlockState().setValue(ReservoirBlock.FACING,facing);
                var receiving=new ReservoirBlockEntity(BlockPos.ZERO,state);
                h.assertTrue(receiving.bag().isEmpty() && ReservoirProfile.of(receiving.getBlockState())==item.profile(),
                        "The first placed frame uses the block host even before the inventory packet");
                var authoritative=new ReservoirBlockEntity(BlockPos.ZERO,state);
                var bag=new ItemStack(item);
                ReservoirData.cell(bag,item.profile().cells-1,"thaumcraft:aer",17);
                authoritative.setBag(bag);
                receiving.loadWithComponents(authoritative.getUpdateTag(registries),registries);
                h.assertTrue(ReservoirProfile.of(receiving.getBlockState())==ReservoirProfile.of(receiving.bag())
                                && ReservoirData.amount(receiving.bag(),item.profile().cells-1)==17,
                        "Receiving contents fills the correct last chamber without changing the placed model family");
            }
        }
        h.succeed();
    }
    @GameTest(templateNamespace="kaleidoscope_witchcraft",template="empty")
    public static void reservoirCurrentLedgerRoundTripsAndRejectsOldFormats(GameTestHelper h){
        var s=setup(h);
        var registries=h.getLevel().registryAccess();
        var decoded=ItemStack.parseOptional(registries,(net.minecraft.nbt.CompoundTag)s.bag.save(registries));
        h.assertTrue(ReservoirData.supported(decoded) && ReservoirData.amount(decoded,0)==17
                && ReservoirData.aspect(decoded,0).equals("aer"),"Current chamber survives item serialization");
        var converter=ReservoirData.slot(decoded,ReservoirData.FIRST_PLUGIN_SLOT,registries);
        h.assertTrue(converter.is(ReservoirContent.CONVERTER.get()) && ReservoirData.balance(converter,0)==150
                && ReservoirData.balance(converter,5)==75,"Removable plugin keeps its independent paid balances");
        ReservoirData.debit(converter,0,50);
        ReservoirData.slot(decoded,ReservoirData.FIRST_PLUGIN_SLOT,converter,registries);
        h.assertTrue(ReservoirData.balance(ReservoirData.slot(decoded,ReservoirData.FIRST_PLUGIN_SLOT,registries),0)==100
                && ReservoirData.amount(decoded,0)==17 && decoded.getHoverName().getString().equals("retained-data"),
                "Editing the decoded plugin retains chamber stock and unrelated components");
        for(int version:new int[]{0,1,3,99}){
            var unsupported=decoded.copy();
            var root=unsupported.getOrDefault(net.minecraft.core.component.DataComponents.CUSTOM_DATA,
                    net.minecraft.world.item.component.CustomData.EMPTY).copyTag();
            root.getCompound("kw_reservoir").putInt("version",version);
            unsupported.set(net.minecraft.core.component.DataComponents.CUSTOM_DATA,net.minecraft.world.item.component.CustomData.of(root));
            var before=unsupported.copy();
            h.assertTrue(!ReservoirData.supported(unsupported),"Non-current ledger is not editable");
            ReservoirData.cell(unsupported,0,"ignis",1);
            ReservoirData.slot(unsupported,ReservoirData.FIRST_PLUGIN_SLOT,ItemStack.EMPTY,registries);
            h.assertTrue(ItemStack.isSameItemSameComponents(before,unsupported),"Refused edits preserve opaque inventory and stock");
        }
        h.succeed();
    }
    @GameTest(templateNamespace="kaleidoscope_witchcraft",template="empty")
    public static void reservoirFrostGrantTracksCurrentFuelAndInstalledItem(GameTestHelper h){
        var s=setup(h);
        var registries=h.getLevel().registryAccess();
        var bag=new ItemStack(ReservoirContent.BACKPACK.get());
        var module=new ItemStack(ReservoirContent.FROST_MODULE.get());
        ReservoirData.cell(bag,0,"thaumcraft:gelum",2);
        ReservoirData.slot(bag,ReservoirData.FIRST_PLUGIN_SLOT,module,registries);
        s.player.setItemSlot(net.minecraft.world.entity.EquipmentSlot.CHEST,bag);
        dev.senluo.witchcraft.brew.FrostAbilities.beginFrame(s.player);
        ReservoirPlugins.tick(s.player,bag,ReservoirPluginItem.Phase.BEFORE_PLAYER);
        h.assertTrue(dev.senluo.witchcraft.brew.FrostAbilities.moduleActive(s.player)
                && ReservoirData.amount(bag,0)==1,"One installed grant pays one real source unit");
        var paid=ReservoirData.slot(bag,ReservoirData.FIRST_PLUGIN_SLOT,registries);
        h.assertTrue(FrostModuleItem.remaining(paid)==FrostModuleItem.TICKS_PER_ESSENTIA-1,"Unused prepaid time stays on the module");
        ReservoirPlugins.tick(s.player,bag,ReservoirPluginItem.Phase.BEFORE_PLAYER);
        h.assertTrue(ItemStack.matches(paid,ReservoirData.slot(bag,ReservoirData.FIRST_PLUGIN_SLOT,registries)),
                "Repeated dispatch cannot charge a second grant in the same frame");
        ReservoirData.slot(bag,ReservoirData.FIRST_PLUGIN_SLOT,ItemStack.EMPTY,registries);
        h.assertTrue(!dev.senluo.witchcraft.brew.FrostAbilities.moduleActive(s.player),"Removal revokes the grant immediately");
        ReservoirData.slot(bag,ReservoirData.FIRST_PLUGIN_SLOT,paid,registries);
        dev.senluo.witchcraft.brew.FrostAbilities.beginFrame(s.player);
        ReservoirPlugins.tick(s.player,bag,ReservoirPluginItem.Phase.BEFORE_PLAYER);
        h.assertTrue(ReservoirData.amount(bag,0)==1 && FrostModuleItem.remaining(
                ReservoirData.slot(bag,ReservoirData.FIRST_PLUGIN_SLOT,registries))==FrostModuleItem.TICKS_PER_ESSENTIA-2,
                "Reinstalled module spends its paid remainder before consuming another source unit");
        var old=new ItemStack(ReservoirContent.BACKPACK.get());
        ReservoirData.cell(old,0,"gelum",2);
        ReservoirData.slot(old,ReservoirData.FIRST_PLUGIN_SLOT,new ItemStack(ReservoirContent.FROST_MODULE.get()),registries);
        s.player.setItemSlot(net.minecraft.world.entity.EquipmentSlot.CHEST,old);
        dev.senluo.witchcraft.brew.FrostAbilities.beginFrame(s.player);
        ReservoirPlugins.tick(s.player,old,ReservoirPluginItem.Phase.BEFORE_PLAYER);
        h.assertTrue(!dev.senluo.witchcraft.brew.FrostAbilities.moduleActive(s.player) && ReservoirData.amount(old,0)==2,
                "Unnamespaced historical fuel is not silently reinterpreted");
        h.succeed();
    }
    @GameTest(templateNamespace="kaleidoscope_witchcraft",template="empty")
    public static void reservoirPickupCancellationRetainsStock(GameTestHelper h){
        var s=setup(h);
        var listener=new Listener(e->{if(e.getPlayer()==s.player)e.setCanceled(true);});
        NeoForge.EVENT_BUS.register(listener);
        try{
            h.assertTrue(!s.block.pickup(s.player),"Canceled removal is refused");
            h.assertTrue(h.getLevel().getBlockEntity(s.pos)==s.block && s.block.bag()==s.bag && bags(s)==0,"Cancellation retains actual stock and original block");
        }finally{NeoForge.EVENT_BUS.unregister(listener);}
        s.player.setGameMode(GameType.ADVENTURE);
        h.assertTrue(!s.block.pickup(s.player) && s.block.bag()==s.bag,"Adventure mode cannot take the placed container");
        h.succeed();
    }
    @GameTest(templateNamespace="kaleidoscope_witchcraft",template="empty")
    public static void reservoirPickupReentryTransfersOnce(GameTestHelper h){
        var s=setup(h);
        int[] calls={0};
        var listener=new Listener(e->{if(e.getPlayer()==s.player){calls[0]++;h.assertTrue(!s.block.pickup(s.player),"Nested pickup is refused");}});
        NeoForge.EVENT_BUS.register(listener);
        try{
            h.assertTrue(s.block.pickup(s.player),"Actual removal transfers the bag");
            h.assertTrue(calls[0]==1 && bags(s)==1 && s.block.bag().isEmpty(),"Exactly one event and one transfer");
            h.assertTrue(s.player.getInventory().items.stream().anyMatch(i->ItemStack.isSameItemSameComponents(i,s.bag)),"Bag data survives transfer");
            h.assertTrue(!s.block.pickup(s.player),"Removed block cannot pay twice");
        }finally{NeoForge.EVENT_BUS.unregister(listener);}
        h.succeed();
    }
    @GameTest(templateNamespace="kaleidoscope_witchcraft",template="empty")
    public static void reservoirPickupEventReplacementPreserved(GameTestHelper h){
        var s=setup(h);
        var listener=new Listener(e->{if(e.getPlayer()==s.player)h.getLevel().setBlockAndUpdate(s.pos,Blocks.STONE.defaultBlockState());});
        NeoForge.EVENT_BUS.register(listener);
        try{
            h.assertTrue(!s.block.pickup(s.player),"Replaced original cannot be picked up");
            h.assertTrue(h.getLevel().getBlockState(s.pos).is(Blocks.STONE) && bags(s)==0,"Replacement survives; no stale stock granted");
            var dropped=h.getLevel().getEntitiesOfClass(ItemEntity.class,new AABB(s.pos).inflate(1));
            h.assertTrue(dropped.stream().filter(i->ItemStack.isSameItemSameComponents(i.getItem(),s.bag)).mapToInt(i->i.getItem().getCount()).sum()==1,"Listener removal follows normal drop path exactly once");
        }finally{NeoForge.EVENT_BUS.unregister(listener);}
        h.succeed();
    }
}
