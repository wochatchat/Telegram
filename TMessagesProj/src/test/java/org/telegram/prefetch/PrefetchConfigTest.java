package org.telegram.prefetch;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

/**
 * 预下载配置纯函数单测（设计文档 §九 默认参数总表 / §3.4 数据流 / §八 边界）。
 */
public class PrefetchConfigTest {

    // ------------------------------------------------------------------
    // 默认参数总表
    // ------------------------------------------------------------------

    @Test
    public void defaults_matchDesignDocTable() {
        PrefetchConfig c = new PrefetchConfig();
        assertEquals(false, c.enabled);
        assertEquals(2, c.concurrency);
        assertEquals(1, c.reservedUserSlots);
        assertEquals(0, c.speedLimitBytesPerSec);
        assertEquals(true, c.allowWifi);
        assertEquals(false, c.allowCellular);
        assertEquals(false, c.allowRoaming);
        assertEquals(true, c.nightHoarding);
        assertEquals(20, c.minBatteryPct);
        assertEquals(-1, c.monthlyCellularLimit);
        assertEquals(7, c.defaultDepthDays);
        assertEquals(512 * PrefetchConfig.MB, c.defaultMaxFileSize);
        assertEquals(40, c.defaultQuotaPct);
    }

    // ------------------------------------------------------------------
    // clamp
    // ------------------------------------------------------------------

    @Test
    public void clamp_concurrencyBounds() {
        assertEquals(1, PrefetchConfig.clampConcurrency(0));
        assertEquals(1, PrefetchConfig.clampConcurrency(1));
        assertEquals(4, PrefetchConfig.clampConcurrency(4));
        assertEquals(4, PrefetchConfig.clampConcurrency(9));
    }

    @Test
    public void clamp_reservedSlotsBounds() {
        assertEquals(0, PrefetchConfig.clampReservedUserSlots(-1));
        assertEquals(2, PrefetchConfig.clampReservedUserSlots(3));
        assertEquals(1, PrefetchConfig.clampReservedUserSlots(1));
    }

    @Test
    public void clamp_batteryBounds() {
        assertEquals(5, PrefetchConfig.clampBatteryPct(0));
        assertEquals(50, PrefetchConfig.clampBatteryPct(99));
        assertEquals(20, PrefetchConfig.clampBatteryPct(20));
    }

    @Test
    public void clamp_sizeBounds() {
        assertEquals(10 * PrefetchConfig.MB, PrefetchConfig.clampMaxFileSize(0));
        assertEquals(2L * 1024 * PrefetchConfig.MB, PrefetchConfig.clampMaxFileSize(10L * 1024 * PrefetchConfig.MB));
        assertEquals(512 * PrefetchConfig.MB, PrefetchConfig.clampMaxFileSize(512 * PrefetchConfig.MB));
    }

    @Test
    public void clamp_quotaPct() {
        assertEquals(5, PrefetchConfig.clampQuotaPct(0));
        assertEquals(100, PrefetchConfig.clampQuotaPct(120));
        assertEquals(40, PrefetchConfig.clampQuotaPct(40));
    }

    @Test
    public void clamp_depthDays() {
        assertEquals(1, PrefetchConfig.clampDepthDays(0));
        assertEquals(30, PrefetchConfig.clampDepthDays(31));
        assertEquals(7, PrefetchConfig.clampDepthDays(7));
    }

    // ------------------------------------------------------------------
    // 允许时段（含跨零点）
    // ------------------------------------------------------------------

    @Test
    public void hours_fullDay() {
        assertEquals(true, PrefetchConfig.isWithinHours(0, 0, 24));
        assertEquals(true, PrefetchConfig.isWithinHours(13, 0, 24));
        assertEquals(true, PrefetchConfig.isWithinHours(23, 0, 24));
    }

    @Test
    public void hours_dayRange() {
        // 02:00-24:00（设计文档默认示例 2 点起）
        assertEquals(false, PrefetchConfig.isWithinHours(1, 2, 24));
        assertEquals(true, PrefetchConfig.isWithinHours(2, 2, 24));
        assertEquals(true, PrefetchConfig.isWithinHours(23, 2, 24));
    }

    @Test
    public void hours_overnightWindow() {
        // 22:00-06:00 跨零点
        assertEquals(true, PrefetchConfig.isWithinHours(23, 22, 6));
        assertEquals(true, PrefetchConfig.isWithinHours(3, 22, 6));
        assertEquals(false, PrefetchConfig.isWithinHours(12, 22, 6));
    }

    @Test
    public void hours_normalizesNegativeAndLarge() {
        // -2 ≡ 22 点，在 22-6 窗口内
        assertEquals(true, PrefetchConfig.isWithinHours(-2, 22, 6));
        // 26 ≡ 2 点，在 22-6 窗口内
        assertEquals(true, PrefetchConfig.isWithinHours(26, 22, 6));
    }

    // ------------------------------------------------------------------
    // 生效值合并（每聊天覆盖全局）
    // ------------------------------------------------------------------

    @Test
    public void effective_fallbackToGlobal() {
        PrefetchConfig c = new PrefetchConfig();
        assertEquals(512 * PrefetchConfig.MB, c.effectiveMaxFileSize(null));
        assertEquals(40, c.effectiveQuotaPct(null));
        assertEquals(7, c.effectiveDepthDays(null));

        PrefetchConfig.ChatPrefetchSource src = new PrefetchConfig.ChatPrefetchSource();
        src.maxSize = 100 * PrefetchConfig.MB;
        src.depthDays = 3;
        assertEquals(100 * PrefetchConfig.MB, c.effectiveMaxFileSize(src));
        assertEquals(3, c.effectiveDepthDays(src));
    }

    @Test
    public void keepForever_hardProtect() {
        PrefetchConfig.ChatPrefetchSource src = new PrefetchConfig.ChatPrefetchSource();
        src.keepDays = PrefetchConfig.KEEP_FOREVER;
        PrefetchConfig c = new PrefetchConfig();
        assertEquals(true, c.isKeepForever(src));
        assertEquals(30, c.effectiveKeepDays(src));
        src.keepDays = PrefetchConfig.KEEP_FOLLOW_GLOBAL;
        assertEquals(30, c.effectiveKeepDays(src));
    }

    // ------------------------------------------------------------------
    // 白名单 JSON 序列化（设计文档 §3.4）
    // ------------------------------------------------------------------

    @Test
    public void json_roundtrip() {
        HashMap<Long, PrefetchConfig.ChatPrefetchSource> map = new HashMap<>();
        map.put(-1001234567890L, s1());
        map.put(200L, s2());
        String json = PrefetchSourcesJson.serialize(map);

        HashMap<Long, PrefetchConfig.ChatPrefetchSource> parsed = new HashMap<>(PrefetchSourcesJson.parse(json));
        assertEquals(2, parsed.size());
        PrefetchConfig.ChatPrefetchSource p1 = parsed.get(-1001234567890L);
        assertEquals(PrefetchConfig.MODE_SMALL_ONLY, p1.mode);
        assertEquals(100 * PrefetchConfig.MB, p1.maxSize);
        assertEquals(40, p1.quotaPct);
        assertEquals(PrefetchConfig.PRIORITY_HIGH, p1.priority);
        assertEquals(-1, p1.keepDays);
        assertEquals(7, p1.depthDays);
        PrefetchConfig.ChatPrefetchSource p2 = parsed.get(200L);
        assertEquals(PrefetchConfig.MODE_ALL, p2.mode);
        assertEquals(0, p2.maxSize);
        assertEquals(80, p2.quotaPct);
        assertEquals(PrefetchConfig.PRIORITY_LOW, p2.priority);
        assertEquals(7, p2.keepDays);
        assertEquals(3, p2.depthDays);
    }

    private PrefetchConfig.ChatPrefetchSource s1() {
        return new PrefetchConfig.ChatPrefetchSource(PrefetchConfig.MODE_SMALL_ONLY, 100 * PrefetchConfig.MB, 40, PrefetchConfig.PRIORITY_HIGH, -1, 7);
    }

    private PrefetchConfig.ChatPrefetchSource s2() {
        return new PrefetchConfig.ChatPrefetchSource(PrefetchConfig.MODE_ALL, 0, 80, PrefetchConfig.PRIORITY_LOW, 7, 3);
    }

    @Test
    public void json_roundtrip_stable() {
        HashMap<Long, PrefetchConfig.ChatPrefetchSource> sources = new HashMap<>();
        sources.put(-1001234567890L, s1());
        sources.put(200L, s2());
        String json = PrefetchSourcesJson.serialize(sources);
        Map<Long, PrefetchConfig.ChatPrefetchSource> parsed = PrefetchSourcesJson.parse(json);
        assertEquals(2, parsed.size());
        // 二次序列化应与首次一致（排序稳定）
        assertEquals(json, PrefetchSourcesJson.serialize(parsed));
    }

    @Test
    public void json_garbageReturnsEmpty() {
        assertEquals(0, PrefetchSourcesJson.parse(null).size());
        assertEquals(0, PrefetchSourcesJson.parse("").size());
        assertEquals(0, PrefetchSourcesJson.parse("not json").size());
        assertEquals(0, PrefetchSourcesJson.parse("{\"bad\":[]}").size());
    }

    @Test
    public void json_emptySources() {
        assertEquals("{}", PrefetchSourcesJson.serialize(new HashMap<>()));
        assertEquals(0, PrefetchSourcesJson.parse("{}").size());
        // 空字符串/null 不抛异常
        assertEquals(0, PrefetchSourcesJson.parse(null).size());
        assertEquals(0, PrefetchSourcesJson.parse("").size());
    }

    @Test
    public void json_documentExample() {
        // 设计文档 §3.4 原文样例
        String json = "{\"chat_id_1\":{\"mode\":0,\"maxSize\":536870912,\"quotaPct\":40,\"priority\":1,\"keep\":-1,\"depthDays\":7}}";
        HashMap<Long, PrefetchConfig.ChatPrefetchSource> parsed = PrefetchSourcesJson.parse(json.replace("chat_id_1", "123"));
        assertEquals(1, parsed.size());
        PrefetchConfig.ChatPrefetchSource src = parsed.get(123L);
        assertEquals(PrefetchConfig.MODE_ALL, src.mode);
        assertEquals(536870912L, src.maxSize);
        assertEquals(40, src.quotaPct);
        assertEquals(1, src.priority);
        assertEquals(-1, src.keepDays);
        assertEquals(7, src.depthDays);
    }
}
