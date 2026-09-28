package com.example.aibot.llm;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 缓存字段的「多方言」解析器。
 *
 * <p><b>为什么必须有这一层</b>：本模组默认面向「中转站」（OpenAI 兼容聚合 API），
 * 而中转站的 usage 字段命名极其混乱：</p>
 * <ul>
 *   <li>DeepSeek 官方：{@code prompt_cache_hit_tokens} / {@code prompt_cache_miss_tokens}</li>
 *   <li>OpenAI 官方：{@code prompt_tokens_details.cached_tokens}</li>
 *   <li>Anthropic 风格（部分中转站转成 OpenAI 格式时保留）：
 *       {@code cache_read_input_tokens} / {@code cache_creation_input_tokens}</li>
 *   <li>国内厂商：{@code cached_tokens}、{@code cache_tokens}、
 *       {@code prompt_cached_tokens}、{@code input_tokens_details.cached_tokens}</li>
 *   <li>很多中转站：<b>什么都不上报</b>（usage 里只有 prompt_tokens / completion_tokens）</li>
 * </ul>
 *
 * <p>如果只认某一种，就会像很多同类模组一样：明明在命中缓存，
 * 统计里却永远显示 0%，于是用户以为「缓存没生效」而反复折腾。</p>
 *
 * <p><b>本类的职责</b>：把上面所有方言归一成三个数
 * （cacheHit / cacheMiss / promptTotal），并在完全拿不到数据时
 * 明确标记为「未知」而不是谎报 0 —— 0 会被算进命中率分母，
 * 把真实命中率稀释成假的低值。</p>
 */
public final class CacheUsageParser {

    /** 解析结果。 */
    public static final class Result {
        /** prompt 总量（服务端上报的 input token 数）。 */
        public long promptTokens;
        /** 命中缓存的 token 数。 */
        public long cachedTokens;
        /** 未命中的 token 数。 */
        public long missTokens;
        /** completion token 数。 */
        public long completionTokens;
        /**
         * 服务端是否上报了缓存信息。
         *
         * <p>false 表示「无法判断」，此时调用方<b>不应该</b>把它算进命中率，
         * 否则会把命中率算低。</p>
         */
        public boolean cacheReported;
        /** 命中的字段名，用于诊断日志（告诉用户「我是从哪个字段读到的」）。 */
        public String sourceField = "";

        @Override
        public String toString() {
            return "prompt=" + promptTokens + " hit=" + cachedTokens + " miss=" + missTokens
                    + " out=" + completionTokens
                    + (cacheReported ? " (来自 " + sourceField + ")" : " (未上报)");
        }
    }

    /**
     * 缓存命中字段的候选名单。
     *
     * <p>顺序即优先级：越靠前越权威。用 LinkedHashMap 保证遍历顺序稳定
     * （顺序影响「读到的字段名」，进而影响日志；虽然是诊断用，
     * 但保持确定性是原则）。</p>
     */
    private static final Map<String, Kind> HIT_FIELDS = new LinkedHashMap<>();

    /** 未命中字段候选（只有 DeepSeek 会单独给）。 */
    private static final Map<String, Kind> MISS_FIELDS = new LinkedHashMap<>();

    private enum Kind {
        /** 顶层字段，直接取 long。 */
        TOP_LEVEL,
        /** 在 prompt_tokens_details 对象里。 */
        PROMPT_DETAILS,
        /** 在 input_tokens_details 对象里。 */
        INPUT_DETAILS
    }

    static {
        // ---- 命中字段：按权威性排序 ----
        HIT_FIELDS.put("prompt_cache_hit_tokens", Kind.TOP_LEVEL);   // DeepSeek 官方
        HIT_FIELDS.put("prompt_tokens_details.cached_tokens", Kind.PROMPT_DETAILS); // OpenAI 官方
        HIT_FIELDS.put("input_tokens_details.cached_tokens", Kind.INPUT_DETAILS);
        HIT_FIELDS.put("cached_tokens", Kind.TOP_LEVEL);             // 大量中转站/国内厂商
        HIT_FIELDS.put("prompt_cached_tokens", Kind.TOP_LEVEL);
        HIT_FIELDS.put("cache_tokens", Kind.TOP_LEVEL);
        HIT_FIELDS.put("cache_read_input_tokens", Kind.TOP_LEVEL);   // Anthropic 转译
        HIT_FIELDS.put("prompt_cache_hit", Kind.TOP_LEVEL);

        // ---- 未命中字段 ----
        MISS_FIELDS.put("prompt_cache_miss_tokens", Kind.TOP_LEVEL); // DeepSeek 官方
        MISS_FIELDS.put("cache_miss_tokens", Kind.TOP_LEVEL);
    }

    /**
     * 命中 token 的字段名候选（供配置覆盖用）。
     *
     * @return 逗号分隔的候选名，顺序即优先级
     */
    public static String knownHitFields() {
        return String.join(", ", HIT_FIELDS.keySet());
    }

    private CacheUsageParser() {
    }

    /**
     * 解析 usage 对象。
     *
     * @param usage     服务端返回的 usage JSON 对象（可为 null）
     * @param completionFromCaller 调用方已解析出的 completion token（作为兜底）
     * @return 归一化结果，永不为 null
     */
    public static Result parse(com.google.gson.JsonObject usage, long completionFromCaller) {
        Result r = new Result();
        r.completionTokens = completionFromCaller;

        if (usage == null) {
            // 服务端没给 usage：全未知。不要把 prompt 记成 0 后算成 miss。
            return r;
        }

        r.promptTokens = getLong(usage, "prompt_tokens", getLong(usage, "input_tokens", 0));

        // ---- 命中 ----
        for (Map.Entry<String, Kind> e : HIT_FIELDS.entrySet()) {
            long v = read(usage, e.getKey(), e.getValue());
            if (v >= 0) {
                r.cachedTokens = v;
                r.sourceField = e.getKey();
                r.cacheReported = true;
                break;
            }
        }

        if (!r.cacheReported) {
            // 服务端完全没有缓存字段：标记未上报，交给调用方决定怎么统计。
            return r;
        }

        // ---- 未命中 ----
        long miss = -1;
        for (Map.Entry<String, Kind> e : MISS_FIELDS.entrySet()) {
            long v = read(usage, e.getKey(), e.getValue());
            if (v >= 0) {
                miss = v;
                break;
            }
        }
        if (miss >= 0) {
            r.missTokens = miss;
        } else if (r.promptTokens > 0) {
            // 只报了命中：未命中 = 总量 - 命中（下限 0）
            r.missTokens = Math.max(0, r.promptTokens - r.cachedTokens);
        } else {
            // 连 prompt 总量都没有，但报了命中：这是一些中转站的怪行为。
            // 此时无从推算未命中，标记为未上报更诚实。
            r.cacheReported = false;
            return r;
        }

        // 一致性校正：某些中转站把 cached 报得比 prompt 还大（bug）。
        if (r.promptTokens > 0 && r.cachedTokens > r.promptTokens) {
            r.cachedTokens = r.promptTokens;
            r.missTokens = 0;
        }
        return r;
    }

    /** 按 Kind 读取字段；不存在返回 -1。 */
    private static long read(com.google.gson.JsonObject usage, String path, Kind kind) {
        try {
            switch (kind) {
                case TOP_LEVEL:
                    return getLong(usage, path, -1);
                case PROMPT_DETAILS: {
                    if (!usage.has("prompt_tokens_details")) {
                        return -1;
                    }
                    var d = usage.get("prompt_tokens_details");
                    if (!d.isJsonObject()) {
                        return -1;
                    }
                    // path 形如 a.b，取最后一段
                    return getLong(d.getAsJsonObject(), tail(path), -1);
                }
                case INPUT_DETAILS: {
                    if (!usage.has("input_tokens_details")) {
                        return -1;
                    }
                    var d = usage.get("input_tokens_details");
                    if (!d.isJsonObject()) {
                        return -1;
                    }
                    return getLong(d.getAsJsonObject(), tail(path), -1);
                }
                default:
                    return -1;
            }
        } catch (Exception ignored) {
            return -1;
        }
    }

    private static String tail(String dotted) {
        int i = dotted.lastIndexOf('.');
        return i < 0 ? dotted : dotted.substring(i + 1);
    }

    private static long getLong(com.google.gson.JsonObject obj, String key, long def) {
        try {
            if (obj != null && obj.has(key) && !obj.get(key).isJsonNull()) {
                return obj.get(key).getAsLong();
            }
        } catch (Exception ignored) {
        }
        return def;
    }
}
