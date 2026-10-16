/*
 * This is the source code of Telegram for Android v. 5.x.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.prefetch;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 白名单 JSON 序列化（设计文档 §3.4 数据流；账号隔离由 {@link PrefetchStore} 的 prefs key 负责）。
 * 格式：{"<chatId>":{"mode":0,"maxSize":536870912,"quotaPct":40,"priority":1,"keep":-1,"depthDays":7}}
 * <p>
 * 采用手写紧凑解析器：结构固定为两层扁平对象、值全为数字，未知 key 跳过。
 * 不用 org.json 是因为它在 JVM 单测（mockable android.jar）下不可用。
 */
public final class PrefetchSourcesJson {

    private PrefetchSourcesJson() {
    }

    public static String serialize(Map<Long, PrefetchConfig.ChatPrefetchSource> sources) {
        StringBuilder b = new StringBuilder("{");
        if (sources != null && !sources.isEmpty()) {
            List<Long> ids = new ArrayList<>(sources.keySet());
            Collections.sort(ids);
            boolean first = true;
            for (Long id : ids) {
                if (!first) {
                    b.append(',');
                }
                first = false;
                b.append('"').append(id).append("\":").append(serializeSource(sources.get(id)));
            }
        }
        return b.append('}').toString();
    }

    public static Map<Long, PrefetchConfig.ChatPrefetchSource> parse(String json) {
        HashMap<Long, PrefetchConfig.ChatPrefetchSource> out = new HashMap<>();
        if (json == null || json.isEmpty()) {
            return out;
        }
        try {
            P p = new P(json);
            p.skipWs();
            if (!p.take('{')) {
                return out;
            }
            p.skipWs();
            if (p.peek() == '}') {
                return out;
            }
            while (true) {
                p.skipWs();
                String key = p.parseString();
                if (key == null) {
                    return out;
                }
                p.skipWs();
                if (!p.take(':')) {
                    return out;
                }
                p.skipWs();
                if (p.take('{')) {
                    out.put(parseId(key), parseSource(p));
                    p.skipWs();
                } else {
                    p.skipNumber();
                }
                p.skipWs();
                if (p.take(',')) {
                    continue;
                }
                if (p.take('}')) {
                    return out;
                }
                return out;
            }
        } catch (Exception ignore) {
            // 损坏的配置：返回已解析部分（设计文档 §八.1 不因脏数据丢全部配置）
            return out;
        }
    }

    public static Map<Long, PrefetchConfig.ChatPrefetchSource> parseSources(String json) {
        return parse(json);
    }

    private static long parseId(String key) {
        try {
            return Long.parseLong(key.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static PrefetchConfig.ChatPrefetchSource parseSource(P p) {
        PrefetchConfig.ChatPrefetchSource src = new PrefetchConfig.ChatPrefetchSource();
        p.skipWs();
        if (!p.take('{')) {
            return null;
        }
        p.skipWs();
        if (p.take('}')) {
            return src;
        }
        while (true) {
            p.skipWs();
            String key = p.parseString();
            if (key == null) {
                return src;
            }
            p.skipWs();
            if (!p.take(':')) {
                return src;
            }
            p.skipWs();
            long value = p.readNumber();
            switch (key) {
                case "mode":
                    src.mode = (int) value;
                    break;
                case "maxSize":
                    src.maxSize = value;
                    break;
                case "quotaPct":
                    src.quotaPct = (int) value;
                    break;
                case "priority":
                    src.priority = (int) value;
                    break;
                case "keep":
                    src.keepDays = (int) value;
                    break;
                case "depthDays":
                    src.depthDays = (int) value;
                    break;
                default:
                    // 未知字段：忽略，向前兼容
                    break;
            }
            p.skipWs();
            if (p.take(',')) {
                continue;
            }
            p.take('}');
            return src;
        }
    }

    public static String serializeSource(PrefetchConfig.ChatPrefetchSource src) {
        StringBuilder b = new StringBuilder("{");
        b.append("\"mode\":").append(src.mode);
        b.append(",\"maxSize\":").append(src.maxSize);
        b.append(",\"quotaPct\":").append(src.quotaPct);
        b.append(",\"priority\":").append(src.priority);
        b.append(",\"keep\":").append(src.keepDays);
        b.append(",\"depthDays\":").append(src.depthDays);
        return b.append('}').toString();
    }

    /** 极小 JSON tokenizer：仅支持字符串 key 与无符号整数字面量（本格式足够） */
    private static final class P {
        private final String s;
        private int i;

        P(String s) {
            this.s = s;
        }

        int peek() {
            return i < s.length() ? s.charAt(i) : -1;
        }

        boolean take(char c) {
            skipWs();
            if (i < s.length() && s.charAt(i) == c) {
                i++;
                return true;
            }
            return false;
        }

        String parseString() {
            if (!take('"')) {
                return null;
            }
            StringBuilder b = new StringBuilder();
            while (i < s.length()) {
                char c = s.charAt(i++);
                if (c == '\\' && i < s.length()) {
                    b.append(s.charAt(i++));
                } else if (c == '"') {
                    return b.toString();
                } else {
                    b.append(c);
                }
            }
            return null;
        }

        long readNumber() {
            int start = i;
            while (i < s.length()) {
                char c = s.charAt(i);
                if ((c >= '0' && c <= '9') || c == '-' || c == '+') {
                    i++;
                } else {
                    break;
                }
            }
            if (start == i) {
                return 0;
            }
            try {
                return Long.parseLong(s.substring(start, i));
            } catch (NumberFormatException e) {
                return 0;
            }
        }

        void skipNumber() {
            readNumber();
        }
    }
}
