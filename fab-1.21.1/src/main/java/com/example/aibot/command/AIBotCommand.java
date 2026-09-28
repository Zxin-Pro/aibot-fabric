package com.example.aibot.command;

import com.example.aibot.action.ActionExecutor;
import com.example.aibot.action.ActionParser;
import com.example.aibot.config.AIConfig;
import com.example.aibot.config.ConfigStore;
import com.example.aibot.entity.BotProfile;
import com.example.aibot.entity.BotProfileStore;
import com.example.aibot.entity.MultiBotManager;
import com.example.aibot.llm.CacheStats;
import com.example.aibot.llm.CacheUsageParser;
import com.example.aibot.llm.PromptBuilder;
import com.example.aibot.llm.StaticPrefix;
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
 * /aibot 命令系统（多智能体 + 中转站版）。
 *
 * <p><b>设计目标：玩家只需要两条命令</b></p>
 * <pre>
 *   /aibot config set apiKey  &lt;你的中转站密钥&gt;
 *   /aibot config set baseUrl &lt;你的中转站地址&gt;
 *   /aibot config set model   &lt;模型名&gt;
 *   /aibot spawn 小明          ← 到这里就结束了，它会自己玩
 * </pre>
 *
 * <p>生成之后默认自动开启自主循环（{@code autoStartOnSpawn}），
 * 不需要再敲 auto on。</p>
 *
 * <pre>
 * 【日常使用】
 * /aibot spawn [名字]                          生成并开始自主玩耍
 * /aibot list                                  列出所有智能体
 * /aibot status [名字]                         查看详细状态（含缓存/上游诊断）
 * /aibot remove &lt;名字|all&gt;                     移除
 *
 * 【目标与性格（可选）】
 * /aibot goal &lt;名字&gt; &lt;文本&gt;                   指定长期目标；留空则自己决定
 * /aibot personality &lt;名字&gt; &lt;文本&gt;             设置性格
 *
 * 【自主控制】
 * /aibot auto &lt;名字|all&gt; on|off                开关自主循环
 * /aibot autonomous &lt;名字|all&gt; on|off          开关「无人干预自主模式」
 * /aibot resume &lt;名字&gt;                         死亡后手动拉起
 *
 * 【缓存诊断（中转站专用）】
 * /aibot cache stats                           命中率统计
 * /aibot cache probe                           查看最近一次请求的 usage 原文
 * /aibot cache upstream                        上游轮询诊断
 * /aibot cache reset                           重置统计
 *
 * 【配置】
 * /aibot config set &lt;key&gt; &lt;value&gt;
 * /aibot config show
 * </pre>
 */
public final class AIBotCommand {

    private final ConfigStore configStore;
    private final BotProfileStore profileStore;
    private final CacheStats cacheStats;
    private final MultiBotManager bots;
    private final Supplier<MinecraftServer> serverSupplier;
    /** 供 cache probe 读取最近一次 usage 原文与上游追踪。 */
    private final Supplier<com.example.aibot.llm.LLMClient> llmSupplier;

    public AIBotCommand(ConfigStore configStore,
                        BotProfileStore profileStore,
                        CacheStats cacheStats,
                        MultiBotManager bots,
                        Supplier<MinecraftServer> serverSupplier,
                        Supplier<com.example.aibot.llm.LLMClient> llmSupplier) {
        this.configStore = configStore;
        this.profileStore = profileStore;
        this.cacheStats = cacheStats;
        this.bots = bots;
        this.serverSupplier = serverSupplier;
        this.llmSupplier = llmSupplier;
    }

    /** 注册命令树。需要 OP（等级 2）。 */
    public void register(CommandDispatcher<CommandSourceStack> dispatcher,
                         CommandBuildContext buildContext,
                         Commands.CommandSelection selection) {
        dispatcher.register(Commands.literal("aibot")
                .requires(src -> src.hasPermission(2))

                // ---- spawn [名字] ----
                .then(Commands.literal("spawn")
                        .executes(ctx -> doSpawn(ctx, null))
                        .then(Commands.argument("name", StringArgumentType.word())
                                .executes(ctx -> doSpawn(ctx, StringArgumentType.getString(ctx, "name")))))

                // ---- remove <名字|all> ----
                .then(Commands.literal("remove")
                        .then(Commands.argument("name", StringArgumentType.word())
                                .executes(ctx -> doRemove(ctx, StringArgumentType.getString(ctx, "name")))))

                // ---- list ----
                .then(Commands.literal("list")
                        .executes(ctx -> doList(ctx)))

                // ---- goal <名字> [文本] ----
                .then(Commands.literal("goal")
                        .then(Commands.argument("name", StringArgumentType.word())
                                .executes(ctx -> doSetGoal(ctx, ""))
                                .then(Commands.argument("text", StringArgumentType.greedyString())
                                        .executes(ctx -> doSetGoal(ctx,
                                                StringArgumentType.getString(ctx, "text"))))))

                // ---- personality <名字> <文本> ----
                .then(Commands.literal("personality")
                        .then(Commands.argument("name", StringArgumentType.word())
                                .then(Commands.argument("text", StringArgumentType.greedyString())
                                        .executes(ctx -> doSetPersonality(ctx)))))

                // ---- auto <名字|all> on|off ----
                .then(Commands.literal("auto")
                        .then(Commands.argument("name", StringArgumentType.word())
                                .then(Commands.literal("on").executes(ctx -> doAuto(ctx, true)))
                                .then(Commands.literal("off").executes(ctx -> doAuto(ctx, false)))))

                // ---- autonomous <名字|all> on|off ----
                .then(Commands.literal("autonomous")
                        .then(Commands.argument("name", StringArgumentType.word())
                                .then(Commands.literal("on").executes(ctx -> doAutonomous(ctx, true)))
                                .then(Commands.literal("off").executes(ctx -> doAutonomous(ctx, false)))))

                // ---- resume <名字> ----
                .then(Commands.literal("resume")
                        .then(Commands.argument("name", StringArgumentType.word())
                                .executes(ctx -> doResume(ctx, StringArgumentType.getString(ctx, "name")))))

                // ---- status [名字] ----
                .then(Commands.literal("status")
                        .executes(ctx -> doStatus(ctx, null))
                        .then(Commands.argument("name", StringArgumentType.word())
                                .executes(ctx -> doStatus(ctx, StringArgumentType.getString(ctx, "name")))))

                // ---- cache ----
                .then(Commands.literal("cache")
                        .then(Commands.literal("stats").executes(this::doCacheStats))
                        .then(Commands.literal("probe").executes(this::doCacheProbe))
                        .then(Commands.literal("upstream").executes(this::doCacheUpstream))
                        .then(Commands.literal("reset").executes(this::doCacheReset)))

                // ---- context ----
                .then(Commands.literal("context")
                        .then(Commands.literal("stats").executes(ctx -> doContextStats(ctx, null))
                                .then(Commands.argument("name", StringArgumentType.word())
                                        .executes(ctx -> doContextStats(ctx,
                                                StringArgumentType.getString(ctx, "name")))))
                        .then(Commands.literal("clear").executes(ctx -> doContextClear(ctx, null))
                                .then(Commands.argument("name", StringArgumentType.word())
                                        .executes(ctx -> doContextClear(ctx,
                                                StringArgumentType.getString(ctx, "name"))))))

                // ---- do <名字> <JSON>（调试） ----
                .then(Commands.literal("do")
                        .then(Commands.argument("name", StringArgumentType.word())
                                .then(Commands.argument("json", StringArgumentType.greedyString())
                                        .executes(this::doManualAction))))

                // ---- prompt（查看静态前缀信息） ----
                .then(Commands.literal("prompt")
                        .executes(this::doPromptInfo))

                // ---- config ----
                .then(Commands.literal("config")
                        .then(Commands.literal("show").executes(this::doConfigShow))
                        .then(Commands.literal("set")
                                .then(Commands.argument("key", StringArgumentType.word())
                                        .then(Commands.argument("value", StringArgumentType.greedyString())
                                                .executes(this::doConfigSet))))
                        .then(Commands.literal("path").executes(ctx -> {
                            send(ctx, "配置文件: " + configStore.getConfigFile().toAbsolutePath());
                            return 1;
                        })))
        );
    }

    // ==================================================================
    // 生成 / 移除
    // ==================================================================

    private int doSpawn(CommandContext<CommandSourceStack> ctx, String name) {
        MinecraftServer server = serverSupplier.get();
        if (server == null) {
            return fail(ctx, "服务器未就绪");
        }
        AIConfig config = configStore.get();

        if (!config.isUsable()) {
            return fail(ctx, "LLM 配置不完整，缺少: " + config.missingFields()
                    + "\n请先执行：\n"
                    + "  /aibot config set apiKey <你的中转站密钥>\n"
                    + "  /aibot config set baseUrl <你的中转站地址>\n"
                    + "  /aibot config set model <模型名>");
        }

        if (bots.size() >= config.maxBots) {
            return fail(ctx, "已达智能体上限 " + config.maxBots
                    + " 个，请先移除一些，或用 /aibot config set maxBots <数量> 调高");
        }

        String botName = name != null ? name : config.botName;

        BotProfile profile = profileStore.get(botName);
        boolean isNew = profile == null;
        if (isNew) {
            profile = new BotProfile(botName);
            // 新档案：把配置里的默认目标带进来（可能为空 = 让它自己决定）
            profile.goal = config.defaultGoal == null ? "" : config.defaultGoal;
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

        // 【关键】spawn 后自动开始自主玩耍，玩家不需要再敲任何命令。
        boolean autoStarted = false;
        if (config.autoStartOnSpawn && !agent.loop.isRunning()) {
            agent.loop.start();
            agent.profile.autoLoop = true;
            profileStore.put(agent.profile);
            profileStore.save();
            autoStarted = true;
        }

        ServerPlayer p = agent.player();
        StringBuilder sb = new StringBuilder();
        sb.append("已生成智能体 ").append(botName)
                .append(p != null ? "，位置 " + fmtPos(p) : "").append("\n");
        if (autoStarted) {
            sb.append("✅ 自主循环已自动开启——它会自己玩，不需要你再管它。\n");
            sb.append("   目标：")
                    .append(agent.loop.getGoal().isEmpty()
                            ? "未指定（由它依据「先生存、再发展」自行决定）"
                            : agent.loop.getGoal())
                    .append("\n");
        } else if (!config.autoStartOnSpawn) {
            sb.append("提示：autoStartOnSpawn 已关闭，需手动执行 /aibot auto ")
                    .append(botName).append(" on 才会开始自主活动。\n");
        }
        sb.append("查看状态：/aibot status ").append(botName).append("\n");
        sb.append("想让它一直安静地玩：/aibot config set chatAnnounceLevel 0");
        return ok(ctx, sb.toString());
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
                        .append(a.loop.isAutonomous() ? " [自主模式]" : "")
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

    private int doSetGoal(CommandContext<CommandSourceStack> ctx, String text) {
        String name = StringArgumentType.getString(ctx, "name");
        MultiBotManager.Agent a = bots.get(name);
        if (a == null) {
            return fail(ctx, "找不到智能体: " + name);
        }
        a.loop.setGoal(text);
        a.profile.goal = text;
        profileStore.put(a.profile);
        profileStore.save();
        if (text.isEmpty()) {
            return ok(ctx, "已清除 " + name + " 的目标——它将按自主模式自行决定要做什么。");
        }
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
        return ok(ctx, "已设置 " + name + " 的性格：" + text + "\n（下一轮决策起生效）");
    }

    private int doAuto(CommandContext<CommandSourceStack> ctx, boolean on) {
        String name = StringArgumentType.getString(ctx, "name");
        AIConfig config = configStore.get();

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
                return fail(ctx, "LLM 配置不完整，缺少: " + config.missingFields());
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
                return fail(ctx, "LLM 配置不完整，缺少: " + config.missingFields());
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
                + (config.maxStepsPerSession <= 0 ? "无步数上限）" : "上限 "
                + config.maxStepsPerSession + " 步）") : ""));
    }

    /** 开关「无人干预自主模式」。 */
    private int doAutonomous(CommandContext<CommandSourceStack> ctx, boolean on) {
        String name = StringArgumentType.getString(ctx, "name");
        if ("all".equalsIgnoreCase(name)) {
            int n = 0;
            for (MultiBotManager.Agent a : bots.all()) {
                a.loop.setAutonomous(on);
                n++;
            }
            return ok(ctx, "已" + (on ? "开启" : "关闭") + "全部 " + n
                    + " 个智能体的自主模式"
                    + (on ? "（无人干预时它们会自己找事做）" : ""));
        }
        MultiBotManager.Agent a = bots.get(name);
        if (a == null) {
            return fail(ctx, "找不到智能体: " + name);
        }
        a.loop.setAutonomous(on);
        return ok(ctx, name + " 的自主模式已" + (on ? "开启" : "关闭")
                + (on ? "：没有玩家命令时它会自行安排目标并推进" : "：它将等待你的指令"));
    }

    /** 手动拉起一个已死亡/未生成的智能体并开启自主循环。 */
    private int doResume(CommandContext<CommandSourceStack> ctx, String name) {
        MinecraftServer server = serverSupplier.get();
        if (server == null) {
            return fail(ctx, "服务器未就绪");
        }
        MultiBotManager.Agent a = bots.get(name);
        if (a == null) {
            return fail(ctx, "找不到智能体: " + name + "。用 /aibot spawn " + name + " 生成它。");
        }
        if (!a.alive()) {
            ServerPlayer revived = bots.respawn(server, name);
            if (revived == null) {
                return fail(ctx, "重生失败，请查看服务器日志");
            }
        }
        if (!a.loop.isRunning()) {
            a.loop.start();
        }
        return ok(ctx, name + " 已恢复运行");
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

    // ==================================================================
    // 缓存诊断
    // ==================================================================

    private int doCacheStats(CommandContext<CommandSourceStack> ctx) {
        send(ctx, cacheStats.report());
        return 1;
    }

    private int doCacheReset(CommandContext<CommandSourceStack> ctx) {
        cacheStats.reset();
        com.example.aibot.llm.LLMClient c = llmSupplier.get();
        if (c != null) {
            c.upstreamTracker().reset();
        }
        return ok(ctx, "缓存与上游统计已重置");
    }

    /**
     * 查看最近一次请求的 usage 原文。
     *
     * <p>这是排查「我的中转站到底有没有缓存」最直接的手段：
     * 用户能亲眼看到服务端返回了哪些字段。</p>
     */
    private int doCacheProbe(CommandContext<CommandSourceStack> ctx) {
        com.example.aibot.llm.LLMClient c = llmSupplier.get();
        if (c == null) {
            return fail(ctx, "LLM 客户端尚未初始化");
        }
        StringBuilder sb = new StringBuilder();
        sb.append("===== 缓存探测 =====\n");
        sb.append("说明：下面是服务端最近一次返回的 usage 字段原文。\n");
        sb.append("缓存是否生效，看这里有没有出现「命中」类字段。\n\n");

        String raw = c.lastUsageRaw();
        if (raw == null || raw.isEmpty()) {
            sb.append("(还没有成功请求过，暂无数据)\n");
        } else {
            sb.append("最近一次 usage:\n").append(raw).append("\n");
        }

        String snippet = c.lastResponseSnippet();
        if (snippet != null && !snippet.isEmpty()) {
            sb.append("\n最近一次错误响应片段:\n").append(snippet).append("\n");
        }

        sb.append("\n本模组会自动识别的命中字段（按优先级）:\n");
        sb.append("  ").append(CacheUsageParser.knownHitFields()).append("\n");
        sb.append("若你的中转站用了别的字段名，可手动指定：\n");
        sb.append("  /aibot config set cacheHitField <字段名>\n");

        if (!cacheStats.lastSourceField().isEmpty()) {
            sb.append("\n本次实际读到的字段: ").append(cacheStats.lastSourceField()).append("\n");
        }
        send(ctx, sb.toString());
        return 1;
    }

    private int doCacheUpstream(CommandContext<CommandSourceStack> ctx) {
        com.example.aibot.llm.LLMClient c = llmSupplier.get();
        if (c == null) {
            return fail(ctx, "LLM 客户端尚未初始化");
        }
        send(ctx, c.upstreamTracker().diagnose());
        return 1;
    }

    // ==================================================================
    // 上下文
    // ==================================================================

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
        sb.append('\n').append("策略：保留最近 ").append(configStore.get().contextWindow)
                .append(" 条完整细节，更早的按批聚合成摘要，并按 ")
                .append(configStore.get().contextTokenBudget)
                .append(" token 预算硬性裁剪。\n")
                .append("这让提示词长度保持恒定，是「可以一直玩下去」的前提。");
        send(ctx, sb.toString());
        return 1;
    }

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

    // ==================================================================
    // 调试
    // ==================================================================

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

        if (result.async()) {
            agent.loop.adoptExternalAction(executor);
            return ok(ctx, "动作已启动：" + result.message()
                    + "\n（该动作需要若干 tick 完成，可用 /aibot status " + name + " 查看）");
        }
        return result.success()
                ? ok(ctx, "执行成功：" + result.message())
                : fail(ctx, "执行失败：" + result.message());
    }

    /** 展示静态前缀信息，帮助用户确认缓存基础是否健康。 */
    private int doPromptInfo(CommandContext<CommandSourceStack> ctx) {
        StringBuilder sb = new StringBuilder("===== 提示词结构 =====\n");
        sb.append("请求由 4 条消息组成，按「变化频率」从低到高排列：\n");
        sb.append("  [0] system  静态系统提示词      ── 永不变，全 bot 共享\n");
        sb.append("  [1] user    静态动作协议        ── 永不变，全 bot 共享\n");
        sb.append("  [2] user    慢变层（身份+长期经验）── 每会话/若干步才变\n");
        sb.append("  [3] user    快变层（状态+计划+反馈）── 每轮都变\n\n");
        sb.append("这样第 3 条无论怎么变，都不会影响前 3 条的缓存。\n\n");
        sb.append("静态前缀指纹: ").append(PromptBuilder.staticFingerprint()).append("\n");
        sb.append("静态前缀估算 token: ~").append(PromptBuilder.staticTokenEstimate()).append("\n");
        sb.append("（这一部分理论上每次请求都能命中缓存，是省钱的主体）\n\n");
        String warn = StaticPrefix.selfCheck();
        if (warn != null) {
            sb.append("[警告] ").append(warn).append("\n");
        } else {
            sb.append("自检：通过（未发现动态内容污染静态前缀）\n");
        }
        send(ctx, sb.toString());
        return 1;
    }

    // ==================================================================
    // 配置
    // ==================================================================

    private int doConfigShow(CommandContext<CommandSourceStack> ctx) {
        AIConfig c = configStore.get();
        StringBuilder sb = new StringBuilder("===== AIBot 配置 =====\n【连接】\n");
        sb.append("  apiKey: ").append(c.maskedApiKey()).append('\n');
        sb.append("  baseUrl: ").append(c.baseUrl.isEmpty() ? "(未设置)" : c.baseUrl).append('\n');
        sb.append("  model: ").append(c.model.isEmpty() ? "(未设置)" : c.model).append('\n');
        sb.append("  temperature: ").append(c.temperature).append('\n');
        sb.append("  maxTokens: ").append(c.maxTokens <= 0 ? "(不发送)" : c.maxTokens).append('\n');
        sb.append("  requestTimeoutMs: ").append(c.requestTimeoutMs).append('\n');
        sb.append("  maxRetries: ").append(c.maxRetries).append('\n');
        sb.append("  minRequestIntervalMs: ").append(c.minRequestIntervalMs).append('\n');
        sb.append("  extraHeaders: ").append(c.extraHeaders.isEmpty() ? "(无)" : "已设置").append('\n');

        sb.append("【缓存与计费】\n");
        sb.append("  cacheHitField: ").append(c.cacheHitField.isEmpty() ? "(自动探测)" : c.cacheHitField).append('\n');
        sb.append("  单价(未命中/命中/输出): ").append(c.pricePerMillionInputMiss).append(" / ")
                .append(c.pricePerMillionInputHit).append(" / ").append(c.pricePerMillionOutput).append('\n');

        sb.append("【自主行为】\n");
        sb.append("  autonomousMode: ").append(c.autonomousMode)
                .append("（无人干预时自主游玩）").append('\n');
        sb.append("  autoStartOnSpawn: ").append(c.autoStartOnSpawn)
                .append("（spawn 后自动开始）").append('\n');
        sb.append("  autoLoop: ").append(c.autoLoop).append('\n');
        sb.append("  decisionIntervalTicks: ").append(c.decisionIntervalTicks).append('\n');
        sb.append("  maxStepsPerSession: ").append(c.maxStepsPerSession)
                .append(c.maxStepsPerSession <= 0 ? "（无上限）" : "").append('\n');
        sb.append("  autoRespawn: ").append(c.autoRespawn).append('\n');
        sb.append("  autoRestart: ").append(c.autoRestart).append('\n');
        sb.append("  survivalReflex: ").append(c.survivalReflex).append('\n');
        sb.append("  defaultGoal: ").append(c.defaultGoal.isEmpty() ? "(空，由智能体自行决定)" : c.defaultGoal).append('\n');

        sb.append("【输出】\n");
        sb.append("  chatAnnounceLevel: ").append(c.chatAnnounceLevel)
                .append(c.chatAnnounceLevel == 0 ? "（静默）" : "").append('\n');
        sb.append("  allowChat: ").append(c.allowChat)
                .append("（是否允许智能体说话）").append('\n');
        sb.append("  chatCooldownSteps: ").append(c.chatCooldownSteps).append('\n');

        sb.append("【多智能体与上下文】\n");
        sb.append("  botName: ").append(c.botName).append('\n');
        sb.append("  maxBots: ").append(c.maxBots).append('\n');
        sb.append("  autoSpawnOnStart: ").append(c.autoSpawnOnStart).append('\n');
        sb.append("  contextWindow: ").append(c.contextWindow).append('\n');
        sb.append("  contextTokenBudget: ").append(c.contextTokenBudget).append('\n');

        sb.append("配置文件: ").append(configStore.getConfigFile().toAbsolutePath());
        send(ctx, sb.toString());
        return 1;
    }

    private int doConfigSet(CommandContext<CommandSourceStack> ctx) {
        String key = StringArgumentType.getString(ctx, "key");
        // 保留原始值（含空格），只去掉首尾空白
        String value = StringArgumentType.getString(ctx, "value").trim();
        AIConfig c = configStore.get();

        try {
            switch (key) {
                // ---- 连接 ----
                case "apiKey" -> c.apiKey = value;
                case "baseUrl" -> {
                    if (!value.startsWith("http://") && !value.startsWith("https://")) {
                        return fail(ctx, "baseUrl 必须以 http:// 或 https:// 开头");
                    }
                    c.baseUrl = value;
                }
                case "model" -> c.model = value;
                case "extraHeaders" -> {
                    // 支持用 "\n" 字面量分隔多行，方便在游戏内一条命令写完
                    c.extraHeaders = value.replace("\\n", "\n");
                }
                case "temperature" -> c.temperature = Double.parseDouble(value);
                case "maxTokens" -> c.maxTokens = Integer.parseInt(value);
                case "requestTimeoutMs" -> c.requestTimeoutMs = Integer.parseInt(value);
                case "connectTimeoutSeconds" -> c.connectTimeoutSeconds = Integer.parseInt(value);
                case "maxRetries" -> c.maxRetries = Integer.parseInt(value);
                case "minRequestIntervalMs" -> c.minRequestIntervalMs = Long.parseLong(value);

                // ---- 缓存与计费 ----
                case "cacheHitField" -> c.cacheHitField = value;
                case "pricePerMillionInputMiss" -> c.pricePerMillionInputMiss = Double.parseDouble(value);
                case "pricePerMillionInputHit" -> c.pricePerMillionInputHit = Double.parseDouble(value);
                case "pricePerMillionOutput" -> c.pricePerMillionOutput = Double.parseDouble(value);

                // ---- 自主行为 ----
                case "autonomousMode" -> c.autonomousMode = parseBool(value);
                case "autoStartOnSpawn" -> c.autoStartOnSpawn = parseBool(value);
                case "autoLoop" -> c.autoLoop = parseBool(value);
                case "decisionIntervalTicks" -> c.decisionIntervalTicks = Integer.parseInt(value);
                case "maxStepsPerSession" -> c.maxStepsPerSession = Integer.parseInt(value);
                case "autoRestart" -> c.autoRestart = parseBool(value);
                case "autoRespawn" -> c.autoRespawn = parseBool(value);
                case "survivalReflex" -> c.survivalReflex = parseBool(value);
                case "persistPlan" -> c.persistPlan = parseBool(value);
                case "stepTimeoutTicks" -> c.stepTimeoutTicks = Integer.parseInt(value);
                case "stuckThreshold" -> c.stuckThreshold = Integer.parseInt(value);
                case "respawnDelayTicks" -> c.respawnDelayTicks = Integer.parseInt(value);
                case "defaultGoal" -> c.defaultGoal = value;

                // ---- 输出 ----
                case "chatAnnounceLevel" -> c.chatAnnounceLevel = Integer.parseInt(value);
                case "allowChat" -> c.allowChat = parseBool(value);
                case "chatCooldownSteps" -> c.chatCooldownSteps = Integer.parseInt(value);

                // ---- 多智能体与上下文 ----
                case "botName" -> c.botName = value;
                case "maxBots" -> c.maxBots = Integer.parseInt(value);
                case "autoSpawnOnStart" -> c.autoSpawnOnStart = parseBool(value);
                case "contextWindow" -> c.contextWindow = Integer.parseInt(value);
                case "contextTokenBudget" -> c.contextTokenBudget = Integer.parseInt(value);

                default -> {
                    return fail(ctx, "未知配置项: " + key + "\n可用项见 /aibot config show");
                }
            }
        } catch (NumberFormatException e) {
            return fail(ctx, "数值格式错误: " + value);
        }

        configStore.save();

        // 单价变化要同步给统计器，否则费用估算不会更新
        com.example.aibot.llm.LLMClient cl = llmSupplier.get();
        if (cl != null) {
            cacheStats.setPrices(c.pricePerMillionInputMiss,
                    c.pricePerMillionInputHit, c.pricePerMillionOutput);
        }

        String shown = key.equals("apiKey") ? c.maskedApiKey() : value;
        String extra = "";
        if (key.equals("maxStepsPerSession") && c.maxStepsPerSession <= 0) {
            extra = "\n（0 或负数 = 无上限，智能体会一直玩下去）";
        } else if (key.equals("chatAnnounceLevel")) {
            extra = c.chatAnnounceLevel == 0 ? "\n（静默模式：不会在聊天栏刷系统消息）" : "";
        } else if (key.equals("baseUrl") || key.equals("model")) {
            extra = "\n注意：更换地址或模型会让缓存全部冷启动，命中率会先降后升。";
        }
        return ok(ctx, "配置已更新: " + key + " = " + shown + extra);
    }

    // ==================================================================
    // 辅助
    // ==================================================================

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
}
