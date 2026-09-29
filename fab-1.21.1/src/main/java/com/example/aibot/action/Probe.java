// 探针：测试哪些容器操作 API 是公开可用的
package com.example.aibot.action;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.AbstractFurnaceMenu;

public class Probe {
    void t1(ServerPlayer p, AbstractContainerMenu m) {
        m.clicked(0, 0, ClickType.PICKUP, p);              // 方案A
    }
    void t2(ServerPlayer p, AbstractContainerMenu m) {
        m.doClick(0, 0, ClickType.PICKUP, p);              // 方案B
    }
    void t3(ServerPlayer p, AbstractContainerMenu m) {
        m.quickMoveStack(p, 0);                            // 方案C
    }
    int t4() { return CraftingMenu.CRAFT_SLOT_START; }
    int t5() { return CraftingMenu.RESULT_SLOT; }
    int t6() { return InventoryMenu.CRAFT_SLOT_START; }
    int t7() { return AbstractFurnaceMenu.INGREDIENT_SLOT; }
    void t8(ServerPlayer p) {
        p.containerMenu = p.containerMenu;                 // 字段可写?
    }
}
