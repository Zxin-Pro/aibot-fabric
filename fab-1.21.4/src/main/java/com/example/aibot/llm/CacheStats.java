package com.example.aibot.llm;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 缓存命中统计器。
 *
 * <p><b>设计要点（这一版重写过，原因记在下面）</b>：</p>
 *
 * <p>旧版把「服务端没上报缓存字段」和「上报了但全未命中」当成同一件事，
 * 都记成 cached=0、miss=prompt。这在官方 API 上没问题，
 * 但本模组主要面向<b>中转站</b> —— 大量中转站根本不返回缓存字段。
 * 结果是：明明命中了缓存，统计里的命中率却被这些「未知」请求
 * 稀释成一个难看的低值，用户据此以为缓存没生效，白白折腾。</p>
 *
 * <p>现在把统计分成三个桶：</p>
 * <ul>
 *   <li><b>上报</b>：服务端明确给了缓存信息 → 计入命中率分母</li>
 *   <li><b>未上报</b>：服务端没给 → <b>不计入</b>命中率分母，单独展示，
 *       并用「prompt token 总量」提示用户这部分的规模</li>
 * </ul>
 *
 * <p>另外提供 {@link #estimatedSavings()} 的费用估算，
 * 单价可配置（中转站价格与官方差异极大，不能写死）。</p>
 */
public final class CacheStats {

    private final AtomicLong totalCalls = new AtomicLong();
    private final AtomicLong successCalls = new AtomicLong();
    private final AtomicLong failedCalls = new AtomicLong();

    private final AtomicLong promptTokens = new AtomicLong();
    private final AtomicLong completionTokens = new AtomicLong();

    /** 有缓存上报的请求所贡献的命中/未命中 token（命中率的分母）。 */
    private final AtomicLong reportedHitTokens = new AtomicLong();
    private final AtomicLong reportedMissTokens = new AtomicLong();

    /** 没有缓存上报的请求数与其 prompt token 量（不进命中率分母）。 */
    private final AtomicLong unreportedCalls = new AtomicLong();
    private final AtomicLong unreportedPromptTokens = new AtomicLong();

    /** 最近一次实际读到的字段名，用于诊断（告诉用户缓存信息从哪来的）。 */
    private volatile String lastSourceField = "";

    /**
     * 单价（货币单位与 API 计费一致）。
     *
     * <p>默认按 DeepSeek 官方价（人民币元 / 百万 token）：命中 0.2、未命中 2、输出 8。
     * 中转站价格差异极大，因此这些值由 {@link com.example.aibot.config.AIConfig}
     * 提供，会随配置更新。</p>
     */
    private volatile double pricePerMillionInputMiss = 2.0;
    private volatile double pricePerMillionInputHit = 0.2;
    private volatile double pricePerMillionOutput = 8.0;

    /** 每调用多少次打印一次缓存命中率日志。 */
    public static final int LOG_INTERVAL = 100;

    /**
     * 记录一次成功调用。
     *
     * @param u 归一化后的用量（由 {@link CacheUsageParser} 产出）
     */
    public void recordSuccess(CacheUsageParser.Result u) {
        totalCalls.incrementAndGet();
        successCalls.incrementAndGet();

        promptTokens.addAndGet(Math.max(0, u.promptTokens));
        completionTokens.addAndGet(Math.max(0, u.completionTokens));

        if (u.cacheReported) {
            reportedHitTokens.addAndGet(Math.max(0, u.cachedTokens));
            reportedMissTokens.addAndGet(Math.max(0, u.missTokens));
            if (u.sourceField != null && !u.sourceField.isEmpty()) {
                lastSourceField = u.sourceField;
            }
        } else {
            unreportedCalls.incrementAndGet();
            unreportedPromptTokens.addAndGet(Math.max(0, u.promptTokens));
        }

        long n = totalCalls.get();
        if (n % LOG_INTERVAL == 0) {
            java.util.logging.Logger.getLogger("aibot-cache").info(
                    "[AIBot] 缓存统计（第 " + n + " 次调用）：命中率="
                            + String.format(java.util.Locale.ROOT, "%.1f%%", hitRate() * 100)
                            + "（仅统计有上报的请求）"
                            + " 命中=" + reportedHitTokens.get()
                            + " 未命中=" + reportedMissTokens.get()
                            + " 未上报请求=" + unreportedCalls.get());
        }
    }

    /** 记录一次失败调用（不产生 token 统计）。 */
    public void recordFailure() {
        totalCalls.incrementAndGet();
        failedCalls.incrementAndGet();
    }

    /**
     * 缓存命中率 = 命中 / (命中 + 未命中)，<b>仅统计有缓存上报的请求</b>。
     *
     * <p>没有任何上报数据时返回 -1 表示「未知」，而不是 0。
     * 展示层据此显示「未知（服务端未上报）」。</p>
     */
    public double hitRate() {
        long hit = reportedHitTokens.get();
        long miss = reportedMissTokens.get();
        long denom = hit + miss;
        if (denom <= 0) {
            return -1.0;
        }
        return (double) hit / (double) denom;
    }

    /** 是否至少有一次请求提供了缓存信息。 */
    public boolean hasCacheData() {
        return reportedHitTokens.get() + reportedMissTokens.get() > 0;
    }

    /** 估算节省费用（命中价与未命中价之差 × 命中量）。 */
    public double estimatedSavings() {
        double hitTokens = reportedHitTokens.get();
        double savedPerToken = (pricePerMillionInputMiss - pricePerMillionInputHit) / 1_000_000.0;
        return hitTokens * savedPerToken;
    }

    /** 估算累计总花费。 */
    public double estimatedCost() {
        double miss = reportedMissTokens.get() / 1_000_000.0 * pricePerMillionInputMiss;
        double hit = reportedHitTokens.get() / 1_000_000.0 * pricePerMillionInputHit;
        double out = completionTokens.get() / 1_000_000.0 * pricePerMillionOutput;
        // 未上报的请求按未命中价保守估算（我们无法知道它到底有没有命中）
        double unknown = unreportedPromptTokens.get() / 1_000_000.0 * pricePerMillionInputMiss;
        return miss + hit + out + unknown;
    }

    /** 更新单价（来自配置）。 */
    public void setPrices(double miss, double hit, double output) {
        if (miss > 0) {
            this.pricePerMillionInputMiss = miss;
        }
        if (hit >= 0) {
            this.pricePerMillionInputHit = hit;
        }
        if (output > 0) {
            this.pricePerMillionOutput = output;
        }
    }

    /** 生成诊断报告文本。 */
    public String report() {
        StringBuilder sb = new StringBuilder();
        sb.append("===== AIBot 缓存统计 =====\n");
        sb.append("调用次数: ").append(totalCalls.get())
                .append("（成功 ").append(successCalls.get())
                .append(" / 失败 ").append(failedCalls.get()).append("）\n");
        sb.append("输入 token 总计: ").append(promptTokens.get()).append("\n");
        sb.append("输出 token 总计: ").append(completionTokens.get()).append("\n");

        if (hasCacheData()) {
            sb.append("缓存命中 token: ").append(reportedHitTokens.get()).append("\n");
            sb.append("缓存未命中 token: ").append(reportedMissTokens.get()).append("\n");
            sb.append("缓存命中率: ")
                    .append(String.format(java.util.Locale.ROOT, "%.1f%%", hitRate() * 100))
                    .append("（仅统计 ").append(successCalls.get() - unreportedCalls.get())
                    .append(" 次有上报的请求）\n");
            if (!lastSourceField.isEmpty()) {
                sb.append("命中字段来源: entry[").append(lastSourceField).append("]\n");
            }
        } else {
            sb.append("缓存命中率: 未知（服务端未上报任何缓存字段）\n");
        }

        if (unreportedCalls.get() > 0) {
            sb.append("未上报缓存的请求: ").append(unreportedCalls.get())
                    .append(" 次（涉及 ").append(unreportedPromptTokens.get())
                    .append(" prompt token，未计入命中率）\n");
        }

        sb.append("估算节省: ").append(String.format(java.util.Locale.ROOT, "%.4f", estimatedSavings())).append("\n");
        sb.append("估算总花费: ").append(String.format(java.util.Locale.ROOT, "%.4f", estimatedCost())).append("\n");

        // 诊断建议：只在「有上报数据且命中率明显偏低」时提示
        if (hasCacheData() && hitRate() >= 0 && hitRate() < 0.5) {
            sb.append("\n[诊断] 命中率偏低，按顺序排查：\n");
            sb.append("  1. 静态前缀是否被改动（改一次 = 全线缓存冷启动）；\n");
            sb.append("  2. 是否切换了 model 或 baseUrl（换模型必然冷启动）；\n");
            sb.append("  3. 中转站是否做了「多上游轮询」——\n");
            sb.append("     请求被随机转发到不同上游时，缓存无法复用，命中率会天然很低。\n");
            sb.append("     这是中转站架构决定的，与本模组无关。\n");
            sb.append("  4. 用 /aibot cache probe 查看最近一次请求读到的字段名。\n");
        } else if (!hasCacheData() && totalCalls.get() > 0) {
            sb.append("\n[诊断] 服务端从未返回缓存字段，说明：\n");
            sb.append("  · 该中转站/上游未实现 prompt caching，或\n");
            sb.append("  · 它实现了缓存但不在 usage 里暴露统计（省钱仍然生效，只是看不到数）。\n");
            sb.append("  用 /aibot cache probe 可查看最近一次请求的 usage 原文。\n");
        }
        return sb.toString();
    }

    /** 生成状态页用的一行摘要。 */
    public String shortSummary() {
        if (!hasCacheData()) {
            if (totalCalls.get() == 0) {
                return "尚无调用";
            }
            return "未知（服务端未上报缓存字段，" + unreportedCalls.get() + " 次请求）";
        }
        return String.format(java.util.Locale.ROOT,
                "命中率 %.1f%%（命中 %d / 未命中 %d）%s",
                hitRate() * 100, reportedHitTokens.get(), reportedMissTokens.get(),
                unreportedCalls.get() > 0 ? "，另有 " + unreportedCalls.get() + " 次未上报" : "");
    }

    /** 重置所有统计。 */
    public void reset() {
        totalCalls.set(0);
        successCalls.set(0);
        failedCalls.set(0);
        promptTokens.set(0);
        completionTokens.set(0);
        reportedHitTokens.set(0);
        reportedMissTokens.set(0);
        unreportedCalls.set(0);
        unreportedPromptTokens.set(0);
        lastSourceField = "";
    }

    public String lastSourceField() {
        return lastSourceField;
    }

    public long getTotalCalls() {
        return totalCalls.get();
    }

    public long getSuccessCalls() {
        return successCalls.get();
    }

    public long getFailedCalls() {
        return failedCalls.get();
    }

    public long getPromptTokens() {
        return promptTokens.get();
    }

    public long getCompletionTokens() {
        return completionTokens.get();
    }

    public long getCachedTokens() {
        return reportedHitTokens.get();
    }

    public long getMissTokens() {
        return reportedMissTokens.get();
    }

    public long getUnreportedCalls() {
        return unreportedCalls.get();
    }
}
