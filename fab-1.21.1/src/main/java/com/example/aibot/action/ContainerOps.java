package com.example.aibot.action;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.AbstractFurnaceMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 原版 GUI 操作层 —— 让假玩家像真人一样「打开界面、摆材料、取成品」。
 *
 * <p><b>为什么必须有这一层</b></p>
 *
 * <p>旧实现直接操作 {@code Container} 对象（往箱子塞东西用 {@code setItem}，
 * 合成用「查配方 → 直接往背包塞成品」）。这等于给假玩家开后台：
 * 不用走到工作台前、不用打开界面、不用摆材料。结果虽一样，
 * 但过程与真人完全不同。</p>
 *
 * <p><b>本层的做法：走真人客户端的数据包路径</b></p>
 *
 * <p>真人点击界面时，客户端发 {@code ServerboundContainerClickPacket}，
 * 服务端由 {@code ServerGamePacketListenerImpl.handleContainerClick(...)} 处理。
 * 本层直接构造并调用同一条路径：</p>
 *
 * <pre>
 *   rightClickBlock()    → gameMode.useItemOn  → 原版 openMenu   （等同真人右键）
 *   clickSlot()          → ServerboundContainerClickPacket       （等同真人点鼠标）
 *                        → listener.handleContainerClick(...)
 *   close()              → closeContainer()                      （等同真人按 Esc）
 * </pre>
 *
 * <p>这样服务端看到的字节流/调用序列与真人在该界面上操作完全一致，
 * 任何容器插件、反作弊、日志系统都无法区分。</p>
 *
 * <p><b>槽位编号约定</b>（原版规则）：</p>
 * <pre>
 *   0 .. N-1       容器自己的槽位（箱子 0-26；熔炉 0=原料 1=燃料 2=产物；
 *                            工作台 0=成品 1-9=3x3 网格）
 *   N .. N+26      玩家主背包
 *   N+27 .. N+35   玩家快捷栏
 *   -999           界面外（丢弃）
 * </pre>
 */
public final class ContainerOps {

    private static final Logger LOGGER = Logger.getLogger("aibot-gui");

    /** 原版「界面外」槽位编号。 */
    public static final int SLOT_OUTSIDE = -999;

    /** 玩家背包固定占用的槽位数（27 主背包 + 9 快捷栏）。 */
    public static final int PLAYER_SLOTS = 36;

    private ContainerOps() {
    }

    // ==================================================================
    // 点击原语（走真人数据包路径）
    // ==================================================================

    /**
     * 在指定槽位上点一下 —— <b>构造并投递原版点击数据包</b>。
     *
     * <p>这是本类的核心：它模拟的正是真人客户端在 GUI 里点击时
     * 发往服务端的那一个数据包，因此服务端执行的是完整原版逻辑
     * （槽位校验、堆叠上限、能否拿取、容器锁等）。</p>
     *
     * @param player 假玩家
     * @param menu   当前打开的界面
     * @param slot   槽位编号（-999 表示界面外）
     * @param button 0=左键，1=右键
     * @param type   点击类型（PICKUP / QUICK_MOVE / SWAP / THROW ...）
     */
    public static void click(ServerPlayer player, AbstractContainerMenu menu,
                             int slot, int button, ClickType type) {
        try {
            // 记录点击前的各槽位内容，作为「变更集」上报（原版协议要求）
            it.unimi.dsi.fastutil.ints.Int2ObjectMap changed =
                    new it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap();
            for (int i = 0; i < menu.slots.size(); i++) {
                Slot s = menu.getSlot(i);
                if (s != null && s.hasItem()) {
                    changed.put(i, s.getItem().copy());
                }
            }

            var packet = new net.minecraft.network.protocol.game.ServerboundContainerClickPacket(
                    menu.containerId,
                    menu.getStateId(),
                    (short) slot,
                    (byte) button,
                    type,
                    menu.getCarried().copy(),
                    changed);

            // 投递给服务端处理 —— 与真人客户端发来的包走同一个方法
            var listener = player.connection;
            if (listener != null) {
                listener.handleContainerClick(packet);
            } else {
                // 极端情况下没有连接对象：退化为直接调用 menu.clicked
                // （clicked 是 public，是 doClick 的入口）
                menu.clicked(slot, button, type, player);
                menu.broadcastChanges();
            }
        } catch (Throwable t) {
            LOGGER.log(Level.WARNING, "[AIBot] GUI 点击失败 slot=" + slot + " type=" + type, t);
            // 兜底：至少尝试公开入口，保证功能不中断
            try {
                menu.clicked(slot, button, type, player);
                menu.broadcastChanges();
            } catch (Throwable ignored) {
            }
        }
    }

    /** 左键点一下槽位（拿整摞 / 放下）。 */
    public static void leftClick(ServerPlayer player, AbstractContainerMenu menu, int slot) {
        click(player, menu, slot, 0, ClickType.PICKUP);
    }

    /** 右键点一下槽位（拿一个 / 放一个 / 对半分）。 */
    public static void rightClick(ServerPlayer player, AbstractContainerMenu menu, int slot) {
        click(player, menu, slot, 1, ClickType.PICKUP);
    }

    /** Shift + 点击（原版 QUICK_MOVE）：在容器与背包之间整摞搬运。 */
    public static void quickMove(ServerPlayer player, AbstractContainerMenu menu, int slot) {
        click(player, menu, slot, 0, ClickType.QUICK_MOVE);
    }

    /** 把光标上拿着的东西丢到界面外（清理光标，避免后续点击错位）。 */
    public static void dropCarried(ServerPlayer player, AbstractContainerMenu menu) {
        if (!menu.getCarried().isEmpty()) {
            click(player, menu, SLOT_OUTSIDE, 0, ClickType.PICKUP);
        }
    }

    // ==================================================================
    // 打开 / 关闭容器
    // ==================================================================

    /**
     * 右键一个方块 —— 等同真人把准心对准方块点右键。
     *
     * <p>走 {@code gameMode.useItemOn}，因此触发原版全套校验：
     * 距离、方块能否打开、权限插件是否拦截。</p>
     */
    public static boolean rightClickBlock(ServerPlayer player, BlockPos pos) {
        ServerLevel level = (ServerLevel) player.level();
        Vec3 hitVec = new Vec3(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
        // 朝向选上表面：与原版准心对准方块中心时最常见的结果一致
        BlockHitResult hit = new BlockHitResult(hitVec, net.minecraft.core.Direction.UP, pos, false);
        try {
            player.gameMode.useItemOn(player, level, player.getMainHandItem(),
                    net.minecraft.world.InteractionHand.MAIN_HAND, hit);
            return true;
        } catch (Throwable t) {
            LOGGER.log(Level.WARNING, "[AIBot] 右键方块失败 " + pos, t);
            return false;
        }
    }

    /**
     * 打开指定坐标上的容器界面。
     *
     * @return 成功返回菜单对象；失败返回 null
     */
    public static AbstractContainerMenu openContainerAt(ServerPlayer player, BlockPos pos) {
        if (player.containerMenu != player.inventoryMenu) {
            close(player);
        }
        rightClickBlock(player, pos);
        AbstractContainerMenu menu = player.containerMenu;
        if (menu == null || menu == player.inventoryMenu) {
            return null;   // 没打开（被阻挡/无权限/距离太远）
        }
        return menu;
    }

    /** 关闭当前界面 —— 等同真人按 Esc。 */
    public static void close(ServerPlayer player) {
        try {
            if (player.containerMenu != player.inventoryMenu) {
                AbstractContainerMenu menu = player.containerMenu;
                // 先把光标上的东西放回背包，否则关闭时物品会消失
                returnCarriedToInventory(player, menu);
                player.closeContainer();
            }
        } catch (Throwable t) {
            LOGGER.log(Level.WARNING, "[AIBot] 关闭界面异常", t);
        }
    }

    /** 把光标物品放回背包（真人也会先放下再关）。 */
    private static void returnCarriedToInventory(ServerPlayer player, AbstractContainerMenu menu) {
        try {
            if (menu.getCarried().isEmpty()) {
                return;
            }
            int size = menu.slots.size();
            for (int i = size - PLAYER_SLOTS; i < size; i++) {
                if (!menu.getSlot(i).hasItem()) {
                    leftClick(player, menu, i);
                    if (menu.getCarried().isEmpty()) {
                        return;
                    }
                }
            }
            // 背包满了：丢到地上（与真人表现一致）
            while (!menu.getCarried().isEmpty()) {
                click(player, menu, SLOT_OUTSIDE, 0, ClickType.PICKUP);
            }
        } catch (Throwable t) {
            LOGGER.log(Level.WARNING, "[AIBot] 清理光标物品失败", t);
        }
    }

    // ==================================================================
    // 槽位辅助
    // ==================================================================

    /** 容器槽位的结束位置（不含）。原版：玩家背包固定占最后 36 格。 */
    public static int containerSlotEnd(AbstractContainerMenu menu) {
        return Math.max(0, menu.slots.size() - PLAYER_SLOTS);
    }

    /** 玩家背包的起始槽位。 */
    public static int playerSlotStart(AbstractContainerMenu menu) {
        return Math.max(0, menu.slots.size() - PLAYER_SLOTS);
    }

    /** 在指定范围里找装着指定物品的槽位；itemId 为空表示任意非空槽。 */
    public static int findSlotWithItem(AbstractContainerMenu menu, String itemId, int from, int to) {
        for (int i = from; i < to && i < menu.slots.size(); i++) {
            ItemStack s = menu.getSlot(i).getItem();
            if (s.isEmpty()) {
                continue;
            }
            if (itemId == null || itemId.isEmpty()) {
                return i;
            }
            if (matches(s, itemId)) {
                return i;
            }
        }
        return -1;
    }

    /** 在玩家背包里找指定物品。 */
    public static int findInPlayerInventory(AbstractContainerMenu menu, String itemId) {
        return findSlotWithItem(menu, itemId, playerSlotStart(menu), menu.slots.size());
    }

    /** 在指定范围里找空槽位。 */
    public static int findEmptySlot(AbstractContainerMenu menu, int from, int to) {
        for (int i = from; i < to && i < menu.slots.size(); i++) {
            if (!menu.getSlot(i).hasItem()) {
                return i;
            }
        }
        return -1;
    }

    /** 物品是否匹配给定 ID（自动补 minecraft: 前缀）。 */
    private static boolean matches(ItemStack stack, String itemId) {
        if (stack.isEmpty() || itemId == null || itemId.isEmpty()) {
            return false;
        }
        String id = net.minecraft.core.registries.BuiltInRegistries.ITEM
                .getKey(stack.getItem()).toString();
        return id.equals(itemId) || id.equals("minecraft:" + itemId);
    }

    // ==================================================================
    // 高层搬运
    // ==================================================================

    /**
     * 把玩家背包里的指定物品搬进容器（真人：打开箱子 → shift 点击）。
     *
     * @param limit 最多搬几个（&le;0 表示不限）
     * @return 实际搬走的数量
     */
    public static int putIntoContainer(ServerPlayer player, AbstractContainerMenu menu,
                                       String itemId, int limit) {
        int moved = 0;
        int pStart = playerSlotStart(menu);
        int size = menu.slots.size();
        // 从后往前扫：避免搬运过程中索引位移导致跳格
        for (int i = size - 1; i >= pStart; i--) {
            if (limit > 0 && moved >= limit) {
                break;
            }
            ItemStack s = menu.getSlot(i).getItem();
            if (s.isEmpty()) {
                continue;
            }
            if (itemId != null && !itemId.isEmpty() && !matches(s, itemId)) {
                continue;
            }
            int before = s.getCount();
            quickMove(player, menu, i);
            ItemStack after = menu.getSlot(i).getItem();
            moved += before - (after.isEmpty() ? 0 : after.getCount());
        }
        return moved;
    }

    /**
     * 从容器取出指定物品到背包（真人：打开箱子 → shift 点击）。
     */
    public static int takeFromContainer(ServerPlayer player, AbstractContainerMenu menu,
                                        String itemId, int limit) {
        int taken = 0;
        int cEnd = containerSlotEnd(menu);
        for (int i = 0; i < cEnd; i++) {
            if (limit > 0 && taken >= limit) {
                break;
            }
            ItemStack s = menu.getSlot(i).getItem();
            if (s.isEmpty()) {
                continue;
            }
            if (itemId != null && !itemId.isEmpty() && !matches(s, itemId)) {
                continue;
            }
            int before = s.getCount();
            quickMove(player, menu, i);
            ItemStack after = menu.getSlot(i).getItem();
            taken += before - (after.isEmpty() ? 0 : after.getCount());
        }
        return taken;
    }

    // ==================================================================
    // 熔炉
    // ==================================================================

    /**
     * 往熔炉里放料（原料 + 燃料），逐格操作，与真人一致。
     *
     * <p>真人：开熔炉 → 把矿石放到上方格 → 把煤放到下方格 → 关界面等。</p>
     *
     * @return 操作描述
     */
    public static String stockFurnace(ServerPlayer player, AbstractContainerMenu menu,
                                      String inputId, int inputCount, String fuelId, int fuelCount) {
        StringBuilder sb = new StringBuilder();

        // ---- 原料 ----
        if (menu.getSlot(AbstractFurnaceMenu.INGREDIENT_SLOT).getItem().isEmpty()) {
            int slot = findInPlayerInventory(menu, inputId);
            if (slot < 0) {
                return "背包里没有 " + shortId(inputId);
            }
            movePartially(player, menu, slot, AbstractFurnaceMenu.INGREDIENT_SLOT, inputCount);
            sb.append("放入原料 ").append(shortId(inputId)).append(" x").append(inputCount);
        } else {
            sb.append("原料格已有物品，跳过");
        }

        // ---- 燃料 ----
        sb.append("；");
        if (menu.getSlot(AbstractFurnaceMenu.FUEL_SLOT).getItem().isEmpty()) {
            int slot = findInPlayerInventory(menu, fuelId);
            String usedName = fuelId;
            if (slot < 0) {
                slot = findAnyFuelSlot(player, menu);
                if (slot >= 0) {
                    usedName = net.minecraft.core.registries.BuiltInRegistries.ITEM
                            .getKey(menu.getSlot(slot).getItem().getItem()).toString();
                }
            }
            if (slot >= 0) {
                movePartially(player, menu, slot, AbstractFurnaceMenu.FUEL_SLOT,
                        Math.max(1, fuelCount));
                sb.append("放入燃料 ").append(shortId(usedName)).append(" x")
                        .append(Math.max(1, fuelCount));
            } else {
                sb.append("背包里没有可用燃料");
            }
        } else {
            sb.append("燃料格已有物品，跳过");
        }

        return sb.toString();
    }

    /**
     * 从背包槽位向目标槽位移动「指定数量」的物品。
     *
     * <p>真人做法：左键拿起整摞 → 右键目标格逐个放 → 剩余放回原格。
     * 这里用完全相同的点击序列，因此堆叠上限等校验都由原版完成。</p>
     */
    public static void movePartially(ServerPlayer player, AbstractContainerMenu menu,
                                     int fromSlot, int toSlot, int count) {
        ItemStack src = menu.getSlot(fromSlot).getItem();
        if (src.isEmpty()) {
            return;
        }
        if (count <= 0 || count >= src.getCount()) {
            quickMove(player, menu, fromSlot);
            return;
        }
        leftClick(player, menu, fromSlot);              // 拿起整摞
        for (int i = 0; i < count; i++) {
            if (menu.getCarried().isEmpty()) {
                break;
            }
            rightClick(player, menu, toSlot);           // 逐个放到目标格
        }
        if (!menu.getCarried().isEmpty()) {
            leftClick(player, menu, fromSlot);          // 剩余放回
        }
    }

    /** 在背包里找任意可燃物（未指定燃料时的兜底）。 */
    private static int findAnyFuelSlot(ServerPlayer player, AbstractContainerMenu menu) {
        int pStart = playerSlotStart(menu);
        for (int i = pStart; i < menu.slots.size(); i++) {
            ItemStack s = menu.getSlot(i).getItem();
            if (s.isEmpty()) {
                continue;
            }
            try {
                // 原版燃料判定（1.21.1 用静态燃料表 getFuel()）
                Integer burn = net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity
                        .getFuel().get(s.getItem());
                if (burn != null && burn > 0) {
                    return i;
                }
            } catch (Throwable ignored) {
                // 拿不到燃料表：放弃自动挑选，由调用方指定燃料
            }
        }
        return -1;
    }

    /** 从熔炉取出成品（真人：点产物格拿走）。 */
    public static int collectFurnaceOutput(ServerPlayer player, AbstractContainerMenu menu) {
        ItemStack out = menu.getSlot(AbstractFurnaceMenu.RESULT_SLOT).getItem();
        if (out.isEmpty()) {
            return 0;
        }
        int before = out.getCount();
        quickMove(player, menu, AbstractFurnaceMenu.RESULT_SLOT);
        ItemStack after = menu.getSlot(AbstractFurnaceMenu.RESULT_SLOT).getItem();
        return before - (after.isEmpty() ? 0 : after.getCount());
    }

    /** 熔炉是否还在工作（原料与燃料格都有东西）。 */
    public static boolean furnaceStillWorking(AbstractContainerMenu menu) {
        return !menu.getSlot(AbstractFurnaceMenu.INGREDIENT_SLOT).getItem().isEmpty()
                && !menu.getSlot(AbstractFurnaceMenu.FUEL_SLOT).getItem().isEmpty();
    }

    // ==================================================================
    // 方块查找
    // ==================================================================

    /** 找附近的容器方块（箱子/木桶/潜影盒）。 */
    public static BlockPos findNearbyContainer(ServerLevel level, BlockPos center, int radius) {
        return findNearby(level, center, radius, p -> {
            BlockEntity be = level.getBlockEntity(p);
            return be instanceof ChestBlockEntity
                    || be instanceof BarrelBlockEntity
                    || be instanceof ShulkerBoxBlockEntity;
        });
    }

    /** 找附近的工作台。 */
    public static BlockPos findNearbyCraftingTable(ServerLevel level, BlockPos center, int radius) {
        return findNearby(level, center, radius, p ->
                level.getBlockState(p).is(net.minecraft.world.level.block.Blocks.CRAFTING_TABLE));
    }

    /** 找附近的熔炉（熔炉/高炉/烟熏炉）。 */
    public static BlockPos findNearbyFurnace(ServerLevel level, BlockPos center, int radius) {
        return findNearby(level, center, radius, p -> {
            var st = level.getBlockState(p);
            return st.is(net.minecraft.world.level.block.Blocks.FURNACE)
                    || st.is(net.minecraft.world.level.block.Blocks.BLAST_FURNACE)
                    || st.is(net.minecraft.world.level.block.Blocks.SMOKER);
        });
    }

    /** 通用：按谓词找最近的方块。 */
    private static BlockPos findNearby(ServerLevel level, BlockPos center, int radius,
                                       java.util.function.Predicate<BlockPos> test) {
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        for (BlockPos p : BlockPos.betweenClosed(
                center.offset(-radius, -radius, -radius),
                center.offset(radius, radius, radius))) {
            if (!level.isLoaded(p)) {
                continue;
            }
            if (!test.test(p)) {
                continue;
            }
            double d = p.distSqr(center);
            if (d < bestDist) {
                bestDist = d;
                best = p.immutable();
            }
        }
        return best;
    }

    /** 简短 ID（去掉 minecraft: 前缀）。 */
    public static String shortId(String id) {
        if (id == null) {
            return "";
        }
        return id.startsWith("minecraft:") ? id.substring("minecraft:".length()) : id;
    }

    /** 玩家背包在 InventoryMenu 里的槽位起始（用于背包内拖动）。 */
    public static int inventoryMenuPlayerStart() {
        return InventoryMenu.INV_SLOT_START;
    }
}
