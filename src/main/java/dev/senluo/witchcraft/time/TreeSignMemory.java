package dev.senluo.witchcraft.time;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.SignBlock;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/** Only native sign text/style/wax is portable; editors, positions and unrelated entity data are not. */
public final class TreeSignMemory {
    private TreeSignMemory() {}
    static boolean supports(BlockState state) {
        return state.getBlock() instanceof SignBlock && state.getBlock() instanceof YearsBuildingEdits.Part;
    }
    static CompoundTag snapshot(SignBlockEntity sign) {
        CompoundTag raw = sign.saveCustomOnly(sign.getLevel().registryAccess()), data = new CompoundTag();
        for (String key : new String[]{"front_text", "back_text", "is_waxed"})
            if (raw.contains(key)) data.put(key, raw.get(key).copy());
        return data;
    }
    public static void changed(SignBlockEntity sign) {
        if (sign.getLevel() instanceof ServerLevel level && supports(sign.getBlockState()))
            TemporalWorld.get(level).trees.rememberSign(sign);
    }
    public static void beforeReplacement(ServerLevel level, BlockPos pos, BlockState old, BlockState replacement) {
        if (old.getBlock() != replacement.getBlock() && supports(old)
                && level.getBlockEntity(pos) instanceof SignBlockEntity sign) changed(sign);
    }
    static void restore(ServerLevel level, BlockPos pos, CompoundTag data) {
        if (data != null && level.getBlockEntity(pos) instanceof SignBlockEntity sign) {
            sign.loadCustomOnly(data.copy(), level.registryAccess());
            sign.setChanged();
            level.sendBlockUpdated(pos, sign.getBlockState(), sign.getBlockState(), 3);
        }
    }
}
