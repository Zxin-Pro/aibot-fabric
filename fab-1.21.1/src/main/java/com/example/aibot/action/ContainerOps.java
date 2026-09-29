package com.example.aibot.action;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.AbstractFurnaceMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 原版 GUI 操作层 —— 让假玩家像真人一样「打开界面、摆材料、取成品」。
 *
 * <p><b>为什么必须有这一层</b></p>
 *
 * <p>旧实现是直接操作 {@code Container} 对象：往箱子里塞物品用的是
 * {@code container.setItem(...)}，合成用的是「查配方 → 直接往背包里塞成品」。
 * 这等于给假玩家开了个后门：不用走到工作台前、不用打开界面、不用摆材料，
 * 结果虽然一样，但<b>过程与真人完全不同</b>。</p>
 *
 * <p>本层改成走原版路径：</p>
 * <ol>
 *   <li>右键方块（{@code gameMode.useItemOn}）→ 触发原版 {@code openMenu}</li>
 *   <li>在原版 {@code AbstractContainerMenu} 上执行 {@code doClick(...)}，
 *       —— 这就是服务端处理「客户端点了一下鼠标」的入口，
 *       包含全套原版校验（槽位是否有效、能否拿取、堆叠上限等）</li>
 *   <li>关闭界面（{@code closeContainer()}）</li>
 * </ol>
 *
 * <p><b>等价性说明</b>：{@code doClick} 是原版 {@code ServerboundContainerClickPacket}
 * 的处理终点。走它等于「有一个真实客户端在点这个界面」，
 * 因此任何反作弊插件、容器锁插件看到的轨迹都与真人一致。</p>
 *
 * <p><b>槽位编号约定</b>（原版规则，所有容器通用）：</p>
 * <pre>
 *   0 .. N-1         容器自己的槽位（箱子 0-26；熔炉 0=原料 1=燃料 2=产物；
 *                              工作台 0-8=合成格，0 号即成品格）
 *   N .. N+26        玩家主背包 27 格
 *   N+27 .. N+35     玩家快捷栏 9 格
 *   特殊：-999 表示「界面外」，用于丢弃
 * </pre>
 *
 * <p><b>版本说明</b>：本类是「纯逻辑」，不直接引用任何版本特有的构造签名，
 * 所有对 MC 的调用都限制在上述稳定 API 上，因此 8 条版本线可共用同一份。
 * 若某版本签名有变，只需改 {@link AIBotPlayerFacade} 的适配实现。</p>
 */
public final class ContainerOps {

    private static final Logger LOGGER = Logger.getLogger("aibot-gui");

    /** 原版「界面外」槽位编号，用于把物品丢出界面。 */
    public static final int SLOT_OUTSIDE = -999;

    private ContainerOps() {
    }

    // ==================================================================
    // 基本点击原语
    // ==================================================================

    /**
     * 在指定槽位上点一下（左键 PICKUP）。
     *
     * <p>语义与真人完全一致：鼠标指向该槽位点一次左键。
     * 空手时是「拿起整摞」，手上有东西时是「放下/交换」。</p>
     *
     * @param player 假玩家
     * @param menu   当前打开的界面
     * @param slot   槽位编号
     * @param button 0=左键，1=右键（右键是单个/对半）
     */
    public static void clickSlot(ServerPlayer player, AbstractContainerMenu menu,
                                 int slot, int button) {
        menu.doClick(slot, button, ClickType.PICKUP, player);
        menu.broadcastChanges();
    }

    /** 左键点一下槽位。 */
    public static void leftClick(ServerPlayer player, AbstractContainerMenu menu, int slot) {
        clickSlot(player, menu, slot, 0);
    }

    /** 右键点一下槽位（单个/对半）。 */
    public static void rightClick(ServerPlayer player, AbstractContainerMenu menu, int slot) {
        clickSlot(player, menu, slot, 1);
    }

    /**
     * Shift + 点击（原版 QUICK_MOVE）：把整摞在容器与背包之间移动。
     *
     * <p>这是最高效也最像真人的搬运方式 —— 真人取一摞东西也是 shift 点。</p>
     */
    public static void quickMove(ServerPlayer player, AbstractContainerMenu menu, int slot) {
        menu.doClick(slot, 0, ClickType.QUICK_MOVE, player);
        menu.broadcastChanges();
    }

    /**
     * 把鼠标上「拿着的东西」（光标物品）丢到界面外。
     *
     * <p>用于清理光标，避免残留物品卡在光标上导致后续点击错位。</p>
     */
    public static void dropCarried(ServerPlayer player, AbstractContainerMenu menu) {
        if (!menu.getCarried().isEmpty()) {
            menu.doClick(SLOT_OUTSIDE, 0, ClickType.PICKUP, player);
            menu.broadcastChanges();
        }
    }

    // ==================================================================
    // 打开 / 关闭容器
    // ==================================================================

    /**
     * 右键一个方块，像真人那样打开它的界面。
     *
     * <p>走 {@code gameMode.useItemOn}，因此会触发原版全套校验：
     * 距离是否够、方块是否真的能开、是否被权限插件拦截等。</p>
     *
     * @param player 假玩家
     * @param pos    目标方块坐标
     * @return 成功触发返回 true（界面是否真的打开需另外检查）
     */
    public static boolean rightClickBlock(ServerPlayer player, BlockPos pos) {
        ServerLevel level = (ServerLevel) player.level();
        // 命中点取方块中心：与原版客户端准心对准方块中心时的结果一致
        Vec3 hitVec = new Vec3(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
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
     * 打开指定坐标上的容器界面（右键它，等待原版把 menu 挂上来）。
     *
     * @return 打开成功返回 menu，失败返回 null
     */
    public static AbstractContainerMenu openContainerAt(ServerPlayer player, BlockPos pos) {
        if (player.containerMenu != player.inventoryMenu) {
            player.closeContainer();
        }
        rightClickBlock(player, pos);
        AbstractContainerMenu menu = player.containerMenu;
        if (menu == null || menu == player.inventoryMenu) {
            return null;
        }
        return menu;
    }

    /** 关闭当前界面（等同真人按 Esc）。 */
    public static void close(ServerPlayer player) {
        try {
            if (player.containerMenu != player.inventoryMenu) {
                // 关闭前先把手上的东西放回背包，避免物品凭空消失
                AbstractContainerMenu menu = player.containerMenu;
                dropCarriedToInventory(player, menu);
                player.closeContainer();
            }
        } catch (Throwable t) {
            LOGGER.log(Level.WARNING, "[AIBot] 关闭界面异常", t);
        }
    }

    /** 把光标上的物品放回背包（优先 shift 到玩家栏）。 */
    private static void dropCarriedToInventory(ServerPlayer player, AbstractContainerMenu menu) {
        try {
            if (menu.getCarried().isEmpty()) {
                return;
            }
            // 找玩家背包里的空槽，把光标物品放进去
            int size = menu.slots.size();
            for (int i = size - 36; i < size; i++) {
                Slot s = menu.getSlot(i);
                if (!s.hasItem()) {
                    menu.doClick(i, 0, ClickType.PICKUP, player);
                    menu.broadcastChanges();
                    if (menu.getCarried().isEmpty()) {
                        return;
                    }
                }
            }
            // 没有空位：直接丢到地上（和真人背包满了的表现一致）
            while (!menu.getCarried().isEmpty()) {
                menu.doClick(SLOT_OUTSIDE, 0, ClickType.PICKUP, player);
                menu.broadcastChanges();
            }
        } catch (Throwable t) {
            LOGGER.log(Level.WARNING, "[AIBot] 清理光标物品失败", t);
        }
    }

    // ==================================================================
    // 槽位查找辅助
    // ==================================================================

    /**
     * 在界面里找一个「装着指定物品」的槽位。
     *
     * @param menu   界面
     * @param itemId 物品 ID，空格表示任意非空物品
     * @param from   起始槽位（含）
     * @param to     结束槽位（不含）
     * @return 找到返回槽位号，否则 -1
     */
    public static int findSlotWithItem(AbstractContainerMenu menu, String itemId,
                                       int from, int to) {
        for (int i = from; i < to && i < menu.slots.size(); i++) {
            ItemStack s = menu.getSlot(i).getItem();
            if (s.isEmpty()) {
                continue;
            }
            if (itemId == null || itemId.isEmpty()) {
                return i;
            }
            String id = net.minecraft.core.registries.BuiltInRegistries.ITEM
                    .getKey(s.getItem()).toString();
            if (id.equals(itemId) || id.equals("minecraft:" + itemId)) {
                return i;
            }
        }
        return -1;
    }

    /** 在指定范围内找空槽位。 */
    public static int findEmptySlot(AbstractContainerMenu menu, int from, int to) {
        for (int i = from; i < to && i < menu.slots.size(); i++) {
            if (!menu.getSlot(i).hasItem()) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 找出这个界面里「容器部分的槽位范围」。
     *
     * <p>原版规则：玩家背包固定占据最后 36 个槽位，
     * 因此容器槽位是 {@code [0, size-36)}。</p>
     */
    public static int containerSlotStart() {
        return 0;
    }

    /** 容器槽位结束位置（不含）。 */
    public static int containerSlotEnd(AbstractContainerMenu menu) {
        return Math.max(0, menu.slots.size() - 36);
    }

    /** 玩家背包的起始槽位。 */
    public static int playerSlotStart(AbstractContainerMenu menu) {
        return Math.max(0, menu.slots.size() - 36);
    }

    // ==================================================================
    // 高层操作：搬运 / 取出 / 存入
    // ==================================================================

    /**
     * 把玩家背包里的指定物品搬进容器（等同真人打开箱子 → shift 点击）。
     *
     * @param limit 最多搬多少个（0 或负数表示不限）
     * @return 实际搬走的数量
     */
    public static int putIntoContainer(ServerPlayer player, AbstractContainerMenu menu,
                                       String itemId, int limit) {
        int moved = 0;
        int pStart = playerSlotStart(menu);
        int size = menu.slots.size();
        // 从后往前扫，避免边搬边改索引导致跳格
        for (int i = size - 1; i >= pStart; i--) {
            if (limit > 0 && moved >= limit) {
                break;
            }
            ItemStack s = menu.getSlot(i).getItem();
            if (s.isEmpty()) {
                continue;
            }
            if (itemId != null && !itemId.isEmpty()) {
                String id = net.minecraft.core.registries.BuiltInRegistries.ITEM
                        .getKey(s.getItem()).toString();
                if (!id.equals(itemId) && !id.equals("minecraft:" + itemId)) {
                    continue;
                }
            }
            int before = s.getCount();
            quickMove(player, menu, i);
            // quickMove 之后原槽位数量减少（或被清空）
            ItemStack after = menu.getSlot(i).getItem();
            moved += before - (after.isEmpty() ? 0 : after.getCount());
            if (limit > 0 && moved >= limit) {
                break;
            }
        }
        return moved;
    }

    /**
     * 从容器里取出指定物品到玩家背包（等同真人打开箱子 → shift 点击）。
     *
     * @param limit 最多取多少个
     * @return 实际取出的数量
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
            if (itemId != null && !itemId.isEmpty()) {
                String id = net.minecraft.core.registries.BuiltInRegistries.ITEM
                        .getKey(s.getItem()).toString();
                if (!id.equals(itemId) && !id.equals("minecraft:" + itemId)) {
                    continue;
                }
            }
            int before = s.getCount();
            quickMove(player, menu, i);
            ItemStack after = menu.getSlot(i).getItem();
            taken += before - (after.isEmpty() ? 0 : after.getCount());
            if (limit > 0 && taken >= limit) {
                break;
            }
        }
        return taken;
    }

    /**
     * 从玩家背包指定槽位取出一整摞物品（用于给熔炉放料）。
     *
     * @return 实际取出的数量
     */
    public static int takeFromPlayerSlot(ServerPlayer player, AbstractContainerMenu menu, int slot) {
        if (slot < 0 || slot >= menu.slots.size()) {
            return 0;
        }
        ItemStack s = menu.getSlot(slot).getItem();
        if (s.isEmpty()) {
            return 0;
        }
        int before = s.getCount();
        quickMove(player, menu, slot);
        ItemStack after = menu.getSlot(slot).getItem();
        return before - (after.isEmpty() ? 0 : after.getCount());
    }

    /**
     * 在玩家背包（含快捷栏）里找指定物品的槽位。
     *
     * @return 界面里的槽位号，找不到返回 -1
     */
    public static int findInPlayerInventory(AbstractContainerMenu menu, String itemId) {
        int pStart = playerSlotStart(menu);
        return findSlotWithItem(menu, itemId, pStart, menu.slots.size());
    }

    // ==================================================================
    // 熔炉专用
    // ==================================================================

    /** 熔炉界面索引常量（与原版 AbstractFurnaceMenu 一致）。 */
    public static final int FURNACE_INPUT = AbstractFurnaceMenu.INGREDIENT_SLOT;
    public static final int FURNACE_FUEL = AbstractFurnaceMenu.FUEL_SLOT;
    public static final int FURNACE_RESULT = AbstractFurnaceMenu.RESULT_SLOT;

    /**
     * 往熔炉里放料（原料 + 燃料），像真人一样逐格放。
     *
     * <p>真人操作：开熔炉 → 把矿石拖到上方格 → 把煤拖到下方格 → 关界面等。</p>
     *
     * @return 放料结果描述
     */
    public static String stockFurnace(ServerPlayer player, AbstractContainerMenu menu,
                                      String inputId, int inputCount, String fuelId, int fuelCount) {
        StringBuilder sb = new StringBuilder();

        // ---- 原料 ----
        if (menu.getSlot(FURNACE_INPUT).getItem().isEmpty()) {
            int slot = findInPlayerInventory(menu, inputId);
            if (slot < 0) {
                return "背包里没有 " + shortId(inputId);
            }
            // 真人也是把整摞放进去，烧不完的留着
            moveStackPartially(player, menu, slot, FURNACE_INPUT, inputCount);
            sb.append("放入原料 ").append(shortId(inputId)).append(" x")
                    .append(inputCount).append("；");
        } else {
            sb.append("原料格已有物品，跳过；");
        }

        // ---- 燃料 ----
        if (menu.getSlot(FURNACE_FUEL).getItem().isEmpty()) {
            int slot = findInPlayerInventory(menu, fuelId);
            if (slot < 0) {
                // 燃料没指定或找不到：尝试用任何可燃物
                slot = findAnyFuelSlot(player, menu);
            }
            if (slot >= 0) {
                ItemStack fuelStack = menu.getSlot(slot).getItem();
                moveStackPartially(player, menu, slot, FURNACE_FUEL, fuelCount);
                sb.append("放入燃料 ").append(shortId(
                        net.minecraft.core.registries.BuiltInRegistries.ITEM
                                .getKey(fuelStack.getItem()).toString()))
                        .append(" x").append(fuelCount);
            } else {
                sb.append("背包里没有可用燃料");
            }
        } else {
            sb.append("燃料格已有物品，跳过");
        }

        return sb.toString();
    }

    /**
     * 把某个背包槽位的物品「部分」移动到目标槽位。
     *
     * <p>真人做法：左键拿起整摞 → 右键目标格逐个放 → 剩余的放回原格。
     * 这里用同样的操作序列实现，确保与原版行为一致（含堆叠上限校验）。</p>
     */
    public static void moveStackPartially(ServerPlayer player, AbstractContainerMenu menu,
                                          int fromSlot, int toSlot, int count) {
        ItemStack src = menu.getSlot(fromSlot).getItem();
        if (src.isEmpty()) {
            return;
        }
        if (count <= 0 || count >= src.getCount()) {
            // 整摞搬：直接用 shift 点击最省事，且是真人常用操作
            quickMove(player, menu, fromSlot);
            return;
        }

        // 部分搬：真人用右键逐个放，这里照做
        // 1) 右键点源槽，拿起一半；连续右键会逐个拿起，这里用左键拿整摞更稳
        leftClick(player, menu, fromSlot);
        // 2) 光标现在拿着整摞，右键目标格 count 次
        for (int i = 0; i < count; i++) {
            if (menu.getCarried().isEmpty()) {
                break;
            }
            rightClick(player, menu, toSlot);
        }
        // 3) 剩余的放回原槽
        if (!menu.getCarried().isEmpty()) {
            leftClick(player, menu, fromSlot);
        }
    }

    /** 在玩家背包里找任意可燃物（用于「没指定燃料」时的兜底）。 */
    private static int findAnyFuelSlot(ServerPlayer player, AbstractContainerMenu menu) {
        int pStart = playerSlotStart(menu);
        for (int i = pStart; i < menu.slots.size(); i++) {
            ItemStack s = menu.getSlot(i).getItem();
            if (s.isEmpty()) {
                continue;
            }
            try {
                // 原版燃料判定：1.21.2+ 用 FuelValues.getBurnDuration(stack)
                // （旧版是 AbstractFurnaceBlockEntity.getFuel() 静态 Map，已移除）
                var level = (ServerLevel) player.level();
                var fuels = level.fuelValues();
                if (fuels.getBurnDuration(s) > 0) {
                    return i;
                }
            } catch (Throwable ignored) {
                // 拿不到燃料表时退化为「不自动挑燃料」，由调用方指定
            }
        }
        return -1;
    }

    /**
     * 从熔炉取出烧好的产物（等同真人点产物格拿走）。
     *
     * @return 取出的数量
     */
    public static int collectFurnaceOutput(ServerPlayer player, AbstractContainerMenu menu) {
        ItemStack out = menu.getSlot(FURNACE_RESULT).getItem();
        if (out.isEmpty()) {
            return 0;
        }
        int before = out.getCount();
        quickMove(player, menu, FURNACE_RESULT);
        ItemStack after = menu.getSlot(FURNACE_RESULT).getItem();
        return before - (after.isEmpty() ? 0 : after.getCount());
    }

    /** 熔炉是否还在烧（判断依据：原料格/燃料格有东西且产物格没满）。 */
    public static boolean furnaceStillWorking(AbstractContainerMenu menu) {
        boolean hasInput = !menu.getSlot(FURNACE_INPUT).getItem().isEmpty();
        boolean hasFuel = !menu.getSlot(FURNACE_FUEL).getItem().isEmpty();
        return hasInput && hasFuel;
    }

    // ==================================================================
    // 工作台专用
    // ==================================================================

    /**
     * 在工作台界面里按配方摆材料，然后取出成品。
     *
     * <p><b>与真人的等价性</b>：真人的操作是
     * 「把材料一个个拖进 3x3 网格 → 成品格出现结果 → 拖出来」。
     * 这里用完全相同的点击序列：</p>
     * <ol>
     *   <li>对每个需要的材料，从背包 shift/点击 到对应的合成格</li>
     *   <li>等原版自动算出成品（{@code CraftingMenu.slotsChanged} 会更新结果格）</li>
     *   <li>对成品格执行 shift 点击，把成品收进背包</li>
     *   <li>把网格里剩余的材料拿回背包（避免卡在工作台里）</li>
     * </ol>
     *
     * @param recipe 要合成的配方（由调用方从原版配方表里选出）
     * @param crafts 合成次数（攒着一次取多个成品，真人也会这么做）
     * @return 实际合成出的物品数量；-1 表示材料不足
     */
    public static int craftWithTable(ServerPlayer player, AbstractContainerMenu menu,
                                     RecipeHolder<? extends CraftingRecipe> recipe, int crafts) {
        if (!(menu instanceof CraftingMenu)) {
            return -1;
        }

        CraftingRecipe craft = recipe.value();
        // 原版配方自带摆放形状；这里用「按形状摆」的方式，最贴合真人观感
        return new CraftingPlacer(player, menu, craft).run(crafts);
    }

    // ==================================================================
    // 工具辅助
    // ==================================================================

    /** 简短 ID（去掉 minecraft: 前缀）。 */
    public static String shortId(String id) {
        if (id == null) {
            return "";
        }
        return id.startsWith("minecraft:") ? id.substring("minecraft:".length()) : id;
    }

    /** 找附近的容器方块（箱子/木桶/潜影盒等），供打开界面用。 */
    public static BlockPos findNearbyContainer(ServerLevel level, BlockPos center, int radius) {
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        for (BlockPos p : BlockPos.betweenClosed(
                center.offset(-radius, -radius, -radius),
                center.offset(radius, radius, radius))) {
            if (!level.isLoaded(p)) {
                continue;
            }
            BlockEntity be = level.getBlockEntity(p);
            if (be instanceof ChestBlockEntity
                    || be instanceof net.minecraft.world.level.block.entity.BarrelBlockEntity
                    || be instanceof net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity) {
                double d = p.distSqr(center);
                if (d < bestDist) {
                    bestDist = d;
                    best = p.immutable();
                }
            }
        }
        return best;
    }

    /** 找附近的工作台。 */
    public static BlockPos findNearbyCraftingTable(ServerLevel level, BlockPos center, int radius) {
        return findNearbyBlock(level, center, radius,
                net.minecraft.world.level.block.Blocks.CRAFTING_TABLE);
    }

    /** 找附近的熔炉类方块（熔炉/高炉/烟熏炉）。 */
    public static BlockPos findNearbyFurnace(ServerLevel level, BlockPos center, int radius) {
        BlockPos a = findNearbyBlock(level, center, radius, Blocks.FURNACE);
        if (a != null) {
            return a;
        }
        a = findNearbyBlock(level, center, radius, Blocks.BLAST_FURNACE);
        if (a != null) {
            return a;
        }
        return findNearbyBlock(level, center, radius, Blocks.SMOKER);
    }

    /** 通用：找附近指定方块。 */
    public static BlockPos findNearbyBlock(ServerLevel level, BlockPos center, int radius,
                                           net.minecraft.world.level.block.Block block) {
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        for (BlockPos p : BlockPos.betweenClosed(
                center.offset(-radius, -radius, -radius),
                center.offset(radius, radius, radius))) {
            if (!level.isLoaded(p)) {
                continue;
            }
            if (level.getBlockState(p).is(block)) {
                double d = p.distSqr(center);
                if (d < bestDist) {
                    bestDist = d;
                    best = p.immutable();
                }
            }
        }
        return best;
    }

    /**
     * 摆材料到合成网格（内部类，把「怎么摆」这件事独立出来）。
     *
     * <p>原版 {CraftingRecipe} 提供 {@code getIngredients()}（3x3 的有序列表）
     * 与 {@code getResultItem()}。摆放规则：</p>
     * <ul>
     *   <li>对每个有内容的 ingredient，找到对应网格位置（0-8），
     *       从背包取一个材料放进去</li>
     *   <li>配方不需要的格子保持空着</li>
     * </ul>
     */
    private static final class CraftingPlacer {
        private final ServerPlayer player;
        private final AbstractContainerMenu menu;
        private final CraftingRecipe recipe;

        CraftingPlacer(ServerPlayer player, AbstractContainerMenu menu, CraftingRecipe recipe) {
            this.player = player;
            this.menu = menu;
            this.recipe = recipe;
        }

        /** 执行摆放与取货。 */
        int run(int crafts) {
            List<Ingredient> ingredients = new ArrayList<>(recipe.getIngredients());
            // 原版 CraftingMenu 的合成格：槽位 1..9（0 号是成品格）
            int gridStart = CraftingMenu.CRAFT_SLOT_START;

            // ---- 逐格摆材料（一次只摆一份，之后靠 shift 取货批量合成）----
            for (int i = 0; i < ingredients.size() && i < 9; i++) {
                Ingredient ing = ingredients.get(i);
                if (ing == null || ing.isEmpty()) {
                    continue;   // 该格不需要放东西
                }
                int gridSlot = gridStart + i;
                if (placeOneInto(ing, gridSlot) < 0) {
                    // 材料不够：把已摆的收回，报告失败
                    clearGrid(gridStart);
                    return -1;
                }
            }

            // ---- 取出成品 ----
            int total = 0;
            int resultSlot = CraftingMenu.RESULT_SLOT;
            for (int c = 0; c < crafts; c++) {
                ItemStack result = menu.getSlot(resultSlot).getItem();
                if (result.isEmpty()) {
                    break;
                }
                int before = result.getCount();
                // shift 点击成品格：原版会一次做出「尽可能多」并塞进背包
                quickMove(player, menu, resultSlot);
                ItemStack after = menu.getSlot(resultSlot).getItem();
                int got = before - (after.isEmpty() ? 0 : after.getCount());
                if (got <= 0) {
                    break;
                }
                total += got;
            }

            // ---- 把网格里剩余材料收回背包（真人也会这么做，否则浪费）----
            clearGrid(gridStart);
            return total;
        }

        /**
         * 从背包取「一个」匹配的材料放进指定合成格。
         *
         * <p>真人做法是左键拿起一摞、右键放一个、再把剩下的放回去。
         * 这里用同样的序列。</p>
         *
         * @return 成功返回 0，失败返回 -1
         */
        private int placeOneInto(Ingredient ing, int gridSlot) {
            int pStart = playerSlotStart(menu);
            for (int i = pStart; i < menu.slots.size(); i++) {
                ItemStack s = menu.getSlot(i).getItem();
                if (s.isEmpty() || !ing.test(s)) {
                    continue;
                }
                if (s.getCount() == 1) {
                    // 只有一个：直接 shift 过去（原版会把整摞放进网格）
                    quickMove(player, menu, i);
                } else {
                    // 多个：拿整摞 → 右键放一个 → 剩余的放回
                    leftClick(player, menu, i);
                    rightClick(player, menu, gridSlot);
                    if (!menu.getCarried().isEmpty()) {
                        leftClick(player, menu, i);
                    }
                }
                // 确认格子里确实有东西了
                if (!menu.getSlot(gridSlot).getItem().isEmpty()) {
                    return 0;
                }
            }
            return -1;
        }

        /** 把合成网格里剩余的材料收回背包。 */
        private void clearGrid(int gridStart) {
            for (int i = 0; i < 9; i++) {
                int slot = gridStart + i;
                if (slot >= menu.slots.size()) {
                    break;
                }
                if (!menu.getSlot(slot).getItem().isEmpty()) {
                    quickMove(player, menu, slot);
                }
            }
        }
    }
}
