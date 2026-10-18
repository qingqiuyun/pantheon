package world;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * EngineClient —— 管理 C 世界子进程（stdin/stdout JSON-lines 管道）。
 *
 * 职责边界：只负责进程与协议传输，不含任何模拟逻辑。C 崩了 -> 进程退出 ->
 * 读线程读到 EOF -> recvTick 返回 null，由 Orchestrator 决定如何收尾（不残留僵尸）。
 */
public final class EngineClient {

    public static final class Tick {
        public final int t;
        public final Map<String, Double> state;
        public final String collapse;   // null=正常；非 null=崩溃原因；"exit"=C 主动退出
        Tick(int t, Map<String, Double> state, String collapse) {
            this.t = t; this.state = state; this.collapse = collapse;
        }
    }

    private final Process proc;
    private final BufferedReader in;       // C 的 stdout
    private final BufferedWriter out;      // C 的 stdin

    public EngineClient(String exePath) throws IOException {
        ProcessBuilder pb = new ProcessBuilder(exePath);
        pb.redirectErrorStream(false);
        proc = pb.start();
        in = new BufferedReader(new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8));
        out = new BufferedWriter(new OutputStreamWriter(proc.getOutputStream(), StandardCharsets.UTF_8));
        // 单独线程排空 C 的 stderr，避免管道写满导致 C 阻塞
        Thread err = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(proc.getErrorStream(), StandardCharsets.UTF_8))) {
                String l;
                while ((l = r.readLine()) != null) System.err.println("[C] " + l);
            } catch (IOException ignored) {}
        });
        err.setDaemon(true);
        err.start();
    }

    /** 发送 init（维度列表 + relax + 软/硬边界 + 默认初值） */
    public void sendInit(WorldSpec w) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"type\":\"init\",\"dims\":[");
        for (int i = 0; i < w.dims.length; i++) {
            if (i > 0) sb.append(',');
            sb.append('"').append(w.dims[i]).append('"');
        }
        sb.append("],\"relax\":").append(w.relax)
          .append(",\"soft_lo\":").append(w.softLo)
          .append(",\"soft_hi\":").append(w.softHi)
          .append(",\"hard_lo\":").append(w.hardLo)
          .append(",\"hard_hi\":").append(w.hardHi)
          .append(",\"default_state\":").append(w.defaultState)
          .append("}\n");
        write(sb.toString());
    }

    /** 发送 step：聚合后的 dim->delta（C 忽略 actor，只看 dim/delta） */
    public void sendStep(Map<String, Double> deltas) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"type\":\"step\",\"deltas\":[");
        boolean first = true;
        for (var e : deltas.entrySet()) {
            if (e.getValue() == 0.0) continue;
            if (!first) sb.append(',');
            first = false;
            sb.append("{\"dim\":\"").append(e.getKey()).append("\",\"delta\":")
               .append(e.getValue()).append('}');
        }
        sb.append("]}\n");
        write(sb.toString());
    }

    private void write(String s) throws IOException {
        out.write(s);
        out.flush();
    }

    /** 读一条 tick（或 exit）；EOF 返回 null */
    public Tick recvTick() throws IOException {
        String line = in.readLine();
        if (line == null) return null;
        Map<String, Object> obj = Json.asObject(Json.parse(line));
        String type = (String) obj.get("type");
        if (!"tick".equals(type)) {
            return new Tick(-1, new LinkedHashMap<>(), "exit");
        }
        int t = ((Number) obj.get("t")).intValue();
        String collapse = obj.get("collapse") == null ? null : (String) obj.get("collapse");
        Map<String, Double> state = new LinkedHashMap<>();
        Map<?, ?> st = Json.asObject(obj.get("state"));
        for (var e : st.entrySet())
            state.put((String) e.getKey(), Json.asDouble(e.getValue()));
        return new Tick(t, state, collapse);
    }

    /** 发送 stop 并等待 C 退出（防止僵尸进程） */
    public void stopAndWait() {
        try { write("{\"type\":\"stop\"}\n"); } catch (IOException ignored) {}
        try {
            if (!proc.waitFor(2, TimeUnit.SECONDS)) {
                proc.destroyForcibly();   // 兜底，避免残留
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            proc.destroyForcibly();
        }
    }

    public boolean isAlive() { return proc.isAlive(); }
}
