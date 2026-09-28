package com.example.aibot.llm;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 缓存命中统计器。
 *
 * <p>统计维度：</p>
 * <ul>
 *   <li>累计调用次数、成功/失败次数</li>
 *   <li>累计 prompt token、completion token</li>
 *   <li>累计缓存命中 token（DeepSeek: prompt_cache_hit_tokens / OpenAI: cached_tokens）</li>
 *   <li>累计缓存未命中 token（DeepSeek: prompt_cache_miss_tokens）</li>
 *   <li>费用估算（按可配置单价）</li>
 * </ul>
 *
 * <p>使用 AtomicLong 保证异步线程与主线程并发读写安全。</p>
 */
public final class CacheStats {

    private final AtomicLong totalCalls = new AtomicLong();
    private final AtomicLong successCalls = new AtomicLong();
    private final AtomicLong failedCalls = new AtomicLong();

    private final AtomicLong promptTokens = new AtomicLong();
    private final AtomicLong completionTokens = new AtomicLong();
    private final AtomicLong cachedTokens = new AtomicLong();
    private final AtomicLong missTokens = new AtomicLong();

    /** 单价的货币单位与 API 计费一致（DeepSeek 默认人民币元 / 百万 token）。 */
    private volatile double pricePerMillionInputMiss = 2.0;
    private volatile double pricePerMillionInputHit = 0.2;
    private volatile double pricePerMillionOutput = 8.0;

    /** 每调用多少次打印一次缓存命中率日志（需求 8）。 */
    public static final int LOG_INTERVAL = 100;

    /** 记录一次成功调用。 */
    public void recordSuccess(long prompt, long completion, long cached, long miss) {
        totalCalls.incrementAndGet();
        successCalls.incrementAndGet();
        promptTokens.addAndGet(Math.max(0, prompt));
        completionTokens.addAndGet(Math.max(0, completion));
        cachedTokens.addAndGet(Math.max(0, cached));
        missTokens.addAndGet(Math.max(0, miss));

        // 每 100 次调用输出一次命中率，便于持续优化
        long n = totalCalls.get();
        if (n % LOG_INTERVAL == 0) {
            java.util.logging.Logger.getLogger("aibot-cache").info(
                    "[AIBot] 缓存统计（第 " + n + " 次调用）：命中率=" + String.format("%.1f%%", hitRate() * 100)
                            + " 累计命中=" + cachedTokens.get() + " 未命中=" + missTokens.get());
        }
    }

    /** 记录一次失败调用（不产生 token 统计）。 */
    public void recordFailure() {
        totalCalls.incrementAndGet();
        failedCalls.incrementAndGet();
    }

    /**
     * 缓存命中率 = 命中 token / (命中 token + 未命中 token)。
     * 没有任何数据时返回 0。
     */
    public double hitRate() {
        long hit = cachedTokens.get();
        long miss = missTokens.get();
        long denom = hit + miss;
        if (denom <= 0) {
            return 0.0;
        }
        return (double) hit / (double) denom;
    }

    /**
     * 估算节省费用。
     *
     * <p>计算方式：假设所有命中 token 都按未命中价格计费会花多少，
     * 减去实际按命中价格计费的花费，即为节省金额。</p>
     */
    public double estimatedSavings() {
        double hitTokens = cachedTokens.get();
        double savedPerToken = (pricePerMillionInputMiss - pricePerMillionInputHit) / 1_000_000.0;
        return hitTokens * savedPerToken;
    }

    /** 估算累计总花费。 */
    public double estimatedCost() {
        double miss = missTokens.get() / 1_000_000.0 * pricePerMillionInputMiss;
        double hit = cachedTokens.get() / 1_000_000.0 * pricePerMillionInputHit;
        double out = completionTokens.get() / 1_000_000.0 * pricePerMillionOutput;
        return miss + hit + out;
    }

    /** 生成 /aibot cache stats 的输出文本。 */
    public String report() {
        StringBuilder sb = new StringBuilder();
        sb.append("===== AIBot 缓存统计 =====\n");
        sb.append("调用次数: ").append(totalCalls.get())
                .append("（成功 ").append(successCalls.get())
                .append(" / 失败 ").append(failedCalls.get()).append("）\n");
        sb.append("输入 token 总计: ").append(promptTokens.get()).append("\n");
        sb.append("输出 token 总计: ").append(completionTokens.get()).append("\n");
        sb.append("缓存命中 token: ").append(cachedTokens.get()).append("\n");
        sb.append("缓存未命中 token: ").append(missTokens.get()).append("\n");
        sb.append("缓存命中率: ").append(String.format("%.1f%%", hitRate() * 100)).append("\n");
        sb.append("估算节省: ").append(String.format("%.4f", estimatedSavings())).append("\n");
        sb.append("估算总花费: ").append(String.format("%.4f", estimatedCost())).append("\n");

        // 命中率过低时给出明确提示（需求 9）
        if (missTokens.get() + cachedTokens.get() > 0 && hitRate() < 0.5) {
            sb.append("\n[警告] 缓存命中率低于 50%。请检查：\n");
            sb.append("  1. 系统提示词（StaticPrefix.SYSTEM_PROMPT）是否被改动过；\n");
            sb.append("  2. 动态内容是否被误放进了静态前缀；\n");
            sb.append("  3. 状态 JSON 的字段顺序是否稳定；\n");
            sb.append("  4. 是否更换了 model 或 baseUrl（换模型必然冷启动）。\n");
        }
        return sb.toString();
    }

    /** 生成 /aibot status 用的一行摘要。 */
    public String shortSummary() {
        return String.format("缓存命中率 %.1f%%（命中 %d / 未命中 %d，共 %d 次调用）",
                hitRate() * 100, cachedTokens.get(), missTokens.get(), totalCalls.get());
    }

    /** 重置所有统计（/aibot cache reset）。 */
    public void reset() {
        totalCalls.set(0);
        successCalls.set(0);
        failedCalls.set(0);
        promptTokens.set(0);
        completionTokens.set(0);
        cachedTokens.set(0);
        missTokens.set(0);
    }

    // 供命令层读取的只读访问器
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
        return cachedTokens.get();
    }

    public long getMissTokens() {
        return missTokens.get();
    }
}
