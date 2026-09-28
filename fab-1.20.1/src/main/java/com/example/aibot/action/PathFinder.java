package com.example.aibot.action;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * A* 寻路器：让假玩家像真人一样绕开障碍走到目的地。
 *
 * <p><b>为什么需要它</b>：之前的移动是「朝目标方向直走」，
 * 遇到墙就一直顶着直到超时 —— 真人不会这么干，真人会绕过去。
 * 本类实现真正的路径搜索。</p>
 *
 * <p><b>支持的动作</b>（都是真人会做的）：</p>
 * <ul>
 *   <li>平地走、走上台阶（1 格高）</li>
 *   <li>从高处下落（限定落差，避免摔死）</li>
 *   <li>在水中游</li>
 *   <li>挖穿挡路的方块（可配置，默认允许挖软方块）</li>
 *   <li>在缺口上搭桥/垫脚（可配置）</li>
 * </ul>
 *
 * <p><b>性能</b>：搜索节点上限 + 迭代上限双重保护，
 * 并且是纯计算（不改世界），即使在大范围内搜索也不会卡 tick。</p>
 */
public final class PathFinder {

    /** 单次搜索最多展开多少个节点（防止极端地形下卡死）。 */
    private static final int MAX_NODES = 4000;

    /** 允许的最大下落高度（超过则不走，避免摔伤）。 */
    private static final int MAX_FALL = 4;

    /** 允许挖穿的最大方块硬度（越大越慢，太硬的就不挖了）。 */
    private static final float MAX_BREAK_HARDNESS = 3.0f;

    /** 允许搭桥的最大跨度。 */
    private static final int MAX_BRIDGE = 3;

    /**
     * 单个搜索节点的移动代价与方式。
     */
    private record Move(int dx, int dy, int dz, double cost, boolean needsDig, boolean needsPlace) {
    }

    /** 搜索节点。 */
    private static final class Node implements Comparable<Node> {
        final int x;
        final int y;
        final int z;
        /** 从起点到这里的实际代价。 */
        double g;
        /** 启发式估计的总代价。 */
        double f;
        Node parent;
        /** 到达本节点的移动方式（用于回溯时决定"挖"还是"搭"）。 */
        Move via;

        Node(int x, int y, int z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }

        @Override
        public int compareTo(Node o) {
            return Double.compare(this.f, o.f);
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof Node n)) {
                return false;
            }
            return x == n.x && y == n.y && z == n.z;
        }

        @Override
        public int hashCode() {
            return (x * 31 + y) * 31 + z;
        }
    }

    /**
     * 一个寻路步骤：告诉调用方「走到哪个格子」以及「路上要不要先挖/搭」。
     */
    public static final class Step {
        /** 要去的格子（玩家脚所在位置）。 */
        public final BlockPos pos;
        /** 到达该格子前需要挖掉的方块（可能为空）。 */
        public final BlockPos digPos;
        /** 到达该格子前需要放置方块的格子（可能为空）。 */
        public final BlockPos placePos;
        /** 是否需要在到达时跳跃。 */
        public final boolean jump;

        Step(BlockPos pos, BlockPos digPos, BlockPos placePos, boolean jump) {
            this.pos = pos;
            this.digPos = digPos;
            this.placePos = placePos;
            this.jump = jump;
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder(pos.toShortString());
            if (digPos != null) {
                sb.append(" 挖").append(digPos.toShortString());
            }
            if (placePos != null) {
                sb.append(" 搭").append(placePos.toShortString());
            }
            if (jump) {
                sb.append(" 跳");
            }
            return sb.toString();
        }
    }

    /** 是否允许挖穿挡路方块。 */
    private final boolean allowDig;

    /** 是否允许搭桥/垫脚（需要背包有方块）。 */
    private final boolean allowPlace;

    /** 用于搭桥的方块。 */
    private final Block bridgeBlock;

    public PathFinder(boolean allowDig, boolean allowPlace, Block bridgeBlock) {
        this.allowDig = allowDig;
        this.allowPlace = allowPlace;
        this.bridgeBlock = bridgeBlock;
    }

    /**
     * 搜索从起点到终点的路径。
     *
     * @param level 世界
     * @param start 起点（玩家脚所在格）
     * @param goal  终点
     * @return 路径步骤列表（不含起点）；找不到路径返回空列表
     */
    public List<Step> findPath(ServerLevel level, BlockPos start, BlockPos goal) {
        if (start.equals(goal)) {
            return Collections.emptyList();
        }

        PriorityQueue<Node> open = new PriorityQueue<>();
        Map<Long, Node> allNodes = new HashMap<>();
        Set<Long> closed = new HashSet<>();

        Node startNode = new Node(start.getX(), start.getY(), start.getZ());
        startNode.g = 0;
        startNode.f = heuristic(start, goal);
        open.add(startNode);
        allNodes.put(key(start.getX(), start.getY(), start.getZ()), startNode);

        int expanded = 0;
        Node goalNode = null;

        while (!open.isEmpty() && expanded < MAX_NODES) {
            Node current = open.poll();
            long ck = key(current.x, current.y, current.z);
            if (closed.contains(ck)) {
                continue;
            }
            closed.add(ck);
            expanded++;

            // 到达目标（允许垂直容差，因为目标点可能在半空）
            if (current.x == goal.getX() && current.z == goal.getZ()
                    && Math.abs(current.y - goal.getY()) <= 1) {
                goalNode = current;
                break;
            }

            for (Move move : getMoves(level, current.x, current.y, current.z)) {
                int nx = current.x + move.dx();
                int ny = current.y + move.dy();
                int nz = current.z + move.dz();

                // 边界保护
                // 1.20.1 / 1.21.1 的 LevelHeightAccessor 用 getMinBuildHeight/getMaxBuildHeight
                if (ny < level.getMinBuildHeight() || ny > level.getMaxBuildHeight()) {
                    continue;
                }

                long nk = key(nx, ny, nz);
                if (closed.contains(nk)) {
                    continue;
                }

                // 落点必须能站人
                BlockPos np = new BlockPos(nx, ny, nz);
                if (!canStandAt(level, np)) {
                    continue;
                }

                // 挖/搭的可行性检查
                if (move.needsDig() && !canDigThrough(level, np, move)) {
                    continue;
                }
                if (move.needsPlace() && !allowPlace) {
                    continue;
                }

                double tentativeG = current.g + move.cost();
                Node existing = allNodes.get(nk);
                if (existing == null) {
                    Node node = new Node(nx, ny, nz);
                    node.g = tentativeG;
                    node.f = tentativeG + heuristic(np, goal);
                    node.parent = current;
                    node.via = move;
                    open.add(node);
                    allNodes.put(nk, node);
                } else if (tentativeG < existing.g) {
                    existing.g = tentativeG;
                    existing.f = tentativeG + heuristic(np, goal);
                    existing.parent = current;
                    existing.via = move;
                    // 重新入队（惰性删除，靠 closed 去重）
                    open.add(existing);
                }
            }
        }

        if (goalNode == null) {
            return Collections.emptyList();
        }
        return reconstruct(goalNode);
    }

    /** 回溯路径。 */
    private List<Step> reconstruct(Node node) {
        List<Step> steps = new ArrayList<>();
        Node cur = node;
        while (cur.parent != null) {
            Node from = cur.parent;
            Move via = cur.via;
            BlockPos pos = new BlockPos(cur.x, cur.y, cur.z);

            BlockPos digPos = null;
            BlockPos placePos = null;
            boolean jump = false;

            if (via != null) {
                if (via.needsDig()) {
                    // 需要挖掉的是落点处的方块（或头部）
                    digPos = pos;
                }
                if (via.needsPlace()) {
                    // 需要在脚下那个空缺位置搭方块
                    placePos = pos.below();
                }
                // 上台阶需要跳
                jump = via.dy() > 0;
            }

            steps.add(new Step(pos, digPos, placePos, jump));
            cur = from;
        }
        Collections.reverse(steps);
        return steps;
    }

    /**
     * 枚举从 (x,y,z) 出发的所有可行移动。
     *
     * <p>以真人的行为为模板：走平地、上/下一格台阶、跳跃、下落、游泳、挖穿、搭桥。</p>
     */
    private List<Move> getMoves(ServerLevel level, int x, int y, int z) {
        List<Move> moves = new ArrayList<>();
        BlockPos here = new BlockPos(x, y, z);

        for (Direction d : Direction.Plane.HORIZONTAL) {
            int dx = d.getStepX();
            int dz = d.getStepZ();
            BlockPos horizontal = here.offset(dx, 0, dz);

            // ---- 1. 平地走 ----
            if (canStandAt(level, horizontal)) {
                moves.add(new Move(dx, 0, dz, 1.0, false, false));
                continue;
            }

            // ---- 2. 上一格台阶（真人按空格）----
            BlockPos up = here.offset(dx, 1, dz);
            if (canStandAt(level, up) && canStandAt(level, horizontal.above())) {
                moves.add(new Move(dx, 1, dz, 1.5, false, false));
                continue;
            }

            // ---- 3. 挖穿挡路的方块（真人会挖开）----
            if (allowDig && canDigAt(level, horizontal)) {
                moves.add(new Move(dx, 0, dz, 4.0, true, false));
                continue;
            }
            if (allowDig && canDigAt(level, horizontal.above()) && canStandAt(level, horizontal)) {
                moves.add(new Move(dx, 1, dz, 4.0, true, false));
                continue;
            }

            // ---- 4. 搭桥跨过缺口（真人会垫方块）----
            if (allowPlace) {
                if (tryBridge(level, here, dx, dz, moves)) {
                    continue;
                }
            }

            // ---- 5. 向下走（台阶下落）----
            for (int drop = 1; drop <= MAX_FALL; drop++) {
                BlockPos down = here.offset(dx, -drop, dz);
                if (canStandAt(level, down)) {
                    // 下落不能穿过实体（否则等于穿墙）
                    if (isClearColumn(level, here.offset(dx, 0, dz), down)) {
                        moves.add(new Move(dx, -drop, dz, 1.2 + drop * 0.2, false, false));
                    }
                    break;
                }
                // 掉进水里也可以
                if (isWaterAt(level, down)) {
                    moves.add(new Move(dx, -drop, dz, 1.5 + drop * 0.2, false, false));
                    break;
                }
            }
        }

        // ---- 6. 垂直向上（爬梯子/藤蔓，或者原地跳上高一格）----
        if (isClimbable(level, here) || isClimbable(level, here.above())) {
            BlockPos up = here.above();
            if (canStandAt(level, up)) {
                moves.add(new Move(0, 1, 0, 1.5, false, false));
            }
        }

        return moves;
    }

    /** 尝试搭桥：前方是缺口时，在缺口处垫一个方块走过去。 */
    private boolean tryBridge(ServerLevel level, BlockPos here, int dx, int dz, List<Move> moves) {
        if (bridgeBlock == null) {
            return false;
        }
        // 检查跨度 1~MAX_BRIDGE
        for (int span = 1; span <= MAX_BRIDGE; span++) {
            BlockPos gap = here.offset(dx * span, 0, dz * span);
            // 缺口下方是空的，说明是悬空
            if (!isAirAt(level, gap.below())) {
                break;
            }
            // 缺口当前位置必须能放方块（空气或可替换）
            if (!canPlaceAt(level, gap)) {
                break;
            }
            // 落点（跨过缺口之后）必须能站
            BlockPos landing = here.offset(dx * (span + 1), 0, dz * (span + 1));
            if (canStandAt(level, landing)) {
                moves.add(new Move(dx, 0, dz, 6.0 + span, false, true));
                return true;
            }
        }
        return false;
    }

    /**
     * 判断某格子能否站人（脚位空气 + 头顶有空间 + 脚下有支撑）。
     *
     * <p>用原版的 {@code noCollision} 做真实碰撞判断，
     * 所以台阶、栅栏、半砖这些形状都能正确处理。</p>
     */
    public boolean canStandAt(ServerLevel level, BlockPos pos) {
        if (!level.isLoaded(pos)) {
            return false;
        }
        // 玩家碰撞箱：宽 0.6、高 1.8
        AABB box = new AABB(
                pos.getX() + 0.2, pos.getY(), pos.getZ() + 0.2,
                pos.getX() + 0.8, pos.getY() + 1.8, pos.getZ() + 0.8);
        if (!level.noCollision(box)) {
            return false;
        }
        // 脚下必须有支撑（固体或水）
        BlockPos below = pos.below();
        if (!level.isLoaded(below)) {
            return false;
        }
        BlockState belowState = level.getBlockState(below);
        return !belowState.isAir() || isWaterAt(level, below);
    }

    /** 某格能否被挖穿（是可破坏的方块，且硬度在阈值内）。 */
    private boolean canDigAt(ServerLevel level, BlockPos pos) {
        if (!level.isLoaded(pos)) {
            return false;
        }
        BlockState state = level.getBlockState(pos);
        if (state.isAir()) {
            return false;
        }
        // 流体不算阻挡
        if (!state.getFluidState().isEmpty()) {
            return false;
        }
        // 基岩等不可破坏的方块不能挖
        if (state.getDestroySpeed(level, pos) < 0) {
            return false;
        }
        return state.getDestroySpeed(level, pos) <= MAX_BREAK_HARDNESS;
    }

    /** 移动是否需要挖穿，以及挖穿是否可行。 */
    private boolean canDigThrough(ServerLevel level, BlockPos landing, Move move) {
        if (!move.needsDig()) {
            return true;
        }
        // 落点本身或它上方是挡路的方块
        return canDigAt(level, landing) || canDigAt(level, landing.above());
    }

    /** 某格能否放置方块。 */
    private boolean canPlaceAt(ServerLevel level, BlockPos pos) {
        if (!level.isLoaded(pos)) {
            return false;
        }
        BlockState state = level.getBlockState(pos);
        return state.isAir() || state.canBeReplaced();
    }

    /** 从 from 到 to 之间是否没有实体阻挡（用于下落判定）。 */
    private boolean isClearColumn(ServerLevel level, BlockPos from, BlockPos to) {
        int step = from.getY() > to.getY() ? -1 : 1;
        for (int y = from.getY(); y != to.getY(); y += step) {
            BlockPos p = new BlockPos(from.getX(), y, from.getZ());
            if (!level.isLoaded(p)) {
                return false;
            }
            BlockState s = level.getBlockState(p);
            if (!s.isAir() && s.getFluidState().isEmpty() && s.isSolidRender(level, p)) {
                return false;
            }
        }
        return true;
    }

    private boolean isWaterAt(ServerLevel level, BlockPos pos) {
        if (!level.isLoaded(pos)) {
            return false;
        }
        return !level.getBlockState(pos).getFluidState().isEmpty();
    }

    private boolean isClimbable(ServerLevel level, BlockPos pos) {
        if (!level.isLoaded(pos)) {
            return false;
        }
        BlockState s = level.getBlockState(pos);
        return s.is(net.minecraft.tags.BlockTags.CLIMBABLE);
    }

    private boolean isAirAt(ServerLevel level, BlockPos pos) {
        if (!level.isLoaded(pos)) {
            return false;
        }
        return level.getBlockState(pos).isAir();
    }

    /** 启发式：曼哈顿距离 + 垂直惩罚（让路径倾向走平地）。 */
    private double heuristic(BlockPos from, BlockPos to) {
        int dx = Math.abs(from.getX() - to.getX());
        int dy = Math.abs(from.getY() - to.getY());
        int dz = Math.abs(from.getZ() - to.getZ());
        return dx + dz + dy * 1.5;
    }

    private static long key(int x, int y, int z) {
        // 简单可靠的哈希，避免碰撞
        return ((long) x & 0x3FFFFFFL) << 38
                | ((long) z & 0x3FFFFFFL) << 12
                | ((long) y & 0xFFFL);
    }

    /**
     * 视线检查：判断两点之间是否没有遮挡。
     *
     * <p>真人能直接看到的东西才会「知道」它在那里。
     * 用于让感知更符合真实（不是透视）。</p>
     */
    public static boolean hasLineOfSight(ServerLevel level, Vec3 from, Vec3 to) {
        Vec3 dir = to.subtract(from);
        double dist = dir.length();
        if (dist < 1.0E-4) {
            return true;
        }
        Vec3 step = dir.normalize().scale(0.5);
        Vec3 cur = from;
        int steps = (int) (dist / 0.5);
        for (int i = 0; i < steps; i++) {
            cur = cur.add(step);
            BlockPos p = BlockPos.containing(cur);
            if (!level.isLoaded(p)) {
                return false;
            }
            BlockState s = level.getBlockState(p);
            if (!s.isAir() && s.isSolidRender(level, p)) {
                return false;
            }
        }
        return true;
    }
}
