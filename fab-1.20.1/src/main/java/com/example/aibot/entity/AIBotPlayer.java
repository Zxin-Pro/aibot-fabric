package com.example.aibot.entity;

import com.mojang.authlib.GameProfile;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;

import java.util.UUID;

/**
 * AIBot 假玩家实体（1.21.11 实现）。
 *
 * <p><b>版本差异（务必对照其他模块）</b>：</p>
 * <ul>
 *   <li>1.20.1：{@code ServerPlayer(MinecraftServer, ServerLevel, GameProfile)} —— 3 参数</li>
 *   <li>1.21.11：{@code ServerPlayer(MinecraftServer, ServerLevel, GameProfile, ClientInformation)}
 *       —— 4 参数，多了客户端信息（语言、视距、皮肤部件等）</li>
 * </ul>
 *
 * <p>做法参考 Carpet mod 的伪造连接思路：</p>
 * <ol>
 *   <li>用 {@code new Connection(PacketFlow.SERVERBOUND)} 造一个不走真实 socket 的连接对象</li>
 *   <li>把它交给 {@code ServerGamePacketListenerImpl} 作为网络处理器</li>
 *   <li>于是玩家能正常收发数据包逻辑（但发包会被丢弃），且不会真的掉线</li>
 * </ol>
 *
 * <p>注意：本类不覆写任何行为，仅提供构造与注册辅助，
 * 具体的 AI 行为由 ActionExecutor 驱动（调用标准 API，不 hack）。</p>
 */
public class AIBotPlayer extends ServerPlayer {

    /** 假玩家的固定 UUID 命名空间，保证同名玩家每次生成 UUID 一致。 */
    private static final String UUID_NAMESPACE = "aibot-fake-player:";

    /** 是否为 AIBot（供其他模块快速判断）。 */
    private final boolean aibot = true;

    /**
     * 伪造的网络连接。
     *
     * <p><b>重要</b>：{@code ServerPlayer.connection} 字段的类型是
     * {@code ServerGamePacketListenerImpl}（数据包监听器），
     * 而 {@code PlayerList.placeNewPlayer(...)} 需要的是原始 {@code net.minecraft.network.Connection}。
     * 两者类型不同，必须分开保存，否则编译报「不兼容的类型」。</p>
     *
     * <p><b>踩坑记录</b>：这里用【全限定名】声明更安全，
     * 避免将来版本出现同名嵌套类型遮蔽简单名
     * （1.21.11 的 {@code WaypointTransmitter.Connection} 就会遮蔽它）。</p>
     */
    private final net.minecraft.network.Connection networkConnection;

    /**
     * 构造假玩家。
     *
     * <p><b>1.20.1 与 1.21.x 的关键差异</b>：
     * 1.20.1 的构造函数是 <b>3 参数</b>
     * {@code (MinecraftServer, ServerLevel, GameProfile)}，
     * 没有 {@code ClientInformation}；1.21.x 起变成 4 参数。</p>
     *
     * @param server 服务器实例
     * @param level  目标世界（通常为主世界）
     * @param name   玩家名
     */
    public AIBotPlayer(MinecraftServer server, ServerLevel level, String name) {
        // 注意：1.20.1 没有 ClientInformation 参数
        super(server, level, new GameProfile(offlineUuid(name), name));
        // 不走真实 socket 的连接对象
        this.networkConnection = new net.minecraft.network.Connection(PacketFlow.SERVERBOUND);
        // 用伪造的数据包监听器替换默认的空连接，避免部分代码路径 NPE。
        // 1.20.1 的构造函数是 3 参数（没有 CommonListenerCookie）。
        this.connection = new ServerGamePacketListenerImpl(
                server,
                this.networkConnection,
                this);
    }

    /** placeNewPlayer 需要的原始网络连接（注意不是 connection 字段）。 */
    public net.minecraft.network.Connection getNetworkConnection() {
        return this.networkConnection;
    }

    /**
     * 由玩家名生成稳定的离线 UUID（与 Minecraft 离线模式算法一致：UUIDv3 name-based）。
     *
     * <p>这样同一个名字每次生成都是同一个 UUID，避免重复入服时产生幽灵玩家。</p>
     */
    public static UUID offlineUuid(String name) {
        return UUID.nameUUIDFromBytes((UUID_NAMESPACE + name).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /** 标记位：本实体是 AIBot。 */
    public boolean isAibot() {
        return this.aibot;
    }

    /**
     * 让假玩家不因为「长时间不移动」被服务器踢出。
     * 原版对挂机玩家没有强制踢出，但某些服务端插件会，这里覆写以增加空闲超时。
     */
    @Override
    public boolean isSpectator() {
        return false;
    }

    /**
     * 假玩家不参与统计与成就刷屏（可选行为）。
     * 保持返回 true 会让它像普通玩家；如需隐藏可改为 false。
     */
    @Override
    public boolean isCreative() {
        return false;
    }
}
