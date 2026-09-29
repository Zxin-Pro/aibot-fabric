package com.example.aibot.llm;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * CacheUsageParser 的独立测试（不依赖 Minecraft）。
 *
 * <p>运行方式见仓库根目录 tools/run-tests.sh。
 * 这是纯 Java，可以直接用 javac + java 跑。</p>
 */
public class CacheParserTest {

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) {
        System.out.println("===== CacheUsageParser 测试 =====");

        deepseekStyle();
        openaiStyle();
        relayTopLevel();
        anthropicStyle();
        notReported();
        malformedCachedGreaterThanPrompt();
        hitOnlyNoPrompt();
        wrappedDetails();

        System.out.println();
        System.out.println("通过 " + passed + " / 失败 " + failed);
        if (failed > 0) {
            System.exit(1);
        }
        System.out.println("全部通过 ✔");
    }

    /** DeepSeek 官方：prompt_cache_hit_tokens / prompt_cache_miss_tokens */
    private static void deepseekStyle() {
        JsonObject u = parse("{\"prompt_tokens\":1000,\"completion_tokens\":50,"
                + "\"prompt_cache_hit_tokens\":900,\"prompt_cache_miss_tokens\":100}");
        CacheUsageParser.Result r = CacheUsageParser.parse(u, 50);
        check("DeepSeek 命中", r.cachedTokens == 900);
        check("DeepSeek 未命中", r.missTokens == 100);
        check("DeepSeek 已上报", r.cacheReported);
        check("DeepSeek 字段名", r.sourceField.equals("prompt_cache_hit_tokens"));
    }

    /** OpenAI 官方：prompt_tokens_details.cached_tokens */
    private static void openaiStyle() {
        JsonObject u = parse("{\"prompt_tokens\":1000,\"completion_tokens\":50,"
                + "\"prompt_tokens_details\":{\"cached_tokens\":800}}");
        CacheUsageParser.Result r = CacheUsageParser.parse(u, 50);
        check("OpenAI 命中", r.cachedTokens == 800);
        check("OpenAI 未命中推算", r.missTokens == 200);
        check("OpenAI 已上报", r.cacheReported);
    }

    /** 大量中转站：顶层 cached_tokens */
    private static void relayTopLevel() {
        JsonObject u = parse("{\"prompt_tokens\":2000,\"completion_tokens\":30,\"cached_tokens\":1500}");
        CacheUsageParser.Result r = CacheUsageParser.parse(u, 30);
        check("中转站顶层命中", r.cachedTokens == 1500);
        check("中转站未命中推算", r.missTokens == 500);
    }

    /** Anthropic 转译风格 */
    private static void anthropicStyle() {
        JsonObject u = parse("{\"prompt_tokens\":500,\"completion_tokens\":20,"
                + "\"cache_read_input_tokens\":400}");
        CacheUsageParser.Result r = CacheUsageParser.parse(u, 20);
        check("Anthropic 命中", r.cachedTokens == 400);
        check("Anthropic 已上报", r.cacheReported);
    }

    /** 完全未上报：必须标记为「未知」，不能谎报 0 */
    private static void notReported() {
        JsonObject u = parse("{\"prompt_tokens\":1000,\"completion_tokens\":50}");
        CacheUsageParser.Result r = CacheUsageParser.parse(u, 50);
        check("未上报时 cacheReported=false", !r.cacheReported);
        check("未上报时 prompt 仍记录", r.promptTokens == 1000);
        check("未上报时 hit 为 0", r.cachedTokens == 0);
    }

    /** 异常数据：cached > prompt，应被校正 */
    private static void malformedCachedGreaterThanPrompt() {
        JsonObject u = parse("{\"prompt_tokens\":100,\"completion_tokens\":10,\"cached_tokens\":500}");
        CacheUsageParser.Result r = CacheUsageParser.parse(u, 10);
        check("异常数据被校正 hit<=prompt", r.cachedTokens == 100);
        check("异常数据被校正 miss=0", r.missTokens == 0);
    }

    /** 只报命中、不报 prompt 总量：诚实标记为未上报 */
    private static void hitOnlyNoPrompt() {
        JsonObject u = parse("{\"cached_tokens\":300}");
        CacheUsageParser.Result r = CacheUsageParser.parse(u, 10);
        check("只有命中无总量 -> 标记未上报", !r.cacheReported);
    }

    /** 嵌套的 input_tokens_details */
    private static void wrappedDetails() {
        JsonObject u = parse("{\"input_tokens\":700,\"completion_tokens\":15,"
                + "\"input_tokens_details\":{\"cached_tokens\":600}}");
        CacheUsageParser.Result r = CacheUsageParser.parse(u, 15);
        check("input_tokens 兜底 prompt", r.promptTokens == 700);
        check("嵌套 details 命中", r.cachedTokens == 600);
    }

    // ---- 工具 ----

    private static JsonObject parse(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
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
