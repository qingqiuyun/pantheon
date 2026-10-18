package world;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Orchestrator —— Java 编排层。核心：每 tick 一个循环，单方向管道。
 *
 *   收 state -> 对每个神 perceive/decide -> 打包 deltas 发 step -> 收 tick -> 查崩溃/进度
 *
 * 确定性保证：
 *   - 每个神独立 Random（seed = masterSeed*31 + name.hashCode()），互不影响。
 *   - 神按 name 排序后用 List 迭代，绝不依赖 HashMap 迭代顺序。
 *   - 每神每 tick 恰好一次 RNG 抽取（softmax 采样）。
 */
public final class Orchestrator {

    /** 单次推演结果 */
    public static final class SimResult {
        public String reason = "?";
        public int tick = 0;
        public final Map<String, Double> state = new LinkedHashMap<>();
        public final Map<String, Double> progress = new LinkedHashMap<>();
        public final Map<String, String> modes = new LinkedHashMap<>();
    }

    /** 运行配置 */
    public static final class Config {
        String buildDir = "build";
        String logicDir = "build/logic";
        String exePath = "build/main-world";
        int maxTicks = 200;
        int seed = 42;
        int seeds = 1;
        int perturbTick = -1;
        String perturbDim = null;
        double perturbEps = 0.0;
        String jsonlPath = null;
        boolean verbose = true;
    }

    public static void main(String[] args) throws Exception {
        Config cfg = parseArgs(args);
        Path manifestPath = Path.of(cfg.logicDir, "manifest.json");
        String manifestText = Files.readString(manifestPath, StandardCharsets.UTF_8);
        WorldSpec world = WorldSpec.fromManifest(Json.asObject(Json.parse(manifestText)));

        List<God.Def> defs = loadDefs(cfg.logicDir);

        if (cfg.seeds > 1) {
            runSeeds(cfg, world, defs);
        } else {
            runSingle(cfg, world, defs);
        }
    }

    // ───────────────────── 配置 / 加载 ─────────────────────
    private static Config parseArgs(String[] args) {
        Config c = new Config();
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--build": c.buildDir = args[++i]; break;
                case "--logic": c.logicDir = args[++i]; break;
                case "--exe":   c.exePath = args[++i]; break;
                case "--max-ticks": c.maxTicks = Integer.parseInt(args[++i]); break;
                case "--seed":  c.seed = Integer.parseInt(args[++i]); break;
                case "--seeds": c.seeds = Integer.parseInt(args[++i]); break;
                case "--perturb": {
                    String[] p = args[++i].split(":");
                    c.perturbTick = Integer.parseInt(p[0]);
                    c.perturbDim = p[1];
                    c.perturbEps = Double.parseDouble(p[2]);
                    break;
                }
                case "--jsonl": c.jsonlPath = args[++i]; break;
                case "--silent": c.verbose = false; break;
                default: throw new IllegalArgumentException("未知参数: " + args[i]);
            }
        }
        return c;
    }

    @SuppressWarnings("unchecked")
    private static List<God.Def> loadDefs(String logicDir) throws IOException {
        Map<String, Object> manifest = Json.asObject(
                Json.parse(Files.readString(Path.of(logicDir, "manifest.json"), StandardCharsets.UTF_8)));
        List<Object> names = (List<Object>) manifest.get("gods");
        List<God.Def> defs = new ArrayList<>();
        for (Object n : names) {
            String name = (String) n;
            Map<String, Object> g = Json.asObject(
                    Json.parse(Files.readString(Path.of(logicDir, "gods", name + ".json"), StandardCharsets.UTF_8)));
            defs.add(defFromJson(g));
        }
        return defs;
    }

    @SuppressWarnings("unchecked")
    private static God.Def defFromJson(Map<String, Object> g) {
        String name = (String) g.get("name");
        double power = Json.asDouble(g.get("power"));
        double temper = Json.asDouble(g.get("temper"));
        Map<String, Double> goal = numMap((Map<String, Object>) g.get("goal"));
        Map<String, Double> affinity = numMap((Map<String, Object>) g.get("affinity"));
        Map<String, Double> stateInit = numMap((Map<String, Object>) g.get("state_init"));
        Map<String, Double> ema = numMap((Map<String, Object>) g.getOrDefault("ema", new LinkedHashMap<>()));
        Map<String, Object> perceive = (Map<String, Object>) g.get("perceive");

        Map<String, Object> modesRaw = (Map<String, Object>) g.get("modes");
        Map<String, God.Mode> modes = new LinkedHashMap<>();
        for (var e : modesRaw.entrySet()) {
            Map<String, Object> mo = (Map<String, Object>) e.getValue();
            Map<String, Object> deltasRaw = (Map<String, Object>) mo.get("deltas");
            Map<String, Object> deltas = new LinkedHashMap<>();
            for (var d : deltasRaw.entrySet()) deltas.put(d.getKey(), d.getValue());
            modes.put(e.getKey(), new God.Mode(mo.get("score"), deltas));
        }
        return new God.Def(name, power, temper, goal, affinity, stateInit, ema, perceive, modes);
    }

    private static Map<String, Double> numMap(Map<String, Object> m) {
        Map<String, Double> out = new LinkedHashMap<>();
        if (m == null) return out;
        for (var e : m.entrySet()) out.put(e.getKey(), Json.asDouble(e.getValue()));
        return out;
    }

    // ───────────────────── 单次推演 ─────────────────────
    private static SimResult runOnce(Config cfg, WorldSpec world, List<God.Def> defs,
                                     int seed, boolean verbose, Writer jsonl) throws Exception {
        SimResult res = new SimResult();
        EngineClient client = new EngineClient(cfg.exePath);
        final AtomicBoolean running = new AtomicBoolean(true);

        // 三层停止：Ctrl-C 的 shutdown hook 也要能干净收尾（先检查句柄，避免 BrokenPipe）
        Thread hook = new Thread(() -> {
            running.set(false);
            try {
                if (client.isAlive()) client.stopAndWait();
            } catch (Exception ignored) {}
        });
        Runtime.getRuntime().addShutdownHook(hook);

        try {
            client.sendInit(world);
            // 神按 name 排序，用 List 迭代（确定性）
            List<God> gods = new ArrayList<>();
            TreeMap<String, God.Def> sorted = new TreeMap<>();
            for (God.Def d : defs) sorted.put(d.name, d);
            for (God.Def d : sorted.values()) gods.add(new God(d, seed));

            Map<String, Double> worldState = world.initial();
            int tick = 0;

            outer:
            while (running.get() && (cfg.maxTicks == 0 || tick < cfg.maxTicks)) {
                // 1) 收 state（上一步 C 回传；首步为 default）—— 已存于 worldState
                // 2) 对每个神：perceive -> decide
                Map<String, Double> deltas = new LinkedHashMap<>();
                for (God g : gods) g.perceive(worldState, tick);
                for (God g : gods) {
                    Map<String, Double> d = g.decide(worldState, tick);
                    for (var e : d.entrySet())
                        deltas.merge(e.getKey(), e.getValue(), Double::sum);
                }
                // 扰动：在指定 tick 给某维度注入微小 delta（验证蝴蝶效应）
                if (cfg.perturbTick == tick && cfg.perturbDim != null)
                    deltas.merge(cfg.perturbDim, cfg.perturbEps, Double::sum);

                // 3) 发 step 给 C
                client.sendStep(deltas);
                // 4) 收 C 的 tick 响应
                EngineClient.Tick tk = client.recvTick();
                if (tk == null) { res.reason = "C 进程退出（管道 EOF）"; break; }
                if (tk.collapse != null) {
                    if ("exit".equals(tk.collapse)) { res.reason = "C 主动退出"; break; }
                    res.reason = tk.collapse; break outer;
                }
                worldState = tk.state;
                res.tick = tk.t;
                // 5) 记录进度，检查命途极致
                for (God g : gods) {
                    res.progress.put(g.def.name, g.progress());
                    res.modes.put(g.def.name, g.lastMode);
                }
                if (jsonl != null) writeJsonl(jsonl, tk.t, tk.state, gods);

                if (verbose && tk.t > 0 && tk.t % 100 == 0) {
                    System.out.printf("  t=%d  progress: %s%n", tk.t, shortProgress(res.progress));
                }
                // 命途极致：某神走到尽头
                for (God g : gods) {
                    if (g.progress() >= 1.0) {
                        res.reason = "命途极致：[" + g.def.name + "] 将命途走到尽头，世界被其重塑";
                        break outer;
                    }
                }
                tick++;
            }
            if ("?".equals(res.reason) && !running.get())
                res.reason = "用户中断（Ctrl-C）";
            res.state.putAll(worldState);
        } finally {
            try { Runtime.getRuntime().removeShutdownHook(hook); } catch (Exception ignored) {}
            try { if (client.isAlive()) client.stopAndWait(); } catch (Exception ignored) {}
        }
        return res;
    }

    private static void runSingle(Config cfg, WorldSpec world, List<God.Def> defs) throws Exception {
        Writer jsonl = null;
        if (cfg.jsonlPath != null)
            jsonl = new BufferedWriter(new FileWriter(cfg.jsonlPath, StandardCharsets.UTF_8));
        try {
            SimResult r = runOnce(cfg, world, defs, cfg.seed, cfg.verbose, jsonl);
            if (jsonl != null) jsonl.flush();
            System.out.println("========================================");
            System.out.printf("推演结束 @ tick=%d%n", r.tick);
            System.out.println("原因: " + r.reason);
            System.out.println("-- 终局世界状态 --");
            for (var e : r.state.entrySet())
                System.out.printf("  %-10s = %+.4f%n", e.getKey(), e.getValue());
            System.out.println("-- 各神命途进度 / 末步模式 --");
            for (var e : r.progress.entrySet()) {
                String flag = e.getValue() >= 0.999 ? "  ◀ 重塑世界" : "";
                System.out.printf("  %-4s progress=%.3f  mode=%s%s%n",
                        e.getKey(), e.getValue(), r.modes.get(e.getKey()), flag);
            }
        } finally {
            if (jsonl != null) jsonl.close();
        }
    }

    // ───────────────────── 批量 seed（多世界并行） ─────────────────────
    private static void runSeeds(Config cfg, WorldSpec world, List<God.Def> defs) throws Exception {
        int n = cfg.seeds;
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(8, n));
        Map<String, AtomicInteger> counts = new ConcurrentHashMap<>();
        Map<Integer, String> reasons = new ConcurrentHashMap<>();
        List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            final int seed = cfg.seed * 1000 + i;
            futures.add(pool.submit(() -> {
                try {
                    SimResult r = runOnce(cfg, world, defs, seed, false, null);
                    String key = summarize(r);
                    counts.computeIfAbsent(key, k -> new AtomicInteger()).incrementAndGet();
                    reasons.put(seed, key);
                } catch (Exception e) {
                    counts.computeIfAbsent("ERROR:" + e.getMessage(), k -> new AtomicInteger()).incrementAndGet();
                }
            }));
        }
        for (var f : futures) { try { f.get(); } catch (Exception ignored) {} }
        pool.shutdown();
        pool.awaitTermination(60, TimeUnit.SECONDS);

        System.out.println("========================================");
        System.out.printf("多世界并行：%d 个 seed（每 seed masterSeed=base*1000+i）%n", n);
        System.out.println("终局原因分布：");
        List<Map.Entry<String, AtomicInteger>> sorted = new ArrayList<>(counts.entrySet());
        sorted.sort((a, b) -> Integer.compare(b.getValue().get(), a.getValue().get()));
        for (var e : sorted)
            System.out.printf("  [%3d/%d] %s%n", e.getValue().get(), n, e.getKey());

        if (cfg.jsonlPath != null) {
            try (Writer w = new BufferedWriter(new FileWriter(cfg.jsonlPath, StandardCharsets.UTF_8))) {
                w.write("seed,reason\n");
                for (var e : reasons.entrySet()) w.write(e.getKey() + "," + e.getValue() + "\n");
            }
            // 分布 JSON：供 Python 画图
            try (Writer w = new BufferedWriter(
                    new FileWriter(cfg.jsonlPath + ".dist.json", StandardCharsets.UTF_8))) {
                StringBuilder sb = new StringBuilder();
                sb.append("{\"n\":").append(n).append(",\"dist\":{");
                boolean first = true;
                for (var e : sorted) {
                    if (!first) sb.append(',');
                    first = false;
                    sb.append('"').append(e.getKey()).append("\":").append(e.getValue().get());
                }
                sb.append("}}\n");
                w.write(sb.toString());
            }
        }
    }

    private static String summarize(SimResult r) {
        if (r.reason.startsWith("命途极致"))
            return "命途极致：" + r.reason.substring(r.reason.indexOf('[') + 1, r.reason.indexOf(']'));
        if (r.reason.contains("越界")) return "维度越界";
        return r.reason;
    }

    // ───────────────────── 辅助 ─────────────────────
    private static void writeJsonl(Writer w, int t, Map<String, Double> state, List<God> gods) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"t\":").append(t).append(",\"state\":{");
        boolean first = true;
        for (var e : state.entrySet()) {
            if (!first) sb.append(',');
            first = false;
            sb.append('"').append(e.getKey()).append("\":").append(e.getValue());
        }
        sb.append("},\"modes\":{");
        first = true;
        for (God g : gods) {
            if (!first) sb.append(',');
            first = false;
            sb.append('"').append(g.def.name).append("\":\"").append(g.lastMode).append('"');
        }
        sb.append("},\"progress\":{");
        first = true;
        for (God g : gods) {
            if (!first) sb.append(',');
            first = false;
            sb.append('"').append(g.def.name).append("\":").append(g.progress());
        }
        sb.append("}}\n");
        w.write(sb.toString());
    }

    private static String shortProgress(Map<String, Double> p) {
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (var e : p.entrySet()) {
            if (!first) sb.append(' ');
            first = false;
            sb.append(e.getKey()).append('=').append(String.format("%.2f", e.getValue()));
        }
        return sb.toString();
    }
}
