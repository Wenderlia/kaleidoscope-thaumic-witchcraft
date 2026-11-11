package dev.senluo.witchcraft.compat.botania;

import dev.senluo.witchcraft.time.WorkSources;
import net.minecraft.server.level.ServerLevel;
import vazkii.botania.api.state.BotaniaStateProperties;
import vazkii.botania.common.block.block_entity.flower.generating.EndoflameBlockEntity;

/** Fuel, mana delivery and cooldown stay in the flower's native update. */
public final class EndoflameWork {
    private EndoflameWork() {}

    public static void register() {
        WorkSources.registerProcess(EndoflameBlockEntity.class, new WorkSources.Process<>() {
            public double price(ServerLevel level, EndoflameBlockEntity flower) { return WorkSources.UPDATE_PRICE; }
            public boolean steamWork(ServerLevel level, EndoflameBlockEntity flower) {
                var state = flower.getBlockState();
                return state.getValue(BotaniaStateProperties.GENERATING)
                        || state.getValue(BotaniaStateProperties.ON_COOLDOWN);
            }
        });
    }
}
