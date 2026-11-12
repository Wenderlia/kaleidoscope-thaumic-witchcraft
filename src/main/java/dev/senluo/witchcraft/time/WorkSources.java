package dev.senluo.witchcraft.time;

import dev.senluo.witchcraft.mixin.time.FurnaceAccess;
import dev.senluo.witchcraft.mixin.time.BrewingAccess;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.*;
import net.minecraft.world.item.crafting.*;

/** One adapter owns work qualification and native-ticker semantics. Resource capabilities alone do not imply these semantics. */
public final class WorkSources {
    public static final double UPDATE_PRICE = SourceIncomeRates.WORK;
    @FunctionalInterface public interface Rule<T extends BlockEntity> { double price(ServerLevel level, T entity); }
    public interface Process<T extends BlockEntity> extends Rule<T> {
        /** Only adapters that surround native calls need an always-present local route. */
        default boolean nativeContext() { return false; }
        /** A host's native callback context; effects and payment remain outside the common ticker. */
        default void tick(ServerLevel level, T entity, Runnable nativeTick) { nativeTick.run(); }
        /** Read-only offer for consecutive, already-budgeted steps. Null keeps the native path. */
        default Runnable batch(ServerLevel level, T entity, int steps) { return null; }
        /** Return true only after preparing the native ticker to preserve its paid progress. */
        default boolean prepareSuppressed(T entity) { return false; }
        /** Fixed registration capability. Never infer it from fuel, inventory or other live state. */
        default boolean supportsSteam() { return false; }
        /** Current work admission; evaluated again after native callbacks, never cached as type support. */
        default boolean steamWork(ServerLevel level, T entity) { return supportsSteam(); }
        /** Paid native work, independent of this process's income quote or steam eligibility. */
        default boolean outputWork(ServerLevel level, T entity) { return price(level, entity) > 0; }
    }
    private static final Map<BlockEntityType<?>, Process<BlockEntity>> TYPE_RULES = new java.util.IdentityHashMap<>();
    public static void registerProcess(BlockEntityType<?> type, Process<BlockEntity> process) {
        TYPE_RULES.put(type, process);
    }
    private static final Map<Class<?>, Process<BlockEntity>> RULES = new LinkedHashMap<>();
    private static final Map<Class<?>, Process<BlockEntity>> CLASS_RULES = new java.util.HashMap<>();
    static {
        registerProcess(LivingEntity.class, new Process<>() {
            public double price(ServerLevel level, LivingEntity entity) { return 0; }
            public Runnable batch(ServerLevel level, LivingEntity entity, int steps) {
                return () -> TemporalWorld.get(level).development.pulse(entity, steps);
            }
        });
        registerProcess(FruitEntity.class, new Process<>() {
            public double price(ServerLevel level, FruitEntity entity) { return 0; }
            public Runnable batch(ServerLevel level, FruitEntity entity, int steps) {
                return () -> FruitEntity.tick(entity, steps);
            }
        });
        registerProcess(AbstractFurnaceBlockEntity.class, new Process<>() {
            public boolean supportsSteam() { return true; }
            public double price(ServerLevel level, AbstractFurnaceBlockEntity entity) { return furnace(level, entity); }
            public boolean prepareSuppressed(AbstractFurnaceBlockEntity entity) {
                FurnaceAccess a = (FurnaceAccess) entity;
                a.senluo$cookingProgress(a.senluo$cookingProgress() - 1);
                a.senluo$litTime(a.senluo$litTime() + 1);
                return true;
            }
            public boolean steamWork(ServerLevel level, AbstractFurnaceBlockEntity entity) {
                return ((FurnaceAccess) entity).senluo$litTime() > 0;
            }
        });
        registerProcess(BrewingStandBlockEntity.class, new Process<>() {
            public boolean supportsSteam() { return true; }
            public double price(ServerLevel level, BrewingStandBlockEntity entity) { return brewing(level, entity); }
            public boolean steamWork(ServerLevel level, BrewingStandBlockEntity entity) {
                return ((BrewingAccess) entity).senluo$brewTime() > 0;
            }
            public boolean prepareSuppressed(BrewingStandBlockEntity entity) {
                BrewingAccess a = (BrewingAccess) entity;
                if (a.senluo$brewTime() > 0) a.senluo$brewTime(a.senluo$brewTime() + 1);
                return true;
            }
        });
        registerProcess(CampfireBlockEntity.class, new Process<>() {
            public boolean supportsSteam() { return true; }
            public double price(ServerLevel level, CampfireBlockEntity entity) { return campfire(level, entity); }
            public boolean prepareSuppressed(CampfireBlockEntity entity) {
                int[] progress = ((dev.senluo.witchcraft.mixin.time.CampfireAccess) entity).senluo$progress();
                for (int i = 0; i < progress.length; i++) if (!entity.getItems().get(i).isEmpty()) progress[i]--;
                return true;
            }
            public boolean steamWork(ServerLevel level, CampfireBlockEntity entity) { return campfire(level, entity) > 0; }
        });
    }
    private WorkSources() {}
    public static <T extends BlockEntity> void register(Class<T> type, Rule<T> rule) {
        registerProcess(type, new Process<>() {
            public double price(ServerLevel level, T entity) { return rule.price(level, entity); }
        });
    }
    public static <T extends BlockEntity> void registerProcess(Class<T> type, Process<T> process) {
        java.util.Objects.requireNonNull(type);
        java.util.Objects.requireNonNull(process);
        CLASS_RULES.clear();
        RULES.put(type, new Process<>() {
            public boolean nativeContext() { return process.nativeContext(); }
            public void tick(ServerLevel level, BlockEntity entity, Runnable nativeTick) { process.tick(level, type.cast(entity), nativeTick); }
            public Runnable batch(ServerLevel level, BlockEntity entity, int steps) { return process.batch(level, type.cast(entity), steps); }
            public double price(ServerLevel level, BlockEntity entity) { return process.price(level, type.cast(entity)); }
            public boolean prepareSuppressed(BlockEntity entity) { return process.prepareSuppressed(type.cast(entity)); }
            public boolean supportsSteam() { return process.supportsSteam(); }
            public boolean steamWork(ServerLevel level, BlockEntity entity) { return process.steamWork(level, type.cast(entity)); }
            public boolean outputWork(ServerLevel level, BlockEntity entity) { return process.outputWork(level, type.cast(entity)); }
        });
    }
    private static Process<BlockEntity> process(BlockEntity entity) {
        if (entity == null || entity.isRemoved()) return null;
        var exact = TYPE_RULES.get(entity.getType());
        if (exact != null) return exact;
        Class<?> actual = entity.getClass();
        if (CLASS_RULES.containsKey(actual)) return CLASS_RULES.get(actual);
        Class<?> selected = null;
        Process<BlockEntity> result = null;
        for (var entry : RULES.entrySet()) if (entry.getKey().isInstance(entity)
                && (selected == null || selected.isAssignableFrom(entry.getKey()))) {
            selected = entry.getKey();
            result = entry.getValue();
        }
        CLASS_RULES.put(actual, result);
        return result;
    }
    public static double price(ServerLevel level, BlockEntity entity) {
        if (entity == null || entity.isRemoved()) return 0;
        if (entity instanceof LivingEntity || entity instanceof RootEntity || entity instanceof FruitEntity) return 0;
        var process = process(entity);
        double price = process == null ? UPDATE_PRICE : process.price(level, entity);
        return Double.isFinite(price) && price > 0 ? price : 0;
    }
    public static boolean known(BlockEntity entity) { return process(entity) != null; }
    public static boolean nativeContext(BlockEntity entity) {
        var process = process(entity);
        return process != null && process.nativeContext();
    }
    public static Runnable batch(ServerLevel level, BlockEntity entity, int steps) {
        var process = process(entity);
        return process == null ? null : process.batch(level, entity, steps);
    }
    public static void tick(ServerLevel level, BlockEntity entity, Runnable nativeTick) {
        var process = process(entity);
        if (process == null) nativeTick.run(); else process.tick(level, entity, nativeTick);
    }
    public static boolean outputWork(ServerLevel level, BlockEntity entity) {
        var process = process(entity);
        return process == null || process.outputWork(level, entity);
    }

    /** Pause a paid work step and its continuous fuel cost; the native ticker still owns completion. */
    public static boolean prepareSuppressedProgress(BlockEntity entity) {
        var process = process(entity);
        return process != null && process.prepareSuppressed(entity);
    }
    public static boolean supportsSteam(BlockEntity entity) {
        var process = process(entity);
        return process != null && process.supportsSteam();
    }
    public static boolean steamWork(ServerLevel level, BlockEntity entity) {
        var process = process(entity);
        return process != null && process.supportsSteam() && process.steamWork(level, entity);
    }
    private static double furnace(ServerLevel level, AbstractFurnaceBlockEntity entity) {
        FurnaceAccess access = (FurnaceAccess) entity;
        ItemStack input = entity.getItem(0), output = entity.getItem(2);
        if (input.isEmpty() || access.senluo$litTime() <= 0) return 0;
        var recipe = level.getRecipeManager().getRecipeFor(access.senluo$recipeType(), new SingleRecipeInput(input), level);
        if (recipe.isEmpty()) return 0;
        ItemStack result = recipe.get().value().assemble(new SingleRecipeInput(input), level.registryAccess());
        if (result.isEmpty() || !output.isEmpty() && !ItemStack.isSameItemSameComponents(result, output)) return 0;
        int max = Math.min(entity.getMaxStackSize(), result.getMaxStackSize());
        return output.getCount() + result.getCount() <= max ? UPDATE_PRICE : 0;
    }
    private static double brewing(ServerLevel level, BrewingStandBlockEntity entity) {
        BrewingAccess access = (BrewingAccess) entity;
        ItemStack ingredient = entity.getItem(3);
        if (ingredient.isEmpty() || !level.potionBrewing().isIngredient(ingredient) || access.senluo$brewTime()<=0) return 0;
        if (access.senluo$brewTime() > 0 && ingredient.getItem() != access.senluo$ingredient()) return 0;
        for (int i = 0; i < 3; i++) if (!entity.getItem(i).isEmpty() && level.potionBrewing().hasMix(entity.getItem(i), ingredient)) {
            return access.senluo$brewTime() > 0 || access.senluo$fuel() > 0
                    || entity.getItem(4).is(net.minecraft.world.item.Items.BLAZE_POWDER) ? UPDATE_PRICE : 0;
        }
        return 0;
    }
    private static double campfire(ServerLevel level, CampfireBlockEntity entity) {
        if (!entity.getBlockState().getValue(net.minecraft.world.level.block.CampfireBlock.LIT)) return 0;
        for (ItemStack item : entity.getItems()) if (!item.isEmpty()) {
            var input = new SingleRecipeInput(item);
            var recipe = level.getRecipeManager().getRecipeFor(RecipeType.CAMPFIRE_COOKING, input, level);
            if (recipe.isPresent()) {
                ItemStack output = recipe.get().value().assemble(input, level.registryAccess());
                if (!output.isEmpty() && output.isItemEnabled(level.enabledFeatures())) return UPDATE_PRICE;
            }
        }
        return 0;
    }
}
