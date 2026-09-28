package com.example.aibot.core;

import com.example.aibot.action.ActionExecutor;
import com.example.aibot.action.TickActionDriver;
import com.example.aibot.action.ActionParser;
import com.example.aibot.config.AIConfig;
import com.example.aibot.entity.AIBotPlayer;
import com.example.aibot.entity.BotProfile;
import com.example.aibot.entity.MultiBotManager;
import com.example.aibot.llm.CacheStats;
import com.example.aibot.llm.LLMClient;
import com.example.aibot.llm.PromptBuilder;
import com.example.aibot.memory.ChatMemory;
import com.example.aibot.memory.ContextCompressor;
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
 * 单个智能体的自主循环：感知 → 决策 → 执行 → 记忆 的闭环。
 *
 * <p><b>本版重点：无人干预时的自主游玩</b></p>
 *
 * <p>旧版虽然支持「无步数上限」，但决策提示词里默认写着
 * 「帮助实现用户的长期目标」—— 没有用户目标时，模型容易反复输出 idle
 * 或原地打转，事实上还是需要有人盯着给目标。本版补上了这一环：</p>
 * <ul>
 *   <li>{@code autonomousMode} 开启后，提示词明确要求「没有命令时自己决定做什么」，
 *       并给出「先生存、再发展」的优先级；</li>
 *   <li>空闲自愈：长时间没有推进（连续 idle / 空计划）时，
 *       主动注入「你正在浪费生命，立刻找事做」的反馈；</li>
 *   <li>目标自举：没有长期目标时，由系统给出一个生存导向的兜底目标，
 *       避免模型在「无目标」状态里卡住。</li>
 * </ul>
 *
 * <p><b>长期运行的三项保障</b>（保持原有设计）：</p>
 * <ol>
 *   <li>死亡自动重生，循环不会因死亡终止；</li>
 *   <li>步数上限默认 0（无限），即使设了正数也会自动续跑；</li>
 *   <li>上下文压缩，保证提示词长度恒定。</li>
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

    private enum Phase {
        IDLE,
        WAITING_FOR_LLM,
        COOLDOWN,
        EXECUTING,
        DEAD_WAITING_RESPAWN
    }

    private final AIConfig config;
    private final CacheStats cacheStats;
    private final LLMClient llmClient;
    private final MultiBotManager bots;
    private final ShortTermMemory shortTermMemory;
    private final LongTermMemory longTermMemory;
    private final LandmarkMemory landmarkMemory;
    private final TaskPlan plan = new TaskPlan();
    private final BotProfile profile;
    private final ContextCompressor compressor = new ContextCompressor();
    private final ChatMemory chatMemory;

    private final AtomicBoolean running = new AtomicBoolean(false);

    private volatile Phase phase = Phase.IDLE;
    private int tickCounter = 0;

    private final AtomicLong stepCounter = new AtomicLong(0);
    private final AtomicLong totalStepCounter = new AtomicLong(0);

    private long requestSentAtTick = 0;
    private long diedAtTick = 0;

    private volatile String goal = "";
    private volatile String lastFeedback = "";
    private volatile boolean awaitingPlan = false;
    private final AtomicBoolean manualStop = new AtomicBoolean(false);

    private volatile TickActionDriver activeDriver = null;

    /** 上一 tick 的血量，用于检测「正在挨打」。 */
    private float lastKnownHealth = -1.0f;

    private long activeStep = 0;
    private String activeActionName = "";
    private String activeActionParams = "";

    /** 连续输出 idle 的次数（用于空闲自愈）。 */
    private int consecutiveIdle = 0;

    /** 连续没有推进计划的步数（用于空闲自愈）。 */
    private int stepsWithoutProgress = 0;

    /** 上一次主动发言的步号（用于说话冷却）。 */
    private long lastChatStep = -1000;

    /** 自主模式是否被玩家显式关闭（关闭后回到「等指令」模式）。 */
    private final AtomicBoolean autonomousOverride = new AtomicBoolean(false);

    public AutoLoop(AIConfig config,
                    CacheStats cacheStats,
                    LLMClient llmClient,
                    MultiBotManager bots,
                    ShortTermMemory shortTermMemory,
                    LongTermMemory longTermMemory,
                    LandmarkMemory landmarkMemory,
                    BotProfile profile) {
        this(config, cacheStats, llmClient, bots, shortTermMemory, longTermMemory,
                landmarkMemory, profile, new ChatMemory());
    }

    public AutoLoop(AIConfig config,
                    CacheStats cacheStats,
                    LLMClient llmClient,
                    MultiBotManager bots,
                    ShortTermMemory shortTermMemory,
                    LongTermMemory longTermMemory,
                    LandmarkMemory landmarkMemory,
                    BotProfile profile,
                    ChatMemory chatMemory) {
        this.config = config;
        this.cacheStats = cacheStats;
        this.llmClient = llmClient;
        this.bots = bots;
        this.shortTermMemory = shortTermMemory;
        this.longTermMemory = longTermMemory;
        this.landmarkMemory = landmarkMemory;
        this.profile = profile;
        this.chatMemory = chatMemory;
    }

    private ServerPlayer self() {
        MultiBotManager.Agent a = bots.get(profile.name);
        return a == null ? null : a.player();
    }

    private net.minecraft.server.MinecraftServer selfServer() {
        ServerPlayer p = self();
        if (p != null && p.level() != null && p.level().getServer() != null) {
            return p.level().getServer();
        }
        return null;
    }

    // ------------------------------------------------------------------
    // 主循环
    // ------------------------------------------------------------------

    public void tick(long serverTick) {
        if (!running.get()) {
            return;
        }

        // ============ 1. 死亡处理（最高优先级） ============
        if (!isAlive()) {
            handleDeath(serverTick);
            return;
        }

        if (phase == Phase.DEAD_WAITING_RESPAWN) {
            LOGGER.info("[AIBot] " + profile.name + " 已复活，自主循环恢复");
            phase = Phase.COOLDOWN;
            tickCounter = config.decisionIntervalTicks;
            lastFeedback = "你刚刚死亡并已重生，请检查周围环境是否安全，优先确保生存。";
        }

        // ============ 2. 正在执行跨 tick 动作 ============
        if (phase == Phase.EXECUTING) {
            tickActiveAction();
            return;
        }

        // ============ 3. 步数上限（默认无限） ============
        if (config.maxStepsPerSession > 0
                && stepCounter.get() >= config.maxStepsPerSession) {
            if (config.autoRestart && !manualStop.get()) {
                LOGGER.info("[AIBot] " + profile.name + " 已达单次会话步数上限 "
                        + config.maxStepsPerSession + "，自动开始新一轮（累计 "
                        + totalStepCounter.get() + " 步）");
                resetSession();
            } else {
                LOGGER.info("[AIBot] " + profile.name + " 已达步数上限，自动停止");
                stop();
            }
            return;
        }

        // ============ 4. 生存反射 ============
        if (config.survivalReflex && phase != Phase.WAITING_FOR_LLM) {
            if (runSurvivalReflex()) {
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
                requestDecision(self(), serverTick);
                break;

            case WAITING_FOR_LLM:
                if (serverTick - requestSentAtTick > config.stepTimeoutTicks) {
                    LOGGER.warning("[AIBot] " + profile.name + " LLM 响应超时（超过 "
                            + config.stepTimeoutTicks + " tick），本轮放弃");
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
     * 死亡处理：等待若干 tick 后自动重生。
     */
    private void handleDeath(long serverTick) {
        if (!config.autoRespawn) {
            if (phase != Phase.IDLE) {
                LOGGER.warning("[AIBot] " + profile.name + " 已死亡且未开启自动重生，循环停止");
                stop();
            }
            return;
        }

        if (phase != Phase.DEAD_WAITING_RESPAWN) {
            phase = Phase.DEAD_WAITING_RESPAWN;
            diedAtTick = serverTick;
            shortTermMemory.add(stepCounter.get(), "death", "", false, "假玩家死亡，准备自动重生");
            longTermMemory.recordFailure("survive", "", "死亡：需要更谨慎地管理血量与环境");
            longTermMemory.save();
            announce(1, profile.name + " 死亡了，将在 "
                    + (config.respawnDelayTicks / 20) + " 秒后自动重生");
            LOGGER.warning("[AIBot] " + profile.name + " 死亡，将在 "
                    + config.respawnDelayTicks + " tick 后自动重生");
            return;
        }

        if (serverTick - diedAtTick < config.respawnDelayTicks) {
            return;
        }

        ServerPlayer revived;
        try {
            net.minecraft.server.MinecraftServer srv = selfServer();
            if (srv == null) {
                LOGGER.warning("[AIBot] 无法取得服务器实例，重生延后重试");
                diedAtTick = serverTick;
                return;
            }
            revived = bots.respawn(srv, profile.name);
        } catch (Throwable t) {
            LOGGER.log(Level.WARNING, "[AIBot] 重生过程异常，稍后重试", t);
            diedAtTick = serverTick;
            return;
        }
        if (revived == null) {
            LOGGER.warning("[AIBot] 重生失败，稍后重试");
            diedAtTick = serverTick;
            return;
        }

        phase = Phase.COOLDOWN;
        tickCounter = config.decisionIntervalTicks;
        lastFeedback = "你刚刚死亡并已重生。请先确认周围安全、检查饥饿与血量，再考虑继续原计划。";
        // 死亡后目标与计划可能已不适用，清掉让模型重新规划
        plan.clear();
        announce(1, profile.name + " 已重生，继续自主活动");
        LOGGER.info("[AIBot] " + profile.name + " 重生完成，自主循环继续（累计 "
                + totalStepCounter.get() + " 步）");
    }

    /**
     * 生存反射：血量/饥饿过低时的强制自保动作，不经过 LLM。
     */
    private boolean runSurvivalReflex() {
        ServerPlayer bot = self();
        if (bot == null) {
            return false;
        }

        try {
            float health = bot.getHealth();
            int food = bot.getFoodData().getFoodLevel();

            // 反射 0：正在挨打 → 立即逃离
            float lastHealth = this.lastKnownHealth;
            this.lastKnownHealth = health;
            if (lastHealth > 0 && health < lastHealth - 0.5f) {
                float damage = lastHealth - health;
                ActionExecutor executor = new ActionExecutor(bot, config);
                ActionParser.ParsedAction flee = ActionParser.fromParams("flee", "distance", 20.0);
                ActionExecutor.ActionResult r = executor.execute(flee);
                if (r.async()) {
                    startActiveAction(executor, "flee", "受击反射",
                            "受到 " + fmt(damage) + " 点伤害，先撤离");
                    longTermMemory.recordFailure("survive", "", "被攻击受伤，需要更警惕周围威胁");
                    LOGGER.info("[AIBot] " + profile.name + " 受击反射：掉血 " + damage + "，立即撤离");
                    return true;
                }
            }

            // 反射 1：血量极低 → 逃跑
            if (health <= 6.0f) {
                ActionExecutor executor = new ActionExecutor(bot, config);
                ActionParser.ParsedAction flee = ActionParser.fromParams("flee", "distance", 24.0);
                ActionExecutor.ActionResult r = executor.execute(flee);
                if (r.async()) {
                    startActiveAction(executor, "flee", "反射触发", "血量过低(" + (int) health + ")自动逃跑");
                    LOGGER.info("[AIBot] " + profile.name + " 生存反射：血量 " + (int) health + " 过低，逃跑");
                    return true;
                }
            }

            // 反射 2：饥饿过低 → 进食
            if (food <= 6) {
                ActionExecutor executor = new ActionExecutor(bot, config);
                ActionParser.ParsedAction eat = ActionParser.fromParams("eat", "item", "");
                ActionExecutor.ActionResult r = executor.execute(eat);
                if (r.async()) {
                    startActiveAction(executor, "eat", "反射触发", "饥饿值过低(" + food + ")自动进食");
                    LOGGER.info("[AIBot] " + profile.name + " 生存反射：饥饿 " + food + " 过低，进食");
                    return true;
                }
            }
        } catch (Throwable t) {
            LOGGER.log(Level.WARNING, "[AIBot] 生存反射执行异常", t);
        }
        return false;
    }

    // ------------------------------------------------------------------
    // 跨 tick 动作
    // ------------------------------------------------------------------

    private void startActiveAction(ActionExecutor executor, String action, String params, String note) {
        this.activeDriver = executor.getDriver();
        this.activeStep = stepCounter.get();
        this.activeActionName = action;
        this.activeActionParams = params;
        this.lastFeedback = note;
        this.phase = Phase.EXECUTING;
    }

    /** 接管外部（如 /aibot do）发起的跨 tick 动作。 */
    public void adoptExternalAction(ActionExecutor executor) {
        startActiveAction(executor, "manual", "命令触发", "手动执行的动作");
    }

    /**
     * 推进当前跨 tick 动作，完成时写记忆并回到冷却。
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
            return;
        }

        boolean ok = driver.succeeded();
        String msg = driver.result();
        shortTermMemory.add(activeStep, activeActionName, activeActionParams, ok, msg);

        if (ok) {
            longTermMemory.recordSuccess(activeActionName, activeActionParams);
            stepsWithoutProgress = 0;
            if (!plan.isEmpty()) {
                boolean wasLast = plan.doneCount() == plan.size() - 1;
                plan.completeCurrent();
                if (wasLast) {
                    announce(1, profile.name + " 完成了当前计划");
                }
            }
        } else {
            longTermMemory.recordFailure(activeActionName, activeActionParams, msg);
            stepsWithoutProgress++;
            if (!plan.isEmpty()) {
                boolean skipped = plan.failCurrent();
                if (skipped) {
                    LOGGER.warning("[AIBot] " + profile.name + " 任务连续失败过多，已跳过：" + activeActionName);
                }
            }
        }
        longTermMemory.save();

        // 统计连续 idle
        if ("idle".equals(activeActionName)) {
            consecutiveIdle++;
        } else {
            consecutiveIdle = 0;
        }

        lastFeedback = "上一步 " + activeActionName + (ok ? " 成功：" : " 失败：") + msg;
        LOGGER.info("[AIBot] " + profile.name + " 第 " + activeStep + " 步 " + activeActionName
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
     * 发起一次决策：采集状态 → 构建分层提示词 → 异步请求 LLM。
     */
    private void requestDecision(ServerPlayer player, long serverTick) {
        phase = Phase.WAITING_FOR_LLM;
        requestSentAtTick = serverTick;

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

        String shortMem = compressor.compress(shortTermMemory);
        String longMem = longTermMemory.serialize();
        String planText = plan.serialize();
        String landmarkText = landmarkMemory.serialize(px, py, pz, dimension);

        boolean autonomous = isAutonomous();

        // ---- 组装反馈（这是引导模型行为的主要手段，全部走快变层） ----
        StringBuilder fb = new StringBuilder();
        if (this.lastFeedback != null && !this.lastFeedback.isEmpty()) {
            fb.append(this.lastFeedback);
        }

        // 卡住检测
        if (isStuck()) {
            fb.append(" 【重要】当前任务已连续失败多次，必须换一种完全不同的方式，"
                    + "或者先完成一个更容易达成的中间目标。");
        }

        // 空闲自愈：这是「无人干预也能自己玩」的关键补丁。
        // 没有它，模型在无明确目标时会不断 idle，看起来像卡死了。
        if (autonomous) {
            if (consecutiveIdle >= 2) {
                fb.append(" 【空闲警告】你已经连续 ").append(consecutiveIdle)
                        .append(" 次没有做任何事。这是不可接受的——"
                                + "立刻从「生存所需」里挑一件具体的事去做，"
                                + "例如砍树、挖石头、找食物、造庇护所。禁止再输出 idle。");
            } else if (stepsWithoutProgress >= config.stuckThreshold * 2) {
                fb.append(" 【停滞警告】你已经连续 ").append(stepsWithoutProgress)
                        .append(" 步没有取得任何进展。请重新评估局势："
                                + "换一个目标、换一种做法，或者先解决眼前的生存问题（血量/饥饿）。");
            }
        }

        // 没有计划 → 要求规划
        boolean needPlan = false;
        if (plan.isEmpty() && !goal.isEmpty()) {
            needPlan = true;
            awaitingPlan = true;
            fb.append(" 【当前没有执行计划】请用 plan 动作把长期目标拆解成 3~6 个具体步骤。");
        } else {
            awaitingPlan = false;
        }

        // 自主模式且完全没有目标 → 让模型自己立目标
        if (autonomous && goal.isEmpty() && plan.isEmpty()) {
            fb.append(" 【自主模式】当前没有任何人为你设定目标，也没有执行计划。"
                    + "请先用 plan 动作制定一份「先生存、再发展」的计划并立刻开始执行。"
                    + "不要询问、不要等待，自己决定。");
        }

        // 有人搭话 → 提醒回应（仅在允许说话时）
        String chatText = chatMemory.serialize();
        if (config.allowChat && chatMemory.hasRecent(stepCounter.get(), 3)) {
            ChatMemory.Entry lastChat = chatMemory.last();
            if (lastChat != null) {
                fb.append(" 【有人在跟你说话】").append(lastChat.speaker())
                        .append(" 说：「").append(lastChat.message())
                        .append("」。请先用 chat 动作简短回应他，再继续手上的事。"
                                + "回应要自然、符合你的性格，一句话即可。");
            }
        }

        // 说话冷却：不让人搭话时，也要限制主动发言频率，避免刷屏
        if (config.allowChat && config.chatCooldownSteps > 0
                && stepCounter.get() - lastChatStep < config.chatCooldownSteps) {
            // 不加额外指令，靠系统提示词里「不要刷屏」的约束即可。
            // 这里只是记录状态，供后续可能的硬拦截使用。
        }

        PromptBuilder.BuiltPrompt prompt = PromptBuilder.build(
                stateJson, shortMem, longMem, planText, landmarkText, this.goal,
                fb.toString(), profile.name, profile.personality, chatText, autonomous);

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
     * 处理 LLM 响应：解析 → 执行 → 记忆 → 推进计划。
     *
     * <p>本方法在 LLM worker 线程上调用，世界操作切回主线程执行。</p>
     */
    private void handleLlmResponse(String content, long step) {
        ServerPlayer player = self();
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

                if ("plan".equals(parsed.action())) {
                    applyPlan(parsed, step);
                    return;
                }

                if ("remember".equals(parsed.action())) {
                    applyRemember(parsed, step);
                    return;
                }

                // 聊天冷却硬拦截：超过频率则丢弃这次发言，避免刷屏。
                // 只拦「主动说话」，被搭话时的回应不受影响（那属于对话）。
                if ("chat".equals(parsed.action()) && config.allowChat
                        && config.chatCooldownSteps > 0
                        && step - lastChatStep < config.chatCooldownSteps
                        && !chatMemory.hasRecent(step, 3)) {
                    shortTermMemory.add(step, "chat", "", false, "发言过于频繁，已跳过");
                    lastFeedback = "发言过于频繁，本次跳过。请专注手上的事。";
                    return;
                }
                if ("chat".equals(parsed.action())) {
                    lastChatStep = step;
                }

                ActionExecutor executor = new ActionExecutor(player, config);
                ActionExecutor.ActionResult result = executor.execute(parsed);

                if (result.async()) {
                    startActiveAction(executor, parsed.action(), parsed.paramsSummary(), result.message());
                    LOGGER.info("[AIBot] " + profile.name + " 第 " + step + " 步启动 "
                            + parsed.action() + "：" + result.message());
                    return;
                }

                // 瞬时动作：直接结算
                shortTermMemory.add(step, parsed.action(), parsed.paramsSummary(),
                        result.success(), result.message());

                if (result.success()) {
                    longTermMemory.recordSuccess(parsed.action(), targetOf(parsed));
                    stepsWithoutProgress = 0;
                    if (!plan.isEmpty()) {
                        plan.completeCurrent();
                    }
                } else {
                    longTermMemory.recordFailure(parsed.action(), targetOf(parsed), result.message());
                    stepsWithoutProgress++;
                    if (!plan.isEmpty()) {
                        boolean skipped = plan.failCurrent();
                        if (skipped) {
                            LOGGER.warning("[AIBot] " + profile.name + " 任务连续失败过多，已跳过："
                                    + parsed.action());
                            lastFeedback = "当前任务判定为做不到，已跳过，请继续下一个任务。";
                        }
                    }
                }
                longTermMemory.save();

                if ("idle".equals(parsed.action())) {
                    consecutiveIdle++;
                } else {
                    consecutiveIdle = 0;
                }

                lastFeedback = "上一步 " + parsed.action() + " "
                        + (result.success() ? "成功" : "失败") + "：" + result.message();

                LOGGER.info("[AIBot] " + profile.name + " 第 " + step + " 步 " + parsed.action()
                        + " -> " + (result.success() ? "成功" : "失败") + " (" + result.message() + ")");

            } catch (Throwable t) {
                LOGGER.log(Level.SEVERE, "[AIBot] 执行动作时发生严重异常", t);
                shortTermMemory.add(step, "execute", "", false,
                        "执行异常: " + t.getClass().getSimpleName());
            } finally {
                phase = Phase.COOLDOWN;
                tickCounter = config.decisionIntervalTicks;
            }
        });
    }

    /** 处理 plan 动作。 */
    private void applyPlan(ActionParser.ParsedAction parsed, long step) {
        java.util.List<String> steps = parsed.getStringList("steps");
        if (steps.isEmpty()) {
            String single = parsed.getString("step", "");
            if (!single.isEmpty()) {
                steps = java.util.Arrays.asList(single);
            }
        }
        if (steps.isEmpty()) {
            shortTermMemory.add(step, "plan", "", false, "plan 动作缺少 steps 数组");
            lastFeedback = "plan 动作需要一个 steps 字符串数组，"
                    + "例如 {\"action\":\"plan\",\"steps\":[\"砍树x20\",\"合成工作台\"]}";
            return;
        }
        plan.replace(steps);
        pendingPlanPersist = true;
        stepsWithoutProgress = 0;
        shortTermMemory.add(step, "plan", "共" + steps.size() + "步", true, "已制定计划");
        lastFeedback = "计划已制定，共 " + steps.size() + " 步，请开始执行第一步。";
        LOGGER.info("[AIBot] " + profile.name + " 已制定计划（" + steps.size() + " 步）: "
                + String.join(" -> ", steps));
    }

    /** 处理 remember 动作。 */
    private void applyRemember(ActionParser.ParsedAction parsed, long step) {
        ServerPlayer player = self();
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

    private volatile boolean pendingPlanPersist = false;

    public boolean consumePlanPersistFlag() {
        if (pendingPlanPersist) {
            pendingPlanPersist = false;
            return true;
        }
        return false;
    }

    private void handleLlmFailure(String error, long step) {
        shortTermMemory.add(step, "decision", "", false, "LLM 调用失败: " + error);
        lastFeedback = "上一次 LLM 调用失败：" + error;
        LOGGER.warning("[AIBot] " + profile.name + " LLM 调用失败: " + error);
        phase = Phase.COOLDOWN;
        tickCounter = config.decisionIntervalTicks * 2;
    }

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
    // 播报（受 chatAnnounceLevel 控制）
    // ------------------------------------------------------------------

    /**
     * 按级别向聊天栏播报。
     *
     * <p>默认级别 0（静默），这是「干净」的体现：玩家不会看到一堆
     * 系统消息刷屏。设为 1 只播报死亡/重生/计划完成这类重要事件。</p>
     *
     * @param level 该消息的重要性（1=重要，2=一般细节）
     */
    private void announce(int level, String message) {
        if (config.chatAnnounceLevel >= level) {
            try {
                MultiBotManager.Agent a = bots.get(profile.name);
                ServerPlayer p = a == null ? null : a.player();
                if (p != null && p.level() != null && p.level().getServer() != null) {
                    net.minecraft.network.chat.Component c =
                            net.minecraft.network.chat.Component.literal("[AIBot] " + message);
                    p.level().getServer().getPlayerList().broadcastSystemMessage(c, false);
                }
            } catch (Throwable ignored) {
                // 播报失败不影响主流程
            }
        }
    }

    // ------------------------------------------------------------------
    // 生命周期控制
    // ------------------------------------------------------------------

    public void start() {
        if (running.compareAndSet(false, true)) {
            manualStop.set(false);
            phase = Phase.IDLE;
            tickCounter = 0;
            // 注意：不清零 totalStepCounter（累计量），只重置会话计数。
            // 但 start() 可能是「停止后重新开启」，此时也应重置会话步数。
            stepCounter.set(0);
            consecutiveIdle = 0;
            stepsWithoutProgress = 0;
            LOGGER.info("[AIBot] " + profile.name + " 自主循环已开启"
                    + (config.maxStepsPerSession <= 0 ? "（无步数上限，将持续运行）"
                    : "（上限 " + config.maxStepsPerSession + " 步，自动续跑=" + config.autoRestart + "）"));
        }
    }

    public void stop() {
        if (running.compareAndSet(true, false)) {
            manualStop.set(true);
            TickActionDriver d = this.activeDriver;
            if (d != null) {
                d.cancel();
                this.activeDriver = null;
            }
            phase = Phase.IDLE;
            LOGGER.info("[AIBot] " + profile.name + " 自主循环已停止（本会话 "
                    + stepCounter.get() + " 步，累计 " + totalStepCounter.get() + " 步）");
        }
    }

    private void resetSession() {
        stepCounter.set(0);
        phase = Phase.IDLE;
        tickCounter = 0;
    }

    public boolean isRunning() {
        return running.get();
    }

    public long getStepCount() {
        return stepCounter.get();
    }

    public long getTotalStepCount() {
        return totalStepCounter.get();
    }

    /** 设置长期目标（清空旧计划，触发重新规划）。 */
    public void setGoal(String goal) {
        this.goal = goal == null ? "" : goal.trim();
        plan.clear();
        LOGGER.info("[AIBot] " + profile.name + " 长期目标已更新，旧计划已清空");
    }

    public String getGoal() {
        return this.goal;
    }

    public TaskPlan getPlan() {
        return plan;
    }

    public void restorePlan(java.util.List<TaskPlan.Task> tasks) {
        plan.importTasks(tasks);
    }

    public boolean isAlive() {
        MultiBotManager.Agent a = bots.get(profile.name);
        return a != null && a.alive();
    }

    /** 是否处于自主模式（配置开启且未被玩家显式关闭）。 */
    public boolean isAutonomous() {
        return config.autonomousMode && !autonomousOverride.get();
    }

    /** 玩家显式开关自主模式。 */
    public void setAutonomous(boolean on) {
        autonomousOverride.set(!on);
    }

    // ------------------------------------------------------------------
    // 状态展示
    // ------------------------------------------------------------------

    public String statusReport() {
        ServerPlayer player = self();
        StringBuilder sb = new StringBuilder();
        sb.append("===== AIBot 状态：").append(profile.name).append(" =====\n");
        sb.append("实体: ").append(player == null ? "未生成"
                : player.getName().getString() + (player.isAlive() ? "（存活）" : "（已死亡）")).append("\n");
        sb.append("自主循环: ").append(running.get() ? "运行中" : "已关闭").append("\n");
        sb.append("自主模式: ").append(isAutonomous() ? "开启（无人干预时自驱）" : "关闭（等待指令）").append("\n");
        sb.append("当前阶段: ").append(phase).append("\n");
        sb.append("已执行步数: ").append(stepCounter.get())
                .append(config.maxStepsPerSession > 0 ? " / " + config.maxStepsPerSession : "（无上限）")
                .append("，累计 ").append(totalStepCounter.get()).append("\n");
        sb.append("自动重生: ").append(config.autoRespawn ? "开启" : "关闭").append("\n");
        sb.append("生存反射: ").append(config.survivalReflex ? "开启" : "关闭").append("\n");
        sb.append("长期目标: ").append(goal.isEmpty() ? "(未设定，由智能体自行决定)" : goal).append("\n");
        if (!profile.personality.isEmpty()) {
            sb.append("性格: ").append(profile.personality).append("\n");
        }
        sb.append("当前计划: ").append(plan.isEmpty() ? "(无)"
                : plan.doneCount() + "/" + plan.size() + " 步已完成").append("\n");
        sb.append("地标数量: ").append(landmarkMemory.size()).append("\n");
        sb.append("上下文压缩: ").append(compressor.shortSummary()).append("\n");
        sb.append("模型: ").append(config.model.isEmpty() ? "(未设置)" : config.model).append("\n");
        sb.append("API 地址: ").append(config.baseUrl.isEmpty() ? "(未设置)" : config.baseUrl).append("\n");
        sb.append("API Key: ").append(config.maskedApiKey()).append("\n");
        sb.append("缓存: ").append(cacheStats.shortSummary()).append("\n");
        sb.append("上游: ").append(llmClient.upstreamTracker().shortSummary()).append("\n");
        sb.append("短期记忆: ").append(shortTermMemory.size()).append(" 条\n");
        sb.append("长期记忆: 成功 ").append(longTermMemory.successCount())
                .append(" 条 / 失败 ").append(longTermMemory.failureCount()).append(" 条\n");
        sb.append("聊天记忆: ").append(chatMemory.size()).append(" 条\n");
        return sb.toString();
    }

    public ContextCompressor getCompressor() {
        return compressor;
    }

    public BotProfile getProfile() {
        return profile;
    }

    public ChatMemory getChatMemory() {
        return chatMemory;
    }

    private static String fmt(float v) {
        return String.format(java.util.Locale.ROOT, "%.0f", v);
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
