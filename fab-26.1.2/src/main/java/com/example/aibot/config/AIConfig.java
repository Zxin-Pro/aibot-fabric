package com.example.aibot.config;

/**
 * AIBot 全局配置对象（纯 POJO，不含任何 Minecraft 类）。
 *
 * <p><b>设计前提：面向「中转站」</b>。本模组不假设用户直连某一家官方 API，
 * 而是按 OpenAI 兼容协议适配任意中转聚合服务，因此：</p>
 * <ul>
 *   <li>baseUrl 完全自由（可带 /v1 前缀，也可带查询串）；</li>
 *   <li>支持自定义请求头（部分中转站要求额外的路由/鉴权头）；</li>
 *   <li>缓存字段名可手动指定（覆盖自动探测）；</li>
 *   <li>单价可配置（中转站计费与官方差异极大，不能写死）；</li>
 *   <li>限流参数可调（中转站 QPS 限制通常更严）。</li>
 * </ul>
 *
 * <p>本类位于公共逻辑层，被所有版本模块共用，
 * 因此绝对不能 import 任何 net.minecraft.* 类。</p>
 */
public class AIConfig {

    // ==================================================================
    // LLM 连接
    // ==================================================================

    /** API 密钥。默认空字符串，必须由用户通过命令写入，禁止硬编码。 */
    public String apiKey = "";

    /**
     * OpenAI 兼容 API 的 baseUrl。
     *
     * <p>默认留空，强制用户明确填写（避免用户误以为能直接用）。
     * 代码会自动补 "/chat/completions"。</p>
     *
     * <p>示例：</p>
     * <pre>
     *   https://api.deepseek.com
     *   https://your-relay.com/v1
     *   https://your-relay.com/v1/chat/completions   （已带端点也支持）
     * </pre>
     */
    public String baseUrl = "";

    /** 模型名。必须按你的中转站规定的名字填。 */
    public String model = "";

    /**
     * 额外请求头，一行一个，格式 {@code 名称: 值}。
     *
     * <p>部分中转站要求自定义鉴权头或路由头，例如：</p>
     * <pre>
     *   X-API-Key: sk-xxx
     *   X-Route: stable
     * </pre>
     */
    public String extraHeaders = "";

    /**
     * 采样温度。推理/决策类任务建议保持较低值。
     * 设为负数表示不发送该字段，让服务端用默认值。
     */
    public double temperature = 0.3;

    /**
     * 单次回复最大 token 数。
     * 设为 0 或负数表示<b>不发送</b>该字段（部分中转站处理不规范）。
     */
    public int maxTokens = 1024;

    /** 请求超时（毫秒）。中转站通常比官方慢，默认放宽到 45 秒。 */
    public int requestTimeoutMs = 45000;

    /** 建立连接超时（秒）。 */
    public int connectTimeoutSeconds = 15;

    /** 失败重试次数（网络错误 / 429 / 5xx 时重试，其他 4xx 不重试）。 */
    public int maxRetries = 2;

    /** 最小请求间隔（毫秒），避免打爆中转站的 QPS 限制。 */
    public long minRequestIntervalMs = 500L;

    // ==================================================================
    // 缓存与成本
    // ==================================================================

    /**
     * 手动指定缓存命中 token 的字段名。
     *
     * <p>留空表示自动探测（推荐）。只有当你的中转站用了非常规字段名、
     * 自动探测读不到时，才需要在这里填。</p>
     *
     * <p>可用 {@code /aibot cache probe} 查看服务端实际返回的 usage 字段。</p>
     */
    public String cacheHitField = "";

    /** 单价（每百万 token）：未命中输入的单价。默认按 DeepSeek 官方价。 */
    public double pricePerMillionInputMiss = 2.0;
    /** 单价：命中输入的单价。 */
    public double pricePerMillionInputHit = 0.2;
    /** 单价：输出。 */
    public double pricePerMillionOutput = 8.0;

    // ==================================================================
    // 自主行为
    // ==================================================================

    /** 是否开启自主循环（等价于 /aibot auto on）。 */
    public boolean autoLoop = false;

    /**
     * 自主模式：无人干预时自主游玩。
     *
     * <p>开启后，即使没有玩家下达任何命令，智能体也会自行确立目标
     * （先生存、再发展）并持续推进。这是「放着不管它也能自己玩」的开关。</p>
     */
    public boolean autonomousMode = true;

    /**
     * spawn 后是否自动开启自主循环。
     *
     * <p>默认 true：玩家用一条指令创建智能体之后就不需要再管它了。
     * 关掉则需要额外执行 /aibot auto &lt;名字&gt; on。</p>
     */
    public boolean autoStartOnSpawn = true;

    /**
     * 自主模式下的初始目标。留空表示让智能体自己决定。
     *
     * <p>留空时，提示词会指示它按「先生存、再发展」的优先级自行安排，
     * 而不是等待指令。</p>
     */
    public String defaultGoal = "";

    /** 自主循环决策间隔（tick）。40 tick = 2 秒。 */
    public int decisionIntervalTicks = 40;

    /**
     * 单次自主循环的最大连续步数。
     * 设为 0 或负数表示<b>不限制</b>，可以一直跑下去（默认）。
     */
    public int maxStepsPerSession = 0;

    /** 步数到达上限后是否自动重新开始。 */
    public boolean autoRestart = true;

    /** 死亡后是否自动重生并继续自主循环。 */
    public boolean autoRespawn = true;

    /** 死亡重生前的等待 tick 数（默认 100 tick = 5 秒）。 */
    public int respawnDelayTicks = 100;

    /** 连续卡住多少次后强制清空计划并重新规划。 */
    public int stuckThreshold = 3;

    /** 是否启用生存反射（血量/饥饿过低时不经 LLM 直接自救）。 */
    public boolean survivalReflex = true;

    /** 是否把当前计划持久化，服务器重启后接着做。 */
    public boolean persistPlan = true;

    /** 单步超时（tick）。动作执行超过该时长视为卡住。 */
    public int stepTimeoutTicks = 200;

    // ==================================================================
    // 多智能体
    // ==================================================================

    /** 假玩家默认名字。 */
    public String botName = "AIBot";

    /** 同时允许存在的智能体数量上限。 */
    public int maxBots = 5;

    /** 服务器启动时是否自动拉起档案库里标记了自主的智能体。 */
    public boolean autoSpawnOnStart = true;

    // ==================================================================
    // 上下文
    // ==================================================================

    /** 上下文压缩：保留最近多少条完整动作细节。 */
    public int contextWindow = 12;

    /** 上下文压缩：动态部分的目标 token 预算。 */
    public int contextTokenBudget = 1500;

    // ==================================================================
    // 输出与日志
    // ==================================================================

    /**
     * 聊天栏播报级别：
     * <ul>
     *   <li>0 = 静默：智能体的动作不播报到聊天栏（推荐，最干净）</li>
     *   <li>1 = 仅重要事件：死亡、重生、计划完成</li>
     *   <li>2 = 全部：每步动作都播报（调试用，会很吵）</li>
     * </ul>
     * 注意：这只影响「系统播报」。智能体自己用 chat 动作说的话不受影响。
     */
    public int chatAnnounceLevel = 0;

    /**
     * 是否允许智能体主动说话（chat 动作）。
     *
     * <p>开启时它会在被搭话或有关键进展时发言；关闭则完全不说话，
     * 只安静地自己玩。两种情况都不影响它自主游玩的能力。</p>
     */
    public boolean allowChat = true;

    /**
     * 智能体主动说话的最小间隔（步数）。
     * 防止它频繁刷屏。设为 0 表示不限制。
     */
    public int chatCooldownSteps = 20;

    // ==================================================================
    // 工具方法
    // ==================================================================

    /**
     * 归一化 baseUrl：去掉末尾斜杠，补全 /chat/completions。
     *
     * <p>兼容三种写法：</p>
     * <pre>
     *   https://api.deepseek.com                      -> .../chat/completions
     *   https://relay.com/v1                          -> .../v1/chat/completions
     *   https://relay.com/v1/chat/completions         -> 原样
     * </pre>
     */
    public String resolveChatCompletionsUrl() {
        String base = this.baseUrl == null ? "" : this.baseUrl.trim();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        if (base.isEmpty()) {
            return "";
        }
        // 已经带了完整端点
        if (base.endsWith("/chat/completions")) {
            return base;
        }
        // 已经带了 /completions（某些中转站）
        if (base.endsWith("/completions")) {
            return base;
        }
        return base + "/chat/completions";
    }

    /** 脱敏的 API Key（前 4 后 4）。 */
    public String maskedApiKey() {
        String k = this.apiKey == null ? "" : this.apiKey;
        if (k.isEmpty()) {
            return "(未设置)";
        }
        if (k.length() <= 8) {
            return "****";
        }
        return k.substring(0, 4) + "****" + k.substring(k.length() - 4);
    }

    /** 配置是否可用。 */
    public boolean isUsable() {
        return this.apiKey != null && !this.apiKey.trim().isEmpty()
                && this.baseUrl != null && !this.baseUrl.trim().isEmpty()
                && this.model != null && !this.model.trim().isEmpty();
    }

    /** 配置缺失项的可读描述（用于提示用户补什么）。 */
    public String missingFields() {
        StringBuilder sb = new StringBuilder();
        if (apiKey == null || apiKey.trim().isEmpty()) {
            sb.append("apiKey ");
        }
        if (baseUrl == null || baseUrl.trim().isEmpty()) {
            sb.append("baseUrl ");
        }
        if (model == null || model.trim().isEmpty()) {
            sb.append("model ");
        }
        return sb.toString().trim();
    }
}
