package com.example.aibot.llm;

/**
 * PromptBuilder 测试：验证缓存分层的两个硬性要求。
 *
 * <ol>
 *   <li><b>静态前缀必须逐字节稳定</b>：多次调用结果完全一致。
 *       只要有一处不稳定，线上缓存就永远命中不了。</li>
 *   <li><b>动态内容不得污染静态层</b>：状态/计划/名字变化时，
 *       system 与 staticUser 必须保持不变。</li>
 * </ol>
 */
public class PromptBuilderTest {

    private static int passed = 0, failed = 0;

    public static void main(String[] args) {
        System.out.println("===== PromptBuilder 分层测试 =====");

        // ---- 1. 静态前缀稳定性 ----
        PromptBuilder.BuiltPrompt a = build("state-A", "mem-A", "ltm-A", "plan-A",
                "lm-A", "goal-A", "fb-A", "BotA", "pA", "chat-A");
        PromptBuilder.BuiltPrompt b = build("state-B", "mem-B", "ltm-B", "plan-B",
                "lm-B", "goal-B", "fb-B", "BotB", "pB", "chat-B");

        check("system 在不同调用间完全一致",
                a.systemMessage().equals(b.systemMessage()));
        check("staticUser 在不同调用间完全一致",
                a.staticUserMessage().equals(b.staticUserMessage()));

        // ---- 2. 动态内容不得出现在静态层 ----
        check("静态层不含状态数据", !a.systemMessage().contains("state-A")
                && !a.staticUserMessage().contains("state-A"));
        check("静态层不含 bot 名字", !a.systemMessage().contains("BotA")
                && !a.staticUserMessage().contains("BotA"));
        check("静态层不含目标", !a.systemMessage().contains("goal-A")
                && !a.staticUserMessage().contains("goal-A"));

        // ---- 3. 慢变层承载身份 ----
        check("慢变层含 bot 名字", a.slowUserMessage().contains("BotA"));
        check("慢变层含性格", a.slowUserMessage().contains("pA"));
        check("慢变层含长期记忆", a.slowUserMessage().contains("ltm-A"));

        // ---- 4. 快变层承载状态与计划 ----
        check("快变层含状态", a.dynamicUserMessage().contains("state-A"));
        check("快变层含计划", a.dynamicUserMessage().contains("plan-A"));
        check("快变层含目标", a.dynamicUserMessage().contains("goal-A"));
        check("快变层含地标", a.dynamicUserMessage().contains("lm-A"));

        // ---- 5. 身份变化只影响慢变层，不影响静态层 ----
        // 这是多 bot 共享缓存的关键：换个名字，前三层的前两层仍然可复用。
        check("身份变化不改变 system", a.systemMessage().equals(b.systemMessage()));
        check("身份变化不改变 staticUser", a.staticUserMessage().equals(b.staticUserMessage()));
        check("身份变化确实改变慢变层", !a.slowUserMessage().equals(b.slowUserMessage()));

        // ---- 6. 自主模式开关只影响快变层 ----
        PromptBuilder.BuiltPrompt autoOn = PromptBuilder.build("s", "m", "l", "p", "lm",
                "g", "f", "n", "pe", "c", true);
        PromptBuilder.BuiltPrompt autoOff = PromptBuilder.build("s", "m", "l", "p", "lm",
                "g", "f", "n", "pe", "c", false);
        check("自主模式开关不改变静态层",
                autoOn.systemMessage().equals(autoOff.systemMessage())
                        && autoOn.staticUserMessage().equals(autoOff.staticUserMessage()));
        check("自主模式开关不改变慢变层",
                autoOn.slowUserMessage().equals(autoOff.slowUserMessage()));
        check("自主模式开启时快变层含自主指令",
                autoOn.dynamicUserMessage().contains("自主模式"));
        check("自主模式关闭时快变层无自主指令",
                !autoOff.dynamicUserMessage().contains("[自主模式]"));

        // ---- 7. 空值不产生 "null" 字样（会成为不稳定内容） ----
        PromptBuilder.BuiltPrompt nulls = PromptBuilder.build(null, null, null, null,
                null, null, null, null, null, null);
        check("空值不出现 null 字面量",
                !nulls.dynamicUserMessage().contains("null")
                        && !nulls.slowUserMessage().contains("null"));

        // ---- 8. 静态前缀自检 ----
        check("StaticPrefix 自检通过", StaticPrefix.selfCheck() == null);
        check("静态前缀达到有效长度（>1500 字符）",
                StaticPrefix.SYSTEM_PROMPT.length() > 1500);

        // ---- 9. 指纹稳定 ----
        check("指纹两次调用一致",
                PromptBuilder.staticFingerprint().equals(PromptBuilder.staticFingerprint()));

        // ---- 10. 同一输入必须产生完全相同的输出（幂等） ----
        PromptBuilder.BuiltPrompt x1 = build("S", "M", "L", "P", "LM", "G", "F", "N", "PE", "C");
        PromptBuilder.BuiltPrompt x2 = build("S", "M", "L", "P", "LM", "G", "F", "N", "PE", "C");
        check("相同输入 -> 四层全部逐字节一致",
                x1.systemMessage().equals(x2.systemMessage())
                        && x1.staticUserMessage().equals(x2.staticUserMessage())
                        && x1.slowUserMessage().equals(x2.slowUserMessage())
                        && x1.dynamicUserMessage().equals(x2.dynamicUserMessage()));

        System.out.println();
        System.out.println("静态前缀长度: " + StaticPrefix.SYSTEM_PROMPT.length() + " 字符");
        System.out.println("静态部分估算 token: " + PromptBuilder.staticTokenEstimate());
        System.out.println("指纹: " + PromptBuilder.staticFingerprint());
        System.out.println();
        System.out.println("通过 " + passed + " / 失败 " + failed);
        if (failed > 0) {
            System.exit(1);
        }
        System.out.println("全部通过 ✔");
    }

    private static PromptBuilder.BuiltPrompt build(String s, String m, String l, String p,
                                                   String lm, String g, String f,
                                                   String n, String pe, String c) {
        return PromptBuilder.build(s, m, l, p, lm, g, f, n, pe, c);
    }

    private static void check(String name, boolean ok) {
        if (ok) {
            passed++;
            System.out.println("  ✔ " + name);
        } else {
            failed++;
            System.out.println("  ✘ " + name);
        }
    }
}
