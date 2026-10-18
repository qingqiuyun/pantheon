package world;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 世界规格：维度、relax、软/硬边界、默认初值。从 manifest.json 加载。 */
public final class WorldSpec {
    public final String[] dims;
    public final double relax;
    public final double softLo, softHi, hardLo, hardHi;
    public final double defaultState;

    public WorldSpec(String[] dims, double relax, double softLo, double softHi, double hardLo, double hardHi, double defaultState) {
        this.dims = dims; this.relax = relax;
        this.softLo = softLo; this.softHi = softHi;
        this.hardLo = hardLo; this.hardHi = hardHi;
        this.defaultState = defaultState;
    }

    public static WorldSpec fromManifest(Map<String, Object> m) {
        Object dimsO = m.get("dims");
        @SuppressWarnings("unchecked")
        List<Object> dl = (List<Object>) dimsO; // Json 解析数组为 List<Object>
        String[] dims = new String[dl.size()];
        for (int i = 0; i < dims.length; i++) dims[i] = (String) dl.get(i);
        return new WorldSpec(
                dims,
                Json.asDouble(m.get("relax")),
                Json.asDouble(m.get("soft_lo")),
                Json.asDouble(m.get("soft_hi")),
                Json.asDouble(m.get("hard_lo")),
                Json.asDouble(m.get("hard_hi")),
                Json.asDouble(m.get("default_state")));
    }

    /** 初始世界状态（全部为 default） */
    public Map<String, Double> initial() {
        Map<String, Double> m = new LinkedHashMap<>();
        for (String d : dims) m.put(d, defaultState);
        return m;
    }
}
