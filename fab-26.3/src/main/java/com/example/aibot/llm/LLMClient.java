package com.example.aibot.llm;

import com.example.aibot.config.AIConfig;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
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
 * <p>特性：</p>
 * <ul>
 *   <li>基于 JDK 11+ 的 {@link HttpClient} 异步发送，完全不阻塞 Minecraft 主线程</li>
 *   <li>超时、重试（仅对网络错误与 5xx 重试，4xx 直接失败）、限流</li>
 *   <li>解析 usage 中的缓存命中字段（DeepSeek 与 OpenAI 两种命名都支持）</li>
 *   <li>统计信息汇总到 {@link CacheStats}</li>
 * </ul>
 *
 * <p>线程模型：所有 HTTP 调用都在独立的守护线程池里执行，
 * 回调 {@link ResponseHandler} 的实现方需自行切回主线程（由调用方负责）。</p>
 */
public final class LLMClient {

    private static final Logger LOGGER = Logger.getLogger("aibot-llm");
    private static final Gson GSON = new Gson();

    private final AIConfig config;
    private final CacheStats stats;

    /** JDK 内置异步 HTTP 客户端。连接池与线程池由 JDK 管理。 */
    private final HttpClient httpClient;

    /** 执行 CompletableFuture 回调的线程池（守护线程，不阻止 JVM 退出）。 */
    private final ExecutorService executor;

    /** 上次请求发起的时间戳（毫秒），用于限流。 */
    private final AtomicLong lastRequestAt = new AtomicLong(0L);

    /** 用于给日志编号，便于排查单次请求。 */
    private final AtomicLong requestSeq = new AtomicLong(0L);

    /**
     * 异步响应回调。
     */
    public interface ResponseHandler {
        /**
         * 请求成功并解析出模型文本内容。
         *
         * @param content 模型返回的文本（期望是 JSON 动作字符串）
         */
        void onSuccess(String content);

        /**
         * 请求失败（网络、超时、HTTP 错误、解析失败等）。
         *
         * @param error 错误描述
         */
        void onFailure(String error);
    }

    public LLMClient(AIConfig config, CacheStats stats) {
        this.config = config;
        this.stats = stats;
        this.executor = Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "aibot-llm-worker");
            t.setDaemon(true);
            return t;
        });
        this.httpClient = HttpClient.newBuilder()
                .executor(this.executor)
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /**
     * 异步发送一次对话请求。
     *
     * <p><b>缓存关键点</b>：messages 数组的第 0 条永远是固定不变的 system message，
     * 第 1 条是固定不变的静态前缀 user message，动态内容追加在最后。
     * 这样服务端的 KV Cache 才能命中前缀。</p>
     *
     * @param prompt    由 {@link PromptBuilder} 构建好的请求体 messages
     * @param handler   响应回调
     */
    public void requestAsync(PromptBuilder.BuiltPrompt prompt, ResponseHandler handler) {
        long seq = this.requestSeq.incrementAndGet();

        if (!config.isUsable()) {
            handler.onFailure("配置不完整：请先用 /aibot config set apiKey <key> 设置 API Key");
            return;
        }

        // 限流：确保两次请求间隔不小于配置值
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

        String body = buildRequestBody(prompt);
        String url = config.resolveChatCompletionsUrl();

        LOGGER.fine("[AIBot] #" + seq + " 发起请求 -> " + url + " model=" + config.model);
        sendWithRetry(url, body, seq, 0, handler);
    }

    /**
     * 构造请求体 JSON 字符串。
     *
     * <p>messages 顺序固定为：</p>
     * <pre>
     *   [0] system  —— 静态系统提示词（永不变化）
     *   [1] user    —— 静态动作前缀（永不变化）
     *   [2] user    —— 动态上下文（状态 + 记忆 + 目标，每次变化）
     * </pre>
     */
    private String buildRequestBody(PromptBuilder.BuiltPrompt prompt) {
        JsonObject root = new JsonObject();
        root.addProperty("model", config.model);
        root.addProperty("temperature", config.temperature);
        root.addProperty("max_tokens", config.maxTokens);
        // 部分兼容服务需要 stream=false 才返回 usage；显式声明更稳
        root.addProperty("stream", false);

        JsonArray messages = new JsonArray();

        JsonObject sys = new JsonObject();
        sys.addProperty("role", "system");
        sys.addProperty("content", prompt.systemMessage());
        messages.add(sys);

        JsonObject staticUser = new JsonObject();
        staticUser.addProperty("role", "user");
        staticUser.addProperty("content", prompt.staticUserMessage());
        messages.add(staticUser);

        JsonObject dynamicUser = new JsonObject();
        dynamicUser.addProperty("role", "user");
        dynamicUser.addProperty("content", prompt.dynamicUserMessage());
        messages.add(dynamicUser);

        root.add("messages", messages);

        // 让部分服务返回 usage 统计（OpenAI 兼容服务通常默认返回）
        JsonObject streamOptions = new JsonObject();
        streamOptions.addProperty("include_usage", true);
        root.add("stream_options", streamOptions);

        return GSON.toJson(root);
    }

    /**
     * 带重试的实际发送逻辑。
     *
     * <p>重试策略：网络异常 / 超时 / 429 / 5xx 重试并指数退避；
     * 4xx（除 429）属于请求本身有问题，重试无意义，直接失败。</p>
     */
    private void sendWithRetry(String url, String body, long seq, int attempt, ResponseHandler handler) {
        HttpRequest request;
        try {
            request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofMillis(config.requestTimeoutMs))
                    .header("Content-Type", "application/json; charset=utf-8")
                    .header("Authorization", "Bearer " + config.apiKey.trim())
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
        } catch (IllegalArgumentException e) {
            handler.onFailure("baseUrl 非法: " + url + " —— " + e.getMessage());
            return;
        }

        httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                .whenComplete((resp, throwable) -> {
                    if (throwable != null) {
                        // 网络层错误：可重试
                        handleRetryOrFail(url, body, seq, attempt, handler,
                                "网络错误: " + rootCauseMessage(throwable), true);
                        return;
                    }

                    int code = resp.statusCode();
                    if (code >= 200 && code < 300) {
                        handleSuccessBody(resp.body(), seq, handler);
                    } else if (code == 429 || code >= 500) {
                        // 限流或服务端错误：可重试
                        handleRetryOrFail(url, body, seq, attempt, handler,
                                "HTTP " + code + ": " + truncate(resp.body(), 300), true);
                    } else {
                        // 4xx：请求本身有问题，重试无意义
                        stats.recordFailure();
                        LOGGER.warning("[AIBot] #" + seq + " 请求失败 HTTP " + code + ": " + truncate(resp.body(), 500));
                        handler.onFailure("HTTP " + code + " —— " + extractErrorMessage(resp.body()));
                    }
                });
    }

    /**
     * 判断是否继续重试。
     *
     * @param retryable 该错误是否可重试
     */
    private void handleRetryOrFail(String url, String body, long seq, int attempt,
                                   ResponseHandler handler, String error, boolean retryable) {
        if (retryable && attempt < config.maxRetries) {
            long backoffMs = 500L * (1L << attempt); // 500ms, 1s, 2s ...
            LOGGER.info("[AIBot] #" + seq + " " + error + "；" + backoffMs + "ms 后重试（第 "
                    + (attempt + 1) + "/" + config.maxRetries + " 次）");
            // 用单独的线程做延迟，避免阻塞 worker
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
     * 处理 HTTP 2xx 响应体，解析出内容与 usage 缓存字段。
     */
    private void handleSuccessBody(String body, long seq, ResponseHandler handler) {
        try {
            JsonObject root = JsonParser.parseString(body).getAsJsonObject();

            // 解析 choices[0].message.content
            String content = null;
            if (root.has("choices") && root.get("choices").isJsonArray()) {
                JsonArray choices = root.getAsJsonArray("choices");
                if (choices.size() > 0) {
                    JsonObject choice = choices.get(0).getAsJsonObject();
                    if (choice.has("message") && choice.get("message").isJsonObject()) {
                        JsonObject msg = choice.getAsJsonObject("message");
                        if (msg.has("content") && !msg.get("content").isJsonNull()) {
                            content = msg.get("content").getAsString();
                        }
                    }
                }
            }

            // 解析 usage（含缓存字段），这是缓存命中率的数据来源
            long prompt = 0, completion = 0, cached = 0, miss = 0;
            if (root.has("usage") && root.get("usage").isJsonObject()) {
                JsonObject usage = root.getAsJsonObject("usage");
                prompt = getLong(usage, "prompt_tokens", 0);
                completion = getLong(usage, "completion_tokens", 0);

                // DeepSeek 风格：prompt_cache_hit_tokens / prompt_cache_miss_tokens
                long deepseekHit = getLong(usage, "prompt_cache_hit_tokens", -1);
                long deepseekMiss = getLong(usage, "prompt_cache_miss_tokens", -1);

                // OpenAI 风格：prompt_tokens_details.cached_tokens
                long openaiHit = -1;
                if (usage.has("prompt_tokens_details") && usage.get("prompt_tokens_details").isJsonObject()) {
                    openaiHit = getLong(usage.getAsJsonObject("prompt_tokens_details"), "cached_tokens", -1);
                }
                // 部分服务直接把 cached_tokens 放在 usage 顶层
                if (openaiHit < 0) {
                    openaiHit = getLong(usage, "cached_tokens", -1);
                }

                if (deepseekHit >= 0) {
                    cached = deepseekHit;
                    miss = deepseekMiss >= 0 ? deepseekMiss : Math.max(0, prompt - cached);
                } else if (openaiHit >= 0) {
                    cached = openaiHit;
                    miss = Math.max(0, prompt - cached);
                } else {
                    // 服务未返回缓存字段：全部计入未命中，避免虚高命中率
                    cached = 0;
                    miss = prompt;
                }
            }

            stats.recordSuccess(prompt, completion, cached, miss);

            if (content == null || content.trim().isEmpty()) {
                handler.onFailure("模型返回了空内容");
                return;
            }

            LOGGER.fine("[AIBot] #" + seq + " 成功: prompt=" + prompt + " completion=" + completion
                    + " cached=" + cached + " miss=" + miss);
            handler.onSuccess(content);

        } catch (Exception e) {
            stats.recordFailure();
            LOGGER.log(Level.WARNING, "[AIBot] #" + seq + " 解析响应失败", e);
            handler.onFailure("解析响应失败: " + e.getMessage() + " —— 原始内容: " + truncate(body, 300));
        }
    }

    /** 从错误响应体里提取可读的错误信息。 */
    private String extractErrorMessage(String body) {
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
            // 响应体不是 JSON，直接返回原文
        }
        return truncate(body, 300);
    }

    private static long getLong(JsonObject obj, String key, long def) {
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

    /** 客户端关闭（服务器停止时调用）。 */
    public void shutdown() {
        executor.shutdownNow();
    }
}
