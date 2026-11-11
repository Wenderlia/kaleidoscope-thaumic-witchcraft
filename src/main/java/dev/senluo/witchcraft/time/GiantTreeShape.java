package dev.senluo.witchcraft.time;

import java.util.*;
import dev.senluo.witchcraft.time.shape.TreeShapeGenerator;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.state.BlockState;

/** A bounded whole-block plan. Local +Z is behind the exposed core. */
public final class GiantTreeShape {
    public record Part(BlockState state, int phase, boolean required) {}
    private static final Map<Integer,Map<BlockPos,Part>> CACHE = new java.util.concurrent.ConcurrentHashMap<>();
    private static final Map<Integer,List<BlockPos>> REQUIRED = new java.util.concurrent.ConcurrentHashMap<>();
    private static final TreeShapeGenerator.Plan SMALL = TreeShapeGenerator.generate(TreeShapeGenerator.Form.YEARS_TREE,0);
    private GiantTreeShape() {}

    public static Map<BlockPos,BlockState> small(BlockPos origin) {
        Map<BlockPos,BlockState> result = new LinkedHashMap<>();
        SMALL.parts().forEach((p, part) -> result.put(origin.offset(p.x(),p.y(),p.z()),
                part.material() == TreeShapeGenerator.Material.WOOD ? TemporalContent.TIME_LOG.get().defaultBlockState()
                        : TemporalContent.TIME_LEAVES.get().naturalState()));
        return result;
    }

    public static Map<BlockPos,Part> layout(int facing) { return CACHE.computeIfAbsent(facing,GiantTreeShape::create); }
    public static List<BlockPos> required(int facing) {
        return REQUIRED.computeIfAbsent(facing,key -> layout(key).entrySet().stream()
                .filter(e -> e.getValue().required()).map(Map.Entry::getKey).toList());
    }
    private static Map<BlockPos,Part> create(int facing) {
        Map<BlockPos,Part> local=new LinkedHashMap<>();
        BlockState log=TemporalContent.TIME_LOG.get().defaultBlockState();
        BlockState leaves=TemporalContent.TIME_LEAVES.get().naturalState();
        TreeShapeGenerator.generate(TreeShapeGenerator.Form.YEARS_GIANT,0).parts().forEach((p,part)->{
            BlockState state=part.material()==TreeShapeGenerator.Material.WOOD
                    ? log.setValue(RotatedPillarBlock.AXIS,Direction.Axis.valueOf(part.axis().name())) : leaves;
            local.put(new BlockPos(p.x(),p.y(),p.z()),new Part(state,part.phase(),part.required()));
        });
        Map<BlockPos,Part> rotated=new LinkedHashMap<>();
        for (var e:local.entrySet()) {
            BlockPos p=TreeOrientation.relative(e.getKey(),facing); Part part=e.getValue(); BlockState state=part.state();
            if (state.hasProperty(RotatedPillarBlock.AXIS) && (facing==4 || facing==5)) {
                Direction.Axis a=state.getValue(RotatedPillarBlock.AXIS);
                if (a!=Direction.Axis.Y) state=state.setValue(RotatedPillarBlock.AXIS,a==Direction.Axis.X?Direction.Axis.Z:Direction.Axis.X);
            }
            rotated.put(p,new Part(state,part.phase(),part.required()));
        }
        // The original small crown is horizontally symmetric, including its fruit supports.
        small(BlockPos.ZERO).forEach((p,s)->rotated.putIfAbsent(p,new Part(s,0,false)));
        return Collections.unmodifiableMap(rotated);
    }

}
