/*
 * This is the source code of Telegram for Android v. 5.x.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.prefetch;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 预下载源页（Tab1）纯逻辑（设计文档 §三）。
 * <p>
 * 过滤 / 排序 / 批量选择变换全部是纯静态函数，条目数据类不含 Android 依赖，
 * 可在 JVM 单测中直接运行。UI 侧只负责取数（chat 列表）与渲染。
 */
public final class PrefetchSourcesLogic {

    /** 聊天类型：频道（broadcast 频道） */
    public static final int KIND_CHANNEL = 0;
    /** 聊天类型：超级群（megagroup/gigagroup） */
    public static final int KIND_SUPERGROUP = 1;
    /** 聊天类型：普通群（小群） */
    public static final int KIND_GROUP = 2;

    /** 排序：最近更新 */
    public static final int SORT_RECENT = 0;
    /** 排序：已勾选优先 */
    public static final int SORT_SELECTED_FIRST = 1;
    /** 排序：字母 */
    public static final int SORT_ALPHA = 2;

    /** 过滤：全部 / 频道 / 群组 / 已勾选 */
    public static final int FILTER_ALL = 0;
    public static final int FILTER_CHANNELS = 1;
    public static final int FILTER_GROUPS = 2;
    public static final int FILTER_SELECTED = 3;

    /** 批量操作码 */
    public static final int BATCH_SELECT_ALL = 0;
    public static final int BATCH_SELECT_NONE = 1;
    public static final int BATCH_CHANNELS_ONLY = 2;
    public static final int BATCH_INVERT = 3;

    private PrefetchSourcesLogic() {
    }

    /** 列表条目（UI 无关纯数据；activity 异步填充） */
    public static class Entry {
        public long dialogId;
        public String title;
        public int kind;
        public int lastDate;
        public boolean whitelisted;

        public String queryKey; // title 小写，搜索用
    }

    /**
     * 过滤：设计文档 §三 chips（全部/频道/群组/已勾选）。
     * 已勾选过滤优先于类型过滤（互斥的过滤语义，未命中直接排除）。
     */
    public static boolean matchesFilter(Entry e, int filter) {
        if (e == null) {
            return false;
        }
        switch (filter) {
            case FILTER_CHANNELS:
                return e.kind == KIND_CHANNEL;
            case FILTER_GROUPS:
                return e.kind == KIND_GROUP || e.kind == KIND_SUPERGROUP;
            case FILTER_SELECTED:
                return e.whitelisted;
            case FILTER_ALL:
            default:
                return true;
        }
    }

    /** 过滤 + 搜索后按 sortMode 排序（就地修改）。 */
    public static List<Entry> buildVisibleList(List<Entry> all, String query, int filter, int sortMode) {
        List<Entry> out = new ArrayList<>();
        if (all == null) {
            return out;
        }
        String q = query == null ? "" : query.toLowerCase(Locale.US).trim();
        for (Entry e : all) {
            if (!matchesFilter(e, filter)) {
                continue;
            }
            if (q != null && !q.isEmpty() && (e.queryKey == null || !e.queryKey.contains(q))) {
                continue;
            }
            out.add(e);
        }
        sortEntries(out, sortMode);
        return out;
    }

    /** 排序：最近更新 / 已勾选优先（再按时间）/ 字母。稳定排序，输入顺序为加载顺序。 */
    public static void sortEntries(List<Entry> entries, int sortMode) {
        switch (sortMode) {
            case SORT_SELECTED_FIRST:
                Collections.sort(entries, (a, b) -> {
                    if (a.whitelisted != b.whitelisted) {
                        return a.whitelisted ? -1 : 1;
                    }
                    return Integer.compare(b.lastDate, a.lastDate);
                });
                break;
            case SORT_ALPHA:
                Collections.sort(entries, (a, b) -> String.CASE_INSENSITIVE_ORDER.compare(a.title, b.title));
                break;
            case SORT_RECENT:
            default:
                Collections.sort(entries, (a, b) -> Integer.compare(b.lastDate, a.lastDate));
                break;
        }
    }

    /**
     * 批量选择操作（设计文档 §三 批量操作栏）。
     * 返回新的 pending 集合，不修改入参（便于取消/回滚）。
     *
     * @param pending 当前待应用选中集
     * @param visible 当前过滤+搜索后的可见条目
     * @param op      BATCH_SELECT_ALL / SELECT_NONE / CHANNELS_ONLY / INVERT
     */
    public static HashSet<Long> applyBatch(Set<Long> pending, List<Entry> visible, int op) {
        HashSet<Long> out = new HashSet<>(pending);
        if (visible == null) {
            return out;
        }
        switch (op) {
            case BATCH_SELECT_ALL:
                for (Entry e : visible) {
                    out.add(e.dialogId);
                }
                break;
            case BATCH_SELECT_NONE:
                out.clear();
                break;
            case BATCH_CHANNELS_ONLY:
                out.clear();
                for (Entry e : visible) {
                    if (e.kind == KIND_CHANNEL) {
                        out.add(e.dialogId);
                    }
                }
                break;
            case BATCH_INVERT:
                for (Entry e : visible) {
                    if (out.contains(e.dialogId)) {
                        out.remove(e.dialogId);
                    } else {
                        out.add(e.dialogId);
                    }
                }
                break;
            default:
                break;
        }
        return out;
    }

    /**
     * 提交批量选择：返回应从白名单移除的 chatId（需要 removeChatTasks）。
     * 目标白名单 = pending；当前白名单中不在 pending 里的即为移除项。
     */
    public static List<Long> removedChatIds(Map<Long, ?> sources, Set<Long> pending) {
        List<Long> removed = new ArrayList<>();
        if (sources == null) {
            return removed;
        }
        for (Long id : sources.keySet()) {
            if (id != null && !pending.contains(id)) {
                removed.add(id);
            }
        }
        return removed;
    }

    public static boolean isWhitelisted(Map<Long, ?> sources, long dialogId) {
        return sources != null && sources.containsKey(dialogId);
    }
}
