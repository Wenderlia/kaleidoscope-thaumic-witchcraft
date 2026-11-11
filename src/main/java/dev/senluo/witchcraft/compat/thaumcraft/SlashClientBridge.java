package dev.senluo.witchcraft.compat.thaumcraft;

/** Client-side focus selection and launch presentation. */
public final class SlashClientBridge {
    private SlashClientBridge() {}
    public static boolean selectingFocus() { return dev.tc4port.thaumcraft.api.client.ThaumcraftClientRenderApi.isSelectingFocus(); }

    /** Capture once per emitted blade; an already flying attack must not follow later hand/camera motion. */
    public static net.minecraft.world.phys.Vec3 captureLaunchOffset(dev.senluo.witchcraft.slash.SlashEntity slash) {
        if (slash.handBlend(1) <= 0 || slash.ownerId() == null) return net.minecraft.world.phys.Vec3.ZERO;
        var player = slash.level().getPlayerByUUID(slash.ownerId());
        if (player == null || !SlashFocus.installed(player.getMainHandItem())) return net.minecraft.world.phys.Vec3.ZERO;
        var tip = dev.tc4port.thaumcraft.api.client.ThaumcraftClientRenderApi.resolveFocusCastOrigin(player,
                net.minecraft.world.InteractionHand.MAIN_HAND, java.util.Optional.of(SlashFocus.ID));
        var origin = slash.launchOrigin();
        if (origin.isEmpty()) return net.minecraft.world.phys.Vec3.ZERO;
        var offset = tip.subtract(origin.get());
        if (!dev.senluo.witchcraft.motion.MotionPath.finite(offset) || offset.lengthSqr() > 4) return net.minecraft.world.phys.Vec3.ZERO;
        return offset;
    }

    /** The captured correction fades with displayed travel; retain obstruction clipping near the hand. */
    public static net.minecraft.world.phys.Vec3 launchOffset(dev.senluo.witchcraft.slash.SlashEntity slash, float partial,
                                                            net.minecraft.world.phys.Vec3 offset) {
        double blend = slash.handBlend(partial);
        if (blend <= 0 || offset.lengthSqr() < 1.0E-10) return net.minecraft.world.phys.Vec3.ZERO;
        var from = slash.getPosition(partial);
        var end = from.add(offset.scale(blend));
        var hit = slash.level().clip(new net.minecraft.world.level.ClipContext(from, end,
                net.minecraft.world.level.ClipContext.Block.COLLIDER, net.minecraft.world.level.ClipContext.Fluid.NONE, slash));
        return (hit.getType() == net.minecraft.world.phys.HitResult.Type.MISS ? end : hit.getLocation()).subtract(from);
    }
}
