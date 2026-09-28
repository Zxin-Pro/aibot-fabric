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
    /** A* 算出的路径（按顺序经过的格子）。 */
    private java.util.List<PathFinder.Step> path = null;
    /** 当前走到第几个路径点。 */
    private int pathIndex = 0;
    /** 已重新规划次数（防止无限重算烧 CPU）。 */
    private int replans = 0;
    /** 上次重新规划发生在第几 tick。 */
    private int lastReplanTick = 0;
    /** 寻路器：允许挖穿挡路方块与搭桥，像真人一样自己开路。 */
    private PathFinder pathFinder = null;
    /** 路径中途需要挖的方块。 */
    private BlockPos pendingDig = null;
    /** 路径中途需要搭的方块位置。 */
    private BlockPos pendingPlace = null;

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
            // 危险规避优先级最高：站在岩浆旁边时先离开，什么动作都往后放
            // （这是真人的本能，不该等 LLM 判断）
            if (kind != Kind.EAT && avoidDanger()) {
                return;
            }

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

    /**
     * 走路：先 A* 算路，再沿路径点逐个走过去。
     *
     * <p>这是「像真人一样」的核心之一：真人遇到墙会绕，
     * 这里通过 {@link PathFinder} 得到绕行路径；
     * 路径上需要挖穿的方块会挖掉，需要搭桥的地方会垫方块。</p>
     */
    private void tickWalk() {
        if (walkTarget == null) {
            fail("没有行走目标");
            return;
        }

        ServerLevel level = (ServerLevel) bot.level();
        Vec3 pos = bot.position();

        double dx = walkTarget.x - pos.x;
        double dz = walkTarget.z - pos.z;
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        double dy = walkTarget.y - pos.y;

        // 到达判定
        if (horizontal < ARRIVE_H_DISTANCE && Math.abs(dy) < ARRIVE_V_TOLERANCE) {
            clearMovementInput();
            succeed("已走到 (" + fmt(walkTarget.x) + ", " + fmt(walkTarget.y) + ", "
                    + fmt(walkTarget.z) + ")，用时 " + elapsedTicks + " tick"
                    + (replans > 0 ? "（重新规划 " + replans + " 次）" : ""));
            return;
        }

        // ---- 1. 首次进入或路径已走完 → 规划路径 ----
        if (path == null || pathIndex >= path.size()) {
            if (!planPath(level)) {
                return; // 规划失败时 planPath 内部已给出结论
            }
        }

        // ---- 2. 处理路径上的挖/搭 ----
        if (pendingDig != null) {
            if (!handlePathDig(level)) {
                return; // 还在挖
            }
        }
        if (pendingPlace != null && !handlePathPlace(level)) {
            return; // 还在搭
        }

        // ---- 3. 朝当前路径点走 ----
        PathFinder.Step step = path.get(pathIndex);
        Vec3 waypoint = new Vec3(step.pos.getX() + 0.5, step.pos.getY(), step.pos.getZ() + 0.5);
        double wdx = waypoint.x - pos.x;
        double wdz = waypoint.z - pos.z;
        double wdist = Math.sqrt(wdx * wdx + wdz * wdz);

        // 到达当前路径点 → 前进到下一个
        if (wdist < 0.7 && Math.abs(waypoint.y - pos.y) < 1.6) {
            pathIndex++;
            // 取出下一个路径点附带的挖/搭需求
            if (pathIndex < path.size()) {
                PathFinder.Step next = path.get(pathIndex);
                pendingDig = next.digPos;
                pendingPlace = next.placePos;
            }
            return;
        }

        // 转向当前路径点（真人也是先转头再走）
        float yaw = (float) (Math.toDegrees(Math.atan2(wdz, wdx)) - 90.0);
        bot.setYRot(yaw);
        bot.yRotO = yaw;
        bot.setYHeadRot(yaw);

        // 设置前进输入 —— 等价于真人按住 W
        bot.zza = MOVE_FORWARD;
        bot.xxa = 0.0f;

        // 需要上台阶/跳上路径点时按空格（真人手动跳）
        boolean needJump = step.jump
                || shouldJumpToward(level, waypoint)
                || (Math.abs(waypoint.y - pos.y) > 0.6 && bot.onGround());
        bot.setJumping(needJump);

        // 卡住检测：连续多 tick 几乎没动 → 重新规划
        if (elapsedTicks > 20 && isStuckInPlace()) {
            if (replans >= MAX_REPLANS) {
                clearMovementInput();
                fail("反复被卡住（已重新规划 " + replans + " 次），无法到达 "
                        + "(" + fmt(walkTarget.x) + ", " + fmt(walkTarget.y) + ", " + fmt(walkTarget.z) + ")");
                return;
            }
            replans++;
            lastReplanTick = elapsedTicks;
            path = null;
            pathIndex = 0;
            pendingDig = null;
            pendingPlace = null;
            lastPos = null;
            LOGGER.info("[AIBot] 走路卡住，第 " + replans + " 次重新规划路径");
        }
    }

    /** 最多重新规划几次。 */
    private static final int MAX_REPLANS = 5;

    /**
     * 规划路径。
     *
     * @return true 表示成功拿到路径（或已判定无需走）
     */
    private boolean planPath(ServerLevel level) {
        BlockPos start = bot.blockPosition();
        BlockPos goal = BlockPos.containing(walkTarget.x, walkTarget.y, walkTarget.z);

        // 允许挖穿与搭桥：像真人一样自己开路
        if (pathFinder == null) {
            pathFinder = new PathFinder(true, true, bridgeBlockOf(level));
        }

        java.util.List<PathFinder.Step> found = pathFinder.findPath(level, start, goal);

        if (found.isEmpty()) {
            // 目标就在脚下
            if (start.equals(goal) || Math.abs(start.getY() - goal.getY()) <= 1
                    && start.getX() == goal.getX() && start.getZ() == goal.getZ()) {
                clearMovementInput();
                succeed("已在目标位置");
                return false;
            }
            clearMovementInput();
            // 给出更有用的失败原因
            if (!level.isLoaded(goal)) {
                fail("目标位置所在区块未加载，无法寻路");
            } else {
                fail("寻路失败：附近没有可到达的路径（目标可能被封死或超出搜索范围 "
                        + "(" + PathFinder.class.getSimpleName() + " 上限 4000 节点)）");
            }
            return false;
        }

        this.path = found;
        this.pathIndex = 0;
        // 第一个路径点的挖/搭需求
        PathFinder.Step first = found.get(0);
        this.pendingDig = first.digPos;
        this.pendingPlace = first.placePos;
        LOGGER.fine("[AIBot] 寻路成功：" + found.size() + " 步");
        return true;
    }

    /**
     * 处理路径上的挖掘需求。
     *
     * @return true 表示挖完了（可以继续走）
     */
    private boolean handlePathDig(ServerLevel level) {
        BlockPos target = pendingDig;
        BlockState state = level.getBlockState(target);

        // 已经挖掉了
        if (state.isAir()) {
            pendingDig = null;
            clearMovementInput();
            return true;
        }

        // 走过去挖（真人也是走到能挖到的距离）
        double dist = Math.sqrt(bot.blockPosition().distSqr(target));
        if (dist > 4.0) {
            walkToward(target);
            return false;
        }

        clearMovementInput();
        lookAtBlock(target);
        bot.swing(InteractionHand.MAIN_HAND);

        // 走原版挖掘流程（会按硬度耗时）
        bot.gameMode.handleBlockBreakAction(
                target,
                breakingStarted
                        ? ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK
                        : ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK,
                Direction.UP,
                BLOCK_BREAK_LIGHT,
                breakSeq++);
        breakingStarted = true;
        return false;
    }

    /**
     * 处理路径上的搭桥需求。
     *
     * @return true 表示搭好了（可以继续走）
     */
    private boolean handlePathPlace(ServerLevel level) {
        BlockPos target = pendingPlace;
        BlockState state = level.getBlockState(target);

        // 已经有方块了（可能被别的东西占了）
        if (!state.isAir() && !state.canBeReplaced()) {
            pendingPlace = null;
            clearMovementInput();
            return true;
        }

        // 找背包里的搭桥方块
        int slot = findAnyPlaceableSlot();
        if (slot < 0) {
            clearMovementInput();
            fail("需要搭桥但背包里没有可放置的方块");
            return false;
        }
        if (slot < 9) {
            bot.getInventory().setSelectedSlot(slot);
        }

        // 站近一点再放
        double dist = Math.sqrt(bot.blockPosition().distSqr(target));
        if (dist > 3.5) {
            walkToward(target);
            return false;
        }

        clearMovementInput();
        lookAtBlock(target);

        // 贴相邻方块放置
        Direction face = Direction.UP;
        BlockPos against = target.below();
        if (level.getBlockState(against).isAir()) {
            for (Direction d : Direction.values()) {
                BlockPos side = target.relative(d);
                if (!level.getBlockState(side).isAir()) {
                    against = side;
                    face = d.getOpposite();
                    break;
                }
            }
        }

        Vec3 hitVec = new Vec3(against.getX() + 0.5, against.getY() + 0.5, against.getZ() + 0.5);
        BlockHitResult hit = new BlockHitResult(hitVec, face, against, false);
        ItemStack held = bot.getInventory().getSelectedItem();
        bot.gameMode.useItemOn(bot, level, held, InteractionHand.MAIN_HAND, hit);
        bot.swing(InteractionHand.MAIN_HAND, true);

        // 下一 tick 用实际方块状态判定是否成功
        if (!level.getBlockState(target).isAir() && !level.getBlockState(target).canBeReplaced()) {
            pendingPlace = null;
            return true;
        }
        return false;
    }

    /** 找背包里任意一个可以当搭桥材料的方块。 */
    private int findAnyPlaceableSlot() {
        var inv = bot.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (s.isEmpty()) {
                continue;
            }
            if (s.getItem() instanceof net.minecraft.world.item.BlockItem) {
                return i;
            }
        }
        return -1;
    }

    /** 选一个适合搭桥的方块（优先用便宜的）。 */
    private Block bridgeBlockOf(ServerLevel level) {
        // 优先找常见建筑材料
        Block[] prefer = {
                net.minecraft.world.level.block.Blocks.COBBLESTONE,
                net.minecraft.world.level.block.Blocks.DIRT,
                net.minecraft.world.level.block.Blocks.OAK_PLANKS,
                net.minecraft.world.level.block.Blocks.STONE,
        };
        var inv = bot.getInventory();
        for (Block b : prefer) {
            for (int i = 0; i < inv.getContainerSize(); i++) {
                ItemStack s = inv.getItem(i);
                if (!s.isEmpty() && s.getItem() instanceof net.minecraft.world.item.BlockItem bi
                        && bi.getBlock() == b) {
                    return b;
                }
            }
        }
        return null;
    }

    /**
     * 判断朝某个坐标走时是否需要跳。
     */
    private boolean shouldJumpToward(ServerLevel level, Vec3 target) {
        Vec3 pos = bot.position();
        Vec3 dir = target.subtract(pos);
        Vec3 flat = new Vec3(dir.x, 0, dir.z);
        if (flat.lengthSqr() < 1.0E-4) {
            return false;
        }
        Vec3 ahead = pos.add(flat.normalize().scale(0.6));
        BlockPos front = BlockPos.containing(ahead.x, pos.y, ahead.z);
        BlockState frontState = level.getBlockState(front);
        BlockState frontUp = level.getBlockState(front.above());
        boolean blocked = !frontState.isAir() && frontState.isSolidRender();
        boolean canPass = frontUp.isAir() || !frontUp.isSolidRender();
        return blocked && canPass && bot.onGround();
    }

    /** 上一 tick 的位置，用于卡住检测。 */
    private Vec3 lastPos = null;

    // ------------------------------------------------------------------
    // 安全与辅助反射（真人的本能行为）
    // ------------------------------------------------------------------

    /**
     * 危险规避：站在岩浆/火/仙人掌等危险方块旁时自动挪开。
     *
     * <p>真人会本能地避开这些，这里作为兜底反射，
     * 不经过 LLM 直接执行，避免因为 LLM 超时或判断失误而烧死。</p>
     *
     * @return true 表示已经朝安全方向施加了移动输入
     */
    public boolean avoidDanger() {
        ServerLevel level = (ServerLevel) bot.level();
        BlockPos feet = bot.blockPosition();

        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    BlockPos p = feet.offset(dx, dy, dz);
                    if (!level.isLoaded(p)) {
                        continue;
                    }
                    if (!isDangerous(level.getBlockState(p))) {
                        continue;
                    }
                    // 朝远离危险源的方向推一下（等价于真人往后退）
                    Vec3 away = bot.position().subtract(
                            new Vec3(p.getX() + 0.5, p.getY() + 0.5, p.getZ() + 0.5));
                    Vec3 flat = new Vec3(away.x, 0, away.z);
                    if (flat.lengthSqr() < 1.0E-4) {
                        flat = new Vec3(1, 0, 0);
                    }
                    flat = flat.normalize();
                    float yaw = (float) (Math.toDegrees(Math.atan2(flat.z, flat.x)) - 90.0);
                    bot.setYRot(yaw);
                    bot.yRotO = yaw;
                    bot.setYHeadRot(yaw);
                    bot.zza = MOVE_FORWARD;
                    bot.xxa = 0.0f;
                    return true;
                }
            }
        }
        return false;
    }

    /** 是否是危险方块（岩浆、火、仙人掌、岩浆块、营火、甜浆果丛）。 */
    private boolean isDangerous(BlockState s) {
        return s.is(net.minecraft.world.level.block.Blocks.LAVA)
                || s.is(net.minecraft.world.level.block.Blocks.FIRE)
                || s.is(net.minecraft.world.level.block.Blocks.SOUL_FIRE)
                || s.is(net.minecraft.world.level.block.Blocks.CACTUS)
                || s.is(net.minecraft.world.level.block.Blocks.MAGMA_BLOCK)
                || s.is(net.minecraft.world.level.block.Blocks.CAMPFIRE)
                || s.is(net.minecraft.world.level.block.Blocks.SWEET_BERRY_BUSH);
    }

    /**
     * 主动去捡附近的掉落物。
     *
     * <p>真人挖完矿会走过去把掉落物捡起来。这里朝最近的掉落物走，
     * 进入 1.2 格后由原版自动收入背包。</p>
     *
     * @return true 表示正在前往掉落物
     */
    public boolean tryPickupNearby() {
        ServerLevel level = (ServerLevel) bot.level();
        AABB box = bot.getBoundingBox().inflate(4.0);
        var items = level.getEntities(bot, box,
                e -> e instanceof net.minecraft.world.entity.item.ItemEntity && e.isAlive());
        if (items.isEmpty()) {
            return false;
        }
        items.sort(Comparator.comparingDouble(e -> e.distanceTo(bot)));
        var nearest = items.get(0);
        if (nearest.distanceTo(bot) < 1.5) {
            return false; // 已经很近，原版会自动收
        }
        walkToward(nearest.blockPosition());
        return true;
    }

    /**
     * 当前手持工具是否即将损坏。
     *
     * <p>真人会在镐子快坏时换一把，这里提供判断供上层决策。</p>
     */
    public boolean isToolAboutToBreak() {
        try {
            ItemStack held = bot.getInventory().getSelectedItem();
            if (held.isEmpty() || !held.isDamageableItem()) {
                return false;
            }
            int left = held.getMaxDamage() - held.getDamageValue();
            return left <= 1;
        } catch (Throwable t) {
            return false;
        }
    }

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

    /** 清空移动输入（等价于松开所有按键）。 */
    private void clearMovementInput() {
        bot.zza = 0.0f;
        bot.xxa = 0.0f;
        bot.setJumping(false);
    }

    // ------------------------------------------------------------------
    // 挖掘：走原版 handleBlockBreakAction，自动按硬度算时间
    // ------------------------------------------------------------------

    /**
     * 选择挖这个方块最合适的工具，并切到手上。
     *
     * <p>真人挖矿会先换工具：石头用镐、木头用斧、土用锹。
     * 这里模仿同样的行为；找不到合适工具就空手挖（会慢很多）。</p>
     */
    private void selectBestToolFor(BlockState state) {
        var inv = bot.getInventory();

        // 先用原版的「工具对当前方块是否更快」来判断，最通用
        int bestSlot = -1;
        float bestSpeed = 1.0f;

        for (int i = 0; i < 9; i++) { // 只看快捷栏，真人也是切快捷栏
            ItemStack s = inv.getItem(i);
            if (s.isEmpty()) {
                continue;
            }
            try {
                float speed = s.getDestroySpeed(state);
                if (speed > bestSpeed) {
                    bestSpeed = speed;
                    bestSlot = i;
                }
            } catch (Throwable ignored) {
                // 某些版本/物品不支持，忽略
            }
        }

        if (bestSlot >= 0) {
            inv.setSelectedSlot(bestSlot);
        }
    }

    private void tickMine() {
        ServerLevel level = (ServerLevel) bot.level();

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
                // 挖完了：先花点时间把掉落物捡起来（真人也会这么做）
                if (tryPickupNearby()) {
                    return;
                }
                succeed("附近已没有更多 " + shortId(targetBlock) + "，共挖 " + minedSoFar + " 个");
                return;
            }
            // 换目标前先顺路捡掉落物
            if (minedSoFar > 0 && tryPickupNearby()) {
                return;
            }
            mineTarget = next;
            return;
        }

        // 距离太远：用 A* 走过去（而不是直线顶墙）
        double dist = Math.sqrt(bot.blockPosition().distSqr(mineTarget));
        if (dist > 4.5) {
            walkToward(mineTarget);
            return;
        }

        // 到位后停止移动，开始挖
        clearMovementInput();

        // 真人挖矿前会切换到合适的工具（镐挖石、斧砍木、铲挖土）
        selectBestToolFor(current);

        // 面朝方块（真人挖矿也会看着它）
        lookAtBlock(mineTarget);
        bot.swing(InteractionHand.MAIN_HAND, true);

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
        if (shouldJumpToward(bot.level(), new Vec3(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5))) {
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
                    BLOCK_BREAK_LIGHT,
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

        ServerLevel level = (ServerLevel) bot.level();

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
            bot.getInventory().setSelectedSlot(slot);
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

        ItemStack held = bot.getInventory().getSelectedItem();
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
            bot.getInventory().setSelectedSlot(slot);
        }

        ItemStack stack = bot.getInventory().getSelectedItem();
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
        ServerLevel level = (ServerLevel) bot.level();

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
        ServerLevel level = (ServerLevel) bot.level();
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
            return stack.has(net.minecraft.core.component.DataComponents.FOOD);
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
