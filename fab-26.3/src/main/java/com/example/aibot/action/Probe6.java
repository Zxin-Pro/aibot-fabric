package com.example.aibot.action;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
public class Probe6 {
    void a(ServerLevel l, ItemStack s) { int n = l.fuelValues().burnDuration(s); }
    void b(ServerLevel l) { var x = l.fuelValues(); }
    void c(net.minecraft.world.level.Level l) { var x = l.fuelValues(); }
}
