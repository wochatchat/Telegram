/*
 * This is the source code of Telegram for Android v. 5.x.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.prefetch;

import android.content.SharedPreferences;

import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessagesController;

import java.util.Locale;

/**
 * 预下载配置持久化。
 * <p>
 * 存储在账号级 mainconfig（多账号各自独立，key 天然带账号后缀，设计文档 §八.3）。
 * 白名单序列化格式见 {@link PrefetchSourcesJson}。
 */
public final class PrefetchStore {

    private static final String KEY_ENABLED = "prefetch_enabled";
    private static final String KEY_CONCURRENCY = "prefetch_concurrency";
    private static final String KEY_RESERVED_SLOTS = "prefetch_reserved_slots";
    private static final String KEY_SPEED_LIMIT = "prefetch_speed_limit";
    private static final String KEY_NET_WIFI = "prefetch_net_wifi";
    private static final String KEY_NET_CELLULAR = "prefetch_net_cellular";
    private static final String KEY_NET_ROAMING = "prefetch_net_roaming";
    private static final String KEY_CELL_SMALL_ALLOWED = "prefetch_cell_small_allowed";
    private static final String KEY_CELL_SMALL_SIZE = "prefetch_cell_small_size";
    private static final String KEY_ALLOW_START_HOUR = "prefetch_allow_start_hour";
    private static final String KEY_ALLOW_END_HOUR = "prefetch_allow_end_hour";
    private static final String KEY_NIGHT_HOARDING = "prefetch_night_hoarding";
    private static final String KEY_MIN_BATTERY = "prefetch_min_battery";
    private static final String KEY_MONTHLY_LIMIT = "prefetch_monthly_limit";
    private static final String KEY_MONTHLY_CELL_ONLY = "prefetch_monthly_cell_only";
    private static final String KEY_DEFAULT_DEPTH = "prefetch_default_depth_days";
    private static final String KEY_DEFAULT_MAX_SIZE = "prefetch_default_max_size";
    private static final String KEY_DEFAULT_QUOTA = "prefetch_default_quota_pct";
    private static final String KEY_DEFAULT_KEEP_DAYS = "prefetch_default_keep_days";
    private static final String KEY_MONTH_USED_PREFIX = "prefetch_month_used_";
    /** 持久化"预取任务 documentId 集合"（重启后恢复 prefetch 标记的依据，设计文档 §八） */
    private static final String KEY_PREFETCH_DOC_IDS = "prefetch_doc_ids";
    /** doc-id 集合上限（防无限增长，超出按加入顺序淘汰最旧） */
    public static final int MAX_PREFETCH_DOC_IDS = 500;

    private PrefetchStore() {
    }

    public static SharedPreferences prefs(int account) {
        return MessagesController.getMainSettings(account);
    }

    public static PrefetchConfig load(int account) {
        PrefetchConfig c = new PrefetchConfig();
        SharedPreferences p = prefs(account);
        c.enabled = p.getBoolean(KEY_ENABLED, c.enabled);
        c.concurrency = PrefetchConfig.clampConcurrency(p.getInt(KEY_CONCURRENCY, c.concurrency));
        c.reservedUserSlots = PrefetchConfig.clampReservedUserSlots(p.getInt(KEY_RESERVED_SLOTS, c.reservedUserSlots));
        c.speedLimitBytesPerSec = p.getLong(KEY_SPEED_LIMIT, 0);
        c.allowWifi = p.getBoolean(KEY_NET_WIFI, c.allowWifi);
        c.allowCellular = p.getBoolean(KEY_NET_CELLULAR, c.allowCellular);
        c.allowRoaming = p.getBoolean(KEY_NET_ROAMING, c.allowRoaming);
        c.cellSmallVideoAllowed = p.getBoolean(KEY_CELL_SMALL_ALLOWED, c.cellSmallVideoAllowed);
        c.cellSmallVideoMaxSize = p.getLong(KEY_CELL_SMALL_SIZE, c.cellSmallVideoMaxSize);
        c.allowStartHour = p.getInt(KEY_ALLOW_START_HOUR, 0);
        c.allowEndHour = p.getInt(KEY_ALLOW_END_HOUR, 24);
        c.nightHoarding = p.getBoolean(KEY_NIGHT_HOARDING, c.nightHoarding);
        c.minBatteryPct = PrefetchConfig.clampBatteryPct(p.getInt(KEY_MIN_BATTERY, c.minBatteryPct));
        c.monthlyCellularLimit = p.getLong(KEY_MONTHLY_LIMIT, -1);
        c.monthlyLimitCellularOnly = p.getBoolean(KEY_MONTHLY_CELL_ONLY, c.monthlyLimitCellularOnly);
        c.defaultDepthDays = PrefetchConfig.clampDepthDays(p.getInt(KEY_DEFAULT_DEPTH, c.defaultDepthDays));
        c.defaultMaxFileSize = PrefetchConfig.clampMaxFileSize(p.getLong(KEY_DEFAULT_MAX_SIZE, c.defaultMaxFileSize));
        c.defaultQuotaPct = PrefetchConfig.clampQuotaPct(p.getInt(KEY_DEFAULT_QUOTA, c.defaultQuotaPct));
        c.defaultKeepDays = Math.max(1, p.getInt(KEY_DEFAULT_KEEP_DAYS, c.defaultKeepDays));
        c.sources.putAll(PrefetchSourcesJson.parse(p.getString(PrefetchConfig.PREF_SOURCES, null)));
        return c;
    }

    // ------------------------------------------------------------------
    // 月度蜂窝用量计数（月度限额硬闸的数据来源，设计文档 §5.3）
    // ------------------------------------------------------------------

    public static String monthKey() {
        java.util.Calendar cal = java.util.Calendar.getInstance();
        return String.format(Locale.US, "%04d%02d",
                cal.get(java.util.Calendar.YEAR), cal.get(java.util.Calendar.MONTH) + 1);
    }

    public static long getMonthCellularUsed(int account) {
        try {
            return prefs(account).getLong(KEY_MONTH_USED_PREFIX + monthKey(), 0);
        } catch (Exception e) {
            return 0;
        }
    }

    public static void addMonthCellularUsed(int account, long bytes) {
        if (bytes <= 0) {
            return;
        }
        try {
            String key = KEY_MONTH_USED_PREFIX + monthKey();
            SharedPreferences p = prefs(account);
            p.edit().putLong(key, p.getLong(key, 0) + bytes).apply();
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    // ------------------------------------------------------------------
    // 预取 doc-id 集合持久化（重启恢复 prefetch 标记，§八）
    // ------------------------------------------------------------------

    /** 编码：按加入顺序逗号连接；超上限从头部（最旧）淘汰。纯函数，可 JVM 单测。 */
    public static String encodeDocIds(java.util.Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return "";
        }
        java.util.LinkedHashSet<Long> ordered = new java.util.LinkedHashSet<>(ids);
        while (ordered.size() > MAX_PREFETCH_DOC_IDS) {
            java.util.Iterator<Long> it = ordered.iterator();
            it.next();
            it.remove();
        }
        StringBuilder sb = new StringBuilder();
        for (Long id : ordered) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(id);
        }
        return sb.toString();
    }

    /** 解码：容忍脏数据（非法片段跳过）。纯函数，可 JVM 单测。 */
    public static java.util.LinkedHashSet<Long> decodeDocIds(String s) {
        java.util.LinkedHashSet<Long> out = new java.util.LinkedHashSet<>();
        if (s == null || s.isEmpty()) {
            return out;
        }
        for (String part : s.split(",")) {
            part = part.trim();
            if (part.isEmpty()) {
                continue;
            }
            try {
                out.add(Long.parseLong(part));
            } catch (NumberFormatException ignore) {
            }
        }
        return out;
    }

    public static java.util.LinkedHashSet<Long> getPrefetchDocIds(int account) {
        try {
            return decodeDocIds(prefs(account).getString(KEY_PREFETCH_DOC_IDS, null));
        } catch (Exception e) {
            FileLog.e(e);
            return new java.util.LinkedHashSet<>();
        }
    }

    public static void savePrefetchDocIds(int account, java.util.Collection<Long> ids) {
        try {
            prefs(account).edit().putString(KEY_PREFETCH_DOC_IDS, encodeDocIds(ids)).apply();
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    public static void addPrefetchDocIds(int account, java.util.Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return;
        }
        java.util.LinkedHashSet<Long> all = getPrefetchDocIds(account);
        if (all.addAll(ids)) {
            savePrefetchDocIds(account, all);
        }
    }

    public static void removePrefetchDocIds(int account, java.util.Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return;
        }
        java.util.LinkedHashSet<Long> all = getPrefetchDocIds(account);
        if (all.removeAll(ids)) {
            savePrefetchDocIds(account, all);
        }
    }

    public static void save(int account, PrefetchConfig c) {        prefs(account).edit()
                .putBoolean(KEY_ENABLED, c.enabled)
                .putInt(KEY_CONCURRENCY, PrefetchConfig.clampConcurrency(c.concurrency))
                .putInt(KEY_RESERVED_SLOTS, PrefetchConfig.clampReservedUserSlots(c.reservedUserSlots))
                .putLong(KEY_SPEED_LIMIT, c.speedLimitBytesPerSec)
                .putBoolean(KEY_NET_WIFI, c.allowWifi)
                .putBoolean(KEY_NET_CELLULAR, c.allowCellular)
                .putBoolean(KEY_NET_ROAMING, c.allowRoaming)
                .putBoolean(KEY_CELL_SMALL_ALLOWED, c.cellSmallVideoAllowed)
                .putLong(KEY_CELL_SMALL_SIZE, c.cellSmallVideoMaxSize)
                .putInt(KEY_ALLOW_START_HOUR, c.allowStartHour)
                .putInt(KEY_ALLOW_END_HOUR, c.allowEndHour)
                .putBoolean(KEY_NIGHT_HOARDING, c.nightHoarding)
                .putInt(KEY_MIN_BATTERY, PrefetchConfig.clampBatteryPct(c.minBatteryPct))
                .putLong(KEY_MONTHLY_LIMIT, c.monthlyCellularLimit)
                .putBoolean(KEY_MONTHLY_CELL_ONLY, c.monthlyLimitCellularOnly)
                .putInt(KEY_DEFAULT_DEPTH, PrefetchConfig.clampDepthDays(c.defaultDepthDays))
                .putLong(KEY_DEFAULT_MAX_SIZE, PrefetchConfig.clampMaxFileSize(c.defaultMaxFileSize))
                .putInt(KEY_DEFAULT_QUOTA, PrefetchConfig.clampQuotaPct(c.defaultQuotaPct))
                .putInt(KEY_DEFAULT_KEEP_DAYS, c.defaultKeepDays)
                .putString(PrefetchConfig.PREF_SOURCES, PrefetchSourcesJson.serialize(c.sources))
                .apply();
    }
}
