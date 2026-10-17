/*
 * This is the source code of Telegram for Android v. 5.x.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.prefetch;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 后台预下载 · 队列页纯逻辑（设计文档 §四 Tab2）。
 * <p>
 * 状态推导 / 过滤 / 排序 / 进度全部为纯静态函数，无 Android 依赖，可 JVM 单测。
 * 队列页本体（PrefetchQueueActivity）不另建数据源（§4.4），
 * 全部状态从 DownloadController 现状 + 本会话事件推导。
 */
public final class PrefetchQueueLogic {

    public static final int STATUS_DOWNLOADING = 0;
    public static final int STATUS_QUEUED = 1;
    public static final int STATUS_WAITING = 2;   // 硬闸未通过，等待条件恢复（时段/网络/电量/限额）
    public static final int STATUS_PAUSED = 3;    // 用户手动暂停（半成品分片保留）
    public static final int STATUS_COMPLETED = 4;
    public static final int STATUS_FAILED = 5;

    public static final int FILTER_ALL = 0;
    public static final int FILTER_PREFETCH = 1;
    public static final int FILTER_MANUAL = 2;

    public static final int OP_PAUSE = 0;
    public static final int OP_RESUME = 1;
    public static final int OP_DELETE = 2;

    public static final int RECENT_NONE = 0;
    public static final int RECENT_SUCCESS = 1;
    public static final int RECENT_FAILED = 2;

    /** 队列行数据（纯数据，无 MessageObject 引用，方便单测）。 */
    public static final class Item {
        public long dialogId;
        public String title = "";
        public String fileName;
        public long size;
        public long loaded;
        public boolean prefetch;
        public int status;
        public int percent = -1;
    }

    private PrefetchQueueLogic() {
    }

    /**
     * 状态推导优先级（§4.4 全部由现状推导）：
     * 最近失败事件 &gt; 最近成功事件/文件已存在 &gt; 正在下载 &gt; 用户暂停 &gt; 硬闸等待 &gt; 排队。
     *
     * @param userPaused 本会话内用户手动暂停过（cancelLoadFile 保留分片）
     * @param gateClosed 硬闸未通过（调度器 lastGateReason != null）
     */
    public static int statusFor(boolean fileExists, boolean loading, boolean userPaused,
                                boolean gateClosed, int recentState) {
        if (recentState == RECENT_FAILED) {
            return STATUS_FAILED;
        }
        if (recentState == RECENT_SUCCESS || fileExists) {
            return STATUS_COMPLETED;
        }
        if (loading) {
            return STATUS_DOWNLOADING;
        }
        if (userPaused) {
            return STATUS_PAUSED;
        }
        return gateClosed ? STATUS_WAITING : STATUS_QUEUED;
    }

    /** 进度百分比；total 非法返回 -1（不显示进度条）。 */
    public static int percentFor(long loaded, long total) {
        if (total <= 0) {
            return -1;
        }
        int pct = (int) (loaded * 100 / total);
        return Math.max(0, Math.min(100, pct));
    }

    /** 预取/手动过滤 chips（§四）。 */
    public static List<Item> filter(List<Item> items, int filter) {
        List<Item> out = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            Item it = items.get(i);
            if (filter == FILTER_PREFETCH && !it.prefetch) {
                continue;
            }
            if (filter == FILTER_MANUAL && it.prefetch) {
                continue;
            }
            out.add(it);
        }
        return out;
    }

    /**
     * 展示排序：下载中 &gt; 排队 &gt; 等待 &gt; 已暂停 &gt; 失败 &gt; 已完成。
     * Collections.sort 稳定，同状态保持入队顺序。
     */
    public static void sort(List<Item> items) {
        java.util.Collections.sort(items, COMPARATOR);
    }

    static final Comparator<Item> COMPARATOR = (a, b) -> Integer.compare(rank(a.status), rank(b.status));

    static int rank(int status) {
        switch (status) {
            case STATUS_DOWNLOADING:
                return 0;
            case STATUS_QUEUED:
                return 1;
            case STATUS_WAITING:
                return 2;
            case STATUS_PAUSED:
                return 3;
            case STATUS_FAILED:
                return 4;
            case STATUS_COMPLETED:
            default:
                return 5;
        }
    }
}
