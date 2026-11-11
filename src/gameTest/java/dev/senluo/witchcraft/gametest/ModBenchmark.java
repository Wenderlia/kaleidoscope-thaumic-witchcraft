package dev.senluo.witchcraft.gametest;

import com.google.gson.GsonBuilder;
import com.sun.management.ThreadMXBean;
import dev.senluo.witchcraft.ritual.*;
import dev.senluo.witchcraft.time.*;
import java.lang.management.ManagementFactory;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import jdk.jfr.Configuration;
import jdk.jfr.Recording;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.*;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Explicit opt-in, isolated server workload. Never included in the release JAR. */
@PrefixGameTestTemplate(false)
public final class ModBenchmark {
    private static final int WARMUP = 200, SAMPLES = 400;
    private static volatile long blackhole;
    @GameTest(templateNamespace="kaleidoscope_witchcraft", template="empty", timeoutTicks=12000)
    public static void benchmark(GameTestHelper helper) throws Exception { new Run(helper).start(); }

    private record Case(String name, String type, int count, int doublings) {}
    private static final class Run {
        final GameTestHelper helper;
        final ServerLevel level;
        final BlockPos origin;
        final ThreadMXBean bean = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        final List<Case> cases = new ArrayList<>();
        final List<BlockPos> placed = new ArrayList<>();
        final List<AbstractFurnaceBlockEntity> furnaces = new ArrayList<>();
        final List<LivingEntity> roots = new ArrayList<>();
        final List<Map<String,Object>> rows = new ArrayList<>(), kernels = new ArrayList<>();
        final Consumer<ServerTickEvent.Pre> before = this::before;
        final Consumer<ServerTickEvent.Post> after = this::after;
        final double[] times = new double[SAMPLES], cpu = new double[SAMPLES], bytes = new double[SAMPLES];
        final UUID field = new UUID(19, 23);
        Recording recording;
        int phase, tick;
        long started, cpuStart, allocationStart, gcStart, outputStart, rootStart, balanceStart, sampleStart;
        boolean armed, finished;
        Run(GameTestHelper helper) {
            this.helper=helper; level=helper.getLevel();
            var anchor=helper.absolutePos(new BlockPos(0,8,0));
            origin=new BlockPos((anchor.getX()>>4)<<4,anchor.getY(),(anchor.getZ()>>4)<<4);
            cases.add(new Case("baseline_start", "empty", 0, 0));
            var workloads=new ArrayList<Case>();
            for (int n : new int[]{16,64,256}) workloads.add(new Case("furnace_"+n,"furnace",n,0));
            workloads.add(new Case("steam_64_2x","furnace",64,1));
            workloads.add(new Case("steam_64_8x","furnace",64,3));
            for (int n : new int[]{16,64,256}) workloads.add(new Case("roots_"+n,"root",n,0));
            if (Boolean.getBoolean("witchcraft.benchmark.reverse")) Collections.reverse(workloads);
            cases.addAll(workloads); cases.add(new Case("baseline_end","empty",0,0));
        }
        void start() throws Exception {
            level.getGameRules().getRule(GameRules.RULE_DOMOBSPAWNING).set(false,level.getServer());
            level.getGameRules().getRule(GameRules.RULE_RANDOMTICKING).set(3,level.getServer());
            level.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(false,level.getServer());
            bean.setThreadAllocatedMemoryEnabled(true); bean.setThreadCpuTimeEnabled(true);
            for (int x=-1;x<=3;x++) for(int z=-1;z<=3;z++) {
                int cx=(origin.getX()>>4)+x, cz=(origin.getZ()>>4)+z;
                level.setChunkForced(cx,cz,true); level.getChunk(cx,cz);
            }
            recording=new Recording(Configuration.getConfiguration("profile"));
            recording.setName("witchcraft-benchmark"); recording.start();
            kernels(); setup();
            NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST, before);
            NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, after);
        }
        void place(BlockPos pos, net.minecraft.world.level.block.state.BlockState state) {
            level.setBlock(pos,state,3); placed.add(pos.immutable());
        }
        void setup() {
            SteamWork.fields(level).remove(field);
            // Fixture teardown is not a harvest. Do not populate subsequent phases with drops.
            for(var furnace:furnaces) furnace.clearContent();
            for (BlockPos p:placed) level.setBlock(p,Blocks.AIR.defaultBlockState(),3);
            placed.clear(); furnaces.clear(); roots.clear();
            // Native furnace removal also releases accumulated XP, even after clearContent.
            // This opt-in test owns the otherwise empty world; clear BOTH residual entity types.
            var residual=new ArrayList<net.minecraft.world.entity.Entity>();
            for(var e:level.getAllEntities())
                if(e instanceof net.minecraft.world.entity.item.ItemEntity || e instanceof net.minecraft.world.entity.ExperienceOrb)
                    residual.add(e);
            residual.forEach(net.minecraft.world.entity.Entity::discard);
            RitualWorkBudget.unload(level);
            Case c=cases.get(phase);
            for(int i=0;i<c.count;i++) {
                BlockPos p=origin.offset((i%16)*2,0,(i/16)*2);
                if(c.type.equals("furnace")) {
                    place(p,Blocks.FURNACE.defaultBlockState());
                    var f=(AbstractFurnaceBlockEntity)level.getBlockEntity(p);
                    f.setItem(0,new ItemStack(Items.IRON_ORE,64)); f.setItem(1,new ItemStack(Items.COAL,64));
                    furnaces.add(f);
                } else {
                    place(p.below(),TemporalContent.ROOTED_FARMLAND.get().defaultBlockState().setValue(FarmBlock.MOISTURE,7));
                    place(p,TemporalContent.RHIZOME.get().defaultBlockState());
                    var root=(LivingEntity)level.getBlockEntity(p);
                    root.tissue.rootStage=4; root.tissue.productionMode=SupplyMode.NORMAL;
                    root.tissue.setCoreQuantity(1000*Tissue.TU); root.changed();
                    TemporalWorld.get(level).add(root); roots.add(root);
                    place(p.north().below(),TemporalContent.ROOTED_FARMLAND.get().defaultBlockState().setValue(FarmBlock.MOISTURE,7));
                    place(p.north(),Blocks.WHEAT.defaultBlockState());
                }
            }
            if(c.doublings>0) SteamWork.fields(level).put(new RitualFields.Field(field,origin.offset(15,0,15),32,c.doublings));
            tick=0; armed=false;
            System.out.println("KW_BENCH_START "+c.name);
        }
        void before(ServerTickEvent.Pre event) {
            if(finished || event.getServer()!=level.getServer())return;
            if(tick==WARMUP) { outputStart=output(); rootStart=rootAge(); balanceStart=rootBalance(); gcStart=gcTime(); sampleStart=System.currentTimeMillis(); }
            long thread=Thread.currentThread().threadId();
            allocationStart=bean.getThreadAllocatedBytes(thread); cpuStart=bean.getCurrentThreadCpuTime();
            started=System.nanoTime(); armed=true;
        }
        void after(ServerTickEvent.Post event) {
            if(!armed || finished || event.getServer()!=level.getServer())return;
            long elapsed=System.nanoTime()-started;
            long usedCpu=bean.getCurrentThreadCpuTime()-cpuStart;
            long allocated=bean.getThreadAllocatedBytes(Thread.currentThread().threadId())-allocationStart;
            armed=false;
            if(tick>=WARMUP) { int i=tick-WARMUP; times[i]=elapsed/1e6; cpu[i]=usedCpu/1e6; bytes[i]=allocated; }
            if(++tick<WARMUP+SAMPLES) { pace(); return; }
            var c=cases.get(phase); var row=new LinkedHashMap<String,Object>();
            row.put("case",c.name); row.put("count",c.count); row.put("doublings",c.doublings);
            row.put("sample_started_epoch_ms",sampleStart); row.put("sample_ended_epoch_ms",System.currentTimeMillis());
            row.put("tick_ms",stats(times)); row.put("server_cpu_ms",stats(cpu)); row.put("server_allocated_bytes",stats(bytes));
            row.put("gc_ms_whole_process",gcTime()-gcStart); row.put("output_items",output()-outputStart);
            row.put("root_age_ticks",rootAge()-rootStart); row.put("resident_roots",roots.stream().filter(r->!r.isRemoved()).count());
            row.put("resident_items",items().size());
            long orbs=0; for(var e:level.getAllEntities()) if(e instanceof net.minecraft.world.entity.ExperienceOrb)orbs++;
            row.put("resident_xp_orbs",orbs);
            // The core can redistribute into side roots: this is NOT net organism expenditure.
            row.put("main_core_balance_decrease_milli_tu",balanceStart-rootBalance());
            row.put("crop_age_total",roots.stream().mapToInt(r->{
                var s=level.getBlockState(r.getBlockPos().north());
                return s.hasProperty(CropBlock.AGE)?s.getValue(CropBlock.AGE):0;
            }).sum());
            row.put("heap_used_bytes",ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed());
            row.put("budget",RitualWorkBudget.snapshot(level).toString()); rows.add(row);
            if(c.type.equals("furnace") && output()-outputStart<=0) throw new IllegalStateException("Benchmark machines did not tick: "+c.name);
            if(c.type.equals("root") && rootAge()-rootStart<=0) throw new IllegalStateException("Benchmark roots did not tick: "+c.name);
            if(orbs!=0 || !items().isEmpty()) throw new IllegalStateException("Fixture residue contaminated "+c.name);
            System.out.println("KW_BENCH_RESULT "+new GsonBuilder().create().toJson(row));
            if(++phase<cases.size())setup(); else finish();
        }
        long output() { return furnaces.stream().mapToLong(f->f.getItem(2).getCount()).sum(); }
        List<net.minecraft.world.entity.item.ItemEntity> items() {
            return level.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class,
                    new net.minecraft.world.phys.AABB(origin.getX()-32,level.getMinBuildHeight(),origin.getZ()-32,
                            origin.getX()+64,level.getMaxBuildHeight(),origin.getZ()+64));
        }
        long rootAge() { return roots.stream().mapToLong(r->r.tissue.naturalTicks).sum(); }
        long rootBalance() { return roots.stream().mapToLong(r->r.tissue.coreQuantity()).sum(); }
        // GameTestServer skips the normal 20 TPS wait. Pace OUTSIDE the compute sample.
        // Never catch up missed wall time: a slow tick remains a slow tick in the results.
        void pace() {
            long remaining;
            while((remaining=50_000_000L-(System.nanoTime()-started))>0)
                java.util.concurrent.locks.LockSupport.parkNanos(remaining);
        }
        void finish() {
            finished=true; NeoForge.EVENT_BUS.unregister(before); NeoForge.EVENT_BUS.unregister(after);
            try {
                recording.stop(); recording.dump(Path.of("benchmark.jfr")); recording.close();
                var report=new LinkedHashMap<String,Object>();
                report.put("java",System.getProperty("java.runtime.version")); report.put("vm_args",ManagementFactory.getRuntimeMXBean().getInputArguments());
                report.put("warmup_ticks_per_case",WARMUP); report.put("sample_ticks_per_case",SAMPLES);
                report.put("pacing","At least 50 ms per tick; wait occurs outside the Pre/Post compute sample");
                report.put("fixture_origin",origin.toShortString());
                report.put("kernel_warmup_ms",250); report.put("kernel_target_batch_ms",5); report.put("kernel_sample_batches",12);
                report.put("reverse",Boolean.getBoolean("witchcraft.benchmark.reverse")); report.put("kernels",kernels); report.put("scenarios",rows);
                report.put("mods",net.neoforged.fml.ModList.get().getMods().stream().map(m->m.getModId()+"="+m.getVersion()).toList());
                Files.writeString(Path.of("benchmark.json"),new GsonBuilder().setPrettyPrinting().create().toJson(report));
                helper.succeed();
            } catch(Exception ex) { throw new RuntimeException(ex); }
        }
        void kernels() {
            for(int n:new int[]{1,16,64,256,1024}) {
                for(boolean overlap:new boolean[]{false,true}) {
                    var fields=new RitualFields(); var points=new Vec3[64];
                    for(int i=0;i<n;i++) fields.put(new RitualFields.Field(new UUID(0,i+1),new BlockPos(overlap?0:i*128,80,0),16));
                    for(int i=0;i<points.length;i++) points[i]=new Vec3(overlap?i%8:(i%n)*128+.5,80.5,.5);
                    int[] cursor={0};
                    kernel("fields_"+(overlap?"overlap":"spread"),n,1024,()->fields.at(points[(cursor[0]++)&63]).doublings());
                }
                var members=new ArrayList<LivingEntity>();
                for(int i=0;i<n;i++) {
                    var e=new LivingEntity(new BlockPos(i,0,0),TemporalContent.RHIZOME.get().defaultBlockState());
                    e.tissue.rootStage=2; e.tissue.setCoreQuantity(100*Tissue.TU); members.add(e);
                }
                var colony=new Colony(members,List.of());
                kernel("colony_query_warm",n,Math.max(16,4096/n),colony::quantity);
                kernel("storage_rebuild",n,Math.max(4,1024/n),()->TimeStorage.of(members.stream().map(e->e.tissue).toList()).quantity());
                kernel("tissue_save_batch",n,Math.max(2,128/n),()->{
                    long size=0; for(var e:members)size+=TissueTag.save(e.tissue).size(); return size;
                });
            }
        }
        void kernel(String name,int n,int operations,LongSupplier action) {
            double[] ns=new double[12], allocation=new double[12];
            long deadline=System.nanoTime()+250_000_000L;
            do {
                long begin=System.nanoTime(), result=0;
                for(int i=0;i<operations;i++) result+=action.getAsLong();
                blackhole=result;
                long elapsed=Math.max(1,System.nanoTime()-begin);
                operations=(int)Math.clamp((long)operations*5_000_000L/elapsed,1,1_000_000);
            } while(System.nanoTime()<deadline);
            for(int batch=0;batch<12;batch++) {
                long beforeBytes=bean.getThreadAllocatedBytes(Thread.currentThread().threadId()), begin=System.nanoTime(), result=0;
                for(int i=0;i<operations;i++)result+=action.getAsLong();
                long elapsed=System.nanoTime()-begin, allocated=bean.getThreadAllocatedBytes(Thread.currentThread().threadId())-beforeBytes;
                blackhole=result;
                ns[batch]=(double)elapsed/operations;allocation[batch]=(double)allocated/operations;
            }
            var row=new LinkedHashMap<String,Object>();row.put("kernel",name);row.put("n",n);row.put("operations_per_batch",operations);
            row.put("ns_per_operation",stats(ns));row.put("bytes_per_operation",stats(allocation));kernels.add(row);
        }
    }
    static long gcTime() { return ManagementFactory.getGarbageCollectorMXBeans().stream().mapToLong(b->Math.max(0,b.getCollectionTime())).sum(); }
    static Map<String,Double> stats(double[] data) {
        double[] sorted=data.clone();Arrays.sort(sorted);
        var r=new LinkedHashMap<String,Double>();r.put("mean",Arrays.stream(data).average().orElse(0));
        r.put("p50",sorted[(int)Math.ceil(data.length*.5)-1]);r.put("p95",sorted[(int)Math.ceil(data.length*.95)-1]);
        r.put("p99",sorted[(int)Math.ceil(data.length*.99)-1]);r.put("max",sorted[sorted.length-1]);return r;
    }
}
