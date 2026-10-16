/*
 * This is the source code of Telegram for Android v. 5.x.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.prefetch;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** R4 源页纯逻辑测试（过滤 / 排序 / 批量 / 每聊天档位表 / 提交移除清单）。 */
public class PrefetchSourcesTest {

    private static PrefetchSourcesLogic.Entry entry(long id, String title, int kind, int lastDate, boolean whitelisted) {
        PrefetchSourcesLogic.Entry e = new PrefetchSourcesLogic.Entry();
        e.dialogId = id;
        e.title = title;
        e.kind = kind;
        e.lastDate = lastDate;
        e.whitelisted = whitelisted;
        e.queryKey = title.toLowerCase(java.util.Locale.US);
        return e;
    }

    private static ArrayList<PrefetchSourcesLogic.Entry> sample() {
        ArrayList<PrefetchSourcesLogic.Entry> list = new ArrayList<>();
        // id<0：频道/群组；日期越大地越新
        list.add(entry(-100, "Alpha", PrefetchSourcesLogic.KIND_CHANNEL, 100, true));
        list.add(entry(-101, "beta group", PrefetchSourcesLogic.KIND_GROUP, 300, false));
        list.add(entry(-102, "Gamma", PrefetchSourcesLogic.KIND_SUPERGROUP, 200, true));
        list.add(entry(-103, "delta", PrefetchSourcesLogic.KIND_CHANNEL, 50, false));
        return list;
    }

    // ---------------- matchesFilter ----------------

    @Test
    public void testMatchesFilter() {
        PrefetchSourcesLogic.Entry ch = entry(-1, "c", PrefetchSourcesLogic.KIND_CHANNEL, 1, false);
        PrefetchSourcesLogic.Entry sg = entry(-2, "s", PrefetchSourcesLogic.KIND_SUPERGROUP, 1, true);
        PrefetchSourcesLogic.Entry gr = entry(-2, "g", PrefetchSourcesLogic.KIND_GROUP, 1, false);

        assertTrue(PrefetchSourcesLogic.matchesFilter(ch, PrefetchSourcesLogic.FILTER_ALL));
        assertTrue(PrefetchSourcesLogic.matchesFilter(ch, PrefetchSourcesLogic.FILTER_CHANNELS));
        assertFalse(PrefetchSourcesLogic.matchesFilter(sg, PrefetchSourcesLogic.FILTER_CHANNELS));

        assertTrue(PrefetchSourcesLogic.matchesFilter(sg, PrefetchSourcesLogic.FILTER_GROUPS));
        assertTrue(PrefetchSourcesLogic.matchesFilter(gr, PrefetchSourcesLogic.FILTER_GROUPS));
        assertFalse(PrefetchSourcesLogic.matchesFilter(ch, PrefetchSourcesLogic.FILTER_GROUPS));

        assertTrue(PrefetchSourcesLogic.matchesFilter(sg, PrefetchSourcesLogic.FILTER_SELECTED));
        assertFalse(PrefetchSourcesLogic.matchesFilter(ch, PrefetchSourcesLogic.FILTER_SELECTED));

        assertFalse(PrefetchSourcesLogic.matchesFilter(null, PrefetchSourcesLogic.FILTER_ALL));
    }

    // ---------------- 搜索 + 过滤 + 排序 ----------------

    @Test
    public void testBuildVisibleListSearchAndFilter() {
        List<PrefetchSourcesLogic.Entry> all = sample();
        // 搜索 gamma（大小写不敏感）
        assertEquals(1, PrefetchSourcesLogic.buildVisibleList(all, "GAMMA", PrefetchSourcesLogic.FILTER_ALL, PrefetchSourcesLogic.SORT_RECENT).size());
        // 频道过滤
        assertEquals(2, PrefetchSourcesLogic.buildVisibleList(all, null, PrefetchSourcesLogic.FILTER_CHANNELS, PrefetchSourcesLogic.SORT_RECENT).size());
        // 已勾选过滤
        List<PrefetchSourcesLogic.Entry> selected = PrefetchSourcesLogic.buildVisibleList(all, null, PrefetchSourcesLogic.FILTER_SELECTED, PrefetchSourcesLogic.SORT_RECENT);
        assertEquals(2, selected.size());
        // 过滤后的排序：最近更新在前
        assertEquals(-101, PrefetchSourcesLogic.buildVisibleList(all, null, PrefetchSourcesLogic.FILTER_ALL, PrefetchSourcesLogic.SORT_RECENT).get(0).dialogId);
    }

    @Test
    public void testSorting() {
        List<PrefetchSourcesLogic.Entry> all = sample();
        // 已勾选优先（-102/-100 勾选，组内按时间降序），未勾选再按时间降序
        List<PrefetchSourcesLogic.Entry> out = new ArrayList<>(all);
        PrefetchSourcesLogic.sortEntries(out, PrefetchSourcesLogic.SORT_SELECTED_FIRST);
        assertEquals(-102, out.get(0).dialogId);
        assertEquals(-100, out.get(1).dialogId);
        assertEquals(-101, out.get(2).dialogId);

        // 字母序（不区分大小写）
        out = new ArrayList<>(all);
        PrefetchSourcesLogic.sortEntries(out, PrefetchSourcesLogic.SORT_ALPHA);
        assertEquals("Alpha", out.get(0).title);
        assertEquals("beta group", out.get(1).title);
        assertEquals("delta", out.get(2).title);
        assertEquals("Gamma", out.get(3).title);

        // 默认最近更新
        out = new ArrayList<>(all);
        PrefetchSourcesLogic.sortEntries(out, PrefetchSourcesLogic.SORT_RECENT);
        assertEquals(300, out.get(0).lastDate);
    }

    // ---------------- 每聊天档位表（R4 新增） ----------------

    @Test
    public void testChatPresets() {
        // 单文件上限首档为跟随全局
        assertEquals(0, PrefetchConfig.CHAT_MAX_SIZE_PRESETS[0]);
        assertEquals(2L * PrefetchConfig.GB, PrefetchConfig.CHAT_MAX_SIZE_PRESETS[PrefetchConfig.CHAT_MAX_SIZE_PRESETS.length - 1]);
        assertTrue(PrefetchConfig.indexOfLong(PrefetchConfig.CHAT_MAX_SIZE_PRESETS, 512 * PrefetchConfig.MB) == 3);

        // 配额 / 保留 / 深度档位表
        assertTrue(PrefetchConfig.indexOfInt(PrefetchConfig.QUOTA_PRESETS, 40) >= 0);
        assertTrue(PrefetchConfig.indexOfInt(PrefetchConfig.KEEP_PRESETS, PrefetchConfig.KEEP_FOLLOW_GLOBAL) == 0);
        assertTrue(PrefetchConfig.indexOfInt(PrefetchConfig.KEEP_PRESETS, PrefetchConfig.KEEP_FOREVER) == 1);
        assertTrue(PrefetchConfig.indexOfInt(PrefetchConfig.DEPTH_PRESETS, 0) == 0);
        // 保留档位含永久与跟随全局，无非法负值
        for (int v : PrefetchConfig.KEEP_PRESETS) {
            assertTrue(v == PrefetchConfig.KEEP_FOLLOW_GLOBAL || v == PrefetchConfig.KEEP_FOREVER || v > 0);
        }
        for (int v : PrefetchConfig.QUOTA_PRESETS) {
            assertEquals(v, PrefetchConfig.clampQuotaPct(v));
        }
    }

    // ---------------- 批量提交：移除清单 ----------------

    @Test
    public void testRemovedChatIds() {
        Map<Long, PrefetchConfig.ChatPrefetchSource> sources = new java.util.HashMap<>();
        sources.put(-100L, new PrefetchConfig.ChatPrefetchSource());
        sources.put(-102L, new PrefetchConfig.ChatPrefetchSource());

        HashSet<Long> pending = new HashSet<>();
        pending.add(-100L);
        pending.add(-101L); // 新增，不属于白名单 → 不进移除清单

        List<Long> removed = PrefetchSourcesLogic.removedChatIds(sources, pending);
        assertEquals(1, removed.size());
        assertEquals(Long.valueOf(-102L), removed.get(0));
    }

    @Test
    public void testKeepForeverSerializationRoundTrip() {
        PrefetchConfig config = new PrefetchConfig();
        config.sources.put(-1L, new PrefetchConfig.ChatPrefetchSource(PrefetchConfig.MODE_SMALL_ONLY, 100 * PrefetchConfig.MB, 20, PrefetchConfig.PRIORITY_HIGH, PrefetchConfig.KEEP_FOREVER, 3));
        String json = PrefetchSourcesJson.serialize(config.sources);
        Map<Long, PrefetchConfig.ChatPrefetchSource> back = PrefetchSourcesJson.parse(json);
        assertEquals(1, back.size());
        PrefetchConfig.ChatPrefetchSource src = back.get(-1L);
        assertEquals(PrefetchConfig.MODE_SMALL_ONLY, src.mode);
        assertEquals(100 * PrefetchConfig.MB, src.maxSize);
        assertEquals(PrefetchConfig.PRIORITY_HIGH, src.priority);
        assertEquals(PrefetchConfig.KEEP_FOREVER, src.keepDays);
        assertTrue(config.isKeepForever(src));
    }

    // ---------------- 批量选择变换 ----------------

    @Test
    public void testApplyBatch() {
        List<PrefetchSourcesLogic.Entry> visible = sample();
        HashSet<Long> pending = new HashSet<>();
        pending.add(-103L);

        HashSet<Long> all = PrefetchSourcesLogic.applyBatch(pending, visible, PrefetchSourcesLogic.BATCH_SELECT_ALL);
        assertEquals(4, all.size());
        // 入参不被修改（可回滚）
        assertEquals(1, pending.size());

        HashSet<Long> none = PrefetchSourcesLogic.applyBatch(all, visible, PrefetchSourcesLogic.BATCH_SELECT_NONE);
        assertEquals(0, none.size());

        // 仅频道：只剩两个频道
        HashSet<Long> channels = PrefetchSourcesLogic.applyBatch(all, visible, PrefetchSourcesLogic.BATCH_CHANNELS_ONLY);
        assertEquals(2, channels.size());
        assertTrue(channels.contains(-100L));
        assertTrue(channels.contains(-103L));

        // 反选：pending={-103} → 其余三个加入、-103 移除
        HashSet<Long> inverted = PrefetchSourcesLogic.applyBatch(pending, visible, PrefetchSourcesLogic.BATCH_INVERT);
        assertEquals(3, inverted.size());
        assertFalse(inverted.contains(-103L));
        assertTrue(inverted.contains(-100L));
        assertFalse(PrefetchSourcesLogic.applyBatch(pending, null, PrefetchSourcesLogic.BATCH_SELECT_ALL).isEmpty());
    }
}
