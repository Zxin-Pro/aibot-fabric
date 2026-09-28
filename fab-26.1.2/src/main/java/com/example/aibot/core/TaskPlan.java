package com.example.aibot.core;

import java.util.ArrayList;
import java.util.List;

/**
 * 任务计划：一个结构化的任务栈，让 AIBot 的长期目标变成「可执行的步骤序列」。
 *
 * <p><b>为什么需要它</b>：纯反应式循环每轮都重新问 LLM「下一步干嘛」，
 * 会导致反复横跳（挖两下木头又跑去挖石头），永远做不成一件事。
 * 有了任务栈之后，每轮取栈顶任务，LLM 只负责「怎么完成当前这一步」，
 * 做完才推进到下一步。</p>
 *
 * <p>序列化格式固定（用于提示词，缓存友好）：
 * {@code [>] 3/5 合成工作台 (进行中)}、{@code [ ] 4/5 挖石头 x30}、{@code [x] 2/5 砍树 x20}</p>
 */
public final class TaskPlan {

    /**
     * 单个任务。
     */
    public static final class Task {
        /** 任务描述（自然语言，给 LLM 看）。 */
        public String description = "";
        /** 是否已完成。 */
        public boolean done = false;
        /** 连续失败次数，超过阈值会被判定为「做不到」而跳过。 */
        public int failCount = 0;

        public Task() {
        }

        public Task(String description) {
            this.description = description == null ? "" : description.trim();
        }
    }

    /** 任务列表（用 List 保证顺序稳定，栈顶 = 第一个未完成项）。 */
    private final List<Task> tasks = new ArrayList<>();

    /** 单个任务连续失败多少次后自动跳过。 */
    public static final int TASK_FAIL_LIMIT = 5;

    /** 计划最多保留的任务数，防止无限膨胀。 */
    public static final int MAX_TASKS = 30;

    /**
     * 用新的任务列表替换当前计划（LLM 规划时调用）。
     *
     * @param descriptions 任务描述列表，按执行顺序
     */
    public synchronized void replace(List<String> descriptions) {
        this.tasks.clear();
        if (descriptions == null) {
            return;
        }
        for (String d : descriptions) {
            if (d == null || d.trim().isEmpty()) {
                continue;
            }
            this.tasks.add(new Task(d));
            if (this.tasks.size() >= MAX_TASKS) {
                break;
            }
        }
    }

    /** 在计划末尾追加一个任务。 */
    public synchronized void append(String description) {
        if (description == null || description.trim().isEmpty()) {
            return;
        }
        if (this.tasks.size() < MAX_TASKS) {
            this.tasks.add(new Task(description));
        }
    }

    /**
     * 取当前应该执行的任务（第一个未完成的）。
     *
     * @return 任务，全部完成时返回 null
     */
    public synchronized Task current() {
        for (Task t : tasks) {
            if (!t.done) {
                return t;
            }
        }
        return null;
    }

    /**
     * 标记当前任务完成，推进到下一个。
     */
    public synchronized void completeCurrent() {
        Task t = current();
        if (t != null) {
            t.done = true;
            t.failCount = 0;
        }
    }

    /**
     * 给当前任务记一次失败。
     *
     * <p>连续失败超过 {@link #TASK_FAIL_LIMIT} 次时，认为该任务当前做不到，
     * 自动标记为跳过（done=true），避免在死路上无限空转烧 token。</p>
     *
     * @return true 表示该任务因失败过多被跳过
     */
    public synchronized boolean failCurrent() {
        Task t = current();
        if (t == null) {
            return false;
        }
        t.failCount++;
        if (t.failCount >= TASK_FAIL_LIMIT) {
            t.done = true;
            // 跳过时重新计数，避免影响后续判断
            t.failCount = 0;
            return true;
        }
        return false;
    }

    /** 当前任务连续失败次数。 */
    public synchronized int currentFailCount() {
        Task t = current();
        return t == null ? 0 : t.failCount;
    }

    /** 计划是否为空（没有任何未完成任务）。 */
    public synchronized boolean isEmpty() {
        return current() == null;
    }

    /** 清空计划。 */
    public synchronized void clear() {
        this.tasks.clear();
    }

    /**
     * 序列化为固定格式文本，放入提示词动态后缀。
     *
     * <p>格式固定：{@code [>] i/n 描述}（进行中）、{@code [ ] i/n 描述}（待办）、
     * {@code [x] i/n 描述}（已完成）。</p>
     */
    public synchronized String serialize() {
        if (tasks.isEmpty()) {
            return "(尚未制定计划)";
        }
        Task cur = current();
        StringBuilder sb = new StringBuilder();
        int total = tasks.size();
        for (int i = 0; i < total; i++) {
            Task t = tasks.get(i);
            String mark;
            if (t.done) {
                mark = "[x]";
            } else if (t == cur) {
                mark = "[>]";
            } else {
                mark = "[ ]";
            }
            sb.append(mark).append(' ').append(i + 1).append('/').append(total).append(' ')
                    .append(t.description);
            if (t == cur && t.failCount > 0) {
                sb.append("（已失败 ").append(t.failCount).append(" 次）");
            }
            sb.append('\n');
        }
        return sb.toString().trim();
    }

    /** 已完成任务数。 */
    public synchronized int doneCount() {
        int n = 0;
        for (Task t : tasks) {
            if (t.done) {
                n++;
            }
        }
        return n;
    }

    /** 任务总数。 */
    public synchronized int size() {
        return tasks.size();
    }

    /** 导出为可持久化的描述列表（含完成状态），供存档。 */
    public synchronized List<Task> exportTasks() {
        return new ArrayList<>(tasks);
    }

    /** 从存档恢复任务列表。 */
    public synchronized void importTasks(List<Task> loaded) {
        this.tasks.clear();
        if (loaded != null) {
            this.tasks.addAll(loaded);
        }
    }
}
