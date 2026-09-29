package com.example.aibot.entity;

import com.example.aibot.action.ActionExecutor;
import com.example.aibot.config.AIConfig;
import com.example.aibot.core.AutoLoop;
import com.example.aibot.llm.CacheStats;
import com.example.aibot.llm.LLMClient;
import com.example.aibot.memory.LandmarkMemory;
import com.example.aibot.memory.LongTermMemory;
import com.example.aibot.memory.ShortTermMemory;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 多智能体管理器：同时运行任意数量的 AIBot（Minecraft 1.21.11 实现）。
 *
 * <p><b>设计要点（这也是「和真人没区别」的关键）</b>：每个智能体都是一个
 * 独立的 {@link ServerPlayer} 实体，拥有：</p>
 * <ul>
 *   <li>独立的名字与 UUID（{@link BotProfile#uuid()}）——
 *       服务器、TAB 列表、聊天栏都当它是另一个真人玩家</li>
 *   <li>独立的 {@link AutoLoop}（自己的策略、自己的节奏、自己的目标）</li>
 *   <li>独立的短期/长期记忆与地标记忆（互不污染）</li>
 *   <li>独立的名字与性格，因此行为倾向可以完全不同</li>
 * </ul>
 *
 * <p><b>共享的部分</b>：{@link LLMClient} 与 {@link CacheStats} 全局共享。
 * 这样多个智能体的静态前缀完全相同，可以命中同一份服务端 KV 缓存，
 * 池化使用比每个 bot 各建一个客户端便宜得多。</p>
 *
 * <p>用 {@link LinkedHashMap} 保存，保证 tick 顺序与列表输出顺序稳定。</p>
 *
 * <p><b>1.20.1 ↔ 1.21.11 版本差异（务必对照其他模块）</b>：</p>
 * <ul>
 *   <li>{@code placeNewPlayer} 在 1.21.11 是
 *       {@code (Connection, ServerPlayer, CommonListenerCookie)} <b>3 参数</b>，
 *       比 1.20.1 多一个 CommonListenerCookie；cookie 必须与构造
 *       {@code ServerGamePacketListenerImpl} 时用的是同一个对象。</li>
 *   <li>{@code PlayerList.respawn} 在 1.21.11 是
 *       {@code (ServerPlayer, boolean, Entity.RemovalReason)} <b>3 参数</b>，
 *       1.20.1 只有 2 参数（1.21.11 多出的 RemovalReason 必须传 KILLED）。</li>
 * </ul>
 */
public final class MultiBotManager {

    private static final Logger LOGGER = Logger.getLogger("aibot-multi");

    /** 单个智能体的运行时上下文：实体 + 大脑 + 记忆。 */
    public static final class Agent {
        public final BotProfile profile;
        public ServerPlayer entity;
        /** 重生后原版会换掉实体对象，这里单独记住备用。 */
        public ServerPlayer rebound;
        /** 决策循环。因为 AutoLoop 需要引用本 Agent 持有的短期记忆，
         *  构造顺序上先建 Agent 再建 loop，所以这里非 final。 */
        public AutoLoop loop;
        public final ShortTermMemory shortTermMemory = new ShortTermMemory();
        public final LongTermMemory longTermMemory;
        public final LandmarkMemory landmarkMemory;
        /** 本智能体听到的聊天（玩家发言）。每个 bot 一份，互不干扰。 */
        public final com.example.aibot.memory.ChatMemory chatMemory = new com.example.aibot.memory.ChatMemory();

        Agent(BotProfile profile, LongTermMemory ltm, LandmarkMemory lm, AutoLoop loop) {
            this.profile = profile;
            this.longTermMemory = ltm;
            this.landmarkMemory = lm;
            this.loop = loop;
        }

        /** 当前有效实体（优先原实例，其次重生后的普通实例）。 */
        public ServerPlayer player() {
            if (entity != null) {
                return entity;
            }
            return rebound;
        }

        /** 是否存活。 */
        public boolean alive() {
            ServerPlayer p = player();
            return p != null && p.isAlive();
        }

        /** 展示名。 */
        public String name() {
            return profile.name;
        }
    }

    /** 名字 → 智能体（顺序稳定）。 */
    private final Map<String, Agent> agents = new LinkedHashMap<>();

    private final AIConfig config;
    private final CacheStats cacheStats;
    private final LLMClient llmClient;
    private final java.nio.file.Path gameDir;

    /** 最近一次 spawn 失败的真实原因（供命令层显示给玩家，便于自助排查）。 */
    private volatile String lastError = null;

    public String getLastError() {
        return lastError;
    }

    public MultiBotManager(AIConfig config, CacheStats cacheStats, LLMClient llmClient,
                           java.nio.file.Path gameDir) {
        this.config = config;
        this.cacheStats = cacheStats;
        this.llmClient = llmClient;
        this.gameDir = gameDir;
    }

    // ------------------------------------------------------------------
    // 生成 / 移除
    // ------------------------------------------------------------------

    /**
     * 按档案生成一个智能体。
     *
     * @param server  服务器
     * @param profile 档案（名字必须已通过校验）
     * @return 生成好的智能体；失败返回 null
     */
    public Agent spawn(MinecraftServer server, BotProfile profile) {
        String name = profile.name;

        // 名字冲突检查：原版不允许同名玩家同时在线
        if (agents.containsKey(name)) {
            Agent existing = agents.get(name);
            if (existing.alive()) {
                LOGGER.warning("[AIBot] 智能体 " + name + " 已存在且存活，跳过生成");
                return existing;
            }
            // 已死但还在名单里：先清掉旧实体再重建
            removeSilently(server, existing);
        }

        String nameError = profile.validateName();
        if (nameError != null) {
            LOGGER.warning("[AIBot] 非法名字 " + name + "：" + nameError);
            lastError = "名字不合法：" + nameError;
            return null;
        }

        String stage = "准备";
        try {
            ServerLevel overworld = server.overworld();
            // 版本差异点：AIBotPlayer 在 1.21.11 内部会自己建 CommonListenerCookie，
            // 因此 spawn 这里不需要额外传参（1.20.1 亦然，签名保持一致）。
            stage = "① 创建玩家实体";
            AIBotPlayer bot = new AIBotPlayer(server, overworld, name, profile.resolvedSkinOwner());

            // 生成位置：自定义坐标优先，否则用世界重生点
            stage = "② 设置生成坐标";
            if (profile.spawnAtWorldSpawn) {
                var spawnPos = overworld.getSharedSpawnPos();
                bot.setPos(spawnPos.getX() + 0.5, spawnPos.getY(), spawnPos.getZ() + 0.5);
            } else {
                bot.setPos(profile.spawnX, profile.spawnY, profile.spawnZ);
            }
            bot.setYRot(0.0F);
            bot.setXRot(0.0F);

            // 注册进玩家列表 —— 这一步才让它真正「入服」：
            // 有 TAB 条目、能被渲染、能收发聊天、能被其他玩家看见。
            //
            // 【版本差异】1.21.11 的 placeNewPlayer 必须带上与构造连接时
            // 同一个 CommonListenerCookie，否则客户端会收到不一致的登录信息。
            stage = "③ 注册进玩家列表（placeNewPlayer）";
            server.getPlayerList().placeNewPlayer(
                    bot.getNetworkConnection(), bot, bot.createCookie());

            // 每个智能体一套独立记忆，按名字分文件，互不干扰
            stage = "④ 加载记忆文件";
            LongTermMemory ltm = new LongTermMemory(gameDir, name);
            LandmarkMemory lm = new LandmarkMemory(gameDir, name);
            ltm.load();
            lm.load();

            // 独立的决策循环。注意每个 agent 有自己的 AutoLoop 实例，
            // 因此可以各自设置目标、各自卡住重规划。
            // 短期记忆必须是同一个实例：Agent 持有它用于展示与压缩，
            // AutoLoop 用它记录每一步 —— 两份会导致状态展示永远是空的。
            stage = "⑤ 创建决策循环";
            Agent agent = new Agent(profile, ltm, lm, null);
            AutoLoop loop = new AutoLoop(config, cacheStats, llmClient, this,
                    agent.shortTermMemory, ltm, lm, profile, agent.chatMemory);
            agent.loop = loop;
            loop.restorePlan(ltm.loadPlan());
            // 目标优先级：档案里保存的 > 配置里的默认目标 > 空（自行决定）
            String effectiveGoal = profile.goal;
            if (effectiveGoal == null || effectiveGoal.trim().isEmpty()) {
                effectiveGoal = config.defaultGoal == null ? "" : config.defaultGoal.trim();
            }
            loop.setGoal(effectiveGoal);

            agents.put(name, agent);

            // 是否自动开始：
            //  - 档案里已标记 autoLoop 的（老档案/服务器重启恢复）→ 开始
            //  - 配置开启 autoStartOnSpawn（默认开）→ 新生成的也开始
            // 这样玩家 spawn 完就不用管了。
            stage = "⑥ 启动自主循环";
            boolean shouldStart = profile.autoLoop || config.autoStartOnSpawn;
            if (shouldStart) {
                loop.start();
                profile.autoLoop = true;
            }

            LOGGER.info("[AIBot] 智能体已生成: " + name
                    + " @ " + bot.blockPosition().toShortString()
                    + (profile.personality.isEmpty() ? "" : " 性格=" + profile.personality)
                    + (profile.autoLoop ? "（自主循环已开启）" : ""));
            return agent;

        } catch (Throwable t) {
            lastError = stage + " 失败 → " + t.getClass().getSimpleName()
                    + (t.getMessage() == null ? "（无消息）" : "：" + t.getMessage());
            LOGGER.log(Level.SEVERE, "[AIBot] 生成智能体 " + name
                    + " 失败于 [" + stage + "]", t);
            return null;
        }
    }

    /**
     * 移除一个智能体（并停止它的循环）。
     *
     * @return 是否真的移除了
     */
    public boolean remove(MinecraftServer server, String name) {
        Agent agent = agents.get(name);
        if (agent == null) {
            return false;
        }
        removeSilently(server, agent);
        agents.remove(name);
        LOGGER.info("[AIBot] 智能体已移除: " + name);
        return true;
    }

    /** 内部移除：停循环、存档、从玩家列表摘除。 */
    private void removeSilently(MinecraftServer server, Agent agent) {
        try {
            agent.loop.stop();
            agent.longTermMemory.save();
            agent.longTermMemory.savePlan(agent.loop.getPlan());
            agent.landmarkMemory.save();
        } catch (Throwable t) {
            LOGGER.log(Level.WARNING, "[AIBot] 保存 " + agent.name() + " 的数据时出错", t);
        }
        ServerPlayer p = agent.player();
        if (p != null) {
            try {
                server.getPlayerList().remove(p);
            } catch (Throwable t) {
                LOGGER.log(Level.WARNING, "[AIBot] 从玩家列表移除 " + agent.name() + " 时出错", t);
            }
        }
    }

    /** 移除全部智能体。 */
    public int removeAll(MinecraftServer server) {
        List<String> names = new ArrayList<>(agents.keySet());
        for (String n : names) {
            remove(server, n);
        }
        return names.size();
    }

    /**
     * 让某个智能体重生。
     *
     * <p><b>关键坑</b>：原版 {@code PlayerList.respawn(...)} 内部是
     * {@code new ServerPlayer(...)}，会创建一个全新的<b>普通</b> ServerPlayer，
     * 把我们的 {@link AIBotPlayer} 子类实例丢掉。所以重生后必须更新引用，
     * 否则后续所有操作都会打在已死亡的旧对象上。</p>
     *
     * <p><b>1.21.11 版本差异</b>：{@code respawn} 是 3 参数，
     * 比 1.20.1 多一个 {@code Entity.RemovalReason}。这里必须传
     * {@code KILLED}：它决定旧实体的移除方式（KILLED 才会正确触发
     * 死亡掉落与统计，传 CHANGED_DIMENSION 之类的值会留下幽灵实体）。</p>
     *
     * @return 重生后的玩家；失败返回 null
     */
    public ServerPlayer respawn(MinecraftServer server, String name) {
        Agent agent = agents.get(name);
        if (agent == null) {
            return null;
        }
        ServerPlayer old = agent.entity != null ? agent.entity : agent.rebound;
        if (old == null) {
            return null;
        }
        try {
            ServerPlayer fresh = server.getPlayerList().respawn(
                    old,
                    true,
                    net.minecraft.world.entity.Entity.RemovalReason.KILLED);
            if (fresh == null) {
                LOGGER.warning("[AIBot] " + name + " 重生失败：respawn 返回 null");
                return null;
            }
            // respawn 返回新对象，必须更新引用
            if (fresh instanceof AIBotPlayer ap) {
                agent.entity = ap;
                agent.rebound = null;
            } else {
                agent.entity = null;
                agent.rebound = fresh;
                LOGGER.info("[AIBot] " + name
                        + " 重生后不再是自定义实体子类，仍可正常控制");
            }
            LOGGER.info("[AIBot] " + name + " 已重生 @ " + fresh.blockPosition().toShortString());
            return fresh;
        } catch (Throwable t) {
            LOGGER.log(Level.SEVERE, "[AIBot] " + name + " 重生失败", t);
            return null;
        }
    }

    // ------------------------------------------------------------------
    // tick / 查询
    // ------------------------------------------------------------------

    /** 每 tick 驱动所有智能体（必须由主线程调用）。 */
    public void tick(long serverTick) {
        for (Agent a : agents.values()) {
            try {
                if (a.loop.isRunning()) {
                    a.loop.tick(serverTick);
                }
            } catch (Throwable t) {
                // 单个智能体出错绝不能拖垮其他智能体和服务器主循环
                LOGGER.log(Level.SEVERE, "[AIBot] 智能体 " + a.name() + " tick 异常，已单独停止它", t);
                try {
                    a.loop.stop();
                } catch (Throwable ignored) {
                    // 忽略二次异常
                }
            }
        }
    }

    /** 全部智能体（顺序稳定）。 */
    public List<Agent> all() {
        return new ArrayList<>(agents.values());
    }

    /** 按名字取智能体。 */
    public Agent get(String name) {
        return name == null ? null : agents.get(name);
    }

    /** 智能体数量。 */
    public int size() {
        return agents.size();
    }

    /** 是否一个都没有。 */
    public boolean isEmpty() {
        return agents.isEmpty();
    }

    /**
     * 兼容旧接口：取「第一个」智能体。
     *
     * <p>保留它是为了让早期只支持单 bot 的代码路径（动作调试命令等）
     * 仍然可用，而不必到处判空。</p>
     */
    public Agent first() {
        for (Agent a : agents.values()) {
            return a;
        }
        return null;
    }

    /** 第一个智能体的实体（可能为 null）。 */
    public ServerPlayer firstPlayer() {
        Agent a = first();
        return a == null ? null : a.player();
    }

    /** 是否任意一个智能体存活。 */
    public boolean anyAlive() {
        for (Agent a : agents.values()) {
            if (a.alive()) {
                return true;
            }
        }
        return false;
    }

    /**
     * 让所有智能体「听到」一条聊天。
     *
     * <p>真人在自己附近说话是能听见的。这里不做距离衰减 ——
     * 原版聊天本来就是全服可见的，距离衰减反而不像玩家。</p>
     *
     * <p>会跳过智能体自己发的消息，避免它们听见自己的话而无限自问自答。</p>
     *
     * @param speaker 说话者名字（用于判断是不是自己）
     * @param message 内容
     * @param step    当前步号
     */
    public void hearChat(String speaker, String message, long step) {
        for (Agent a : agents.values()) {
            // 自己的发言不必再听一遍
            if (a.name().equals(speaker)) {
                continue;
            }
            a.chatMemory.add(speaker, message, step);
        }
    }

    /** 给服务器停止时统一收尾。 */
    public void shutdown() {
        for (Agent a : agents.values()) {
            try {
                a.loop.stop();
                a.longTermMemory.save();
                a.longTermMemory.savePlan(a.loop.getPlan());
                a.landmarkMemory.save();
            } catch (Throwable t) {
                LOGGER.log(Level.WARNING, "[AIBot] 关闭时保存 " + a.name() + " 数据失败", t);
            }
        }
    }

    /**
     * 让指定智能体执行一个外部动作（供 /aibot do 调试命令用）。
     */
    public ActionExecutor adoptExternalAction(Agent agent, ActionExecutor executor) {
        if (agent != null) {
            agent.loop.adoptExternalAction(executor);
        }
        return executor;
    }
}
