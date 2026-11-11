package dev.senluo.witchcraft.compat.thaumcraft;

import dev.senluo.witchcraft.ritual.WaterReceivers;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/** Optional public PetalApothecary contract. No dependency JAR, private field, fake bucket, or old loader code. */
final class ApothecaryWaterReceiver implements WaterReceivers.Adapter {
    private final Class<?> api;
    private final Method getFluid, setFluid;
    private final Object empty, water;
    private ApothecaryWaterReceiver(Class<?> api, Method getFluid, Method setFluid, Object empty, Object water) {
        this.api = api; this.getFluid = getFluid; this.setFluid = setFluid; this.empty = empty; this.water = water;
    }
    static void registerIfAvailable() {
        if (!net.neoforged.fml.ModList.get().isLoaded("botania")) return;
        try {
            Class<?> api = Class.forName("vazkii.botania.api.block.PetalApothecary");
            Class<?> state = Class.forName("vazkii.botania.api.block.PetalApothecary$State");
            Method get = api.getMethod("getFluid"), set = api.getMethod("setFluid", state);
            if (get.getReturnType() != state || set.getReturnType() != void.class)
                throw new NoSuchMethodException("PetalApothecary fluid contract changed");
            WaterReceivers.register(new ApothecaryWaterReceiver(api, get, set, state.getField("EMPTY").get(null), state.getField("WATER").get(null)));
        } catch (ReflectiveOperationException | LinkageError unsupported) {
            org.slf4j.LoggerFactory.getLogger(ApothecaryWaterReceiver.class).warn(
                    "PetalApothecary API unavailable; only tagged native fluid capabilities remain enabled: {}", unsupported.toString());
        }
    }
    public boolean matches(BlockState state) { return state.is(WaterReceivers.WHOLE_BUCKET); }
    public Optional<WaterReceivers.Fill> quote(ServerLevel level, BlockPos pos, int thirds) {
        var entity = level.getBlockEntity(pos);
        if (!api.isInstance(entity) || invoke(getFluid, entity) != empty) return Optional.empty();
        var before = level.getBlockState(pos);
        return Optional.of(new WaterReceivers.Fill() {
            public int cost() { return 3; }
            public int nextThirds() { return thirds; }
            public boolean ready() {
                return level.hasChunkAt(pos) && level.getBlockEntity(pos) == entity
                        && level.getBlockState(pos).equals(before) && invoke(getFluid, entity) == empty;
            }
            public WaterReceivers.Result commit() {
                if (!ready()) return WaterReceivers.Result.UNCHANGED;
                invoke(setFluid, entity, water);
                if (invoke(getFluid, entity) != water) return WaterReceivers.Result.UNCERTAIN;
                entity.setChanged();
                level.sendBlockUpdated(pos, before, level.getBlockState(pos), Block.UPDATE_ALL);
                return WaterReceivers.Result.FILLED;
            }
        });
    }
    private static Object invoke(Method method, Object target, Object... arguments) {
        try { return method.invoke(target, arguments); }
        catch (InvocationTargetException wrapped) {
            if (wrapped.getCause() instanceof Error fatal) throw fatal;
            if (wrapped.getCause() instanceof RuntimeException failure) throw failure;
            throw new IllegalStateException("PetalApothecary API failure", wrapped.getCause());
        } catch (ReflectiveOperationException changed) { throw new IllegalStateException("PetalApothecary API changed", changed); }
    }
}
