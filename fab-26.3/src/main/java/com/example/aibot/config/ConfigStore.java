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
 * 配置读写器：负责把 {@link AIConfig} 持久化成 JSON 文件。
 *
 * <p>文件位置：{@code config/aibot/config.json}</p>
 *
 * <p>写盘策略：先写临时文件再原子替换，避免游戏崩溃时配置文件被写坏。</p>
 */
public final class ConfigStore {

    private static final Logger LOGGER = Logger.getLogger("aibot-config");

    /** 配置文件所在目录（由主类在服务器启动时注入存档根路径）。 */
    private final Path configDir;
    private final Path configFile;

    private AIConfig config = new AIConfig();

    /**
     * 带缩进的 Gson。禁用 HTML 转义，避免 URL 里的字符被转成 \u003d 之类。
     */
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
     *
     * @return 加载后的配置对象
     */
    public AIConfig load() {
        try {
            Files.createDirectories(this.configDir);
            if (!Files.exists(this.configFile)) {
                // 首次运行：落一份默认配置，方便用户直接编辑
                this.config = new AIConfig();
                save();
                LOGGER.info("[AIBot] 已生成默认配置: " + this.configFile.toAbsolutePath());
                return this.config;
            }

            String json = new String(Files.readAllBytes(this.configFile), StandardCharsets.UTF_8);
            AIConfig loaded = GSON.fromJson(json, AIConfig.class);

            // 文件内容为空或解析成 null 时回退到默认值
            this.config = loaded != null ? loaded : new AIConfig();
            // 兼容旧版本配置文件：补齐后续新增字段的默认值
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
     * 补齐可能缺失的字段（旧配置文件升级场景）。
     * Gson 对缺失字段会保留 Java 字段初始值，这里只处理非法值。
     */
    private void normalize(AIConfig c) {
        if (c.baseUrl == null || c.baseUrl.trim().isEmpty()) {
            c.baseUrl = "https://api.deepseek.com";
        }
        if (c.model == null || c.model.trim().isEmpty()) {
            c.model = "deepseek-chat";
        }
        if (c.maxTokens <= 0) {
            c.maxTokens = 1024;
        }
        if (c.requestTimeoutMs <= 0) {
            c.requestTimeoutMs = 30000;
        }
        if (c.maxRetries < 0) {
            c.maxRetries = 0;
        }
        if (c.decisionIntervalTicks < 1) {
            c.decisionIntervalTicks = 40;
        }
        if (c.maxStepsPerSession < 1) {
            c.maxStepsPerSession = 200;
        }
        if (c.stepTimeoutTicks < 1) {
            c.stepTimeoutTicks = 200;
        }
        if (c.botName == null || c.botName.trim().isEmpty()) {
            c.botName = "AIBot";
        }
    }

    /**
     * 保存当前配置到磁盘（临时文件 + 原子替换）。
     */
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
                // 某些文件系统不支持 ATOMIC_MOVE，退化为普通替换
                Files.move(tmp, this.configFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "[AIBot] 保存配置失败: " + this.configFile, e);
        }
    }

    /** 当前内存中的配置对象（始终非 null）。 */
    public AIConfig get() {
        return this.config;
    }

    /** 配置文件路径，用于 /aibot config path 展示。 */
    public Path getConfigFile() {
        return this.configFile;
    }
}
