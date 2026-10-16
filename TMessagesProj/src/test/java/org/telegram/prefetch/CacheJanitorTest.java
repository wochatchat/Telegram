package org.telegram.prefetch;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;

/**
 * 清理器清理计划单测（设计文档 §3.4 / §六.2）。
 */
public class CacheJanitorTest {

    private static final long NOW_SEC = 1_760_000_000L;

    private static ArrayList<CacheJanitor.Item> list(CacheJanitor.Item... arr) {
        ArrayList<CacheJanitor.Item> out = new ArrayList<>();
        for (CacheJanitor.Item it : arr) {
            out.add(it);
        }
        return out;
    }

    private static boolean contains(ArrayList<CacheJanitor.Item> plan, int messageId) {
        for (CacheJanitor.Item it : plan) {
            if (it.messageId == messageId) {
                return true;
            }
        }
        return false;
    }

    @Test
    public void ttl_deletesExpired_keepsForever() {
        CacheJanitor.Item fresh = new CacheJanitor.Item(1, 11, 100, NOW_SEC - 10 * 86400L);
        CacheJanitor.Item old = new CacheJanitor.Item(1, 12, 200, NOW_SEC - 40 * 86400L);
        CacheJanitor.Item foreverOld = new CacheJanitor.Item(1, 13, 300, NOW_SEC - 999 * 86400L);

        // 聊天 1 配置永久保留：什么都不删
        HashMap<Long, Long> keepsForever = new HashMap<>();
        keepsForever.put(1L, -1L);
        ArrayList<CacheJanitor.Item> plan = CacheJanitor.plan(
                list(fresh, old, foreverOld), Long.MAX_VALUE, null, keepsForever, new HashSet<Long>(), NOW_SEC);
        assertEquals(0, plan.size());

        // 默认保留 30 天：两条超期的删，新鲜的留
        HashMap<Long, Long> keepDefault = new HashMap<>();
        plan = CacheJanitor.plan(
                list(fresh, old, foreverOld), Long.MAX_VALUE, null, keepDefault, new HashSet<Long>(), NOW_SEC);
        assertEquals(2, plan.size());
        assertTrue(contains(plan, 12));
        assertTrue(contains(plan, 13));
        assertFalse(contains(plan, 11));
    }

    @Test
    public void chatQuota_trimsOldest() {
        CacheJanitor.Item newer = new CacheJanitor.Item(1, 11, 300, NOW_SEC - 100L);
        CacheJanitor.Item older = new CacheJanitor.Item(1, 12, 300, NOW_SEC - 10 * 86400L);
        HashMap<Long, Long> budgets = new HashMap<>();
        budgets.put(1L, 500L);
        ArrayList<CacheJanitor.Item> plan = CacheJanitor.plan(
                list(older, newer), Long.MAX_VALUE, budgets, new HashMap<Long, Long>(), new HashSet<Long>(), NOW_SEC);
        assertEquals(1, plan.size());
        assertEquals(12, plan.get(0).messageId);
        assertFalse(contains(plan, 11));
    }

    @Test
    public void watermark_trimsOldestFirst_skipsProtected() {
        CacheJanitor.Item protectedOld = new CacheJanitor.Item(1, 11, 300, NOW_SEC - 20 * 86400L);
        CacheJanitor.Item midAge = new CacheJanitor.Item(2, 21, 300, NOW_SEC - 5 * 86400L);
        CacheJanitor.Item oldest = new CacheJanitor.Item(2, 22, 300, NOW_SEC - 25 * 86400L);

        HashMap<Long, Long> keeps = new HashMap<>();
        keeps.put(1L, -1L);
        keeps.put(2L, -1L);
        HashSet<Long> protectedDialogs = new HashSet<>();
        protectedDialogs.add(1L);

        // 总 900，水位线 500 → 从最旧未保护条目裁起；聊天 1 受保护 → 裁聊天 2 的两条
        ArrayList<CacheJanitor.Item> plan = CacheJanitor.plan(
                list(protectedOld, midAge, oldest), 500, null, keeps, protectedDialogs, NOW_SEC);
        assertEquals(2, plan.size());
        assertEquals(22, plan.get(0).messageId);
        assertTrue(contains(plan, 21));
        assertFalse(contains(plan, 11));
    }
}
