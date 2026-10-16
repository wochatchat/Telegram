/*
 * This is the source code of Telegram for Android v. 5.x.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.prefetch;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;

/**
 * 预取评分与硬闸（控制页设计文档 §六 数据流 / §5.2、§5.3）。
 * <p>
 * 纯静态函数，不依赖任何 Android 类，可在 JVM 单测中直接运行；
 * 时间一律通过 Candidate/Env 注入，禁止写死日期。
 */
public class PrefetchScorer {

    /** 评分达到该值才允许入队 */
    public static final float SCORE_THRESHOLD = 0.35f;

    public static final int NET_WIFI = 0;
    public static final int NET_CELLULAR = 1;
    public static final int NET_ROAMING = 2;
    public static final int NET_OFFLINE = 3;

    /** 单次扫描每聊天取回的候选上限 */
    public static final int MAX_CANDIDATES_PER_CHAT = 100;

    /** 环境快照：调度器在 UI 线程采集，纯函数只读 */
    public static class Env {
        public int netType = NET_WIFI;
        /** 蜂窝且漫游（roaming 视为蜂窝的子集） */
        public boolean roaming;
        public boolean charging;
        /** 0-100，读取失败按 100 */
        public int batteryPct = 100;
        /** 本月蜂窝已用字节 */
        public long monthCellularUsed;
        public int hourOfDay;
        public long nowMillis;
    }

    public static class Candidate {
        public long dialogId;
        public int messageId;
        /** 消息时间，秒 */
        public long dateSeconds;
        public long fileSize;

        public Candidate() {
        }

        public Candidate(long dialogId, int messageId, long dateSeconds, long fileSize) {
            this.dialogId = dialogId;
            this.messageId = messageId;
            this.dateSeconds = dateSeconds;
            this.fileSize = fileSize;
        }
    }

    public static class Verdict {
        public boolean feasible;
        public float score;
        public String rejectReason;
    }

    // ------------------------------------------------------------------
    // 硬闸（入队前整体闸门，设计文档 §5.2 / §5.3）
    // 返回 null = 通过；否则返回被拒原因（也用于队列页显示"等待 xx"）
    // ------------------------------------------------------------------

    public static String checkHardGates(PrefetchConfig cfg, Env env) {
        if (cfg == null || !cfg.enabled) {
            return "disabled";
        }
        if (env.netType == NET_OFFLINE) {
            return "offline";
        }
        if (env.netType == NET_WIFI) {
            if (!cfg.allowWifi) {
                return "network";
            }
        } else if (env.netType == NET_CELLULAR) {
            if (env.roaming) {
                if (!cfg.allowRoaming) {
                    return "roaming";
                }
            } else if (!cfg.allowCellular) {
                return "network";
            }
        } else {
            return "offline";
        }
        if (!PrefetchConfig.isWithinHours(env.hourOfDay, cfg.allowStartHour, cfg.allowEndHour)) {
            return "hours";
        }
        if (!env.charging && env.batteryPct < cfg.minBatteryPct) {
            return "battery";
        }
        if (cfg.monthlyCellularLimit >= 0 && env.monthCellularUsed >= cfg.monthlyCellularLimit) {
            return "monthly_limit";
        }
        return null;
    }

    // ------------------------------------------------------------------
    // 单候选评估：可行性 + 打分
    // ------------------------------------------------------------------

    public static Verdict evaluate(Candidate c, PrefetchConfig cfg, PrefetchConfig.ChatPrefetchSource src, Env env) {
        if (cfg == null || src == null) {
            return reject("no_config");
        }
        if (src.mode == PrefetchConfig.MODE_MANUAL) {
            return reject("manual_mode");
        }
        long nowSec = env.nowMillis / 1000;
        int depth = cfg.effectiveDepthDays(src);
        if (c.dateSeconds < nowSec - depth * 86400L) {
            return reject("depth");
        }
        boolean onCellular = env.netType == NET_CELLULAR || env.netType == NET_ROAMING;
        // 深夜囤货：WiFi + 充电时解除单文件上限（设计文档 §九）
        boolean hoarding = cfg.nightHoarding && env.charging && env.netType == NET_WIFI;
        if (onCellular && blockedOnCellular(cfg, env)) {
            // 蜂窝小视频例外：蜂窝被禁时仅放行 ≤ cellSmallVideoMaxSize 的小视频
            if (!cfg.cellSmallVideoAllowed || c.fileSize > cfg.cellSmallVideoMaxSize) {
                return reject("network");
            }
        }
        if (src.mode == PrefetchConfig.MODE_SMALL_ONLY && c.fileSize > cfg.cellSmallVideoMaxSize) {
            return reject("small_only");
        }
        if (!hoarding && c.fileSize > cfg.effectiveMaxFileSize(src)) {
            return reject("size");
        }
        float s = score(c, cfg, src, env);
        if (s < SCORE_THRESHOLD) {
            return reject("score");
        }
        Verdict v = new Verdict();
        v.feasible = true;
        v.score = s;
        return v;
    }

    private static boolean blockedOnCellular(PrefetchConfig cfg, Env env) {
        if (env.netType != NET_CELLULAR && env.netType != NET_ROAMING) {
            return false;
        }
        return env.roaming ? !cfg.allowRoaming : !cfg.allowCellular;
    }

    /** 时效衰减 × 优先级权重 */
    public static float score(Candidate c, PrefetchConfig cfg, PrefetchConfig.ChatPrefetchSource src, Env env) {
        int depth = cfg.effectiveDepthDays(src);
        float ageDays = Math.max(0f, (env.nowMillis / 1000f - c.dateSeconds) / 86400f);
        float recency = (float) Math.max(0.05, 1.0 - ageDays / (depth * 2.0));
        float priorityFactor;
        int priority = src != null ? src.priority : PrefetchConfig.PRIORITY_NORMAL;
        if (priority == PrefetchConfig.PRIORITY_HIGH) {
            priorityFactor = 1.5f;
        } else if (priority == PrefetchConfig.PRIORITY_LOW) {
            priorityFactor = 0.7f;
        } else {
            priorityFactor = 1.0f;
        }
        return priorityFactor * recency;
    }

    private static Verdict reject(String reason) {
        Verdict v = new Verdict();
        v.feasible = false;
        v.score = 0f;
        v.rejectReason = reason;
        return v;
    }
}
