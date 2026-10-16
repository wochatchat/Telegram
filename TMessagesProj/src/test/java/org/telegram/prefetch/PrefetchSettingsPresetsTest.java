package org.telegram.prefetch;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 流量控制页（R3）档位映射与格式化纯函数测试（设计文档 §五）。
 */
public class PrefetchSettingsPresetsTest {

    // ------------------------------------------------------------------
    // 限速档位映射
    // ------------------------------------------------------------------

    @Test
    public void speedLimitPresets_matchByValue() {
        assertEquals(0, PrefetchConfig.indexOfLong(PrefetchConfig.SPEED_LIMIT_PRESETS, 0));
        assertEquals(1, PrefetchConfig.indexOfLong(PrefetchConfig.SPEED_LIMIT_PRESETS, 512 * 1024));
        assertEquals(2, PrefetchConfig.indexOfLong(PrefetchConfig.SPEED_LIMIT_PRESETS, PrefetchConfig.MB));
        assertEquals(5, PrefetchConfig.indexOfLong(PrefetchConfig.SPEED_LIMIT_PRESETS, 10 * PrefetchConfig.MB));
    }

    @Test
    public void speedLimitPresets_customValueReturnsMinusOne() {
        assertEquals(-1, PrefetchConfig.indexOfLong(PrefetchConfig.SPEED_LIMIT_PRESETS, 3 * PrefetchConfig.MB));
    }

    // ------------------------------------------------------------------
    // 时段档位映射 + 时段解析
    // ------------------------------------------------------------------

    @Test
    public void hoursPresets_indexMatchesConfig() {
        assertEquals(0, PrefetchConfig.indexOfHoursPreset(0, 24));
        assertEquals(1, PrefetchConfig.indexOfHoursPreset(8, 22));
        assertEquals(2, PrefetchConfig.indexOfHoursPreset(22, 6));
        assertEquals(3, PrefetchConfig.indexOfHoursPreset(0, 6));
        assertEquals(-1, PrefetchConfig.indexOfHoursPreset(9, 17));
    }

    @Test
    public void hoursPresets_coverCrossMidnight() {
        // 22-6 跨零点档位：23 点与 5 点在窗口内，7 点/21 点不在
        assertTrue(PrefetchConfig.isWithinHours(23, 22, 6));
        assertTrue(PrefetchConfig.isWithinHours(5, 22, 6));
        assertFalse(PrefetchConfig.isWithinHours(21, 22, 6));
        assertFalse(PrefetchConfig.isWithinHours(6, 22, 6));
        // 全天档位
        for (int h = 0; h < 24; h++) {
            assertTrue(PrefetchConfig.isWithinHours(h, 0, 24));
        }
    }

    // ------------------------------------------------------------------
    // 电量门槛档位 + clamp 边界
    // ------------------------------------------------------------------

    @Test
    public void batteryPresets_containsDefaultAndBounds() {
        assertEquals(3, PrefetchConfig.indexOfInt(PrefetchConfig.BATTERY_PRESETS, 20)); // 默认 20%
        assertEquals(0, PrefetchConfig.indexOfInt(PrefetchConfig.BATTERY_PRESETS, 5));
        assertEquals(PrefetchConfig.BATTERY_PRESETS.length - 1, PrefetchConfig.indexOfInt(PrefetchConfig.BATTERY_PRESETS, 50));
    }

    @Test
    public void batteryClamp_enforcesRange() {
        assertEquals(5, PrefetchConfig.clampBatteryPct(1));
        assertEquals(50, PrefetchConfig.clampBatteryPct(80));
        assertEquals(20, PrefetchConfig.clampBatteryPct(20));
    }

    // ------------------------------------------------------------------
    // 月度限额 / 小视频上限档位
    // ------------------------------------------------------------------

    @Test
    public void monthlyPresets_unlimitedFirst() {
        assertEquals(-1L, PrefetchConfig.MONTHLY_LIMIT_PRESETS[0]);
        assertEquals(0, PrefetchConfig.indexOfLong(PrefetchConfig.MONTHLY_LIMIT_PRESETS, -1));
        assertEquals(2, PrefetchConfig.indexOfLong(PrefetchConfig.MONTHLY_LIMIT_PRESETS, 2L * PrefetchConfig.GB));
    }

    @Test
    public void cellSmallPresets_containsDefault50MB() {
        assertEquals(2, PrefetchConfig.indexOfLong(PrefetchConfig.CELL_SMALL_SIZE_PRESETS, 50 * PrefetchConfig.MB));
    }

    // ------------------------------------------------------------------
    // 格式化
    // ------------------------------------------------------------------

    @Test
    public void formatBytes_units() {
        assertEquals("512 KB", PrefetchConfig.formatBytes(512 * 1024));
        assertEquals("512 MB", PrefetchConfig.formatBytes(512L * PrefetchConfig.MB));
        assertEquals("1 GB", PrefetchConfig.formatBytes(PrefetchConfig.GB));
        assertEquals("1.5 GB", PrefetchConfig.formatBytes(PrefetchConfig.GB + 512L * PrefetchConfig.MB));
    }

    @Test
    public void formatSpeed_unlimitedShowsInfinity() {
        assertEquals("∞", PrefetchConfig.formatSpeed(0));
        assertEquals("1 MB/s", PrefetchConfig.formatSpeed(PrefetchConfig.MB));
    }

    // ------------------------------------------------------------------
    // 时段生效值与档位一致性
    // ------------------------------------------------------------------

    @Test
    public void hoursPresetApplied_configJudgesConsistently() {
        PrefetchConfig c = new PrefetchConfig();
        c.allowStartHour = PrefetchConfig.HOURS_PRESETS[2][0];
        c.allowEndHour = PrefetchConfig.HOURS_PRESETS[2][1];
        assertTrue(c.isWithinAllowedHours(22));
        assertTrue(c.isWithinAllowedHours(3));
        assertFalse(c.isWithinAllowedHours(12));
    }
}
