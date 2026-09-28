package com.example.aibot;

import com.example.aibot.command.AIBotCommand;
import com.example.aibot.config.AIConfig;
import com.example.aibot.config.ConfigStore;
import com.example.aibot.core.AutoLoop;
import com.example.aibot.entity.FakePlayerManager;
import com.example.aibot.llm.CacheStats;
import com.example.aibot.llm.LLMClient;
import com.example.aibot.llm.PromptBuilder;
import com.example.aibot.llm.StaticPrefix;
import com.example.aibot.memory.LandmarkMemory;
import com.example.aibot.memory.LongTermMemory;
import com.example.aibot.memory.ShortTermMemory;
import com.example.aibot.core.TaskPlan;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

/**
 * AIBot 主入口（Minecraft 1.21.11 版本线）。
 *
 * <p>负责组装所有模块并注册事件：</p>
 * <ul>
 *   <li>服务器启动：加载配置与记忆、初始化 LLM 客户端、创建命令处理器</li>
 *   <li>每个服务器 tick：驱动自主循环（异步 LLM 调用，不阻塞主线程）</li>
 *   <li>服务器停止：保存记忆与配置、关闭 LLM 线程池</li>
 * </ul>
 *
 * <p><b>注意</b>：本类只实现 {@link ModInitializer}（双端入口，但逻辑仅在服务端生效）。
 * 假玩家是纯服务端概念，客户端不需要任何逻辑。因此这里刻意<b>不</b>实现
 * ClientModInitializer，避免客户端专用类被服务端加载导致 ClassNotFound。</p>
 */
public class Aibot implements ModInitializer {

    /** 模组 ID，与 fabric.mod.json 保持一致。 */
    public static final String MOD_ID = "aibot";

    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    /** 单例，供命令回调等访问。 */
    private static Aibot instance;

    // ---- 核心组件（在服务器启动时初始化） ----
    private ConfigStore configStore;
    private CacheStats cacheStats;
    private LLMClient llmClient;
    private FakePlayerManager playerManager;
    private ShortTermMemory shortTermMemory;
    private LongTermMemory longTermMemory;
    private LandmarkMemory landmarkMemory;
    private AutoLoop autoLoop;
    private AIBotCommand command;

    /** 当前服务器实例。 */
    private volatile MinecraftServer server;

    /** 服务器 tick 计数，用于超时与节奏控制。 */
    private long tickCounter = 0;

    public static Aibot getInstance() {
        return instance;
    }

    @Override
    public void onInitialize() {
        instance = this;
        LOGGER.info("[AIBot] 正在初始化（Minecraft 1.21.11 版本线）...");

        // 1. 服务器生命周期：配置与记忆的路径依赖服务器工作目录，必须等启动完成
        ServerLifecycleEvents.SERVER_STARTED.register(this::onServerStarted);
        ServerLifecycleEvents.SERVER_STOPPING.register(this::onServerStopping);

        // 2. tick 驱动自主循环。
        //
        // 【关键】必须用 START_SERVER_TICK，不能用 END_SERVER_TICK。
        // 原因：走路是通过设置玩家的 zza/xxa（等价于真人按住 W）实现的，
        // 这些输入会被「玩家自己的 tick」消费。真人客户端的按键包也是在
        // tick 之前就到达服务端的。若放在 END 事件里设置，输入要等到下一
        // tick 才被消费，等于白白慢一拍，且可能被原版重置掉。
        ServerTickEvents.START_SERVER_TICK.register(this::onServerTickStart);
        ServerTickEvents.END_SERVER_TICK.register(this::onServerTickEnd);

        // 3. 注册 /aibot 命令树
        CommandRegistrationCallback.EVENT.register((dispatcher, buildContext, selection) -> {
            if (this.command != null) {
                this.command.register(dispatcher, buildContext, selection);
            }
        });

        // 4. 静态前缀自检：确保没有动态内容污染缓存前缀
        String warning = StaticPrefix.selfCheck();
        if (warning != null) {
            LOGGER.warn("[AIBot] 提示词静态前缀自检警告: {}", warning);
        }
        // 打印指纹，便于确认前缀未被改动（改动会降低缓存命中率）
        LOGGER.info("[AIBot] 提示词静态前缀指纹: {}", PromptBuilder.staticFingerprint());

        LOGGER.info("[AIBot] 初始化完成。使用 /aibot spawn 生成假玩家；"
                + "/aibot config set apiKey <key> 配置密钥。");
    }

    /**
     * 服务器启动完成：此时才能拿到世界目录，用于定位 config/aibot/。
     */
    private void onServerStarted(MinecraftServer server) {
        this.server = server;
        try {
            // 服务器工作目录（单人存档目录 / 服务端根目录）
            Path gameDir = server.getServerDirectory().toPath();

            this.configStore = new ConfigStore(gameDir);
            this.cacheStats = new CacheStats();
            this.playerManager = new FakePlayerManager();
            this.shortTermMemory = new ShortTermMemory();
            this.longTermMemory = new LongTermMemory(gameDir);
            this.landmarkMemory = new LandmarkMemory(gameDir);

            // 加载持久化数据
            AIConfig config = this.configStore.load();
            this.longTermMemory.load();
            this.landmarkMemory.load();

            // LLM 客户端（内部使用守护线程池做异步 HTTP）
            this.llmClient = new LLMClient(config, this.cacheStats);

            // 组装自主循环
            this.autoLoop = new AutoLoop(config, this.cacheStats, this.llmClient,
                    this.playerManager, this.shortTermMemory, this.longTermMemory, this.landmarkMemory);

            // 恢复上次保存的计划，让服务器重启后能接着做
            if (config.persistPlan) {
                java.util.List<TaskPlan.Task> saved = this.longTermMemory.loadPlan();
                if (saved != null && !saved.isEmpty()) {
                    this.autoLoop.restorePlan(saved);
                    LOGGER.info("[AIBot] 已恢复上次的计划（{} 个任务）", saved.size());
                }
            }

            // 命令处理器
            this.command = new AIBotCommand(this.configStore, this.cacheStats, this.playerManager,
                    this.shortTermMemory, this.longTermMemory, this.landmarkMemory,
                    this.autoLoop, () -> this.server);

            LOGGER.info("[AIBot] 组件已就绪，配置文件: {}", this.configStore.getConfigFile().toAbsolutePath());

            if (!config.isUsable()) {
                LOGGER.warn("[AIBot] LLM 尚未配置，请执行 /aibot config set apiKey <你的密钥>");
            }

            // 长期自主模式提示
            if (config.maxStepsPerSession <= 0) {
                LOGGER.info("[AIBot] 步数上限：无限制（可持续自主运行）");
            }
            if (config.autoRespawn) {
                LOGGER.info("[AIBot] 死亡自动重生：已开启");
            }

            // 安全提醒（需求：提醒备份存档）
            LOGGER.warn("[AIBot] 提醒：假玩家会自动修改世界，长期挂机前请务必备份存档！");

        } catch (Throwable t) {
            LOGGER.error("[AIBot] 服务器启动初始化失败", t);
        }
    }

    /**
     * tick 开始：推进自主循环与跨 tick 动作。
     *
     * <p>必须在玩家实体 tick <b>之前</b>设置移动输入，
     * 否则假玩家会比真人慢一拍（详见 onInitialize 里的说明）。</p>
     */
    private void onServerTickStart(MinecraftServer server) {
        this.server = server;
        if (this.autoLoop == null) {
            return;
        }
        this.tickCounter++;
        try {
            this.autoLoop.tick(this.tickCounter);
        } catch (Throwable t) {
            // tick 中绝不能抛异常，否则会影响服务器主循环
            LOGGER.error("[AIBot] tick 处理异常，已自动停止自主循环", t);
            try {
                this.autoLoop.stop();
            } catch (Throwable ignored) {
                // 忽略二次异常
            }
        }
    }

    /**
     * tick 结束：目前没有必须在这里做的事，保留钩子便于将来扩展。
     */
    private void onServerTickEnd(MinecraftServer server) {
        // 预留：例如统计、周期性落盘等
    }

    /**
     * 服务器停止：保存记忆与配置、关闭线程池。
     */
    private void onServerStopping(MinecraftServer server) {
        LOGGER.info("[AIBot] 服务器正在停止，保存数据中...");
        try {
            if (this.autoLoop != null) {
                this.autoLoop.stop();
            }
            if (this.longTermMemory != null) {
                this.longTermMemory.save();
                // 保存当前计划，让下次启动能接着做
                if (this.autoLoop != null && this.configStore != null
                        && this.configStore.get().persistPlan) {
                    this.longTermMemory.savePlan(this.autoLoop.getPlan());
                }
            }
            if (this.landmarkMemory != null) {
                this.landmarkMemory.save();
            }
            if (this.configStore != null) {
                this.configStore.save();
            }
            if (this.llmClient != null) {
                this.llmClient.shutdown();
            }
            if (this.cacheStats != null) {
                LOGGER.info("[AIBot] 本次会话缓存统计: {}", this.cacheStats.shortSummary());
            }
        } catch (Throwable t) {
            LOGGER.warn("[AIBot] 停止阶段保存数据时出错", t);
        }
    }

    // ---- 只读访问入口 ----

    public CacheStats getCacheStats() {
        return cacheStats;
    }

    public FakePlayerManager getPlayerManager() {
        return playerManager;
    }

    public AutoLoop getAutoLoop() {
        return autoLoop;
    }

    public ConfigStore getConfigStore() {
        return configStore;
    }

    public LandmarkMemory getLandmarkMemory() {
        return landmarkMemory;
    }
}
