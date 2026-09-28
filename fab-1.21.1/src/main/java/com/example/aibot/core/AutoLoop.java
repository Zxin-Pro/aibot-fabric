package com.example.aibot.core;

import com.example.aibot.action.ActionExecutor;
import com.example.aibot.action.ActionParser;
import com.example.aibot.config.AIConfig;
import com.example.aibot.entity.AIBotPlayer;
import com.example.aibot.entity.FakePlayerManager;
import com.example.aibot.llm.CacheStats;
import com.example.aibot.llm.LLMClient;
import com.example.aibot.llm.PromptBuilder;
import com.example.aibot.memory.LongTermMemory;
import com.example.aibot.memory.ShortTermMemory;
import com.example.aibot.state.StateCollector;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 自主循环：感知 → 决策 → 执行 → 记忆 的闭环。
 *
 * <p>由服务器 tick 事件驱动（每 {@code decisionIntervalTicks} tick 一次），
 * 但真正的 LLM 调用是异步的，因此不会阻塞主线程。</p>
 *
 * <p>状态机：</p>
 * <pre>
 *   IDLE ──auto on──> WAITING_FOR_LLM ──响应到达──> EXECUTING ──> 冷却 ──> WAITING_FOR_LLM
 *                            │
 *                            └──超时/失败──> 记录失败，进入冷却
 * </pre>
 *
 * <p>安全保护：</p>
 * <ul>
 *   <li>步数上限 {@code maxStepsPerSession}，超过自动停止</li>
 *   <li>单步超时 {@code stepTimeoutTicks}，超时视为卡住</li>
 *   <li>同一动作连续失败 3 次自动切换到探索策略</li>
 * </ul>
 */
public final class AutoLoop {

    private static final Logger LOGGER = Logger.getLogger("aibot-loop");

    /** 连续失败多少次后判定为「卡住」。 */
    private static final int STUCK_THRESHOLD = 3;

    /** 循环状态。 */
    private enum Phase {
        /** 未运行。 */
        IDLE,
        /** 等待 LLM 响应。 */
        WAITING_FOR_LLM,
        /** 冷却中（避免请求过于密集）。 */
        COOLDOWN
    }

    private final AIConfig config;
    private final CacheStats cacheStats;
    private final LLMClient llmClient;
    private final FakePlayerManager playerManager;
    private final ShortTermMemory shortTermMemory;
    private final LongTermMemory longTermMemory;

    /** 是否处于运行状态（/aibot auto on 控制）。 */
    private final AtomicBoolean running = new AtomicBoolean(false);

    private volatile Phase phase = Phase.IDLE;

    /** 距下次决策还需等待的 tick 数。 */
    private int tickCounter = 0;

    /** 当前会话已执行步数。 */
    private final AtomicLong stepCounter = new AtomicLong(0);

    /** 当前等待中的请求发出的 tick（用于超时检测）。 */
    private long requestSentAtTick = 0;

    /** 当前长期目标（自然语言）。 */
    private volatile String goal = "";

    /** 上一步执行结果反馈。 */
    private volatile String lastFeedback = "";

    public AutoLoop(AIConfig config,
                    CacheStats cacheStats,
                    LLMClient llmClient,
                    FakePlayerManager playerManager,
                    ShortTermMemory shortTermMemory,
                    LongTermMemory longTermMemory) {
        this.config = config;
        this.cacheStats = cacheStats;
        this.llmClient = llmClient;
        this.playerManager = playerManager;
        this.shortTermMemory = shortTermMemory;
        this.longTermMemory = longTermMemory;
    }

    /**
     * 每个服务器 tick 调用一次。
     *
     * @param serverTick 当前服务器 tick 计数
     */
    public void tick(long serverTick) {
        if (!running.get()) {
            return;
        }

        AIBotPlayer bot = playerManager.getBot();
        if (bot == null || !bot.isAlive()) {
            // 假玩家不存在或已死亡，暂停循环并提示
            if (phase != Phase.IDLE) {
                LOGGER.warning("[AIBot] 假玩家不存在或已死亡，自动循环暂停");
                stop();
            }
            return;
        }

        // 步数上限保护
        if (stepCounter.get() >= config.maxStepsPerSession) {
            LOGGER.info("[AIBot] 已达单次会话步数上限 " + config.maxStepsPerSession + "，自动停止");
            stop();
            return;
        }

        switch (phase) {
            case IDLE:
                // 启动后进入等待决策节奏
                phase = Phase.COOLDOWN;
                tickCounter = config.decisionIntervalTicks;
                break;

            case COOLDOWN:
                if (tickCounter > 0) {
                    tickCounter--;
                    return;
                }
                requestDecision(bot, serverTick);
                break;

            case WAITING_FOR_LLM:
                // 超时保护：请求发出太久没回来，视为卡住
                if (serverTick - requestSentAtTick > config.stepTimeoutTicks) {
                    LOGGER.warning("[AIBot] LLM 响应超时（超过 " + config.stepTimeoutTicks + " tick），本轮放弃");
                    shortTermMemory.add(stepCounter.get(), "decision", "",
                            false, "LLM 响应超时");
                    phase = Phase.COOLDOWN;
                    tickCounter = config.decisionIntervalTicks;
                }
                break;

            default:
                break;
        }
    }

    /**
     * 发起一次决策：采集状态 → 构建提示词 → 异步请求 LLM。
     */
    private void requestDecision(AIBotPlayer bot, long serverTick) {
        phase = Phase.WAITING_FOR_LLM;
        requestSentAtTick = serverTick;

        // 1. 采集状态（20 tick 一次也可，这里按决策周期采集）
        String stateJson;
        try {
            stateJson = StateCollector.collect(bot, this.goal);
        } catch (Throwable t) {
            LOGGER.log(Level.WARNING, "[AIBot] 状态采集失败", t);
            stateJson = "{}";
        }

        // 2. 构建提示词（静态前缀 + 动态后缀）
        String shortMem = shortTermMemory.serialize();
        String longMem = longTermMemory.serialize();

        // 卡住检测：如果连续失败，加入换策略指令
        String feedback = this.lastFeedback;
        if (isStuck()) {
            feedback = (feedback == null ? "" : feedback + " ")
                    + "【重要】同一个动作已连续失败多次，请务必换一种完全不同的策略，不要再重复同样的动作。";
        }

        PromptBuilder.BuiltPrompt prompt =
                PromptBuilder.build(stateJson, shortMem, longMem, this.goal, feedback);

        // 3. 异步请求（不阻塞主线程）
        final long currentStep = stepCounter.incrementAndGet();
        llmClient.requestAsync(prompt, new LLMClient.ResponseHandler() {
            @Override
            public void onSuccess(String content) {
                handleLlmResponse(content, currentStep);
            }

            @Override
            public void onFailure(String error) {
                handleLlmFailure(error, currentStep);
            }
        });
    }

    /**
     * 处理 LLM 响应：解析动作 → 执行 → 记录记忆 → 进入冷却。
     *
     * <p><b>线程说明</b>：本方法在 LLM worker 线程上被调用。
     * Minecraft 世界操作必须在主线程执行，因此这里用服务器 execute 切回主线程。</p>
     */
    private void handleLlmResponse(String content, long step) {
        AIBotPlayer bot = playerManager.getBot();
        if (bot == null) {
            phase = Phase.COOLDOWN;
            tickCounter = config.decisionIntervalTicks;
            return;
        }

        // 切回服务器主线程执行世界操作
        bot.level().getServer().execute(() -> {
            try {
                ActionParser.ParsedAction parsed = ActionParser.parse(content);
                if (parsed == null) {
                    LOGGER.warning("[AIBot] 无法解析 LLM 输出: " + truncate(content, 200));
                    shortTermMemory.add(step, "parse", "", false, "输出不是合法 JSON 动作");
                    lastFeedback = "上一次输出不是合法 JSON，请只输出一个 JSON 对象。";
                    phase = Phase.COOLDOWN;
                    tickCounter = config.decisionIntervalTicks;
                    return;
                }

                // 执行动作
                ActionExecutor executor = new ActionExecutor(bot);
                ActionExecutor.ActionResult result = executor.execute(parsed);

                // 记录记忆
                shortTermMemory.add(step, parsed.action(), parsed.paramsSummary(),
                        result.success(), result.message());

                if (result.success()) {
                    longTermMemory.recordSuccess(parsed.action(), targetOf(parsed));
                } else {
                    longTermMemory.recordFailure(parsed.action(), targetOf(parsed), result.message());
                }
                // 每次执行后落盘长期记忆（数据量小，开销可接受）
                longTermMemory.save();

                lastFeedback = "上一步 " + parsed.action() + " "
                        + (result.success() ? "成功" : "失败") + "：" + result.message();

                LOGGER.info("[AIBot] 第 " + step + " 步 " + parsed.action()
                        + " -> " + (result.success() ? "成功" : "失败") + " (" + result.message() + ")");

            } catch (Throwable t) {
                LOGGER.log(Level.SEVERE, "[AIBot] 执行动作时发生严重异常", t);
                shortTermMemory.add(step, "execute", "", false, "执行异常: " + t.getClass().getSimpleName());
            } finally {
                phase = Phase.COOLDOWN;
                tickCounter = config.decisionIntervalTicks;
            }
        });
    }

    /** 处理 LLM 失败。 */
    private void handleLlmFailure(String error, long step) {
        shortTermMemory.add(step, "decision", "", false, "LLM 调用失败: " + error);
        lastFeedback = "上一次 LLM 调用失败：" + error;
        LOGGER.warning("[AIBot] LLM 调用失败: " + error);
        phase = Phase.COOLDOWN;
        // 失败后多等一会，避免疯狂重试
        tickCounter = config.decisionIntervalTicks * 2;
    }

    /** 判断是否「卡住」（同一动作连续失败）。 */
    private boolean isStuck() {
        ShortTermMemory.Entry last = shortTermMemory.last();
        if (last == null) {
            return false;
        }
        return shortTermMemory.consecutiveFailures(last.action()) >= STUCK_THRESHOLD;
    }

    /** 提取动作目标（用于长期记忆归类）。 */
    private String targetOf(ActionParser.ParsedAction parsed) {
        for (String key : new String[]{"block", "item", "target", "player"}) {
            String v = parsed.getString(key, "");
            if (!v.isEmpty()) {
                return v;
            }
        }
        return "";
    }

    // ------------------------------------------------------------------
    // 生命周期控制
    // ------------------------------------------------------------------

    /** 开启自主循环。 */
    public void start() {
        if (running.compareAndSet(false, true)) {
            phase = Phase.IDLE;
            tickCounter = 0;
            stepCounter.set(0);
            LOGGER.info("[AIBot] 自主循环已开启");
        }
    }

    /** 停止自主循环。 */
    public void stop() {
        if (running.compareAndSet(true, false)) {
            phase = Phase.IDLE;
            LOGGER.info("[AIBot] 自主循环已停止（本轮共执行 " + stepCounter.get() + " 步）");
        }
    }

    /** 是否正在运行。 */
    public boolean isRunning() {
        return running.get();
    }

    /** 当前步数。 */
    public long getStepCount() {
        return stepCounter.get();
    }

    /** 设置长期目标。 */
    public void setGoal(String goal) {
        this.goal = goal == null ? "" : goal.trim();
    }

    /** 当前长期目标。 */
    public String getGoal() {
        return this.goal;
    }

    /** 生成 /aibot status 的状态文本。 */
    public String statusReport() {
        AIBotPlayer bot = playerManager.getBot();
        StringBuilder sb = new StringBuilder();
        sb.append("===== AIBot 状态 =====\n");
        sb.append("假玩家: ").append(bot == null ? "未生成" : bot.getName().getString()
                + (bot.isAlive() ? "（存活）" : "（已死亡）")).append("\n");
        sb.append("自主循环: ").append(running.get() ? "开启中" : "已关闭").append("\n");
        sb.append("当前阶段: ").append(phase).append("\n");
        sb.append("已执行步数: ").append(stepCounter.get())
                .append(" / ").append(config.maxStepsPerSession).append("\n");
        sb.append("长期目标: ").append(goal.isEmpty() ? "(未设定)" : goal).append("\n");
        sb.append("模型: ").append(config.model).append("\n");
        sb.append("API 地址: ").append(config.baseUrl).append("\n");
        sb.append("API Key: ").append(config.maskedApiKey()).append("\n");
        sb.append("缓存: ").append(cacheStats.shortSummary()).append("\n");
        sb.append("短期记忆: ").append(shortTermMemory.size()).append(" 条\n");
        sb.append("长期记忆: 成功 ").append(longTermMemory.successCount())
                .append(" 条 / 失败 ").append(longTermMemory.failureCount()).append(" 条\n");
        return sb.toString();
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
