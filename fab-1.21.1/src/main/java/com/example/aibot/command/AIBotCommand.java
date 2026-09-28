package com.example.aibot.command;

import com.example.aibot.action.ActionExecutor;
import com.example.aibot.action.ActionParser;
import com.example.aibot.config.AIConfig;
import com.example.aibot.config.ConfigStore;
import com.example.aibot.entity.BotProfile;
import com.example.aibot.entity.BotProfileStore;
import com.example.aibot.entity.MultiBotManager;
import com.example.aibot.llm.CacheStats;
import com.example.aibot.llm.PromptBuilder;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.function.Supplier;

/**
 * /aibot 命令系统（多智能体版，Minecraft 1.21.11 实现）。
 *
 * <p>命名与生成的设计参照 Carpet 模组：<b>名字即身份</b>。
 * 同一个名字重新生成，服务器会认为是同一个玩家，背包与统计数据延续。</p>
 *
 * <pre>
 * /aibot spawn [名字]                        生成智能体（默认配置里的 botName）
 * /aibot remove &lt;名字|all&gt;                   移除智能体
 * /aibot list                                列出所有智能体
 * /aibot goal &lt;名字&gt; &lt;自然语言&gt;             设置某个智能体的长期目标
 * /aibot personality &lt;名字&gt; &lt;描述&gt;           设置性格（决定行为倾向与语气）
 * /aibot auto &lt;名字&gt; on|off                  开关某个智能体的自主循环
 * /aibot auto all on|off                     批量开关
 * /aibot status [名字]                       查看状态（含上下文压缩与缓存命中率）
 * /aibot cache stats|reset                   缓存统计
 * /aibot context stats|clear [名字]          上下文压缩统计/重置
 * /aibot do &lt;名字&gt; &lt;JSON动作&gt;                手动执行动作（调试）
 * /aibot config set &lt;key&gt; &lt;value&gt;           修改配置
 * /aibot config show                         显示当前配置
 * </pre>
 *
 * <p><b>1.21.11 版本差异</b>：命令树本身与版本无关，
 * 与 1.20.1 的差别仅在于权限判断写法 —— 这里沿用
 * {@code src.hasPermission(2)}（在 1.21.11 上依然可用且语义正确）。</p>
 */
public final class AIBotCommand {

    private final ConfigStore configStore;
    private final BotProfileStore profileStore;
    private final CacheStats cacheStats;
    private final MultiBotManager bots;
    private final Supplier<MinecraftServer> serverSupplier;

    public AIBotCommand(ConfigStore configStore,
                        BotProfileStore profileStore,
                        CacheStats cacheStats,
                        MultiBotManager bots,
                        Supplier<MinecraftServer> serverSupplier) {
        this.configStore = configStore;
        this.profileStore = profileStore;
        this.cacheStats = cacheStats;
        this.bots = bots;
        this.serverSupplier = serverSupplier;
    }

    /** 注册命令树。要求 OP（等级 2），避免普通玩家滥用 LLM 额度。 */
    public void register(CommandDispatcher<CommandSourceStack> dispatcher,
                         CommandBuildContext buildContext,
                         Commands.CommandSelection selection) {
        dispatcher.register(Commands.literal("aibot")
                .requires(src -> src.hasPermission(2))

                // ---------------- spawn [名字] ----------------
                .then(Commands.literal("spawn")
                        .executes(ctx -> doSpawn(ctx, null))
                        .then(Commands.argument("name", StringArgumentType.word())
                                .executes(ctx -> doSpawn(ctx, StringArgumentType.getString(ctx, "name")))))

                // ---------------- remove <名字|all> ----------------
                .then(Commands.literal("remove")
                        .then(Commands.argument("name", StringArgumentType.word())
                                .executes(ctx -> doRemove(ctx, StringArgumentType.getString(ctx, "name")))))

                // ---------------- list ----------------
                .then(Commands.literal("list")
                        .executes(ctx -> doList(ctx)))

                // ---------------- goal <名字> <文本> ----------------
                .then(Commands.literal("goal")
                        .then(Commands.argument("name", StringArgumentType.word())
                                .then(Commands.argument("text", StringArgumentType.greedyString())
                                        .executes(ctx -> doSetGoal(ctx)))))

                // ---------------- personality <名字> <文本> ----------------
                .then(Commands.literal("personality")
                        .then(Commands.argument("name", StringArgumentType.word())
                                .then(Commands.argument("text", StringArgumentType.greedyString())
                                        .executes(ctx -> doSetPersonality(ctx)))))

                // ---------------- auto <名字|all> on|off ----------------
                .then(Commands.literal("auto")
                        .then(Commands.argument("name", StringArgumentType.word())
                                .then(Commands.literal("on").executes(ctx -> doAuto(ctx, true)))
                                .then(Commands.literal("off").executes(ctx -> doAuto(ctx, false)))))

                // ---------------- status [名字] ----------------
                .then(Commands.literal("status")
                        .executes(ctx -> doStatus(ctx, null))
                        .then(Commands.argument("name", StringArgumentType.word())
                                .executes(ctx -> doStatus(ctx, StringArgumentType.getString(ctx, "name")))))

                // ---------------- cache ----------------
                .then(Commands.literal("cache")
                        .then(Commands.literal("stats").executes(ctx -> doCacheStats(ctx)))
                        .then(Commands.literal("reset").executes(ctx -> doCacheReset(ctx))))

                // ---------------- context ----------------
                .then(Commands.literal("context")
                        .then(Commands.literal("stats").executes(ctx -> doContextStats(ctx, null))
                                .then(Commands.argument("name", StringArgumentType.word())
                                        .executes(ctx -> doContextStats(ctx, StringArgumentType.getString(ctx, "name")))))
                        .then(Commands.literal("clear").executes(ctx -> doContextClear(ctx, null))
                                .then(Commands.argument("name", StringArgumentType.word())
                                        .executes(ctx -> doContextClear(ctx, StringArgumentType.getString(ctx, "name"))))))

                // ---------------- do <名字> <JSON> ----------------
                .then(Commands.literal("do")
                        .then(Commands.argument("name", StringArgumentType.word())
                                .then(Commands.argument("json", StringArgumentType.greedyString())
                                        .executes(ctx -> doManualAction(ctx)))))

                // ---------------- config ----------------
                .then(Commands.literal("config")
                        .then(Commands.literal("show").executes(ctx -> doConfigShow(ctx)))
                        .then(Commands.literal("set")
                                .then(Commands.argument("key", StringArgumentType.word())
                                        .then(Commands.argument("value", StringArgumentType.greedyString())
                                                .executes(ctx -> doConfigSet(ctx))))))
        );
    }

    // ------------------------------------------------------------------
    // 各子命令实现
    // ------------------------------------------------------------------

    /**
     * 生成智能体。
     *
     * @param name 指定名字；为 null 时用配置里的默认名
     */
    private int doSpawn(CommandContext<CommandSourceStack> ctx, String name) {
        MinecraftServer server = serverSupplier.get();
        if (server == null) {
            return fail(ctx, "服务器未就绪");
        }
        AIConfig config = configStore.get();

        if (bots.size() >= config.maxBots) {
            return fail(ctx, "已达智能体上限 " + config.maxBots
                    + " 个，请先移除一些，或用 /aibot config set maxBots <数量> 调高");
        }

        String botName = name != null ? name : config.botName;

        // 档案已存在则复用（保留性格、目标），否则新建
        BotProfile profile = profileStore.get(botName);
        boolean isNew = profile == null;
        if (isNew) {
            profile = new BotProfile(botName);
        }

        String nameError = profile.validateName();
        if (nameError != null) {
            return fail(ctx, nameError);
        }

        MultiBotManager.Agent agent = bots.spawn(server, profile);
        if (agent == null) {
            return fail(ctx, "生成智能体失败，请查看服务器日志（可能是名字冲突或区块未加载）");
        }

        if (isNew) {
            profileStore.put(profile);
            profileStore.save();
        }

        ServerPlayer p = agent.player();
        return ok(ctx, "已生成智能体 " + botName + (p != null ? "，位置 " + fmtPos(p) : "")
                + (isNew ? "" : "（复用了已保存的档案）")
                + "\n提示：同名智能体的背包与统计数据会被服务器延续，这一点与 Carpet 假玩家一致。"
                + "\n长期挂机前请务必先备份存档。");
    }

    private int doRemove(CommandContext<CommandSourceStack> ctx, String name) {
        MinecraftServer server = serverSupplier.get();
        if (server == null) {
            return fail(ctx, "服务器未就绪");
        }
        if ("all".equalsIgnoreCase(name)) {
            int n = bots.removeAll(server);
            return n > 0 ? ok(ctx, "已移除全部 " + n + " 个智能体") : fail(ctx, "当前没有智能体");
        }
        boolean removed = bots.remove(server, name);
        return removed
                ? ok(ctx, "智能体 " + name + " 已移除（档案仍保留，可再次 spawn 恢复）")
                : fail(ctx, "找不到智能体: " + name);
    }

    /** 列出所有智能体及其状态。 */
    private int doList(CommandContext<CommandSourceStack> ctx) {
        StringBuilder sb = new StringBuilder("===== 智能体列表 =====\n");
        if (bots.isEmpty()) {
            sb.append("（当前没有运行中的智能体）\n");
        } else {
            int i = 1;
            for (MultiBotManager.Agent a : bots.all()) {
                ServerPlayer p = a.player();
                sb.append(i++).append(". ").append(a.name())
                        .append(a.alive() ? " [存活]" : " [已死亡]")
                        .append(a.loop.isRunning() ? " [自主中]" : " [待机]")
                        .append(" 步数=").append(a.loop.getTotalStepCount());
                if (p != null) {
                    sb.append(" @ ").append(p.blockPosition().toShortString());
                }
                if (!a.profile.personality.isEmpty()) {
                    sb.append("\n   性格: ").append(a.profile.personality);
                }
                if (!a.loop.getGoal().isEmpty()) {
                    sb.append("\n   目标: ").append(a.loop.getGoal());
                }
                sb.append('\n');
            }
        }
        sb.append("上限: ").append(bots.size()).append("/").append(configStore.get().maxBots);

        // 档案库里但未运行的
        java.util.List<String> offline = new java.util.ArrayList<>();
        for (BotProfile p : profileStore.all()) {
            if (bots.get(p.name) == null) {
                offline.add(p.name);
            }
        }
        if (!offline.isEmpty()) {
            sb.append("\n已保存但未运行: ").append(String.join(", ", offline));
            sb.append("\n（用 /aibot spawn <名字> 可重新拉起）");
        }
        send(ctx, sb.toString());
        return 1;
    }

    private int doSetGoal(CommandContext<CommandSourceStack> ctx) {
        String name = StringArgumentType.getString(ctx, "name");
        String text = StringArgumentType.getString(ctx, "text");
        MultiBotManager.Agent a = bots.get(name);
        if (a == null) {
            return fail(ctx, "找不到智能体: " + name);
        }
        a.loop.setGoal(text);
        a.profile.goal = text;
        profileStore.put(a.profile);
        profileStore.save();
        return ok(ctx, "已设置 " + name + " 的长期目标：" + text + "\n（旧计划已清空，将重新规划）");
    }

    private int doSetPersonality(CommandContext<CommandSourceStack> ctx) {
        String name = StringArgumentType.getString(ctx, "name");
        String text = StringArgumentType.getString(ctx, "text");
        MultiBotManager.Agent a = bots.get(name);
        if (a == null) {
            return fail(ctx, "找不到智能体: " + name);
        }
        a.profile.personality = text;
        profileStore.put(a.profile);
        profileStore.save();
        return ok(ctx, "已设置 " + name + " 的性格：" + text
                + "\n（下一轮决策起生效，会体现在动作选择与聊天语气里）");
    }

    private int doAuto(CommandContext<CommandSourceStack> ctx, boolean on) {
        String name = StringArgumentType.getString(ctx, "name");
        AIConfig config = configStore.get();

        // 批量操作
        if ("all".equalsIgnoreCase(name)) {
            int n = 0;
            for (MultiBotManager.Agent a : bots.all()) {
                if (on) {
                    if (config.isUsable()) {
                        a.loop.start();
                        a.profile.autoLoop = true;
                        n++;
                    }
                } else {
                    a.loop.stop();
                    a.profile.autoLoop = false;
                    n++;
                }
            }
            if (on && !config.isUsable()) {
                return fail(ctx, "LLM 配置不完整，请先执行 /aibot config set apiKey <你的密钥>");
            }
            profileStore.save();
            config.autoLoop = on;
            configStore.save();
            return ok(ctx, "已" + (on ? "开启" : "停止") + "全部 " + n + " 个智能体的自主循环");
        }

        MultiBotManager.Agent a = bots.get(name);
        if (a == null) {
            return fail(ctx, "找不到智能体: " + name);
        }

        if (on) {
            if (!a.alive()) {
                return fail(ctx, name + " 未生成或已死亡，请先 /aibot spawn " + name);
            }
            if (!config.isUsable()) {
                return fail(ctx, "LLM 配置不完整，请先执行：\n"
                        + "  /aibot config set apiKey <你的密钥>\n"
                        + "  /aibot config set baseUrl https://api.deepseek.com");
            }
            a.loop.start();
            a.profile.autoLoop = true;
        } else {
            a.loop.stop();
            a.profile.autoLoop = false;
        }
        profileStore.put(a.profile);
        profileStore.save();
        config.autoLoop = on;
        configStore.save();

        return ok(ctx, name + " 的自主循环已" + (on ? "开启" : "停止")
                + (on ? "（每 " + config.decisionIntervalTicks + " tick 决策一次，"
                + (config.maxStepsPerSession <= 0 ? "无步数上限）" : "上限 " + config.maxStepsPerSession + " 步）") : ""));
    }

    private int doStatus(CommandContext<CommandSourceStack> ctx, String name) {
        if (name != null) {
            MultiBotManager.Agent a = bots.get(name);
            if (a == null) {
                return fail(ctx, "找不到智能体: " + name);
            }
            send(ctx, a.loop.statusReport());
            return 1;
        }
        // 不指定名字：全部智能体各来一份摘要
        if (bots.isEmpty()) {
            send(ctx, "（当前没有运行中的智能体）\n用 /aibot spawn <名字> 生成一个。");
            return 1;
        }
        StringBuilder sb = new StringBuilder();
        for (MultiBotManager.Agent a : bots.all()) {
            sb.append(a.loop.statusReport()).append('\n');
        }
        send(ctx, sb.toString().trim());
        return 1;
    }

    private int doCacheStats(CommandContext<CommandSourceStack> ctx) {
        send(ctx, cacheStats.report());
        return 1;
    }

    private int doCacheReset(CommandContext<CommandSourceStack> ctx) {
        cacheStats.reset();
        return ok(ctx, "缓存统计已重置");
    }

    /** 上下文压缩统计。 */
    private int doContextStats(CommandContext<CommandSourceStack> ctx, String name) {
        if (name != null) {
            MultiBotManager.Agent a = bots.get(name);
            if (a == null) {
                return fail(ctx, "找不到智能体: " + name);
            }
            send(ctx, "===== " + name + " 上下文压缩 =====\n"
                    + a.loop.getCompressor().shortSummary());
            return 1;
        }
        StringBuilder sb = new StringBuilder("===== 上下文压缩 =====\n");
        if (bots.isEmpty()) {
            sb.append("（没有运行中的智能体）");
        } else {
            for (MultiBotManager.Agent a : bots.all()) {
                sb.append(a.name()).append(": ")
                        .append(a.loop.getCompressor().shortSummary()).append('\n');
            }
        }
        sb.append('\n').append("压缩策略：保留最近 ").append(configStore.get().contextWindow)
                .append(" 条完整细节，更早的按批聚合成摘要，并按 ")
                .append(configStore.get().contextTokenBudget)
                .append(" token 预算硬性裁剪。\n")
                .append("这让提示词长度保持恒定，是「可以一直玩下去」的前提。");
        send(ctx, sb.toString());
        return 1;
    }

    /** 清空某个智能体的上下文压缩状态。 */
    private int doContextClear(CommandContext<CommandSourceStack> ctx, String name) {
        if (name != null) {
            MultiBotManager.Agent a = bots.get(name);
            if (a == null) {
                return fail(ctx, "找不到智能体: " + name);
            }
            a.loop.getCompressor().clear();
            a.shortTermMemory.clear();
            return ok(ctx, name + " 的上下文与短期记忆已清空");
        }
        for (MultiBotManager.Agent a : bots.all()) {
            a.loop.getCompressor().clear();
            a.shortTermMemory.clear();
        }
        return ok(ctx, "已清空全部智能体的上下文与短期记忆");
    }

    /** 手动执行一个动作，用于调试模型输出是否可用。 */
    private int doManualAction(CommandContext<CommandSourceStack> ctx) {
        String name = StringArgumentType.getString(ctx, "name");
        MultiBotManager.Agent agent = bots.get(name);
        if (agent == null) {
            return fail(ctx, "找不到智能体: " + name);
        }
        ServerPlayer bot = agent.player();
        if (bot == null) {
            return fail(ctx, name + " 没有有效实体");
        }
        String json = StringArgumentType.getString(ctx, "json");
        ActionParser.ParsedAction parsed = ActionParser.parse(json);
        if (parsed == null) {
            return fail(ctx, "无法解析为合法动作 JSON：" + json);
        }
        ActionExecutor executor = new ActionExecutor(bot, configStore.get());
        ActionExecutor.ActionResult result = executor.execute(parsed);

        // 跨 tick 动作：交给该智能体的循环推进（即使 auto 未开启也会走完）
        if (result.async()) {
            agent.loop.adoptExternalAction(executor);
            return ok(ctx, "动作已启动：" + result.message()
                    + "\n（该动作需要若干 tick 完成，可用 /aibot status " + name + " 查看进度）");
        }
        return result.success()
                ? ok(ctx, "执行成功：" + result.message())
                : fail(ctx, "执行失败：" + result.message());
    }

    private int doConfigShow(CommandContext<CommandSourceStack> ctx) {
        AIConfig c = configStore.get();
        StringBuilder sb = new StringBuilder("===== AIBot 配置 =====\n");
        sb.append("apiKey: ").append(c.maskedApiKey()).append('\n');
        sb.append("baseUrl: ").append(c.baseUrl).append('\n');
        sb.append("model: ").append(c.model).append('\n');
        sb.append("temperature: ").append(c.temperature).append('\n');
        sb.append("maxTokens: ").append(c.maxTokens).append('\n');
        sb.append("cacheEnabled: ").append(c.cacheEnabled).append('\n');
        sb.append("botName: ").append(c.botName).append('\n');
        sb.append("maxBots: ").append(c.maxBots).append('\n');
        sb.append("decisionIntervalTicks: ").append(c.decisionIntervalTicks).append('\n');
        sb.append("maxStepsPerSession: ").append(c.maxStepsPerSession)
                .append(c.maxStepsPerSession <= 0 ? "（无上限）" : "").append('\n');
        sb.append("autoRespawn: ").append(c.autoRespawn).append('\n');
        sb.append("autoSpawnOnStart: ").append(c.autoSpawnOnStart).append('\n');
        sb.append("contextWindow: ").append(c.contextWindow).append('\n');
        sb.append("contextTokenBudget: ").append(c.contextTokenBudget).append('\n');
        sb.append("配置文件: ").append(configStore.getConfigFile().toAbsolutePath());
        send(ctx, sb.toString());
        return 1;
    }

    /** 修改配置项。apiKey 等敏感值写入文件，不硬编码在代码里。 */
    private int doConfigSet(CommandContext<CommandSourceStack> ctx) {
        String key = StringArgumentType.getString(ctx, "key");
        String value = StringArgumentType.getString(ctx, "value").trim();
        AIConfig c = configStore.get();

        try {
            switch (key) {
                case "apiKey" -> c.apiKey = value;
                case "baseUrl" -> {
                    if (!value.startsWith("http://") && !value.startsWith("https://")) {
                        return fail(ctx, "baseUrl 必须以 http:// 或 https:// 开头");
                    }
                    c.baseUrl = value;
                }
                case "model" -> c.model = value;
                case "temperature" -> c.temperature = Double.parseDouble(value);
                case "maxTokens" -> c.maxTokens = Integer.parseInt(value);
                case "cacheEnabled" -> c.cacheEnabled = parseBool(value);
                case "botName" -> c.botName = value;
                case "maxBots" -> c.maxBots = Integer.parseInt(value);
                case "decisionIntervalTicks" -> c.decisionIntervalTicks = Integer.parseInt(value);
                case "maxStepsPerSession" -> c.maxStepsPerSession = Integer.parseInt(value);
                case "requestTimeoutMs" -> c.requestTimeoutMs = Integer.parseInt(value);
                case "autoRespawn" -> c.autoRespawn = parseBool(value);
                case "autoRestart" -> c.autoRestart = parseBool(value);
                case "survivalReflex" -> c.survivalReflex = parseBool(value);
                case "autoSpawnOnStart" -> c.autoSpawnOnStart = parseBool(value);
                case "contextWindow" -> c.contextWindow = Integer.parseInt(value);
                case "contextTokenBudget" -> c.contextTokenBudget = Integer.parseInt(value);
                default -> {
                    return fail(ctx, "未知配置项: " + key + "\n可用项: apiKey, baseUrl, model, temperature, "
                            + "maxTokens, cacheEnabled, botName, maxBots, decisionIntervalTicks, "
                            + "maxStepsPerSession, requestTimeoutMs, autoRespawn, autoRestart, "
                            + "survivalReflex, autoSpawnOnStart, contextWindow, contextTokenBudget");
                }
            }
        } catch (NumberFormatException e) {
            return fail(ctx, "数值格式错误: " + value);
        }

        configStore.save();
        String shown = key.equals("apiKey") ? c.maskedApiKey() : value;
        return ok(ctx, "配置已更新: " + key + " = " + shown
                + "\n（已保存到 " + configStore.getConfigFile().toAbsolutePath() + "）");
    }

    // ------------------------------------------------------------------
    // 辅助方法
    // ------------------------------------------------------------------

    private static boolean parseBool(String v) {
        return v.equalsIgnoreCase("true") || v.equalsIgnoreCase("on") || v.equals("1")
                || v.equalsIgnoreCase("yes") || v.equalsIgnoreCase("是");
    }

    private static void send(CommandContext<CommandSourceStack> ctx, String message) {
        ctx.getSource().sendSuccess(() -> Component.literal(message), false);
    }

    private static int ok(CommandContext<CommandSourceStack> ctx, String message) {
        ctx.getSource().sendSuccess(() -> Component.literal("[AIBot] " + message), false);
        return 1;
    }

    private static int fail(CommandContext<CommandSourceStack> ctx, String message) {
        ctx.getSource().sendFailure(Component.literal("[AIBot] " + message));
        return 0;
    }

    private static String fmtPos(ServerPlayer bot) {
        return String.format(java.util.Locale.ROOT, "(%.0f, %.0f, %.0f)",
                bot.getX(), bot.getY(), bot.getZ());
    }

    /** 供主类在启动时打印静态前缀指纹，便于确认缓存前缀未被改动。 */
    public static String promptFingerprint() {
        return PromptBuilder.staticFingerprint();
    }
}
