package com.example.aibot.memory;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 地标记忆：记录基地、箱子、矿洞等重要坐标。
 *
 * <p><b>为什么需要它</b>：{@code StateCollector} 每次只扫描身边 8 格，
 * 假玩家没有任何空间记忆。一旦走远就再也找不到家，
 * 这是「打造帝国」最致命的短板：建好的东西没法再利用。</p>
 *
 * <p>存储位置：{@code config/aibot/landmarks.json}，与 memory.json 分开存放，
 * 因为地标是「事实」，而经验是可淘汰的启发式信息。</p>
 */
public final class LandmarkMemory {

    private static final Logger LOGGER = Logger.getLogger("aibot-landmark");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    /** 单条地标。字段顺序固定，序列化稳定。 */
    public static final class Landmark {
        /** 类型：home / chest / mine / farm / base / other。 */
        public String type = "other";
        /** 名称（LLM 可读）。 */
        public String name = "";
        public int x;
        public int y;
        public int z;
        /** 维度 ID。 */
        public String dimension = "";
        /** 备注。 */
        public String note = "";

        public Landmark() {
        }

        public Landmark(String type, String name, int x, int y, int z, String dimension, String note) {
            this.type = type == null ? "other" : type;
            this.name = name == null ? "" : name;
            this.x = x;
            this.y = y;
            this.z = z;
            this.dimension = dimension == null ? "" : dimension;
            this.note = note == null ? "" : note;
        }

        /** 同一维度内 16 格以内视为同一个地标，避免反复记录同一地点。 */
        public boolean isNear(int ox, int oy, int oz, String dim, int radius) {
            if (!this.dimension.equals(dim)) {
                return false;
            }
            long dx = this.x - ox;
            long dy = this.y - oy;
            long dz = this.z - oz;
            return dx * dx + dy * dy + dz * dz <= (long) radius * radius;
        }
    }

    /** 地标列表：用 List 保证顺序稳定（不用 HashMap，避免遍历顺序抖动影响缓存）。 */
    private final List<Landmark> landmarks = new ArrayList<>();

    /** 最多保留的地标数。 */
    private static final int MAX_LANDMARKS = 40;

    private final Path file;

    /** 单智能体地标记忆（兼容旧存档）。 */
    public LandmarkMemory(Path gameDir) {
        this(gameDir, "default");
    }

    /**
     * 按智能体名字隔离的地标文件。
     *
     * <p>多智能体场景下每个 bot 有自己探索到的地图知识，
     * 共享一份会导致 A 记的「家」被 B 当成自己的家而乱跑。</p>
     */
    public LandmarkMemory(Path gameDir, String botName) {
        Path dir = gameDir.resolve("config").resolve("aibot");
        if (botName == null || botName.trim().isEmpty() || "default".equals(botName)) {
            this.file = dir.resolve("landmarks.json");
        } else {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < botName.length(); i++) {
                char c = botName.charAt(i);
                if (c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z'
                        || c >= '0' && c <= '9' || c == '_' || c == '-') {
                    sb.append(c);
                }
            }
            this.file = dir.resolve("landmarks-" + (sb.length() == 0 ? "bot" : sb) + ".json");
        }
    }

    /** 从磁盘加载。 */
    public synchronized void load() {
        try {
            if (!Files.exists(this.file)) {
                return;
            }
            String json = new String(Files.readAllBytes(this.file), StandardCharsets.UTF_8);
            LandmarkFile loaded = GSON.fromJson(json, LandmarkFile.class);
            this.landmarks.clear();
            if (loaded != null && loaded.landmarks != null) {
                this.landmarks.addAll(loaded.landmarks);
            }
            LOGGER.info("[AIBot] 地标记忆已加载：" + this.landmarks.size() + " 条");
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "[AIBot] 加载地标记忆失败", e);
        }
    }

    /** 保存到磁盘（临时文件 + 原子替换）。 */
    public synchronized void save() {
        try {
            Files.createDirectories(this.file.getParent());
            LandmarkFile f = new LandmarkFile();
            f.landmarks = new ArrayList<>(this.landmarks);
            Path tmp = this.file.resolveSibling(this.file.getFileName() + ".tmp");
            Files.write(tmp, GSON.toJson(f).getBytes(StandardCharsets.UTF_8));
            try {
                Files.move(tmp, this.file,
                        StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicFailed) {
                Files.move(tmp, this.file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "[AIBot] 保存地标记忆失败", e);
        }
    }

    /**
     * 记录一个地标。同一位置已有地标时更新而不是重复添加。
     *
     * @return 是否新增（false 表示更新了已有地标）
     */
    public synchronized boolean remember(String type, String name, int x, int y, int z,
                                         String dimension, String note) {
        // 先在附近找同类型地标
        for (Landmark lm : landmarks) {
            if (lm.type.equals(type) && lm.isNear(x, y, z, dimension, 16)) {
                lm.x = x;
                lm.y = y;
                lm.z = z;
                lm.name = name;
                lm.note = note;
                save();
                return false;
            }
        }
        landmarks.add(new Landmark(type, name, x, y, z, dimension, note));
        // 超出上限时丢弃最旧的
        while (landmarks.size() > MAX_LANDMARKS) {
            landmarks.remove(0);
        }
        save();
        return true;
    }

    /** 查找指定类型最近的 landmarks（供「回家」用）。 */
    public synchronized Landmark nearest(String type, int fromX, int fromY, int fromZ, String dimension) {
        Landmark best = null;
        long bestDist = Long.MAX_VALUE;
        for (Landmark lm : landmarks) {
            if (type != null && !lm.type.equals(type)) {
                continue;
            }
            if (dimension != null && !lm.dimension.equals(dimension)) {
                continue;
            }
            long dx = lm.x - fromX;
            long dy = lm.y - fromY;
            long dz = lm.z - fromZ;
            long d = dx * dx + dy * dy + dz * dz;
            if (d < bestDist) {
                bestDist = d;
                best = lm;
            }
        }
        return best;
    }

    /**
     * 序列化为固定格式文本，放入提示词动态后缀。
     *
     * <p>每行固定：{@code [type] name @ (x, y, z) 距离N格 note}</p>
     * 距离是相对当前位置算的，让 LLM 知道「家还有多远」。</p>
     */
    public synchronized String serialize(int fromX, int fromY, int fromZ, String dimension) {
        if (landmarks.isEmpty()) {
            return "(暂无地标记录)";
        }
        StringBuilder sb = new StringBuilder();
        for (Landmark lm : landmarks) {
            sb.append('[').append(lm.type).append("] ").append(lm.name)
                    .append(" @ (").append(lm.x).append(", ").append(lm.y).append(", ").append(lm.z).append(')');
            if (lm.dimension.equals(dimension)) {
                double dist = Math.sqrt(
                        Math.pow(lm.x - fromX, 2) + Math.pow(lm.y - fromY, 2) + Math.pow(lm.z - fromZ, 2));
                sb.append(" 距离").append((int) dist).append("格");
            } else {
                sb.append(" 维度:").append(lm.dimension);
            }
            if (!lm.note.isEmpty()) {
                sb.append(' ').append(lm.note);
            }
            sb.append('\n');
        }
        return sb.toString().trim();
    }

    /** 清空。 */
    public synchronized void clear() {
        landmarks.clear();
        save();
    }

    public synchronized int size() {
        return landmarks.size();
    }

    /** 序列化用外层结构（字段顺序固定）。 */
    private static final class LandmarkFile {
        List<Landmark> landmarks;
    }
}
