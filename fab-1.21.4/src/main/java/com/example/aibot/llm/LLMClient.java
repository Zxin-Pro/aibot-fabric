package com.example.aibot.llm;

import com.example.aibot.config.AIConfig;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * OpenAI 兼容格式的 LLM 异步客户端。
 *
 * <p><b>面向中转站的设计</b>（与旧版最大的区别）：</p>
 * <ol>
 *   <li><b>缓存字段自适应</b>：通过 {@link CacheUsageParser} 兼容 DeepSeek /
 *       OpenAI / Anthropic 转译 / 国内厂商等所有已知命名，
 *       并在完全未上报时如实标记，而不是谎报 0。</li>
 *   <li><b>上游切换检测</b>：中转站常把请求轮询到不同上游，
 *       这会让前缀缓存彻底失效（每次都是新上游的冷缓存）。
 *       这里从响应头提取上游标识并粘进结果，供 {@link UpstreamTracker} 检测。
 *       这是「命中率为什么上不去」最难查的一种原因。</li>
 *   <li><b>usage 原文保留</b>：最近一次请求的 usage 原文会被存下来，
 *       供 {@code /aibot cache probe} 展示，用户能自己看清字段名。</li>
 *   <li><b>max_tokens 缺省不发送</b>：部分中转站对 max_tokens 处理不规范，
 *       设为 0 或负数时改为不发送该字段，让它用上游默认值。</li>
 * </ol>
 *
 * <p>线程模型不变：所有 HTTP 调用在独立守护线程池里执行，
 * 回调方负责切回主线程。</p>
 */
public final class LLMClient {

    private static final Logger LOGGER = Logger.getLogger("aibot-llm");
    private static final Gson GSON = new Gson();

    private final AIConfig config;
    private final CacheStats stats;
    private final UpstreamTracker upstreamTracker;

    private final HttpClient httpClient;
    private final ExecutorService executor;

    private final AtomicLong lastRequestAt = new AtomicLong(0L);
    private final AtomicLong requestSeq = new AtomicLong(0L);

    /** 最近一次请求的 usage 原文（诊断用）。 */
    private volatile String lastUsageRaw = "";
    /** 最近一次请求的完整响应体片段（诊断用，可能含错误信息）。 */
    private volatile String lastResponseSnippet = "";
    /** 最近一次请求的时间戳与步号，用于诊断展示。 */
    private volatile long lastRequestAtMillis = 0L;

    public interface ResponseHandler {
        void onSuccess(String content);

        void onFailure(String error);
    }

    public LLMClient(AIConfig config, CacheStats stats) {
        this(config, stats, new UpstreamTracker());
    }

    public LLMClient(AIConfig config, CacheStats stats, UpstreamTracker upstreamTracker) {
        this.config = config;
        this.stats = stats;
        this.upstreamTracker = upstreamTracker;
        this.executor = Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "aibot-llm-worker");
            t.setDaemon(true);
            return t;
        });
        this.httpClient = HttpClient.newBuilder()
                .executor(this.executor)
                .connectTimeout(Duration.ofSeconds(config.connectTimeoutSeconds))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        // 单价随配置走：中转站的计费与官方差异极大，不能写死
        this.stats.setPrices(config.pricePerMillionInputMiss,
                config.pricePerMillionInputHit, config.pricePerMillionOutput);
    }

    /**
     * 异步发送一次对话请求。
     *
     * <p><b>缓存关键点</b>：messages 数组的第 0 条永远是固定不变的 system message，
     * 接下来是固定不变的静态前缀 user message，动态内容追加在最后。
     * 这样服务端的 KV Cache 才能命中前缀。</p>
     */
    public void requestAsync(PromptBuilder.BuiltPrompt prompt, ResponseHandler handler) {
        long seq = this.requestSeq.incrementAndGet();

        if (!config.isUsable()) {
            handler.onFailure("配置不完整：请先用 /aibot config set apiKey <key> 设置 API Key");
            return;
        }

        // 限流：确保两次请求间隔不小于配置值（中转站通常有更严格的 QPS 限制）
        long now = System.currentTimeMillis();
        long last = lastRequestAt.get();
        long waitMs = config.minRequestIntervalMs - (now - last);
        if (waitMs > 0) {
            try {
                Thread.sleep(waitMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        lastRequestAt.set(System.currentTimeMillis());
        lastRequestAtMillis = System.currentTimeMillis();

        String body = buildRequestBody(prompt);
        String url = config.resolveChatCompletionsUrl();
        LOGGER.fine("[AIBot] #" + seq + " 发起请求 -> " + url + " model=" + config.model);
        sendWithRetry(url, body, seq, 0, handler);
    }

    /**
     * 构造请求体 JSON 字符串。
     *
     * <pre>
     *   [0] system  —— 静态系统提示词（永不变化）
     *   [1] user    —— 静态前缀（永不变化，体量大，缓存收益主体）
     *   [2] user    —— 慢变层动态（身份/能力/长期经验，变化频率低）
     *   [3] user    —— 快变层动态（状态/计划/反馈，每轮变化）
     * </pre>
     *
     * <p><b>为什么要拆成两条动态消息</b>：服务端的前缀缓存是从第一处不一致往后
     * 全部失效。把「几乎不变的长期经验」和「每轮都变的状态」混在一条里，
     * 会导致每次请求都在同一条消息内部出现差异，缓存块边界被切在最前面，
     * 后面所有内容都要重算。拆开之后，慢变层可以独立命中缓存。</p>
     */
    private String buildRequestBody(PromptBuilder.BuiltPrompt prompt) {
        JsonObject root = new JsonObject();
        root.addProperty("model", config.model);
        if (config.temperature >= 0) {
            root.addProperty("temperature", config.temperature);
        }
        // maxTokens<=0 表示「不发送」，让上游用它自己的默认值。
        // 部分中转站对 max_tokens 处理不规范（例如限制了也不报错，直接截断输出）。
        if (config.maxTokens > 0) {
            root.addProperty("max_tokens", config.maxTokens);
        }
        root.addProperty("stream", false);

        JsonArray messages = new JsonArray();
        addMessage(messages, "system", prompt.systemMessage());
        addMessage(messages, "user", prompt.staticUserMessage());
        if (prompt.slowUserMessage() != null && !prompt.slowUserMessage().isEmpty()) {
            addMessage(messages, "user", prompt.slowUserMessage());
        }
        addMessage(messages, "user", prompt.dynamicUserMessage());
        root.add("messages", messages);

        JsonObject streamOptions = new JsonObject();
        streamOptions.addProperty("include_usage", true);
        root.add("stream_options", streamOptions);

        return GSON.toJson(root);
    }

    private static void addMessage(JsonArray arr, String role, String content) {
        JsonObject m = new JsonObject();
        m.addProperty("role", role);
        m.addProperty("content", content == null ? "" : content);
        arr.add(m);
    }

    private void sendWithRetry(String url, String body, long seq, int attempt, ResponseHandler handler) {
        HttpRequest request;
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofMillis(config.requestTimeoutMs))
                    .header("Content-Type", "application/json; charset=utf-8")
                    .header("Authorization", "Bearer " + config.apiKey.trim())
                    .header("Accept", "application/json");
            // 自定义头：部分中转站要求额外的鉴权/路由头
            if (config.extraHeaders != null && !config.extraHeaders.trim().isEmpty()) {
                for (String line : config.extraHeaders.split("\n")) {
                    int i = line.indexOf(':');
                    if (i > 0) {
                        String k = line.substring(0, i).trim();
                        String v = line.substring(i + 1).trim();
                        if (!k.isEmpty() && !v.isEmpty()) {
                            b.header(k, v);
                        }
                    }
                }
            }
            request = b.POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build();
        } catch (IllegalArgumentException e) {
            handler.onFailure("baseUrl 非法: " + url + " —— " + e.getMessage());
            return;
        }

        httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                .whenComplete((resp, throwable) -> {
                    if (throwable != null) {
                        handleRetryOrFail(url, body, seq, attempt, handler,
                                "网络错误: " + rootCauseMessage(throwable), true);
                        return;
                    }

                    int code = resp.statusCode();
                    if (code >= 200 && code < 300) {
                        handleSuccessBody(resp, seq, handler);
                    } else if (code == 429 || code >= 500) {
                        handleRetryOrFail(url, body, seq, attempt, handler,
                                "HTTP " + code + ": " + truncate(resp.body(), 300), true);
                    } else {
                        stats.recordFailure();
                        lastResponseSnippet = truncate(resp.body(), 800);
                        LOGGER.warning("[AIBot] #" + seq + " 请求失败 HTTP " + code + ": "
                                + truncate(resp.body(), 500));
                        handler.onFailure("HTTP " + code + " —— " + extractErrorMessage(resp.body()));
                    }
                });
    }

    private void handleRetryOrFail(String url, String body, long seq, int attempt,
                                   ResponseHandler handler, String error, boolean retryable) {
        if (retryable && attempt < config.maxRetries) {
            long backoffMs = 500L * (1L << attempt);
            LOGGER.info("[AIBot] #" + seq + " " + error + "；" + backoffMs + "ms 后重试（第 "
                    + (attempt + 1) + "/" + config.maxRetries + " 次）");
            final int nextAttempt = attempt + 1;
            CompletableFuture.delayedExecutor(backoffMs, java.util.concurrent.TimeUnit.MILLISECONDS, executor)
                    .execute(() -> sendWithRetry(url, body, seq, nextAttempt, handler));
        } else {
            stats.recordFailure();
            LOGGER.warning("[AIBot] #" + seq + " 最终失败: " + error);
            handler.onFailure(error);
        }
    }

    /**
     * 处理 HTTP 2xx 响应体：解析内容与用量，并把上游标识喂给追踪器。
     */
    private void handleSuccessBody(HttpResponse<String> resp, long seq, ResponseHandler handler) {
        String body = resp.body();
        try {
            JsonObject root = JsonParser.parseString(body).getAsJsonObject();

            // ---- 内容 ----
            String content = null;
            String finishReason = "";
            if (root.has("choices") && root.get("choices").isJsonArray()) {
                JsonArray choices = root.getAsJsonArray("choices");
                if (choices.size() > 0) {
                    JsonObject choice = choices.get(0).getAsJsonObject();
                    if (choice.has("finish_reason") && !choice.get("finish_reason").isJsonNull()) {
                        finishReason = choice.get("finish_reason").getAsString();
                    }
                    if (choice.has("message") && choice.get("message").isJsonObject()) {
                        JsonObject msg = choice.getAsJsonObject("message");
                        if (msg.has("content") && !msg.get("content").isJsonNull()) {
                            content = msg.get("content").getAsString();
                        }
                    }
                }
            }

            // ---- 用量（含缓存字段，多方言） ----
            JsonObject usageObj = null;
            if (root.has("usage") && root.get("usage").isJsonObject()) {
                usageObj = root.getAsJsonObject("usage");
                lastUsageRaw = truncate(GSON.toJson(usageObj), 1200);
            }
            long completionTokens = 0;
            if (usageObj != null) {
                completionTokens = longOf(usageObj, "completion_tokens",
                        longOf(usageObj, "output_tokens", 0));
            }
            CacheUsageParser.Result u = CacheUsageParser.parse(usageObj, completionTokens);
            stats.recordSuccess(u);

            // ---- 上游追踪：中转站轮询会导致缓存永远冷启动 ----
            String upstream = detectUpstream(resp);
            upstreamTracker.record(upstream, u.cacheReported, u.cachedTokens, u.missTokens);

            if (content == null || content.trim().isEmpty()) {
                handler.onFailure("模型返回了空内容"
                        + (finishReason.isEmpty() ? "" : "（finish_reason=" + finishReason + "）"));
                return;
            }

            // 输出被 max_tokens 截断时给出明确提示，否则用户会以为是模型笨
            if ("length".equals(finishReason)) {
                LOGGER.warning("[AIBot] 输出被 max_tokens 截断，建议调大：/aibot config set maxTokens 2048");
            }

            LOGGER.fine("[AIBot] #" + seq + " 成功: " + u);
            handler.onSuccess(content);

        } catch (Exception e) {
            stats.recordFailure();
            lastResponseSnippet = truncate(body, 800);
            LOGGER.log(Level.WARNING, "[AIBot] #" + seq + " 解析响应失败", e);
            handler.onFailure("解析响应失败: " + e.getMessage() + " —— 原始内容: " + truncate(body, 300));
        }
    }

    /**
     * 从响应里识别上游来源。
     *
     * <p>中转站通常会在响应头里留下痕迹（自己的名字、或上游的 server 头）。
     * 识别它不是为了好看，而是为了检测「同一会话被轮询到不同上游」——
     * 这会让前缀缓存完全失效，是命中率上不去的最隐蔽原因。</p>
     */
    private String detectUpstream(HttpResponse<String> resp) {
        try {
            // 优先看自定义的上游标识头（很多中转站会写）
            for (String h : new String[]{
                    "x-upstream", "x-upstream-name", "x-provider", "x-model-provider",
                    "x-cache", "cf-cache-status", "x-served-by", "server"}) {
                var v = resp.headers().firstValue(h);
                if (v.isPresent() && !v.get().isBlank()) {
                    return h + "=" + v.get();
                }
            }
            // 退而求其次：用 cf-ray 这类唯一 ID 的前缀当指纹
            var ray = resp.headers().firstValue("cf-ray");
            if (ray.isPresent() && ray.get().length() > 3) {
                String r = ray.get();
                return "cf-ray-prefix=" + r.substring(Math.max(0, r.length() - 6));
            }
        } catch (Throwable ignored) {
        }
        return "(无标识)";
    }

    private static String extractErrorMessage(String body) {
        try {
            JsonObject root = JsonParser.parseString(body).getAsJsonObject();
            if (root.has("error")) {
                JsonElement err = root.get("error");
                if (err.isJsonObject() && err.getAsJsonObject().has("message")) {
                    return err.getAsJsonObject().get("message").getAsString();
                }
                return err.toString();
            }
            if (root.has("message")) {
                return root.get("message").getAsString();
            }
        } catch (Exception ignored) {
        }
        return truncate(body, 300);
    }

    private static long longOf(JsonObject obj, String key, long def) {
        try {
            if (obj.has(key) && !obj.get(key).isJsonNull()) {
                return obj.get(key).getAsLong();
            }
        } catch (Exception ignored) {
        }
        return def;
    }

    private static String rootCauseMessage(Throwable t) {
        Throwable cur = t;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        String msg = cur.getMessage();
        return cur.getClass().getSimpleName() + (msg != null ? ": " + msg : "");
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "...(截断)";
    }

    // ---- 诊断访问器（供 /aibot cache probe） ----

    /** 最近一次请求的 usage 原文（原始 JSON）。 */
    public String lastUsageRaw() {
        return lastUsageRaw;
    }

    /** 最近一次失败响应的片段。 */
    public String lastResponseSnippet() {
        return lastResponseSnippet;
    }

    public long lastRequestAtMillis() {
        return lastRequestAtMillis;
    }

    public UpstreamTracker upstreamTracker() {
        return upstreamTracker;
    }

    public void shutdown() {
        executor.shutdownNow();
    }
}
