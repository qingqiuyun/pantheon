package world;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * God —— 神的大脑（Java 端）。决策完全由编译后的 AST 驱动（逻辑是数据，不是代码）。
 *
 * 每个神有独立的 Random（种子 = masterSeed*31 + name.hashCode()），互不影响且同 seed 可复现。
 * 主循环每一 tick：perceive() 更新内部状态 -> 计算各 mode 分数 -> softmax -> 采样一个 mode
 * -> 按 mode 生成 deltas。神的顺序由 Orchestrator 用排序后的 List 迭代，绝不依赖 HashMap 迭代。
 */
public final class God {

    /** 不可变的编译后定义（由 build/logic 加载，只读） */
    public static final class Def {
        public final String name;
        public final double power;
        public final double temper;
        public final Map<String, Double> goal;
        public final Map<String, Double> affinity;
        public final Map<String, Double> stateInit;
        public final Map<String, Double> ema;
        public final Map<String, Object> perceive;   // var -> AST
        public final Map<String, Mode> modes;          // modeName -> Mode

        public Def(String name, double power, double temper,
                   Map<String, Double> goal, Map<String, Double> affinity,
                   Map<String, Double> stateInit, Map<String, Double> ema,
                   Map<String, Object> perceive, Map<String, Mode> modes) {
            this.name = name; this.power = power; this.temper = temper;
            this.goal = goal; this.affinity = affinity; this.stateInit = stateInit;
            this.ema = ema; this.perceive = perceive; this.modes = modes;
        }
    }

    public static final class Mode {
        public final Object score;                  // AST
        public final Map<String, Object> deltas;    // dim -> AST
        public Mode(Object score, Map<String, Object> deltas) {
            this.score = score; this.deltas = deltas;
        }
    }

    public final Def def;
    public final Map<String, Double> state = new LinkedHashMap<>();
    public final Random rng;
    public String lastMode = "?";     // 最近一次选择的模式（用于日志）

    public God(Def def, long masterSeed) {
        this.def = def;
        this.rng = new Random((long) masterSeed * 31L + (long) def.name.hashCode());
        reset();
    }

    public void reset() {
        state.clear();
        if (def.stateInit != null) for (var e : def.stateInit.entrySet())
            state.put(e.getKey(), e.getValue());
        lastMode = "?";
    }

    /** 世界与各神目标的对齐度：affinity 加权，区间约 [0,1] */
    public double align(Map<String, Double> world) {
        double num = 0, den = 0;
        for (var e : def.affinity.entrySet()) {
            String d = e.getKey();
            double w = e.getValue();
            double g = def.goal.getOrDefault(d, 0.5);
            num += w * (1.0 - Math.abs(world.getOrDefault(d, 0.5) - g));
            den += w;
        }
        return den == 0 ? 0.5 : num / den;
    }

    /** 构建表达式变量上下文（世界维度、goal_ 与 aff_、内部状态、ema、align、progress 等） */
    private Map<String, Double> context(Map<String, Double> world, double align, int tick) {
        Map<String, Double> ctx = new LinkedHashMap<>();
        if (world != null) ctx.putAll(world);            // 世界维度
        for (var e : def.goal.entrySet())                // goal_<dim>
            ctx.put("goal_" + e.getKey(), e.getValue());
        for (var e : def.affinity.entrySet())            // aff_<dim>
            ctx.put("aff_" + e.getKey(), e.getValue());
        ctx.putAll(state);                               // 内部状态（覆盖同名）
        if (def.ema != null) ctx.putAll(def.ema);        // ema 参数（alpha 等）
        ctx.put("align", align);
        ctx.put("progress", state.getOrDefault("progress", 0.0));
        ctx.put("power", def.power);
        ctx.put("temper", def.temper);
        ctx.put("tick", (double) tick);
        return ctx;
    }

    private Expr.Vars vars(Map<String, Double> ctx, String where) {
        return name -> {
            Double x = ctx.get(name);
            if (x == null)
                throw new RuntimeException("未定义变量 [" + name + "] 于 神[" + def.name + "] 的 " + where);
            return x;
        };
    }

    /** perceive：顺序更新内部状态（含 progress） */
    public void perceive(Map<String, Double> world, int tick) {
        double align = align(world);
        Map<String, Double> ctx = context(world, align, tick);
        Expr.Vars v = vars(ctx, "perceive");
        for (var e : def.perceive.entrySet()) {
            double val = Expr.eval(e.getValue(), v);
            state.put(e.getKey(), val);
            ctx.put(e.getKey(), val);   // 后续表达式可见刚更新的变量
        }
    }

    /** decide：softmax 选一个 mode，返回该 mode 的 dim->delta 映射 */
    public Map<String, Double> decide(Map<String, Double> world, int tick) {
        double align = align(world);
        Map<String, Double> ctx = context(world, align, tick);
        Expr.Vars v = vars(ctx, "score");

        // 计算各 mode 分数
        Map<String, Double> scores = new LinkedHashMap<>();
        double max = Double.NEGATIVE_INFINITY;
        for (var e : def.modes.entrySet()) {
            double s = Expr.eval(e.getValue().score, v);
            scores.put(e.getKey(), s);
            if (s > max) max = s;
        }
        // softmax（数值稳定在 max 处）
        double sum = 0;
        Map<String, Double> w = new LinkedHashMap<>();
        for (var e : scores.entrySet()) {
            double p = Math.exp(e.getValue() - max);
            w.put(e.getKey(), p);
            sum += p;
        }
        // 采样（恰好一次 RNG 抽取）
        double r = rng.nextDouble() * sum;
        String chosen = null;
        double acc = 0;
        for (var e : w.entrySet()) {
            acc += e.getValue();
            if (r <= acc) { chosen = e.getKey(); break; }
        }
        if (chosen == null) chosen = def.modes.keySet().iterator().next();

        lastMode = chosen;
        // 计算所选 mode 的 deltas
        Map<String, Double> out = new LinkedHashMap<>();
        for (var e : def.modes.get(chosen).deltas.entrySet()) {
            out.put(e.getKey(), Expr.eval(e.getValue(), v));
        }
        return out;
    }

    public double progress() { return state.getOrDefault("progress", 0.0); }
}
