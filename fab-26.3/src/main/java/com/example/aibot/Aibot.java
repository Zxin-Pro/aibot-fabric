package com.example.aibot;

import com.example.aibot.command.AIBotCommand;
import com.example.aibot.config.AIConfig;
import com.example.aibot.config.ConfigStore;
import com.example.aibot.entity.BotProfile;
import com.example.aibot.entity.BotProfileStore;
import com.example.aibot.entity.MultiBotManager;
import com.example.aibot.llm.CacheStats;
import com.example.aibot.llm.LLMClient;
import com.example.aibot.llm.PromptBuilder;
import com.example.aibot.llm.StaticPrefix;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

/**
 * AIBot 主入口（Minecraft 26.3 版本线，多智能体版）。
 *
 * <p>负责组装所有模块并注册事件：</p>
 * <ul>
 *   <li>服务器启动：加载配置、智能体档案、初始化共享 LLM 客户端与命令处理器</li>
 *   <li>每个服务器 tick：驱动所有智能体（异步 LLM 调用，不阻塞主线程）</li>
 *   <li>服务器停止：保存所有智能体的记忆与计划、关闭线程池</li>
 * </ul>
 *
 * <p><b>多智能体设计</b>：{@link LLMClient} 与 {@link CacheStats} 全局共享一份，
 * 所有智能体复用同一份静态前缀，因此能命中同一份服务端 KV 缓存，
 * 边际成本远低于让每个 bot 各建一个客户端。</p>
 *
 * <p><b>注意</b>：本类只实现 {@link ModInitializer}。假玩家是纯服务端概念，
 * 客户端不需要任何逻辑，因此刻意<b>不</b>实现 ClientModInitializer，
 * 避免客户端专用类被服务端加载导致 ClassNotFound。</p>
 */
public class Aibot implements ModInitializer {

    /** 模组 ID，与 fabric.mod.json 保持一致。 */
    public static final String MOD_ID = "aibot";

    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    /** 单例，供命令回调等访问。 */
    private static Aibot instance;

    // ---- 核心组件（在服务器启动时初始化） ----
    private ConfigStore configStore;
    private BotProfileStore profileStore;
    private CacheStats cacheStats;
    private LLMClient llmClient;
    private MultiBotManager botManager;
    private AutoLoopRegistry loopRegistry;
    private AIBotCommand command;

    /** 当前服务器实例。 */
    private volatile MinecraftServer server;

    /** 服务器 tick 计数，用于超时与节奏控制。 */
    private long tickCounter = 0;

    public static Aibot getInstance() {
        return instance;
    }

    /**
     * 兼容旧接口的循环注册表。
     *
     * <p>多智能体之后「唯一的一个循环」这个概念消失了，
     * 但命令层仍需要「拿到某个循环来展示状态」。
     * 这个内部类把它收敛在一处，避免命令层到处遍历。</p>
     */
    public static final class AutoLoopRegistry {
        private final MultiBotManager bots;

        AutoLoopRegistry(MultiBotManager bots) {
            this.bots = bots;
        }

        /** 第一个智能体的循环，没有则为 null。 */
        public com.example.aibot.core.AutoLoop first() {
            MultiBotManager.Agent a = bots.first();
            return a == null ? null : a.loop;
        }

        /** 按名字取循环。 */
        public com.example.aibot.core.AutoLoop byName(String name) {
            MultiBotManager.Agent a = bots.get(name);
            return a == null ? null : a.loop;
        }
    }

    @Override
    public void onInitialize() {
        instance = this;
        LOGGER.info("[AIBot] 正在初始化（Minecraft 26.3 版本线，多智能体版）...");

        // 1. 服务器生命周期：配置与记忆的路径依赖服务器工作目录，必须等启动完成
        ServerLifecycleEvents.SERVER_STARTED.register(this::onServerStarted);
        ServerLifecycleEvents.SERVER_STOPPING.register(this::onServerStopping);

        // 2. tick 驱动所有智能体。
        //
        // 【关键】必须用 START_SERVER_TICK，不能用 END_SERVER_TICK。
        // 原因：走路是通过设置玩家的 zza/xxa（等价于真人按住 W）实现的，
        // 这些输入会被「玩家自己的 tick」消费。真人客户端的按键包也是在
        // tick 之前就到达服务端的。若放在 END 事件里设置，输入要等到下一
        // tick 才被消费，等于白白慢一拍，且可能被原版重置掉。
        ServerTickEvents.START_SERVER_TICK.register(this::onServerTickStart);

        // 3. 注册 /aibot 命令树
        CommandRegistrationCallback.EVENT.register((dispatcher, buildContext, selection) -> {
            if (this.command != null) {
                this.command.register(dispatcher, buildContext, selection);
            }
        });

        // 4. 让智能体能「听见」聊天。
        //
        // 之前的版本里智能体只能说、不能听，玩家跟它讲话它毫无反应 ——
        // 这是最不像真人的地方。这里监听所有聊天消息，转给每个智能体，
        // 由它们自行决定是否回应。
        registerChatListener();

        // 5. 静态前缀自检：确保没有动态内容污染缓存前缀
        String warning = StaticPrefix.selfCheck();
        if (warning != null) {
            LOGGER.warn("[AIBot] 提示词静态前缀自检警告: {}", warning);
        }
        LOGGER.info("[AIBot] 提示词静态前缀指纹: {}", PromptBuilder.staticFingerprint());

        LOGGER.info("[AIBot] 初始化完成。使用 /aibot spawn [名字] 生成智能体；"
                + "/aibot config set apiKey <key> 配置密钥。");
    }

    /**
     * 服务器启动完成：此时才能拿到世界目录，用于定位 config/aibot/。
     */
    private void onServerStarted(MinecraftServer server) {
        this.server = server;
        try {
            // 服务器工作目录（单人存档目录 / 服务端根目录）。
            // 26.3 的 MinecraftServer.getServerDirectory() 直接返回 Path（不再是 File），
            // 所以这里不需要再 .toPath()。这与本模块原有写法一致。
            Path gameDir = server.getServerDirectory();

            this.configStore = new ConfigStore(gameDir);
            this.profileStore = new BotProfileStore(gameDir);
            this.cacheStats = new CacheStats();

            AIConfig config = this.configStore.load();
            this.profileStore.load();

            // 共享的 LLM 客户端：所有智能体复用，最大化前缀缓存收益
            this.llmClient = new LLMClient(config, this.cacheStats);
            this.botManager = new MultiBotManager(config, this.cacheStats, this.llmClient, gameDir);
            this.loopRegistry = new AutoLoopRegistry(this.botManager);

            // 命令层需要读取 LLM 客户端（cache probe / upstream 诊断），
            // 用 Supplier 注入而不是直接持有，避免初始化顺序耦合。
            this.command = new AIBotCommand(this.configStore, this.profileStore, this.cacheStats,
                    this.botManager, () -> this.server, () -> this.llmClient);

            LOGGER.info("[AIBot] 组件已就绪，配置文件: {}", this.configStore.getConfigFile().toAbsolutePath());

            if (!config.isUsable()) {
                LOGGER.warn("[AIBot] LLM 尚未配置，请执行 /aibot config set apiKey <你的密钥>");
            }

            LOGGER.info("[AIBot] 步数上限：{}",
                    config.maxStepsPerSession <= 0 ? "无限制（可持续自主运行）" : config.maxStepsPerSession + " 步");
            LOGGER.info("[AIBot] 上下文压缩：窗口 {} 条，预算 {} token",
                    config.contextWindow, config.contextTokenBudget);
            LOGGER.info("[AIBot] 智能体上限：{} 个", config.maxBots);
            LOGGER.info("[AIBot] 自主模式：{}，spawn 后自动开始：{}",
                    config.autonomousMode ? "开启（无人干预时自主游玩）" : "关闭",
                    config.autoStartOnSpawn ? "是" : "否");

            LOGGER.warn("[AIBot] 提醒：智能体会自动修改世界，长期挂机前请务必备份存档！");

            // 按需自动拉起之前记录的智能体，实现「重启即续玩」
            if (config.autoSpawnOnStart) {
                autoSpawnSaved();
            }

        } catch (Throwable t) {
            LOGGER.error("[AIBot] 服务器启动初始化失败", t);
        }
    }

    /** 把档案库里标记了 autoLoop 的智能体全部拉起来。 */
    private void autoSpawnSaved() {
        int started = 0;
        for (BotProfile p : this.profileStore.all()) {
            if (!p.autoLoop) {
                continue;
            }
            if (this.botManager.size() >= this.configStore.get().maxBots) {
                LOGGER.warn("[AIBot] 已达智能体上限，剩余档案未自动拉起");
                break;
            }
            if (this.botManager.spawn(this.server, p) != null) {
                started++;
            }
        }
        if (started > 0) {
            LOGGER.info("[AIBot] 启动时自动拉起了 {} 个智能体", started);
        }
    }

    /**
     * 注册聊天监听：把玩家发言喂给所有智能体。
     *
     * <p>用 Fabric 的 {@code ServerMessageEvents.CHAT_MESSAGE}，
     * 它会在消息广播时触发，覆盖普通聊天与 /say 等命令发言。</p>
     *
     * <p><b>26.3 说明</b>：已用 javap 在 fabric-message-api-v1 与
     * minecraft-merged-deobf-26.3.jar 上核实，26.3 的回调签名仍是
     * {@code onChatMessage(PlayerChatMessage, ServerPlayer, ChatType.Bound)}，
     * 取文本仍然用 {@code message.signedContent()}，与 1.20.1 完全一致。</p>
     *
     * <p>注意：智能体自己发的话也会进这里，但
     * {@link MultiBotManager#hearChat} 会按说话者名字把自己过滤掉，
     * 避免它们听见自己的话而无限自问自答。</p>
     */
    private void registerChatListener() {
        try {
            net.fabricmc.fabric.api.message.v1.ServerMessageEvents.CHAT_MESSAGE.register(
                    (message, sender, boundChatType) -> {
                        if (this.botManager == null) {
                            return;
                        }
                        try {
                            String speaker = sender.getName().getString();
                            String text = message.signedContent();
                            // 步号只用于让模型判断新旧，取当前最大值即可
                            this.botManager.hearChat(speaker, text, this.tickCounter);
                        } catch (Throwable t) {
                            LOGGER.debug("[AIBot] 处理聊天消息失败", t);
                        }
                    });
            LOGGER.info("[AIBot] 已注册聊天监听（智能体可以听到并回应玩家发言）");
        } catch (Throwable t) {
            // 不同 MC 版本的聊天事件签名不同，注册失败不应影响模组加载
            LOGGER.warn("[AIBot] 注册聊天监听失败，智能体将无法听到玩家发言", t);
        }
    }

    /**
     * tick 开始：推进所有智能体。
     */
    private void onServerTickStart(MinecraftServer server) {
        this.server = server;
        if (this.botManager == null) {
            return;
        }
        this.tickCounter++;
        try {
            this.botManager.tick(this.tickCounter);
        } catch (Throwable t) {
            // 单个智能体的异常已在 MultiBotManager 内被隔离，
            // 走到这里说明是框架级错误，绝不能让它打断服务器主循环。
            LOGGER.error("[AIBot] tick 处理异常", t);
        }
    }

    /**
     * 服务器停止：保存所有智能体的数据、关闭线程池。
     */
    private void onServerStopping(MinecraftServer server) {
        LOGGER.info("[AIBot] 服务器正在停止，保存数据中...");
        try {
            if (this.botManager != null) {
                this.botManager.shutdown();
            }
            if (this.profileStore != null) {
                this.profileStore.save();
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

    public MultiBotManager getBotManager() {
        return botManager;
    }

    public ConfigStore getConfigStore() {
        return configStore;
    }

    public BotProfileStore getProfileStore() {
        return profileStore;
    }

    public AutoLoopRegistry getLoopRegistry() {
        return loopRegistry;
    }

    public MinecraftServer getServer() {
        return server;
    }
}
