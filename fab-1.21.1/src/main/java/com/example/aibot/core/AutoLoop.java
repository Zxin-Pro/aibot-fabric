package com.example.aibot.core;

import com.example.aibot.action.ActionExecutor;
import com.example.aibot.action.TickActionDriver;
import com.example.aibot.action.ActionParser;
import com.example.aibot.config.AIConfig;
import com.example.aibot.entity.AIBotPlayer;
import com.example.aibot.entity.FakePlayerManager;
import com.example.aibot.llm.CacheStats;
import com.example.aibot.llm.LLMClient;
import com.example.aibot.llm.PromptBuilder;
import com.example.aibot.memory.LandmarkMemory;
import com.example.aibot.memory.LongTermMemory;
import com.example.aibot.memory.ShortTermMemory;
import com.example.aibot.state.StateCollector;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 自主循环：感知 → 决策 → 执行 → 记忆 的闭环。
 *
 * <p><b>长期自主运行的三项核心保障</b>（这是「让它一直玩下去」的关键）：</p>
 * <ol>
 *   <li><b>死亡自动重生</b>：通过 {@link #handleDeath} 检测死亡，
 *       等待 {@code respawnDelayTicks} 后调用
 *       {@link FakePlayerManager#respawn}，并从玩家列表重新取回新实例继续跑。
 *       循环本身<b>不会</b>因为死亡而停止。</li>
 *   <li><b>不再强制停机</b>：{@code maxStepsPerSession} 默认 0（无限）。
 *       若用户设了正数，到达后按 {@code autoRestart} 决定是否自动续跑。</li>
 *   <li><b>生存反射</b>：在 LLM 决策之外独立运行的安全网 ——
 *       血量过低强制逃跑/进食，这样不会因为 LLM 判断失误而猝死。</li>
 * </ol>
 *
 * <p>状态机：</p>
 * <pre>
 *   IDLE ──auto on──> COOLDOWN ──> WAITING_FOR_LLM ──响应──> (主线程执行) ──> COOLDOWN
 *                        │                │
 *                        │                └──超时/失败──> 记录失败，加长冷却
 *                        └── 每次 tick 先做生存反射 + 死亡检测
 * </pre>
 */
public final class AutoLoop {

    private static final Logger LOGGER = Logger.getLogger("aibot-loop");

    /** 循环状态。 */
    private enum Phase {
        /** 未运行。 */
        IDLE,
        /** 等待 LLM 响应。 */
        WAITING_FOR_LLM,
        /** 冷却中（避免请求过于密集）。 */
        COOLDOWN,
        /** 正在执行一个跨 tick 的动作（走路/挖掘/放置/进食/攻击）。 */
        EXECUTING,
        /** 已死亡，等待重生。 */
        DEAD_WAITING_RESPAWN
    }

    private final AIConfig config;
    private final CacheStats cacheStats;
    private final LLMClient llmClient;
    private final FakePlayerManager playerManager;
    private final ShortTermMemory shortTermMemory;
    private final LongTermMemory longTermMemory;
    private final LandmarkMemory landmarkMemory;
    private final TaskPlan plan = new TaskPlan();

    /** 是否处于运行状态（/aibot auto on 控制）。 */
    private final AtomicBoolean running = new AtomicBoolean(false);

    private volatile Phase phase = Phase.IDLE;

    /** 距下次决策还需等待的 tick 数。 */
    private int tickCounter = 0;

    /** 本会话已执行步数。 */
    private final AtomicLong stepCounter = new AtomicLong(0);

    /** 全部会话累计步数（跨自动重启累加，用于统计"一直在玩"）。 */
    private final AtomicLong totalStepCounter = new AtomicLong(0);

    /** 当前等待中的请求发出的 tick（用于超时检测）。 */
    private long requestSentAtTick = 0;

    /** 死亡时刻的 tick，用于计算重生延迟。 */
    private long diedAtTick = 0;

    /** 当前长期目标（自然语言）。 */
    private volatile String goal = "";

    /** 上一步执行结果反馈。 */
    private volatile String lastFeedback = "";

    /** 是否正在等待"制定计划"的响应（区别于普通动作响应）。 */
    private volatile boolean awaitingPlan = false;

    /** 是否已被要求停止（/aibot auto off 时置位，阻止自动重启）。 */
    private final AtomicBoolean manualStop = new AtomicBoolean(false);

    /**
     * 当前正在进行的跨 tick 动作驱动器。
     *
     * <p>这是「像真人一样」的核心：走路/挖掘/放置/进食都通过它
     * 逐 tick 走原版输入通道，而不是瞬间改世界。</p>
     */
    private volatile TickActionDriver activeDriver = null;

    /**
     * 上一 tick 的血量，用于检测「正在挨打」。
     *
     * <p>真人被打会立刻反应；这里每 tick 比较血量差，
     * 掉血超过 0.5 就触发撤离反射。</p>
     */
    private float lastKnownHealth = -1.0f;

    /** 当前动作对应的步号与动作名，用于动作完成后写记忆。 */
    private long activeStep = 0;
    private String activeActionName = "";
    private String activeActionParams = "";

    public AutoLoop(AIConfig config,
                    CacheStats cacheStats,
                    LLMClient llmClient,
                    FakePlayerManager playerManager,
                    ShortTermMemory shortTermMemory,
                    LongTermMemory longTermMemory,
                    LandmarkMemory landmarkMemory) {
        this.config = config;
        this.cacheStats = cacheStats;
        this.llmClient = llmClient;
        this.playerManager = playerManager;
        this.shortTermMemory = shortTermMemory;
        this.longTermMemory = longTermMemory;
        this.landmarkMemory = landmarkMemory;
    }

    // ------------------------------------------------------------------
    // 主循环
    // ------------------------------------------------------------------

    /**
     * 每个服务器 tick 调用一次。
     *
     * @param serverTick 当前服务器 tick 计数
     */
    public void tick(long serverTick) {
        if (!running.get()) {
            return;
        }

        // ============ 1. 死亡处理（最高优先级） ============
        if (!playerManager.isPlayerAlive()) {
            handleDeath(serverTick);
            return;
        }

        // 如果之前处于死亡等待状态但又活了（手动重生等），恢复正常
        if (phase == Phase.DEAD_WAITING_RESPAWN) {
            LOGGER.info("[AIBot] 假玩家已复活，自主循环恢复");
            phase = Phase.COOLDOWN;
            tickCounter = config.decisionIntervalTicks;
            lastFeedback = "你刚刚死亡并已重生，请检查周围环境是否安全，优先确保生存。";
        }

        // ============ 2. 正在执行跨 tick 动作：先推进它 ============
        if (phase == Phase.EXECUTING) {
            tickActiveAction();
            return;
        }

        // ============ 3. 步数上限（默认无限） ============
        if (config.maxStepsPerSession > 0
                && stepCounter.get() >= config.maxStepsPerSession) {
            if (config.autoRestart && !manualStop.get()) {
                LOGGER.info("[AIBot] 已达单次会话步数上限 " + config.maxStepsPerSession
                        + "，自动开始新一轮（累计 " + totalStepCounter.get() + " 步）");
                resetSession();
            } else {
                LOGGER.info("[AIBot] 已达单次会话步数上限 " + config.maxStepsPerSession + "，自动停止");
                stop();
            }
            return;
        }

        // ============ 4. 生存反射（独立于 LLM 的安全网） ============
        if (config.survivalReflex && phase != Phase.WAITING_FOR_LLM) {
            if (runSurvivalReflex()) {
                // 反射动作已交给驱动器，进入执行阶段
                phase = Phase.EXECUTING;
                return;
            }
        }

        // ============ 5. 常规决策节奏 ============
        switch (phase) {
            case IDLE:
                phase = Phase.COOLDOWN;
                tickCounter = config.decisionIntervalTicks;
                break;

            case COOLDOWN:
                if (tickCounter > 0) {
                    tickCounter--;
                    return;
                }
                requestDecision(playerManager.getPlayer(), serverTick);
                break;

            case WAITING_FOR_LLM:
                if (serverTick - requestSentAtTick > config.stepTimeoutTicks) {
                    LOGGER.warning("[AIBot] LLM 响应超时（超过 " + config.stepTimeoutTicks + " tick），本轮放弃");
                    shortTermMemory.add(stepCounter.get(), "decision", "", false, "LLM 响应超时");
                    phase = Phase.COOLDOWN;
                    tickCounter = config.decisionIntervalTicks;
                }
                break;

            case DEAD_WAITING_RESPAWN:
            default:
                break;
        }
    }

    /**
     * 处理死亡：等待若干 tick 后自动重生。
     *
     * <p>这是「一直玩下去」的核心。死亡不再终止循环，
     * 而是等一会儿重生后继续，并且把死亡原因写进记忆让 LLM 学会规避。</p>
     */
    private void handleDeath(long serverTick) {
        if (!config.autoRespawn) {
            if (phase != Phase.IDLE) {
                LOGGER.warning("[AIBot] 假玩家已死亡且未开启自动重生，自主循环停止");
                stop();
            }
            return;
        }

        // 首次检测到死亡
        if (phase != Phase.DEAD_WAITING_RESPAWN) {
            phase = Phase.DEAD_WAITING_RESPAWN;
            diedAtTick = serverTick;
            shortTermMemory.add(stepCounter.get(), "death", "", false, "假玩家死亡，准备自动重生");
            longTermMemory.recordFailure("survive", "", "死亡：需要更谨慎地管理血量与环境");
            longTermMemory.save();
            LOGGER.warning("[AIBot] 假玩家死亡，将在 " + config.respawnDelayTicks + " tick 后自动重生");
            return;
        }

        // 等待重生延迟
        if (serverTick - diedAtTick < config.respawnDelayTicks) {
            return;
        }

        // 执行重生
        ServerPlayer revived = playerManager.respawn(playerManager.getServerOf());
        if (revived == null) {
            LOGGER.warning("[AIBot] 重生失败，稍后重试");
            diedAtTick = serverTick; // 延后重试，避免每 tick 疯狂重试
            return;
        }

        phase = Phase.COOLDOWN;
        tickCounter = config.decisionIntervalTicks;
        lastFeedback = "你刚刚死亡并已重生。请先确认周围安全、检查饥饿与血量，再考虑继续原计划。";
        LOGGER.info("[AIBot] 重生完成，自主循环继续（累计 " + totalStepCounter.get() + " 步）");
    }

    /**
     * 生存反射：血量/饥饿过低时的强制自保动作。
     *
     * <p>这些动作不经过 LLM，直接在服务端执行，
     * 保证即使 LLM 掉线、超时或判断失误，假玩家也不会白白送死。</p>
     *
     * @return true 表示执行了反射动作
     */
    private boolean runSurvivalReflex() {
        // 用 ServerPlayer 而非 AIBotPlayer：重生后原版会把实体换成普通 ServerPlayer
        ServerPlayer bot = playerManager.getPlayer();
        if (bot == null) {
            return false;
        }

        try {
            float health = bot.getHealth();
            int food = bot.getFoodData().getFoodLevel();

            // 反射 0：正在挨打 / 刚掉过血 → 立即逃离并记录威胁
            // 真人被打会本能地先跑，而不是继续干活。
            float lastHealth = this.lastKnownHealth;
            this.lastKnownHealth = health;
            if (lastHealth > 0 && health < lastHealth - 0.5f) {
                float damage = lastHealth - health;
                ActionExecutor executor = new ActionExecutor(bot, config);
                ActionParser.ParsedAction flee = ActionParser.fromParams("flee", "distance", 20.0);
                ActionExecutor.ActionResult r = executor.execute(flee);
                if (r.async()) {
                    startActiveAction(executor, "flee", "受击反射",
                            "受到 " + String.format(java.util.Locale.ROOT, "%.0f", damage) + " 点伤害，先撤离");
                    longTermMemory.recordFailure("survive", "", "被攻击受伤，需要更警惕周围威胁");
                    LOGGER.info("[AIBot] 受击反射：掉血 " + damage + "，立即撤离");
                    return true;
                }
            }

            // 反射 1：血量极低 → 立即逃离
            if (health <= 6.0f) {
                ActionExecutor executor = new ActionExecutor(bot, config);
                ActionParser.ParsedAction flee = ActionParser.fromParams("flee", "distance", 24.0);
                ActionExecutor.ActionResult r = executor.execute(flee);
                if (r.async()) {
                    startActiveAction(executor, "flee", "反射触发", "血量过低(" + (int) health + ")自动逃跑");
                    LOGGER.info("[AIBot] 生存反射：血量 " + (int) health + " 过低，开始逃跑");
                    return true;
                }
            }

            // 反射 2：饥饿过低 → 自动进食（走原版 startUsingItem，有 1.6 秒时长）
            if (food <= 6) {
                ActionExecutor executor = new ActionExecutor(bot, config);
                ActionParser.ParsedAction eat = ActionParser.fromParams("eat", "item", "");
                ActionExecutor.ActionResult r = executor.execute(eat);
                if (r.async()) {
                    startActiveAction(executor, "eat", "反射触发", "饥饿值过低(" + food + ")自动进食");
                    LOGGER.info("[AIBot] 生存反射：饥饿 " + food + " 过低，开始进食");
                    return true;
                }
            }
        } catch (Throwable t) {
            LOGGER.log(Level.WARNING, "[AIBot] 生存反射执行异常", t);
        }
        return false;
    }

    // ------------------------------------------------------------------
    // 跨 tick 动作的执行
    // ------------------------------------------------------------------

    /**
     * 登记一个正在进行的跨 tick 动作。
     *
     * @param executor 执行器（持有驱动器）
     * @param action   动作名
     * @param params   参数摘要
     * @param note     反馈给 LLM 的说明
     */
    private void startActiveAction(ActionExecutor executor, String action, String params, String note) {
        this.activeDriver = executor.getDriver();
        this.activeStep = stepCounter.get();
        this.activeActionName = action;
        this.activeActionParams = params;
        this.lastFeedback = note;
        this.phase = Phase.EXECUTING;
    }

    /**
     * 接管一个由外部（如 /aibot do 命令）发起的跨 tick 动作。
     *
     * <p>用于调试：即使自主循环没开，手动触发的走路/挖掘也能被正常推进完成。</p>
     */
    public void adoptExternalAction(ActionExecutor executor) {
        startActiveAction(executor, "manual", "命令触发", "手动执行的动作");
    }

    /**
     * 推进当前跨 tick 动作，完成时写记忆并回到冷却。
     *
     * <p>这是「像真人一样」的关键：一个动作可能持续几十到几百 tick
     * （走路几百 tick、挖黑曜石 188 tick、吃东西 32 tick），
     * 期间不会发起新的 LLM 请求，也不会跳过时间。</p>
     */
    private void tickActiveAction() {
        TickActionDriver driver = this.activeDriver;
        if (driver == null) {
            phase = Phase.COOLDOWN;
            tickCounter = config.decisionIntervalTicks;
            return;
        }

        driver.tick();

        if (!driver.isDone()) {
            return; // 还在进行中
        }

        // 动作结束：写记忆
        boolean ok = driver.succeeded();
        String msg = driver.result();
        shortTermMemory.add(activeStep, activeActionName, activeActionParams, ok, msg);

        if (ok) {
            longTermMemory.recordSuccess(activeActionName, activeActionParams);
            if (!plan.isEmpty()) {
                plan.completeCurrent();
            }
        } else {
            longTermMemory.recordFailure(activeActionName, activeActionParams, msg);
            if (!plan.isEmpty()) {
                boolean skipped = plan.failCurrent();
                if (skipped) {
                    LOGGER.warning("[AIBot] 任务连续失败过多，已跳过：" + activeActionName);
                }
            }
        }
        longTermMemory.save();

        lastFeedback = "上一步 " + activeActionName + (ok ? " 成功：" : " 失败：") + msg;
        LOGGER.info("[AIBot] 第 " + activeStep + " 步 " + activeActionName
                + " -> " + (ok ? "成功" : "失败") + " (" + msg + ") 用时 "
                + driver.getElapsedTicks() + " tick");

        this.activeDriver = null;
        this.activeActionName = "";
        this.activeActionParams = "";
        phase = Phase.COOLDOWN;
        tickCounter = config.decisionIntervalTicks;
    }

    // ------------------------------------------------------------------
    // 决策
    // ------------------------------------------------------------------

    /**
     * 发起一次决策：采集状态 → 构建提示词 → 异步请求 LLM。
     */
    private void requestDecision(ServerPlayer player, long serverTick) {
        phase = Phase.WAITING_FOR_LLM;
        requestSentAtTick = serverTick;

        // 1. 采集状态（需要 ServerPlayer 与 ServerLevel）
        String stateJson;
        String dimension = "";
        int px = 0, py = 0, pz = 0;
        try {
            ServerLevel level = (ServerLevel) player.level();
            px = player.blockPosition().getX();
            py = player.blockPosition().getY();
            pz = player.blockPosition().getZ();
            dimension = level.dimension().location().toString();
            stateJson = StateCollector.collect(player, this.goal);
        } catch (Throwable t) {
            LOGGER.log(Level.WARNING, "[AIBot] 状态采集失败", t);
            stateJson = "{}";
        }

        // 2. 构建提示词（静态前缀 + 动态后缀）
        String shortMem = shortTermMemory.serialize();
        String longMem = longTermMemory.serialize();
        String planText = plan.serialize();
        String landmarkText = landmarkMemory.serialize(px, py, pz, dimension);

        // 卡住检测：如果计划中的当前任务反复失败，给出强制换策略指令
        String feedback = this.lastFeedback;
        if (isStuck()) {
            feedback = (feedback == null ? "" : feedback + " ")
                    + "【重要】当前任务已连续失败多次，必须换一种完全不同的方式，"
                    + "或者先完成一个更容易达成的中间目标。";
        }
        // 计划为空且已设长期目标 → 请求 LLM 制定计划
        if (plan.isEmpty() && !goal.isEmpty()) {
            awaitingPlan = true;
            feedback = (feedback == null ? "" : feedback + " ")
                    + "【当前没有执行计划】请用 plan 动作把长期目标拆解成 3~6 个具体步骤。";
        } else {
            awaitingPlan = false;
        }

        PromptBuilder.BuiltPrompt prompt = PromptBuilder.build(
                stateJson, shortMem, longMem, planText, landmarkText, this.goal, feedback);

        // 3. 异步请求（不阻塞主线程）
        final long currentStep = stepCounter.incrementAndGet();
        totalStepCounter.incrementAndGet();
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
     * 处理 LLM 响应：解析动作 → 执行 → 记录记忆 → 推进计划 → 进入冷却。
     *
     * <p><b>线程说明</b>：本方法在 LLM worker 线程上被调用。
     * Minecraft 世界操作必须在主线程执行，因此这里用服务器 execute 切回主线程。</p>
     */
    private void handleLlmResponse(String content, long step) {
        ServerPlayer player = playerManager.getPlayer();
        if (player == null) {
            phase = Phase.COOLDOWN;
            tickCounter = config.decisionIntervalTicks;
            return;
        }

        player.level().getServer().execute(() -> {
            try {
                ActionParser.ParsedAction parsed = ActionParser.parse(content);
                if (parsed == null) {
                    LOGGER.warning("[AIBot] 无法解析 LLM 输出: " + truncate(content, 200));
                    shortTermMemory.add(step, "parse", "", false, "输出不是合法 JSON 动作");
                    lastFeedback = "上一次输出不是合法 JSON，请只输出一个 JSON 对象。";
                    return;
                }

                // 特殊动作：制定计划
                if ("plan".equals(parsed.action())) {
                    applyPlan(parsed, step);
                    return;
                }

                // 特殊动作：记录地标
                if ("remember".equals(parsed.action())) {
                    applyRemember(parsed, step);
                    return;
                }

                // 普通动作：统一用 ActionExecutor。
                // 注意构造函数现在需要 config（TickActionDriver 用它读超时等配置）。
                ActionExecutor executor = new ActionExecutor(player, config);
                ActionExecutor.ActionResult result = executor.execute(parsed);

                // 跨 tick 动作（走路/挖掘/放置/进食/攻击/拾取）：
                // 只是"启动了"，真正完成要等若干 tick。这里登记后进入 EXECUTING，
                // 由 tickActiveAction() 在动作真正结束时统一写记忆。
                if (result.async()) {
                    startActiveAction(executor, parsed.action(), parsed.paramsSummary(), result.message());
                    LOGGER.info("[AIBot] 第 " + step + " 步启动 " + parsed.action() + "：" + result.message());
                    return;
                }

                // 瞬时动作（合成/聊天/环顾/存储等）：直接结算
                shortTermMemory.add(step, parsed.action(), parsed.paramsSummary(),
                        result.success(), result.message());

                if (result.success()) {
                    longTermMemory.recordSuccess(parsed.action(), targetOf(parsed));
                    // 成功后推进计划
                    if (!plan.isEmpty()) {
                        plan.completeCurrent();
                    }
                } else {
                    longTermMemory.recordFailure(parsed.action(), targetOf(parsed), result.message());
                    if (!plan.isEmpty()) {
                        boolean skipped = plan.failCurrent();
                        if (skipped) {
                            LOGGER.warning("[AIBot] 任务连续失败过多，已跳过：" + parsed.action());
                            lastFeedback = "当前任务判定为做不到，已跳过，请继续下一个任务。";
                        }
                    }
                }
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

    /** 处理 LLM 返回的计划（plan 动作）。 */
    private void applyPlan(ActionParser.ParsedAction parsed, long step) {
        java.util.List<String> steps = parsed.getStringList("steps");
        if (steps.isEmpty()) {
            // 兼容：有些模型会返回单个 "step" 字符串
            String single = parsed.getString("step", "");
            if (!single.isEmpty()) {
                steps = java.util.Arrays.asList(single);
            }
        }
        if (steps.isEmpty()) {
            shortTermMemory.add(step, "plan", "", false, "plan 动作缺少 steps 数组");
            lastFeedback = "plan 动作需要一个 steps 字符串数组，例如 {\"action\":\"plan\",\"steps\":[\"砍树x20\",\"合成工作台\"]}";
            return;
        }
        plan.replace(steps);
        pendingPlanPersist = true;
        shortTermMemory.add(step, "plan", "共" + steps.size() + "步", true, "已制定计划");
        lastFeedback = "计划已制定，共 " + steps.size() + " 步，请开始执行第一步。";
        LOGGER.info("[AIBot] 已制定计划（" + steps.size() + " 步）: " + String.join(" -> ", steps));
    }

    /** 处理 LLM 返回的地标记录（remember 动作）。 */
    private void applyRemember(ActionParser.ParsedAction parsed, long step) {
        ServerPlayer player = playerManager.getPlayer();
        if (player == null) {
            return;
        }
        ServerLevel level = (ServerLevel) player.level();
        String type = parsed.getString("type", "other");
        String name = parsed.getString("name", type);
        int x = parsed.getInt("x", player.blockPosition().getX());
        int y = parsed.getInt("y", player.blockPosition().getY());
        int z = parsed.getInt("z", player.blockPosition().getZ());
        String note = parsed.getString("note", "");

        boolean added = landmarkMemory.remember(type, name, x, y, z,
                level.dimension().location().toString(), note);
        shortTermMemory.add(step, "remember", type + " " + name, true,
                added ? "已记录新地标" : "已更新地标");
        lastFeedback = "地标已记录：" + name + " @ (" + x + "," + y + "," + z + ")";
        LOGGER.info("[AIBot] 记录地标 " + type + " " + name + " @ " + x + "," + y + "," + z);
    }

    /** 是否需要把计划落盘（由 tick 或主类统一处理）。 */
    private volatile boolean pendingPlanPersist = false;

    /** 取出并清除"计划待落盘"标志。 */
    public boolean consumePlanPersistFlag() {
        if (pendingPlanPersist) {
            pendingPlanPersist = false;
            return true;
        }
        return false;
    }

    /** 处理 LLM 失败。 */
    private void handleLlmFailure(String error, long step) {
        shortTermMemory.add(step, "decision", "", false, "LLM 调用失败: " + error);
        lastFeedback = "上一次 LLM 调用失败：" + error;
        LOGGER.warning("[AIBot] LLM 调用失败: " + error);
        phase = Phase.COOLDOWN;
        tickCounter = config.decisionIntervalTicks * 2;
    }

    /** 判断是否「卡住」（当前计划任务反复失败）。 */
    private boolean isStuck() {
        if (!plan.isEmpty() && plan.currentFailCount() >= config.stuckThreshold) {
            return true;
        }
        ShortTermMemory.Entry last = shortTermMemory.last();
        if (last == null) {
            return false;
        }
        return shortTermMemory.consecutiveFailures(last.action()) >= config.stuckThreshold;
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
            manualStop.set(false);
            phase = Phase.IDLE;
            tickCounter = 0;
            stepCounter.set(0);
            LOGGER.info("[AIBot] 自主循环已开启"
                    + (config.maxStepsPerSession <= 0 ? "（无步数上限，将持续运行）"
                    : "（上限 " + config.maxStepsPerSession + " 步，自动续跑=" + config.autoRestart + "）"));
        }
    }

    /** 停止自主循环（手动停止，不会被自动重启覆盖）。 */
    public void stop() {
        if (running.compareAndSet(true, false)) {
            manualStop.set(true);
            // 取消正在进行的跨 tick 动作，避免残留输入让玩家一直走
            TickActionDriver d = this.activeDriver;
            if (d != null) {
                d.cancel();
                this.activeDriver = null;
            }
            phase = Phase.IDLE;
            LOGGER.info("[AIBot] 自主循环已停止（本会话 " + stepCounter.get()
                    + " 步，累计 " + totalStepCounter.get() + " 步）");
        }
    }

    /** 重置会话计数（自动续跑时使用，不清空计划与记忆）。 */
    private void resetSession() {
        stepCounter.set(0);
        phase = Phase.IDLE;
        tickCounter = 0;
    }

    /** 是否正在运行。 */
    public boolean isRunning() {
        return running.get();
    }

    /** 本会话步数。 */
    public long getStepCount() {
        return stepCounter.get();
    }

    /** 累计步数（跨自动续跑）。 */
    public long getTotalStepCount() {
        return totalStepCounter.get();
    }

    /** 设置长期目标（会清空旧计划，触发重新规划）。 */
    public void setGoal(String goal) {
        this.goal = goal == null ? "" : goal.trim();
        // 目标变了，旧计划作废
        plan.clear();
        LOGGER.info("[AIBot] 长期目标已更新，旧计划已清空，将重新规划");
    }

    /** 当前长期目标。 */
    public String getGoal() {
        return this.goal;
    }

    /** 当前计划（供命令层展示）。 */
    public TaskPlan getPlan() {
        return plan;
    }

    /** 从存档恢复计划。 */
    public void restorePlan(java.util.List<TaskPlan.Task> tasks) {
        plan.importTasks(tasks);
    }

    /** 生成 /aibot status 的状态文本。 */
    public String statusReport() {
        ServerPlayer player = playerManager.getPlayer();
        StringBuilder sb = new StringBuilder();
        sb.append("===== AIBot 状态 =====\n");
        sb.append("假玩家: ").append(player == null ? "未生成"
                : player.getName().getString() + (player.isAlive() ? "（存活）" : "（已死亡）")).append("\n");
        sb.append("自主循环: ").append(running.get() ? "运行中" : "已关闭").append("\n");
        sb.append("当前阶段: ").append(phase).append("\n");
        sb.append("已执行步数: ").append(stepCounter.get())
                .append(config.maxStepsPerSession > 0 ? " / " + config.maxStepsPerSession : "（无上限）")
                .append("，累计 ").append(totalStepCounter.get()).append("\n");
        sb.append("自动重生: ").append(config.autoRespawn ? "开启" : "关闭").append("\n");
        sb.append("生存反射: ").append(config.survivalReflex ? "开启" : "关闭").append("\n");
        sb.append("长期目标: ").append(goal.isEmpty() ? "(未设定)" : goal).append("\n");
        sb.append("当前计划: ").append(plan.isEmpty() ? "(无)"
                : plan.doneCount() + "/" + plan.size() + " 步已完成").append("\n");
        sb.append("地标数量: ").append(landmarkMemory.size()).append("\n");
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
