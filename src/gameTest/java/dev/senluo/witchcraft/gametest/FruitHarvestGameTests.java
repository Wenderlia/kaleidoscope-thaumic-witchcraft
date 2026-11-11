package dev.senluo.witchcraft.gametest;

import dev.senluo.witchcraft.time.*;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.inventory.AnvilMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@PrefixGameTestTemplate(false)
public final class FruitHarvestGameTests {
    private static FruitEntity fruit(GameTestHelper h, LivingEntity core, int grade) {
        FruitEntity fruit=TreeGameTests.nodeFruit(h,core,grade);
        fruit.grade=grade;
        var tag=fruit.saveWithoutMetadata(h.getLevel().registryAccess());
        tag.putDouble("HarvestRoll",0.95);
        fruit.loadWithComponents(tag,h.getLevel().registryAccess());
        return fruit;
    }

    private static LivingEntity greatTree(GameTestHelper h) {
        LivingEntity core=TreeGameTests.plant(h,true);
        core.tissue.giant=true;GiantRoots.restore(core.tissue);
        return core;
    }

    private static BarrelBlockEntity barrel(GameTestHelper h, BlockPos pos) {
        h.getLevel().setBlock(pos, Blocks.BARREL.defaultBlockState(), 3);
        return (BarrelBlockEntity) h.getLevel().getBlockEntity(pos);
    }

    private static ItemStack shears(GameTestHelper h, int fortune) {
        ItemStack tool = new ItemStack(Items.SHEARS);
        if (fortune > 0) tool.enchant(h.getLevel().registryAccess().lookupOrThrow(Registries.ENCHANTMENT)
                .getOrThrow(Enchantments.FORTUNE), fortune);
        return tool;
    }

    private static int storedFruit(BarrelBlockEntity barrel) {
        int count = 0;
        for (int i = 0; i < barrel.getContainerSize(); i++)
            if (barrel.getItem(i).is(TemporalContent.FRUIT_ITEM.get())) count += barrel.getItem(i).getCount();
        return count;
    }

    private static List<ItemEntity> drops(GameTestHelper h, BlockPos pos) {
        return h.getLevel().getEntitiesOfClass(ItemEntity.class, new AABB(pos).inflate(3),
                entity -> entity.getItem().is(TemporalContent.FRUIT_ITEM.get()));
    }

    @GameTest(templateNamespace="kaleidoscope_witchcraft", template="empty", timeoutTicks=60)
    public static void fruitFortuneUsesAgreedSymmetricDistribution(GameTestHelper h) {
        int[] counts = new int[5];
        for (int i = 0; i < 10_000; i++) counts[FruitHarvest.count(3, (i + 0.5) / 10_000)]++;
        h.assertTrue(Math.abs(counts[1] - 1345) <= 2 && Math.abs(counts[2] - 3655) <= 2
                && counts[1] == counts[4] && counts[2] == counts[3], "Fortune III follows the confirmed 13/37/37/13 distribution");
        h.assertTrue(FruitHarvest.count(0, 0.99) == 1 && FruitHarvest.count(1, 0.01) == 1
                && FruitHarvest.count(1, 0.99) == 2 && FruitHarvest.count(255, 0.999999) == 256,
                "No Fortune yields one; each supported level adds exactly one to the upper bound");
        h.succeed();
    }

    @GameTest(templateNamespace="kaleidoscope_witchcraft", template="empty", timeoutTicks=100)
    public static void fruitAutoHarvestWaitsForQualityLimit(GameTestHelper h) {
        LivingEntity core = greatTree(h);
        core.tissue.bond = dev.senluo.witchcraft.ritual.TreeBond.of(java.util.UUID.randomUUID(), "owner");
        FruitEntity fruit = fruit(h, core, 4);fruit.grade=0;
        BarrelBlockEntity storage = barrel(h, core.getBlockPos().east());
        storage.setItem(0, shears(h, 0));
        FruitHarvest.tick(core, List.of(fruit));
        h.runAfterDelay(25, () -> {
            h.assertTrue(!fruit.isRemoved() && storedFruit(storage) == 0 && storage.getItem(0).getDamageValue() == 0,
                    "A juvenile fixed-quality fruit must remain until its own quality is mature");
            fruit.grade = 4;
            h.succeedWhen(() -> {
                h.assertTrue(fruit.isRemoved() && storedFruit(storage) == 1, "The native tree scheduler collects the ripe fruit into its adjacent barrel");
                h.assertTrue(storage.getItem(0).getDamageValue() == 1 && drops(h, fruit.getBlockPos()).isEmpty(),
                        "One cut consumes one durability, with no duplicate world drop");
            });
        });
    }

    @GameTest(templateNamespace="kaleidoscope_witchcraft", template="empty", timeoutTicks=60)
    public static void fruitAutoHarvestUnlocksOnlyAfterOwnership(GameTestHelper h) {
        LivingEntity core = greatTree(h);
        FruitEntity fruit = fruit(h, core, 4);
        BarrelBlockEntity storage = barrel(h, core.getBlockPos().east());
        storage.setItem(0, shears(h, 0));
        FruitHarvest.tick(core, List.of(fruit));
        h.assertTrue(!fruit.isRemoved() && storedFruit(storage) == 0 && storage.getItem(0).getDamageValue() == 0,
                "An unbound tree cannot collect fruit or consume the tool");
        core.tissue.bond = dev.senluo.witchcraft.ritual.TreeBond.of(java.util.UUID.randomUUID(), "owner");
        FruitHarvest.tick(core, List.of(fruit));
        h.succeedWhen(() -> h.assertTrue(fruit.isRemoved() && storedFruit(storage) == 1 && storage.getItem(0).getDamageValue() == 1,
                "Completing ownership unlocks native collection without recreating the fruit or container"));
    }

    @GameTest(templateNamespace="kaleidoscope_witchcraft", template="empty", timeoutTicks=100)
    public static void fruitFullContainerPreservesYieldAndTool(GameTestHelper h) {
        LivingEntity core = greatTree(h);
        core.tissue.bond = dev.senluo.witchcraft.ritual.TreeBond.of(java.util.UUID.randomUUID(), "owner");
        FruitEntity fruit = fruit(h, core, 4);
        BarrelBlockEntity storage = barrel(h, core.getBlockPos().west());
        for (int i = 0; i < storage.getContainerSize(); i++) storage.setItem(i, new ItemStack(Items.DIRT, 64));
        ItemStack tool = shears(h, 3); tool.setDamageValue(100); storage.setItem(0, tool);
        storage.setItem(1, FruitItem.stack(4).copyWithCount(61)); // Only three of the four rolled fruit would fit.
        FruitHarvest.tick(core, List.of(fruit));
        var saved = fruit.saveWithoutMetadata(h.getLevel().registryAccess());
        fruit.loadWithComponents(saved, h.getLevel().registryAccess());
        h.runAfterDelay(25, () -> {
            h.assertTrue(!fruit.isRemoved() && storedFruit(storage) == 61 && storage.getItem(0).getDamageValue() == 100,
                    "A partial fit must neither harvest nor damage the shears");
            h.assertTrue(FruitHarvest.product(fruit, 3).getCount() == 4, "Save/load and retries keep this fruit's original roll");
            storage.setItem(1, FruitItem.stack(4).copyWithCount(60));
            h.succeedWhen(() -> {
                h.assertTrue(fruit.isRemoved() && storedFruit(storage) == 64 && storage.getItem(0).getDamageValue() == 101,
                        "Freeing exactly four spaces commits the whole yield and one tool use");
                h.assertTrue(drops(h, fruit.getBlockPos()).isEmpty(), "A normally functioning container receives all output");
            });
        });
    }

    @GameTest(templateNamespace="kaleidoscope_witchcraft", template="empty", timeoutTicks=60)
    public static void giantFruitAutoHarvestBreaksLastUseShearsOnce(GameTestHelper h) {
        LivingEntity core = greatTree(h);
        core.tissue.bond = dev.senluo.witchcraft.ritual.TreeBond.of(java.util.UUID.randomUUID(), "owner");
        core.tissue.giant = true; GiantRoots.restore(core.tissue);
        FruitEntity fruit = fruit(h, core, 5);
        BarrelBlockEntity storage = barrel(h, core.getBlockPos().north());
        ItemStack tool = shears(h, 3); tool.setDamageValue(tool.getMaxDamage() - 1); storage.setItem(0, tool);
        FruitHarvest.tick(core, List.of(fruit));
        FruitHarvest.tick(core,List.of(fruit));
        h.succeedWhen(() -> {
        h.assertTrue(fruit.isRemoved() && storedFruit(storage)==4 && !storage.getItem(0).is(Items.SHEARS),
                "The final tool use harvests V with Fortune and breaks the shears");
        FruitHarvest.tick(core,List.of(fruit));
        for(int i=0;i<storage.getContainerSize();i++)
            if(storage.getItem(i).is(TemporalContent.FRUIT_ITEM.get()))
                h.assertTrue(FruitItem.grade(storage.getItem(i))==5,"Every bonus fruit retains quality V");
        h.assertTrue(drops(h,fruit.getBlockPos()).isEmpty(),"Repeated calls cannot emit or collect the same fruit twice");
        });
    }

    @GameTest(templateNamespace="kaleidoscope_witchcraft", template="empty", timeoutTicks=60)
    public static void fruitAutoHarvestRequiresAdjacentShears(GameTestHelper h) {
        LivingEntity core = greatTree(h);
        core.tissue.bond = dev.senluo.witchcraft.ritual.TreeBond.of(java.util.UUID.randomUUID(), "owner");
        FruitEntity fruit = fruit(h, core, 4);
        BarrelBlockEntity diagonal = barrel(h, core.getBlockPos().east().north());
        diagonal.setItem(0, shears(h, 3));
        BarrelBlockEntity adjacent = barrel(h, core.getBlockPos().south());
        adjacent.setItem(0, new ItemStack(Items.IRON_AXE));
        FruitHarvest.tick(core, List.of(fruit));
        h.assertTrue(!fruit.isRemoved() && storedFruit(diagonal) == 0 && storedFruit(adjacent) == 0
                && diagonal.getItem(0).getDamageValue() == 0 && adjacent.getItem(0).getDamageValue() == 0,
                "Only six-face adjacent containers holding shears can harvest; other tools and diagonal storage do not qualify");
        h.succeed();
    }

    @GameTest(templateNamespace="kaleidoscope_witchcraft", template="empty", timeoutTicks=60)
    public static void manualFruitShearingSharesFortuneAndDurability(GameTestHelper h) {
        LivingEntity core = greatTree(h);
        core.tissue.bond = dev.senluo.witchcraft.ritual.TreeBond.of(java.util.UUID.randomUUID(), "owner");
        FruitEntity first = fruit(h, core, 1);
        var player = DevelopmentGameTests.player(h, core);
        ItemStack tool = shears(h, 3); player.setItemInHand(InteractionHand.MAIN_HAND, tool);
        BlockPos pos = first.getBlockPos();
        player.gameMode.useItemOn(player, h.getLevel(), tool, InteractionHand.MAIN_HAND,
                new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false));
        h.assertTrue(first.isRemoved() && tool.getDamageValue() == 1, "Manual right-click cuts a mature node fruit");
        FruitEntity second = fruit(h, core, 1);
        ItemStack lastUse = shears(h, 3); lastUse.setDamageValue(lastUse.getMaxDamage() - 1);
        player.setItemInHand(InteractionHand.MAIN_HAND, lastUse);
        h.assertTrue(player.gameMode.destroyBlock(pos) && second.isRemoved() && lastUse.isEmpty(), "Left-click uses vanilla durability, including tool breakage");
        var products = drops(h, pos);
        h.assertTrue(products.stream().mapToInt(e -> e.getItem().getCount()).sum() == 8
                && products.stream().allMatch(e -> FruitItem.grade(e.getItem()) == 1),
                "Both cuts yield four first-quality fruit; Fortune neither promotes quality nor duplicates drops");
        h.succeed();
    }

    @GameTest(templateNamespace="kaleidoscope_witchcraft", template="empty", timeoutTicks=60)
    public static void shearsAnvilAcceptsFortuneAndRetainsConflicts(GameTestHelper h) {
        LivingEntity core = greatTree(h);
        core.tissue.bond = dev.senluo.witchcraft.ritual.TreeBond.of(java.util.UUID.randomUUID(), "owner");
        var player = DevelopmentGameTests.player(h, core);
        var enchantments = h.getLevel().registryAccess().lookupOrThrow(Registries.ENCHANTMENT);
        ItemStack book = new ItemStack(Items.ENCHANTED_BOOK); book.enchant(enchantments.getOrThrow(Enchantments.FORTUNE), 3);
        AnvilMenu menu = new AnvilMenu(1, player.getInventory());
        menu.getSlot(0).set(shears(h, 0)); menu.getSlot(1).set(book.copy()); menu.createResult();
        h.assertTrue(menu.getSlot(2).getItem().is(Items.SHEARS) && FruitHarvest.fortune(h.getLevel(), menu.getSlot(2).getItem()) == 3
                && menu.getCost() > 0, "Survival anvil accepts Fortune on shears and retains its XP fee");
        ItemStack silk = shears(h, 0); silk.enchant(enchantments.getOrThrow(Enchantments.SILK_TOUCH), 1);
        menu.getSlot(0).set(silk); menu.getSlot(1).set(book.copy()); menu.createResult();
        h.assertTrue(menu.getSlot(2).getItem().isEmpty(), "Silk Touch still conflicts with Fortune");
        menu.getSlot(0).set(new ItemStack(Items.IRON_SWORD)); menu.getSlot(1).set(book.copy()); menu.createResult();
        h.assertTrue(menu.getSlot(2).getItem().isEmpty(), "Non-shears do not gain anvil Fortune eligibility");
        h.succeed();
    }
}
