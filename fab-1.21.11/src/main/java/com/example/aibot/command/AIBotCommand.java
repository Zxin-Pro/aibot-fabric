package com.example.aibot.command;

import com.example.aibot.action.ActionExecutor;
import com.example.aibot.action.ActionParser;
import com.example.aibot.config.AIConfig;
import com.example.aibot.config.ConfigStore;
import com.example.aibot.core.AutoLoop;
import com.example.aibot.entity.AIBotPlayer;
import com.example.aibot.entity.FakePlayerManager;
import com.example.aibot.llm.CacheStats;
import com.example.aibot.llm.PromptBuilder;
import com.example.aibot.memory.LandmarkMemory;
import com.example.aibot.memory.LongTermMemory;
import com.example.aibot.memory.ShortTermMemory;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;

import java.util.function.Supplier;

/**
 * /aibot 命令系统（1.21.11 实现）。
 *
 * <p>命令树：</p>
 * <pre>
 * /aibot spawn                              生成假玩家
 * /aibot remove                             移除假玩家
 * /aibot goal &lt;自然语言&gt;                    设置长期目标
 * /aibot auto on|off                        开关自主循环
 * /aibot status                             打印状态（含缓存命中率）
 * /aibot cache stats|reset                  缓存统计
 * /aibot memory show|clear                  记忆查看/清空
 * /aibot do &lt;JSON动作&gt;                      手动执行一个动作（调试用）
 * /aibot config set &lt;key&gt; &lt;value&gt;           修改配置
 * /aibot config show                        显示当前配置
 * </pre>
 */
public final class AIBotCommand {

    private final ConfigStore configStore;
    private final CacheStats cacheStats;
    private final FakePlayerManager playerManager;
    private final ShortTermMemory shortTermMemory;
    private final LongTermMemory longTermMemory;
    private final LandmarkMemory landmarkMemory;
    private final AutoLoop autoLoop;
    private final Supplier<MinecraftServer> serverSupplier;

    public AIBotCommand(ConfigStore configStore,
                        CacheStats cacheStats,
                        FakePlayerManager playerManager,
                        ShortTermMemory shortTermMemory,
                        LongTermMemory longTermMemory,
                        LandmarkMemory landmarkMemory,
                        AutoLoop autoLoop,
                        Supplier<MinecraftServer> serverSupplier) {
        this.configStore = configStore;
        this.cacheStats = cacheStats;
        this.playerManager = playerManager;
        this.shortTermMemory = shortTermMemory;
        this.longTermMemory = longTermMemory;
        this.landmarkMemory = landmarkMemory;
        this.autoLoop = autoLoop;
        this.serverSupplier = serverSupplier;
    }

    /**
     * 注册命令树。
     *
     * <p>注意权限：这里用 {@code Commands.hasPermission} 风格要求 OP（等级 2），
     * 避免普通玩家在多人服务器上滥用 LLM 额度。</p>
     */
    public void register(CommandDispatcher<CommandSourceStack> dispatcher,
                         CommandBuildContext buildContext,
                         Commands.CommandSelection selection) {
        dispatcher.register(Commands.literal("aibot")
                // 需要 OP 权限。
                // 注意 1.21.11 的 API 变更：旧的 src.hasPermission(2) 已移除，
                // 现在通过 permissions().hasPermission(Permission) 判断。
                .requires(src -> src.permissions()
                        .hasPermission(net.minecraft.server.permissions.Permissions.COMMANDS_MODERATOR))

                // ---------------- spawn ----------------
                .then(Commands.literal("spawn")
                        .executes(ctx -> doSpawn(ctx)))

                // ---------------- remove ----------------
                .then(Commands.literal("remove")
                        .executes(ctx -> doRemove(ctx)))

                // ---------------- goal <自然语言> ----------------
                .then(Commands.literal("goal")
                        .then(Commands.argument("text", StringArgumentType.greedyString())
                                .executes(ctx -> doSetGoal(ctx))))

                // ---------------- auto on|off ----------------
                .then(Commands.literal("auto")
                        .then(Commands.literal("on").executes(ctx -> doAuto(ctx, true)))
                        .then(Commands.literal("off").executes(ctx -> doAuto(ctx, false))))

                // ---------------- status ----------------
                .then(Commands.literal("status")
                        .executes(ctx -> doStatus(ctx)))

                // ---------------- cache ----------------
                .then(Commands.literal("cache")
                        .then(Commands.literal("stats").executes(ctx -> doCacheStats(ctx)))
                        .then(Commands.literal("reset").executes(ctx -> doCacheReset(ctx))))

                // ---------------- memory ----------------
                .then(Commands.literal("memory")
                        .then(Commands.literal("show").executes(ctx -> doMemoryShow(ctx)))
                        .then(Commands.literal("clear").executes(ctx -> doMemoryClear(ctx))))

                // ---------------- do <JSON> ----------------
                .then(Commands.literal("do")
                        .then(Commands.argument("json", StringArgumentType.greedyString())
                                .executes(ctx -> doManualAction(ctx))))

                // ---------------- plan ----------------
                .then(Commands.literal("plan")
                        .then(Commands.literal("show").executes(ctx -> doPlanShow(ctx)))
                        .then(Commands.literal("clear").executes(ctx -> doPlanClear(ctx))))

                // ---------------- landmark ----------------
                .then(Commands.literal("landmark")
                        .then(Commands.literal("show").executes(ctx -> doLandmarkShow(ctx)))
                        .then(Commands.literal("clear").executes(ctx -> doLandmarkClear(ctx))))

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

    private int doSpawn(CommandContext<CommandSourceStack> ctx) {
        MinecraftServer server = serverSupplier.get();
        if (server == null) {
            return fail(ctx, "服务器未就绪");
        }
        AIConfig config = configStore.get();
        AIBotPlayer bot = playerManager.spawn(server, config.botName);
        if (bot == null) {
            return fail(ctx, "生成假玩家失败，请查看服务器日志（可能是名字冲突或区块未加载）");
        }
        return ok(ctx, "已生成假玩家 " + config.botName
                + "，位置 " + fmtPos(bot) + "。建议先备份存档再长期挂机。");
    }

    private int doRemove(CommandContext<CommandSourceStack> ctx) {
        MinecraftServer server = serverSupplier.get();
        if (server == null) {
            return fail(ctx, "服务器未就绪");
        }
        // 移除前先停循环，避免残留请求操作已移除的实体
        autoLoop.stop();
        boolean removed = playerManager.remove(server);
        return removed
                ? ok(ctx, "假玩家已移除，自主循环已停止")
                : fail(ctx, "当前没有假玩家");
    }

    private int doSetGoal(CommandContext<CommandSourceStack> ctx) {
        String text = StringArgumentType.getString(ctx, "text");
        autoLoop.setGoal(text);
        return ok(ctx, "长期目标已设置为：" + text);
    }

    private int doAuto(CommandContext<CommandSourceStack> ctx, boolean on) {
        AIConfig config = configStore.get();

        if (on) {
            if (!playerManager.isAlive()) {
                return fail(ctx, "请先执行 /aibot spawn 生成假玩家");
            }
            if (!config.isUsable()) {
                return fail(ctx, "LLM 配置不完整，请先执行：\n"
                        + "  /aibot config set apiKey <你的密钥>\n"
                        + "  /aibot config set baseUrl https://api.deepseek.com");
            }
            autoLoop.start();
            config.autoLoop = true;
            configStore.save();
            return ok(ctx, "自主循环已开启（每 " + config.decisionIntervalTicks
                    + " tick 决策一次，上限 " + config.maxStepsPerSession + " 步）");
        } else {
            autoLoop.stop();
            config.autoLoop = false;
            configStore.save();
            return ok(ctx, "自主循环已停止");
        }
    }

    private int doStatus(CommandContext<CommandSourceStack> ctx) {
        send(ctx, autoLoop.statusReport());
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

    private int doMemoryShow(CommandContext<CommandSourceStack> ctx) {
        send(ctx, "===== 短期记忆 =====\n" + shortTermMemory.serialize());
        send(ctx, "===== 长期记忆 =====\n" + longTermMemory.serialize());
        return 1;
    }

    private int doMemoryClear(CommandContext<CommandSourceStack> ctx) {
        shortTermMemory.clear();
        longTermMemory.clear();
        longTermMemory.save();
        return ok(ctx, "短期与长期记忆已清空");
    }

    /** 手动执行一个动作，用于调试模型输出是否可用。 */
    private int doManualAction(CommandContext<CommandSourceStack> ctx) {
        AIBotPlayer bot = playerManager.getBot();
        if (bot == null) {
            return fail(ctx, "请先 spawn 假玩家");
        }
        String json = StringArgumentType.getString(ctx, "json");
        ActionParser.ParsedAction parsed = ActionParser.parse(json);
        if (parsed == null) {
            return fail(ctx, "无法解析为合法动作 JSON：" + json);
        }
        ActionExecutor executor = new ActionExecutor(bot);
        ActionExecutor.ActionResult result = executor.execute(parsed);
        return result.success()
                ? ok(ctx, "执行成功：" + result.message())
                : fail(ctx, "执行失败：" + result.message());
    }

    /** 显示当前计划。 */
    private int doPlanShow(CommandContext<CommandSourceStack> ctx) {
        send(ctx, "===== 当前执行计划 =====\n" + autoLoop.getPlan().serialize()
                + "\n进度: " + autoLoop.getPlan().doneCount() + "/" + autoLoop.getPlan().size());
        return 1;
    }

    /** 清空计划（下次决策时会重新规划）。 */
    private int doPlanClear(CommandContext<CommandSourceStack> ctx) {
        autoLoop.getPlan().clear();
        return ok(ctx, "计划已清空，下一轮将重新制定");
    }

    /** 显示已知地标。 */
    private int doLandmarkShow(CommandContext<CommandSourceStack> ctx) {
        var player = playerManager.getPlayer();
        if (player == null) {
            send(ctx, "===== 已知地标 =====\n（假玩家未生成，无法计算距离）\n"
                    + "共 " + landmarkMemory.size() + " 个地标");
            return 1;
        }
        var level = player.level();
        String dim = level.dimension().identifier().toString();
        var pos = player.blockPosition();
        send(ctx, "===== 已知地标 =====\n"
                + landmarkMemory.serialize(pos.getX(), pos.getY(), pos.getZ(), dim));
        return 1;
    }

    /** 清空地标。 */
    private int doLandmarkClear(CommandContext<CommandSourceStack> ctx) {
        landmarkMemory.clear();
        return ok(ctx, "地标记忆已清空");
    }

    private int doConfigShow(CommandContext<CommandSourceStack> ctx) {        AIConfig c = configStore.get();
        StringBuilder sb = new StringBuilder("===== AIBot 配置 =====\n");
        sb.append("apiKey: ").append(c.maskedApiKey()).append('\n');
        sb.append("baseUrl: ").append(c.baseUrl).append('\n');
        sb.append("model: ").append(c.model).append('\n');
        sb.append("temperature: ").append(c.temperature).append('\n');
        sb.append("maxTokens: ").append(c.maxTokens).append('\n');
        sb.append("cacheEnabled: ").append(c.cacheEnabled).append('\n');
        sb.append("botName: ").append(c.botName).append('\n');
        sb.append("decisionIntervalTicks: ").append(c.decisionIntervalTicks).append('\n');
        sb.append("maxStepsPerSession: ").append(c.maxStepsPerSession).append('\n');
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
                case "decisionIntervalTicks" -> c.decisionIntervalTicks = Integer.parseInt(value);
                case "maxStepsPerSession" -> c.maxStepsPerSession = Integer.parseInt(value);
                case "requestTimeoutMs" -> c.requestTimeoutMs = Integer.parseInt(value);
                default -> {
                    return fail(ctx, "未知配置项: " + key + "\n可用项: apiKey, baseUrl, model, temperature, "
                            + "maxTokens, cacheEnabled, botName, decisionIntervalTicks, maxStepsPerSession, requestTimeoutMs");
                }
            }
        } catch (NumberFormatException e) {
            return fail(ctx, "数值格式错误: " + value);
        }

        configStore.save();

        // apiKey 回显要脱敏
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

    /** 发送普通信息（绿色）。 */
    private static void send(CommandContext<CommandSourceStack> ctx, String message) {
        ctx.getSource().sendSuccess(() -> Component.literal(message), false);
    }

    /** 发送成功提示。 */
    private static int ok(CommandContext<CommandSourceStack> ctx, String message) {
        ctx.getSource().sendSuccess(() -> Component.literal("[AIBot] " + message), false);
        return 1;
    }

    /** 发送失败提示（红色）。 */
    private static int fail(CommandContext<CommandSourceStack> ctx, String message) {
        ctx.getSource().sendFailure(Component.literal("[AIBot] " + message));
        return 0;
    }

    private static String fmtPos(AIBotPlayer bot) {
        return String.format(java.util.Locale.ROOT, "(%.0f, %.0f, %.0f)",
                bot.getX(), bot.getY(), bot.getZ());
    }

    /** 供主类在启动时打印静态前缀指纹，便于确认缓存前缀未被改动。 */
    public static String promptFingerprint() {
        return PromptBuilder.staticFingerprint();
    }
}
