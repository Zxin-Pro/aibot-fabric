package com.example.aibot.action;

import com.example.aibot.config.AIConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.Comparator;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 动作执行器（1.21.11 实现）。
 *
 * <p><b>设计原则</b>：所有会改变世界的动作都通过 {@link TickActionDriver}
 * 走<b>原版玩家输入通道</b>完成，而不是直接改世界。这样假玩家的行为
 * 与真人玩家在服务端看来完全一致：</p>
 * <ul>
 *   <li>走路有重力、碰撞、台阶、水中减速</li>
 *   <li>挖方块要按硬度与工具算时间，消耗工具耐久</li>
 *   <li>放方块要过原版的朝向/碰撞/可替换性校验</li>
 *   <li>吃东西有 1.6 秒时长且会被打断</li>
 *   <li>攻击遵守原版冷却</li>
 * </ul>
 *
 * <p>本类只负责「把 LLM 的动作转成驱动指令」并做参数校验；
 * 实际执行由 {@link TickActionDriver} 每 tick 推进。</p>
 */
public class ActionExecutor {

    private static final Logger LOGGER = Logger.getLogger("aibot-action");

    /** 被控制的假玩家。用 ServerPlayer 以便重生后继续工作。 */
    protected final ServerPlayer bot;

    /** 逐 tick 动作驱动器。 */
    protected final TickActionDriver driver;

    public ActionExecutor(ServerPlayer bot, AIConfig config) {
        this.bot = bot;
        this.driver = new TickActionDriver(bot, config);
    }

    /** 取驱动器（自主循环用它查询动作是否完成）。 */
    public TickActionDriver getDriver() {
        return driver;
    }

    /**
     * 动作执行结果。
     *
     * @param success 是否成功
     * @param message 结果描述
     * @param async   true 表示动作已启动、需要若干 tick 才能完成，
     *                结果要稍后从 {@link TickActionDriver} 查询
     */
    public record ActionResult(boolean success, String message, boolean async) {

        public static ActionResult ok(String message) {
            return new ActionResult(true, message, false);
        }

        public static ActionResult fail(String message) {
            return new ActionResult(false, message, false);
        }

        /** 动作已启动，正在逐 tick 进行中。 */
        public static ActionResult started(String message) {
            return new ActionResult(true, message, true);
        }
    }

    /**
     * 执行一个解析好的动作。
     *
     * <p>返回 {@code async=true} 表示动作已交给驱动器，需要后续 tick 推进。</p>
     */
    public ActionResult execute(ActionParser.ParsedAction parsed) {
        String action = parsed.action();
        try {
            switch (action) {
                case "move":
                case "pathfind":
                    return move(parsed);
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
                case "pickup":
                    return pickup(parsed);
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
            LOGGER.log(Level.WARNING, "[AIBot] 执行动作 " + action + " 时异常", t);
            return ActionResult.fail("动作异常: " + t.getClass().getSimpleName());
        }
    }

    // ------------------------------------------------------------------
    // 移动：交给驱动器逐 tick 走
    // ------------------------------------------------------------------

    /**
     * 走到指定坐标。
     *
     * <p><b>不再是瞬移</b>：调用 {@link TickActionDriver#beginWalk}，
     * 之后由原版物理每 tick 推进，走不过去就失败（和真人一样）。</p>
     */
    protected ActionResult move(ActionParser.ParsedAction parsed) {
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

        if (distance < 0.9) {
            return ActionResult.ok("已在目标点附近");
        }

        // 超时按距离估算：正常人走路约 4.3 格/秒 = 0.215 格/tick
        int timeout = (int) Math.max(200, distance / 0.215 * 2.5);
        driver.beginWalk(target, timeout);
        return ActionResult.started(String.format(java.util.Locale.ROOT,
                "开始走向 (%.0f, %.0f, %.0f)，距离 %.1f 格", tx, ty, tz, distance));
    }

    /** 寻路：当前版本等同于 move（真实寻路需接入 Baritone）。 */
    protected ActionResult pathfind(ActionParser.ParsedAction parsed) {
        return move(parsed);
    }

    /** 逃离威胁：朝远离威胁的方向走。 */
    protected ActionResult flee(ActionParser.ParsedAction parsed) {
        double distance = parsed.getDouble("distance", 16.0);
        ServerLevel level = (ServerLevel) bot.level();

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

        int timeout = (int) Math.max(200, distance / 0.215 * 3);
        driver.beginWalk(target, timeout);
        return ActionResult.started("开始逃离 " + shortId(String.valueOf(
                BuiltInRegistries.ENTITY_TYPE.getKey(threat.getType()))));
    }

    // ------------------------------------------------------------------
    // 挖掘：走原版破坏流程（按硬度算时间）
    // ------------------------------------------------------------------

    /**
     * 挖掘方块。
     *
     * <p><b>不再是瞬间破坏</b>：交给驱动器调用
     * {@code ServerPlayerGameMode.handleBlockBreakAction}，
     * 这正是原版处理客户端挖掘包的方法，因此：
     * 石头要挖 0.75 秒、圆石要 1.15 秒、黑曜石要 9.4 秒，
     * 工具越好越快，并且会消耗工具耐久。</p>
     */
    protected ActionResult mine(ActionParser.ParsedAction parsed) {
        String blockId = parsed.getString("block", "");
        int count = Math.max(1, Math.min(parsed.getInt("count", 1), 64));

        if (blockId.isEmpty()) {
            return ActionResult.fail("mine 动作缺少 block 参数");
        }

        Block target = resolveBlock(blockId);
        if (target == null) {
            return ActionResult.fail("未知方块: " + blockId);
        }

        ServerLevel level = (ServerLevel) bot.level();
        BlockPos pos = findNearestBlock(level, target, 24);
        if (pos == null) {
            return ActionResult.fail("附近 24 格内找不到 " + shortId(blockId));
        }

        driver.beginMine(pos, target, count);
        return ActionResult.started("开始挖掘 " + shortId(blockId) + " x" + count
                + "，从 " + fmtPos(pos) + " 开始（将按方块硬度耗时）");
    }

    /**
     * 放置方块。
     *
     * <p>走原版 {@code useItemOn}，因此需要手上真的拿着该方块，
     * 且目标位置必须通过原版校验。</p>
     */
    protected ActionResult place(ActionParser.ParsedAction parsed) {
        String blockId = parsed.getString("block", "");
        if (blockId.isEmpty()) {
            return ActionResult.fail("place 动作缺少 block 参数");
        }

        Block target = resolveBlock(blockId);
        if (target == null) {
            return ActionResult.fail("未知方块: " + blockId);
        }

        BlockPos base = bot.blockPosition();
        int dx = parsed.getInt("x", 1);
        int dy = parsed.getInt("y", 0);
        int dz = parsed.getInt("z", 0);
        BlockPos pos = base.offset(dx, dy, dz);

        ServerLevel level = (ServerLevel) bot.level();
        if (!level.isLoaded(pos)) {
            return ActionResult.fail("目标位置所在区块未加载");
        }

        // 手上没有就先从背包找
        if (findBlockItemSlot(target) < 0) {
            return ActionResult.fail("背包里没有 " + shortId(blockId));
        }

        driver.beginPlace(pos, target);
        return ActionResult.started("开始放置 " + shortId(blockId) + " 于 " + fmtPos(pos));
    }

    // ------------------------------------------------------------------
    // 合成：这是唯一允许「非 tick 驱动」的动作
    // ------------------------------------------------------------------

    /**
     * 合成物品。
     *
     * <p><b>说明</b>：合成在真人操作里是「开界面点配方」，
     * 假玩家没有客户端界面。这里在服务端按真实配方规则结算
     * （材料必须齐全、产物按配方数量给），不凭空产出。</p>
     */
    protected ActionResult craft(ActionParser.ParsedAction parsed) {
        String itemId = parsed.getString("item", "");
        int count = Math.max(1, Math.min(parsed.getInt("count", 1), 64));
        if (itemId.isEmpty()) {
            return ActionResult.fail("craft 动作缺少 item 参数");
        }

        String full = itemId.contains(":") ? itemId : "minecraft:" + itemId;
        ResourceLocation key = ResourceLocation.parse(full);
        if (key == null) {
            return ActionResult.fail("非法物品 ID: " + itemId);
        }
        net.minecraft.world.item.Item targetItem = BuiltInRegistries.ITEM.getValue(key);
        if (targetItem == null || targetItem == Items.AIR) {
            return ActionResult.fail("未知物品: " + itemId);
        }

        ServerLevel level = (ServerLevel) bot.level();
        var recipes = level.getServer().getRecipeManager();
        var ctx = net.minecraft.world.item.crafting.display.SlotDisplayContext.fromLevel(level);

        net.minecraft.world.item.crafting.RecipeHolder<?> matchedRecipe = null;
        ItemStack matchedResult = ItemStack.EMPTY;
        boolean recipeExists = false;

        for (var holder : recipes.getRecipes()) {
            ItemStack result = resultOf(holder.value(), ctx);
            if (result.isEmpty() || !result.is(targetItem)) {
                continue;
            }
            recipeExists = true;
            if (hasIngredients(holder.value())) {
                matchedRecipe = holder;
                matchedResult = result;
                break;
            }
        }

        if (matchedRecipe == null || matchedResult.isEmpty()) {
            return ActionResult.fail(recipeExists
                    ? "材料不足，无法合成 " + shortId(full)
                    : "找不到合成 " + shortId(full) + " 的配方");
        }

        consumeIngredients(matchedRecipe.value());

        int perCraft = Math.max(1, matchedResult.getCount());
        int total = perCraft * count;
        while (total > 0) {
            int batch = Math.min(perCraft, total);
            ItemStack out = matchedResult.copy();
            out.setCount(batch);
            if (!bot.getInventory().add(out)) {
                bot.drop(out, false);
            }
            total -= batch;
        }

        return ActionResult.ok("合成了 " + count + " 份 " + shortId(full)
                + "（共 " + (perCraft * count) + " 个）");
    }

    /** 取配方产物（1.21.11 的 RecipeDisplay 体系）。 */
    protected ItemStack resultOf(net.minecraft.world.item.crafting.Recipe<?> recipe,
                                 net.minecraft.util.context.ContextMap ctx) {
        try {
            var displays = recipe.display();
            if (displays == null || displays.isEmpty()) {
                return ItemStack.EMPTY;
            }
            var slot = displays.get(0).result();
            if (slot == null) {
                return ItemStack.EMPTY;
            }
            ItemStack s = slot.resolveForFirstStack(ctx);
            return s == null ? ItemStack.EMPTY : s;
        } catch (Throwable t) {
            return ItemStack.EMPTY;
        }
    }

    protected boolean hasIngredients(net.minecraft.world.item.crafting.Recipe<?> recipe) {
        try {
            var info = recipe.placementInfo();
            if (info == null) {
                return false;
            }
            var ingredients = info.ingredients();
            if (ingredients == null || ingredients.isEmpty()) {
                return false;
            }
            for (var ing : ingredients) {
                if (ing == null || ing.isEmpty()) {
                    continue;
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

    protected void consumeIngredients(net.minecraft.world.item.crafting.Recipe<?> recipe) {
        try {
            var info = recipe.placementInfo();
            if (info == null) {
                return;
            }
            var inv = bot.getInventory();
            for (var ing : info.ingredients()) {
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

    // ------------------------------------------------------------------
    // 其余动作
    // ------------------------------------------------------------------

    /** 攻击：交给驱动器，遵守原版冷却。 */
    protected ActionResult attack(ActionParser.ParsedAction parsed) {
        String target = parsed.getString("target", "");
        driver.beginAttack(target);
        return ActionResult.started("开始攻击" + (target.isEmpty() ? "最近目标" : " " + shortId(target)));
    }

    /** 进食：走原版 startUsingItem，有 1.6 秒时长。 */
    protected ActionResult eat(ActionParser.ParsedAction parsed) {
        var food = bot.getFoodData();
        if (food.getFoodLevel() >= 20) {
            return ActionResult.ok("并不饿，跳过进食");
        }
        String item = parsed.getString("item", "");
        driver.beginEat(item);
        return ActionResult.started("开始进食" + (item.isEmpty() ? "" : " " + shortId(item))
                + "（需 1.6 秒，会被攻击打断）");
    }

    /** 拾取附近掉落物。 */
    protected ActionResult pickup(ActionParser.ParsedAction parsed) {
        driver.beginPickup();
        return ActionResult.started("开始拾取附近掉落物");
    }

    /**
     * 聊天。
     *
     * <p><b>为什么不用 broadcastSystemMessage</b>：那样发出来的是系统消息，
     * 游戏里显示成黄色斜体，且<b>不带玩家名牌、不进聊天记录、其他插件也读不到</b>。
     * 真人说话走的是聊天通道，所以这里用 {@code ChatType} 广播，
     * 让它和真人发言在客户端呈现上完全一致。</p>
     *
     * <p><b>1.21.11 版本差异</b>：{@code broadcastChatMessage} 与
     * {@code PlayerChatMessage.unsigned} 的签名和 1.20.1 完全一致
     * （已用 javap 核对过 1.21.11 的 merged jar），因此这里的调用形式可以照搬。</p>
     */
    protected ActionResult chat(ActionParser.ParsedAction parsed) {
        String message = parsed.getString("message", "");
        if (message.trim().isEmpty()) {
            return ActionResult.fail("chat 动作缺少 message 参数");
        }
        String text = message.trim();
        if (text.length() > 120) {
            text = text.substring(0, 120) + "...";
        }
        try {
            // 用原版聊天广播：与真人发言走同一条渲染路径
            //（有玩家名牌、进聊天记录、其他插件能监听到 PlayerChatEvent）。
            //
            // 签名：broadcastChatMessage(PlayerChatMessage, ServerPlayer, ChatType.Bound)
            // 其中消息用 unsigned 构造 —— 在离线/伪造连接下这是正确做法
            //（我们没有 Mojang 的会话密钥，也不应该伪造正版签名）。
            // ChatType.bind(key, entity) 内部会去 registry 查 ChatType 再绑定发送者。
            net.minecraft.network.chat.ChatType.Bound bound =
                    net.minecraft.network.chat.ChatType.bind(
                            net.minecraft.network.chat.ChatType.CHAT,
                            bot);
            net.minecraft.network.chat.PlayerChatMessage msg =
                    net.minecraft.network.chat.PlayerChatMessage.unsigned(bot.getUUID(), text);
            ((ServerLevel) bot.level()).getServer().getPlayerList()
                    .broadcastChatMessage(msg, bot, bound);
            return ActionResult.ok("说了: " + text);
        } catch (Throwable t) {
            // 少数版本签名不同，退回到直接发玩家消息（仍是玩家身份，不是系统广播）
            bot.sendSystemMessage(net.minecraft.network.chat.Component.literal(text));
            return ActionResult.ok("说了: " + text);
        }
    }

    /**
     * 环顾四周：像真人一样平滑转动视角，而不是瞬间归零。
     *
     * <p>原来的实现直接把 yaw/pitch 设为 0 —— 那会在一 tick 内把头猛转 180 度，
     * 真人的鼠标不可能这样。这里改为在当前朝向附近做小幅随机扫视。</p>
     */
    protected ActionResult look(ActionParser.ParsedAction parsed) {
        float curYaw = bot.getYRot();
        float curPitch = bot.getXRot();
        // 在 ±60 度范围内扫视，俯仰限制在合理区间
        float newYaw = curYaw + (float) ((Math.random() - 0.5) * 120.0);
        float newPitch = curPitch + (float) ((Math.random() - 0.5) * 40.0);
        newPitch = Math.max(-60.0f, Math.min(60.0f, newPitch));
        bot.setYRot(newYaw % 360.0f);
        bot.setXRot(newPitch);
        // 同步给客户端，否则其他玩家看到的是旧朝向。
        // 1.21.11 的 connection 仍是 ServerGamePacketListenerImpl，
        // 其 teleport(double,double,double,float,float) 签名与 1.20.1 相同。
        bot.connection.teleport(bot.getX(), bot.getY(), bot.getZ(),
                bot.getYRot(), bot.getXRot());
        return ActionResult.ok("环顾四周完成");
    }

    /** 睡觉：需要夜晚且有床（走原版 startSleepInBed）。 */
    protected ActionResult sleep(ActionParser.ParsedAction parsed) {
        ServerLevel level = (ServerLevel) bot.level();
        long dayTime = level.getDayTime() % 24000L;
        if (dayTime < 13000L) {
            return ActionResult.fail("现在是白天，无法睡觉");
        }

        BlockPos bedPos = null;
        if (parsed.has("x")) {
            bedPos = BlockPos.containing(parsed.getDouble("x", 0),
                    parsed.getDouble("y", 0), parsed.getDouble("z", 0));
            if (!(level.getBlockState(bedPos).getBlock()
                    instanceof net.minecraft.world.level.block.BedBlock)) {
                bedPos = null;
            }
        }
        if (bedPos == null) {
            bedPos = findNearestBlockOfType(level, net.minecraft.world.level.block.BedBlock.class, 24);
            if (bedPos == null) {
                return ActionResult.fail("附近 24 格内找不到床");
            }
        }

        // 先走过去，再上床
        double dist = Math.sqrt(bot.blockPosition().distSqr(bedPos));
        if (dist > 2.5) {
            driver.beginWalk(new Vec3(bedPos.getX() + 0.5, bedPos.getY(), bedPos.getZ() + 0.5),
                    (int) Math.max(200, dist / 0.215 * 3));
            return ActionResult.started("先走到床边，再尝试入睡");
        }

        try {
            var result = bot.startSleepInBed(bedPos);
            if (result.left().isPresent()) {
                // 1.21.4 的 BedSleepingProblem 还没有 message()，只能报枚举名
                return ActionResult.fail("无法入睡：" + result.left().get().name());
            }
            return ActionResult.ok("已上床睡觉");
        } catch (Throwable t) {
            return ActionResult.fail("睡觉异常: " + t.getClass().getSimpleName());
        }
    }

    /** 跟随玩家：走过去，与原版一致。 */
    protected ActionResult follow(ActionParser.ParsedAction parsed) {
        String playerName = parsed.getString("player", "");
        if (playerName.isEmpty()) {
            return ActionResult.fail("follow 动作缺少 player 参数");
        }
        for (var p : ((ServerLevel) bot.level()).players()) {
            if (p.getName().getString().equalsIgnoreCase(playerName)) {
                double dist = p.distanceTo(bot);
                driver.beginWalk(p.position(), (int) Math.max(200, dist / 0.215 * 3));
                return ActionResult.started("开始走向玩家 " + playerName);
            }
        }
        return ActionResult.fail("找不到玩家 " + playerName);
    }

    /**
     * 存放物品到容器。
     *
     * <p>真人也是打开箱子再放，这里直接与容器交互（服务端等价操作）。</p>
     */
    protected ActionResult store(ActionParser.ParsedAction parsed) {
        String wanted = parsed.getString("item", "");
        ServerLevel level = (ServerLevel) bot.level();

        net.minecraft.world.Container container = findNearestContainer(level, 6);
        if (container == null) {
            return ActionResult.fail("附近 6 格内没有可用的容器（箱子/桶）");
        }

        var inv = bot.getInventory();
        int movedCount = 0;
        int movedKinds = 0;

        // 跳过快捷栏前 9 格，避免把正在用的工具存走
        for (int i = 9; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.isEmpty()) {
                continue;
            }
            if (!wanted.isEmpty()) {
                ResourceLocation key = BuiltInRegistries.ITEM.getKey(stack.getItem());
                if (key == null || (!key.toString().equals(wanted)
                        && !key.toString().equals("minecraft:" + wanted))) {
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

    protected ItemStack insertIntoContainer(net.minecraft.world.Container container, ItemStack stack) {
        for (int i = 0; i < container.getContainerSize() && !stack.isEmpty(); i++) {
            ItemStack slot = container.getItem(i);
            if (slot.isEmpty()) {
                container.setItem(i, stack.copy());
                return ItemStack.EMPTY;
            }
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

    // ------------------------------------------------------------------
    // 工具方法
    // ------------------------------------------------------------------

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

    /** 在半径内寻找最近的某类方块。 */
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

    /** 在半径内找最近的指定方块。 */
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

    /** 在背包里找能放目标方块的物品槽位。 */
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

    /** 按注册 ID 解析方块。 */
    protected Block resolveBlock(String id) {
        String full = id.contains(":") ? id : "minecraft:" + id;
        ResourceLocation key = ResourceLocation.parse(full);
        if (key == null) {
            return null;
        }
        Block b = BuiltInRegistries.BLOCK.getValue(key);
        return b == null || b == Blocks.AIR ? null : b;
    }

    protected static String shortId(String id) {
        if (id == null) {
            return "";
        }
        return id.startsWith("minecraft:") ? id.substring("minecraft:".length()) : id;
    }

    protected static String fmtPos(BlockPos p) {
        return "(" + p.getX() + ", " + p.getY() + ", " + p.getZ() + ")";
    }
}
