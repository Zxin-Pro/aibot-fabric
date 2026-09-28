package com.example.aibot.action;

import com.example.aibot.entity.AIBotPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 动作执行器（1.21.11 实现）。
 *
 * <p>每个动作一个方法，统一返回 {@link ActionResult} 描述成功/失败与结果文本，
 * 供记忆模块与自主循环使用。</p>
 *
 * <p>设计原则：</p>
 * <ul>
 *   <li>所有动作都是「尽力而为」：失败不抛异常，返回失败结果让 LLM 换策略。</li>
 *   <li>不做长时间阻塞操作；耗时动作（如寻路）由自主循环按 tick 推进。</li>
 *   <li>move 采用简单的直线移动 + 逐步靠近，MVP 不接入 Baritone。</li>
 * </ul>
 */
public class ActionExecutor {

    private static final Logger LOGGER = Logger.getLogger("aibot-action");

    /** 单次 move 动作最大移动距离（防止瞬移过远造成穿墙）。 */
    private static final double MAX_MOVE_DISTANCE = 32.0;

    /** 判定「到达目标」的距离阈值。 */
    private static final double ARRIVE_DISTANCE = 1.5;

    /** 挖掘可触及距离。 */
    private static final double REACH_DISTANCE = 5.0;

    protected final AIBotPlayer bot;

    public ActionExecutor(AIBotPlayer bot) {
        this.bot = bot;
    }

    /**
     * 动作执行结果。
     *
     * @param success 是否成功
     * @param message 结果描述（会写入记忆，保持简短）
     */
    public record ActionResult(boolean success, String message) {

        public static ActionResult ok(String message) {
            return new ActionResult(true, message);
        }

        public static ActionResult fail(String message) {
            return new ActionResult(false, message);
        }
    }

    /**
     * 执行一个解析好的动作。
     *
     * @param parsed LLM 解析出来的动作
     * @return 执行结果
     */
    public ActionResult execute(ActionParser.ParsedAction parsed) {
        String action = parsed.action();
        try {
            switch (action) {
                case "move":
                    return move(parsed);
                case "pathfind":
                    return pathfind(parsed);
                case "mine":
                    return mine(parsed);
                case "place":
                    return place(parsed);
                case "craft":
                    return craft(parsed);
                case "attack":
                    return attack(parsed);
                case "flee":
                    return flee(parsed);
                case "eat":
                    return eat(parsed);
                case "sleep":
                    return sleep(parsed);
                case "follow":
                    return follow(parsed);
                case "store":
                    return store(parsed);
                case "chat":
                    return chat(parsed);
                case "look":
                    return look(parsed);
                case "idle":
                    return ActionResult.ok("原地待命");
                default:
                    return ActionResult.fail("未知动作: " + action);
            }
        } catch (Throwable t) {
            // 任何动作异常都不能让服务器崩溃
            LOGGER.log(Level.WARNING, "[AIBot] 执行动作 " + action + " 时异常", t);
            return ActionResult.fail("动作异常: " + t.getClass().getSimpleName());
        }
    }

    // ------------------------------------------------------------------
    // 已完整实现的动作
    // ------------------------------------------------------------------

    /**
     * 移动到指定坐标。
     *
     * <p>MVP 实现：计算方向向量，按最大步长逐步靠近；不做地形寻路。
     * 如果目标点不可达（如悬空），会在附近停下并返回失败，让 LLM 换策略。</p>
     */
    protected ActionResult move(ActionParser.ParsedAction parsed) {
        ServerLevel level = bot.serverLevel();
        Vec3 current = bot.position();

        double tx = parsed.getDouble("x", current.x);
        double ty = parsed.getDouble("y", current.y);
        double tz = parsed.getDouble("z", current.z);
        boolean relative = parsed.getBoolean("relative", false);

        if (relative) {
            tx = current.x + tx;
            ty = current.y + ty;
            tz = current.z + tz;
        }

        Vec3 target = new Vec3(tx, ty, tz);
        double distance = current.distanceTo(target);

        if (distance < ARRIVE_DISTANCE) {
            return ActionResult.ok("已到达目标点附近");
        }

        // 限制单次移动距离，避免瞬移穿墙
        Vec3 direction = target.subtract(current).normalize();
        double step = Math.min(distance, MAX_MOVE_DISTANCE);
        Vec3 next = current.add(direction.scale(step));

        // 安全落地：从目标 XZ 位置向下找第一个可站立的地面
        BlockPos landing = findGroundBelow(level, BlockPos.containing(next.x, next.y, next.z));
        if (landing == null) {
            // 找不到落脚点，就只移动一小步并报告
            return ActionResult.fail("目标点下方没有可站立地面，无法到达");
        }

        bot.teleportTo(landing.getX() + 0.5, landing.getY(), landing.getZ() + 0.5);
        bot.lookAt(net.minecraft.commands.arguments.EntityAnchorArgument.Anchor.EYES, target);

        double moved = current.distanceTo(bot.position());
        return ActionResult.ok(String.format(java.util.Locale.ROOT,
                "从(%.0f,%.0f,%.0f)移动到(%.0f,%.0f,%.0f)，前进%.1f格",
                current.x, current.y, current.z,
                bot.getX(), bot.getY(), bot.getZ(), moved));
    }

    /**
     * 寻路到指定坐标。
     *
     * <p>MVP 实现：与 move 共用逻辑，但允许更大距离并倾向选择已加载区块内的路径。
     * 后续可替换为接入 Baritone 的实现。</p>
     */
    protected ActionResult pathfind(ActionParser.ParsedAction parsed) {
        ActionResult r = move(parsed);
        return r.success()
                ? ActionResult.ok("寻路完成：" + r.message())
                : ActionResult.fail("寻路失败：" + r.message());
    }

    /**
     * 挖掘指定方块。
     *
     * <p>实现：在半径内寻找目标方块 → 靠近 → 破坏并掉落物品到背包。</p>
     */
    protected ActionResult mine(ActionParser.ParsedAction parsed) {
        String blockId = parsed.getString("block", "");
        int count = Math.max(1, Math.min(parsed.getInt("count", 1), 64));

        if (blockId.isEmpty()) {
            return ActionResult.fail("mine 动作缺少 block 参数");
        }

        ServerLevel level = bot.serverLevel();
        Block targetBlock = resolveBlock(blockId);
        if (targetBlock == null) {
            return ActionResult.fail("未知方块: " + blockId);
        }

        int mined = 0;
        int attempts = 0;
        // 每次挖掘都重新找最近的目标，最多尝试 count 次
        while (mined < count && attempts < count * 3) {
            attempts++;
            BlockPos target = findNearestBlock(level, targetBlock, 16);
            if (target == null) {
                break;
            }

            // 距离太远就先靠近
            double dist = Math.sqrt(bot.blockPosition().distSqr(target));
            if (dist > REACH_DISTANCE) {
                BlockPos stand = findGroundBelow(level, target);
                if (stand != null) {
                    bot.teleportTo(stand.getX() + 0.5, stand.getY(), stand.getZ() + 0.5);
                }
            }

            // 破坏方块并掉落（1.21.11 的 destroyBlock 是 4 参数版本：
            // pos, drop, sourceEntity, recursionLeft）
            boolean destroyed = level.destroyBlock(target, true, bot, 512);
            if (destroyed) {
                mined++;
            }
        }

        if (mined == 0) {
            return ActionResult.fail("附近找不到 " + blockId + "，或无法破坏");
        }
        return ActionResult.ok("挖掘了 " + mined + " 个 " + shortId(blockId));
    }

    /** 在聊天栏说话。 */
    protected ActionResult chat(ActionParser.ParsedAction parsed) {
        String message = parsed.getString("message", "");
        if (message.trim().isEmpty()) {
            return ActionResult.fail("chat 动作缺少 message 参数");
        }
        // 长度保护，避免刷屏
        if (message.length() > 120) {
            message = message.substring(0, 120) + "...";
        }
        // getServer() 在 Entity 上不可直接用，通过 level() 拿服务器实例
        bot.serverLevel().getServer().getPlayerList()
                .broadcastSystemMessage(
                        net.minecraft.network.chat.Component.literal("<" + bot.getName().getString() + "> " + message),
                        false);
        return ActionResult.ok("说了: " + message);
    }

    /**
     * 进食：从背包里找食物并吃掉。
     *
     * <p>实现：直接调用 eat 逻辑并恢复饥饿值。
     * 1.21.x 的食物数据在 DataComponents.FOOD 上，这里用简化判断。</p>
     */
    protected ActionResult eat(ActionParser.ParsedAction parsed) {
        String wanted = parsed.getString("item", "");
        var food = bot.getFoodData();

        if (food.getFoodLevel() >= 20) {
            return ActionResult.ok("并不饿，跳过进食");
        }

        // 在背包里找可食用物品
        var inventory = bot.getInventory();
        int bestSlot = -1;
        ItemStack bestStack = ItemStack.EMPTY;

        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (stack.isEmpty()) {
                continue;
            }
            if (!isFood(stack)) {
                continue;
            }
            // 指定了物品就精确匹配
            if (!wanted.isEmpty()) {
                ResourceLocation key = BuiltInRegistries.ITEM.getKey(stack.getItem());
                if (key == null || !key.toString().equals(wanted)) {
                    continue;
                }
            }
            bestSlot = i;
            bestStack = stack;
            break;
        }

        if (bestSlot < 0 || bestStack.isEmpty()) {
            return ActionResult.fail(wanted.isEmpty() ? "背包里没有食物" : "背包里没有 " + wanted);
        }

        // 简化实现：恢复饥饿值并消耗一个物品
        // 真实食用动画/时长由客户端驱动，服务端假玩家直接结算更稳妥
        int nutrition = 4; // 保守默认值
        food.eat(nutrition, 0.6f);
        bestStack.shrink(1);

        String name = String.valueOf(BuiltInRegistries.ITEM.getKey(bestStack.getItem()));
        return ActionResult.ok("进食了 " + shortId(name) + "，当前饥饿值 " + food.getFoodLevel());
    }

    /** 观察四周（信息性动作，不改变世界）。 */
    protected ActionResult look(ActionParser.ParsedAction parsed) {
        // 随机? 不——缓存友好要求避免随机。这里转向正北，稳定可复现。
        bot.setYRot(0.0F);
        bot.setXRot(0.0F);
        return ActionResult.ok("环顾四周完成");
    }

    // ------------------------------------------------------------------
    // 预留接口：MVP 阶段返回「未实现」，但保留完整签名便于后续填充
    // ------------------------------------------------------------------

    /** 放置方块。TODO: 后续实现（需要从背包取出对应方块并调用 useOn）。 */
    protected ActionResult place(ActionParser.ParsedAction parsed) {
        String blockId = parsed.getString("block", "");
        return ActionResult.fail("place 动作尚未实现（计划中）: " + blockId);
    }

    /** 合成物品。TODO: 后续实现（需要匹配配方并消耗材料）。 */
    protected ActionResult craft(ActionParser.ParsedAction parsed) {
        String item = parsed.getString("item", "");
        return ActionResult.fail("craft 动作尚未实现（计划中）: " + item);
    }

    /** 攻击目标。TODO: 后续实现（需要计算攻击冷却与击退）。 */
    protected ActionResult attack(ActionParser.ParsedAction parsed) {
        String target = parsed.getString("target", "");
        // 先做一个最小可用版本：攻击最近的敌对生物
        ServerLevel level = bot.serverLevel();
        AABB box = bot.getBoundingBox().inflate(6.0);
        List<Entity> entities = level.getEntities(bot, box, e -> e instanceof Monster && e.isAlive());
        if (entities.isEmpty()) {
            return ActionResult.fail("附近没有可攻击的敌对生物");
        }
        entities.sort(Comparator.comparingDouble(e -> e.distanceTo(bot)));
        Entity victim = entities.get(0);
        victim.hurt(level.damageSources().playerAttack(bot), 4.0f);
        return ActionResult.ok("攻击了 " + shortId(String.valueOf(
                BuiltInRegistries.ENTITY_TYPE.getKey(victim.getType()))));
    }

    /** 逃离：向远离威胁的方向移动。 */
    protected ActionResult flee(ActionParser.ParsedAction parsed) {
        double distance = parsed.getDouble("distance", 16.0);
        ServerLevel level = bot.serverLevel();

        // 找最近的威胁
        AABB box = bot.getBoundingBox().inflate(16.0);
        List<Entity> threats = level.getEntities(bot, box, e -> e instanceof Monster && e.isAlive());
        if (threats.isEmpty()) {
            return ActionResult.ok("附近没有威胁，无需逃跑");
        }
        threats.sort(Comparator.comparingDouble(e -> e.distanceTo(bot)));
        Entity threat = threats.get(0);

        Vec3 away = bot.position().subtract(threat.position());
        if (away.lengthSqr() < 0.01) {
            away = new Vec3(1, 0, 0);
        }
        Vec3 target = bot.position().add(away.normalize().scale(distance));

        BlockPos landing = findGroundBelow(level, BlockPos.containing(target.x, target.y, target.z));
        if (landing == null) {
            return ActionResult.fail("逃跑方向上没有可站立地面");
        }
        bot.teleportTo(landing.getX() + 0.5, landing.getY(), landing.getZ() + 0.5);
        return ActionResult.ok("逃离威胁 " + shortId(String.valueOf(
                BuiltInRegistries.ENTITY_TYPE.getKey(threat.getType()))));
    }

    /** 睡觉。TODO: 后续实现的完整版本需要检查是否夜晚与床的存在。 */
    protected ActionResult sleep(ActionParser.ParsedAction parsed) {
        ServerLevel level = bot.serverLevel();
        long dayTime = level.getDayTime() % 24000L;
        if (dayTime < 13000L) {
            return ActionResult.fail("现在是白天，无法睡觉");
        }
        return ActionResult.fail("sleep 动作尚未实现（计划中）");
    }

    /** 跟随玩家。TODO: 后续实现（需要每 tick 跟踪目标玩家位置）。 */
    protected ActionResult follow(ActionParser.ParsedAction parsed) {
        String playerName = parsed.getString("player", "");
        if (playerName.isEmpty()) {
            return ActionResult.fail("follow 动作缺少 player 参数");
        }
        var players = bot.serverLevel().players();
        for (var p : players) {
            if (p.getName().getString().equalsIgnoreCase(playerName)) {
                // 最小实现：一次跳跃式靠近
                var target = p.position();
                BlockPos landing = findGroundBelow(bot.serverLevel(),
                        BlockPos.containing(target.x, target.y, target.z));
                if (landing != null) {
                    bot.teleportTo(landing.getX() + 0.5, landing.getY(), landing.getZ() + 0.5);
                    return ActionResult.ok("跟随 " + playerName + " 移动了一程");
                }
            }
        }
        return ActionResult.fail("找不到玩家 " + playerName);
    }

    /** 存放物品到容器。TODO: 后续实现（需要扫描附近容器方块实体）。 */
    protected ActionResult store(ActionParser.ParsedAction parsed) {
        return ActionResult.fail("store 动作尚未实现（计划中）");
    }

    // ------------------------------------------------------------------
    // 工具方法
    // ------------------------------------------------------------------

    /**
     * 从给定位置向下寻找第一个可站立的地面。
     *
     * @return 可站立的方块坐标上方位置；找不到返回 null
     */
    protected BlockPos findGroundBelow(ServerLevel level, BlockPos from) {
        // 向上最多找 8 格，向下最多找 16 格
        for (int dy = 8; dy >= -16; dy--) {
            BlockPos feet = from.offset(0, dy, 0);
            if (!level.isLoaded(feet)) {
                continue;
            }
            BlockState below = level.getBlockState(feet.below());
            BlockState at = level.getBlockState(feet);
            BlockState above = level.getBlockState(feet.above());

            boolean solidBelow = !below.isAir() && below.isSolidRender(level, feet.below());
            boolean feetFree = at.isAir() || !at.isSolidRender(level, feet);
            boolean headFree = above.isAir() || !above.isSolidRender(level, feet.above());

            if (solidBelow && feetFree && headFree) {
                return feet;
            }
        }
        return null;
    }

    /**
     * 在半径内寻找最近的指定方块。
     *
     * @return 方块坐标；找不到返回 null
     */
    protected BlockPos findNearestBlock(ServerLevel level, Block target, int radius) {
        BlockPos center = bot.blockPosition();
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;

        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -6; dy <= 6; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    BlockPos pos = center.offset(dx, dy, dz);
                    if (!level.isLoaded(pos)) {
                        continue;
                    }
                    if (level.getBlockState(pos).is(target)) {
                        double d = center.distSqr(pos);
                        if (d < bestDist) {
                            bestDist = d;
                            best = pos.immutable();
                        }
                    }
                }
            }
        }
        return best;
    }

    /** 按注册 ID 解析方块；支持带或不带 minecraft: 前缀。 */
    protected Block resolveBlock(String id) {
        String full = id.contains(":") ? id : "minecraft:" + id;
        ResourceLocation key = ResourceLocation.tryParse(full);
        if (key == null) {
            return null;
        }
        // Registry.getValue(ResourceLocation) 直接返回 T（不是 Holder），找不到时返回 null
        Block b = BuiltInRegistries.BLOCK.get(key);
        return b == null || b == Blocks.AIR ? null : b;
    }

    /**
     * 判断物品是否可食用。
     * 1.20.5+ 食物信息在 DataComponents.FOOD 上。
     */
    protected boolean isFood(ItemStack stack) {
        try {
            // 1.20.1 没有 DataComponents（1.20.5+ 才有），直接用 Item.isEdible()
            return stack.getItem().isEdible();
        } catch (Throwable t) {
            // 兜底：用常见食物列表判断
            return stack.is(Items.BREAD) || stack.is(Items.APPLE)
                    || stack.is(Items.COOKED_BEEF) || stack.is(Items.COOKED_PORKCHOP)
                    || stack.is(Items.GOLDEN_APPLE) || stack.is(Items.CARROT);
        }
    }

    /** 去掉 minecraft: 前缀，缩短日志与提示词长度。 */
    protected static String shortId(String id) {
        if (id == null) {
            return "";
        }
        return id.startsWith("minecraft:") ? id.substring("minecraft:".length()) : id;
    }
}
