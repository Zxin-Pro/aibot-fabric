package com.example.aibot.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 配置读写器：把 {@link AIConfig} 持久化成 JSON 文件。
 *
 * <p>位置：{@code config/aibot/config.json}</p>
 *
 * <p>写盘策略：先写临时文件再原子替换，避免崩溃时写坏配置。</p>
 *
 * <p><b>升级兼容</b>：{@link #normalize(AIConfig)} 负责把旧版本配置文件
 * 补齐成当前结构。所有新增字段都必须在这里处理，
 * 否则老用户升级后会拿到字段初始值而不知道发生了什么。</p>
 */
public final class ConfigStore {

    private static final Logger LOGGER = Logger.getLogger("aibot-config");

    private final Path configDir;
    private final Path configFile;

    private AIConfig config = new AIConfig();

    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .create();

    public ConfigStore(Path gameDir) {
        this.configDir = gameDir.resolve("config").resolve("aibot");
        this.configFile = this.configDir.resolve("config.json");
    }

    /**
     * 从磁盘加载配置；文件不存在则写出一份默认配置。
     */
    public AIConfig load() {
        try {
            Files.createDirectories(this.configDir);
            if (!Files.exists(this.configFile)) {
                this.config = new AIConfig();
                save();
                LOGGER.info("[AIBot] 已生成默认配置: " + this.configFile.toAbsolutePath());
                return this.config;
            }

            String json = new String(Files.readAllBytes(this.configFile), StandardCharsets.UTF_8);
            AIConfig loaded = GSON.fromJson(json, AIConfig.class);
            this.config = loaded != null ? loaded : new AIConfig();
            normalize(this.config);
            LOGGER.info("[AIBot] 配置已加载: " + this.configFile.toAbsolutePath());
            return this.config;
        } catch (JsonSyntaxException e) {
            LOGGER.log(Level.SEVERE, "[AIBot] 配置文件 JSON 格式错误，已回退到默认配置: " + this.configFile, e);
            this.config = new AIConfig();
            return this.config;
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "[AIBot] 读取配置文件失败，已回退到默认配置", e);
            this.config = new AIConfig();
            return this.config;
        }
    }

    /**
     * 修正非法值与补齐旧配置。
     *
     * <p><b>历史教训（务必保留注释）</b>：早期版本这里把
     * {@code maxStepsPerSession <= 0} 静默改成 200，
     * 结果「无步数上限、可以一直玩下去」的承诺被悄悄破坏，
     * 用户跑到 200 步就发现它自己停了。
     * <b>0 与负数在这里是合法值，表示不限制，绝不能改。</b></p>
     */
    private void normalize(AIConfig c) {
        if (c.baseUrl == null) {
            c.baseUrl = "";
        }
        if (c.model == null) {
            c.model = "";
        }
        if (c.extraHeaders == null) {
            c.extraHeaders = "";
        }
        if (c.cacheHitField == null) {
            c.cacheHitField = "";
        }
        if (c.defaultGoal == null) {
            c.defaultGoal = "";
        }

        if (c.maxTokens < 0) {
            c.maxTokens = 0;
        }
        if (c.requestTimeoutMs <= 0) {
            c.requestTimeoutMs = 45000;
        }
        if (c.connectTimeoutSeconds <= 0) {
            c.connectTimeoutSeconds = 15;
        }
        if (c.maxRetries < 0) {
            c.maxRetries = 0;
        }
        if (c.minRequestIntervalMs < 0) {
            c.minRequestIntervalMs = 500L;
        }
        if (c.decisionIntervalTicks < 1) {
            c.decisionIntervalTicks = 40;
        }
        // 注意：maxStepsPerSession 允许为 0 或负数 = 无上限，绝不能改（见方法注释）。
        if (c.stepTimeoutTicks < 1) {
            c.stepTimeoutTicks = 200;
        }
        if (c.botName == null || c.botName.trim().isEmpty()) {
            c.botName = "AIBot";
        }
        if (c.maxBots < 1) {
            c.maxBots = 5;
        }
        if (c.contextWindow < 4) {
            c.contextWindow = 12;
        }
        if (c.contextTokenBudget < 200) {
            c.contextTokenBudget = 1500;
        }
        if (c.respawnDelayTicks < 0) {
            c.respawnDelayTicks = 100;
        }
        if (c.stuckThreshold < 1) {
            c.stuckThreshold = 3;
        }
        if (c.chatAnnounceLevel < 0) {
            c.chatAnnounceLevel = 0;
        }
        if (c.chatAnnounceLevel > 2) {
            c.chatAnnounceLevel = 2;
        }
        if (c.chatCooldownSteps < 0) {
            c.chatCooldownSteps = 0;
        }
        // 单价必须为正，否则费用估算会变成负数
        if (c.pricePerMillionInputMiss <= 0) {
            c.pricePerMillionInputMiss = 2.0;
        }
        if (c.pricePerMillionInputHit < 0) {
            c.pricePerMillionInputHit = 0.2;
        }
        if (c.pricePerMillionOutput <= 0) {
            c.pricePerMillionOutput = 8.0;
        }
    }

    /** 保存到磁盘（临时文件 + 原子替换）。 */
    public void save() {
        try {
            Files.createDirectories(this.configDir);
            Path tmp = this.configFile.resolveSibling("config.json.tmp");
            Files.write(tmp, GSON.toJson(this.config).getBytes(StandardCharsets.UTF_8));
            try {
                Files.move(tmp, this.configFile,
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicFailed) {
                Files.move(tmp, this.configFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "[AIBot] 保存配置失败: " + this.configFile, e);
        }
    }

    public AIConfig get() {
        return this.config;
    }

    public Path getConfigFile() {
        return this.configFile;
    }
}
