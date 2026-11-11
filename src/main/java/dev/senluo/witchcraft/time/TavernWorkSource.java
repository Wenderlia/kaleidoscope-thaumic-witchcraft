package dev.senluo.witchcraft.time;

import com.github.ysbbbbbb.kaleidoscopetavern.blockentity.brew.BarrelBlockEntity;
import com.github.ysbbbbbb.kaleidoscopetavern.blockentity.brew.TapBlockEntity;

/** Loaded only when the pinned optional Tavern dependency is present. */
public final class TavernWorkSource {
    private TavernWorkSource() {}
    public static void register() {
        // The valve ticker emits drip particles and closes an empty tap; it performs no production.
        // Bottling runs on a separate scheduled block tick. Absorbing this ticker only breaks feedback.
        WorkSources.register(TapBlockEntity.class, (level, tap) -> 0);
        WorkSources.registerProcess(BarrelBlockEntity.class, new WorkSources.Process<>() {
            public double price(net.minecraft.server.level.ServerLevel level, BarrelBlockEntity barrel) {
                if (barrel.isOpen() || !barrel.isBrewing() || barrel.isMaxBrewLevel()) return 0;
                long offset = barrel.hashCode() % 97 + 97;
                return (level.getGameTime() + offset) % 97 == 0 ? 97*WorkSources.UPDATE_PRICE : 0;
            }
            public boolean outputWork(net.minecraft.server.level.ServerLevel level, BarrelBlockEntity barrel) {
                return !barrel.isOpen() && barrel.isBrewing() && !barrel.isMaxBrewLevel();
            }
        });
    }
}
