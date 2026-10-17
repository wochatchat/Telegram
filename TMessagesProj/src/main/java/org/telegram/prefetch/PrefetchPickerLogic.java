/*
 * This is the source code of Telegram for Android v. 5.x.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.prefetch;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 手动选消息 · 纯逻辑（设计文档 §3.3）。
 * <p>
 * 视频网格按日期分组的段计算与选择集操作，全部为纯静态函数，无 Android 依赖，可 JVM 单测。
 * 页面本体（PrefetchVideoPickerActivity）持有 Android 组件，不进本类。
 */
public final class PrefetchPickerLogic {

    /** 连续同一天的行区间 [start, end)（输入按时间降序排列）。 */
    public static final class Section {
        public final long dayKey;
        public int start;
        public int end;

        public Section(long dayKey, int start, int end) {
            this.dayKey = dayKey;
            this.start = start;
            this.end = end;
        }
    }

    private PrefetchPickerLogic() {
    }

    /**
     * UTC 秒时间戳 → 当地日历日 key（yyyy*10000+MM*100+dd）。
     * Calendar 由调用方提供（单线程内复用），便于单测注入固定时区。
     */
    public static long dayKeyOf(long dateSeconds, java.util.Calendar cal) {
        cal.setTimeInMillis(dateSeconds * 1000L);
        int y = cal.get(java.util.Calendar.YEAR);
        int m = cal.get(java.util.Calendar.MONTH) + 1;
        int d = cal.get(java.util.Calendar.DAY_OF_MONTH);
        return y * 10000L + m * 100L + d;
    }

    /**
     * 输入按时间降序排列的 dayKey 序列 → 连续同值段列表（[start,end)，降序时 start 即段首）。
     * 空列表返回空；单元素返回一个段。
     */
    public static ArrayList<Section> groupSections(List<Long> dayKeys) {
        ArrayList<Section> out = new ArrayList<>();
        for (int i = 0; i < dayKeys.size(); i++) {
            long key = dayKeys.get(i);
            if (!out.isEmpty() && out.get(out.size() - 1).dayKey == key) {
                out.get(out.size() - 1).end = i + 1;
            } else {
                out.add(new Section(key, i, i + 1));
            }
        }
        return out;
    }

    /** 选择集切换：@return 切换后是否选中。 */
    public static boolean toggle(Set<Long> selected, long id) {
        if (selected.remove(id)) {
            return false;
        }
        selected.add(id);
        return true;
    }
}
