package com.example.aibot.action;
import net.minecraft.world.item.crafting.*;
import net.minecraft.world.item.crafting.display.*;
import net.minecraft.world.item.ItemStack;
import net.minecraft.util.context.ContextMap;
import net.minecraft.world.level.block.entity.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Items;

public class Probe3 {
    // 1) FuelValues 方法名
    void a(FuelValues f, ItemStack s) { int n = f.burnDuration(s); }
    // 2) ingredient EMPTY 常量
    void b() { var x = Ingredient.EMPTY; }
    // 3) CraftingRecipe.display() 返回类型
    void c(CraftingRecipe r, ContextMap ctx) {
        var d = r.display();
        ItemStack s = d.get(0).resolveForFirstStack(ctx);
    }
    // 4) 配方类型转换
    void d(Recipe<?> r) { CraftingRecipe c = (CraftingRecipe) r; }
    // 5) 静态 getFuel 是否还在
    void e() { var m = AbstractFurnaceBlockEntity.getFuel(); }
}
