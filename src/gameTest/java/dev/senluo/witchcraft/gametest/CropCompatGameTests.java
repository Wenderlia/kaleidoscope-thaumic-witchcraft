package dev.senluo.witchcraft.gametest;

import dev.senluo.witchcraft.compat.CropGrowth;
import dev.senluo.witchcraft.time.*;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@PrefixGameTestTemplate(false)
public final class CropCompatGameTests {
    @GameTest(templateNamespace="kaleidoscope_witchcraft", template="empty", timeoutTicks=40)
    public static void lazyCropsFollowPlantHarvestAndRemoval(GameTestHelper h) {
        LivingEntity plant = DevelopmentGameTests.plant(h, 4, 4, false);
        var level = h.getLevel();
        var world = TemporalWorld.get(level);
        Colony group = world.colony(plant);
        BlockPos crop = plant.getBlockPos().east();
        level.setBlock(crop.below(), Blocks.FARMLAND.defaultBlockState(), 3);
        h.assertTrue(!world.crops.get(group).contains(crop), "Initial empty discovery");
        level.setBlock(crop, Blocks.WHEAT.defaultBlockState().setValue(CropBlock.AGE, 7), 3);
        List<BlockPos> indexed = world.crops.get(group);
        h.assertTrue(indexed.contains(crop) && !CropGrowth.canAttempt(level.getBlockState(crop)), "Mature crops stay indexed without paid growth");
        level.setBlock(crop, Blocks.WHEAT.defaultBlockState(), 3);
        h.assertTrue(world.crops.get(group) == indexed && CropGrowth.canAttempt(level.getBlockState(crop)), "Harvest age changes reuse positions and recheck eligibility");
        level.removeBlock(crop, false);
        h.assertTrue(!world.crops.get(group).contains(crop), "Removal invalidates targets immediately");
        h.succeed();
    }

    @GameTest(templateNamespace="kaleidoscope_witchcraft", template="empty", timeoutTicks=40)
    public static void woodenRootConversionKeepsColonyWithoutHarvestDrops(GameTestHelper h) {
        var level = h.getLevel();
        BlockPos core = h.absolutePos(new BlockPos(4, 3, 4)), root = core.below();
        level.setBlock(root, TemporalContent.ROOT.get().defaultBlockState(), 3);
        level.setBlock(core, TemporalContent.CORE.get().defaultBlockState(), 3);
        LivingEntity tree = (LivingEntity) level.getBlockEntity(core);
        tree.tissue.giant = true;
        TemporalWorld world = TemporalWorld.get(level);
        world.add(tree);
        BlockPos farm = root.east();
        level.setBlock(farm, TemporalContent.ROOTED_FARMLAND.get().defaultBlockState(), 3);
        h.assertTrue(world.colony(tree).roots.contains(root), "Root is connected before conversion");
        h.assertTrue(world.growWood(root, TemporalContent.TIME_LOG.get().defaultBlockState()), "Conversion commits");
        h.assertTrue(level.getBlockState(core).canSurvive(level, core) && level.getBlockEntity(root) == null,
                "Core survives; a passive log has no block entity");
        h.assertTrue(world.colony(tree).logs.contains(root) && world.colony(tree).roots.contains(farm), "Log retains the farmland route");
        h.assertTrue(level.getEntitiesOfClass(ItemEntity.class, new AABB(root).inflate(1)).isEmpty(), "Conversion emits no root or soil drops");
        h.succeed();
    }

    @GameTest(templateNamespace="kaleidoscope_witchcraft", template="empty", timeoutTicks=40)
    public static void cropSubunitPriceKeepsPrepaymentAcrossSave(GameTestHelper h) {
        LivingEntity plant = DevelopmentGameTests.plant(h, 4, 4, false);
        var saved = plant.saveWithoutMetadata(h.getLevel().registryAccess());
        int prepaid = 0;
        long paid = 0;
        for (int i = 0; i < 4096; i++) {
            long charge = CropSupplyRates.charge(Tissue.TU, CropSupplyRates.TICK_WORK, prepaid);
            prepaid = CropSupplyRates.credit(Tissue.TU, CropSupplyRates.TICK_WORK, prepaid, charge);
            paid += charge;
            if (i == 0) {
                saved.putInt("CropCostCredit", prepaid);
                plant.loadWithComponents(saved, h.getLevel().registryAccess());
                prepaid = plant.saveWithoutMetadata(h.getLevel().registryAccess()).getInt("CropCostCredit");
            }
        }
        h.assertTrue(paid == 3 * Tissue.TU && prepaid == 0, "4096 native ticks equal three attempts, including a reload and sub-mTU charges");
        h.succeed();
    }

    private static Block requiredBlock(GameTestHelper h, String id) {
        var key = ResourceLocation.parse(id);
        h.assertTrue(BuiltInRegistries.BLOCK.containsKey(key), "Requires the TC4R 20737 withCompat profile: " + id);
        return BuiltInRegistries.BLOCK.get(key);
    }

    @GameTest(templateNamespace="kaleidoscope_witchcraft", template="empty", timeoutTicks=40)
    public static void infusedCropCatalysisUsesNativeClock(GameTestHelper h) {
        var level = h.getLevel();
        BlockPos pos = h.absolutePos(new BlockPos(4, 3, 4));
        Block crop = requiredBlock(h, "thaumictinkerer:infused_crop");
        level.setBlock(pos.below(), requiredBlock(h, "thaumictinkerer:infused_farmland").defaultBlockState(), 3);
        level.setBlock(pos, crop.defaultBlockState(), 3);
        level.setBlock(pos.above(), Blocks.SEA_LANTERN.defaultBlockState(), 3);
        var entity = level.getBlockEntity(pos);
        CompoundTag data = entity.saveWithoutMetadata(level.registryAccess());
        data.putString("main_aspect", "thaumcraft:aer");
        ListTag tendencies = new ListTag();
        CompoundTag water = new CompoundTag();
        water.putString("aspect", "thaumcraft:aqua");
        water.putInt("amount", 1001); // Valid saved-data range; forces a roll of 1 under every supported growth-base config.
        tendencies.add(water);
        data.put("tendencies", tendencies);
        entity.loadWithComponents(data, level.registryAccess());
        var state = level.getBlockState(pos);
        h.assertTrue(!CropGrowth.canRandomlyGrow(level, pos, state), "The maintenance random tick is not a growth source");
        state.randomTick(level, pos, RandomSource.create(1));
        h.assertTrue(level.getBlockState(pos).getValue(CropBlock.AGE) == 0, "Native random tick does not advance infused crops");
        var operation = CropGrowth.operation(level, pos, state);
        h.assertTrue(operation != null && operation.timed(), "Native ticker is available for catalysis");
        operation.advance().run();
        h.assertTrue(level.getBlockState(pos).getValue(CropBlock.AGE) == 1, "Catalysis executes the crop's native clock and genetics");
        h.succeed();
    }

    @GameTest(templateNamespace="kaleidoscope_witchcraft", template="empty", timeoutTicks=40)
    public static void manaBeanCatalysisKeepsNativeHybridization(GameTestHelper h) {
        h.setBiome(net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.BIOME,
                ResourceLocation.fromNamespaceAndPath("thaumcraft", "magical_forest")));
        var level = h.getLevel();
        BlockPos pos = h.absolutePos(new BlockPos(4, 3, 4));
        Block pod = requiredBlock(h, "thaumcraft:mana_pod");
        var age = (net.minecraft.world.level.block.state.properties.IntegerProperty) pod.getStateDefinition().getProperty("age");
        for (BlockPos p : List.of(pos, pos.east())) {
            level.setBlock(p.above(), TemporalContent.TIME_LOG.get().defaultBlockState(), 3);
            level.setBlock(p, pod.defaultBlockState().setValue(age, 2), 3);
            var entity = level.getBlockEntity(p);
            var data = entity.saveWithoutMetadata(level.registryAccess());
            data.putString("aspect", p.equals(pos) ? "thaumcraft:aer" : "thaumcraft:ignis");
            entity.loadWithComponents(data, level.registryAccess());
        }
        h.assertTrue(level.getBlockState(pos).canSurvive(level, pos), "Native mana pods accept time wood in the magical forest");
        long seed = 0;
        for (; seed < 100000; seed++) {
            RandomSource probe = RandomSource.create(seed);
            if (probe.nextInt(30) != 0) continue;
            int outcome = probe.nextInt(6);
            if (outcome != 0 && outcome != 3) break;
        }
        h.assertTrue(seed < 100000, "Deterministic hybridization seed found");
        level.random.setSeed(seed);
        var operation = CropGrowth.operation(level, pos, level.getBlockState(pos));
        h.assertTrue(operation != null && !operation.timed(), "Mana pods retain their random growth mechanism");
        operation.advance().run();
        h.assertTrue(level.getBlockState(pos).getValue(age) == 3, "Catalysis reaches the native crossing stage");
        h.assertTrue(level.getBlockEntity(pos).saveWithoutMetadata(level.registryAccess()).getString("aspect").equals("lux"),
                "Native adjacent aer/ignis crossing produces lux during catalysis");
        level.setBlock(pos, level.getBlockState(pos).setValue(age, 7), 3);
        h.assertTrue(CropGrowth.operation(level, pos, level.getBlockState(pos)) == null, "Mature pods do not buy repeated crossing rolls");
        h.succeed();
    }
}
