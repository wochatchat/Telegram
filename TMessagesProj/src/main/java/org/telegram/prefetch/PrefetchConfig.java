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
 * 后台视频预下载配置（数据 + 纯函数）。
 * <p>
 * 本类刻意不依赖任何 Android 类：序列化/解析/钳制/时段判断全部是纯静态函数，
 * 可在 JVM 单测中直接运行。持久化见 {@link PrefetchStore}。
 * 默认取值与控制页设计文档 §九 默认参数总表保持一致。
 */
public class PrefetchConfig {

    /** 白名单存储 key（设计文档 §3.4 prefetch_source） */
    public static final String PREF_SOURCES = "prefetch_source";

    /** 预下载模式：全部视频 */
    public static final int MODE_ALL = 0;
    /** 预下载模式：仅小视频（≤ maxSize） */
    public static final int MODE_SMALL_ONLY = 1;
    /** 预下载模式：手动选消息，一次性任务，不参与自动预取 */
    public static final int MODE_MANUAL = 2;

    public static final int PRIORITY_LOW = 0;
    public static final int PRIORITY_NORMAL = 1;
    public static final int PRIORITY_HIGH = 2;

    /** 保留策略：跟随全局 */
    public static final int KEEP_FOLLOW_GLOBAL = -1;
    /** 保留策略：永久保留（白名单，进清理器硬保护名单） */
    public static final int KEEP_FOREVER = 0;

    public static final long MB = 1024L * 1024;

    /** 总开关，默认关 */
    public boolean enabled = false;
    /** 预取并发数，范围 1-4 */
    public int concurrency = 2;
    /** 用户手动操作保留并发槽，范围 0-2 */
    public int reservedUserSlots = 1;
    /** 预取单独限速，字节/秒，0 = 不限 */
    public long speedLimitBytesPerSec = 0;
    /** 允许预取的网络 */
    public boolean allowWifi = true;
    public boolean allowCellular = false;
    public boolean allowRoaming = false;
    /** 蜂窝下允许的小视频例外 */
    public boolean cellSmallVideoAllowed = false;
    public long cellSmallVideoMaxSize = 50 * MB;
    /** 允许时段（小时，含起点不含终点，支持跨零点如 22-6） */
    public int allowStartHour = 0;
    public int allowEndHour = 24;
    /** 深夜囤货模式：WiFi + 充电时解除单文件上限 */
    public boolean nightHoarding = true;
    /** 低于此电量暂停预取，范围 5-50 */
    public int minBatteryPct = 20;
    /** 月度蜂窝限额，字节，-1 = 不限 */
    public long monthlyCellularLimit = -1;
    /** 月度限额只统计蜂窝流量（WiFi 不计） */
    public boolean monthlyLimitCellularOnly = true;
    /** 默认深度：只囤最近 N 天消息 */
    public int defaultDepthDays = 7;
    /** 默认单文件上限 */
    public long defaultMaxFileSize = 512 * MB;
    /** 默认单聊天配额（占全局预算百分比） */
    public int defaultQuotaPct = 40;
    /** 全局默认保留天数（每聊天 keep=KEEP_FOLLOW_GLOBAL 时生效） */
    public int defaultKeepDays = 30;

    /** 预下载源白名单：chatId → 每聊天配置（设计文档 §3.4） */
    public final Map<Long, ChatPrefetchSource> sources = new HashMap<>();

    public static class ChatPrefetchSource {
        public int mode = MODE_ALL;
        /** 单文件上限，0 = 跟随全局 */
        public long maxSize = 0;
        /** 单聊天缓存配额，占全局预算百分比 5-100 */
        public int quotaPct = 40;
        public int priority = PRIORITY_NORMAL;
        /** 保留策略：KEEP_FOLLOW_GLOBAL / KEEP_FOREVER / N 天 */
        public int keepDays = KEEP_FOLLOW_GLOBAL;
        /** 深度：只囤最近 N 天消息，0 = 跟随全局 */
        public int depthDays = 0;

        public ChatPrefetchSource() {
        }

        public ChatPrefetchSource(int mode, long maxSize, int quotaPct, int priority, int keepDays, int depthDays) {
            this.mode = mode;
            this.maxSize = maxSize;
            this.quotaPct = quotaPct;
            this.priority = priority;
            this.keepDays = keepDays;
            this.depthDays = depthDays;
        }
    }

    // ------------------------------------------------------------------
    // 参数 clamp（设计文档 §九 范围约束）
    // ------------------------------------------------------------------

    public static int clampConcurrency(int v) {
        return Math.max(1, Math.min(4, v));
    }

    public static int clampReservedUserSlots(int v) {
        return Math.max(0, Math.min(2, v));
    }

    public static int clampBatteryPct(int v) {
        return Math.max(5, Math.min(50, v));
    }

    public static int clampDepthDays(int v) {
        return Math.max(1, Math.min(30, v));
    }

    public static int clampQuotaPct(int v) {
        return Math.max(5, Math.min(100, v));
    }

    public static long clampMaxFileSize(long v) {
        return Math.max(10 * MB, Math.min(2L * 1024 * MB, v));
    }

    // ------------------------------------------------------------------
    // 时段判断（允许时段，支持跨零点，如 22:00-06:00）
    // ------------------------------------------------------------------

    public boolean isWithinAllowedHours(int hour) {
        return isWithinHours(hour, allowStartHour, allowEndHour);
    }

    public static boolean isWithinHours(int hour, int start, int end) {
        int h = ((hour % 24) + 24) % 24;
        int s = Math.max(0, Math.min(24, start));
        int e = Math.max(0, Math.min(24, end));
        if (s == e) {
            return s == 0 && e == 24; // 仅全天（0-24）视为全时段允许
        }
        if (s <= e) {
            return h >= s && h < e;
        }
        // 跨零点：22:00-06:00
        return h >= s || h < e;
    }

    // ------------------------------------------------------------------
    // 生效值：每聊天配置覆盖全局默认
    // ------------------------------------------------------------------

    public long effectiveMaxFileSize(ChatPrefetchSource src) {
        return src != null && src.maxSize > 0 ? clampMaxFileSize(src.maxSize) : clampMaxFileSize(defaultMaxFileSize);
    }

    public int effectiveQuotaPct(ChatPrefetchSource src) {
        return src != null ? clampQuotaPct(src.quotaPct) : clampQuotaPct(defaultQuotaPct);
    }

    public int effectiveDepthDays(ChatPrefetchSource src) {
        if (src != null && src.depthDays > 0) {
            return clampDepthDays(src.depthDays);
        }
        return clampDepthDays(defaultDepthDays);
    }

    /** keepDays 生效值：每聊天覆盖全局；KEEP_FOREVER(0) 表示永久保留 */
    public int effectiveKeepDays(ChatPrefetchSource src) {
        if (src != null && src.keepDays != KEEP_FOLLOW_GLOBAL) {
            return src.keepDays;
        }
        return Math.max(1, defaultKeepDays);
    }

    /** 是否清理器硬保护（永久保留白名单，设计文档 §3.4） */
    public boolean isKeepForever(ChatPrefetchSource src) {
        return src != null && src.keepDays == KEEP_FOREVER;
    }
}

