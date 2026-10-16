package org.telegram.prefetch;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;

/**
 * 特殊策略与清理器单测（设计文档 §3.4 / §六.2）。
 */
public class PrefetchScorerAndJanitorTest {

    private static final long NOW = 1_760_000_000_000L;

    private PrefetchScorer.Env env(int netType, int hour, int batteryPct, boolean charging) {
        PrefetchScorer.Env env = new PrefetchScorer.Env();
        env.netType = netType;
        env.hourOfDay = hour;
        env.batteryPct = 100;
        env.charging = charging;
        env.nowMillis = NOW;
        return env;
    }

    // ------------------------------------------------------------------
    // 夜间囤货 / 小视频例外
    // ------------------------------------------------------------------

    @Test
    public void nightHoarding_bypassesSizeCap_onChargingWifi() {
        PrefetchConfig c = new PrefetchConfig();
        c.enabled = true;
        c.nightHoarding = true;
        long nowSec = NOW / 1000;
        PrefetchScorer.Candidate big = new PrefetchScorer.Candidate(1, 1, nowSec, 900 * PrefetchConfig.MB);
        // WiFi + 充电 → 解除单文件上限
        assertTrue(PrefetchScorer.evaluate(big, c, new PrefetchConfig.ChatPrefetchSource(),
                env(PrefetchScorer.NET_WIFI, 10, 100, true)).feasible);
        // 不充电 → 受上限约束
        assertFalse(PrefetchScorer.evaluate(big, c, new PrefetchConfig.ChatPrefetchSource(),
                env(PrefetchScorer.NET_WIFI, 10, 100, false)).feasible);
        // 蜂窝充电也不触发（夜间囤货仅 WiFi）
        assertFalse(PrefetchScorer.evaluate(big, c, new PrefetchConfig.ChatPrefetchSource(),
                env(PrefetchScorer.NET_CELLULAR, 10, 100, true)).feasible);
    }

    @Test
    public void cellularSmallVideoException() {
        PrefetchConfig c = new PrefetchConfig();
        c.enabled = true;
        c.allowCellular = false;
        c.cellSmallVideoAllowed = true;
        long nowSec = NOW / 1000;
        PrefetchScorer.Env env = env(PrefetchScorer.NET_CELLULAR, 10, 100, false);
        assertTrue(PrefetchScorer.evaluate(new PrefetchScorer.Candidate(1, 1, nowSec, 30 * PrefetchConfig.MB), c,
                new PrefetchConfig.ChatPrefetchSource(), env).feasible);
        assertFalse(PrefetchScorer.evaluate(new PrefetchScorer.Candidate(1, 1, nowSec, 80 * PrefetchConfig.MB), c,
                new PrefetchConfig.ChatPrefetchSource(), env).feasible);
    }

    @Test
    public void smallOnlyModeUsesSmallSizeThreshold() {
        PrefetchConfig c = new PrefetchConfig();
        c.enabled = true;
        PrefetchScorer.Env env = env(PrefetchScorer.NET_WIFI, 10, 100, false);
        PrefetchConfig.ChatPrefetchSource small = new PrefetchConfig.ChatPrefetchSource(
                PrefetchConfig.MODE_SMALL_ONLY, 0, 40, PrefetchConfig.PRIORITY_NORMAL,
                PrefetchConfig.KEEP_FOLLOW_GLOBAL, 0);
        assertFalse(PrefetchScorer.evaluate(new PrefetchScorer.Candidate(1, 1, NOW / 1000, 200 * PrefetchConfig.MB), c, small, env).feasible);
        assertTrue(PrefetchScorer.evaluate(new PrefetchScorer.Candidate(1, 1, NOW / 1000, 20 * PrefetchConfig.MB), c, small, env).feasible);
    }

    @Test
    public void score_recencyDecay_andPriority() {
        PrefetchConfig c = new PrefetchConfig();
        c.enabled = true;
        PrefetchScorer.Env env = env(PrefetchScorer.NET_WIFI, 10, 100, false);
        long nowSec = NOW / 1000;
        PrefetchConfig.ChatPrefetchSource src = new PrefetchConfig.ChatPrefetchSource();
        PrefetchScorer.Candidate fresh = new PrefetchScorer.Candidate(1, 1, nowSec, 1);
        PrefetchScorer.Candidate old = new PrefetchScorer.Candidate(1, 1, nowSec - 6 * 86400L, 1);
        assertTrue(PrefetchScorer.score(fresh, c, src, env) > PrefetchScorer.score(old, c, src, env));

        PrefetchConfig.ChatPrefetchSource high = new PrefetchConfig.ChatPrefetchSource(PrefetchConfig.MODE_ALL, 0, 40,
                PrefetchConfig.PRIORITY_HIGH, PrefetchConfig.KEEP_FOLLOW_GLOBAL, 0);
        assertTrue(PrefetchScorer.score(fresh, c, high, env) > PrefetchScorer.score(fresh, c, src, env));
    }
}
