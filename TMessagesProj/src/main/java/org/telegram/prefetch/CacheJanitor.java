/*
 * This is the source code of Telegram for Android v. 5.x.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.prefetch;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;

/**
 * 预取缓存清理器（控制页设计文档 §3.4 / §六.2）。
 * <p>
 * 纯静态函数骨架：只负责"删哪些、按什么顺序"，实际的文件删除
 * 在后续轮次接入（R6）。时间通过参数注入，可在 JVM 单测运行。
 */
public class CacheJanitor {

    public static class Item {
        public long dialogId;
        public int messageId;
        public long size;
        /** 消息/入队时间，秒 */
        public long dateSeconds;

        public Item() {
        }

        public Item(long dialogId, int messageId, long size, long dateSeconds) {
            this.dialogId = dialogId;
            this.messageId = messageId;
            this.size = size;
            this.dateSeconds = dateSeconds;
        }
    }

    /** 聊天未配置保留时长时的默认保留（30 天） */
    public static final long DEFAULT_KEEP_SECONDS = 30L * 86400;

    /**
     * 生成清理计划（有序，先删者优先级高），不会包含白名单硬保护聊天：
     * <ol>
     *   <li>TTL：超过该聊天生效保留秒数的先删（keepSeconds &lt; 0 = 永久保留）</li>
     *   <li>单聊天配额：超出预算的聊天从最旧开始裁剪</li>
     *   <li>全局水位线：总大小超水位线时从最旧未保护条目开始裁剪</li>
     * </ol>
     *
     * @param items            当前已下载的预取条目
     * @param watermarkBytes   全局水位线（字节）
     * @param chatBudgets      dialogId → 单聊天预算字节
     * @param keepSeconds      dialogId → 保留秒数；&lt; 0 = 永久保留
     * @param protectedDialogs 白名单硬保护聊天（水位线裁剪时跳过）
     * @param nowSeconds       当前时间（秒）
     */
    public static ArrayList<Item> plan(ArrayList<Item> items,
                                       long watermarkBytes,
                                       HashMap<Long, Long> chatBudgets,
                                       HashMap<Long, Long> keepSeconds,
                                       HashSet<Long> protectedDialogs,
                                       long nowSeconds) {
        ArrayList<Item> deletions = new ArrayList<>();
        if (items == null || items.isEmpty()) {
            return deletions;
        }
        HashSet<Item> deleted = new HashSet<>();
        HashMap<Long, ArrayList<Item>> perChat = new HashMap<>();
        long total = 0;
        for (int a = 0; a < items.size(); a++) {
            Item it = items.get(a);
            ArrayList<Item> list = perChat.get(it.dialogId);
            if (list == null) {
                list = new ArrayList<>();
                perChat.put(it.dialogId, list);
            }
            list.add(it);
            total += it.size;
        }

        // 1) TTL：按聊天生效保留秒数（< 0 = 永久保留，跳过）
        for (int a = 0; a < items.size(); a++) {
            Item it = items.get(a);
            if (deleted.contains(it)) {
                continue;
            }
            Long keep = keepSeconds != null ? keepSeconds.get(it.dialogId) : null;
            if (keep != null && keep < 0) {
                continue;
            }
            long effectiveKeep = keep != null && keep > 0 ? keep : DEFAULT_KEEP_SECONDS;
            if (nowSeconds - it.dateSeconds > effectiveKeep) {
                deletions.add(it);
                deleted.add(it);
                total -= it.size;
                perChat.get(it.dialogId).remove(it);
            }
        }

        // 2) 单聊天配额：超出预算的聊天从最旧开始裁剪
        for (HashMap.Entry<Long, ArrayList<Item>> e : perChat.entrySet()) {
            Long budget = chatBudgets != null ? chatBudgets.get(e.getKey()) : null;
            if (budget == null || budget <= 0) {
                continue;
            }
            ArrayList<Item> list = e.getValue();
            sortByDateDesc(list); // 新 → 旧，尾部为最旧
            while (!list.isEmpty() && sum(list) > budget) {
                Item oldest = list.remove(list.size() - 1);
                deletions.add(oldest);
                deleted.add(oldest);
                total -= oldest.size;
            }
        }

        // 3) 全局水位线：从最旧的未保护条目开始裁剪
        ArrayList<Item> rest = new ArrayList<>();
        for (int a = 0; a < items.size(); a++) {
            if (!deleted.contains(items.get(a))) {
                rest.add(items.get(a));
            }
        }
        sortByDateDesc(rest);
        for (int i = rest.size() - 1; i >= 0 && total > watermarkBytes; i--) {
            Item it = rest.get(i);
            if (protectedDialogs != null && protectedDialogs.contains(it.dialogId)) {
                continue;
            }
            if (deleted.contains(it)) {
                continue;
            }
            deletions.add(it);
            deleted.add(it);
            total -= it.size;
        }
        return deletions;
    }

    private static long sum(ArrayList<Item> list) {
        long s = 0;
        for (int a = 0; a < list.size(); a++) {
            s += list.get(a).size;
        }
        return s;
    }

    /** 新 → 旧排序（尾部最旧，便于从尾部裁剪） */
    private static void sortByDateDesc(ArrayList<Item> list) {
        Collections.sort(list, new Comparator<Item>() {
            @Override
            public int compare(Item o1, Item o2) {
                return Long.compare(o2.dateSeconds, o1.dateSeconds);
            }
        });
    }

    private static void sortByDateAsc(ArrayList<Item> list) {
        Collections.sort(list, new Comparator<Item>() {
            @Override
            public int compare(Item o1, Item o2) {
                return Long.compare(o1.dateSeconds, o2.dateSeconds);
            }
        });
    }
}
