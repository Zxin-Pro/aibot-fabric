package com.example.aibot.memory;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * 上下文压缩器：让 AIBot 可以「一直玩下去」而不撑爆上下文窗口。
 *
 * <p><b>为什么必须有它</b>：一个真正长期自主运行的智能体，玩几小时就是几千步。
 * 如果把每一步都塞进提示词，上下文会无限膨胀，最后必然触发 API 上限、
 * 不但贵而且模型会被无关的陈旧细节淹没、判断力下降。</p>
 *
 * <p><b>压缩策略（三层，从便宜到贵）：</b></p>
 * <ol>
 *   <li><b>滑窗</b>：只保留最近 N 条完整动作记录（细节），更早的丢弃。</li>
 *   <li><b>摘要</b>：被滑窗挤出去的记录，按「阶段」聚合成一句摘要，
 *       保留「做过什么、成了没有」的结论，丢掉逐条流水账。</li>
 *   <li><b>预算截断</b>：按估算 token 数硬性裁剪，逼近上限时优先丢最早的摘要。</li>
 * </ol>
 *
 * <p><b>缓存友好的关键</b>：压缩只在「窗口滑出」时发生，
 * 且输出格式完全确定（无哈希、无时间戳、无随机顺序），
 * 因此同一状态下反复构建的提示词字节完全一致，前缀缓存不会被打穿。</p>
 *
 * <p><b>不调用 LLM</b>：这里的摘要用确定性规则聚合，不额外发请求。
 * 原因有二：一是每轮都调用摘要会额外花钱且变慢；
 * 二是规则聚合的结果稳定，而 LLM 摘要每次措辞不同，会破坏提示词缓存的字节一致性。</p>
 */
public final class ContextCompressor {

    /** 保留完整细节的最近记录条数。 */
    public static final int DEFAULT_WINDOW = 12;

    /** 摘要最多保留条数（阶段数），超出后更早的合并成「早期历史」。 */
    public static final int MAX_SUMMARIES = 16;

    /** 动态部分的目标 token 预算（估算值）。超出后开始裁剪。 */
    public static final int DEFAULT_TOKEN_BUDGET = 1500;

    /**
     * 粗略的 token 估算：中文约 1 字 1 token，英文约 4 字符 1 token。
     *
     * <p>刻意高估而非低估 —— 高估只是浪费一点预算，
     * 低估会导致请求超限被 API 拒绝。</p>
     */
    public static int estimateTokens(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int cjk = 0;
        int other = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            // CJK 统一表意文字 + 中文标点区间
            if (c >= 0x4E00 && c <= 0x9FFF || c >= 0x3000 && c <= 0x303F
                    || c >= 0xFF00 && c <= 0xFFEF) {
                cjk++;
            } else {
                other++;
            }
        }
        // 中文 1 字 ≈ 1 token；其他字符 4 个 ≈ 1 token
        return cjk + (other / 4) + 1;
    }

    /** 被压缩掉的一条历史（摘要单元）。 */
    private static final class Summary {
        /** 覆盖的步数区间起点。 */
        long fromStep;
        /** 覆盖的步数区间终点。 */
        long toStep;
        /** 主要动作类型（出现次数最多的动作）。 */
        String dominantAction = "";
        /** 成功次数。 */
        int success;
        /** 失败次数。 */
        int failure;
        /** 有代表性的失败原因（最多保留两条）。 */
        final List<String> reasons = new ArrayList<>();

        /** 渲染成一行固定格式的摘要。 */
        String render() {
            StringBuilder sb = new StringBuilder();
            sb.append("步骤 ").append(fromStep).append('-').append(toStep).append("：");
            if (!dominantAction.isEmpty()) {
                sb.append("主要在做 ").append(dominantAction).append("，");
            }
            sb.append("成功 ").append(success).append(" 次");
            if (failure > 0) {
                sb.append("、失败 ").append(failure).append(" 次");
            }
            if (!reasons.isEmpty()) {
                sb.append("（").append(String.join("；", reasons)).append("）");
            }
            return sb.toString();
        }
    }

    /** 已压缩的历史摘要（最早的在队首）。 */
    private final Deque<Summary> summaries = new ArrayDeque<>();

    /** 待聚合的缓冲：从短期记忆滑出的记录先攒着，攒够一批再合成摘要。 */
    private final List<ShortTermMemory.Entry> pending = new ArrayList<>();

    /** 每攒够多少条滑出记录就合成一条摘要。 */
    private static final int SUMMARY_BATCH = 5;

    private final int window;
    private final int tokenBudget;

    /** 累计压缩掉的原始记录数（用于 /aibot status 展示「省了多少」）。 */
    private long compressedCount = 0;

    public ContextCompressor() {
        this(DEFAULT_WINDOW, DEFAULT_TOKEN_BUDGET);
    }

    public ContextCompressor(int window, int tokenBudget) {
        this.window = Math.max(4, window);
        this.tokenBudget = Math.max(200, tokenBudget);
    }

    /**
     * 把短期记忆压缩成「最近细节 + 历史摘要」两段文本。
     *
     * @param shortTerm 短期记忆（全量）
     * @return 压缩后的文本，可直接放进提示词
     */
    public synchronized String compress(ShortTermMemory shortTerm) {
        List<ShortTermMemory.Entry> all = shortTerm.snapshot();

        // 1. 找出应该滑出窗口的记录，交给缓冲聚合
        int overflow = all.size() - window;
        if (overflow > 0) {
            absorb(all.subList(0, overflow));
        }

        // 2. 渲染：摘要在前（旧），最近细节在后（新）
        StringBuilder sb = new StringBuilder(1024);

        if (!summaries.isEmpty()) {
            sb.append("【历史摘要（更早的经历，已压缩）】\n");
            for (Summary s : summaries) {
                sb.append("  ").append(s.render()).append('\n');
            }
        }

        sb.append("【最近细节】\n");
        List<ShortTermMemory.Entry> recent = overflow > 0
                ? new ArrayList<>(all.subList(overflow, all.size()))
                : all;
        if (recent.isEmpty()) {
            sb.append("  (暂无)\n");
        } else {
            for (ShortTermMemory.Entry e : recent) {
                sb.append("  #").append(e.step())
                        .append(e.success() ? " [成功] " : " [失败] ")
                        .append(e.action());
                if (!e.params().isEmpty()) {
                    sb.append('(').append(e.params()).append(')');
                }
                sb.append(" -> ").append(e.result()).append('\n');
            }
        }

        return enforceBudget(sb.toString().trim());
    }

    /**
     * 把滑出窗口的记录吸收进摘要缓冲，攒够一批就合成摘要。
     */
    private void absorb(List<ShortTermMemory.Entry> overflowed) {
        for (ShortTermMemory.Entry e : overflowed) {
            // 避免重复吸收（compress 会被反复调用）
            if (!pending.isEmpty() && e.step() <= pending.get(pending.size() - 1).step()) {
                continue;
            }
            pending.add(e);
            compressedCount++;
        }
        while (pending.size() >= SUMMARY_BATCH) {
            List<ShortTermMemory.Entry> batch = new ArrayList<>(pending.subList(0, SUMMARY_BATCH));
            pending.subList(0, SUMMARY_BATCH).clear();
            summaries.addLast(buildSummary(batch));
            while (summaries.size() > MAX_SUMMARIES) {
                summaries.removeFirst();
            }
        }
    }

    /** 把一批记录聚合成一条摘要。 */
    private Summary buildSummary(List<ShortTermMemory.Entry> batch) {
        Summary s = new Summary();
        s.fromStep = batch.get(0).step();
        s.toStep = batch.get(batch.size() - 1).step();

        // 统计各动作出现次数，取最多的作为主要动作
        java.util.LinkedHashMap<String, Integer> counts = new java.util.LinkedHashMap<>();
        for (ShortTermMemory.Entry e : batch) {
            counts.merge(e.action(), 1, Integer::sum);
            if (e.success()) {
                s.success++;
            } else {
                s.failure++;
                // 收集代表性失败原因，最多两条且去重
                if (s.reasons.size() < 2 && !e.result().isEmpty()
                        && !s.reasons.contains(e.result())) {
                    s.reasons.add(truncate(e.result(), 24));
                }
            }
        }
        int best = -1;
        for (java.util.Map.Entry<String, Integer> en : counts.entrySet()) {
            if (en.getValue() > best) {
                best = en.getValue();
                s.dominantAction = en.getKey();
            }
        }
        return s;
    }

    /**
     * 按 token 预算裁剪：超预算时优先丢弃最早的摘要（细节更宝贵，最后才动）。
     */
    private String enforceBudget(String text) {
        if (estimateTokens(text) <= tokenBudget) {
            return text;
        }
        // 逐步丢弃最早的摘要，直到落进预算
        while (!summaries.isEmpty() && estimateTokens(text) > tokenBudget) {
            summaries.removeFirst();
            text = rebuildWithoutOldest(text);
        }
        if (estimateTokens(text) <= tokenBudget) {
            return text;
        }
        // 摘要丢光了还是超：只能硬截断细节（保留尾部，因为最近的最重要）
        int approxChars = tokenBudget * 2;
        if (text.length() > approxChars) {
            return "（早期内容因超出上下文预算已省略）\n"
                    + text.substring(text.length() - approxChars);
        }
        return text;
    }

    /** 丢掉当前文本里最旧的一条摘要行，返回重建后的文本。 */
    private String rebuildWithoutOldest(String text) {
        int start = text.indexOf("【历史摘要");
        if (start < 0) {
            return text;
        }
        int detailStart = text.indexOf("【最近细节】");
        if (detailStart < 0) {
            return text;
        }
        // 摘要在中间：去掉紧跟标题的第一行摘要
        int firstLineEnd = text.indexOf('\n', start);
        if (firstLineEnd < 0 || firstLineEnd >= detailStart) {
            return text;
        }
        int secondLineEnd = text.indexOf('\n', firstLineEnd + 1);
        if (secondLineEnd < 0 || secondLineEnd > detailStart) {
            // 只剩最后一条摘要：整个标题块去掉
            return text.substring(0, start) + text.substring(detailStart);
        }
        return text.substring(0, firstLineEnd + 1) + text.substring(secondLineEnd + 1);
    }

    /** 清空压缩状态（/aibot memory clear 时调用）。 */
    public synchronized void clear() {
        summaries.clear();
        pending.clear();
        compressedCount = 0;
    }

    /** 已压缩掉的原始记录数。 */
    public synchronized long getCompressedCount() {
        return compressedCount;
    }

    /** 已生成的摘要条数。 */
    public synchronized int getSummaryCount() {
        return summaries.size();
    }

    /** 供状态展示的一行摘要。 */
    public synchronized String shortSummary() {
        return "已压缩 " + compressedCount + " 条历史 → " + summaries.size()
                + " 条摘要（窗口 " + window + " 条，预算 " + tokenBudget + " token）";
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
