package com.example.aibot.memory;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.lang.reflect.Type;
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
 * 长期记忆：跨会话保存的成功/失败经验。
 *
 * <p>存储位置：{@code config/aibot/memory.json}</p>
 *
 * <p>结构（字段顺序固定，避免破坏序列化稳定性）：</p>
 * <pre>
 * {
 *   "successes": [ {"action":"mine","target":"minecraft:oak_log","count":12}, ... ],
 *   "failures":  [ {"action":"mine","target":"minecraft:diamond_ore","reason":"找不到方块"}, ... ]
 * }
 * </pre>
 *
 * <p>使用 {@link LinkedHashMap} 与 {@link ArrayList} 保证顺序稳定；
 * Gson 对 List/Map 会保持插入顺序。</p>
 */
public final class LongTermMemory {

    private static final Logger LOGGER = Logger.getLogger("aibot-memory");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    /** 单条经验记录。使用 LinkedHashMap 保证 JSON 字段顺序固定。 */
    public static final class Experience {
        public String action = "";
        public String target = "";
        public int count = 1;
        /** 成功时为空；失败时记录原因 */
        public String reason = "";

        public Experience() {
        }

        public Experience(String action, String target, int count, String reason) {
            this.action = action == null ? "" : action;
            this.target = target == null ? "" : target;
            this.count = count;
            this.reason = reason == null ? "" : reason;
        }

        /** 经验去重键：动作 + 目标 + 结果原因。 */
        public String key() {
            return this.action + "|" + this.target + "|" + this.reason;
        }
    }

    /** 经验列表：用 List + 手动查重，保持稳定顺序（不用 HashMap 以免顺序抖动）。 */
    private final List<Experience> successes = new ArrayList<>();
    private final List<Experience> failures = new ArrayList<>();

    /** 每类经验最多保留条数，防止文件无限膨胀、提示词过长。 */
    private static final int MAX_PER_CATEGORY = 30;

    private final Path memoryFile;

    public LongTermMemory(Path gameDir) {
        Path dir = gameDir.resolve("config").resolve("aibot");
        this.memoryFile = dir.resolve("memory.json");
    }

    /** 从磁盘加载（不存在则视为空记忆）。 */
    public synchronized void load() {
        try {
            if (!Files.exists(this.memoryFile)) {
                LOGGER.info("[AIBot] 未找到长期记忆文件，将从空记忆开始: " + this.memoryFile.toAbsolutePath());
                return;
            }
            String json = new String(Files.readAllBytes(this.memoryFile), StandardCharsets.UTF_8);
            Type type = new TypeToken<MemoryFile>() {
            }.getType();
            MemoryFile loaded = GSON.fromJson(json, type);
            if (loaded != null) {
                this.successes.clear();
                this.failures.clear();
                if (loaded.successes != null) {
                    this.successes.addAll(loaded.successes);
                }
                if (loaded.failures != null) {
                    this.failures.addAll(loaded.failures);
                }
            }
            LOGGER.info("[AIBot] 长期记忆已加载：成功经验 " + this.successes.size()
                    + " 条，失败经验 " + this.failures.size() + " 条");
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "[AIBot] 加载长期记忆失败，将以空记忆继续", e);
        }
    }

    /** 保存到磁盘（临时文件 + 原子替换）。 */
    public synchronized void save() {
        try {
            Files.createDirectories(this.memoryFile.getParent());
            MemoryFile file = new MemoryFile();
            file.successes = new ArrayList<>(this.successes);
            file.failures = new ArrayList<>(this.failures);

            Path tmp = this.memoryFile.resolveSibling("memory.json.tmp");
            Files.write(tmp, GSON.toJson(file).getBytes(StandardCharsets.UTF_8));
            try {
                Files.move(tmp, this.memoryFile,
                        StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicFailed) {
                Files.move(tmp, this.memoryFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "[AIBot] 保存长期记忆失败", e);
        }
    }

    /**
     * 记录一次成功经验（自动合并相同条目并累加次数）。
     *
     * @param action 动作名
     * @param target 动作目标（方块/物品/实体 ID），可为空
     */
    public synchronized void recordSuccess(String action, String target) {
        merge(this.successes, new Experience(action, target, 1, ""));
    }

    /**
     * 记录一次失败经验（同一动作+目标+原因只保留一条，累加次数）。
     */
    public synchronized void recordFailure(String action, String target, String reason) {
        merge(this.failures, new Experience(action, target, 1, reason == null ? "" : reason));
    }

    /** 合并逻辑：同 key 累加次数并前置（最近用到的排在前面，剪枝时优先淘汰）。 */
    private void merge(List<Experience> list, Experience incoming) {
        for (Experience e : list) {
            if (e.key().equals(incoming.key())) {
                e.count += 1;
                // 移到最前，表示最近使用过
                list.remove(e);
                list.add(0, e);
                return;
            }
        }
        list.add(0, incoming);
        while (list.size() > MAX_PER_CATEGORY) {
            list.remove(list.size() - 1);
        }
    }

    /**
     * 序列化为稳定格式文本，放入提示词动态后缀。
     *
     * <p>每行格式固定：{@code [成功|失败] action target (xN) reason}</p>
     */
    public synchronized String serialize() {
        if (successes.isEmpty() && failures.isEmpty()) {
            return "(暂无)";
        }
        StringBuilder sb = new StringBuilder();
        if (!successes.isEmpty()) {
            sb.append("已验证有效的做法：\n");
            for (Experience e : successes) {
                sb.append("  [成功] ").append(e.action);
                if (!e.target.isEmpty()) {
                    sb.append(' ').append(e.target);
                }
                if (e.count > 1) {
                    sb.append(" (x").append(e.count).append(")");
                }
                sb.append('\n');
            }
        }
        if (!failures.isEmpty()) {
            sb.append("应避免的失败做法：\n");
            for (Experience e : failures) {
                sb.append("  [失败] ").append(e.action);
                if (!e.target.isEmpty()) {
                    sb.append(' ').append(e.target);
                }
                if (e.count > 1) {
                    sb.append(" (x").append(e.count).append(")");
                }
                if (!e.reason.isEmpty()) {
                    sb.append(" —— ").append(e.reason);
                }
                sb.append('\n');
            }
        }
        return sb.toString().trim();
    }

    /** 是否有关于某动作的失败记录（供自主循环避免重复踩坑）。 */
    public synchronized boolean hasFailureFor(String action, String target) {
        for (Experience e : failures) {
            if (e.action.equals(action) && (target == null || target.isEmpty() || e.target.equals(target))) {
                return true;
            }
        }
        return false;
    }

    /** 清空长期记忆。 */
    public synchronized void clear() {
        successes.clear();
        failures.clear();
    }

    // ------------------------------------------------------------------
    // 计划持久化：让服务器重启后能接着做未完成的事
    // ------------------------------------------------------------------

    /** 计划存档文件（与 memory.json 同目录）。 */
    private Path planFile() {
        return this.memoryFile.resolveSibling("plan.json");
    }

    /**
     * 保存当前计划。
     *
     * @param plan 任务计划
     */
    public synchronized void savePlan(com.example.aibot.core.TaskPlan plan) {
        try {
            Files.createDirectories(this.memoryFile.getParent());
            PlanFile pf = new PlanFile();
            pf.tasks = plan.exportTasks();
            Path tmp = planFile().resolveSibling("plan.json.tmp");
            Files.write(tmp, GSON.toJson(pf).getBytes(StandardCharsets.UTF_8));
            try {
                Files.move(tmp, planFile(),
                        StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicFailed) {
                Files.move(tmp, planFile(), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "[AIBot] 保存计划失败", e);
        }
    }

    /**
     * 读取上次保存的计划。
     *
     * @return 任务列表；无存档时返回空列表
     */
    public synchronized List<com.example.aibot.core.TaskPlan.Task> loadPlan() {
        List<com.example.aibot.core.TaskPlan.Task> out = new ArrayList<>();
        try {
            Path f = planFile();
            if (!Files.exists(f)) {
                return out;
            }
            String json = new String(Files.readAllBytes(f), StandardCharsets.UTF_8);
            PlanFile pf = GSON.fromJson(json, PlanFile.class);
            if (pf != null && pf.tasks != null) {
                out.addAll(pf.tasks);
            }
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "[AIBot] 读取计划失败", e);
        }
        return out;
    }

    /** 计划存档结构（字段顺序固定）。 */
    private static final class PlanFile {
        List<com.example.aibot.core.TaskPlan.Task> tasks;
    }

    public synchronized int successCount() {
        return successes.size();
    }

    public synchronized int failureCount() {
        return failures.size();
    }

    public Path getMemoryFile() {
        return memoryFile;
    }

    /** 序列化用的外层结构（字段顺序固定）。 */
    private static final class MemoryFile {
        List<Experience> successes;
        List<Experience> failures;
    }

    /** 预留：兼容 Map 形式的旧文件。 */
    @SuppressWarnings("unused")
    private static Map<String, Object> emptyMap() {
        return new LinkedHashMap<>();
    }
}
