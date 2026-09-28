package com.example.aibot.state;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 世界状态采集器（感知模块）。
 *
 * <p><b>缓存友好性要求（极其重要）</b>：</p>
 * <ul>
 *   <li>JSON 字段顺序必须【永远固定】，因此这里手写字符串拼接，
 *       绝不使用 Gson 序列化 HashMap（遍历顺序不稳定会破坏缓存）。</li>
 *   <li>数值精度固定（坐标保留 1 位小数），避免浮点尾数抖动。</li>
 *   <li>实体/方块列表按固定规则排序（距离升序，同距离按 ID 字典序），
 *       避免因内部遍历顺序不同导致文本变化。</li>
 * </ul>
 *
 * <p>采集项：自身坐标、血量、饥饿、背包、附近方块、附近实体、附近玩家、当前任务。</p>
 */
public final class StateCollector {

    /** 扫描半径（方块）。范围越大 token 消耗越多，8 格是延迟与信息的平衡点。 */
    private static final int BLOCK_SCAN_RADIUS = 8;

    /** 实体扫描半径。 */
    private static final double ENTITY_SCAN_RADIUS = 24.0;

    /** 最多列出的方块种类数（按数量排序取前 N，避免状态过长）。 */
    private static final int MAX_BLOCK_TYPES = 12;

    /** 最多列出的实体数。 */
    private static final int MAX_ENTITIES = 8;

    /** 最多列出的背包物品数。 */
    private static final int MAX_INVENTORY_ITEMS = 20;

    private StateCollector() {
    }

    /**
     * 采集当前状态并序列化为固定字段顺序的 JSON 字符串。
     *
     * @param bot          假玩家
     * @param currentGoal  当前长期目标（用于放进状态，便于模型对照）
     * @return 状态 JSON 字符串
     */
    public static String collect(net.minecraft.server.level.ServerPlayer bot, String currentGoal) {
        StringBuilder sb = new StringBuilder(2048);
        sb.append('{');

        // 固定顺序 1: self
        appendSelf(sb, bot);
        sb.append(',');

        // 固定顺序 2: inventory
        appendInventory(sb, bot);
        sb.append(',');

        // 固定顺序 3: nearby_blocks
        appendNearbyBlocks(sb, bot);
        sb.append(',');

        // 固定顺序 4: nearby_entities
        appendNearbyEntities(sb, bot);
        sb.append(',');

        // 固定顺序 5: nearby_players
        appendNearbyPlayers(sb, bot);
        sb.append(',');

        // 固定顺序 6: environment
        appendEnvironment(sb, bot);
        sb.append(',');

        // 固定顺序 7: goal（放在最后，因为它是唯一可能经常变化的外部输入）
        sb.append("\"goal\":").append(jsonString(
                currentGoal == null || currentGoal.trim().isEmpty() ? "" : currentGoal.trim()));

        sb.append('}');
        return sb.toString();
    }

    /** 自身状态：坐标、血量、饥饿、维度、是否在地面、时间。 */
    private static void appendSelf(StringBuilder sb, net.minecraft.server.level.ServerPlayer bot) {
        sb.append("\"self\":{");
        sb.append("\"name\":").append(jsonString(bot.getName().getString())).append(',');
        sb.append("\"x\":").append(fmt(bot.getX())).append(',');
        sb.append("\"y\":").append(fmt(bot.getY())).append(',');
        sb.append("\"z\":").append(fmt(bot.getZ())).append(',');
        sb.append("\"health\":").append(fmt(bot.getHealth())).append(',');
        sb.append("\"max_health\":").append(fmt(bot.getMaxHealth())).append(',');
        // 饥饿值：FoodData.getFoodLevel() 是 0~20 的整数
        sb.append("\"food\":").append(bot.getFoodData().getFoodLevel()).append(',');
        sb.append("\"saturation\":").append(fmt(bot.getFoodData().getSaturationLevel())).append(',');
        sb.append("\"on_ground\":").append(bot.onGround()).append(',');
        sb.append("\"alive\":").append(bot.isAlive()).append(',');
        sb.append("\"dimension\":").append(jsonString(bot.level().dimension().identifier().toString()));
        sb.append('}');
    }

    /** 背包物品：按槽位顺序，格式固定。 */
    private static void appendInventory(StringBuilder sb, net.minecraft.server.level.ServerPlayer bot) {
        sb.append("\"inventory\":[");
        Inventory inv = bot.getInventory();
        int written = 0;
        for (int i = 0; i < inv.getContainerSize() && written < MAX_INVENTORY_ITEMS; i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.isEmpty()) {
                continue;
            }
            if (written > 0) {
                sb.append(',');
            }
            sb.append('{');
            sb.append("\"slot\":").append(i).append(',');
            sb.append("\"item\":").append(jsonString(itemId(stack))).append(',');
            sb.append("\"count\":").append(stack.getCount());
            sb.append('}');
            written++;
        }
        sb.append(']');
    }

    /**
     * 附近方块：统计半径内出现的方块种类及数量，按【数量降序 + ID 字典序】排序，保证稳定。
     */
    private static void appendNearbyBlocks(StringBuilder sb, net.minecraft.server.level.ServerPlayer bot) {
        sb.append("\"nearby_blocks\":[");
        ServerLevel level = (ServerLevel) bot.level();
        BlockPos center = bot.blockPosition();

        // 用 List 收集再排序，避免 HashMap 顺序抖动
        List<BlockCount> counts = new ArrayList<>();
        for (int dx = -BLOCK_SCAN_RADIUS; dx <= BLOCK_SCAN_RADIUS; dx++) {
            for (int dy = -3; dy <= 3; dy++) {
                for (int dz = -BLOCK_SCAN_RADIUS; dz <= BLOCK_SCAN_RADIUS; dz++) {
                    BlockPos pos = center.offset(dx, dy, dz);
                    // 只统计已加载区块，避免强制加载造成卡顿
                    if (!level.isLoaded(pos)) {
                        continue;
                    }
                    BlockState state = level.getBlockState(pos);
                    if (state.isAir()) {
                        continue;
                    }
                    String id = blockId(state);
                    BlockCount found = null;
                    for (BlockCount bc : counts) {
                        if (bc.id.equals(id)) {
                            found = bc;
                            break;
                        }
                    }
                    if (found == null) {
                        counts.add(new BlockCount(id, 1, pos.immutable()));
                    } else {
                        found.count++;
                    }
                }
            }
        }

        // 稳定排序：数量多的在前；数量相同按 ID 字典序
        counts.sort(Comparator.comparingInt((BlockCount b) -> -b.count).thenComparing(b -> b.id));

        int written = 0;
        for (BlockCount bc : counts) {
            if (written >= MAX_BLOCK_TYPES) {
                break;
            }
            if (written > 0) {
                sb.append(',');
            }
            sb.append('{');
            sb.append("\"block\":").append(jsonString(bc.id)).append(',');
            sb.append("\"count\":").append(bc.count).append(',');
            // 附带一个示例坐标，方便模型直接指定挖掘目标
            sb.append("\"sample_x\":").append(bc.sample.getX()).append(',');
            sb.append("\"sample_y\":").append(bc.sample.getY()).append(',');
            sb.append("\"sample_z\":").append(bc.sample.getZ());
            sb.append('}');
            written++;
        }
        sb.append(']');
    }

    /** 附近实体：按距离升序，同距离按类型 ID 排序。 */
    private static void appendNearbyEntities(StringBuilder sb, net.minecraft.server.level.ServerPlayer bot) {
        sb.append("\"nearby_entities\":[");
        ServerLevel level = (ServerLevel) bot.level();
        AABB box = bot.getBoundingBox().inflate(ENTITY_SCAN_RADIUS);
        // 注意：AABB 版本的 getEntities 定义在 Level 上（不是 ServerLevel），
        // 1.21.x 里 ServerLevel 只保留了 EntityTypeTest 版本的重载。
        List<Entity> entities = level.getEntities(bot, box, e -> true);

        List<EntityInfo> infos = new ArrayList<>();
        for (Entity e : entities) {
            // 玩家单独归类，不在这里重复列出
            if (e instanceof ServerPlayer) {
                continue;
            }
            double dist = e.distanceTo(bot);
            infos.add(new EntityInfo(entityId(e), dist,
                    e instanceof LivingEntity le ? le.getHealth() : -1.0f,
                    e instanceof Monster,
                    e instanceof ItemEntity));
        }

        infos.sort(Comparator.comparingDouble((EntityInfo i) -> i.distance).thenComparing(i -> i.id));

        int written = 0;
        for (EntityInfo info : infos) {
            if (written >= MAX_ENTITIES) {
                break;
            }
            if (written > 0) {
                sb.append(',');
            }
            sb.append('{');
            sb.append("\"type\":").append(jsonString(info.id)).append(',');
            sb.append("\"distance\":").append(fmt(info.distance)).append(',');
            sb.append("\"hostile\":").append(info.hostile).append(',');
            sb.append("\"item\":").append(info.item);
            if (info.health >= 0) {
                sb.append(",\"health\":").append(fmt(info.health));
            }
            sb.append('}');
            written++;
        }
        sb.append(']');
    }

    /** 附近玩家：按距离升序。 */
    private static void appendNearbyPlayers(StringBuilder sb, net.minecraft.server.level.ServerPlayer bot) {
        sb.append("\"nearby_players\":[");
        List<ServerPlayer> players = ((ServerLevel) bot.level()).players();
        List<PlayerInfo> infos = new ArrayList<>();
        for (ServerPlayer p : players) {
            if (p.getUUID().equals(bot.getUUID())) {
                continue;
            }
            infos.add(new PlayerInfo(p.getName().getString(), p.distanceTo(bot)));
        }
        infos.sort(Comparator.comparingDouble((PlayerInfo i) -> i.distance).thenComparing(i -> i.name));

        int written = 0;
        for (PlayerInfo info : infos) {
            if (written >= MAX_ENTITIES) {
                break;
            }
            if (written > 0) {
                sb.append(',');
            }
            sb.append('{');
            sb.append("\"name\":").append(jsonString(info.name)).append(',');
            sb.append("\"distance\":").append(fmt(info.distance));
            sb.append('}');
            written++;
        }
        sb.append(']');
    }

    /** 环境：时间、天气、光照。 */
    private static void appendEnvironment(StringBuilder sb, net.minecraft.server.level.ServerPlayer bot) {
        ServerLevel level = (ServerLevel) bot.level();
        long dayTime = level.getDayTime() % 24000L;
        sb.append("\"environment\":{");
        sb.append("\"day_time\":").append(dayTime).append(',');
        sb.append("\"is_day\":").append(dayTime < 13000L).append(',');
        // getSkyDarken 反映天空亮度，约等于夜晚指示
        sb.append("\"is_raining\":").append(level.isRaining()).append(',');
        sb.append("\"is_thundering\":").append(level.isThundering());
        sb.append('}');
    }

    // ------------------------------------------------------------------
    // 工具方法
    // ------------------------------------------------------------------

    /**
     * 坐标格式化：固定 1 位小数。
     * 固定精度可避免浮点尾数抖动破坏缓存与记忆一致性。
     */
    private static String fmt(double v) {
        return String.format(java.util.Locale.ROOT, "%.1f", v);
    }

    /** 转义 JSON 字符串（最小实现，足够处理 ID 与玩家名）。 */
    private static String jsonString(String s) {
        if (s == null) {
            return "\"\"";
        }
        StringBuilder sb = new StringBuilder(s.length() + 2);
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
        return sb.toString();
    }

    /** 取物品的注册 ID（如 minecraft:oak_log）。 */
    private static String itemId(ItemStack stack) {
        ResourceLocation key = BuiltInRegistries.ITEM.getKey(stack.getItem());
        return key == null ? "unknown" : key.toString();
    }

    /** 取方块注册 ID。 */
    private static String blockId(BlockState state) {
        ResourceLocation key = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        return key == null ? "unknown" : key.toString();
    }

    /** 取实体类型注册 ID。 */
    private static String entityId(Entity e) {
        ResourceLocation key = BuiltInRegistries.ENTITY_TYPE.getKey(e.getType());
        return key == null ? "unknown" : key.toString();
    }

    // 内部辅助记录类
    private static final class BlockCount {
        final String id;
        int count;
        final BlockPos sample;

        BlockCount(String id, int count, BlockPos sample) {
            this.id = id;
            this.count = count;
            this.sample = sample;
        }
    }

    private record EntityInfo(String id, double distance, float health, boolean hostile, boolean item) {
    }

    private record PlayerInfo(String name, double distance) {
    }
}
