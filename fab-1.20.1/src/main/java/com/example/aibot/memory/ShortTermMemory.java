package com.example.aibot.memory;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * 短期记忆：最近 N 条「状态 → 动作 → 结果」记录。
 *
 * <p><b>缓存友好性要求</b>：序列化格式必须稳定，
 * 因此这里不使用 HashMap，而是用固定字段顺序手动拼接字符串。</p>
 *
 * <p>线程安全：所有方法都加锁，因为 LLM 回调线程可能同时写入。</p>
 */
public final class ShortTermMemory {

    /** 最多保留的记录条数（需求：最近 20 条）。 */
    public static final int MAX_ENTRIES = 20;

    /**
     * 单条记忆记录。
     *
     * @param step    步数序号
     * @param action  执行的动作名
     * @param params  动作参数（已格式化为稳定字符串）
     * @param success 是否成功
     * @param result  结果简述
     */
    public record Entry(long step,
                        String action,
                        String params,
                        boolean success,
                        String result) {
    }

    /** 环形缓冲：队首是最旧的记录。 */
    private final Deque<Entry> entries = new ArrayDeque<>();

    /** 记录一条新记忆，超出上限时自动丢弃最旧的。 */
    public synchronized void add(long step, String action, String params, boolean success, String result) {
        entries.addLast(new Entry(step,
                action == null ? "unknown" : action,
                params == null ? "" : params,
                success,
                result == null ? "" : result));
        while (entries.size() > MAX_ENTRIES) {
            entries.removeFirst();
        }
    }

    /**
     * 序列化为固定格式的文本，用于放入提示词动态后缀。
     *
     * <p>每行格式固定为：{@code #<step> [成功|失败] <action>(<params>) -> <result>}
     * 不使用 JSON 是为了节省 token，同时顺序完全确定。</p>
     */
    public synchronized String serialize() {
        if (entries.isEmpty()) {
            return "(暂无)";
        }
        StringBuilder sb = new StringBuilder();
        for (Entry e : entries) {
            sb.append('#').append(e.step())
                    .append(e.success() ? " [成功] " : " [失败] ")
                    .append(e.action());
            if (!e.params().isEmpty()) {
                sb.append('(').append(e.params()).append(')');
            }
            sb.append(" -> ").append(e.result())
                    .append('\n');
        }
        return sb.toString().trim();
    }

    /** 取最近一条记录（用于判断是否需要换策略）。 */
    public synchronized Entry last() {
        return entries.peekLast();
    }

    /**
     * 统计最近连续失败同一动作的次数。
     *
     * <p>自主循环用它来判断「卡住」，从而切换策略。</p>
     *
     * @param action 动作名
     * @return 末尾连续失败次数
     */
    public synchronized int consecutiveFailures(String action) {
        int count = 0;
        List<Entry> list = new ArrayList<>(entries);
        for (int i = list.size() - 1; i >= 0; i--) {
            Entry e = list.get(i);
            if (!e.action().equals(action)) {
                break;
            }
            if (e.success()) {
                break;
            }
            count++;
        }
        return count;
    }

    /** 清空短期记忆（/aibot memory clear）。 */
    public synchronized void clear() {
        entries.clear();
    }

    /** 当前记录条数。 */
    public synchronized int size() {
        return entries.size();
    }
}
