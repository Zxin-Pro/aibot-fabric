import com.example.aibot.memory.ContextCompressor;
import com.example.aibot.memory.ShortTermMemory;

/**
 * ContextCompressor 的独立验证程序（不依赖 Minecraft）。
 *
 * <p>用 javac/java 直接跑，验证三件事：</p>
 * <ol>
 *   <li>长跑时提示词长度是否真的恒定（这是「一直玩下去」的核心承诺）；</li>
 *   <li>历史信息是否被保留成摘要而不是凭空消失；</li>
 *   <li>token 预算截断是否生效。</li>
 * </ol>
 */
public class CompressorTest {

    /** 模拟一个长期运行的 bot：跑 N 步，每步记录一次记忆并压缩。 */
    private static void simulate(int steps, int window, int budget) {
        ShortTermMemory stm = new ShortTermMemory();
        ContextCompressor cc = new ContextCompressor(window, budget);

        int minLen = Integer.MAX_VALUE;
        int maxLen = 0;
        long sumLen = 0;
        int samples = 0;

        for (int i = 1; i <= steps; i++) {
            // 模拟一条动作记忆，长度与真实情况相当
            stm.add(i, "mine", "minecraft:stone", i % 5 != 0,
                    i % 5 != 0 ? "成功挖到石头" : "目标方块不在附近，挖掘失败");

            // 每 3 步压缩一次（真实场景是每轮决策压缩一次）
            if (i % 3 == 0) {
                String out = cc.compress(stm);
                int len = out.length();
                minLen = Math.min(minLen, len);
                maxLen = Math.max(maxLen, len);
                sumLen += len;
                samples++;

                if (i == steps) {
                    System.out.println("  --- 第 " + i + " 步的压缩结果（前 400 字）---");
                    System.out.println("  " + out.substring(0, Math.min(400, out.length()))
                            .replace("\n", "\n  "));
                }
            }
        }

        int avg = (int) (sumLen / Math.max(1, samples));
        System.out.println("  步数=" + steps + " 窗口=" + window + " 预算=" + budget);
        System.out.println("  提示词长度: 最小=" + minLen + " 最大=" + maxLen + " 平均=" + avg);
        System.out.println("  压缩统计: " + cc.shortSummary());

        // 断言 1：长度必须有界。若无限增长，说明压缩没起作用。
        if (maxLen > budget * 4) {
            System.out.println("  [失败] 提示词长度失控（最大 " + maxLen + "，预算 " + budget + "）");
            failures++;
        } else {
            System.out.println("  [通过] 提示词长度有界");
        }

        // 断言 2：模拟 1000 步后，长度不应显著大于模拟 100 步时
        if (steps >= 1000 && maxLen > 20000) {
            System.out.println("  [失败] 长跑后长度过大");
            failures++;
        }
    }

    static int failures = 0;

    public static void main(String[] args) {
        System.out.println("=== 测试 1：短期模拟（200 步）===");
        simulate(200, 12, 1500);

        System.out.println();
        System.out.println("=== 测试 2：长期模拟（3000 步，验证不会撑爆上下文）===");
        simulate(3000, 12, 1500);

        System.out.println();
        System.out.println("=== 测试 3：token 估算 ===");
        int t1 = ContextCompressor.estimateTokens("你好世界");
        int t2 = ContextCompressor.estimateTokens("hello world");
        int t3 = ContextCompressor.estimateTokens("");
        System.out.println("  '你好世界' -> " + t1 + " token (期望 ~4)");
        System.out.println("  'hello world' -> " + t2 + " token (期望 ~3)");
        System.out.println("  '' -> " + t3 + " token (期望 0)");
        if (t1 < 4 || t2 < 2 || t3 != 0) {
            System.out.println("  [失败] token 估算不符合预期");
            failures++;
        } else {
            System.out.println("  [通过]");
        }

        System.out.println();
        System.out.println("=== 测试 4：清空后行为 ===");
        ShortTermMemory stm = new ShortTermMemory();
        ContextCompressor cc = new ContextCompressor(5, 500);
        for (int i = 1; i <= 30; i++) {
            stm.add(i, "walk", "10,64,10", true, "已到达");
        }
        cc.compress(stm);
        long before = cc.getCompressedCount();
        cc.clear();
        stm.clear();
        String after = cc.compress(stm);
        System.out.println("  清空前压缩数=" + before + "，清空后=" + cc.getCompressedCount());
        System.out.println("  清空后输出: " + after.replace("\n", " | "));
        if (cc.getCompressedCount() != 0) {
            System.out.println("  [失败] clear() 未重置计数");
            failures++;
        } else if (!after.contains("暂无")) {
            System.out.println("  [失败] 清空后应显示暂无");
            failures++;
        } else {
            System.out.println("  [通过]");
        }

        System.out.println();
        System.out.println("=== 测试 5：summary 是否保留历史信息 ===");
        ShortTermMemory stm2 = new ShortTermMemory();
        ContextCompressor cc2 = new ContextCompressor(4, 1500);
        // 前 20 步全是挖钻石
        for (int i = 1; i <= 20; i++) {
            stm2.add(i, "mine", "minecraft:diamond_ore", true, "挖到钻石");
        }
        // 后 4 步换成砍树
        for (int i = 21; i <= 24; i++) {
            stm2.add(i, "mine", "minecraft:oak_log", true, "砍到木头");
        }
        String out2 = cc2.compress(stm2);
        System.out.println("  输出前 500 字:");
        System.out.println("  " + out2.substring(0, Math.min(500, out2.length())).replace("\n", "\n  "));
        // 早期的挖钻石行为必须以摘要形式保留下来
        if (!out2.contains("diamond") && !out2.contains("摘要")) {
            System.out.println("  [失败] 早期历史信息完全丢失");
            failures++;
        } else {
            System.out.println("  [通过] 早期历史以摘要形式保留");
        }

        System.out.println();
        if (failures == 0) {
            System.out.println(">>> 全部测试通过");
        } else {
            System.out.println(">>> 有 " + failures + " 项失败");
            System.exit(1);
        }
    }
}
