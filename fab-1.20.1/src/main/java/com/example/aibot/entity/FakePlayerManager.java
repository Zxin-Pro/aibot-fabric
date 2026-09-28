package com.example.aibot.entity;

import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 假玩家生命周期管理器：负责生成、注册到玩家列表、移除。
 *
 * <p>关键点：光 new 一个 {@link ServerPlayer} 不会出现在玩家列表里，
 * 必须调用 {@code PlayerList.placeNewPlayer(...)} 才会真正「入服」，
 * 从而拥有 TAB 列表、实体渲染、能收到聊天、能被其他玩家看到。</p>
 *
 * <p>我们传入的 {@code Connection} 是伪造的（不走 socket），
 * 因此 placeNewPlayer 内部发送的登录包会被丢弃，不会抛异常。</p>
 */
public final class FakePlayerManager {

    private static final Logger LOGGER = Logger.getLogger("aibot-spawn");

    /** 当前存活的假玩家（同一时间只允许一个，简化设计）。 */
    private AIBotPlayer currentBot;

    /**
     * 重生后由原版创建的普通 ServerPlayer。
     * 原版 respawn 会 new 一个新的 ServerPlayer，丢掉我们的 AIBotPlayer 子类，
     * 所以这里单独保存，保证重生后仍能继续控制。
     */
    private ServerPlayer plainBot;

    /** 最近一次 spawn 时的服务器引用，兜底用。 */
    private MinecraftServer lastServer;

    /**
     * 生成假玩家。
     *
     * @param server 服务器
     * @param name   玩家名
     * @return 生成好的假玩家；失败返回 null
     */
    public AIBotPlayer spawn(MinecraftServer server, String name) {
        if (this.currentBot != null && this.currentBot.isAlive()) {
            LOGGER.warning("[AIBot] 假玩家已存在，先执行 remove 再 spawn");
            return this.currentBot;
        }

        try {
            this.lastServer = server;
            ServerLevel overworld = server.overworld();

            // 1. 构造假玩家实体（版本差异封装在 AIBotPlayer 里）
            AIBotPlayer bot = new AIBotPlayer(server, overworld, name);

            // 2. 设置初始位置：世界重生点（1.21.11 用 getRespawnData().pos()，
            //    旧版本是 getSharedSpawnPos()，这是版本差异点之一）
            var spawnPos = overworld.getSharedSpawnPos();
            bot.setPos(spawnPos.getX() + 0.5, spawnPos.getY(), spawnPos.getZ() + 0.5);
            bot.setYRot(0.0F);
            bot.setXRot(0.0F);

            // 3. 注册到玩家列表，让它真正「入服」
            //    注意参数类型：第一个参数要原始 Connection（不是 connection 字段，
            //    那个是 ServerGamePacketListenerImpl）。
            server.getPlayerList().placeNewPlayer(bot.getNetworkConnection(), bot);

            this.currentBot = bot;
            LOGGER.info("[AIBot] 假玩家已生成: " + name + " @ " + spawnPos.toShortString());
            return bot;

        } catch (Throwable t) {
            // 假玩家涉及大量内部状态，任何异常都不应导致服务器崩溃
            LOGGER.log(Level.SEVERE, "[AIBot] 生成假玩家失败", t);
            this.currentBot = null;
            return null;
        }
    }

    /**
     * 移除假玩家。
     *
     * @return 是否真的移除了
     */
    public boolean remove(MinecraftServer server) {
        ServerPlayer bot = getPlayer();
        if (bot == null) {
            return false;
        }
        try {
            server.getPlayerList().remove(bot);
        } catch (Throwable t) {
            LOGGER.log(Level.WARNING, "[AIBot] 从玩家列表移除假玩家时出错", t);
        }
        this.currentBot = null;
        this.plainBot = null;
        LOGGER.info("[AIBot] 假玩家已移除");
        return true;
    }

    /**
     * 让假玩家重生。
     *
     * <p><b>关键坑</b>：原版 {@code PlayerList.respawn(...)} 内部是
     * {@code new ServerPlayer(...)} —— 它会创建一个<b>全新的普通 ServerPlayer</b>，
     * 把我们的 {@link AIBotPlayer} 子类实例丢掉。
     * 因此重生后必须用返回的新实例替换 {@link #currentBot}，
     * 否则后续所有 {@code getBot()} 调用都会拿到已死亡/已移除的旧对象。</p>
     *
     * @param server 服务器
     * @return 重生后的玩家；失败返回 null
     */
    public ServerPlayer respawn(MinecraftServer server) {
        AIBotPlayer old = this.currentBot;
        if (old == null) {
            return null;
        }
        try {
            ServerPlayer fresh = server.getPlayerList().respawn(old, true);
            if (fresh == null) {
                LOGGER.warning("[AIBot] 重生失败：respawn 返回 null");
                return null;
            }
            // 重要：respawn 返回的是新对象，必须更新引用
            this.currentBot = (fresh instanceof AIBotPlayer ap) ? ap : null;
            this.plainBot = (this.currentBot == null) ? fresh : null;

            LOGGER.info("[AIBot] 假玩家已重生: " + fresh.getName().getString()
                    + " @ " + fresh.blockPosition().toShortString()
                    + (this.currentBot == null ? "（注意：重生后不再是我们自己的实体子类，行为仍可用）" : ""));
            return fresh;
        } catch (Throwable t) {
            LOGGER.log(Level.SEVERE, "[AIBot] 假玩家重生失败", t);
            return null;
        }
    }

    /** 当前假玩家（可能为 null）。 */
    public ServerPlayer getPlayer() {
        if (this.currentBot != null) {
            return this.currentBot;
        }
        return this.plainBot;
    }

    /** 最近一次操作的服务器实例（重生等操作需要）。 */
    public MinecraftServer getServerOf() {
        ServerPlayer p = getPlayer();
        if (p != null && p.level() != null) {
            return p.level().getServer();
        }
        return this.lastServer;
    }

    /** 记录服务器引用（spawn 时更新），用于玩家对象不可用时仍能操作。 */
    public void setServer(MinecraftServer server) {
        this.lastServer = server;
    }

    /** 当前假玩家，可能为 null。 */
    public AIBotPlayer getBot() {
        return this.currentBot;
    }

    /** 任意形态的假玩家是否存活（含重生后的普通 ServerPlayer）。 */
    public boolean isPlayerAlive() {
        ServerPlayer p = getPlayer();
        return p != null && p.isAlive();
    }

    /** 假玩家是否存在且存活。 */
    public boolean isAlive() {
        return isPlayerAlive();
    }

    /**
     * 让假玩家在聊天栏说话。
     *
     * <p>用 sendSystemMessage 把消息发给全体玩家，模拟聊天效果。</p>
     *
     * @param server  服务器
     * @param message 内容
     */
    public void chat(MinecraftServer server, String message) {
        if (message == null || message.trim().isEmpty()) {
            return;
        }
        ServerPlayer bot = getPlayer();
        String text = "<" + (bot != null ? bot.getName().getString() : "AIBot") + "> " + message.trim();
        try {
            server.getPlayerList().broadcastSystemMessage(Component.literal(text), false);
            LOGGER.info("[AIBot] 假玩家说话: " + text);
        } catch (Throwable t) {
            LOGGER.log(Level.WARNING, "[AIBot] 发送聊天消息失败", t);
        }
    }

    /** 给单个玩家发消息（用于命令反馈）。 */
    public static void tell(ServerPlayer player, String message) {
        player.sendSystemMessage(Component.literal(message));
    }
}
