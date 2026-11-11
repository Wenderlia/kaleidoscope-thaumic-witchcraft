package dev.senluo.witchcraft.gametest;

import com.github.ysbbbbbb.kaleidoscopetavern.blockentity.brew.BarrelBlockEntity;
import com.github.ysbbbbbb.kaleidoscopetavern.init.ModBlocks;
import com.github.ysbbbbbb.kaleidoscopetavern.init.ModItems;
import dev.senluo.witchcraft.brew.*;
import dev.senluo.witchcraft.compat.tavern.*;
import dev.senluo.witchcraft.time.*;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.*;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction;

final class WineBarrelCases {
    private static BarrelBlockEntity barrel(GameTestHelper h) {
        BlockPos pos=h.absolutePos(new BlockPos(4,2,4));
        h.getLevel().setBlock(pos,ModBlocks.BARREL.get().defaultBlockState(),3);
        return (BarrelBlockEntity)h.getLevel().getBlockEntity(pos);
    }
    private static Tissue root() {
        Tissue t=new Tissue();t.rootStage=1;t.setCoreQuantity(4800*Tissue.TU);
        Tissue child=new Tissue();child.setCoreQuantity(1200*Tissue.TU);t.sides.add(Tissue.Side.graft(child));return t;
    }
    static void lifecycle(GameTestHelper h) {
        BarrelBlockEntity b=barrel(h);Player p=h.makeMockPlayer(GameType.SURVIVAL);
        b.getFluid().fill(new FluidStack(Fluids.WATER,4000),FluidAction.EXECUTE);
        ItemStack wheat=new ItemStack(Items.WHEAT,8);h.assertTrue(b.addIngredient(p,wheat) && wheat.isEmpty(),"Wheat may precede the special batch");
        ItemStack honey=new ItemStack(Items.HONEY_BOTTLE,4);
        for(int i=0;i<4;i++)h.assertTrue(b.addIngredient(p,honey),"Honey admitted once");
        for(int i=0;i<16;i++)h.assertTrue(b.addIngredient(p,TissueItem.stack(root())),"16 unique root stacks fit");
        h.assertTrue(!b.addIngredient(p,TissueItem.stack(root())),"Seventeenth root cannot be consumed");
        BarrelBatch batch=((WineBarrel)b).witchcraft$batch();
        h.assertTrue(batch.roots()==16 && batch.honey()==4 && batch.wheat()==8 && b.getIngredient().getStackInSlot(0).isEmpty(),"Batch owns inputs exactly once");
        h.assertTrue(p.getInventory().countItem(Items.GLASS_BOTTLE)==4,"Four honey inputs return four glass bottles");
        CompoundTag saved=b.saveWithoutMetadata(h.getLevel().registryAccess());b.loadWithComponents(saved,h.getLevel().registryAccess());
        h.assertTrue(batch.roots()==16 && batch.ready(b) && batch.wine().rawTu()==6000,"Reload preserves all root and nested-side stock");
        h.assertTrue(b.closeLid(p),"Complete batch closes");
        h.runAfterDelay(120,()->{
            h.assertTrue(b.isBrewing() && b.getOutput().getStackInSlot(0).getCount()==16 && !batch.active() && b.getFluid().isEmpty(),"Starts once, consumes inputs, creates 16 outputs");
            CompoundTag aging=b.saveWithoutMetadata(h.getLevel().registryAccess());aging.putInt("brew_level",6);b.loadWithComponents(aging,h.getLevel().registryAccess());
            BlockPos tap=b.getBlockPos().offset(4,2,0);
            ItemEntity bottles=new ItemEntity(h.getLevel(),tap.getX()+.5,tap.getY()-.5,tap.getZ()+.5,new ItemStack(ModItems.EMPTY_BOTTLE.get(),16));
            h.getLevel().addFreshEntity(bottles);
            for(int i=0;i<16;i++){
                h.assertTrue(b.canTapExtract(h.getLevel(),tap,p),"Original tap accepts original empty wine bottles");
                b.doTapExtract(h.getLevel(),tap);
            }
            var wine=h.getLevel().getEntitiesOfClass(ItemEntity.class,new AABB(tap.below()).inflate(1),e->e.getItem().is(TemporalContent.LIUJIN_VODKA.get()));
            var placed=(com.github.ysbbbbbb.kaleidoscopetavern.blockentity.brew.DrinkBlockEntity)h.getLevel().getBlockEntity(tap.below());
            h.assertTrue(wine.stream().mapToInt(e->e.getItem().getCount()).sum()==15 && placed!=null && !b.isBrewing(),"Native tap places first bottle and drops remaining 15, without changing total yield");
            WineData placedData=WineData.read(placed.getItems().getFirst());
            h.assertTrue(placedData!=null && placedData.rawTu()==6000 && placedData.quality()==6,"Placed first bottle retains score and native quality");
            for(ItemEntity item:wine){WineData data=WineData.read(item.getItem());h.assertTrue(data!=null && data.rawTu()==6000 && data.quality()==6 && data.maturity()==1,"Per-bottle score inputs survive actual tap path");}
            h.succeed();
        });
    }
    static void removal(GameTestHelper h) {
        BarrelBlockEntity b=barrel(h);Player p=h.makeMockPlayer(GameType.SURVIVAL);ItemStack root=TissueItem.stack(root());
        h.assertTrue(!b.addIngredient(p,root) && root.getCount()==1,"No water means no consumed root");
        b.getFluid().fill(new FluidStack(Fluids.WATER,4000),FluidAction.EXECUTE);
        b.addIngredient(p,root);b.addIngredient(p,new ItemStack(Items.HONEY_BOTTLE));
        h.assertTrue(!b.closeLid(p) && b.isOpen(),"Incomplete inputs cannot turn into vinegar");
        BarrelBatch batch=((WineBarrel)b).witchcraft$batch();
        h.assertTrue(batch.takeHoney(b,p,new ItemStack(Items.GLASS_BOTTLE)) && batch.honey()==0,"Honey retrieval requires a real glass bottle");
        h.assertTrue(b.removeIngredient(p) && batch.roots()==0,"Roots are removable before brewing");
        ItemStack returned=p.getInventory().items.stream().filter(i->i.is(TemporalContent.TISSUE.get())).findFirst().orElseThrow();
        h.assertTrue(TissueItem.read(returned).stored()==6000*Tissue.TU && TissueItem.read(returned).sides.size()==1,"Removal preserves attached tissues");
        b.addIngredient(p,returned);b.addIngredient(p,new ItemStack(Items.WHEAT,8));
        BlockPos pos=b.getBlockPos();h.getLevel().destroyBlock(pos,false);
        var drops=h.getLevel().getEntitiesOfClass(ItemEntity.class,new AABB(pos).inflate(2));
        h.assertTrue(drops.stream().filter(e->e.getItem().is(TemporalContent.TISSUE.get())).count()==1
                && drops.stream().filter(e->e.getItem().is(Items.WHEAT)).mapToInt(e->e.getItem().getCount()).sum()==8,"Breaking a batch releases stored solids once");
        h.succeed();
    }
    static void diluteAndBreak(GameTestHelper h) {
        BarrelBlockEntity b=barrel(h);Player p=h.makeMockPlayer(GameType.SURVIVAL);
        b.getFluid().fill(new FluidStack(Fluids.WATER,4000),FluidAction.EXECUTE);
        b.addIngredient(p,TissueItem.stack(root()));b.addIngredient(p,new ItemStack(Items.WHEAT,8));
        for(int i=0;i<4;i++)b.addIngredient(p,new ItemStack(Items.HONEY_BOTTLE));
        b.closeLid(p);
        h.runAfterDelay(120,()->{
            ItemStack output=b.getOutput().getStackInSlot(0);WineData data=WineData.read(output);
            h.assertTrue(output.getCount()==16 && data!=null && data.rawTu()==375 && data.maturity()==.0625,
                    "One root still makes 16 diluted bottles, including its attached stock");
            CompoundTag aging=b.saveWithoutMetadata(h.getLevel().registryAccess());aging.putInt("brew_level",4);b.loadWithComponents(aging,h.getLevel().registryAccess());
            BlockPos pos=b.getBlockPos();h.getLevel().destroyBlock(pos,false);
            var drops=h.getLevel().getEntitiesOfClass(ItemEntity.class,new AABB(pos).inflate(2),e->e.getItem().is(TemporalContent.LIUJIN_VODKA.get()));
            h.assertTrue(drops.stream().mapToInt(e->e.getItem().getCount()).sum()==16,"Finished barrel drops exactly its remaining wine");
            for(var item:drops)h.assertTrue(WineData.read(item.getItem()).quality()==4 && WineData.read(item.getItem()).rawTu()==375,"Broken barrel keeps aging and diluted value");
            h.succeed();
        });
    }
    static void placement(GameTestHelper h) {
        var block=TavernWine.BOTTLE.get();var item=TemporalContent.LIUJIN_VODKA.get();
        h.assertTrue(item instanceof com.github.ysbbbbbb.kaleidoscopetavern.item.DrinkBlockItem,"Native drink interfaces retained");
        Player p=h.makeMockPlayer(GameType.SURVIVAL);p.setShiftKeyDown(true);
        BlockPos pos=h.absolutePos(new BlockPos(3,2,3));
        h.getLevel().setBlockAndUpdate(pos.below(),net.minecraft.world.level.block.Blocks.STONE.defaultBlockState());
        for(int i=0;i<4;i++){
            ItemStack stack=new ItemStack(item);new WineData(4800*(i+1),1,i+1).write(stack);
            if(i==0)stack.remove(com.github.ysbbbbbb.kaleidoscopetavern.init.ModDataComponents.BREW_LEVEL);
            p.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND,stack);
            var target=i==0?pos.below():pos;
            var hit=new net.minecraft.world.phys.BlockHitResult(net.minecraft.world.phys.Vec3.atCenterOf(target),net.minecraft.core.Direction.UP,target,false);
            var result=item.useOn(new net.minecraft.world.item.context.UseOnContext(p,net.minecraft.world.InteractionHand.MAIN_HAND,hit));
            h.assertTrue(result.consumesAction() && stack.isEmpty(),"Sneak placement or native bottle grouping consumes one actual bottle");
        }
        var state=h.getLevel().getBlockState(pos);
        h.assertTrue(state.is(block) && state.getValue(block.getCountProperty())==4,"Native count and model support four bottles");
        var be=(com.github.ysbbbbbb.kaleidoscopetavern.blockentity.brew.DrinkBlockEntity)h.getLevel().getBlockEntity(pos);
        var saved=be.saveWithoutMetadata(h.getLevel().registryAccess());be.loadWithComponents(saved,h.getLevel().registryAccess());
        for(int i=0;i<4;i++)h.assertTrue(WineData.read(be.getItems().get(i)).rawTu()==4800*(i+1) && com.github.ysbbbbbb.kaleidoscopetavern.item.BottleBlockItem.getBrewLevel(be.getItems().get(i))==i+1,"Each placed bottle preserves its independent score and native brew component");
        p.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND,ItemStack.EMPTY);
        block.useItemOn(ItemStack.EMPTY,state,h.getLevel(),pos,p,net.minecraft.world.InteractionHand.MAIN_HAND,
                new net.minecraft.world.phys.BlockHitResult(net.minecraft.world.phys.Vec3.atCenterOf(pos),net.minecraft.core.Direction.UP,pos,false));
        h.assertTrue(h.getLevel().getBlockState(pos).getValue(block.getCountProperty())==3,"Empty hand removes one bottle");
        ItemStack returned=p.getInventory().items.stream().filter(stack->stack.is(item)).findFirst().orElseThrow();
        h.assertTrue(WineData.read(returned).rawTu()==19200 && WineData.read(returned).quality()==4,"Retrieved bottle has its original score");
        h.getLevel().destroyBlock(pos,true);
        var drops=h.getLevel().getEntitiesOfClass(ItemEntity.class,new AABB(pos).inflate(2),e->e.getItem().is(item));
        h.assertTrue(drops.stream().mapToInt(e->e.getItem().getCount()).sum()==3,"Breaking drops the three remaining bottles exactly once");
        h.assertTrue(drops.stream().mapToDouble(e->WineData.read(e.getItem()).rawTu()*e.getItem().getCount()).sum()==28800,"Dropped bottles retain distinct data");
        var lines=new java.util.ArrayList<net.minecraft.network.chat.Component>();
        item.appendHoverText(returned,Item.TooltipContext.of(h.getLevel()),lines,TooltipFlag.NORMAL);
        h.assertTrue(lines.stream().anyMatch(c->c.getContents() instanceof net.minecraft.network.chat.contents.TranslatableContents t && t.getKey().equals("tooltip.kaleidoscope_tavern.bottle_block.brew_level")),"Tooltip delegates native Tavern quality format");
        h.assertTrue(lines.stream().anyMatch(c->c.getString().contains("岁月盈身") || c.getString().contains("Years Abundance")),"Native potion-effect line displays dynamic grade and duration");
        h.succeed();
    }
    static void placedCarrier(GameTestHelper h) {
        BarrelBlockEntity b=barrel(h);Player p=h.makeMockPlayer(GameType.SURVIVAL);
        b.getFluid().fill(new FluidStack(Fluids.WATER,4000),FluidAction.EXECUTE);
        b.addIngredient(p,TissueItem.stack(root()));b.addIngredient(p,new ItemStack(Items.WHEAT,8));
        for(int i=0;i<4;i++)b.addIngredient(p,new ItemStack(Items.HONEY_BOTTLE));b.closeLid(p);
        h.runAfterDelay(120,()->{
            BlockPos tap=b.getBlockPos().offset(4,2,0);
            h.getLevel().setBlockAndUpdate(tap.below(),((net.minecraft.world.item.BlockItem)ModItems.EMPTY_BOTTLE.get()).getBlock().defaultBlockState());
            h.assertTrue(b.canTapExtract(h.getLevel(),tap,p),"Native placed empty bottle is a valid carrier");
            b.doTapExtract(h.getLevel(),tap);
            var be=(com.github.ysbbbbbb.kaleidoscopetavern.blockentity.brew.DrinkBlockEntity)h.getLevel().getBlockEntity(tap.below());
            WineData data=WineData.read(be.getItems().getFirst());
            h.assertTrue(data!=null && data.rawTu()==375 && data.quality()==1 && b.getOutput().getStackInSlot(0).getCount()==15,"Filled ground bottle gets actual batch data and consumes one output");h.succeed();
        });
    }
    static void intoxication(GameTestHelper h) {
        var nativeTipsy=com.github.ysbbbbbb.kaleidoscopetavern.init.ModEffects.SLIGHTLY_TIPSY;
        var template=com.github.ysbbbbbb.kaleidoscopetavern.datamap.resources.DrinkEffectDataReloadListener.INSTANCE.get(TemporalContent.LIUJIN_VODKA.get());
        h.assertTrue(template!=null && template.effects().size()==6,"Tavern loaded six actual effect rows for Liujin");
        int[] seconds={5,45,30,30,20,10};
        for(int grade=1;grade<=6;grade++){
            var p=new net.neoforged.neoforge.common.util.FakePlayer(h.getLevel(),new com.mojang.authlib.GameProfile(java.util.UUID.randomUUID(),"wine-intoxication"));
            ItemStack wine=new ItemStack(TemporalContent.LIUJIN_VODKA.get());new WineData(4800,1,grade).write(wine);
            ItemStack result=wine.finishUsingItem(h.getLevel(),p);
            h.assertTrue(result.is(ModItems.EMPTY_BOTTLE.get()) && p.hasEffect(BrewContent.YEARS_ABUNDANCE),"Intoxication keeps native container and body time");
            var expected=grade==1 ? net.minecraft.world.effect.MobEffects.CONFUSION : nativeTipsy;
            var effect=p.getEffect(expected);
            h.assertTrue(effect!=null && effect.getDuration()==seconds[grade-1]*20 && effect.getAmplifier()==0,"Native intoxication duration follows brewing grade");
            BodyTime time=p.getData(BrewContent.BODY_TIME);double before=time.baseTicks(), nauseaPrice=time.price(100);
            boolean remains=effect.tick(p,()->{});
            if(grade==1)h.assertTrue(!remains && Math.abs(time.baseTicks()-(before-nauseaPrice))<.00001,"New first-quality nausea pays five seconds at the body's actual grade discount");
            else h.assertTrue(remains && time.baseTicks()==before && effect.getDuration()==seconds[grade-1]*20-1,"Neutral tipsiness remains and does not spend body time");
            var lines=new java.util.ArrayList<net.minecraft.network.chat.Component>();
            ItemStack preview=new ItemStack(TemporalContent.LIUJIN_VODKA.get());new WineData(4800,1,grade).write(preview);
            preview.getItem().appendHoverText(preview,Item.TooltipContext.of(h.getLevel()),lines,TooltipFlag.NORMAL);
            String name=net.minecraft.network.chat.Component.translatable(expected.value().getDescriptionId()).getString();
            h.assertTrue(lines.stream().anyMatch(c->c.getString().contains(name)),"Native tooltip includes the configured intoxication effect");
            h.assertTrue(!p.hasEffect(net.minecraft.world.effect.MobEffects.DAMAGE_BOOST),"Does not accidentally copy ordinary vodka's strength bonus");
        }
        h.succeed();
    }
    static void vanilla(GameTestHelper h) {
        BarrelBlockEntity b=barrel(h);Player p=h.makeMockPlayer(GameType.SURVIVAL);
        b.getFluid().fill(new FluidStack(Fluids.WATER,4000),FluidAction.EXECUTE);
        b.addIngredient(p,new ItemStack(Items.POTATO,16));b.closeLid(p);
        h.runAfterDelay(120,()->{
            h.assertTrue(b.getOutput().getStackInSlot(0).is(ModItems.VODKA.get()) && b.getOutput().getStackInSlot(0).getCount()==16,"Ordinary Tavern recipe remains unchanged");h.succeed();
        });
    }
}
