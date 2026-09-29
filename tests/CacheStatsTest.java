package com.example.aibot.llm;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * CacheStats 测试：验证命中率计算不会被「未上报」请求污染。
 *
 * <p>这是本版最重要的行为修正：旧版把未上报当成 0 命中，
 * 导致中转站用户看到假的很低命中率。</p>
 */
public class CacheStatsTest {

    private static int passed = 0, failed = 0;

    public static void main(String[] args) {
        System.out.println("===== CacheStats 测试 =====");

        // 场景 1：全部上报且高命中
        CacheStats s1 = new CacheStats();
        for (int i = 0; i < 10; i++) {
            s1.recordSuccess(parse("{\"prompt_tokens\":1000,\"completion_tokens\":20,"
                    + "\"prompt_cache_hit_tokens\":950,\"prompt_cache_miss_tokens\":50}", 20));
        }
        check("全是命中 -> 95%", Math.abs(s1.hitRate() - 0.95) < 0.001);
        check("有缓存数据", s1.hasCacheData());
        check("未上报计数为 0", s1.getUnreportedCalls() == 0);

        // 场景 2：一半上报一半未上报 —— 命中率必须只按上报的算
        CacheStats s2 = new CacheStats();
        for (int i = 0; i < 10; i++) {
            s2.recordSuccess(parse("{\"prompt_tokens\":1000,\"completion_tokens\":20,"
                    + "\"prompt_cache_hit_tokens\":900,\"prompt_cache_miss_tokens\":100}", 20));
        }
        for (int i = 0; i < 10; i++) {
            // 完全没缓存字段
            s2.recordSuccess(parse("{\"prompt_tokens\":1000,\"completion_tokens\":20}", 20));
        }
        check("混杂场景命中率仍为 90%（不被未上报稀释）",
                Math.abs(s2.hitRate() - 0.90) < 0.001);
        check("未上报计数为 10", s2.getUnreportedCalls() == 10);

        // 场景 3：从未上报 -> 命中率必须是 -1（未知），不是 0
        CacheStats s3 = new CacheStats();
        for (int i = 0; i < 5; i++) {
            s3.recordSuccess(parse("{\"prompt_tokens\":800,\"completion_tokens\":10}", 10));
        }
        check("全未上报 -> 命中率为 -1（未知）", s3.hitRate() < 0);
        check("全未上报 -> hasCacheData=false", !s3.hasCacheData());
        check("摘要写明未上报", s3.shortSummary().contains("未上报"));

        // 场景 4：失败调用不污染 token 统计
        CacheStats s4 = new CacheStats();
        s4.recordFailure();
        s4.recordFailure();
        check("失败不计入 prompt token", s4.getPromptTokens() == 0);
        check("失败计数正确", s4.getFailedCalls() == 2);

        // 场景 5：费用估算（命中比未命中便宜，节省必须为正）
        CacheStats s5 = new CacheStats();
        s5.setPrices(2.0, 0.2, 8.0);
        s5.recordSuccess(parse("{\"prompt_tokens\":1000000,\"completion_tokens\":1000,"
                + "\"prompt_cache_hit_tokens\":900000,\"prompt_cache_miss_tokens\":100000}", 1000));
        check("节省为正", s5.estimatedSavings() > 0);
        // 900000 命中 * (2.0-0.2)/1e6 = 1.62
        check("节省金额正确约 1.62", Math.abs(s5.estimatedSavings() - 1.62) < 0.01);

        // 场景 6：reset 后一切归零
        s5.reset();
        check("reset 后无数据", !s5.hasCacheData());
        check("reset 后调用数为 0", s5.getTotalCalls() == 0);

        System.out.println();
        System.out.println("通过 " + passed + " / 失败 " + failed);
        if (failed > 0) {
            System.exit(1);
        }
        System.out.println("全部通过 ✔");
    }

    private static CacheUsageParser.Result parse(String usageJson, long completion) {
        JsonObject u = JsonParser.parseString(usageJson).getAsJsonObject();
        return CacheUsageParser.parse(u, completion);
    }

    private static void check(String name, boolean ok) {
        if (ok) {
            passed++;
            System.out.println("  ✔ " + name);
        } else {
            failed++;
            System.out.println("  ✘ " + name);
        }
    }
}
