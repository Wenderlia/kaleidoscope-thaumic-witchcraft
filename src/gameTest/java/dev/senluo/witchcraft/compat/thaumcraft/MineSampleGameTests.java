package dev.senluo.witchcraft.compat.thaumcraft;

import java.util.Set;
import java.util.UUID;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Detached content crosses the same coordinate/identity boundary used by native working-copy loads. */
@PrefixGameTestTemplate(false)
public final class MineSampleGameTests {
    @GameTest(templateNamespace="kaleidoscope_witchcraft", template="empty")
    public static void mineSupplyLeasesDoNotRebuildForecastAndSurvivePromotion(GameTestHelper h) {
        var pos = h.absolutePos(new BlockPos(4, 3, 4));
        var block = dev.tc4port.thaumcraft.api.ThaumcraftContent.block("arcane_bore");
        var facing = (net.minecraft.world.level.block.state.properties.DirectionProperty) block.getStateDefinition().getProperty("facing");
        h.getLevel().setBlock(pos.below(), dev.tc4port.thaumcraft.api.ThaumcraftContent.block("arcane_bore_base").defaultBlockState(), 3);
        h.getLevel().setBlock(pos, block.defaultBlockState().setValue(facing, net.minecraft.core.Direction.DOWN), 3);
        h.getLevel().setBlock(pos.east(), net.minecraft.world.level.block.Blocks.REDSTONE_BLOCK.defaultBlockState(), 3);
        var bore = h.getLevel().getBlockEntity(pos);
        h.assertTrue(bore instanceof net.minecraft.world.Container, "The native drill remains assembled after powering it");
        var inventory = (net.minecraft.world.Container) bore;
        inventory.setItem(0, new net.minecraft.world.item.ItemStack(dev.tc4port.thaumcraft.api.ThaumcraftContent.item("focus_excavation")));
        inventory.setItem(1, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.DIAMOND_PICKAXE));
        var level = MixedMine.forHost(h.getLevel().getServer().overworld());
        var supply = new MineSupply(20261117L).atLevel(level);
        try {
            supply.bounds(0, -60); supply.maintain(bore, 0); supply.column(0L, 0);
            int tick = level.getServer().getTickCount();
            supply.prepare(tick);
            int builds = supply.snapshot().forecastRebuilds();
            h.assertTrue(builds == 1, "A powered native bore establishes one forecast");
            long first = new ChunkPos(supply.origin(0)).toLong();
            for (int i = 0; i < 128; i++) {
                var lease = supply.hold(0, first, null); lease.close(); lease.close();
            }
            supply.prepare(tick);
            h.assertTrue(supply.snapshot().forecastRebuilds() == builds,
                    "128 target acquisitions and idempotent releases do not rebuild column prediction");
            var held = supply.hold(0, first, null);
            supply.column(0L, 1); supply.prepare(tick);
            h.assertTrue(supply.snapshot().forecastRebuilds() == builds + 1 && supply.reads.planned(first),
                    "Advancing the column promotes the next band but preserves the old target's dependencies");
            supply.close(); supply.prepare(tick);
            h.assertTrue(!held.current(supply) && supply.reads.planned(first),
                    "Closing rejects receipts while retaining the resources of a request still being cancelled");
            held.close(); supply.prepare(tick);
            h.assertTrue(!supply.reads.planned(first) && supply.reads.level == null,
                    "The last receipt releases the retired run without resurrecting its forecast");
        } finally { supply.unload(); }
        h.succeed();
    }

    @GameTest(templateNamespace="kaleidoscope_witchcraft", template="empty", timeoutTicks=200)
    public static void mineTemplatePoolDistinguishesPendingAndPreservesSelection(GameTestHelper h) {
        var memory = MineSamplePool.get(h.getLevel().getServer());
        var source = net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("kaleidoscope_witchcraft", "test_" + UUID.randomUUID());
        var pool = memory.source(source);
        h.assertTrue(pool.request(0, 0, Set.of()).status() == MineSamplePool.Status.WAITING,
                "An index being read is not an empty template pool");
        h.startSequence().thenWaitUntil(() -> h.assertTrue(pool.request(0, 0, Set.of()).status() == MineSamplePool.Status.ABSENT,
                "The new isolated index eventually confirms absence")).thenExecute(() -> {
            var level = MixedMine.forHost(h.getLevel().getServer().overworld());
            var tag = payload(level); tag.getCompound("Description").putString("Source", source.toString());
            var sample = sample(h, tag);
            try {
                Set<UUID> before = pool.ids();
                h.assertTrue(pool.admit(sample), "The pool accepts an independently owned candidate");
                h.assertTrue(before.isEmpty() && pool.ids().contains(sample.description.id()),
                        "Slot membership changes publish a new immutable index");
                h.assertTrue(pool.request(0, 0, Set.of(sample.description.id())).status() == MineSamplePool.Status.ABSENT,
                        "The shared store cannot clear an immutable caller exclusion set to start another round");
                var first = pool.request(0, 0, Set.of());
                var second = pool.request(0, 0, Set.of());
                h.assertTrue(first.status() == MineSamplePool.Status.READY && second.status() == MineSamplePool.Status.READY,
                        "Independent consumers can retain the same read-only content");
                first.sample().release(); second.sample().release();
                h.assertTrue(sample.references() >= 2, "Returning readers leaves the caller and pool references alive");
            } finally { sample.release(); }
        }).thenSucceed();
    }

    private static CompoundTag payload(ServerLevel level) {
        var description = new MineSample.Description(UUID.randomUUID(),
                MixedMine.source(level.dimension().location()).location(), 8, 8, 0, 0,
                level.getMinBuildHeight(), level.getHeight(), 2, Set.of(0L), Set.of(0L));
        CompoundTag terrain = new CompoundTag();
        terrain.putString("Status", "minecraft:full");
        terrain.putInt("DataVersion", SharedConstants.getCurrentVersion().getDataVersion().getVersion());
        terrain.putInt("xPos", 0); terrain.putInt("zPos", 0);
        CompoundTag section = new CompoundTag(), states = new CompoundTag(), stone = new CompoundTag();
        stone.putString("Name", "minecraft:stone");
        ListTag palette = new ListTag(); palette.add(stone); states.put("palette", palette);
        section.putByte("Y", (byte) 0); section.put("block_states", states);
        ListTag sections = new ListTag(); sections.add(section); terrain.put("sections", sections);

        CompoundTag chest = new CompoundTag(); chest.putString("id", "minecraft:chest");
        chest.putInt("x", 8); chest.putInt("y", 0); chest.putInt("z", 8);
        chest.putString("LootTable", "minecraft:chests/simple_dungeon"); chest.putLong("LootTableSeed", 123);
        ListTag blockEntities = new ListTag(); blockEntities.add(chest); terrain.put("block_entities", blockEntities);
        CompoundTag tick = new CompoundTag(); tick.putInt("x", 8); tick.putInt("y", 0); tick.putInt("z", 8);
        tick.putString("i", "minecraft:stone"); tick.putInt("t", 7); tick.putInt("p", 0);
        ListTag ticks = new ListTag(); ticks.add(tick); terrain.put("block_ticks", ticks);
        CompoundTag references = new CompoundTag(), structures = new CompoundTag();
        references.putLongArray("minecraft:mineshaft", new long[]{ChunkPos.asLong(-1, 1)});
        structures.put("References", references); terrain.put("structures", structures);
        terrain.putInt("brewinandchewin:data_version", 100);
        terrain.putInt("mek_world_gen_version", 7);
        CompoundTag external = new CompoundTag(); external.putUUID("Network", UUID.randomUUID());
        terrain.put("example:network", external);
        CompoundTag cart = new CompoundTag(); cart.putString("id", "minecraft:chest_minecart");
        cart.putUUID("UUID", UUID.randomUUID());
        ListTag position = new ListTag();
        position.add(DoubleTag.valueOf(8.5)); position.add(DoubleTag.valueOf(0)); position.add(DoubleTag.valueOf(8.5));
        cart.put("Pos", position);
        ListTag entities = new ListTag(); entities.add(cart);
        CompoundTag entry = new CompoundTag(); entry.putLong("Offset", 0);
        entry.put("Terrain", terrain); entry.put("Entities", entities);
        ListTag chunks = new ListTag(); chunks.add(entry);
        CompoundTag result = new CompoundTag(); result.put("Description", description.save());
        result.putLong("Seed", 12345); result.put("Chunks", chunks); return result;
    }

    private static MineSample sample(GameTestHelper h, CompoundTag payload) {
        var memory = MineSamplePool.get(h.getLevel().getServer());
        long bytes = payload.sizeInBytes() + 4096L;
        h.assertTrue(memory.reserve(bytes), "The isolated content fixture fits the shared budget");
        return MineSample.decode(memory, payload, bytes);
    }

    @GameTest(templateNamespace="kaleidoscope_witchcraft", template="empty")
    public static void mineSampleCopiesKeepLootAndIsolateCoordinates(GameTestHelper h) {
        var level = MixedMine.forHost(h.getLevel().getServer().overworld());
        var sample = sample(h, payload(level));
        try {
            h.assertTrue(sample.compatible(level), "Native payload with mod metadata is eligible for reuse");
            var destination = new BlockPos(40, 0, -24);
            var first = sample.terrain(0, destination, 900);
            var second = sample.terrain(0, new BlockPos(8, 0, 8), 901);
            var chest = first.getList("block_entities", Tag.TAG_COMPOUND).getCompound(0);
            h.assertTrue(chest.getInt("x") == 40 && chest.getInt("z") == -24 && chest.getLong("LootTableSeed") == 123,
                    "Working coordinates move while the original loot seed survives");
            var tick = first.getList("block_ticks", Tag.TAG_COMPOUND).getCompound(0);
            h.assertTrue(tick.getInt("x") == 40 && tick.getInt("z") == -24 && tick.getInt("t") == 7,
                    "Native pending ticks retain their relative delay at the new address");
            h.assertTrue(first.getCompound("structures").getCompound("References")
                            .getLongArray("minecraft:mineshaft")[0] == ChunkPos.asLong(1, -1),
                    "Structure references move by the same chunk offset");
            chest.putLong("LootTableSeed", -1);
            h.assertTrue(second.getList("block_entities", Tag.TAG_COMPOUND).getCompound(0).getLong("LootTableSeed") == 123
                            && second.getList("block_entities", Tag.TAG_COMPOUND).getCompound(0).getInt("x") == 8,
                    "Mutating one loader payload cannot alter another copy or the template");
            var firstCart = sample.entities(0, destination).getCompound(0);
            var secondCart = sample.entities(0, destination).getCompound(0);
            h.assertTrue(!firstCart.getUUID("UUID").equals(secondCart.getUUID("UUID"))
                            && firstCart.getList("Pos", Tag.TAG_DOUBLE).getDouble(0) == 40.5,
                    "Each restored entity receives its own identity and translated position");
            h.assertTrue(java.util.Arrays.equals(sample.heights(0, 0), new int[]{0}),
                    "The lazy index respects the output slice rather than exposing the complete context");
        } finally { sample.release(); }
        h.succeed();
    }

    @GameTest(templateNamespace="kaleidoscope_witchcraft", template="empty")
    public static void mineSampleModMetadataSurvivesIndependentCopies(GameTestHelper h) {
        var level = MixedMine.forHost(h.getLevel().getServer().overworld());
        var sample = sample(h, payload(level));
        try {
            h.assertTrue(sample.compatible(level), "Mod metadata does not require a separate content whitelist");
            var first = sample.terrain(0, new BlockPos(40, 0, -24), 900);
            var second = sample.terrain(0, new BlockPos(8, 0, 8), 901);
            h.assertTrue(first.getInt("brewinandchewin:data_version") == 100
                            && first.getInt("mek_world_gen_version") == 7,
                    "Native mod data versions survive working-copy loading");
            UUID original = first.getCompound("example:network").getUUID("Network");
            first.getCompound("example:network").putUUID("Network", UUID.randomUUID());
            h.assertTrue(second.getCompound("example:network").getUUID("Network").equals(original)
                            && sample.terrain(0, new BlockPos(8, 0, 8), 902)
                                    .getCompound("example:network").getUUID("Network").equals(original),
                    "Opaque metadata is preserved and isolated, not interpreted as private coordinates");
        } finally { sample.release(); }
        h.succeed();
    }

    @GameTest(templateNamespace="kaleidoscope_witchcraft", template="empty")
    public static void mineSampleIncludesLightNeighborsWithoutPromotingThem(GameTestHelper h) {
        Set<Long> work = new java.util.HashSet<>();
        for (int x = -1; x <= 1; x++) for (int z = -1; z <= 1; z++) work.add(ChunkPos.asLong(x, z));
        var context = MineSample.loadingContext(work);
        h.assertTrue(context.size() == 25, "Nine FULL work chunks require a five-by-five saved loading context");
        for (int x = -2; x <= 2; x++) for (int z = -2; z <= 2; z++) {
            long key = ChunkPos.asLong(x, z);
            h.assertTrue(context.get(key) == (work.contains(key) ? ChunkStatus.FULL : ChunkStatus.INITIALIZE_LIGHT),
                    "The perimeter supplies the native loading stage rather than becoming additional FULL work");
        }
        h.succeed();
    }

    @GameTest(templateNamespace="kaleidoscope_witchcraft", template="empty")
    public static void mineSamplePartialContextPreservesNativeEntityOwnership(GameTestHelper h) {
        var level = MixedMine.forHost(h.getLevel().getServer().overworld());
        var payload = payload(level);
        payload.getCompound("Description").putInt("Radius", 32);
        var chunks = payload.getList("Chunks", Tag.TAG_COMPOUND);
        var full = chunks.getCompound(0);
        full.getCompound("Terrain").put("entities", full.getList("Entities", Tag.TAG_COMPOUND).copy());
        var support = full.copy();
        support.putLong("Offset", ChunkPos.asLong(1, 0));
        support.getCompound("Terrain").putString("Status", "minecraft:initialize_light");
        support.getCompound("Terrain").putInt("xPos", 1);
        support.put("Entities", new ListTag());
        chunks.add(support);
        var sample = sample(h, payload);
        try {
            h.assertTrue(sample.chunks().size() == 2 && sample.description.workChunks().size() == 1,
                    "Loading payloads and FULL work demand remain distinct after decoding");
            h.assertTrue(!sample.description.accepts(16, 0), "Support content cannot expand the recorded output mask");
            var destination = new BlockPos(40, 0, -24);
            h.assertTrue(!sample.terrain(0, destination, 900).contains("entities"),
                    "FULL content restores entities through EntityStorage only");
            var first = sample.terrain(ChunkPos.asLong(1, 0), destination, 900).getList("entities", Tag.TAG_COMPOUND);
            var second = sample.terrain(ChunkPos.asLong(1, 0), destination, 901).getList("entities", Tag.TAG_COMPOUND);
            h.assertTrue(first.size() == 1 && second.size() == 1
                            && first.getCompound(0).getList("Pos", Tag.TAG_DOUBLE).getDouble(0) == 40.5
                            && !first.getCompound(0).getUUID("UUID").equals(second.getCompound(0).getUUID("UUID")),
                    "PROTO content keeps native entities with translated positions and independent identities");
        } finally { sample.release(); }
        h.succeed();
    }

    @GameTest(templateNamespace="kaleidoscope_witchcraft", template="empty", timeoutTicks=200, batch="mine-native-generation")
    public static void mineUsesNativePostGenerationAndSkipsRestoredChunks(GameTestHelper h) {
        var level = MixedMine.forHost(h.getLevel().getServer().overworld());
        var ledger = dev.tc4port.thaumcraft.worldgen.RetroGenerationData.get(level);
        var options = new dev.tc4port.thaumcraft.worldgen.RetroGenerationSystem.Options(true, false, false, false, false, false, "witchcraft_gametest");
        var memory = MineSamplePool.get(level.getServer());
        try (var context = dev.senluo.witchcraft.worldgen.LocalGeneration.open(level, 20777L)) {
            var pos = new ChunkPos(context.origin(0));
            var chunk = level.getChunk(pos.x, pos.z);
            h.assertTrue(chunk.getPos().equals(pos) && !ledger.generated(options.key(), pos), "A fresh FULL chunk precedes this native pass");
            dev.tc4port.thaumcraft.worldgen.RetroGenerationSystem.enqueue(level, pos);
            h.assertTrue(!TemporaryGeneration.ready(level, pos.toLong()), "A queued native pass holds the snapshot");
            for (int i = 0; i < 64 && !TemporaryGeneration.ready(level, pos.toLong()); i++)
                dev.tc4port.thaumcraft.worldgen.RetroGenerationSystem.tickLevel(level, options);
            h.assertTrue(TemporaryGeneration.ready(level, pos.toLong()) && ledger.generated(options.key(), pos),
                    "The native pass marked this chunk before the mine can snapshot it");
        }

        var sample = sample(h, payload(level));
        var area = MineWorkAreas.get(level).area(20778L, () -> {}, new MineSupply(20778L).reads);
        try {
            var copy = area.restore(sample, memory);
            h.assertTrue(copy != null, "A restored working copy leases its registered payload");
            var pos = new ChunkPos(copy.origin());
            h.assertTrue(MineWorkAreas.contains(level, pos) && ledger.generated(options.key(), pos)
                    && !dev.tc4port.thaumcraft.worldgen.RetroGenerationSystem.enqueue(level, pos),
                    "Native post-generation skips restored content");
            copy.retire();
            h.assertTrue(!MineWorkAreas.contains(level, pos) && !ledger.generated(options.key(), pos),
                    "Retiring the final copy releases the restored-area marker");
        } finally {
            if (area.copy != null) area.copy.retire();
            area.close();
            sample.release();
        }
        h.succeed();
    }
}
