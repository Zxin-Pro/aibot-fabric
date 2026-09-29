package com.example.aibot.action;
import net.minecraft.world.item.crafting.*;
import net.minecraft.world.item.crafting.display.*;
import net.minecraft.world.item.ItemStack;
import net.minecraft.util.context.ContextMap;
import net.minecraft.core.NonNullList;
import java.util.List;
import java.util.Optional;

public class Probe4 {
    // 试各种可能的名字
    void a(SlotDisplay d, ContextMap c) { List<ItemStack> l = d.resolveForStacks(c); }
    void b(SlotDisplay d) { List<ItemStack> l = d.getItems(); }
    void c(SlotDisplay d) { ItemStack s = d.getFirst(); }
    // Ingredient 空值怎么表示
    void d() { Optional<Ingredient> o = Optional.empty(); }
    // ShapedRecipe 的 ingredient 取法
    void e(ShapedRecipe r) {
        var x = r.getIngredients();
        for (var opt : x) { Optional<Ingredient> o = opt; }
    }
    // 有没有别的拿产物途径
    void f(Recipe<?> r, CraftingInput in, net.minecraft.core.HolderLookup.Provider p) {
        ItemStack s = r.assemble(in, p);
    }
    void g() { var x = CraftingInput.EMPTY; }
    void h() { NonNullList<Ingredient> n = null; }
}
