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
            ServerLevel overworld = server.overworld();

            // 1. 构造假玩家实体（版本差异封装在 AIBotPlayer 里）
            AIBotPlayer bot = new AIBotPlayer(server, overworld, name);

            // 2. 设置初始位置：世界重生点（1.21.11 用 getRespawnData().pos()，
            //    旧版本是 getSharedSpawnPos()，这是版本差异点之一）
            var spawnPos = overworld.getRespawnData().pos();
            bot.setPos(spawnPos.getX() + 0.5, spawnPos.getY(), spawnPos.getZ() + 0.5);
            bot.setYRot(0.0F);
            bot.setXRot(0.0F);

            // 3. 注册到玩家列表，让它真正「入服」
            //    注意参数类型：第一个参数要原始 Connection（不是 connection 字段，
            //    那个是 ServerGamePacketListenerImpl）。
            server.getPlayerList().placeNewPlayer(
                    bot.getNetworkConnection(), bot, bot.createCookie());

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
        AIBotPlayer bot = this.currentBot;
        if (bot == null) {
            return false;
        }
        try {
            server.getPlayerList().remove(bot);
        } catch (Throwable t) {
            LOGGER.log(Level.WARNING, "[AIBot] 从玩家列表移除假玩家时出错", t);
        }
        this.currentBot = null;
        LOGGER.info("[AIBot] 假玩家已移除");
        return true;
    }

    /** 当前假玩家，可能为 null。 */
    public AIBotPlayer getBot() {
        return this.currentBot;
    }

    /** 假玩家是否存在且存活。 */
    public boolean isAlive() {
        return this.currentBot != null && this.currentBot.isAlive();
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
        String text = "<" + (this.currentBot != null
                ? this.currentBot.getName().getString()
                : "AIBot") + "> " + message.trim();
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
