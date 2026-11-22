package dev.senluo.witchcraft.compat.thaumcraft;

import com.mojang.authlib.GameProfile;
import dev.senluo.witchcraft.slash.*;
import dev.senluo.witchcraft.slash.drawing.*;
import dev.tc4port.thaumcraft.api.Registration;
import dev.tc4port.thaumcraft.api.event.ArcaneCraftingEvent;
import dev.tc4port.thaumcraft.block.entity.ArcaneWorktableBlockEntity;
import dev.tc4port.thaumcraft.menu.ArcaneWorktableMenu;
import dev.tc4port.thaumcraft.registry.TCBlocks;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.Items;
import dev.tc4port.thaumcraft.api.aspect.*;
import dev.tc4port.thaumcraft.api.focus.*;
import dev.tc4port.thaumcraft.api.focus.behavior.*;
import dev.tc4port.thaumcraft.api.wand.*;
import dev.tc4port.thaumcraft.api.wand.pipeline.*;
import java.util.*;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.ServerChatEvent;
import net.neoforged.neoforge.network.registration.NetworkRegistry;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@PrefixGameTestTemplate(false)
public final class SlashCastGameTests {
    private static final InteractionHand HAND = InteractionHand.MAIN_HAND;

    private static ResourceLocation key() { return SlashContent.id("test_" + UUID.randomUUID()); }

    private static final class Cast implements AutoCloseable {
        final GameTestHelper test;
        final ServerPlayer player;
        final ItemStack wand = WandApi.createFullyCharged(WandMaterialId.WOOD, WandMaterialId.IRON);
        SlashEntity blade;

        Cast(GameTestHelper h) {
            this(h, new FakePlayer(h.getLevel(), new GameProfile(UUID.randomUUID(), "slash-cast")));
        }

        Cast(GameTestHelper h, ServerPlayer player) {
            test = h;
            this.player = player;
            player.setGameMode(GameType.SURVIVAL);
            player.setPos(h.absolutePos(new BlockPos(1, 3, 1)).getCenter());
            player.setItemInHand(HAND, wand);
            install(new ItemStack(SlashContent.FOCUS.get()));
        }

        void install(ItemStack focus) {
            var result = FocusApi.install(player, HAND, focus, VisAction.EXECUTE);
            test.assertTrue(result == FocusInstallResult.INSTALLED, "Native focus install: " + result);
        }

        int balance(AspectId aspect) { return WandCharging.storage(wand).snapshot().currentCentivis().getOrDefault(aspect, 0); }

        Registration onSpawn(WandPipelineHandler<WandCastStages.Spawn, Boolean> handler) {
            return WandPipelineApi.aroundAll(key(), WandCastStages.SPAWN, -9000, (q, next) -> {
                if (q.player() != player || q.carrier() != wand) return next.proceed(q);
                blade = (SlashEntity) q.entity();
                return handler.handle(q, next);
            });
        }

        @Override public void close() {
            if (blade != null && !blade.isRemoved()) blade.discard();
            player.setItemInHand(HAND, ItemStack.EMPTY);
            FocusBehaviorApi.session(player, HAND, FocusServerSession.class);
        }
    }

    @GameTest(templateNamespace="kaleidoscope_witchcraft", template="empty", batch="slash-cast")
    public static void slashCommitsOnlyTheSimulatedFinalQuote(GameTestHelper h) {
        try (var cast = new Cast(h)) {
            int before = cast.balance(BuiltinAspects.ORDO);
            int[] quotes = {0}, simulations = {0}, commits = {0};
            try (var cost = WandPipelineApi.aroundCost(VisCostSource.FOCUS, key(), -9000, (q, next) ->
                        q.player() == cast.player ? Map.of(BuiltinAspects.ORDO, ++quotes[0] == 1 ? 11 : 19) : next.proceed(q));
                 var pay = WandPipelineApi.aroundAll(key(), WandCastStages.PAYMENT, -9000, (q, next) -> {
                     if (q.player() == cast.player) {
                         int amount = q.cost().amounts().get(BuiltinAspects.ORDO);
                         if (q.action() == VisAction.EXECUTE) {
                             commits[0]++;
                             h.assertTrue(amount == 19, "Debit uses the checked final bill");
                         } else {
                             simulations[0]++;
                             h.assertTrue(amount == (simulations[0] == 1 ? 11 : 19), "Both phases simulate their own price");
                         }
                     }
                     return next.proceed(q);
                 });
                 var spawn = cast.onSpawn((q, next) -> {
                     h.assertTrue(cast.blade.visibility() == SlashVisibility.HIDDEN, "Pending blade stays hidden");
                     cast.blade.visibility(SlashVisibility.CASTER_ONLY);
                     return next.proceed(q);
                 })) {
                h.assertTrue(SlashFocus.cast(cast.player), "Normal cast succeeds");
                h.assertTrue(quotes[0] == 2 && simulations[0] == 2 && commits[0] == 1, "Two quotes, two simulations, one debit");
                h.assertTrue(before - cast.balance(BuiltinAspects.ORDO) == 19 && cast.blade.visibility() == SlashVisibility.CASTER_ONLY,
                        "Actual charge and the listener's audience survive admission");
            }
        }
        h.succeed();
    }

    @GameTest(templateNamespace="kaleidoscope_witchcraft", template="empty", batch="slash-cast")
    public static void freeDrawnSlashCanBeAdmittedByAnAddon(GameTestHelper h) {
        try (var cast = new Cast(h)) {
            var rawPrices = new ArrayList<Map<AspectId,Integer>>();
            try (var cost = WandPipelineApi.aroundCost(VisCostSource.FOCUS, key(), -9000, (q, next) -> {
                    if (q.player() != cast.player) return next.proceed(q);
                    rawPrices.add(Map.copyOf(q.raw()));
                    return Map.of();
                 }); var spawn = cast.onSpawn((q, next) -> h.getLevel().addFreshEntity(cast.blade))) {
                var trace = new StrokeTrace();
                for (int n = 1; n <= 12; n++) trace.sample(new Vec3(Math.sin(n * .16), 0, Math.cos(n * .16)).scale(StrokeSphere.RADIUS));
                int before = cast.balance(BuiltinAspects.PERDITIO);
                h.assertTrue(SlashFocus.castDrawing(cast.player, HAND, StrokeSphere.facing(cast.player.getLookAngle()), trace), "Free drawn cast succeeds");
                h.assertTrue(rawPrices.size() == 4 && rawPrices.get(0).equals(rawPrices.get(2))
                        && rawPrices.get(1).equals(rawPrices.get(3)) && !rawPrices.get(0).equals(rawPrices.get(1)), "Two base and two scaled native quotes");
                h.assertTrue(before == cast.balance(BuiltinAspects.PERDITIO) && cast.blade.hasRadialGeometry()
                        && cast.blade.saveWithoutId(new CompoundTag()).getBoolean("Paid"), "Admitted free blade flies with the drawn geometry");
            }
        }
        h.succeed();
    }

    @GameTest(templateNamespace="kaleidoscope_witchcraft", template="empty", batch="slash-cast")
    public static void slashRejectsMissingCanceledOrMovedAdmission(GameTestHelper h) {
        for (int i = 0; i < 3; i++) {
            final int mode = i;
            try (var cast = new Cast(h); var spawn = cast.onSpawn((q, next) -> {
                if (mode == 0) return true;
                boolean added = next.proceed(q);
                if (mode == 2) cast.blade.setPos(cast.blade.position().add(.5, 0, 0));
                return added;
            })) {
                Consumer<EntityJoinLevelEvent> veto = e -> { if (mode == 1 && e.getEntity() == cast.blade) e.setCanceled(true); };
                NeoForge.EVENT_BUS.addListener(veto);
                try {
                    int before = cast.balance(BuiltinAspects.PERDITIO);
                    h.assertTrue(!SlashFocus.cast(cast.player) && cast.blade != null && cast.blade.isRemoved()
                            && before == cast.balance(BuiltinAspects.PERDITIO), "Rejected admission leaves no blade or bill, case " + mode);
                } finally { NeoForge.EVENT_BUS.unregister(veto); }
            }
        }
        h.succeed();
    }

    @GameTest(templateNamespace="kaleidoscope_witchcraft", template="empty", batch="slash-cast")
    public static void slashRevalidatesThePhysicalWandAndOpeningCore(GameTestHelper h) {
        try (var cast = new Cast(h); var move = WandPipelineApi.aroundAll(key(), WandCastStages.EXECUTE, -9000, (q, next) -> {
            if (q.player() == cast.player) {
                var focus = new ItemStack(SlashContent.FOCUS.get());
                focus.set(SlashContent.MEMORY.get(), new FocusMemory(List.of(), Map.of(key(), "changed")));
                cast.install(focus);
            }
            return next.proceed(q);
        }); var spawn = cast.onSpawn((q,next) -> next.proceed(q))) {
            int before = cast.balance(BuiltinAspects.PERDITIO);
            h.assertTrue(!SlashFocus.cast(cast.player) && cast.blade == null && before == cast.balance(BuiltinAspects.PERDITIO),
                    "EXECUTE replacing the core state in the same wand cannot cast");
        }
        try (var cast = new Cast(h); var spawn = cast.onSpawn((q,next) -> next.proceed(q));
             var pay = WandPipelineApi.aroundAll(key(), WandCastStages.PAYMENT, -9000, (q, next) -> {
                 var receipt = next.proceed(q);
                 if (q.player() == cast.player && q.action() == VisAction.SIMULATE && cast.blade != null)
                     cast.player.setItemInHand(HAND, cast.wand.copy());
                 return receipt;
             })) {
            int before = cast.balance(BuiltinAspects.PERDITIO);
            h.assertTrue(!SlashFocus.cast(cast.player) && cast.blade != null && cast.blade.isRemoved()
                    && before == cast.balance(BuiltinAspects.PERDITIO), "Final simulation swapping an equal stack cancels before debit");
        }
        h.succeed();
    }

    @GameTest(templateNamespace="kaleidoscope_witchcraft", template="empty", batch="slash-cast")
    public static void slashRetiresWhenPaymentRemovesTheAdmittedEntity(GameTestHelper h) {
        var world = h.getLevel();
        var cookie = CommonListenerCookie.createInitial(new GameProfile(UUID.randomUUID(), "slash-chant"), false);
        var player = new ServerPlayer(world.getServer(), world, cookie.gameProfile(), cookie.clientInformation());
        var connection = new Connection(PacketFlow.SERVERBOUND);
        new io.netty.channel.embedded.EmbeddedChannel(connection);
        NetworkRegistry.configureMockConnection(connection);
        var players = world.getServer().getPlayerList();
        players.placeNewPlayer(connection, player, cookie);
        try (var cast = new Cast(h, player); var spawn = cast.onSpawn((q, next) -> next.proceed(q))) {
            SlashCriticalIntent.chat(new ServerChatEvent(player, "解", Component.literal("解")));
            h.assertTrue(SlashCriticalIntent.available(player, 60), "Chant reached the player");
            int before = cast.balance(BuiltinAspects.PERDITIO);
            try (var pay = WandPipelineApi.aroundAll(key(), WandCastStages.PAYMENT, -9000, (q, next) -> {
                var receipt = next.proceed(q);
                if (q.player() == player && q.action() == VisAction.EXECUTE && receipt.consumed()) {
                    h.assertTrue(!SlashCriticalIntent.available(player, 60), "Chant is reserved during payment");
                    cast.blade.discard();
                }
                return receipt;
            })) {
                h.assertTrue(SlashFocus.cast(player) && cast.blade.isRemoved() && before > cast.balance(BuiltinAspects.PERDITIO)
                        && SlashFormation.preview(player).index() == 0, "Input settled once, with no later blade or formation");
            }
            h.assertTrue(SlashCriticalIntent.available(player, 60), "Unreleased blade returns the chant");
            h.assertTrue(SlashFocus.cast(player) && cast.blade.visual() == SlashVisual.BLACK_RED
                    && !SlashCriticalIntent.available(player, 60), "Next paid release consumes the chant");
        } finally {
            players.remove(player);
        }
        h.succeed();
    }

    @GameTest(templateNamespace="kaleidoscope_witchcraft", template="empty", batch="slash-cast")
    public static void slashScopesItsPriceAndRoundsTheLongStrokeOnce(GameTestHelper h) {
        var raw = Map.of(BuiltinAspects.ORDO, 300, BuiltinAspects.PERDITIO, 200);
        var sources = List.of(VisCostSource.ARCANE_WORKTABLE, VisCostSource.FORMATION, VisCostSource.REPAIR,
                VisCostSource.RUNIC_SHIELD, SlashContent.id("test_device"));
        try (var cast = new Cast(h)) {
            var original = new HashMap<ResourceLocation, Map<AspectId,Integer>>();
            for (var s : sources) original.put(s, WandVis.cost(cast.wand, cast.player, raw, s));
            var focus = new ItemStack(SlashContent.FOCUS.get());
            focus.set(SlashContent.MEMORY.get(), new FocusMemory(List.of(SlashEfficacy.ID.value()), Map.of()));
            cast.install(focus);
            for (var s : sources) h.assertTrue(original.get(s).equals(WandVis.cost(cast.wand, cast.player, raw, s)),
                    "Efficacy only changes focus casting; checked " + s);
            try (var unitRate = WandPipelineApi.aroundCost(VisCostSource.FOCUS, key(), 9000, (q,next) ->
                    q.player() == cast.player ? q.raw() : next.proceed(q))) {
                var result = WandVis.cost(cast.wand, cast.player, raw, VisCostSource.FOCUS);
                h.assertTrue(result.equals(Map.of(BuiltinAspects.ORDO, 356, BuiltinAspects.PERDITIO, 237)),
                        "Scaled raw centivis receive efficacy and discount exactly once: " + result);
            }
        }
        h.succeed();
    }

    @GameTest(templateNamespace="kaleidoscope_witchcraft", template="empty", batch="slash-cast")
    public static void slashFreezesItsWeaponBeforeTheFinalPaymentSimulation(GameTestHelper h) {
        var sharpness = h.getLevel().registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.ENCHANTMENT)
                .getOrThrow(net.minecraft.world.item.enchantment.Enchantments.SHARPNESS);
        try (var cast = new Cast(h); var spawn = cast.onSpawn((q, next) -> {
            var focus = new ItemStack(SlashContent.FOCUS.get());
            focus.enchant(sharpness, 2);
            cast.install(focus);
            return next.proceed(q);
        }); var pay = WandPipelineApi.aroundAll(key(), WandCastStages.PAYMENT, -9000, (q, next) -> {
            if (q.player() == cast.player && q.action() == VisAction.SIMULATE && cast.blade != null) {
                var focus = new ItemStack(SlashContent.FOCUS.get());
                focus.enchant(sharpness, 4);
                cast.install(focus);
            }
            return next.proceed(q);
        })) {
            h.assertTrue(SlashFocus.cast(cast.player) && cast.blade.getWeaponItem().getEnchantmentLevel(sharpness) == 2
                    && FocusApi.installedStack(cast.wand).getEnchantmentLevel(sharpness) == 4,
                    "Join edits reach the blade, final-simulation edits stay on the live focus");
        }
        h.succeed();
    }

    @GameTest(templateNamespace="kaleidoscope_witchcraft", template="empty", batch="slash-cast")
    public static void drawingCancelAndRepeatedReleaseOnlyCommitOnce(GameTestHelper h) {
        try (var cast = new Cast(h); var spawn = cast.onSpawn((q, next) -> next.proceed(q))) {
            var p = cast.player;
            SlashDrawing.Status[] issued = {null};
            p.connection = new net.minecraft.server.network.ServerGamePacketListenerImpl(p.server,
                    new net.minecraft.network.Connection(net.minecraft.network.protocol.PacketFlow.SERVERBOUND), p,
                    net.minecraft.server.network.CommonListenerCookie.createInitial(p.getGameProfile(), false)) {
                @Override public void send(net.minecraft.network.protocol.Packet<?> packet) {
                    if (packet instanceof net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket frame
                            && frame.payload() instanceof SlashDrawing.Status status && status.open()) issued[0] = status;
                }
            };
            int before = cast.balance(BuiltinAspects.PERDITIO);
            h.assertTrue(SlashDrawing.begin(p, HAND) && issued[0] != null, "Server issues an identified drawing session");
            var canceled = issued[0];
            new SlashDrawing.Control(canceled.id(), canceled.dimension(), SlashDrawing.CANCEL).handle(p);
            new SlashDrawing.Control(canceled.id(), canceled.dimension(), SlashDrawing.RELEASE).handle(p);
            h.assertTrue(cast.blade == null && cast.balance(BuiltinAspects.PERDITIO) == before, "Canceled drawing never charges");
            h.assertTrue(SlashDrawing.begin(p, HAND) && issued[0].id() != canceled.id(), "New draft has its own identity");
            p.setYRot(p.getYRot() + 35);
            p.startUsingItem(HAND);
            SlashDrawing.tick(new net.neoforged.neoforge.event.tick.PlayerTickEvent.Post(p));
            SlashDrawing.stopped(p, HAND);
            var release = new SlashDrawing.Control(issued[0].id(), issued[0].dimension(), SlashDrawing.RELEASE);
            release.handle(p);
            int after = cast.balance(BuiltinAspects.PERDITIO);
            var blade = cast.blade;
            release.handle(p);
            h.assertTrue(blade != null && cast.blade == blade && blade.hasRadialGeometry() && after < before
                    && cast.balance(BuiltinAspects.PERDITIO) == after && !SlashDrawing.active(p), "Release consumes the draft once");
        }
        h.succeed();
    }

    private static ItemStack gridItem(net.minecraft.world.item.Item item, int count) {
        var input = new ItemStack(item, count);
        input.set(net.minecraft.core.component.DataComponents.CUSTOM_MODEL_DATA, new net.minecraft.world.item.component.CustomModelData(20777));
        return input;
    }

    @GameTest(templateNamespace="kaleidoscope_witchcraft", template="empty", batch="arcane-worktable")
    public static void worktablePrioritizesArcaneAndChargesOnlyCraftableShiftResults(GameTestHelper h) {
        try (var cast = new Cast(h)) {
            int before = cast.balance(BuiltinAspects.ORDO), price = before / 2 + 1;
            var pos = h.absolutePos(new BlockPos(1, 2, 1));
            h.getLevel().setBlockAndUpdate(pos, TCBlocks.ARCANE_WORKTABLE.get().defaultBlockState());
            var table = (ArcaneWorktableBlockEntity) h.getLevel().getBlockEntity(pos);
            var menu = new ArcaneWorktableMenu(1, cast.player.getInventory(), table);
            cast.player.containerMenu = menu;
            cast.player.setItemInHand(HAND, ItemStack.EMPTY);
            int[] crafts = {0};
            Consumer<ArcaneCraftingEvent.Crafted> receipt = event -> {
                if (event.player().orElse(null) != cast.player) return;
                crafts[0]++;
                h.assertTrue(event.recipeId().equals(SlashContent.id("test_worktable"))
                        && event.paidCentivis().equals(Map.of(BuiltinAspects.ORDO, price)), "Actual receipt matches the arcane result");
            };
            NeoForge.EVENT_BUS.addListener(receipt);
            try (var cost = WandPipelineApi.aroundCost(VisCostSource.ARCANE_WORKTABLE, key(), -9000, (q,next) ->
                    q.player() == cast.player ? Map.of(BuiltinAspects.ORDO, price) : next.proceed(q))) {
                table.setItem(0, gridItem(Items.OAK_LOG, 3));
                h.assertTrue(menu.getSlot(ArcaneWorktableMenu.RESULT_MENU_SLOT).getItem().is(Items.OAK_PLANKS), "Without a wand, marked logs still match vanilla planks");
                table.setItem(ArcaneWorktableBlockEntity.WAND_SLOT, cast.wand);
                h.assertTrue(menu.displayState().preview().is(Items.AMETHYST_SHARD), "With the wand the matching arcane recipe is preferred");
                for (int i = 0; i < cast.player.getInventory().items.size(); i++) cast.player.getInventory().setItem(i, new ItemStack(Items.COBBLESTONE,64));
                menu.clicked(ArcaneWorktableMenu.RESULT_MENU_SLOT, 0, ClickType.QUICK_MOVE, cast.player);
                h.assertTrue(cast.balance(BuiltinAspects.ORDO) == before && crafts[0] == 0 && table.getItem(0).getCount() == 3,
                        "Full inventory leaves ingredients and wand intact");
                cast.player.getInventory().setItem(5, ItemStack.EMPTY);
                menu.clicked(ArcaneWorktableMenu.RESULT_MENU_SLOT, 0, ClickType.QUICK_MOVE, cast.player);
                h.assertTrue(before - cast.balance(BuiltinAspects.ORDO) == price && crafts[0] == 1
                        && cast.player.getInventory().countItem(Items.AMETHYST_SHARD) == 1 && table.getItem(0).getCount() == 2,
                        "One affordable shift result, one debit, two uncooked inputs");
            } finally { NeoForge.EVENT_BUS.unregister(receipt); menu.removed(cast.player); cast.player.containerMenu = cast.player.inventoryMenu; }
        }
        h.succeed();
    }

    @GameTest(templateNamespace="kaleidoscope_witchcraft", template="empty", batch="arcane-worktable")
    public static void worktableHonorsCanceledAndFreeQuotesWithNativeRemainders(GameTestHelper h) {
        for (int i = 0; i < 2; i++) {
            boolean cancel = i == 0;
            try (var cast = new Cast(h)) {
                var pos = h.absolutePos(new BlockPos(1+i, 2, 1));
                h.getLevel().setBlockAndUpdate(pos, TCBlocks.ARCANE_WORKTABLE.get().defaultBlockState());
                var table = (ArcaneWorktableBlockEntity) h.getLevel().getBlockEntity(pos);
                Consumer<ArcaneCraftingEvent.Quote> listener = event -> {
                    if (!event.recipeId().equals(SlashContent.id("test_worktable"))) return;
                    if (cancel) event.setCanceled(true); else event.setCentivisCost(Map.of());
                };
                NeoForge.EVENT_BUS.addListener(listener);
                var menu = new ArcaneWorktableMenu(1, cast.player.getInventory(), table);
                cast.player.containerMenu = menu;
                cast.player.setItemInHand(HAND, ItemStack.EMPTY);
                int before = cast.balance(BuiltinAspects.ORDO);
                try {
                    table.setItem(ArcaneWorktableBlockEntity.WAND_SLOT, cast.wand);
                    table.setItem(0, gridItem(Items.MILK_BUCKET, 1));
                    menu.clicked(ArcaneWorktableMenu.RESULT_MENU_SLOT, 0, ClickType.PICKUP, cast.player);
                    h.assertTrue(cast.balance(BuiltinAspects.ORDO) == before, "No debit on cancel or a free quote");
                    h.assertTrue(cancel ? menu.getCarried().isEmpty() && table.getItem(0).is(Items.MILK_BUCKET)
                            : menu.getCarried().is(Items.AMETHYST_SHARD) && table.getItem(0).is(Items.BUCKET),
                            "Only a valid free craft consumes the milk and returns its bucket");
                } finally { NeoForge.EVENT_BUS.unregister(listener); menu.removed(cast.player); cast.player.containerMenu = cast.player.inventoryMenu; }
            }
        }
        h.succeed();
    }
}
