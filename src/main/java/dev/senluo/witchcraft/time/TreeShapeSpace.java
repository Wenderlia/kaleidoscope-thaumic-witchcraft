package dev.senluo.witchcraft.time;

import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;

/** The authoritative, portable tree shape. World damage never edits this space. */
public final class TreeShapeSpace {
    public static final int TREE_RADIUS=8, GIANT_RADIUS=16, MAX_PARTS=33*33*29, MAX_ENTRIES=MAX_PARTS-1;
    public static final int MAX_PRUNED=MAX_PARTS;
    static final List<BlockPos> NEIGHBORS = neighbours();
    private static final TagKey<Block> PARTS = TagKey.create(Registries.BLOCK,
            ResourceLocation.fromNamespaceAndPath("kaleidoscope_witchcraft", "tree_shape_parts"));
    private static final Map<Integer,Map<BlockPos,BlockState>> TEMPLATES = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<Long,BlockState> cells = new LinkedHashMap<>();
    private final Map<Long,BlockState> cellView = Collections.unmodifiableMap(cells);
    // Player grafts, plus v6 entries whose missing provenance must not permit destructive inference.
    private final Set<Long> grafts = new HashSet<>();
    private final Set<Long> excluded = new HashSet<>(), removed = new HashSet<>();
    private final Map<Long,dev.senluo.witchcraft.ritual.ChalkMarks.Mark> carvings = new LinkedHashMap<>();
    private final Map<Long,CompoundTag> signs = new LinkedHashMap<>();
    private boolean initialized, splitDirty;
    private int templatePhase = -2;
    private long revision;
    private ArrayDeque<BlockPos> cutQueue;
    private Set<Long> cutSeen, cutDisconnected;
    private Iterator<Long> cutCandidates;
    private Candidate preview;
    record Candidate(long revision, int phase, Map<Long,BlockState> cells) {}

    private static List<BlockPos> neighbours() {
        List<BlockPos> result=new ArrayList<>(26);
        for(int x=-1;x<=1;x++)for(int y=-1;y<=1;y++)for(int z=-1;z<=1;z++)
            if(x!=0||y!=0||z!=0)result.add(new BlockPos(x,y,z));
        return List.copyOf(result);
    }
    public static boolean material(BlockState state) {
        return state.is(PARTS) && (!state.hasBlockEntity() || state.is(TemporalContent.FRUIT.get()) || TreeSignMemory.supports(state));
    }
    public static boolean contains(Tissue tissue, BlockPos p) { return contains(tissue.giant||tissue.giantGrowing,p); }
    static boolean contains(boolean giant, BlockPos p) {
        return !p.equals(BlockPos.ZERO) && Math.abs(p.getX())<=(giant?GIANT_RADIUS:TREE_RADIUS)
                && Math.abs(p.getZ())<=(giant?GIANT_RADIUS:TREE_RADIUS)
                && p.getY()>=(giant?-4:0) && p.getY()<=(giant?24:6);
    }
    public boolean initialized(){return initialized;}
    public void initialize(){if(!initialized){initialized=true;revision++;}}
    public long revision(){return revision;}
    public boolean has(long p){return cells.containsKey(p);}
    public boolean pruned(long p){return excluded.contains(p);}
    public boolean pendingSplit(){return splitDirty || !removed.isEmpty();}
    public boolean coreOnly(){return initialized && cells.isEmpty();}
    public Map<Long,BlockState> cells(){return cellView;}
    public Map<Long,dev.senluo.witchcraft.ritual.ChalkMarks.Mark> carvings(){return Collections.unmodifiableMap(carvings);}
    CompoundTag sign(long p) { return signs.get(p); }
    boolean rememberSign(long p, CompoundTag data) {
        BlockState state = cells.get(p);
        if (state == null || !TreeSignMemory.supports(state) || data.equals(signs.get(p))) return false;
        signs.put(p, data.copy()); return true;
    }

    static int phase(Tissue tissue) {
        if (tissue.giant) return 4;
        return tissue.giantGrowing ? Math.min(3,tissue.giantTicks/(TreeBalance.GIANT_TICKS/4)) : -1;
    }
    static Map<BlockPos,BlockState> template(Tissue tissue, int phase) {
        int key = phase < 0 ? -1 : phase * 8 + tissue.treeFacing;
        return TEMPLATES.computeIfAbsent(key, unused -> {
            if (phase < 0) return Collections.unmodifiableMap(GiantTreeShape.small(BlockPos.ZERO));
            Map<BlockPos,BlockState> result = new LinkedHashMap<>();
            GiantTreeShape.layout(tissue.treeFacing).forEach((p,v) -> {
                if (v.phase() <= Math.min(3,phase)) result.put(p,v.state());
            });
            return Collections.unmodifiableMap(result);
        });
    }

    boolean atPhase(int phase) { return initialized && templatePhase == phase; }
    public boolean grafted(long p) { return grafts.contains(p); }

    /** Pure, revision-cached merge shared by transition preflight and stage submission. */
    Candidate preview(Map<BlockPos,BlockState> template, int phase) {
        if (preview != null && preview.revision() == revision && preview.phase() == phase) return preview;
        Map<Long,BlockState> next = new LinkedHashMap<>(cells);
        Set<Long> newPositions = new HashSet<>();
        if (!coreOnly()) for (var entry : template.entrySet()) {
            long p = entry.getKey().asLong();
            if (excluded.contains(p) || grafts.contains(p)) continue;
            BlockState state = entry.getValue(), old = cells.get(p);
            // Nurturing channel colour is a material transformation, not a new natural template.
            if (old != null && old.is(TreeWood.LOGS) && state.is(TreeWood.LOGS))
                state = FruitWoods.regrowth(old).setValue(RotatedPillarBlock.AXIS, state.getValue(RotatedPillarBlock.AXIS));
            if (phase == 4 && state.is(TemporalContent.TIME_LEAVES.get()))
                state = state.setValue(TreeLeavesBlock.GREAT_TREE, true);
            next.put(p, state);
            if (!cells.containsKey(p)) newPositions.add(p);
        }
        // Only additions are filtered. An artificial shell never deletes existing interior memory.
        if (!grafts.isEmpty() && !newPositions.isEmpty()) skipEnclosedAdditions(next, newPositions, phase >= 0);
        // New natural cells must join the remaining abstract shape; a cut never seeds a floating island.
        Set<Long> connected = new HashSet<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        queue.add(BlockPos.ZERO);
        while (!queue.isEmpty()) {
            BlockPos p = queue.removeFirst();
            for (BlockPos d : NEIGHBORS) {
                BlockPos n = p.offset(d);
                if (next.containsKey(n.asLong()) && connected.add(n.asLong())) queue.addLast(n);
            }
        }
        next.keySet().removeIf(p -> !cells.containsKey(p) && !connected.contains(p));
        preview = new Candidate(revision, phase, Collections.unmodifiableMap(next));
        return preview;
    }

    /** Six-face void reachability is distinct from the shape's 26-neighbour material connection. */
    private void skipEnclosedAdditions(Map<Long,BlockState> next, Set<Long> additions, boolean giant) {
        giant |= grafts.stream().anyMatch(p -> !contains(false,BlockPos.of(p)));
        int radius = (giant ? GIANT_RADIUS : TREE_RADIUS) + 1;
        int bottom = giant ? -5 : -1, top = giant ? 25 : 7;
        int width = radius * 2 + 1, plane = width * width, volume = plane * (top - bottom + 1);
        boolean[] closed = new boolean[volume], reached = new boolean[volume];
        for (long key : grafts) {
            BlockPos p = BlockPos.of(key);
            closed[(p.getY()-bottom)*plane + (p.getZ()+radius)*width + p.getX()+radius] = true;
        }
        closed[-bottom*plane + radius*width + radius] = true; // core
        int[] queue = new int[volume]; int read = 0, end = 1;
        reached[0] = true;
        while (read < end) {
            int at = queue[read++], x = at % width, z = at / width % width;
            for (int step : FACE_STEPS) {
                int n = switch (step) { case 0 -> x > 0 ? at-1 : -1; case 1 -> x+1 < width ? at+1 : -1;
                    case 2 -> z > 0 ? at-width : -1; case 3 -> z+1 < width ? at+width : -1;
                    case 4 -> at-plane; default -> at+plane; };
                if (n >= 0 && n < volume && !closed[n] && !reached[n]) {
                    reached[n] = true; queue[end++] = n;
                }
            }
        }
        for (long key : additions) {
            BlockPos p = BlockPos.of(key);
            int index = (p.getY()-bottom)*plane + (p.getZ()+radius)*width + p.getX()+radius;
            if (!reached[index]) next.remove(key);
        }
    }
    private static final int[] FACE_STEPS = {0,1,2,3,4,5};

    boolean commit(Candidate candidate) {
        if (candidate.revision() != revision || splitDirty) return false;
        cells.clear(); cells.putAll(candidate.cells());
        signs.keySet().removeIf(p -> !cells.containsKey(p) || !TreeSignMemory.supports(cells.get(p)));
        initialized = true; templatePhase = candidate.phase(); revision++; preview = null;
        return true;
    }

    /** Explicit graft edit. Ordinary observation must not overwrite an existing natural target. */
    public boolean remember(Tissue tissue,BlockPos p,BlockState state) {
        if(!contains(tissue,p)||!material(state)||excluded.contains(p.asLong())
                || !cells.containsKey(p.asLong())&&cells.size()>=MAX_ENTRIES)return false;
        // A fruit is a structural route only. Its live entity still owns all investment and growth.
        BlockState next=normalized(state);
        BlockState old=cells.put(p.asLong(),next);
        if (!TreeSignMemory.supports(next) || old == null || !old.is(next.getBlock())) signs.remove(p.asLong());
        boolean sourceChanged = grafts.add(p.asLong());
        if(next.equals(old) && !sourceChanged)return false;
        revision++;return true;
    }
    boolean observe(Tissue tissue, BlockPos p, BlockState state) {
        return !has(p.asLong()) && remember(tissue, p, state);
    }
    boolean transform(Tissue tissue, BlockPos p, BlockState old, BlockState state) {
        long key = p.asLong();
        if (!has(key) || !material(state) || !cells.get(key).equals(normalized(old))) return false;
        BlockState next = normalized(state);
        if (next.equals(cells.get(key))) return false;
        cells.put(key, next); revision++; return true;
    }
    private static BlockState normalized(BlockState state) {
        BlockState next = state.is(TemporalContent.FRUIT.get())
                ? TemporalContent.FRUIT.get().defaultBlockState() : FruitWoods.regrowth(state);
        return next.is(TemporalContent.TIME_LEAVES.get())
                ? next.setValue(LeavesBlock.DISTANCE, LeavesBlock.DECAY_DISTANCE) : YearsBuildingEdits.normalized(next);
    }
    static boolean sameForm(BlockState actual, BlockState remembered) {
        return remembered != null && normalized(actual).equals(normalized(remembered));
    }
    public boolean rememberLeaf(Tissue tissue,BlockPos p,BlockState s){return remember(tissue,p,s);}
    public boolean prune(Tissue tissue,BlockPos p) {
        long key=p.asLong();
        if(p.equals(BlockPos.ZERO)||!cells.containsKey(key)||excluded.size()>=MAX_PRUNED)return false;
        exclude(key);splitDirty=true;cutQueue=null;cutCandidates=null;return true;
    }
    private void exclude(long p) {
        cells.remove(p);grafts.remove(p);carvings.remove(p);signs.remove(p);excluded.add(p);removed.add(p);revision++;
    }
    /** One traversal for a batch of cuts. It reads only the abstract space, including unloaded cells. */
    void settleCuts() {
        advanceCuts(Integer.MAX_VALUE);
    }
    /** A work unit visits one cell (at most 26 neighbours), or classifies one saved cell. */
    int advanceCuts(int budget) {
        if(!splitDirty)return 0;
        if(cutQueue==null){cutQueue=new ArrayDeque<>();cutQueue.add(BlockPos.ZERO);cutSeen=new HashSet<>();cutDisconnected=new HashSet<>();}
        int used=0;
        while(!cutQueue.isEmpty() && used<budget) {
            BlockPos p=cutQueue.removeFirst();used++;
            for(BlockPos d:NEIGHBORS) {
                BlockPos n=p.offset(d);long key=n.asLong();
                if(cells.containsKey(key)&&cutSeen.add(key))cutQueue.addLast(n);
            }
        }
        if(!cutQueue.isEmpty())return used;
        if(cutCandidates==null)cutCandidates=cells.keySet().iterator();
        while(cutCandidates.hasNext() && used<budget){long p=cutCandidates.next();used++;if(!cutSeen.contains(p))cutDisconnected.add(p);}
        if(!cutCandidates.hasNext()) {
            // Publish the component removal only after its entire decision has been calculated.
            for(long p:cutDisconnected)exclude(p);
            splitDirty=false;cutQueue=null;cutCandidates=null;cutSeen=null;cutDisconnected=null;
        }
        return used;
    }
    boolean calculatingCuts(){return splitDirty;}
    Set<Long> takeRemoved(){settleCuts();Set<Long> result=new HashSet<>(removed);removed.clear();return result;}
    public boolean reattach(Tissue tissue,BlockPos p) {
        if(!contains(tissue,p)||!excluded.remove(p.asLong()))return false;
        revision++;return true;
    }
    public boolean rememberCarving(Tissue tissue,BlockPos p,dev.senluo.witchcraft.ritual.ChalkMarks.Mark mark) {
        if(!mark.supported()||mark.medium()!=1||excluded.contains(p.asLong())
                || !p.equals(BlockPos.ZERO)&&!contains(tissue,p)||carvings.size()>=MAX_PARTS)return false;
        return !mark.equals(carvings.put(p.asLong(),mark));
    }
    void rotate(int turns) {
        settleCuts();
        Rotation r=switch(Math.floorMod(turns,4)){case 1->Rotation.CLOCKWISE_90;case 2->Rotation.CLOCKWISE_180;case 3->Rotation.COUNTERCLOCKWISE_90;default->Rotation.NONE;};
        Map<Long,BlockState> next=new LinkedHashMap<>();
        cells.forEach((p,s)->next.put(TreeOrientation.rotate(BlockPos.of(p),turns).asLong(),s.rotate(r)));
        cells.clear();cells.putAll(next);
        Map<Long,CompoundTag> rotatedSigns = new LinkedHashMap<>();
        signs.forEach((p,data) -> rotatedSigns.put(TreeOrientation.rotate(BlockPos.of(p),turns).asLong(),data));
        signs.clear(); signs.putAll(rotatedSigns);
        Set<Long> rotatedGrafts = new HashSet<>();
        for (long p : grafts) rotatedGrafts.add(TreeOrientation.rotate(BlockPos.of(p),turns).asLong());
        grafts.clear(); grafts.addAll(rotatedGrafts);
        Set<Long> exclusions=new HashSet<>();for(long p:excluded)exclusions.add(TreeOrientation.rotate(BlockPos.of(p),turns).asLong());
        excluded.clear();excluded.addAll(exclusions);
        Map<Long,dev.senluo.witchcraft.ritual.ChalkMarks.Mark> marks=new LinkedHashMap<>();
        carvings.forEach((p,m)->marks.put(TreeOrientation.rotate(BlockPos.of(p),turns).asLong(),
                new dev.senluo.witchcraft.ritual.ChalkMarks.Mark(m.symbol(),(m.rotation()+turns)&3,m.medium(),m.design())));
        carvings.clear();carvings.putAll(marks);removed.clear();revision++;
    }
    public CompoundTag save() {
        settleCuts();
        CompoundTag tag=new CompoundTag();tag.putInt("Version",8);tag.putBoolean("Initialized",initialized);tag.putInt("Phase",templatePhase);
        // Encode repeated block states once; each cell uses a position and palette index/source bit.
        Map<BlockState,Integer> indices = new LinkedHashMap<>(); ListTag palette = new ListTag();
        long[] positions = new long[cells.size()]; int[] states = new int[cells.size()]; int index = 0;
        for (var entry : cells.entrySet()) {
            int material = indices.computeIfAbsent(entry.getValue(), state -> {
                palette.add(NbtUtils.writeBlockState(state)); return palette.size()-1;
            });
            positions[index] = entry.getKey();
            states[index++] = material*2 + (grafts.contains(entry.getKey()) ? 1 : 0);
        }
        tag.put("Palette",palette); tag.putLongArray("Positions",positions); tag.putIntArray("States",states);
        tag.putLongArray("Pruned",excluded.stream().mapToLong(Long::longValue).toArray());
        ListTag marks=new ListTag();carvings.forEach((p,m)->{CompoundTag e=new CompoundTag();e.putLong("Position",p);e.putInt("Rotation",m.rotation());e.putInt("Design",m.design());marks.add(e);});
        tag.put("Carvings",marks);
        ListTag signList = new ListTag();
        signs.forEach((p,data) -> { CompoundTag entry = new CompoundTag(); entry.putLong("Position",p); entry.put("Data",data.copy()); signList.add(entry); });
        tag.put("Signs",signList); return tag;
    }
    static boolean readable(CompoundTag tag) {
        if(tag.isEmpty()||tag.getInt("Version")==6)return LegacyTreeShape.readable(tag);
        if((tag.getInt("Version")!=7 && tag.getInt("Version")!=8)||!tag.contains("Initialized",Tag.TAG_BYTE)||!tag.contains("Phase",Tag.TAG_INT)
                ||tag.getInt("Phase") < -2||tag.getInt("Phase")>4
                ||!tag.contains("Positions",Tag.TAG_LONG_ARRAY)||!tag.contains("States",Tag.TAG_INT_ARRAY)
                ||!(tag.get("Palette") instanceof ListTag palette)||palette.size()>MAX_ENTRIES
                ||!palette.isEmpty()&&palette.getElementType()!=Tag.TAG_COMPOUND
                ||!tag.contains("Pruned",Tag.TAG_LONG_ARRAY)||tag.getLongArray("Pruned").length>MAX_PRUNED)return false;
        long[] positions = tag.getLongArray("Positions"); int[] states = tag.getIntArray("States");
        if (positions.length != states.length || positions.length > MAX_ENTRIES) return false;
        boolean[] signMaterials = new boolean[palette.size()]; int paletteIndex=0;
        for(var value:palette){CompoundTag stateTag=(CompoundTag)value;
            var id=ResourceLocation.tryParse(stateTag.getString("Name"));
            if(id==null||!BuiltInRegistries.BLOCK.containsKey(id))return false;
            BlockState decoded=NbtUtils.readBlockState(BuiltInRegistries.BLOCK.asLookup(),stateTag);
            if(!material(decoded))return false;
            signMaterials[paletteIndex++]=TreeSignMemory.supports(decoded);
        }
        Set<Long> seen=new HashSet<>();
        for (int i=0; i<positions.length; i++) {
            if (!contains(true,BlockPos.of(positions[i])) || !seen.add(positions[i])
                    || states[i] < 0 || states[i]/2 >= palette.size()) return false;
        }
        for(long p:tag.getLongArray("Pruned"))if(!contains(true,BlockPos.of(p))||!seen.add(p))return false;
        if (tag.getInt("Version") == 8) {
            if (!(tag.get("Signs") instanceof ListTag signList) || signList.size() > positions.length
                    || !signList.isEmpty() && signList.getElementType() != Tag.TAG_COMPOUND) return false;
            Set<Long> signSeen = new HashSet<>();
            Set<Long> signPositions = new HashSet<>();
            for (int i=0; i<positions.length; i++) if (signMaterials[states[i]/2]) signPositions.add(positions[i]);
            for (var value : signList) {
                CompoundTag entry=(CompoundTag)value;
                if (!entry.contains("Position",Tag.TAG_LONG) || !signSeen.add(entry.getLong("Position"))
                        || !signPositions.contains(entry.getLong("Position")) || !entry.contains("Data",Tag.TAG_COMPOUND)) return false;
                CompoundTag data=entry.getCompound("Data");
                if (!data.contains("front_text",Tag.TAG_COMPOUND) || !data.contains("back_text",Tag.TAG_COMPOUND)
                        || !data.contains("is_waxed",Tag.TAG_BYTE) || data.getAllKeys().size()!=3) return false;
            }
        }
        if(!(tag.get("Carvings") instanceof ListTag marks)||marks.size()>MAX_PARTS||!marks.isEmpty()&&marks.getElementType()!=Tag.TAG_COMPOUND)return false;
        seen.clear();
        for(var value:marks){CompoundTag e=(CompoundTag)value;long p=e.getLong("Position");
            if(!e.contains("Position",Tag.TAG_LONG)||!seen.add(p)||p!=0&&!contains(true,BlockPos.of(p))
                ||!e.contains("Rotation",Tag.TAG_INT)||!e.contains("Design",Tag.TAG_INT)||e.getInt("Rotation")<0||e.getInt("Rotation")>3
                ||e.getInt("Design")<0||e.getInt("Design")>=dev.senluo.witchcraft.ritual.ChalkPattern.CARVING_ATLAS_DESIGNS)return false;}
        return true;
    }
    public void load(CompoundTag tag,Tissue tissue) {
        cells.clear();grafts.clear();excluded.clear();carvings.clear();signs.clear();removed.clear();preview=null;initialized=false;splitDirty=false;cutQueue=null;cutCandidates=null;cutSeen=null;cutDisconnected=null;templatePhase=-2;revision++;
        if(!tissue.tree||tag.isEmpty()||!readable(tag))return;
        if(tag.getInt("Version")==6){
            var old=LegacyTreeShape.read(tag,tissue);
            cells.putAll(old.cells());
            excluded.addAll(old.excluded());
            carvings.putAll(old.carvings());
            Map<BlockPos,BlockState> baseline = template(tissue, phase(tissue));
            // v6 cannot distinguish a hand-placed copy from an identical natural cell.
            // Preserve every explicitly recorded form; only formerly implicit template cells stay natural.
            grafts.addAll(cells.keySet());
            if (old.initialized() && cells.isEmpty() && baseline.keySet().stream().allMatch(p -> excluded.contains(p.asLong()))) {
                initialized = true; templatePhase = phase(tissue);
            }
            // The former record omits natural leaves: the first registration seeds that template once.
            return;
        }
        List<BlockState> palette = new ArrayList<>();
        for (var value : tag.getList("Palette",Tag.TAG_COMPOUND))
            palette.add(normalized(NbtUtils.readBlockState(BuiltInRegistries.BLOCK.asLookup(),(CompoundTag)value)));
        long[] positions=tag.getLongArray("Positions"); int[] states=tag.getIntArray("States");
        for (int i=0; i<positions.length; i++) {
            cells.put(positions[i],palette.get(states[i]/2)); if ((states[i]&1)!=0) grafts.add(positions[i]);
        }
        for(long p:tag.getLongArray("Pruned"))excluded.add(p);
        for(var value:tag.getList("Carvings",Tag.TAG_COMPOUND)){CompoundTag e=(CompoundTag)value;carvings.put(e.getLong("Position"),new dev.senluo.witchcraft.ritual.ChalkMarks.Mark(0,e.getInt("Rotation"),1,e.getInt("Design")));}
        for(var value:tag.getList("Signs",Tag.TAG_COMPOUND)){CompoundTag e=(CompoundTag)value;signs.put(e.getLong("Position"),e.getCompound("Data").copy());}
        initialized=tag.getBoolean("Initialized");templatePhase=tag.getInt("Phase");
    }
    // Material projections retained for existing inspection callers; runtime planning uses cells().
    public Map<Long,Direction.Axis> entries(){Map<Long,Direction.Axis> result=new LinkedHashMap<>();cells.forEach((p,s)->{if(s.is(TreeWood.LOGS))result.put(p,s.getValue(RotatedPillarBlock.AXIS));});return Collections.unmodifiableMap(result);}
    public record Leaf(Direction.Axis axis,boolean persistent){public BlockState state(boolean giant){return TemporalContent.TIME_LEAVES.get().naturalState().setValue(RotatedPillarBlock.AXIS,axis).setValue(LeavesBlock.PERSISTENT,persistent).setValue(TreeLeavesBlock.GREAT_TREE,giant);}}
    public Map<Long,Leaf> leaves(){Map<Long,Leaf> result=new LinkedHashMap<>();cells.forEach((p,s)->{if(s.is(TemporalContent.TIME_LEAVES.get()))result.put(p,new Leaf(s.getValue(RotatedPillarBlock.AXIS),s.getValue(LeavesBlock.PERSISTENT)));});return Collections.unmodifiableMap(result);}
    public BlockState state(long p,Direction.Axis axis){return cells.getOrDefault(p,state(axis));}
    public static BlockState state(Direction.Axis axis){return TemporalContent.TIME_LOG.get().defaultBlockState().setValue(RotatedPillarBlock.AXIS,axis);}
}
