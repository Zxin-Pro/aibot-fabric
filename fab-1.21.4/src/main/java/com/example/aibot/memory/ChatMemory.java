package com.example.aibot.memory;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.ArrayList;

/**
 * 聊天记忆：记录智能体「听到」的其他玩家发言。
 *
 * <p><b>为什么需要它</b>：之前的实现里智能体只能说、不能听 ——
 * 玩家跟它说话它完全没反应。真人玩家会注意到别人在跟自己讲话，
 * 并给出回应。这是「和真人没区别」里最容易被忽略、但感知最明显的一环。</p>
 *
 * <p>记录会进入提示词，让模型知道自己刚被谁、说了什么，
 * 从而决定是否用 chat 动作回话。</p>
 *
 * <p>格式稳定、有上限，避免长跑时无界增长影响缓存与前缀一致性。</p>
 */
public final class ChatMemory {

    /** 最多保留的聊天条数。太多会挤占上下文预算。 */
    public static final int MAX_ENTRIES = 10;

    /**
     * 单条聊天记录。
     *
     * @param speaker 说话者名字
     * @param message 内容
     * @param step    收到时的步号（用于让模型判断新旧）
     */
    public record Entry(String speaker, String message, long step) {
    }

    private final Deque<Entry> entries = new ArrayDeque<>();

    /** 记录一条听到的聊天。 */
    public synchronized void add(String speaker, String message, long step) {
        if (message == null || message.trim().isEmpty()) {
            return;
        }
        String text = message.trim();
        // 限制单条长度，防止有人刷屏把上下文撑爆
        if (text.length() > 120) {
            text = text.substring(0, 120) + "...";
        }
        entries.addLast(new Entry(
                speaker == null || speaker.isEmpty() ? "未知玩家" : speaker,
                text,
                step));
        while (entries.size() > MAX_ENTRIES) {
            entries.removeFirst();
        }
    }

    /**
     * 序列化为固定格式文本（供提示词使用）。
     *
     * <p>固定格式：{@code #<步号> <说话者>: <内容>}</p>
     */
    public synchronized String serialize() {
        if (entries.isEmpty()) {
            return "(暂无)";
        }
        StringBuilder sb = new StringBuilder();
        for (Entry e : entries) {
            sb.append('#').append(e.step()).append(' ')
                    .append(e.speaker()).append(": ").append(e.message()).append('\n');
        }
        return sb.toString().trim();
    }

    /** 是否最近有未回应的聊天（可用于触发主动回话）。 */
    public synchronized boolean hasRecent(long currentStep, long withinSteps) {
        Entry last = entries.peekLast();
        return last != null && currentStep - last.step() <= withinSteps;
    }

    /** 最近一条聊天。 */
    public synchronized Entry last() {
        return entries.peekLast();
    }

    /** 快照（供压缩/统计）。 */
    public synchronized List<Entry> snapshot() {
        return new ArrayList<>(entries);
    }

    /** 清空。 */
    public synchronized void clear() {
        entries.clear();
    }

    /** 条数。 */
    public synchronized int size() {
        return entries.size();
    }
}
