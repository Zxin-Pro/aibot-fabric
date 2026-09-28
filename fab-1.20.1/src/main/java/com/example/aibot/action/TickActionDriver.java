package com.example.aibot.action;

import com.example.aibot.config.AIConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.Comparator;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 逐 tick 动作驱动器：把「动作」变成像真人一样、跨多个 tick 逐步完成的过程。
 *
 * <p><b>设计动机</b>：真正的玩家挖一个石头要按硬度算时间、走路要受碰撞和重力约束、
 * 吃东西有 1.6 秒动画且会被打断。之前的实现直接调 {@code destroyBlock} / {@code teleportTo}
 * 瞬间改世界，那是作弊，不是玩家。</p>
 *
 * <p><b>本类做的事</b>：每个服务器 tick 推进一次「当前正在进行的动作」，
 * 通过<b>原版玩家输入通道</b>来驱动：</p>
 * <ul>
 *   <li>移动：设置 {@code zza}/{@code xxa}/{@code jumping}，交给原版 {@code travel()} 处理
 *       —— 因此有重力、碰撞、台阶、水中减速，和真人完全一致</li>
 *   <li>挖掘：调用 {@code gameMode.handleBlockBreakAction(...)}，
 *       这正是原版处理客户端挖掘包的方法，因此<b>硬度、工具、效率附魔全部自动生效</b></li>
 *   <li>放置：调用 {@code gameMode.useItemOn(...)}，走原版放置校验</li>
 *   <li>进食：调用 {@code startUsingItem(hand)}，然后由原版 tick 在 1.6 秒后自动结算</li>
 *   <li>攻击：遵守原版攻击冷却（{@code getAttackStrengthScale}）</li>
 * </ul>
 *
 * <p>使用方式：{@code beginXxx()} 发起动作，之后每 tick 调用 {@link #tick()}，
 * 完成或失败时通过 {@link #isDone()} / {@link #result()} 查询。</p>
 */
public final class TickActionDriver {

    private static final Logger LOGGER = Logger.getLogger("aibot-tick-action");

    /** 走路时的前进输入强度（1.0 = 真人按住 W 不放）。 */
    private static final float MOVE_FORWARD = 1.0f;

    /** 判定「已到达目标」的水平距离。 */
    private static final double ARRIVE_H_DISTANCE = 0.9;

    /** 判定「已到达目标」的垂直容差。 */
    private static final double ARRIVE_V_TOLERANCE = 1.5;

    /** 单个动作最多持续多少 tick，超过判定失败（防止永久卡住）。 */
    private static final int DEFAULT_TIMEOUT_TICKS = 600;

    /**
     * 破坏方块包里的光照参数。
     *
     * <p>该参数只用于客户端显示破坏动画进度，服务端破坏逻辑并不使用它，
     * 因此这里用固定值即可 —— 也避免了各版本亮度 API 名称不一致的问题。</p>
     */
    private static final int BLOCK_BREAK_LIGHT = 0;

    /** 正在进行的动作类型。 */
    public enum Kind {
        NONE,
        /** 走到坐标 */
        WALK,
        /** 挖方块 */
        MINE,
        /** 放置方块 */
        PLACE,
        /** 进食 */
        EAT,
        /** 攻击实体 */
        ATTACK,
        /** 拾取附近掉落物 */
        PICKUP
    }

    /** 动作终态。 */
    public enum Status {
        /** 还在进行中 */
        RUNNING,
        /** 成功完成 */
        SUCCESS,
        /** 失败（含超时） */
        FAILED
    }

    private final ServerPlayer bot;
    private final AIConfig config;

    // ---- 当前动作状态 ----
    private Kind kind = Kind.NONE;
    private Status status = Status.RUNNING;
    private String message = "";
    private int elapsedTicks = 0;
    private int timeoutTicks = DEFAULT_TIMEOUT_TICKS;

    // 走路的参数
    private Vec3 walkTarget = null;

    // 挖掘的参数
    private BlockPos mineTarget = null;
    private Block targetBlock = null;
    private int mineCount = 1;
    private int minedSoFar = 0;
    /** 是否已经对当前方块发过 START_DESTROY_BLOCK。 */
    private boolean breakingStarted = false;
    private int breakSeq = 0;

    // 放置的参数
    private BlockPos placePos = null;
    private Block placeBlock = null;

    // 进食的参数
    private String eatItemFilter = "";

    // 攻击的参数
    private String attackTargetType = "";

    public TickActionDriver(ServerPlayer bot, AIConfig config) {
        this.bot = bot;
        this.config = config;
    }

    // ------------------------------------------------------------------
    // 查询
    // ------------------------------------------------------------------

    public Kind getKind() {
        return kind;
    }

    public Status getStatus() {
        return status;
    }

    public String getMessage() {
        return message;
    }

    public boolean isRunning() {
        return kind != Kind.NONE && status == Status.RUNNING;
    }

    public boolean isDone() {
        return status != Status.RUNNING;
    }

    /** 动作结束原因文本。 */
    public String result() {
        return message;
    }

    public boolean succeeded() {
        return status == Status.SUCCESS;
    }

    /** 动作已进行的 tick 数。 */
    public int getElapsedTicks() {
        return elapsedTicks;
    }

    /** 取消当前动作（不发包、不动世界）。 */
    public void cancel() {
        clearMovementInput();
        if (kind == Kind.MINE && breakingStarted && mineTarget != null) {
            abortBreaking();
        }
        if (kind == Kind.EAT && bot.isUsingItem()) {
            bot.stopUsingItem();
        }
        kind = Kind.NONE;
        status = Status.RUNNING;
        message = "";
        elapsedTicks = 0;
        breakingStarted = false;
    }

    // ------------------------------------------------------------------
    // 动作发起
    // ------------------------------------------------------------------

    /**
     * 发起「走到坐标」动作。
     *
     * <p>真正的行走：每 tick 朝目标方向设置输入，由原版物理推进。
     * 不需要寻路也能走平地；被墙挡住会持续顶墙直到超时失败。</p>
     */
    public void beginWalk(Vec3 target, int timeout) {
        cancel();
        this.kind = Kind.WALK;
        this.walkTarget = target;
        this.status = Status.RUNNING;
        this.message = "";
        this.elapsedTicks = 0;
        this.timeoutTicks = timeout > 0 ? timeout : DEFAULT_TIMEOUT_TICKS;
    }

    /**
     * 发起「挖掘方块」动作。
     *
     * @param pos   目标方块坐标
     * @param block 目标方块类型
     * @param count 需要挖的数量（会依次寻找最近的同类方块）
     */
    public void beginMine(BlockPos pos, Block block, int count) {
        cancel();
        this.kind = Kind.MINE;
        this.mineTarget = pos;
        this.targetBlock = block;
        this.mineCount = Math.max(1, count);
        this.minedSoFar = 0;
        this.breakingStarted = false;
        this.status = Status.RUNNING;
        this.message = "";
        this.elapsedTicks = 0;
        this.timeoutTicks = Math.max(DEFAULT_TIMEOUT_TICKS, 200 * this.mineCount);
    }

    /** 发起「放置方块」动作。 */
    public void beginPlace(BlockPos pos, Block block) {
        cancel();
        this.kind = Kind.PLACE;
        this.placePos = pos;
        this.placeBlock = block;
        this.status = Status.RUNNING;
        this.message = "";
        this.elapsedTicks = 0;
        this.timeoutTicks = 40;
    }

    /** 发起「进食」动作。 */
    public void beginEat(String itemFilter) {
        cancel();
        this.kind = Kind.EAT;
        this.eatItemFilter = itemFilter == null ? "" : itemFilter;
        this.status = Status.RUNNING;
        this.message = "";
        this.elapsedTicks = 0;
        // 吃满一组最多也就几秒，给足余量
        this.timeoutTicks = 400;
    }

    /** 发起「攻击」动作。 */
    public void beginAttack(String targetType) {
        cancel();
        this.kind = Kind.ATTACK;
        this.attackTargetType = targetType == null ? "" : targetType;
        this.status = Status.RUNNING;
        this.message = "";
        this.elapsedTicks = 0;
        this.timeoutTicks = 200;
    }

    /** 发起「拾取附近掉落物」动作。 */
    public void beginPickup() {
        cancel();
        this.kind = Kind.PICKUP;
        this.status = Status.RUNNING;
        this.message = "";
        this.elapsedTicks = 0;
        this.timeoutTicks = 200;
    }

    // ------------------------------------------------------------------
    // 每 tick 推进
    // ------------------------------------------------------------------

    /**
     * 每个服务器 tick 调用一次，推进当前动作。
     *
     * <p>必须在玩家实体 tick <b>之前</b>调用（本模组用 START_SERVER_TICK），
     * 这样设置好的输入能在同一 tick 内被原版物理消费。</p>
     */
    public void tick() {
        if (!isRunning()) {
            return;
        }

        elapsedTicks++;
        if (elapsedTicks > timeoutTicks) {
            fail("动作超时（" + timeoutTicks + " tick 内未完成）");
            return;
        }

        try {
            switch (kind) {
                case WALK -> tickWalk();
                case MINE -> tickMine();
                case PLACE -> tickPlace();
                case EAT -> tickEat();
                case ATTACK -> tickAttack();
                case PICKUP -> tickPickup();
                default -> {
                }
            }
        } catch (Throwable t) {
            LOGGER.log(Level.WARNING, "[AIBot] 动作推进异常: " + kind, t);
            fail("动作异常: " + t.getClass().getSimpleName());
        }
    }

    // ------------------------------------------------------------------
    // 走路：用原版输入驱动
    // ------------------------------------------------------------------

    private void tickWalk() {
        if (walkTarget == null) {
            fail("没有行走目标");
            return;
        }

        ServerLevel level = bot.serverLevel();
        Vec3 pos = bot.position();

        double dx = walkTarget.x - pos.x;
        double dz = walkTarget.z - pos.z;
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        double dy = walkTarget.y - pos.y;

        // 到达判定
        if (horizontal < ARRIVE_H_DISTANCE && Math.abs(dy) < ARRIVE_V_TOLERANCE) {
            clearMovementInput();
            succeed("已走到 (" + fmt(walkTarget.x) + ", " + fmt(walkTarget.y) + ", "
                    + fmt(walkTarget.z) + ")，用时 " + elapsedTicks + " tick");
            return;
        }

        // 转向目标（真人也是先转头再走）
        float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        bot.setYRot(yaw);
        bot.yRotO = yaw;
        bot.setYHeadRot(yaw);

        // 设置前进输入 —— 等价于真人按住 W
        bot.zza = MOVE_FORWARD;
        bot.xxa = 0.0f;

        // 前方有障碍或需要上台阶时跳跃（真人手动按空格）
        if (shouldJump(level)) {
            bot.setJumping(true);
        } else {
            bot.setJumping(false);
        }

        // 卡住检测：连续多 tick 位移极小，说明被挡住了
        if (elapsedTicks > 40 && isStuckInPlace()) {
            clearMovementInput();
            fail("被障碍挡住，无法继续前进（可尝试绕行或先挖开）");
        }
    }

    /** 上一 tick 的位置，用于卡住检测。 */
    private Vec3 lastPos = null;

    /** 判断这一 tick 是否几乎没移动。 */
    private boolean isStuckInPlace() {
        Vec3 now = bot.position();
        if (lastPos == null) {
            lastPos = now;
            return false;
        }
        double moved = now.distanceTo(lastPos);
        lastPos = now;
        // 每 tick 正常走路至少移动 0.05 格；低于这个值视为被挡
        return moved < 0.02;
    }

    /**
     * 判断是否需要跳跃。
     *
     * <p>真人遇到一格高的台阶会按空格。这里检测前方一格是否有阻挡、
     * 且其上方是空的（可跳上去）。</p>
     */
    private boolean shouldJump(ServerLevel level) {
        Vec3 pos = bot.position();
        Vec3 dir = walkTarget.subtract(pos);
        Vec3 flat = new Vec3(dir.x, 0, dir.z);
        if (flat.lengthSqr() < 1.0E-4) {
            return false;
        }
        Vec3 ahead = pos.add(flat.normalize().scale(0.6));

        BlockPos front = BlockPos.containing(ahead.x, pos.y, ahead.z);
        BlockState frontState = level.getBlockState(front);
        BlockState frontUp = level.getBlockState(front.above());

        // 前方有实体方块，且上方可通过 → 跳
        boolean blocked = !frontState.isAir() && frontState.isSolidRender(bot.serverLevel(), front);
        boolean canPass = frontUp.isAir() || !frontUp.isSolidRender(bot.serverLevel(), front.above());
        return blocked && canPass && bot.onGround();
    }

    /** 清空移动输入（等价于松开所有按键）。 */
    private void clearMovementInput() {
        bot.zza = 0.0f;
        bot.xxa = 0.0f;
        bot.setJumping(false);
    }

    // ------------------------------------------------------------------
    // 挖掘：走原版 handleBlockBreakAction，自动按硬度算时间
    // ------------------------------------------------------------------

    private void tickMine() {
        ServerLevel level = bot.serverLevel();

        if (mineTarget == null || targetBlock == null) {
            fail("没有挖掘目标");
            return;
        }

        // 目标方块已被破坏（可能是上一 tick 完成的）
        BlockState current = level.getBlockState(mineTarget);
        if (current.isAir() || !current.is(targetBlock)) {
            minedSoFar++;
            breakingStarted = false;

            if (minedSoFar >= mineCount) {
                succeed("挖到了 " + minedSoFar + " 个 " + shortId(targetBlock)
                        + "，用时 " + elapsedTicks + " tick");
                return;
            }
            // 找下一个同类方块
            BlockPos next = findNearestBlock(level, targetBlock, 24);
            if (next == null) {
                succeed("附近已没有更多 " + shortId(targetBlock) + "，共挖 " + minedSoFar + " 个");
                return;
            }
            mineTarget = next;
            return;
        }

        // 距离太远：先走过去
        double dist = Math.sqrt(bot.blockPosition().distSqr(mineTarget));
        if (dist > 4.5) {
            // 用走路推进（复用 walk 逻辑但保持 MINE 状态）
            walkToward(mineTarget);
            return;
        }

        // 到位后停止移动，开始挖
        clearMovementInput();

        // 面朝方块（真人挖矿也会看着它）
        lookAtBlock(mineTarget);
        bot.swing(InteractionHand.MAIN_HAND);

        // 原版挖掘：第一次发 START，之后每 tick 发 STOP 继续累积进度
        // 这正是真人客户端持续按住左键时做的事情。
        if (!breakingStarted) {
            bot.gameMode.handleBlockBreakAction(
                    mineTarget,
                    ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK,
                    Direction.UP,
                    BLOCK_BREAK_LIGHT,
                    breakSeq++);
            breakingStarted = true;
        } else {
            bot.gameMode.handleBlockBreakAction(
                    mineTarget,
                    ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK,
                    Direction.UP,
                    BLOCK_BREAK_LIGHT,
                    breakSeq++);
        }
    }

    /** 让玩家朝某个方块走（挖掘时用的短距离接近）。 */
    private void walkToward(BlockPos pos) {
        Vec3 target = new Vec3(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
        Vec3 self = bot.position();
        double dx = target.x - self.x;
        double dz = target.z - self.z;
        float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        bot.setYRot(yaw);
        bot.yRotO = yaw;
        bot.setYHeadRot(yaw);
        bot.zza = MOVE_FORWARD;
        bot.xxa = 0.0f;
        if (shouldJump(bot.serverLevel())) {
            bot.setJumping(true);
        }
    }

    /** 让玩家看向某个方块。 */
    private void lookAtBlock(BlockPos pos) {
        Vec3 eye = bot.getEyePosition();
        Vec3 center = new Vec3(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
        Vec3 d = center.subtract(eye);
        double horizontal = Math.sqrt(d.x * d.x + d.z * d.z);
        float yaw = (float) (Math.toDegrees(Math.atan2(d.z, d.x)) - 90.0);
        float pitch = (float) (-Math.toDegrees(Math.atan2(d.y, horizontal)));
        bot.setYRot(yaw);
        bot.setXRot(pitch);
        bot.yRotO = yaw;
        bot.xRotO = pitch;
        bot.setYHeadRot(yaw);
    }

    /** 中断挖掘（发 ABORT 包，恢复原位）。 */
    private void abortBreaking() {
        if (mineTarget == null) {
            return;
        }
        try {
            bot.gameMode.handleBlockBreakAction(
                    mineTarget,
                    ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK,
                    Direction.UP,
                    bot.serverLevel().getMaxLocalRawBrightness(mineTarget),
                    breakSeq++);
        } catch (Throwable ignored) {
            // 中断失败不影响主流程
        }
    }

    // ------------------------------------------------------------------
    // 放置：走原版 useItemOn
    // ------------------------------------------------------------------

    private void tickPlace() {
        if (placePos == null || placeBlock == null) {
            fail("没有放置目标");
            return;
        }

        ServerLevel level = bot.serverLevel();

        // 已经放好了（原版放置成功后状态会变）
        BlockState now = level.getBlockState(placePos);
        if (now.is(placeBlock)) {
            succeed("已放置 " + shortId(placeBlock) + " 于 " + fmtPos(placePos));
            return;
        }

        // 需要手持该方块才能放（真人也是先切到手上）
        int slot = findBlockItemSlot(placeBlock);
        if (slot < 0) {
            fail("背包里没有 " + shortId(placeBlock));
            return;
        }
        // 切到对应快捷栏槽位（等价于真人滚轮/数字键切换）
        if (slot < 9) {
            bot.getInventory().selected = slot;
        }

        // 站到能碰到目标的位置
        double dist = Math.sqrt(bot.blockPosition().distSqr(placePos));
        if (dist > 4.0) {
            walkToward(placePos);
            return;
        }
        clearMovementInput();
        lookAtBlock(placePos);

        // 贴着目标方块的某个面放置
        BlockPos against = placePos.below();
        Direction face = Direction.UP;
        if (level.getBlockState(against).isAir()) {
            // 下方是空的，就贴相邻的实体方块
            for (Direction d : Direction.values()) {
                BlockPos side = placePos.relative(d);
                if (!level.getBlockState(side).isAir()) {
                    against = side;
                    face = d.getOpposite();
                    break;
                }
            }
        }

        Vec3 hitVec = new Vec3(against.getX() + 0.5, against.getY() + 0.5, against.getZ() + 0.5);
        BlockHitResult hit = new BlockHitResult(hitVec, face, against, false);

        ItemStack held = bot.getInventory().getSelected();
        // 原版放置入口：会做朝向、碰撞、可替换性等全部校验
        var result = bot.gameMode.useItemOn(bot, level, held, InteractionHand.MAIN_HAND, hit);
        bot.swing(InteractionHand.MAIN_HAND);

        if (result.consumesAction() || level.getBlockState(placePos).is(placeBlock)) {
            // 成功与否下一 tick 用实际方块状态判定
            return;
        }
        // 明确被拒绝时不要死等
        if (result == net.minecraft.world.InteractionResult.FAIL) {
            fail("无法在 " + fmtPos(placePos) + " 放置 " + shortId(placeBlock) + "（位置不合法或被占用）");
        }
    }

    // ------------------------------------------------------------------
    // 进食：走原版 startUsingItem，1.6 秒后由 tick 自动结算
    // ------------------------------------------------------------------

    private void tickEat() {
        // 正在使用物品：等原版把计时跑完
        if (bot.isUsingItem()) {
            ItemStack using = bot.getUseItem();
            if (using.isEmpty()) {
                succeed("进食结束");
            }
            // 原版会在计时结束后自动 completeUsingItem，这里继续等
            return;
        }

        // 第一次进入：检查饥饿值，选食物并开始使用
        var food = bot.getFoodData();
        if (food.getFoodLevel() >= 20 && elapsedTicks > 2) {
            succeed("并不饿，跳过进食");
            return;
        }

        int slot = findFoodSlot(eatItemFilter);
        if (slot < 0) {
            fail(eatItemFilter.isEmpty() ? "背包里没有食物" : "背包里没有 " + shortId(eatItemFilter));
            return;
        }

        // 切到手上
        if (slot < 9) {
            bot.getInventory().selected = slot;
        }

        ItemStack stack = bot.getInventory().getSelected();
        // 必须是食物才能 startUsingItem
        if (!isFood(stack)) {
            fail("选中的物品不是食物: " + shortId(String.valueOf(
                    net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem()))));
            return;
        }

        // 真人右键开始进食。之后由原版 LivingEntity.tick 结算：
        // 有 1.6 秒时长、会显示动画、被攻击会中断。这里不再做任何加速。
        bot.startUsingItem(InteractionHand.MAIN_HAND);

        // 已经在用但一直没结算（例如被中断）→ 下一 tick 重新判断
        if (elapsedTicks > 60) {
            if (bot.isUsingItem()) {
                return; // 原版还在吃
            }
            int before = food.getFoodLevel();
            succeed("进食完成，饥饿值 " + before);
        }
    }

    // ------------------------------------------------------------------
    // 攻击：遵守原版攻击冷却
    // ------------------------------------------------------------------

    private void tickAttack() {
        ServerLevel level = bot.serverLevel();

        // 原版攻击冷却：真人必须等冷却条满才能打出满伤害
        float cooldown = bot.getAttackStrengthScale(0.5f);
        if (cooldown < 0.95f) {
            // 冷却没好，等一下（真人也是在等冷却）
            return;
        }

        // 找目标
        AABB box = bot.getBoundingBox().inflate(4.0);
        List<Entity> candidates = level.getEntities(bot, box,
                e -> e instanceof LivingEntity && e.isAlive() && !(e instanceof ServerPlayer));

        if (candidates.isEmpty()) {
            fail("附近没有可攻击的目标");
            return;
        }

        // 按类型筛选（如果指定了）
        if (!attackTargetType.isEmpty()) {
            String want = attackTargetType.contains(":")
                    ? attackTargetType : "minecraft:" + attackTargetType;
            candidates.removeIf(e -> {
                var key = net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(e.getType());
                return key == null || !key.toString().equals(want);
            });
            if (candidates.isEmpty()) {
                fail("附近没有 " + shortId(want));
                return;
            }
        }

        candidates.sort(Comparator.comparingDouble(e -> e.distanceTo(bot)));
        Entity victim = candidates.get(0);

        // 面朝目标
        lookAtBlock(victim.blockPosition());
        bot.swing(InteractionHand.MAIN_HAND);

        // 走原版攻击路径：会计算伤害、击退、暴击、冷却倍率
        bot.attack(victim);

        succeed("攻击了 " + shortId(String.valueOf(
                net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(victim.getType()))));
    }

    // ------------------------------------------------------------------
    // 拾取：走过去让原版自动拾取
    // ------------------------------------------------------------------

    private void tickPickup() {
        ServerLevel level = bot.serverLevel();
        AABB box = bot.getBoundingBox().inflate(8.0);
        List<Entity> items = level.getEntities(bot, box,
                e -> e instanceof net.minecraft.world.entity.item.ItemEntity && e.isAlive());

        if (items.isEmpty()) {
            succeed("附近没有掉落物");
            return;
        }
        items.sort(Comparator.comparingDouble(e -> e.distanceTo(bot)));
        Entity nearest = items.get(0);

        double dist = nearest.distanceTo(bot);
        if (dist < 1.2) {
            // 站上去，原版的 pickup 逻辑会自动收进背包
            succeed("已拾取附近掉落物");
            return;
        }
        walkToward(nearest.blockPosition());
    }

    // ------------------------------------------------------------------
    // 辅助
    // ------------------------------------------------------------------

    private void succeed(String msg) {
        clearMovementInput();
        this.status = Status.SUCCESS;
        this.message = msg;
        this.kind = Kind.NONE;
    }

    private void fail(String msg) {
        clearMovementInput();
        if (kind == Kind.MINE && breakingStarted) {
            abortBreaking();
        }
        if (bot.isUsingItem()) {
            bot.stopUsingItem();
        }
        this.status = Status.FAILED;
        this.message = msg;
        this.kind = Kind.NONE;
        this.breakingStarted = false;
    }

    /** 在半径内找最近的指定方块。 */
    private BlockPos findNearestBlock(ServerLevel level, Block target, int radius) {
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
    private int findBlockItemSlot(Block block) {
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

    /** 在背包里找可食用物品。 */
    private int findFoodSlot(String filter) {
        var inv = bot.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (s.isEmpty() || !isFood(s)) {
                continue;
            }
            if (!filter.isEmpty()) {
                var key = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(s.getItem());
                if (key == null || (!key.toString().equals(filter)
                        && !key.toString().equals("minecraft:" + filter))) {
                    continue;
                }
            }
            return i;
        }
        return -1;
    }

    /** 判断是否可食用（1.21.11 用 DataComponents.FOOD）。 */
    private boolean isFood(ItemStack stack) {
        try {
            return stack.getItem().isEdible();
        } catch (Throwable t) {
            return false;
        }
    }

    private static String fmt(double v) {
        return String.format(java.util.Locale.ROOT, "%.0f", v);
    }

    private static String fmtPos(BlockPos p) {
        return "(" + p.getX() + ", " + p.getY() + ", " + p.getZ() + ")";
    }

    private static String shortId(Block b) {
        var key = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(b);
        String s = key == null ? "unknown" : key.toString();
        return s.startsWith("minecraft:") ? s.substring("minecraft:".length()) : s;
    }

    private static String shortId(String id) {
        return id.startsWith("minecraft:") ? id.substring("minecraft:".length()) : id;
    }
}
