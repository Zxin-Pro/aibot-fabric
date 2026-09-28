package com.example.aibot.config;

/**
 * AIBot 全局配置对象（纯 POJO，不含任何 Minecraft 类）。
 *
 * <p>设计要点：本类位于 common 逻辑层，被四个版本模块共用，
 * 因此绝对不能 import 任何 net.minecraft.* 类，否则跨版本复用会失败。</p>
 *
 * <p>字段与 /aibot config set 命令一一对应。</p>
 */
public class AIConfig {

    // ------------------------------------------------------------------
    // LLM 连接配置
    // ------------------------------------------------------------------

    /** API 密钥。默认空字符串，必须由用户通过命令写入，禁止硬编码。 */
    public String apiKey = "";

    /**
     * OpenAI 兼容 API 的 baseUrl。
     * 默认 DeepSeek，可改成任意兼容服务（OpenAI / 智谱 / 通义 / 本地 vLLM / Ollama 等）。
     * 代码会自动拼接 "/chat/completions"。
     */
    public String baseUrl = "https://api.deepseek.com";

    /** 模型名。默认 deepseek-chat。 */
    public String model = "deepseek-chat";

    /** 采样温度。注意：推理/决策类任务建议保持较低值，0.3 左右较稳。 */
    public double temperature = 0.3;

    /** 单次回复最大 token 数。 */
    public int maxTokens = 1024;

    /** 请求超时（毫秒）。 */
    public int requestTimeoutMs = 30000;

    /** 失败重试次数（网络错误 / 5xx 时重试，4xx 不重试）。 */
    public int maxRetries = 2;

    /** 最小请求间隔（毫秒），用于限流，避免打爆 API。 */
    public long minRequestIntervalMs = 500L;

    /**
     * 是否启用提示词缓存优化。
     * true 时严格保证静态前缀不变（见 PromptBuilder），并统计缓存命中。
     * 某些不支持缓存的 API 可以关掉，但关掉只是关闭统计，前缀仍然稳定。
     */
    public boolean cacheEnabled = true;

    // ------------------------------------------------------------------
    // 行为配置
    // ------------------------------------------------------------------

    /** 是否开启自主循环（等价于 /aibot auto on）。 */
    public boolean autoLoop = false;

    /** 自主循环决策间隔（tick）。40 tick = 2 秒。 */
    public int decisionIntervalTicks = 40;

    /**
     * 单次自主循环的最大连续步数。
     *
     * <p><b>长期自主模式</b>：设为 0 或负数表示<b>不限制</b>，可以一直跑下去。
     * 默认 0（无限），配合 {@link #autoRestart} 实现完全自主游玩。</p>
     *
     * <p>如果只想做成本控制，可以设成正数（如 2000），
     * 到达上限后会写入存档并在服务器重启后自动续跑。</p>
     */
    public int maxStepsPerSession = 0;

    /**
     * 步数到达上限后是否自动重新开始（而不是彻底停止）。
     * 仅在 {@link #maxStepsPerSession} > 0 时有意义。
     */
    public boolean autoRestart = true;

    /**
     * 死亡后是否自动重生并继续自主循环。
     * 这是「完全自主游玩」的关键开关，默认 true。
     */
    public boolean autoRespawn = true;

    /**
     * 死亡重生前的等待 tick 数（默认 100 tick = 5 秒），
     * 给死亡动画和掉落物留出时间。
     */
    public int respawnDelayTicks = 100;

    /**
     * 连续卡住（同一动作反复失败）多少次后，强制清空当前计划并重新规划。
     * 防止在一个做不到的目标上无限空转烧 token。
     */
    public int stuckThreshold = 3;

    /**
     * 是否启用「自主生存反射」：血量过低自动逃跑/进食，夜晚自动睡觉。
     * 这些反射在 LLM 决策之外独立生效，保证不会因为 LLM 判断失误而猝死。
     */
    public boolean survivalReflex = true;

    /**
     * 是否把当前计划（任务栈）持久化到 memory.json，服务器重启后接着做。
     */
    public boolean persistPlan = true;

    /** 单步超时（tick）。动作执行超过该时长视为卡住，触发换策略。 */
    public int stepTimeoutTicks = 200;

    /** 假玩家默认名字（多智能体时可被每个 bot 的档案覆盖）。 */
    public String botName = "AIBot";

    /**
     * 同时允许存在的智能体数量上限。
     *
     * <p>每个智能体都要独立发 LLM 请求，数量上去后费用与服务器负载
     * 都是线性增长，因此必须有个闸门。默认 5。</p>
     */
    public int maxBots = 5;

    /** 服务器启动时是否自动把档案库里标记了 autoLoop 的智能体全部拉起来。 */
    public boolean autoSpawnOnStart = false;

    /** 上下文压缩：保留最近多少条完整动作细节。 */
    public int contextWindow = 12;

    /** 上下文压缩：动态部分的目标 token 预算。 */
    public int contextTokenBudget = 1500;

    /**
     * 是否只允许单人/自己的服务器使用（安全提示开关）。
     * 保持 true 时，模组会在多人服务器上打印合规提醒。
     */
    public boolean singleplayerWarning = true;

    // ------------------------------------------------------------------
    // 工具方法
    // ------------------------------------------------------------------

    /**
     * 归一化 baseUrl：去掉末尾斜杠，并补全 /chat/completions。
     * 用户可以只填 "https://api.deepseek.com"，也可以填到 "/v1"。
     *
     * @return 完整的 chat completions 端点 URL
     */
    public String resolveChatCompletionsUrl() {
        String base = this.baseUrl == null ? "" : this.baseUrl.trim();
        // 去掉末尾所有斜杠
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        if (base.isEmpty()) {
            base = "https://api.deepseek.com";
        }
        // 用户已经填了完整端点就直接用
        if (base.endsWith("/chat/completions")) {
            return base;
        }
        return base + "/chat/completions";
    }

    /**
     * 生成用于日志/状态展示的脱敏 API Key（只显示前 4 位和后 4 位）。
     */
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

    /**
     * 配置是否可用（能否真正发起请求）。
     */
    public boolean isUsable() {
        return this.apiKey != null && !this.apiKey.trim().isEmpty()
                && this.baseUrl != null && !this.baseUrl.trim().isEmpty()
                && this.model != null && !this.model.trim().isEmpty();
    }
}
