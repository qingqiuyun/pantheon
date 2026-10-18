package world;

import java.util.List;
import java.util.Map;

/**
 * Expr —— AST 解释器（几十行）。
 * 直接 eval Python 编译产物（build/logic 里的 AST JSON），不在运行时依赖 Python。
 *
 * 节点约定：
 *   {"t":"num","v":1.2}
 *   {"t":"var","n":"entropy"}
 *   {"t":"neg"|"not","a":node}
 *   {"t":"add"|"sub"|"mul"|"div"|"mod","a","b"}
 *   {"t":"lt"|"le"|"gt"|"ge"|"eq"|"ne","a","b"}
 *   {"t":"and"|"or","a","b"}
 *   {"t":"call","f":"abs","args":[...]}
 *
 * 变量解析通过 Vars 接口注入（世界维度、goal_ 与 aff_、内部状态、align、progress 等）。
 * 未定义变量直接抛异常——既保证确定性，也让配置错误立即暴露。
 */
public final class Expr {

    public interface Vars {
        double get(String name);
    }

    @SuppressWarnings("unchecked")
    public static double eval(Object node, Vars v) {
        Map<String, Object> m = (Map<String, Object>) node;
        String t = (String) m.get("t");
        switch (t) {
            case "num":
                return ((Number) m.get("v")).doubleValue();
            case "var":
                return v.get((String) m.get("n"));
            case "neg":
                return -eval(m.get("a"), v);
            case "not":
                return eval(m.get("a"), v) == 0.0 ? 1.0 : 0.0;
            case "add":
                return eval(m.get("a"), v) + eval(m.get("b"), v);
            case "sub":
                return eval(m.get("a"), v) - eval(m.get("b"), v);
            case "mul":
                return eval(m.get("a"), v) * eval(m.get("b"), v);
            case "div":
                return eval(m.get("a"), v) / eval(m.get("b"), v);
            case "mod":
                return eval(m.get("a"), v) % eval(m.get("b"), v);
            case "lt": return eval(m.get("a"), v) < eval(m.get("b"), v) ? 1.0 : 0.0;
            case "le": return eval(m.get("a"), v) <= eval(m.get("b"), v) ? 1.0 : 0.0;
            case "gt": return eval(m.get("a"), v) > eval(m.get("b"), v) ? 1.0 : 0.0;
            case "ge": return eval(m.get("a"), v) >= eval(m.get("b"), v) ? 1.0 : 0.0;
            case "eq": return eval(m.get("a"), v) == eval(m.get("b"), v) ? 1.0 : 0.0;
            case "ne": return eval(m.get("a"), v) != eval(m.get("b"), v) ? 1.0 : 0.0;
            case "and": return (eval(m.get("a"), v) != 0 && eval(m.get("b"), v) != 0) ? 1.0 : 0.0;
            case "or":  return (eval(m.get("a"), v) != 0 || eval(m.get("b"), v) != 0) ? 1.0 : 0.0;
            case "call": {
                String f = (String) m.get("f");
                List<Object> args = (List<Object>) m.get("args");
                return call(f, args, v);
            }
            default:
                throw new RuntimeException("未知 AST 节点类型: " + t);
        }
    }

    private static double call(String f, List<Object> args, Vars v) {
        double[] a = new double[args.size()];
        for (int k = 0; k < a.length; k++) a[k] = eval(args.get(k), v);
        switch (f) {
            case "sin": return Math.sin(a[0]);
            case "cos": return Math.cos(a[0]);
            case "tan": return Math.tan(a[0]);
            case "asin": return Math.asin(a[0]);
            case "acos": return Math.acos(a[0]);
            case "atan": return Math.atan(a[0]);
            case "exp": return Math.exp(a[0]);
            case "log": return Math.log(a[0]);
            case "log10": return Math.log10(a[0]);
            case "sqrt": return Math.sqrt(a[0]);
            case "abs": return Math.abs(a[0]);
            case "sign": return a[0] > 0 ? 1 : (a[0] < 0 ? -1 : 0);
            case "floor": return Math.floor(a[0]);
            case "ceil": return Math.ceil(a[0]);
            case "round": return Math.round(a[0]);
            case "tanh": return Math.tanh(a[0]);
            case "mod": return a[0] % a[1];
            case "pow": return Math.pow(a[0], a[1]);
            case "min": return Math.min(a[0], a[1]);
            case "max": return Math.max(a[0], a[1]);
            case "hypot": return Math.hypot(a[0], a[1]);
            case "clamp": return Math.max(a[1], Math.min(a[2], a[0]));
            default:
                throw new RuntimeException("未知函数: " + f);
        }
    }
}
