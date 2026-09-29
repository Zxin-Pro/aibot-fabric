package com.example.aibot.action;
import net.minecraft.world.item.crafting.*;
import net.minecraft.world.item.crafting.display.*;
import net.minecraft.world.item.ItemStack;
import net.minecraft.util.context.ContextMap;
import java.util.List;

public class Probe2 {
    // 测 ShapedRecipe.getIngredients 的返回类型
    void a(ShapedRecipe r) {
        List<Ingredient> l = r.getIngredients();        // 若报错则说明是 Optional 包装
    }
    void b(SlotDisplay d, ContextMap c) {
        ItemStack s = d.resolveForFirstStack(c);
    }
    void c(Recipe<?> r) {
        var x = r.display();
    }
    void d(FuelValues f, ItemStack s) {
        int n = f.burnDuration(s);
    }
}
