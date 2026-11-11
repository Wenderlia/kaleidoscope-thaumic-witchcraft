package dev.senluo.witchcraft.compat.tacz;

import dev.senluo.witchcraft.accessory.ExplosionBarrier;
import dev.senluo.witchcraft.accessory.ProjectileBarrier;
import dev.senluo.witchcraft.accessory.PropagationField;
import dev.senluo.witchcraft.accessory.TurtleField;
import java.util.function.Function;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/** TaCZ owns simulation and damage; this adapter only bounds its synchronous propagation queries. */
public final class TaczBarrier {
    private record Trace(Level level, Entity bullet, Explosion explosion, PropagationField field) {}
    private static final ThreadLocal<Trace> TRACE = new ThreadLocal<>();

    public static void tick(Entity bullet, Runnable original) {
        scoped(new Trace(bullet.level(), bullet, null,
                TurtleField.propagation(bullet.level(), ProjectileBarrier.owner(bullet))), original);
    }

    public static void explode(Level level, Explosion explosion, Runnable original) {
        scoped(new Trace(level, null, explosion, TurtleField.propagation(level)),
                () -> ExplosionBarrier.explodeWithNativeExposure(level, explosion, original));
    }

    private static void scoped(Trace value, Runnable original) {
        Trace previous = TRACE.get();
        if (value == null) TRACE.remove(); else TRACE.set(value);
        try { original.run(); }
        finally { if (previous == null) TRACE.remove(); else TRACE.set(previous); }
    }

    /** Arbitrary hit listeners must not inherit the enclosing bullet's query identity. */
    public static void impact(Entity bullet, Runnable original) {
        ProjectileBarrier.nativeImpact(bullet);
        scoped(null, original);
    }

    public static boolean blocks(Entity bullet, Vec3 from, HitResult result) {
        if (result instanceof BoundaryHit) return true;
        if (result.getType() == HitResult.Type.MISS) return false;
        // Revalidate actual endpoints immediately before callbacks; no victim-centre approximation.
        return TurtleField.propagation(bullet.level(), ProjectileBarrier.owner(bullet))
                .blocks(from, result.getLocation());
    }

    public static BlockHitResult clip(Level level, ClipContext context, Function<ClipContext, BlockHitResult> original) {
        Trace trace = TRACE.get();
        if (trace == null || trace.level != level || trace.field.isEmpty()) return original.apply(context);
        // Only the host's own flight/exposure rays belong to this scope.
        Vec3 origin = trace.bullet != null ? trace.bullet.position() : trace.explosion.center();
        if (!origin.equals(context.getFrom())) return original.apply(context);
        PropagationField.Hit contact = trace.field.hit(context.getFrom(), context.getTo());
        if (contact == null) return original.apply(context);
        BlockHitResult boundary = new BoundaryHit(contact.point(), contact.direction());
        if (contact.point().equals(context.getFrom())) return boundary;
        ClipContext prefix = new ClipContext(context.getFrom(), contact.point(), ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE, CollisionContext.empty()) {
            @Override public VoxelShape getBlockShape(BlockState state, BlockGetter world, BlockPos pos) {
                return context.getBlockShape(state, world, pos);
            }
            @Override public VoxelShape getFluidShape(FluidState state, BlockGetter world, BlockPos pos) {
                return context.getFluidShape(state, world, pos);
            }
        };
        BlockHitResult nativeHit = original.apply(prefix);
        return nativeHit.getType() != HitResult.Type.MISS
                && context.getFrom().distanceToSqr(nativeHit.getLocation())
                < context.getFrom().distanceToSqr(contact.point()) ? nativeHit : boundary;
    }

    /** TaCZ samples living hitboxes itself; non-living targets use a single native impulse point. */
    public static boolean blocksUnsampledExplosion(Entity target, Explosion explosion) {
        Trace trace = TRACE.get();
        if (trace == null || trace.explosion != explosion || target instanceof LivingEntity) return false;
        Vec3 point = new Vec3(target.getX(), target instanceof PrimedTnt ? target.getY() : target.getEyeY(), target.getZ());
        return trace.field.blocks(explosion.center(), point);
    }

    /** Internal stop token for TaCZ's shared scan/tracer protocol, NEVER a committable block impact. */
    private static final class BoundaryHit extends BlockHitResult {
        BoundaryHit(Vec3 point, Vec3 direction) {
            super(point, Direction.getNearest(-direction.x, -direction.y, -direction.z), BlockPos.containing(point), false);
        }
    }

    private TaczBarrier() {}
}
