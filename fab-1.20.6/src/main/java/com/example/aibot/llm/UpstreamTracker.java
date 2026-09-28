package com.example.aibot.llm;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 上游追踪器：诊断「中转站轮询导致缓存失效」这个最难查的问题。
 *
 * <p><b>它解决什么</b>：很多中转站背后挂着多个上游账号（甚至多个厂商），
 * 请求会被轮流转发。而 prompt cache 是<b>跟着上游实例走的</b> ——
 * 这次命中在 A 上游建立了缓存，下次被转到 B 上游就是全新冷启动。</p>
 *
 * <p>表现就是：静态前缀设计得再完美，命中率也只有 1/N（N 是上游数量）。
 * 用户会以为是自己配置错了，反复改提示词，永远修不好。</p>
 *
 * <p><b>本类的做法</b>：记录每次请求识别到的上游标识与它的缓存表现，
 * 当检测到「多上游轮询」时，直接给出结论与建议，
 * 而不是让用户去猜。</p>
 *
 * <p>注意：上游标识来自响应头，不同中转站暴露程度不同。
 * 拿不到标识时本类会如实说明「无法判断」，不会瞎猜。</p>
 */
public final class UpstreamTracker {

    /** 单个上游的统计。 */
    public static final class UpstreamStat {
        public final String id;
        public long calls;
        public long reportedCalls;
        public long hitTokens;
        public long missTokens;

        UpstreamStat(String id) {
            this.id = id;
        }

        /** 该上游自身的缓存命中率；无数据返回 -1。 */
        public double hitRate() {
            long d = hitTokens + missTokens;
            return d <= 0 ? -1.0 : (double) hitTokens / (double) d;
        }

        public String shortLine() {
            double hr = hitRate();
            return "  · " + id + "：调用 " + calls + " 次，命中率 "
                    + (hr < 0 ? "未知" : String.format(java.util.Locale.ROOT, "%.1f%%", hr * 100));
        }
    }

    /** 上游标识 → 统计。最多保留若干个，防止长时间运行后无限增长。 */
    private final Map<String, UpstreamStat> upstreams = new LinkedHashMap<>();

    private static final int MAX_UPSTREAMS = 16;

    /** 总共观测到的请求数。 */
    private long totalObserved = 0;
    /** 能识别出标识的请求数（用于判断「能否诊断」）。 */
    private long identifiable = 0;

    /**
     * 记录一次请求的上游表现。
     *
     * @param upstreamId   上游标识（"(无标识)" 表示无法识别）
     * @param cacheReported 本次是否上报了缓存信息
     * @param hitTokens    命中 token
     * @param missTokens   未命中 token
     */
    public synchronized void record(String upstreamId, boolean cacheReported,
                                    long hitTokens, long missTokens) {
        totalObserved++;
        String id = (upstreamId == null || upstreamId.isBlank()) ? "(无标识)" : upstreamId;
        boolean unidentifiable = "(无标识)".equals(id);
        if (!unidentifiable) {
            identifiable++;
        }

        UpstreamStat st = upstreams.get(id);
        if (st == null) {
            if (upstreams.size() >= MAX_UPSTREAMS) {
                // 超过上限：不再新增，避免内存与展示无限膨胀。
                // 仍然计入总数，只是不单独统计。
                return;
            }
            st = new UpstreamStat(id);
            upstreams.put(id, st);
        }
        st.calls++;
        if (cacheReported) {
            st.reportedCalls++;
            st.hitTokens += Math.max(0, hitTokens);
            st.missTokens += Math.max(0, missTokens);
        }
    }

    /** 观测到的不同上游数量。 */
    public synchronized int distinctUpstreams() {
        return upstreams.size();
    }

    /**
     * 是否很可能存在「多上游轮询」。
     *
     * <p>判据：识别出 2 个以上不同上游，且样本量足够
     * （避免刚开服几条请求就误报）。</p>
     */
    public synchronized boolean likelyRotating() {
        return distinctUpstreams() >= 2 && totalObserved >= 6;
    }

    /** 能否做出上游层面的诊断（样本里有可识别的标识）。 */
    public synchronized boolean canDiagnose() {
        return identifiable >= 3;
    }

    /**
     * 生成诊断文本，直接告诉用户命中率为什么低。
     */
    public synchronized String diagnose() {
        StringBuilder sb = new StringBuilder();
        sb.append("===== 上游追踪诊断 =====\n");
        sb.append("观测请求数: ").append(totalObserved)
                .append("（可识别上游 ").append(identifiable).append(" 次）\n");
        sb.append("识别到的上游数: ").append(upstreams.size()).append("\n");

        if (upstreams.isEmpty()) {
            sb.append("\n尚无数据。先让智能体跑一会儿再来看。\n");
            return sb.toString();
        }

        sb.append("\n各上游表现:\n");
        List<UpstreamStat> list = new ArrayList<>(upstreams.values());
        // 按调用次数降序，方便看主要走哪个上游
        list.sort((a, b) -> Long.compare(b.calls, a.calls));
        for (UpstreamStat st : list) {
            sb.append(st.shortLine()).append('\n');
        }

        sb.append('\n');
        if (!canDiagnose()) {
            sb.append("【结论】响应中缺少可识别的上游标识，无法判断是否轮询。\n");
            sb.append("  说明：很多中转站不暴露上游信息，这是正常的。\n");
            sb.append("  若命中率长期偏低，可直接询问你的中转站：\n");
            sb.append("  「同一 API Key 的请求是否会被转发到不同上游？」\n");
        } else if (likelyRotating()) {
            sb.append("【结论】检测到多个上游 —— 你的中转站很可能在做请求轮询。\n");
            sb.append("  这会让 prompt cache 无法复用：在 A 上游建立的缓存，\n");
            sb.append("  请求被转到 B 上游时就是全新冷启动，命中率天然只有约 1/N。\n");
            sb.append("  这是中转站架构决定的，本模组无法绕过。\n");
            sb.append("\n  可行的改善方向：\n");
            sb.append("  1. 询问中转站是否支持「会话粘性 / 固定上游」（sticky session），\n");
            sb.append("     这是唯一能根治的办法；\n");
            sb.append("  2. 换一个承诺「不轮询、或不换上游」的中转站；\n");
            sb.append("  3. 直接用上游厂商官方 API（命中率最高最稳）。\n");
        } else {
            sb.append("【结论】上游保持稳定，缓存失效不是轮询造成的。\n");
            sb.append("  若命中率仍低，请用 /aibot cache probe 查看 usage 原文，\n");
            sb.append("  确认服务端到底有没有上报缓存字段。\n");
        }
        return sb.toString();
    }

    /** 一行摘要（状态页用）。 */
    public synchronized String shortSummary() {
        int n = upstreams.size();
        if (n == 0) {
            return "无数据";
        }
        if (likelyRotating()) {
            return "检测到 " + n + " 个上游（疑似轮询，会破坏缓存）";
        }
        if (n == 1) {
            return "单上游，稳定";
        }
        return n + " 个上游";
    }

    public synchronized void reset() {
        upstreams.clear();
        totalObserved = 0;
        identifiable = 0;
    }
}
