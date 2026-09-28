package com.example.aibot.entity;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 智能体档案库：把所有 {@link BotProfile} 持久化到 {@code config/aibot/bots.json}。
 *
 * <p>这样服务器重启后，之前生成过的智能体（名字、性格、目标）
 * 都还在，可以一键全部重新拉起，实现「长期挂着一直玩」。</p>
 *
 * <p>用 {@link LinkedHashMap} 而不是 HashMap：保证 JSON 里智能体顺序稳定，
 * 便于用户人工阅读和 git diff，也让 /aibot list 输出顺序固定。</p>
 */
public final class BotProfileStore {

    private static final Logger LOGGER = Logger.getLogger("aibot-profiles");

    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .create();

    private final Path file;

    /** 名字 → 档案。用 LinkedHashMap 保证顺序稳定。 */
    private final Map<String, BotProfile> profiles = new LinkedHashMap<>();

    public BotProfileStore(Path gameDir) {
        Path dir = gameDir.resolve("config").resolve("aibot");
        this.file = dir.resolve("bots.json");
    }

    /** 从磁盘加载档案库。 */
    public synchronized void load() {
        try {
            if (!Files.exists(this.file)) {
                LOGGER.info("[AIBot] 未找到智能体档案库，将从空开始: " + this.file.toAbsolutePath());
                return;
            }
            String json = new String(Files.readAllBytes(this.file), StandardCharsets.UTF_8);
            BotProfile.ListFile loaded = GSON.fromJson(json, BotProfile.ListFile.class);
            if (loaded != null && loaded.bots != null) {
                this.profiles.clear();
                for (BotProfile p : loaded.bots) {
                    if (p != null && p.name != null && !p.name.trim().isEmpty()) {
                        this.profiles.put(p.name, p);
                    }
                }
            }
            LOGGER.info("[AIBot] 智能体档案已加载：" + this.profiles.size() + " 个");
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "[AIBot] 加载智能体档案失败，将从空开始", e);
        }
    }

    /** 保存到磁盘（临时文件 + 原子替换）。 */
    public synchronized void save() {
        try {
            Files.createDirectories(this.file.getParent());
            BotProfile.ListFile out = new BotProfile.ListFile();
            out.bots = new ArrayList<>(this.profiles.values());

            Path tmp = this.file.resolveSibling("bots.json.tmp");
            Files.write(tmp, GSON.toJson(out).getBytes(StandardCharsets.UTF_8));
            try {
                Files.move(tmp, this.file,
                        StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicFailed) {
                Files.move(tmp, this.file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "[AIBot] 保存智能体档案失败", e);
        }
    }

    /** 新增或更新档案（按名字索引）。 */
    public synchronized void put(BotProfile profile) {
        if (profile == null || profile.name == null) {
            return;
        }
        this.profiles.put(profile.name, profile);
    }

    /** 按名字取档案，不存在返回 null。 */
    public synchronized BotProfile get(String name) {
        return name == null ? null : this.profiles.get(name);
    }

    /** 是否存在该名字的档案。 */
    public synchronized boolean contains(String name) {
        return name != null && this.profiles.containsKey(name);
    }

    /** 删除档案。 */
    public synchronized boolean remove(String name) {
        return name != null && this.profiles.remove(name) != null;
    }

    /** 全部档案（顺序稳定，为副本）。 */
    public synchronized List<BotProfile> all() {
        return new ArrayList<>(this.profiles.values());
    }

    /** 档案数量。 */
    public synchronized int size() {
        return this.profiles.size();
    }

    /** 清空全部档案。 */
    public synchronized void clear() {
        this.profiles.clear();
    }

    /** 档案文件路径。 */
    public Path getFile() {
        return this.file;
    }

    /** 所有名字（顺序稳定）。 */
    public synchronized List<String> names() {
        return new ArrayList<>(this.profiles.keySet());
    }
}
