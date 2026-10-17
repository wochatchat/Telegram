package org.telegram.prefetch;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/** 队列页纯逻辑单测：状态推导 / 过滤 / 排序 / 进度。 */
public class PrefetchQueueTest {

    private static PrefetchQueueLogic.Item item(long dialogId, String title, boolean prefetch, int status) {
        PrefetchQueueLogic.Item it = new PrefetchQueueLogic.Item();
        it.dialogId = dialogId;
        it.title = title;
        it.prefetch = prefetch;
        it.status = status;
        it.fileName = "file_" + title + ".mp4";
        return it;
    }

    // ---- 状态推导 ----

    @Test
    public void testStatusPriority() {
        // 最近失败 > 一切
        assertEquals(PrefetchQueueLogic.STATUS_FAILED,
                PrefetchQueueLogic.statusFor(true, true, false, false, PrefetchQueueLogic.RECENT_FAILED));
        // 文件已存在 → 完成（即使还在 loading，状态以事实为准）
        assertEquals(PrefetchQueueLogic.STATUS_COMPLETED,
                PrefetchQueueLogic.statusFor(true, true, false, false, PrefetchQueueLogic.RECENT_NONE));
        // 正在下载
        assertEquals(PrefetchQueueLogic.STATUS_DOWNLOADING,
                PrefetchQueueLogic.statusFor(false, true, false, false, PrefetchQueueLogic.RECENT_NONE));
        // 用户暂停优先于硬闸等待
        assertEquals(PrefetchQueueLogic.STATUS_PAUSED,
                PrefetchQueueLogic.statusFor(false, false, true, true, PrefetchQueueLogic.RECENT_NONE));
        // 硬闸等待 > 排队
        assertEquals(PrefetchQueueLogic.STATUS_WAITING,
                PrefetchQueueLogic.statusFor(false, false, false, true, PrefetchQueueLogic.RECENT_NONE));
        assertEquals(PrefetchQueueLogic.STATUS_QUEUED,
                PrefetchQueueLogic.statusFor(false, false, false, false, PrefetchQueueLogic.RECENT_NONE));
    }

    @Test
    public void testPercentFor() {
        assertEquals(-1, PrefetchQueueLogic.percentFor(10, 0));
        assertEquals(-1, PrefetchQueueLogic.percentFor(0, -5));
        assertEquals(0, PrefetchQueueLogic.percentFor(0, 100));
        assertEquals(50, PrefetchQueueLogic.percentFor(50, 100));
        assertEquals(100, PrefetchQueueLogic.percentFor(150, 100));
    }

    // ---- 过滤 ----

    @Test
    public void testFilter() {
        ArrayList<PrefetchQueueLogic.Item> items = new ArrayList<>();
        items.add(item(1, "p", true, 0));
        items.add(item(2, "m", false, 0));
        items.add(item(3, "p2", true, 0));

        assertEquals(3, PrefetchQueueLogic.filter(items, PrefetchQueueLogic.FILTER_ALL).size());

        List<PrefetchQueueLogic.Item> pf = PrefetchQueueLogic.filter(items, PrefetchQueueLogic.FILTER_PREFETCH);
        assertEquals(2, pf.size());
        assertEquals("p", pf.get(0).title);
        assertEquals("p2", pf.get(1).title);

        List<PrefetchQueueLogic.Item> mf = PrefetchQueueLogic.filter(items, PrefetchQueueLogic.FILTER_MANUAL);
        assertEquals(1, mf.size());
        assertEquals("m", mf.get(0).title);
    }

    // ---- 排序 ----

    @Test
    public void testSort() {
        ArrayList<PrefetchQueueLogic.Item> items = new ArrayList<>();
        items.add(item(1, "completed", true, PrefetchQueueLogic.STATUS_COMPLETED));
        items.add(item(2, "downloading1", false, PrefetchQueueLogic.STATUS_DOWNLOADING));
        items.add(item(3, "failed", true, PrefetchQueueLogic.STATUS_FAILED));
        items.add(item(4, "queued1", true, PrefetchQueueLogic.STATUS_QUEUED));
        items.add(item(5, "downloading2", true, PrefetchQueueLogic.STATUS_DOWNLOADING));
        items.add(item(6, "waiting", false, PrefetchQueueLogic.STATUS_WAITING));
        items.add(item(6, "paused", true, PrefetchQueueLogic.STATUS_PAUSED));

        PrefetchQueueLogic.sort(items);

        assertEquals("downloading1", items.get(0).title);
        assertEquals("downloading2", items.get(1).title);
        // 同状态稳定：排队项保持插入顺序
        assertEquals("failed", items.get(5).title);
        assertEquals("completed", items.get(6).title);
    }
}
