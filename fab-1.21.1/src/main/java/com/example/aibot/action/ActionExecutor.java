package com.example.aibot.action;

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

    /**
     * 被控制的假玩家。
     *
     * <p><b>为什么是 ServerPlayer 而不是 AIBotPlayer</b>：
     * 原版 {@code PlayerList.respawn(...)} 内部会 {@code new ServerPlayer(...)}，
     * 死亡重生后我们的 {@link AIBotPlayer} 子类实例会被替换成普通 {@code ServerPlayer}。
     * 用父类型声明可以在重生后继续正常驱动，无需另写一套执行器
     * （本类用到的全部方法都定义在 ServerPlayer/Entity/Player 上）。</p>
     */
    protected final net.minecraft.server.level.ServerPlayer bot;

    public ActionExecutor(net.minecraft.server.level.ServerPlayer bot) {
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
    // 建造与合成（长期自主运行的必备能力）
    // ------------------------------------------------------------------

    /**
     * 放置方块。
     *
     * <p>实现方式：从背包找到对应方块物品 → 在目标位置直接写入方块状态并扣掉一个物品。
     * 不走 {@code BlockItem.useOn} 的上下文流程，因为假玩家没有真正的"手持交互"，
     * 直接写世界更可靠、也更容易预测。</p>
     *
     * <p>坐标缺省时放在假玩家脚下前方一格。</p>
     */
    protected ActionResult place(ActionParser.ParsedAction parsed) {
        String blockId = parsed.getString("block", "");
        if (blockId.isEmpty()) {
            return ActionResult.fail("place 动作缺少 block 参数");
        }

        ServerLevel level = bot.serverLevel();
        Block target = resolveBlock(blockId);
        if (target == null) {
            return ActionResult.fail("未知方块: " + blockId);
        }

        // 目标坐标：缺省为玩家前方一格
        BlockPos base = bot.blockPosition();
        int dx = parsed.getInt("x", 1);
        int dy = parsed.getInt("y", 0);
        int dz = parsed.getInt("z", 0);
        BlockPos pos = base.offset(dx, dy, dz);

        if (!level.isLoaded(pos)) {
            return ActionResult.fail("目标位置所在区块未加载");
        }
        // 不覆盖已有实体方块（避免把建筑挖穿）
        BlockState existing = level.getBlockState(pos);
        if (!existing.isAir() && existing.isSolidRender(level, pos)) {
            return ActionResult.fail("目标位置已被 " + shortId(blockIdOf(existing)) + " 占据");
        }
        // 从背包找这个方块物品
        int slot = findBlockItemSlot(target);
        if (slot < 0) {
            // 创造模式兜底：没有物品也允许放置
            if (bot.isCreative()) {
                level.setBlockAndUpdate(pos, target.defaultBlockState());
                return ActionResult.ok("已在 (" + pos.getX() + "," + pos.getY() + "," + pos.getZ()
                        + ") 放置 " + shortId(blockId) + "（创造模式）");
            }
            return ActionResult.fail("背包里没有 " + shortId(blockId));
        }

        // 写入方块并扣物品
        boolean placed = level.setBlockAndUpdate(pos, target.defaultBlockState());
        if (!placed) {
            return ActionResult.fail("放置失败（位置不合法）");
        }
        bot.getInventory().removeItem(slot, 1);

        return ActionResult.ok("已在 (" + pos.getX() + "," + pos.getY() + "," + pos.getZ()
                + ") 放置 " + shortId(blockId));
    }

    /**
     * 合成物品。
     *
     * <p><b>1.21.11 的配方 API 与旧版完全不同</b>（这是本版本最需要留意的改动之一）：
     * <ul>
     *   <li>旧版：{@code recipe.getResultItem(registryAccess)} / {@code recipe.getIngredients()}</li>
     *   <li>1.21.11：结果要通过 {@code recipe.display()} 拿到 {@code RecipeDisplay}，
     *       再 {@code display.result().resolveForFirstStack(...)}；
     *       材料要从 {@code recipe.placementInfo().ingredients()} 取。</li>
     * </ul>
     * 这里按新 API 实现。这样可以避开原版合成界面（假玩家没有界面），
     * 同时仍然尊重真实配方——材料不够就是合成不了。</p>
     */
    protected ActionResult craft(ActionParser.ParsedAction parsed) {
        String itemId = parsed.getString("item", "");
        int count = Math.max(1, Math.min(parsed.getInt("count", 1), 64));
        if (itemId.isEmpty()) {
            return ActionResult.fail("craft 动作缺少 item 参数");
        }

        String full = itemId.contains(":") ? itemId : "minecraft:" + itemId;
        ResourceLocation key = ResourceLocation.tryParse(full);
        if (key == null) {
            return ActionResult.fail("非法物品 ID: " + itemId);
        }
        // Registry.getValue(ResourceLocation) 直接返回 T，比 get() 的 Optional<Holder> 更方便
        net.minecraft.world.item.Item targetItem = BuiltInRegistries.ITEM.get(key);
        if (targetItem == null || targetItem == Items.AIR) {
            return ActionResult.fail("未知物品: " + itemId);
        }

        ServerLevel level = bot.serverLevel();
        var recipes = level.getServer().getRecipeManager();
        // 1.21.1 没有 RecipeDisplay 体系，直接用 getResultItem(registryAccess)

        net.minecraft.world.item.crafting.RecipeHolder<?> matchedRecipe = null;
        ItemStack matchedResult = ItemStack.EMPTY;
        boolean recipeExists = false;

        for (var holder : recipes.getRecipes()) {
            ItemStack result = holder.value().getResultItem(level.registryAccess());
            if (result.isEmpty() || !result.is(targetItem)) {
                continue;
            }
            recipeExists = true;
            // 找到配方后还要确认材料齐全
            if (hasIngredients(holder.value())) {
                matchedRecipe = holder;
                matchedResult = result;
                break;
            }
        }

        if (matchedRecipe == null || matchedResult.isEmpty()) {
            return ActionResult.fail(recipeExists
                    ? "材料不足，无法合成 " + shortId(full)
                    : "找不到合成 " + shortId(full) + " 的配方（可能需要工作台或特殊结构）");
        }

        // 消耗材料
        consumeIngredients(matchedRecipe.value());

        // 放入产物
        int perCraft = Math.max(1, matchedResult.getCount());
        int total = perCraft * count;
        while (total > 0) {
            int batch = Math.min(perCraft, total);
            ItemStack out = matchedResult.copy();
            out.setCount(batch);
            if (!bot.getInventory().add(out)) {
                // 背包满：掉落在地上
                bot.drop(out, false);
            }
            total -= batch;
        }

        return ActionResult.ok("合成了 " + count + " 份 " + shortId(full)
                + "（共 " + (perCraft * count) + " 个）");
    }

    /** 检查玩家背包是否满足某配方的全部材料。 */
    protected boolean hasIngredients(net.minecraft.world.item.crafting.Recipe<?> recipe) {
        try {
            // 1.21.1 用 getIngredients()（1.21.11 起改为 placementInfo().ingredients()）
            var ingredients = recipe.getIngredients();
            if (ingredients == null || ingredients.isEmpty()) {
                return false;
            }
            for (var ing : ingredients) {
                if (ing == null || ing.isEmpty()) {
                    continue; // 空槽位在有序配方中是允许的
                }
                if (!hasIngredient(ing)) {
                    return false;
                }
            }
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 背包里是否有某个 Ingredient 需要的物品。 */
    protected boolean hasIngredient(net.minecraft.world.item.crafting.Ingredient ing) {
        var inv = bot.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (!s.isEmpty() && ing.test(s)) {
                return true;
            }
        }
        return false;
    }

    /** 消耗一次配方所需的材料。 */
    protected void consumeIngredients(net.minecraft.world.item.crafting.Recipe<?> recipe) {
        try {
            // 1.21.1 用 getIngredients()
            var inv = bot.getInventory();
            for (var ing : recipe.getIngredients()) {
                if (ing == null || ing.isEmpty()) {
                    continue;
                }
                for (int i = 0; i < inv.getContainerSize(); i++) {
                    ItemStack s = inv.getItem(i);
                    if (!s.isEmpty() && ing.test(s)) {
                        s.shrink(1);
                        break;
                    }
                }
            }
        } catch (Throwable t) {
            LOGGER.log(Level.WARNING, "[AIBot] 消耗合成材料时异常", t);
        }
    }

    /** 在背包中查找能放置出指定方块的物品槽位。 */
    protected int findBlockItemSlot(Block block) {
        var inv = bot.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (s.isEmpty()) {
                continue;
            }
            if (s.getItem() instanceof net.minecraft.world.item.BlockItem bi && bi.getBlock() == block) {
                return i;
            }
        }
        return -1;
    }

    /** 攻击目标。 */
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

    /**
     * 睡觉。
     *
     * <p>实现：夜晚时寻找最近的床并让假玩家上床。
     * 找不到床时返回失败，让 LLM 去造一张床（羊毛 + 木板）。</p>
     */
    protected ActionResult sleep(ActionParser.ParsedAction parsed) {
        ServerLevel level = bot.serverLevel();

        // 坐标可选：给了就用给的，没给就找最近的床
        BlockPos bedPos = null;
        if (parsed.has("x")) {
            bedPos = BlockPos.containing(parsed.getDouble("x", 0),
                    parsed.getDouble("y", 0), parsed.getDouble("z", 0));
            if (!(level.getBlockState(bedPos).getBlock() instanceof net.minecraft.world.level.block.BedBlock)) {
                bedPos = null; // 指定位置不是床，退化为自动寻找
            }
        }
        if (bedPos == null) {
            BlockPos found = findNearestBlockOfType(level, net.minecraft.world.level.block.BedBlock.class, 24);
            if (found == null) {
                return ActionResult.fail("附近 24 格内找不到床");
            }
            bedPos = found;
        }

        // 走到床边
        double dist = Math.sqrt(bot.blockPosition().distSqr(bedPos));
        if (dist > 3.0) {
            BlockPos stand = findGroundBelow(level, bedPos);
            if (stand != null) {
                bot.teleportTo(stand.getX() + 0.5, stand.getY(), stand.getZ() + 0.5);
            }
        }

        // 真正上床（原版方法会处理昼夜与怪物判定）
        try {
            var result = bot.startSleepInBed(bedPos);
            if (result.left().isPresent()) {
                // BedSleepingProblem 在 1.21.11 是 record，用 message() 取可读文本
                return ActionResult.fail("无法入睡：" + result.left().get().name());
            }
            return ActionResult.ok("已上床睡觉");
        } catch (Throwable t) {
            LOGGER.log(Level.WARNING, "[AIBot] 睡觉失败", t);
            return ActionResult.fail("睡觉异常: " + t.getClass().getSimpleName());
        }
    }

    /**
     * 把背包物品存入附近容器（箱子、桶等）。
     *
     * <p>实现：扫描附近 8 格内的方块实体，找到实现了 {@code Container} 的容器，
     * 把指定物品（或全部可存物品）转移进去。这是"整理背包"和建立基地仓储的关键。</p>
     */
    protected ActionResult store(ActionParser.ParsedAction parsed) {
        String wanted = parsed.getString("item", "");
        ServerLevel level = bot.serverLevel();

        // 1. 找最近的容器
        net.minecraft.world.Container container = findNearestContainer(level, 8);
        if (container == null) {
            return ActionResult.fail("附近 8 格内没有可用的容器（箱子/桶）");
        }

        var inv = bot.getInventory();
        int movedCount = 0;
        int movedKinds = 0;

        // 2. 逐个槽位转移
        // 注意：跳过快捷栏前 9 格，避免把正在用的工具存走
        for (int i = 9; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.isEmpty()) {
                continue;
            }
            // 指定了物品就精确匹配
            if (!wanted.isEmpty()) {
                ResourceLocation key = BuiltInRegistries.ITEM.getKey(stack.getItem());
                if (key == null || !key.toString().equals(wanted)
                        && !key.toString().equals("minecraft:" + wanted)) {
                    continue;
                }
            }

            int before = stack.getCount();
            ItemStack remaining = insertIntoContainer(container, stack);
            int moved = before - remaining.getCount();
            if (moved > 0) {
                movedCount += moved;
                movedKinds++;
                inv.setItem(i, remaining);
            }
        }

        if (movedCount == 0) {
            return ActionResult.fail(wanted.isEmpty()
                    ? "容器已满或没有可存入的物品"
                    : "没有可存入的 " + shortId(wanted));
        }
        container.setChanged();
        return ActionResult.ok("存入 " + movedKinds + " 种物品，共 " + movedCount + " 个");
    }

    /**
     * 把物品堆尽量塞进容器。
     *
     * @return 没塞进去的剩余部分（全部塞入时返回空堆）
     */
    protected ItemStack insertIntoContainer(net.minecraft.world.Container container, ItemStack stack) {
        for (int i = 0; i < container.getContainerSize() && !stack.isEmpty(); i++) {
            ItemStack slot = container.getItem(i);
            // 空槽：直接放
            if (slot.isEmpty()) {
                container.setItem(i, stack.copy());
                return ItemStack.EMPTY;
            }
            // 同类物品：合并
            if (ItemStack.isSameItemSameComponents(slot, stack)) {
                int max = Math.min(container.getMaxStackSize(), slot.getMaxStackSize());
                int space = max - slot.getCount();
                if (space > 0) {
                    int move = Math.min(space, stack.getCount());
                    slot.grow(move);
                    stack.shrink(move);
                    container.setItem(i, slot);
                }
            }
        }
        return stack;
    }

    /** 找最近的实现了 Container 的方块实体。 */
    protected net.minecraft.world.Container findNearestContainer(ServerLevel level, int radius) {
        BlockPos center = bot.blockPosition();
        net.minecraft.world.Container best = null;
        double bestDist = Double.MAX_VALUE;

        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -4; dy <= 4; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    BlockPos pos = center.offset(dx, dy, dz);
                    if (!level.isLoaded(pos)) {
                        continue;
                    }
                    var be = level.getBlockEntity(pos);
                    if (be instanceof net.minecraft.world.Container c) {
                        double d = center.distSqr(pos);
                        if (d < bestDist) {
                            bestDist = d;
                            best = c;
                        }
                    }
                }
            }
        }
        return best;
    }

    /** 在半径内寻找最近的指定类型的方块（如床）。 */
    protected BlockPos findNearestBlockOfType(ServerLevel level, Class<?> blockClass, int radius) {
        BlockPos center = bot.blockPosition();
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;

        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -5; dy <= 5; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    BlockPos pos = center.offset(dx, dy, dz);
                    if (!level.isLoaded(pos)) {
                        continue;
                    }
                    if (blockClass.isInstance(level.getBlockState(pos).getBlock())) {
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
            return stack.has(net.minecraft.core.component.DataComponents.FOOD);
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

    /** 取方块状态的注册 ID 字符串。 */
    protected static String blockIdOf(BlockState state) {
        ResourceLocation key = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        return key == null ? "unknown" : key.toString();
    }
}
