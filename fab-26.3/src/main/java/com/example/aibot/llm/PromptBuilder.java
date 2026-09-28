package com.example.aibot.llm;

/**
 * 提示词构建器：把提示词拆成「缓存收益递减」的三层。
 *
 * <p><b>为什么必须分层（这是命中率能不能上去的关键）</b></p>
 *
 * <p>服务端的前缀缓存规则是：<b>从第一个字节不同的位置开始，后面全部失效</b>。
 * 所以决定命中率的是「变化点在消息序列里的位置」—— 变化点越靠后，
 * 能被复用的前缀越长。</p>
 *
 * <p>旧版把身份、目标、计划、地标、状态、记忆、聊天、反馈全部塞进同一条
 * user 消息里。这条消息从第一个字符就在变（身份因人而异、目标随时可改），
 * 导致它<b>整条</b>都要重算，其中体积最大的「长期记忆、地标」也跟着陪葬。</p>
 *
 * <p>现在拆成三条，按变化频率从低到高排列：</p>
 *
 * <pre>
 *   [0] system   静态系统提示词           —— 永不变，全 bot 共享
 *   [1] user     静态动作协议             —— 永不变，全 bot 共享
 *   [2] user     慢变层：能力说明 + 长期经验 —— 每会话/每若干步才变
 *   [3] user     快变层：状态 + 计划 + 反馈 —— 每轮都变
 * </pre>
 *
 * <p>效果：第 3 条无论怎么变，都不会影响第 0~2 条的缓存。
 * 慢变层里的长期记忆更新频率低（只有动作结算时才写），
 * 因此它也能在多数请求里命中。</p>
 *
 * <p><b>身份的处理</b>：名字与性格每个 bot 都不同，但<b>单个 bot 自己的名字和性格
 * 在整个生命周期里是恒定的</b>。因此它属于「慢变层」而不是「快变层」——
 * 放慢变层后，同一个 bot 的连续请求可以复用这一段；
 * 放快变层则每个 bot 都从第 3 条就分叉，浪费了前两条之后的全部缓存。</p>
 */
public final class PromptBuilder {

    /**
     * 构建完成的提示词（分层）。
     *
     * @param systemMessage      第 0 条 system（静态，永不变化，全 bot 共享）
     * @param staticUserMessage  第 1 条 user（静态，永不变化，全 bot 共享）
     * @param slowUserMessage    第 2 条 user（慢变：身份 + 长期经验）
     * @param dynamicUserMessage 第 3 条 user（快变：状态 + 计划 + 反馈）
     */
    public record BuiltPrompt(String systemMessage,
                              String staticUserMessage,
                              String slowUserMessage,
                              String dynamicUserMessage) {
    }

    /**
     * 静态 user 消息（第 1 条）。
     *
     * <p>内容是动作协议的重申与输出格式约束，属于「不随状态变化」的内容，
     * 因此放在静态区享受缓存收益。</p>
     */
    private static final String STATIC_USER =
            "请阅读下面的上下文，然后按系统提示词中定义的动作 Schema，"
            + "只输出一个 JSON 对象作为你的下一步动作。\n"
            + "再次强调：不要输出 Markdown 代码块标记，不要输出解释文字，"
            + "只输出形如 {\"action\":\"...\",\"reason\":\"...\"} 的单个 JSON 对象。\n"
            + "字段顺序请保持：action 在前，其余参数居中，reason 在最后。\n"
            + StaticPrefix.ACTION_JSON_SCHEMA_HINT;

    private PromptBuilder() {
    }

    /**
     * 构建分层提示词（完整版）。
     *
     * @param stateJson       当前世界状态 JSON（字段顺序固定，由 StateCollector 保证）
     * @param shortTermMemory 短期记忆（走压缩器，长度恒定）
     * @param longTermMemory  长期记忆摘要（变化频率低）
     * @param plan            当前执行计划
     * @param landmarks       地标记忆（变化频率低）
     * @param goal            当前长期目标
     * @param lastFeedback    上一步动作的结果反馈
     * @param botName         智能体名字
     * @param personality     性格设定
     * @param chatText        最近听到的聊天
     * @return 分层后的提示词
     */
    public static BuiltPrompt build(String stateJson,
                                    String shortTermMemory,
                                    String longTermMemory,
                                    String plan,
                                    String landmarks,
                                    String goal,
                                    String lastFeedback,
                                    String botName,
                                    String personality,
                                    String chatText) {
        return build(stateJson, shortTermMemory, longTermMemory, plan, landmarks,
                goal, lastFeedback, botName, personality, chatText, true);
    }

    /**
     * 构建分层提示词（可控是否启用自主模式的强化指令）。
     *
     * @param autonomousMode 是否处于自主模式（无玩家干预时的自驱指令）
     */
    public static BuiltPrompt build(String stateJson,
                                    String shortTermMemory,
                                    String longTermMemory,
                                    String plan,
                                    String landmarks,
                                    String goal,
                                    String lastFeedback,
                                    String botName,
                                    String personality,
                                    String chatText,
                                    boolean autonomousMode) {
        // ============================================================
        // 第 2 条：慢变层
        //
        // 内容按「几乎不变 → 偶尔变化」排列：
        //   身份（一个 bot 一生不变）→ 长期经验（动作结算时才写）
        // 这样同一 bot 的连续请求能复用这一整段。
        // ============================================================
        StringBuilder slow = new StringBuilder(1536);
        slow.append("\n===== 你的身份与经验（长期稳定，不会每轮变化）=====\n");

        if (botName != null && !botName.trim().isEmpty()) {
            slow.append("\n[你的身份]\n");
            slow.append("你的名字是 ").append(botName.trim()).append("。\n");
            if (personality != null && !personality.trim().isEmpty()) {
                slow.append("你的性格与行事风格：").append(personality.trim()).append("\n");
                slow.append("请让这个性格体现在你的动作选择里。\n");
            }
        }

        slow.append("\n[长期记忆（成功/失败经验）]\n");
        slow.append(longTermMemory == null || longTermMemory.trim().isEmpty()
                ? "(暂无)" : longTermMemory.trim());
        slow.append("\n");

        // ============================================================
        // 第 3 条：快变层
        //
        // 全部是每轮都可能变动的内容。变化集中在这一条，
        // 前面的缓存因此不受影响。
        // ============================================================
        StringBuilder dyn = new StringBuilder(2048);
        dyn.append("\n===== 当前情境（每轮更新）=====\n");

        dyn.append("\n[当前长期目标]\n");
        dyn.append(goal == null || goal.trim().isEmpty()
                ? "(未设定——请依据自主模式规则自行确立目标)" : goal.trim());
        dyn.append("\n");

        dyn.append("\n[当前执行计划]\n");
        dyn.append(plan == null || plan.trim().isEmpty() ? "(无计划)" : plan.trim());
        dyn.append("\n");
        dyn.append("说明：计划中用 [>] 标记的是你当前正在进行的一步。\n");

        dyn.append("\n[已知地标]\n");
        dyn.append(landmarks == null || landmarks.trim().isEmpty() ? "(暂无)" : landmarks.trim());
        dyn.append("\n");

        dyn.append("\n[当前世界状态]\n");
        dyn.append(stateJson == null ? "{}" : stateJson);
        dyn.append("\n");

        dyn.append("\n[聊天记录（你听到的其他玩家发言）]\n");
        dyn.append(chatText == null || chatText.trim().isEmpty() ? "(暂无)" : chatText.trim());
        dyn.append("\n");

        dyn.append("\n[近期动作与结果]\n");
        dyn.append(shortTermMemory == null || shortTermMemory.trim().isEmpty()
                ? "(暂无)" : shortTermMemory.trim());
        dyn.append("\n");

        if (lastFeedback != null && !lastFeedback.trim().isEmpty()) {
            dyn.append("\n[上一步执行结果]\n");
            dyn.append(lastFeedback.trim());
            dyn.append("\n");
        }

        // 自主模式指令放最末尾：它是每轮都可能出现的短线内容，
        // 放在这里可以保证它之前的所有内容都还是「同一份前缀」。
        if (autonomousMode) {
            dyn.append("\n[自主模式]\n");
            dyn.append("没有玩家给你下达命令，你必须自己决定下一步做什么。\n");
            dyn.append("按这个优先级自己安排：\n");
            dyn.append("  1) 先活下来：血量、饥饿、夜晚庇护、避开敌对生物；\n");
            dyn.append("  2) 再做发展：收集资源 → 制作工具 → 建造据点 → 探索与升级装备；\n");
            dyn.append("  3) 没有明确目标时，从「生存所需」里自己挑一件推进，不要原地发呆。\n");
            dyn.append("不要因为「没人告诉我做什么」而输出 idle；持续给自己找事做。\n");
        }

        dyn.append("\n请输出下一步动作 JSON：\n");

        return new BuiltPrompt(StaticPrefix.SYSTEM_PROMPT, STATIC_USER,
                slow.toString(), dyn.toString());
    }

    /** 简化重载（无身份/聊天）。 */
    public static BuiltPrompt build(String stateJson,
                                    String shortTermMemory,
                                    String longTermMemory,
                                    String goal,
                                    String lastFeedback) {
        return build(stateJson, shortTermMemory, longTermMemory, "", "", goal,
                lastFeedback, "", "", "");
    }

    /**
     * 用于测试/诊断：返回静态部分的指纹。
     *
     * <p>两次运行之间这个指纹变了，就说明静态前缀被改动，
     * 线上缓存会全部冷启动一次。启动日志里会打印它。</p>
     */
    public static String staticFingerprint() {
        String combined = StaticPrefix.SYSTEM_PROMPT + "\u0000" + STATIC_USER;
        int hash = combined.hashCode();
        return "len=" + combined.length() + " hash=" + Integer.toHexString(hash);
    }

    /** 静态部分的估算 token 量（用于展示「最多能缓存多少」）。 */
    public static int staticTokenEstimate() {
        return com.example.aibot.memory.ContextCompressor.estimateTokens(
                StaticPrefix.SYSTEM_PROMPT + STATIC_USER);
    }
}
