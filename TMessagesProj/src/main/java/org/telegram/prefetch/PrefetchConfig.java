/*
 * This is the source code of Telegram for Android v. 5.x.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.prefetch;

import java.util.HashMap;
import java.util.Locale;
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
    public static final long GB = 1024L * MB;

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
    // 设置页档位表（设计文档 §五 / §九；纯数据 + 纯函数，可 JVM 单测）
    // ------------------------------------------------------------------

    /** 预取限速档位：字节/秒，0 = 不限 */
    public static final long[] SPEED_LIMIT_PRESETS = {0, 512 * 1024, MB, 2 * MB, 5 * MB, 10 * MB};

    /** 允许时段档位：{startHour, endHour}，支持跨零点 */
    public static final int[][] HOURS_PRESETS = {{0, 24}, {8, 22}, {22, 6}, {0, 6}};

    /** 电量门槛档位：低于该电量暂停预取 */
    public static final int[] BATTERY_PRESETS = {5, 10, 15, 20, 30, 50};

    /** 月度蜂窝限额档位：字节，-1 = 不限 */
    public static final long[] MONTHLY_LIMIT_PRESETS = {-1, GB, 2 * GB, 5 * GB, 10 * GB, 20 * GB};

    /** 蜂窝小视频上限档位：字节 */
    public static final long[] CELL_SMALL_SIZE_PRESETS = {10 * MB, 25 * MB, 50 * MB, 100 * MB, 200 * MB};

    /** 档位索引：精确匹配返回下标，未命中返回 -1（自定义值） */
    public static int indexOfLong(long[] presets, long v) {
        for (int i = 0; i < presets.length; i++) {
            if (presets[i] == v) {
                return i;
            }
        }
        return -1;
    }

    public static int indexOfInt(int[] presets, int v) {
        for (int i = 0; i < presets.length; i++) {
            if (presets[i] == v) {
                return i;
            }
        }
        return -1;
    }

    public static int indexOfHoursPreset(int start, int end) {
        for (int i = 0; i < HOURS_PRESETS.length; i++) {
            if (HOURS_PRESETS[i][0] == start && HOURS_PRESETS[i][1] == end) {
                return i;
            }
        }
        return -1;
    }

    /** 月份/文件大小格式化（纯函数，Locale.US 固定，供设置页与测试共用） */
    public static String formatBytes(long bytes) {
        if (bytes >= GB) {
            long tenths = Math.round(bytes / (double) GB * 10);
            if (tenths % 10 == 0) {
                return String.format(Locale.US, "%d GB", tenths / 10);
            }
            return String.format(Locale.US, "%.1f GB", tenths / 10.0);
        }
        if (bytes >= MB) {
            long tenths = Math.round(bytes / (double) MB * 10);
            if (tenths % 10 == 0) {
                return String.format(Locale.US, "%d MB", tenths / 10);
            }
            return String.format(Locale.US, "%.1f MB", tenths / 10.0);
        }
        return String.format(Locale.US, "%d KB", bytes / 1024);
    }

    /** 限速格式化：0 显示为不限 */
    public static String formatSpeed(long bytesPerSec) {
        if (bytesPerSec <= 0) {
            return "∞";
        }
        return formatBytes(bytesPerSec) + "/s";
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

