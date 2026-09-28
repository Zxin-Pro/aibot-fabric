package com.example.aibot.action;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.List;

/**
 * 解析 LLM 返回的 JSON 动作。
 *
 * <p>LLM 输出经常不完美，因此这里做多轮容错：</p>
 * <ol>
 *   <li>直接解析纯 JSON</li>
 *   <li>剥离 ```json ... ``` Markdown 代码块</li>
 *   <li>从文本中截取第一个 { 到最后一个 } 之间的内容</li>
 * </ol>
 *
 * <p>解析失败时返回 {@code null}，由调用方决定重试或降级为 idle。</p>
 */
public final class ActionParser {

    private ActionParser() {
    }

    /**
     * 解析结果。
     *
     * @param action 动作名（小写）
     * @param json   完整 JSON 对象
     * @param reason 模型给出的理由，可为空
     */
    public record ParsedAction(String action, JsonObject json, String reason) {

        /** 是否有指定字段。 */
        public boolean has(String key) {
            return json.has(key) && !json.get(key).isJsonNull();
        }

        /** 取字符串参数。 */
        public String getString(String key, String def) {
            try {
                if (has(key)) {
                    return json.get(key).getAsString();
                }
            } catch (Exception ignored) {
            }
            return def;
        }

        /** 取整数参数。 */
        public int getInt(String key, int def) {
            try {
                if (has(key)) {
                    return json.get(key).getAsInt();
                }
            } catch (Exception ignored) {
            }
            return def;
        }

        /** 取浮点参数。 */
        public double getDouble(String key, double def) {
            try {
                if (has(key)) {
                    return json.get(key).getAsDouble();
                }
            } catch (Exception ignored) {
            }
            return def;
        }

        /** 取布尔参数。 */
        public boolean getBoolean(String key, boolean def) {
            try {
                if (has(key)) {
                    return json.get(key).getAsBoolean();
                }
            } catch (Exception ignored) {
            }
            return def;
        }

        /**
         * 取字符串数组参数（用于 plan 动作的 steps）。
         * 同时容忍模型返回单个字符串或 JSON null。
         */
        public java.util.List<String> getStringList(String key) {
            java.util.List<String> out = new ArrayList<>();
            try {
                if (!has(key)) {
                    return out;
                }
                JsonElement el = json.get(key);
                if (el.isJsonArray()) {
                    for (JsonElement item : el.getAsJsonArray()) {
                        if (!item.isJsonNull()) {
                            String s = item.isJsonPrimitive() ? item.getAsString() : item.toString();
                            if (s != null && !s.trim().isEmpty()) {
                                out.add(s.trim());
                            }
                        }
                    }
                } else if (el.isJsonPrimitive()) {
                    String s = el.getAsString();
                    if (s != null && !s.trim().isEmpty()) {
                        out.add(s.trim());
                    }
                }
            } catch (Exception ignored) {
            }
            return out;
        }

        /**
         * 把参数格式化为短字符串，用于记忆与日志。
         * 字段顺序固定（按 key 排序），保证序列化稳定。
         */
        public String paramsSummary() {
            List<String> keys = new ArrayList<>();
            for (String k : json.keySet()) {
                if (!k.equals("action") && !k.equals("reason")) {
                    keys.add(k);
                }
            }
            java.util.Collections.sort(keys);
            StringBuilder sb = new StringBuilder();
            for (String k : keys) {
                if (sb.length() > 0) {
                    sb.append(',');
                }
                sb.append(k).append('=').append(shortValue(json.get(k)));
            }
            return sb.toString();
        }

        private static String shortValue(JsonElement e) {
            String s = e.toString();
            // 去掉字符串两端的引号
            if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) {
                s = s.substring(1, s.length() - 1);
            }
            return s.length() > 40 ? s.substring(0, 40) + "..." : s;
        }
    }

    /**
     * 解析模型输出文本。
     *
     * @param raw 模型原始输出
     * @return 解析结果，失败返回 null
     */
    public static ParsedAction parse(String raw) {
        if (raw == null) {
            return null;
        }
        String text = raw.trim();
        if (text.isEmpty()) {
            return null;
        }

        // 尝试 1：直接解析
        ParsedAction direct = tryParse(text);
        if (direct != null) {
            return direct;
        }

        // 尝试 2：剥离 Markdown 代码块围栏
        String stripped = stripCodeFence(text);
        if (!stripped.equals(text)) {
            ParsedAction p = tryParse(stripped);
            if (p != null) {
                return p;
            }
        }

        // 尝试 3：截取第一个 { 到最后一个 }
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start >= 0 && end > start) {
            ParsedAction p = tryParse(text.substring(start, end + 1));
            if (p != null) {
                return p;
            }
        }

        return null;
    }

    /**
     * 直接构造一个动作（供内部反射逻辑使用，不经过 LLM）。
     *
     * @param action 动作名
     * @param key    参数名
     * @param value  参数值（String / Number / Boolean）
     * @return 可直接交给 ActionExecutor 的动作对象
     */
    public static ParsedAction fromParams(String action, String key, Object value) {
        JsonObject obj = new JsonObject();
        obj.addProperty("action", action);
        if (key != null && !key.isEmpty() && value != null) {
            if (value instanceof Number n) {
                obj.addProperty(key, n);
            } else if (value instanceof Boolean b) {
                obj.addProperty(key, b);
            } else {
                obj.addProperty(key, String.valueOf(value));
            }
        }
        return new ParsedAction(action, obj, "内部反射触发");
    }

    /** 尝试把一段文本解析为动作对象。 */
    private static ParsedAction tryParse(String candidate) {
        try {
            JsonElement el = JsonParser.parseString(candidate);
            if (!el.isJsonObject()) {
                return null;
            }
            JsonObject obj = el.getAsJsonObject();
            if (!obj.has("action") || obj.get("action").isJsonNull()) {
                return null;
            }
            String action = obj.get("action").getAsString().trim().toLowerCase();
            if (action.isEmpty()) {
                return null;
            }
            String reason = "";
            if (obj.has("reason") && !obj.get("reason").isJsonNull()) {
                try {
                    reason = obj.get("reason").getAsString();
                } catch (Exception ignored) {
                    reason = obj.get("reason").toString();
                }
            }
            return new ParsedAction(action, obj, reason);
        } catch (Exception e) {
            return null;
        }
    }

    /** 去掉 ```json ... ``` 或 ``` ... ``` 围栏。 */
    private static String stripCodeFence(String text) {        String t = text;
        if (t.startsWith("```")) {
            int firstNewline = t.indexOf('\n');
            if (firstNewline > 0) {
                t = t.substring(firstNewline + 1);
            }
            int fenceEnd = t.lastIndexOf("```");
            if (fenceEnd >= 0) {
                t = t.substring(0, fenceEnd);
            }
        }
        return t.trim();
    }
}
