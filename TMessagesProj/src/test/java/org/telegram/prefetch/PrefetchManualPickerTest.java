package org.telegram.prefetch;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.TimeZone;

/**
 * R6 手动选消息 + 重启恢复 纯逻辑单测。
 */
public class PrefetchManualPickerTest {

    private static Calendar calAt(int year, int month, int day, int hour, int minute, int second) {
        Calendar c = Calendar.getInstance(TimeZone.getTimeZone("GMT+08:00"));
        c.clear();
        c.set(year, month, day, hour, minute, second);
        return c;
    }

    // ------------------------------------------------------------------
    // dayKeyOf：本地日历日边界
    // ------------------------------------------------------------------

    @Test
    public void testDayKeyBasic() {
        Calendar cal = Calendar.getInstance(TimeZone.getTimeZone("GMT+08:00"));
        Calendar c = calAt(2026, Calendar.OCTOBER, 17, 12, 0, 0);
        assertEquals(20261017L, PrefetchPickerLogic.dayKeyOf(c.getTimeInMillis() / 1000, cal));
    }

    @Test
    public void testDayKeyMidnightBoundary() {
        Calendar cal = Calendar.getInstance(TimeZone.getTimeZone("GMT+08:00"));
        Calendar c = calAt(2026, Calendar.OCTOBER, 17, 0, 0, 0);
        long seconds = c.getTimeInMillis() / 1000;
        // 当日 00:00:00
        assertEquals(20261017L, PrefetchPickerLogic.dayKeyOf(seconds, cal));
        // 前一秒跨回前一天
        assertEquals(20261016L, PrefetchPickerLogic.dayKeyOf(seconds - 1, cal));
    }

    // ------------------------------------------------------------------
    // groupSections
    // ------------------------------------------------------------------

    @Test
    public void testGroupEmpty() {
        assertEquals(0, PrefetchPickerLogic.groupSections(new ArrayList<Long>()).size());
    }

    @Test
    public void testGroupSingle() {
        ArrayList<Long> keys = new ArrayList<>();
        keys.add(20261017L);
        ArrayList<PrefetchPickerLogic.Section> sections = PrefetchPickerLogic.groupSections(keys);
        assertEquals(1, sections.size());
        assertEquals(0, sections.get(0).start);
        assertEquals(1, sections.get(0).end);
        assertEquals(20261017L, sections.get(0).dayKey);
    }

    @Test
    public void testGroupConsecutiveAndSplit() {
        ArrayList<Long> keys = new ArrayList<>(Arrays.asList(3L, 3L, 2L, 1L, 1L, 1L));
        ArrayList<PrefetchPickerLogic.Section> sections = PrefetchPickerLogic.groupSections(keys);
        assertEquals(3, sections.size());
        assertEquals(0, sections.get(0).start);
        assertEquals(2, sections.get(0).end);
        assertEquals(3L, sections.get(0).dayKey);
        assertEquals(2, sections.get(1).start);
        assertEquals(3, sections.get(1).end);
        assertEquals(2L, sections.get(1).dayKey);
        assertEquals(3, sections.get(2).start);
        assertEquals(6, sections.get(2).end);
        assertEquals(1L, sections.get(2).dayKey);
    }

    // ------------------------------------------------------------------
    // toggle
    // ------------------------------------------------------------------

    @Test
    public void testToggle() {
        HashSet<Long> set = new HashSet<>();
        assertTrue(PrefetchPickerLogic.toggle(set, 5L));
        assertEquals(1, set.size());
        assertFalse(PrefetchPickerLogic.toggle(set, 5L));
        assertEquals(0, set.size());
    }

    // ------------------------------------------------------------------
    // encode/decode doc ids（含脏数据容错与上限淘汰）
    // ------------------------------------------------------------------

    @Test
    public void testEncodeDecodeRoundtrip() {
        LinkedHashSet<Long> ids = new LinkedHashSet<>(Arrays.asList(10L, 20L, 30L));
        String enc = PrefetchStore.encodeDocIds(ids);
        assertEquals("10,20,30", enc);
        LinkedHashSet<Long> dec = PrefetchStore.decodeDocIds(enc);
        assertEquals(3, dec.size());
        assertTrue(new ArrayList<>(dec).contains(20L));
    }

    @Test
    public void testEncodeEmptyAndDirtyDecode() {
        assertEquals("", PrefetchStore.encodeDocIds(new HashSet<Long>()));
        LinkedHashSet<Long> out = PrefetchStore.decodeDocIds("1,,abc, 7 ");
        assertEquals(2, out.size());
        assertTrue(new ArrayList<>(out).contains(1L));
        assertTrue(new ArrayList<>(out).contains(7L));
    }

    @Test
    public void testEncodeCapDropsOldest() {
        LinkedHashSet<Long> ids = new LinkedHashSet<>();
        int total = PrefetchStore.MAX_PREFETCH_DOC_IDS + 10;
        for (int i = 0; i < total; i++) {
            ids.add((long) i);
        }
        String enc = PrefetchStore.encodeDocIds(ids);
        assertEquals(PrefetchStore.MAX_PREFETCH_DOC_IDS, enc.split(",").length);
        // 头部（最旧）被淘汰：0 和 9 已不在；末尾仍在
        LinkedHashSet<Long> dec = PrefetchStore.decodeDocIds(enc);
        assertFalse(dec.contains(9L));
        assertTrue(dec.contains((long) total - 1));
    }
}
