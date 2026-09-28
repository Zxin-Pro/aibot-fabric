package com.example.aibot.entity;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 单个 AIBot 的身份档案：名字、性格、皮肤、生成位置等。
 *
 * <p><b>参照 Carpet 模组的 /player 命令设计</b>：Carpet 允许用
 * {@code /player <名字> spawn} 生成任意命名的假玩家，
 * 名字即身份，同名 = 同一个玩家。这里沿用同样的心智模型，
 * 并额外支持「一个名字对应一套性格档案」。</p>
 *
 * <p><b>为什么需要独立档案而不是全局一个 botName</b>：
 * 同时跑多个智能体时，每个必须有独立的 UUID、独立记忆、
 * 独立目标，否则它们会互相覆盖记忆文件、互相抢同一个实体。</p>
 *
 * <p>本类不引用任何 Minecraft 类，纯数据，各版本线共用。</p>
 */
public final class BotProfile {

    /** 玩家名（Carpet 风格：名字即身份，必须符合原版玩家名规则）。 */
    public String name = "AIBot";

    /**
     * 性格提示词：追加到该 bot 的动态提示词里，让每个智能体有不同行为倾向。
     * 例如「谨慎的农夫，优先种地，遇敌就躲」。
     */
    public String personality = "";

    /** 长期目标（自然语言）。 */
    public String goal = "";

    /** 是否启用自主循环。 */
    public boolean autoLoop = false;

    /** 生成坐标；三个都为 0 且 spawnAtWorldSpawn=true 时使用世界重生点。 */
    public double spawnX = 0;
    public double spawnY = 0;
    public double spawnZ = 0;

    /** 是否在世界重生点生成（默认 true；设了自定义坐标则为 false）。 */
    public boolean spawnAtWorldSpawn = true;

    /**
     * 皮肤所有者名字。留空则用 {@link #name}。
     *
     * <p>原版在离线模式下无法真正换皮，但正版服务器上
     * 会按 GameProfile 的名字去查皮肤，因此填一个正版玩家名即可「借用」其皮肤外观。</p>
     */
    public String skinOwner = "";

    /** 创建时间（毫秒），用于列表排序。 */
    public long createdAt = System.currentTimeMillis();

    public BotProfile() {
    }

    public BotProfile(String name) {
        this.name = name == null ? "AIBot" : name.trim();
    }

    /**
     * 校验玩家名是否合法。
     *
     * <p>原版规则：3~16 个字符，仅允许字母、数字、下划线。
     * 不校验的话，非法名字会在 {@code PlayerList.placeNewPlayer} 里
     * 抛出难以理解的异常，甚至产生无法移除的幽灵玩家。</p>
     *
     * @return 合法返回 null，否则返回错误说明
     */
    public String validateName() {
        if (name == null || name.isEmpty()) {
            return "名字不能为空";
        }
        if (name.length() < 3 || name.length() > 16) {
            return "名字长度必须在 3~16 个字符之间（当前 " + name.length() + "）";
        }
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            boolean ok = c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z'
                    || c >= '0' && c <= '9' || c == '_';
            if (!ok) {
                return "名字只能包含字母、数字、下划线（非法字符: '" + c + "'）";
            }
        }
        return null;
    }

    /**
     * 由名字生成稳定 UUID。
     *
     * <p>用 {@link UUID#nameUUIDFromBytes}（UUIDv3，MD5 名字散列），
     * 与离线模式服务器生成玩家 UUID 的思路一致。
     * 同一个名字永远得到同一个 UUID，因此：
     * 移除后重新 spawn，仍然被服务器认为是同一个玩家，
     * 背包与统计数据得以延续 —— 这正是 Carpet 假玩家的行为。</p>
     *
     * <p>命名空间与 {@code AIBotPlayer.offlineUuid} 保持一致，
     * 否则同名的 AIBotPlayer 与自己算出的 UUID 会对不上。</p>
     */
    public UUID uuid() {
        return UUID.nameUUIDFromBytes(("aibot-fake-player:" + name)
                .getBytes(StandardCharsets.UTF_8));
    }

    /** 皮肤归属名（留空时回落到玩家名）。 */
    public String resolvedSkinOwner() {
        return skinOwner == null || skinOwner.trim().isEmpty() ? name : skinOwner.trim();
    }

    /** 复制一份（避免命令层直接改到运行中的档案）。 */
    public BotProfile copy() {
        BotProfile p = new BotProfile();
        p.name = this.name;
        p.personality = this.personality;
        p.goal = this.goal;
        p.autoLoop = this.autoLoop;
        p.spawnX = this.spawnX;
        p.spawnY = this.spawnY;
        p.spawnZ = this.spawnZ;
        p.spawnAtWorldSpawn = this.spawnAtWorldSpawn;
        p.skinOwner = this.skinOwner;
        p.createdAt = this.createdAt;
        return p;
    }

    /** 档案列表容器（供 Gson 序列化成 JSON 数组的包装）。 */
    public static final class ListFile {
        public List<BotProfile> bots = new ArrayList<>();
    }

    @Override
    public String toString() {
        return name + (personality.isEmpty() ? "" : "（" + truncate(personality, 20) + "）");
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
