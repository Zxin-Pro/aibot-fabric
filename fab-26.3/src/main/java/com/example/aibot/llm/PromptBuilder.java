package com.example.aibot.llm;

/**
 * 提示词构建器：把「静态前缀」与「动态后缀」拼装成最终请求。
 *
 * <p><b>缓存设计的核心契约：</b></p>
 * <ol>
 *   <li>{@link #systemMessage()} 永远返回 {@link StaticPrefix#SYSTEM_PROMPT}，
 *       任何情况下都不得拼接运行时数据。</li>
 *   <li>{@link #staticUserMessage()} 永远返回同一份动作说明文本，
 *       同一进程内多次调用结果必须完全一致。</li>
 *   <li>{@link #dynamicUserMessage()} 承载所有变化内容：状态、记忆、目标、反馈。</li>
 * </ol>
 *
 * <p>这样 API 服务端的 prompt cache 可以缓存前两条消息的 KV，
 * 只有第三条需要重新计算，从而最大化命中率并降低延迟。</p>
 */
public final class PromptBuilder {

    /**
     * 构建完成的提示词三件套。
     *
     * @param systemMessage      第 0 条 system（静态，永不变化）
     * @param staticUserMessage  第 1 条 user（静态，永不变化）
     * @param dynamicUserMessage 第 2 条 user（动态，每轮变化）
     */
    public record BuiltPrompt(String systemMessage,
                              String staticUserMessage,
                              String dynamicUserMessage) {
    }

    /**
     * 静态 user 消息。
     *
     * <p>内容是动作协议的重申 + 输出格式约束，属于「不随状态变化」的内容，
     * 因此放在静态区，享受缓存收益。</p>
     */
    private static final String STATIC_USER =
            "请阅读下面的动态上下文，然后按系统提示词中定义的动作 Schema，"
            + "只输出一个 JSON 对象作为你的下一步动作。\n"
            + "再次强调：不要输出 Markdown 代码块标记，不要输出解释文字，"
            + "只输出形如 {\"action\":\"...\",\"reason\":\"...\"} 的单个 JSON 对象。\n"
            + "字段顺序请保持：action 在前，其余参数居中，reason 在最后。\n"
            + StaticPrefix.ACTION_JSON_SCHEMA_HINT;

    private PromptBuilder() {
    }

    /**
     * 构建提示词（长期自主模式）。
     *
     * @param stateJson      当前世界状态 JSON（字段顺序必须固定，由 StateCollector 保证）
     * @param shortTermMemory 短期记忆（最近若干条动作及结果，固定格式）
     * @param longTermMemory  长期记忆摘要（固定格式）
     * @param plan            当前执行计划（任务栈，固定格式）
     * @param landmarks       地标记忆（含相对当前位置的距离）
     * @param goal           当前长期目标，可为空字符串
     * @param lastFeedback    上一步动作的执行结果反馈，可为空字符串
     * @return 构建好的提示词
     */
    public static BuiltPrompt build(String stateJson,
                                    String shortTermMemory,
                                    String longTermMemory,
                                    String plan,
                                    String landmarks,
                                    String goal,
                                    String lastFeedback) {
        StringBuilder sb = new StringBuilder(2048);

        sb.append(StaticPrefix.DYNAMIC_SECTION_HEADER);

        // 固定的小节顺序，且顺序本身也是固定的（缓存友好）。
        // 把「目标 + 计划」放最前，因为这是模型最需要的上下文。
        sb.append("\n[当前长期目标]\n");
        sb.append(goal == null || goal.trim().isEmpty() ? "(未设定，以生存和探索为主)" : goal.trim());
        sb.append("\n");

        sb.append("\n[当前执行计划]\n");
        sb.append(plan == null || plan.trim().isEmpty() ? "(无计划)" : plan.trim());
        sb.append("\n");
        sb.append("说明：计划中用 [>] 标记的是你当前正在进行的一步。");
        sb.append("请优先完成当前这一步；完成后系统会自动推进到下一步。\n");

        sb.append("\n[已知地标]\n");
        sb.append(landmarks == null || landmarks.trim().isEmpty() ? "(暂无)" : landmarks.trim());
        sb.append("\n");

        sb.append("\n[当前世界状态]\n");
        sb.append(stateJson == null ? "{}" : stateJson);
        sb.append("\n");

        sb.append("\n[短期记忆（最近的动作与结果）]\n");
        sb.append(shortTermMemory == null || shortTermMemory.trim().isEmpty() ? "(暂无)" : shortTermMemory);
        sb.append("\n");

        sb.append("\n[长期记忆（成功/失败经验）]\n");
        sb.append(longTermMemory == null || longTermMemory.trim().isEmpty() ? "(暂无)" : longTermMemory);
        sb.append("\n");

        if (lastFeedback != null && !lastFeedback.trim().isEmpty()) {
            sb.append("\n[上一步执行结果]\n");
            sb.append(lastFeedback.trim());
            sb.append("\n");
        }

        sb.append("\n请输出下一步动作 JSON：\n");

        return new BuiltPrompt(StaticPrefix.SYSTEM_PROMPT, STATIC_USER, sb.toString());
    }

    /**
     * 构建提示词（简化重载，无计划与地标，兼容旧调用）。
     */
    public static BuiltPrompt build(String stateJson,
                                    String shortTermMemory,
                                    String longTermMemory,
                                    String goal,
                                    String lastFeedback) {
        return build(stateJson, shortTermMemory, longTermMemory, "", "", goal, lastFeedback);
    }

    /**
     * 用于测试/诊断：返回静态部分的指纹（长度 + 哈希）。
     *
     * <p>如果两次运行之间这个指纹变了，就说明有人改动了静态前缀，
     * 线上缓存命中率会掉，需要排查。</p>
     */
    public static String staticFingerprint() {
        String combined = StaticPrefix.SYSTEM_PROMPT + "\u0000" + STATIC_USER;
        int hash = combined.hashCode();
        return "len=" + combined.length() + " hash=" + Integer.toHexString(hash);
    }
}
