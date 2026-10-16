package org.telegram.prefetch;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 预取评分与硬闸单测（设计文档 §5.2 / §5.3 / §六 数据流）。
 * 时间全部通过 Env 注入，不写死日期。
 */
public class PrefetchScorerTest {

    private static final long NOW = 1_760_000_000_000L; // 固定基准

    private PrefetchScorer.Env env(int netType, int hour, int batteryPct, boolean charging) {
        PrefetchScorer.Env env = new PrefetchScorer.Env();
        env.netType = netType;
        env.hourOfDay = hour;
        env.batteryPct = batteryPct;
        env.charging = charging;
        env.nowMillis = NOW;
        return env;
    }

    private static PrefetchConfig.ChatPrefetchSource srcAll() {
        return new PrefetchConfig.ChatPrefetchSource(PrefetchConfig.MODE_ALL, 0, 40,
                PrefetchConfig.PRIORITY_NORMAL, PrefetchConfig.KEEP_FOLLOW_GLOBAL, 0);
    }

    // ------------------------------------------------------------------
    // 硬闸
    // ------------------------------------------------------------------

    @Test
    public void gates_totalSwitchOff() {
        PrefetchConfig cfg = new PrefetchConfig();
        cfg.enabled = false;
        assertEquals("disabled", PrefetchScorer.checkHardGates(cfg, env(PrefetchScorer.NET_WIFI, 10, 100, false)));
    }

    @Test
    public void gates_offline() {
        PrefetchConfig c = new PrefetchConfig();
        c.enabled = true;
        assertEquals("offline", PrefetchScorer.checkHardGates(c, env(PrefetchScorer.NET_OFFLINE, 10, 100, false)));
    }

    @Test
    public void gates_wifiAllowedByDefault_cellularBlocked() {
        PrefetchConfig c = new PrefetchConfig();
        c.enabled = true;
        assertNull(PrefetchScorer.checkHardGates(c, env(PrefetchScorer.NET_WIFI, 10, 100, false)));
        assertEquals("network", PrefetchScorer.checkHardGates(c, env(PrefetchScorer.NET_CELLULAR, 10, 100, false)));
    }

    @Test
    public void gates_roamingRequiresRoamingSwitch() {
        PrefetchConfig c = new PrefetchConfig();
        c.enabled = true;
        c.allowCellular = true;
        c.allowRoaming = false;
        PrefetchScorer.Env e = env(PrefetchScorer.NET_CELLULAR, 10, 100, false);
        e.roaming = true;
        assertEquals("roaming", PrefetchScorer.checkHardGates(c, e));
        c.allowRoaming = true;
        assertNull(PrefetchScorer.checkHardGates(c, e));
    }

    @Test
    public void gates_hoursWindow() {
        PrefetchConfig c = new PrefetchConfig();
        c.enabled = true;
        c.allowStartHour = 22;
        c.allowEndHour = 6;
        assertNull(PrefetchScorer.checkHardGates(c, env(PrefetchScorer.NET_WIFI, 23, 100, false)));
        assertNull(PrefetchScorer.checkHardGates(c, env(PrefetchScorer.NET_WIFI, 5, 100, false)));
        assertEquals("hours", PrefetchScorer.checkHardGates(c, env(PrefetchScorer.NET_WIFI, 12, 100, false)));
    }

    @Test
    public void gates_battery() {
        PrefetchConfig c = new PrefetchConfig();
        c.enabled = true;
        c.minBatteryPct = 20;
        assertEquals("battery", PrefetchScorer.checkHardGates(c, env(PrefetchScorer.NET_WIFI, 10, 15, false)));
        assertNull(PrefetchScorer.checkHardGates(c, env(PrefetchScorer.NET_WIFI, 10, 21, false)));
        assertNull(PrefetchScorer.checkHardGates(c, env(PrefetchScorer.NET_WIFI, 10, 5, true)));
    }

    @Test
    public void gates_monthlyLimit() {
        PrefetchConfig c = new PrefetchConfig();
        c.enabled = true;
        c.allowCellular = true;
        c.monthlyCellularLimit = 1024;
        PrefetchScorer.Env env = env(PrefetchScorer.NET_CELLULAR, 10, 100, false);
        env.monthCellularUsed = 1024;
        assertEquals("monthly_limit", PrefetchScorer.checkHardGates(c, env));
        env.monthCellularUsed = 1023;
        assertNull(PrefetchScorer.checkHardGates(c, env));
        c.monthlyCellularLimit = -1;
        env.monthCellularUsed = Long.MAX_VALUE / 2;
        assertNull(PrefetchScorer.checkHardGates(c, env));
    }

    // ------------------------------------------------------------------
    // 候选评估
    // ------------------------------------------------------------------

    @Test
    public void evaluate_manualModeNeverAuto() {
        PrefetchConfig c = new PrefetchConfig();
        c.enabled = true;
        PrefetchScorer.Verdict v = PrefetchScorer.evaluate(new PrefetchScorer.Candidate(1, 1, NOW / 1000, 1), c,
                new PrefetchConfig.ChatPrefetchSource(PrefetchConfig.MODE_MANUAL, 0, 40, 1,
                        PrefetchConfig.KEEP_FOLLOW_GLOBAL, 0),
                env(PrefetchScorer.NET_WIFI, 10, 100, false));
        assertFalse(v.feasible);
        assertEquals("manual_mode", v.rejectReason);
    }

    @Test
    public void evaluate_depthRejectsOldMessages() {
        PrefetchConfig c = new PrefetchConfig();
        c.enabled = true;
        c.defaultDepthDays = 7;
        long nowSec = NOW / 1000;
        PrefetchScorer.Env env = env(PrefetchScorer.NET_WIFI, 10, 100, false);
        assertFalse(PrefetchScorer.evaluate(new PrefetchScorer.Candidate(1, 1, nowSec - 8 * 86400L, 1), c, srcAll(), env).feasible);
        assertTrue(PrefetchScorer.evaluate(new PrefetchScorer.Candidate(1, 1, nowSec - 3 * 86400L, 1), c, srcAll(), env).feasible);
    }

    @Test
    public void evaluate_sizeCap() {
        PrefetchConfig c = new PrefetchConfig();
        c.enabled = true;
        PrefetchConfig.ChatPrefetchSource src = srcAll();
        PrefetchScorer.Env env = env(PrefetchScorer.NET_WIFI, 10, 100, false);
        long nowSec = NOW / 1000;
        assertFalse(PrefetchScorer.evaluate(new PrefetchScorer.Candidate(1, 1, nowSec, 600 * PrefetchConfig.MB), c, src, env).feasible);
        assertTrue(PrefetchScorer.evaluate(new PrefetchScorer.Candidate(1, 1, nowSec, 100 * PrefetchConfig.MB), c, src, env).feasible);
    }
}

