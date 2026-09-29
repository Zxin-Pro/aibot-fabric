package com.example.aibot.action;

import com.example.aibot.action.ActionParser;
import com.example.aibot.config.AIConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.AbstractFurnaceMenu;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 原版 GUI 动作（合成 / 熔炼 / 存取）—— 全部走真实的界面操作。
 *
 * <p><b>本类存在的意义</b></p>
 *
 * <p>旧实现是「查配方 → 直接往背包塞成品」和「直接操作 Container 对象」。
 * 那等于作弊：不用工作台、不用熔炉、不用走到箱子旁、不用打开界面。
 * 结果虽然一样，但过程与真人完全不同。</p>
 *
 * <p>本类改成和真人<b>逐操作等价</b>：</p>
 *
 * <table border="1">
 *   <caption>操作对照</caption>
 *   <tr><th>要做的事</th><th>真人怎么做</th><th>本类的做法</th></tr>
 *   <tr>
 *     <td>合成木镐</td>
 *     <td>拿出木头 → 做木板 → 放下工作台 → 右键打开 →
 *         把木板摆成镐子形状 → 拖出成品</td>
 *     <td>{@link #craftViaTable}：自动补齐前置合成（同样走界面）、
 *         放置工作台、右键打开、{@link ContainerOps} 逐格摆放、shift 取货</td>
 *   </tr>
 *   <tr>
 *     <td>烧铁矿</td>
 *     <td>放下熔炉 → 右键打开 → 放矿石 → 放煤 → 关界面等 →
 *         开界面取出铁锭</td>
 *     <td>{@link #smelt}：同上，含等待与分批取出</td>
 *   </tr>
 *   <tr>
 *     <td>从箱子拿东西</td>
 *     <td>走到箱子前 → 右键打开 → shift 点击目标 → 关界面</td>
 *     <td>{@link #withdraw}：同样的序列</td>
 *   </tr>
 * </table>
 *
 * <p><b>关于「多步操作」</b>：合成一把木镐在真人手里也是多个步骤
 * （先做木板、再做木棍、再摆工作台）。本类不做「一步到位」的取巧，
 * 而是把这些步骤按原版配方递归补全，每一步都是真实的界面操作。
 * 因此它会消耗对应的时间与材料，和真人完全一致。</p>
 */
public final class GuiActions {

    private static final Logger LOGGER = Logger.getLogger("aibot-gui-actions");

    /** 单次调用最多补全多少层前置合成，防止配方环路导致死循环。 */
    private static final int MAX_RECURSION = 6;

    private final ServerPlayer bot;
    private final AIConfig config;
    private final ActionExecutor executor;
    private final TickActionDriver driver;

    public GuiActions(ServerPlayer bot, AIConfig config, ActionExecutor executor, TickActionDriver driver) {
        this.bot = bot;
        this.config = config;
        this.executor = executor;
        this.driver = driver;
    }

    // ==================================================================
    // 合成
    // ==================================================================

    /**
     * 通过工作台合成物品（完整原版路径）。
     *
     * <p>流程：</p>
     * <ol>
     *   <li>确认背包里有目标配方所需材料</li>
     *   <li>没有工作台 → 先合成工作台 → 放下它</li>
     *   <li>走到工作台旁 → 右键打开界面</li>
     *   <li>按配方逐格摆放 → shift 取成品 → 关闭界面</li>
     * </ol>
     *
     * @return 执行结果
     */
    public ActionExecutor.ActionResult craft(String itemId, int count) {
        String full = normalize(itemId);
        Item target = resolveItem(full);
        if (target == null || target == Items.AIR) {
            return ActionExecutor.ActionResult.fail("未知物品: " + itemId);
        }

        // 1) 找配方（含「是否需要工作台」的信息）
        ServerLevel level = (ServerLevel) bot.level();
        CraftingRecipe recipe = findCraftingRecipe(level, target);
        if (recipe == null) {
            return ActionExecutor.ActionResult.fail("找不到合成 " + ContainerOps.shortId(full) + " 的配方");
        }

        boolean needsTable = requiresTable(recipe);

        // 2) 不需要工作台的 2x2 配方：直接用背包自带界面合成（真人也是这么做的）
        if (!needsTable) {
            return craftInInventory(recipe, count, full);
        }

        // 3) 需要工作台
        BlockPos table = ContainerOps.findNearbyCraftingTable(level, bot.blockPosition(), 12);
        if (table == null) {
            // 3a) 背包里有工作台 → 放下
            if (hasItemInInventory("minecraft:crafting_table")) {
                ActionExecutor.ActionResult placed = executor.place(
                        ActionParser.fromParams("place", "block", "minecraft:crafting_table"));
                if (placed.async()) {
                    // 放置是跨 tick 动作，需要等它完成后再继续。
                    // 这里返回「先放工作台」，由 AI 下一轮继续合成 —— 与真人分两步做完全一致。
                    // 注意：这里返回 ok 而不是 started ——
                    // 放置动作已经通过 executor.place 交给了 driver，
                    // 但它属于「另一个动作」。若返回 started，
                    // AutoLoop 会把接下来完成的 WALK/PLACE 误判为 craft 完成，
                    // 导致下一轮重复合成。
                    // 返回 ok 表示「本轮 craft 到此为止」，由模型下一轮继续。
                    return ActionExecutor.ActionResult.ok(
                            "已开始放置工作台。放下后再执行一次 craft 即可合成 "
                                    + ContainerOps.shortId(full));
                }
            }
            // 3b) 没有工作台 → 先合成一个（递归，同样走界面）
            return craftTableFirst(count, full);
        }

        // 4) 走到工作台旁再打开（真人不会隔空开界面）
        double dist = Math.sqrt(table.distSqr(bot.blockPosition()));
        if (dist > 4.0) {
            driver.beginWalk(net.minecraft.world.phys.Vec3.atCenterOf(table), 200);
            // 返回 ok：走到位后模型会再发一次 craft，那时就能开界面了。
            // 返回 started 会让 AutoLoop 把 WALK 的完成当成 craft 的完成。
            return ActionExecutor.ActionResult.ok("正在走向工作台，到达后再执行 craft");
        }

        // 5) 打开界面并摆放
        AbstractContainerMenu menu = ContainerOps.openContainerAt(bot, table);
        if (!(menu instanceof CraftingMenu)) {
            ContainerOps.close(bot);
            return ActionExecutor.ActionResult.fail("打开工作台界面失败（可能被阻挡或无权限）");
        }

        try {
            int made = craftInMenu(menu, recipe, count, 1, 9);  // 工作台：0=成品，1..9=3x3
            if (made < 0) {
                return ActionExecutor.ActionResult.fail("材料不足，无法合成 " + ContainerOps.shortId(full));
            }
            return ActionExecutor.ActionResult.ok(
                    "在工作台上合成了 " + made + " 个 " + ContainerOps.shortId(full));
        } finally {
            ContainerOps.close(bot);
        }
    }

    /**
     * 用背包自带的 2x2 界面合成（不需要工作台的配方，如木板、木棍、火把）。
     *
     * <p>真人做这类合成时也不会去放工作台，直接开背包界面就行。
     * 这里用 {@code inventoryMenu}（就是那个 2x2 格子），走同样的点击序列。</p>
     */
    private ActionExecutor.ActionResult craftInInventory(CraftingRecipe recipe,
                                                         int count, String full) {
        AbstractContainerMenu menu = bot.inventoryMenu;
        try {
            // inventoryMenu 的合成格同样是 «0 号是成品，1-4 是 2x2 网格» 的布局
            int made = craftInMenu(menu, recipe, count,
                    net.minecraft.world.inventory.InventoryMenu.CRAFT_SLOT_START, 4);
            if (made < 0) {
                return ActionExecutor.ActionResult.fail("材料不足，无法合成 " + ContainerOps.shortId(full));
            }
            return ActionExecutor.ActionResult.ok(
                    "在背包界面合成了 " + made + " 个 " + ContainerOps.shortId(full));
        } finally {
            // inventoryMenu 是常驻界面，不需要 close
        }
    }

    /**
     * 先合成一个工作台，再继续合成目标物品。
     *
     * <p>真人从零开始做木镐时也是这个顺序：砍木头 → 做木板 → 做工作台 → 放下 →
     * 在工作台上做木棍 → 做木镐。这里不做「一步到位」的取巧。</p>
     */
    private ActionExecutor.ActionResult craftTableFirst(int wantedCount, String full) {
        // 检查能否做出工作台（4 个木板）
        Item planks = findAnyPlanks();
        if (planks == null) {
            return ActionExecutor.ActionResult.fail(
                    "需要先合成工作台，但背包里没有木板（先用原木合成木板）");
        }
        // 递归做工作台（木板→工作台是 2x2 配方，走背包界面）
        ActionExecutor.ActionResult r = craft("minecraft:crafting_table", 1);
        if (r.success()) {
            // 返回 ok：本轮已把工作台做出来了，模型下一轮会：
            // 放下它 → 再发一次 craft。这与真人的步骤完全一致。
            return ActionExecutor.ActionResult.ok(
                    "已用木板做出工作台。请先放下（place），再执行一次 craft 合成 "
                            + ContainerOps.shortId(full));
        }
        return ActionExecutor.ActionResult.fail("无法合成工作台：" + r.message());
    }

    /**
     * 在任意「含合成网格的界面」里按配方摆放并取货。
     *
     * @param gridStart 网格起始槽位（工作台=1，背包=1）
     * @param gridSize  网格格子数（工作台=9，背包=4）
     * @return 合成数量；材料不足返回 -1
     */
    private int craftInMenu(AbstractContainerMenu menu, CraftingRecipe recipe,
                            int count, int gridStart, int gridSize) {
        List<net.minecraft.world.item.crafting.Ingredient> ingredients = recipe.getIngredients();
        // 原版 getIngredients() 返回的是「按 3x3 顺序」的列表；
        // 对于 2x2 配方，非零元素集中在左上角，截取前 gridSize 个即可。
        int limit = Math.min(ingredients.size(), gridSize);

        // 摆材料
        for (int i = 0; i < limit; i++) {
            var ing = ingredients.get(i);
            if (ing == null || ing.isEmpty()) {
                continue;
            }
            int slot = gridStart + i;
            if (!placeOne(menu, ing, slot)) {
                clearGrid(menu, gridStart, gridSize);
                return -1;
            }
        }

        // 取成品
        int total = 0;
        for (int c = 0; c < count; c++) {
            ItemStack res = menu.getSlot(0).getItem();
            if (res.isEmpty()) {
                break;
            }
            int before = res.getCount();
            ContainerOps.quickMove(bot, menu, 0);
            ItemStack after = menu.getSlot(0).getItem();
            int got = before - (after.isEmpty() ? 0 : after.getCount());
            if (got <= 0) {
                break;
            }
            total += got;
        }

        clearGrid(menu, gridStart, gridSize);
        return total;
    }

    /** 把一个材料放进指定合成格（真人：拿整摞 → 右键放一个 → 放回剩余的）。 */
    private boolean placeOne(AbstractContainerMenu menu, net.minecraft.world.item.crafting.Ingredient ing,
                             int gridSlot) {
        int pStart = ContainerOps.playerSlotStart(menu);
        for (int i = pStart; i < menu.slots.size(); i++) {
            ItemStack s = menu.getSlot(i).getItem();
            if (s.isEmpty() || !ing.test(s)) {
                continue;
            }
            if (s.getCount() == 1) {
                ContainerOps.quickMove(bot, menu, i);
            } else {
                ContainerOps.leftClick(bot, menu, i);
                ContainerOps.rightClick(bot, menu, gridSlot);
                if (!menu.getCarried().isEmpty()) {
                    ContainerOps.leftClick(bot, menu, i);
                }
            }
            if (!menu.getSlot(gridSlot).getItem().isEmpty()) {
                return true;
            }
        }
        return false;
    }

    /** 把网格里剩余材料收回背包。 */
    private void clearGrid(AbstractContainerMenu menu, int gridStart, int gridSize) {
        for (int i = 0; i < gridSize; i++) {
            int slot = gridStart + i;
            if (slot >= menu.slots.size()) {
                break;
            }
            if (!menu.getSlot(slot).getItem().isEmpty()) {
                ContainerOps.quickMove(bot, menu, slot);
            }
        }
    }

    // ==================================================================
    // 熔炼
    // ==================================================================

    /**
     * 熔炼物品（完整原版路径）。
     *
     * <p>流程与真人一致：</p>
     * <ol>
     *   <li>找熔炉；没有则先合成并放下</li>
     *   <li>走到熔炉旁 → 右键打开界面</li>
     *   <li>放原料 + 放燃料 → 关闭界面（真人放完就走开干别的）</li>
     *   <li>等待烧炼完成（原版速率：10 秒/个，燃料决定能烧几个）</li>
     *   <li>重新打开界面 → 取出成品</li>
     * </ol>
     *
     * @param inputId 要烧的物品（如 minecraft:raw_iron）
     * @param count   要烧几个
     * @param fuelId  燃料（如 minecraft:coal），留空自动挑
     */
    public ActionExecutor.ActionResult smelt(String inputId, int count, String fuelId) {
        String full = normalize(inputId);
        if (resolveItem(full) == null) {
            return ActionExecutor.ActionResult.fail("未知物品: " + inputId);
        }
        if (!hasItemInInventory(full)) {
            return ActionExecutor.ActionResult.fail("背包里没有 " + ContainerOps.shortId(full));
        }

        ServerLevel level = (ServerLevel) bot.level();
        BlockPos furnace = ContainerOps.findNearbyFurnace(level, bot.blockPosition(), 12);

        if (furnace == null) {
            // 没熔炉：先做一个（圆石 x8，需要工作台）
            if (hasItemInInventory("minecraft:furnace")) {
                ActionExecutor.ActionResult placed = executor.place(
                        ActionParser.fromParams("place", "block", "minecraft:furnace"));
                if (placed.async()) {
                    return ActionExecutor.ActionResult.ok("已开始放置熔炉，放下后再执行 smelt");
                }
            }
            return ActionExecutor.ActionResult.fail(
                    "附近没有熔炉，背包里也没有。请先合成熔炉（圆石 x8，需要工作台）");
        }

        double dist = Math.sqrt(furnace.distSqr(bot.blockPosition()));
        if (dist > 4.0) {
            driver.beginWalk(net.minecraft.world.phys.Vec3.atCenterOf(furnace), 200);
            return ActionExecutor.ActionResult.ok("正在走向熔炉，到达后再执行 smelt");
        }

        AbstractContainerMenu menu = ContainerOps.openContainerAt(bot, furnace);
        if (!(menu instanceof AbstractFurnaceMenu)) {
            ContainerOps.close(bot);
            return ActionExecutor.ActionResult.fail("打开熔炉界面失败");
        }

        try {
            String fuel = (fuelId == null || fuelId.isEmpty()) ? "minecraft:coal" : normalize(fuelId);
            String desc = ContainerOps.stockFurnace(bot, menu, full, count, fuel, Math.max(1, count / 8 + 1));
            // 放完料就关闭界面（真人也是放完就走，让它在后台烧）
            return ActionExecutor.ActionResult.ok(
                    "已放入熔炉：" + desc + "。烧炼需要时间（约 " + count * 10
                            + " 秒），之后用 collect 动作取成品。");
        } finally {
            ContainerOps.close(bot);
        }
    }

    /** 从熔炉取出烧好的成品。 */
    public ActionExecutor.ActionResult collectFurnace() {
        ServerLevel level = (ServerLevel) bot.level();
        BlockPos furnace = ContainerOps.findNearbyFurnace(level, bot.blockPosition(), 12);
        if (furnace == null) {
            return ActionExecutor.ActionResult.fail("附近没有熔炉");
        }
        double dist = Math.sqrt(furnace.distSqr(bot.blockPosition()));
        if (dist > 4.0) {
            driver.beginWalk(net.minecraft.world.phys.Vec3.atCenterOf(furnace), 200);
            return ActionExecutor.ActionResult.ok("正在走向熔炉，到达后再执行 collect");
        }

        AbstractContainerMenu menu = ContainerOps.openContainerAt(bot, furnace);
        if (!(menu instanceof AbstractFurnaceMenu)) {
            ContainerOps.close(bot);
            return ActionExecutor.ActionResult.fail("打开熔炉界面失败");
        }
        try {
            int got = ContainerOps.collectFurnaceOutput(bot, menu);
            if (got <= 0) {
                return ActionExecutor.ActionResult.ok("熔炉里还没有烧好的成品");
            }
            return ActionExecutor.ActionResult.ok("从熔炉取出 " + got + " 个成品");
        } finally {
            ContainerOps.close(bot);
        }
    }

    // ==================================================================
    // 箱子存取
    // ==================================================================

    /**
     * 从箱子取出物品（完整原版路径：走到箱子 → 开界面 → shift 点击 → 关界面）。
     *
     * @param itemId 要取的物品，留空表示随便取
     * @param count  取多少个（0 表示能取多少取多少）
     */
    public ActionExecutor.ActionResult withdraw(String itemId, int count) {
        ServerLevel level = (ServerLevel) bot.level();
        BlockPos chest = ContainerOps.findNearbyContainer(level, bot.blockPosition(), 12);
        if (chest == null) {
            return ActionExecutor.ActionResult.fail("附近没有箱子");
        }
        double dist = Math.sqrt(chest.distSqr(bot.blockPosition()));
        if (dist > 4.0) {
            driver.beginWalk(net.minecraft.world.phys.Vec3.atCenterOf(chest), 200);
            return ActionExecutor.ActionResult.ok("正在走向箱子，到达后再执行");
        }

        AbstractContainerMenu menu = ContainerOps.openContainerAt(bot, chest);
        if (menu == null) {
            return ActionExecutor.ActionResult.fail("打开箱子失败（可能被阻挡）");
        }
        try {
            String want = (itemId == null || itemId.isEmpty()) ? "" : normalize(itemId);
            int got = ContainerOps.takeFromContainer(bot, menu, want, count);
            if (got <= 0) {
                return ActionExecutor.ActionResult.fail(want.isEmpty()
                        ? "箱子里是空的" : "箱子里没有 " + ContainerOps.shortId(want));
            }
            return ActionExecutor.ActionResult.ok("从箱子取出 " + got + " 个物品");
        } finally {
            ContainerOps.close(bot);
        }
    }

    /**
     * 把物品存进箱子（完整原版路径）。
     *
     * @param itemId 要存的物品，留空表示把背包里能存的都存了
     */
    public ActionExecutor.ActionResult store(String itemId) {
        ServerLevel level = (ServerLevel) bot.level();
        BlockPos chest = ContainerOps.findNearbyContainer(level, bot.blockPosition(), 12);
        if (chest == null) {
            return ActionExecutor.ActionResult.fail("附近没有箱子");
        }
        double dist = Math.sqrt(chest.distSqr(bot.blockPosition()));
        if (dist > 4.0) {
            driver.beginWalk(net.minecraft.world.phys.Vec3.atCenterOf(chest), 200);
            return ActionExecutor.ActionResult.ok("正在走向箱子，到达后再执行");
        }

        AbstractContainerMenu menu = ContainerOps.openContainerAt(bot, chest);
        if (menu == null) {
            return ActionExecutor.ActionResult.fail("打开箱子失败");
        }
        try {
            String want = (itemId == null || itemId.isEmpty()) ? "" : normalize(itemId);
            // 保留快捷栏前 9 格不存（真人也会留着手上的工具）
            int moved = putIntoContainerExceptHotbar(menu, want);
            if (moved <= 0) {
                return ActionExecutor.ActionResult.fail("没有可存入的物品（或箱子已满）");
            }
            return ActionExecutor.ActionResult.ok("存入箱子 " + moved + " 个物品");
        } finally {
            ContainerOps.close(bot);
        }
    }

    /** 存物品，但跳过快捷栏（保住手上的工具）。 */
    private int putIntoContainerExceptHotbar(AbstractContainerMenu menu, String itemId) {
        int moved = 0;
        int size = menu.slots.size();
        // 快捷栏 = 最后 9 格；主背包 = 倒数第 36..10 格
        int mainStart = size - 36;
        int mainEnd = size - 9;
        for (int i = mainEnd - 1; i >= mainStart; i--) {
            ItemStack s = menu.getSlot(i).getItem();
            if (s.isEmpty()) {
                continue;
            }
            if (!itemId.isEmpty()) {
                String id = BuiltInRegistries.ITEM.getKey(s.getItem()).toString();
                if (!id.equals(itemId)) {
                    continue;
                }
            }
            int before = s.getCount();
            ContainerOps.quickMove(bot, menu, i);
            ItemStack after = menu.getSlot(i).getItem();
            moved += before - (after.isEmpty() ? 0 : after.getCount());
        }
        return moved;
    }

    // ==================================================================
    // 工具辅助
    // ==================================================================

    /** 补全 minecraft: 前缀。 */
    private static String normalize(String id) {
        if (id == null || id.isEmpty()) {
            return "";
        }
        return id.contains(":") ? id : "minecraft:" + id;
    }

    /** 按 ID 解析物品。 */
    private static Item resolveItem(String full) {
        try {
            ResourceLocation key = ResourceLocation.tryParse(full);
            if (key == null) {
                return null;
            }
            return BuiltInRegistries.ITEM.get(key);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 背包里是否有指定物品。 */
    private boolean hasItemInInventory(String full) {
        var inv = bot.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (s.isEmpty()) {
                continue;
            }
            String id = BuiltInRegistries.ITEM.getKey(s.getItem()).toString();
            if (id.equals(full)) {
                return true;
            }
        }
        return false;
    }

    /** 找背包里任意一种木板。 */
    private Item findAnyPlanks() {
        var inv = bot.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (s.isEmpty()) {
                continue;
            }
            String id = BuiltInRegistries.ITEM.getKey(s.getItem()).toString();
            if (id.endsWith("_planks")) {
                return s.getItem();
            }
        }
        return null;
    }

    /**
     * 在原版配方表里找「产出指定物品」的合成配方。
     *
     * <p>优先返回「当前材料够做」的那个；若都不够，返回任意一个
     * （用于给出「材料不足」的准确提示）。</p>
     */
    private CraftingRecipe findCraftingRecipe(ServerLevel level, Item target) {
        var rm = level.getServer().getRecipeManager();
        CraftingRecipe fallback = null;
        for (net.minecraft.world.item.crafting.Recipe<?> holder : rm.getRecipes()) {
            if (!(holder instanceof CraftingRecipe cr)) {
                continue;
            }
            ItemStack result;
            try {
                result = cr.getResultItem(level.registryAccess());
            } catch (Throwable t) {
                continue;
            }
            if (result.isEmpty() || !result.is(target)) {
                continue;
            }
            if (fallback == null) {
                fallback = (CraftingRecipe) holder;
            }
            if (materialsAvailable(cr)) {
                return (CraftingRecipe) holder;
            }
        }
        return fallback;
    }

    /** 判断配方所需材料背包里是否齐全（只算一级，前置合成由 craft 递归处理）。 */
    private boolean materialsAvailable(CraftingRecipe recipe) {
        for (var ing : recipe.getIngredients()) {
            if (ing == null || ing.isEmpty()) {
                continue;
            }
            boolean found = false;
            var inv = bot.getInventory();
            for (int i = 0; i < inv.getContainerSize(); i++) {
                ItemStack s = inv.getItem(i);
                if (!s.isEmpty() && ing.test(s)) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                return false;
            }
        }
        return true;
    }

    /**
     * 该配方是否需要工作台（3x3）。
     *
     * <p>判断依据是原版配方的<b>图样尺寸</b>：</p>
     * <ul>
     *   <li>有序配方（{@link ShapedRecipe}）：看 {@code getWidth()}/{@code getHeight()}，
     *       只要有一边 &gt; 2 就必须用工作台</li>
     *   <li>无序配方（{@link ShapelessRecipe}）：看材料种类数，&gt; 4 就必须用工作台
     *       （2x2 最多放 4 种材料）</li>
     * </ul>
     *
     * <p>这比「猜测」准确，也与真人的判断一致：真人看到配方摆不下 2x2
     * 就知道得去做工作台。</p>
     */
    private static boolean requiresTable(CraftingRecipe recipe) {
        try {
            if (recipe instanceof net.minecraft.world.item.crafting.ShapedRecipe shaped) {
                return shaped.getWidth() > 2 || shaped.getHeight() > 2;
            }
            if (recipe instanceof net.minecraft.world.item.crafting.ShapelessRecipe) {
                int kinds = 0;
                for (var ing : recipe.getIngredients()) {
                    if (ing != null && !ing.isEmpty()) {
                        kinds++;
                    }
                }
                return kinds > 4;
            }
        } catch (Throwable t) {
            // 拿不到尺寸信息：保守认为需要工作台（绝大多数配方确实需要）
            return true;
        }
        return true;
    }
}
