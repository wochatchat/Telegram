/*
 * This is the source code of Telegram for Android v. 5.x.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.prefetch;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import org.telegram.SQLite.SQLiteCursor;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.BaseController;
import org.telegram.messenger.DownloadController;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.NativeByteBuffer;
import org.telegram.tgnet.TLRPC;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 预取调度器（控制页设计文档 §六 数据流 / §3.4、§5.2、§5.3）。
 * <ul>
 *   <li>15 分钟定期扫描白名单聊天</li>
 *   <li>硬闸过滤（网络/时段/电量/月度限额）</li>
 *   <li>候选打分、并发槽控制</li>
 *   <li>网络/电量变化实时重调度；条件不满足时暂停在途预取</li>
 *   <li>取消勾选聊天 → 立即移除该聊天未开始任务（§3.4）</li>
 * </ul>
 */
public class PrefetchScheduler extends BaseController {

    private static final long TICK_INTERVAL = 15 * 60 * 1000L;
    private static final int MAX_CANDIDATES_PER_CHAT = 100;

    private static volatile PrefetchScheduler[] instances = new PrefetchScheduler[UserConfig.MAX_ACCOUNT_COUNT];

    public static PrefetchScheduler getInstance(int num) {
        PrefetchScheduler local = instances[num];
        if (local == null) {
            synchronized (PrefetchScheduler.class) {
                local = instances[num];
                if (local == null) {
                    instances[num] = local = new PrefetchScheduler(num);
                }
            }
        }
        return local;
    }

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Runnable tickRunnable = new Runnable() {
        @Override
        public void run() {
            tick();
        }
    };
    private final AtomicBoolean ticking = new AtomicBoolean(false);
    private volatile boolean started;

    /** 最后一次硬闸结果：null = 放行（供队列页显示"等待 xx"） */
    public volatile String lastGateReason;

    private BroadcastReceiver networkReceiver;
    private BroadcastReceiver batteryReceiver;

    private PrefetchScheduler(int account) {
        super(account);
    }

    // ------------------------------------------------------------------
    // 扫描单个聊天 → 评分 → 入队
    // ------------------------------------------------------------------

    private void scanChatAndEnqueue(long dialogId, PrefetchConfig.ChatPrefetchSource src,
                                    PrefetchConfig cfg, PrefetchScorer.Env env, int maxEnqueue) {
        if (maxEnqueue <= 0) {
            return;
        }
        final int depthSec = cfg.effectiveDepthDays(src) * 86400;
        final long minDate = env.nowMillis / 1000 - depthSec;
        final String query = String.format(Locale.US,
            "SELECT m.data, m.mid FROM media_v4 m WHERE m.uid = %d AND m.type = 0 AND m.date >= %d AND m.mid > 0 ORDER BY m.date DESC, m.mid DESC LIMIT %d",
            dialogId, minDate, MAX_CANDIDATES_PER_CHAT);
        final MessagesStorage storage = MessagesStorage.getInstance(currentAccount);
        storage.getStorageQueue().postRunnable(new Runnable() {
            @Override
            public void run() {
                ArrayList<TLRPC.Message> messages = new ArrayList<>();
                try {
                    SQLiteCursor cursor = storage.getDatabase().queryFinalized(query);
                    while (cursor.next()) {
                        NativeByteBuffer data = cursor.byteBufferValue(0);
                        if (data == null) {
                            continue;
                        }
                        TLRPC.Message msg = TLRPC.Message.TLdeserialize(data, data.readInt32(false), false);
                        data.reuse();
                        if (msg != null) {
                            msg.id = cursor.intValue(1);
                            msg.dialog_id = dialogId;
                            messages.add(msg);
                        }
                    }
                    cursor.dispose();
                } catch (Exception e) {
                    FileLog.e(e);
                }
                final ArrayList<TLRPC.Message> msgs = messages;
                final int budget = maxEnqueue;
                AndroidUtilities.runOnUIThread(new Runnable() {
                    @Override
                    public void run() {
                        int enqueued = 0;
                        for (int a = 0; a < msgs.size() && enqueued < budget; a++) {
                            TLRPC.Message msg = msgs.get(a);
                            if (msg == null || msg.media == null || msg.media.document == null) {
                                continue;
                            }
                            if (!MessageObject.isVideoDocument(msg.media.document)) {
                                continue;
                            }
                            File path = FileLoader.getInstance(currentAccount).getPathToMessage(msg);
                            if (path != null && path.exists()) {
                                continue;
                            }
                            if (isInDownloadQueue(msg.media.document.id)) {
                                continue;
                            }
                            PrefetchScorer.Candidate cand = new PrefetchScorer.Candidate(
                                    dialogId, msg.id, msg.date, msg.media.document.size);
                            PrefetchScorer.Verdict v = PrefetchScorer.evaluate(cand, cfg, src, env);
                            if (!v.feasible) {
                                continue;
                            }
                            MessageObject mo = new MessageObject(currentAccount, msg, false, false);
                            mo.prefetch = true;
                            FileLoader.getInstance(currentAccount)
                                    .loadFile(mo.getDocument(), mo, FileLoader.PRIORITY_LOW, 0);
                            enqueued++;
                        }
                    }
                });
            }
        });
    }


    // ------------------------------------------------------------------
    // 生命周期
    // ------------------------------------------------------------------

    public void start() {
        if (started) {
            return;
        }
        started = true;
        registerReceivers();
        registerConfigListener();
        scheduleNext();
    }

    public void stop() {
        started = false;
        mainHandler.removeCallbacks(tickRunnable);
        unregisterReceivers();
        unregisterConfigListener();
    }

    /**
     * 配置变更热生效入口（设置页保存后调用；§六.3 参数直读）。
     * 调度器每次 tick 均从 SharedPreferences 重新加载配置，这里只需立刻重评估
     * 硬闸（暂停/恢复）与并发槽，无需重启。
     */
    public void onConfigChanged() {
        mainHandler.post(tickRunnable);
    }

    /** 强引用持有防 GC（registerOnSharedPreferenceChangeListener 是弱引用注册） */
    private final SharedPreferences.OnSharedPreferenceChangeListener configListener = new SharedPreferences.OnSharedPreferenceChangeListener() {
        @Override
        public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
            if (key != null && key.startsWith("prefetch_")) {
                mainHandler.post(tickRunnable);
            }
        }
    };

    private void registerConfigListener() {
        try {
            PrefetchStore.prefs(currentAccount).registerOnSharedPreferenceChangeListener(configListener);
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    private void unregisterConfigListener() {
        try {
            PrefetchStore.prefs(currentAccount).unregisterOnSharedPreferenceChangeListener(configListener);
        } catch (Exception ignore) {
        }
    }

    private void registerReceivers() {
        Context ctx = ApplicationLoader.applicationContext;
        try {
            IntentFilter netFilter = new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION);
            if (Build.VERSION.SDK_INT >= 33) {
                ctx.registerReceiver(networkReceiver(), netFilter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                ctx.registerReceiver(networkReceiver(), netFilter);
            }
            ctx.registerReceiver(batteryReceiver(), new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    private void unregisterReceivers() {
        try {
            Context ctx = ApplicationLoader.applicationContext;
            if (networkReceiver != null) {
                ctx.unregisterReceiver(networkReceiver);
            }
            if (batteryReceiver != null) {
                ctx.unregisterReceiver(batteryReceiver);
            }
        } catch (Exception ignore) {
        }
    }

    private BroadcastReceiver networkReceiver() {
        if (networkReceiver == null) {
            networkReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    mainHandler.post(tickRunnable);
                }
            };
        }
        return networkReceiver;
    }

    private BroadcastReceiver batteryReceiver() {
        if (batteryReceiver == null) {
            batteryReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    mainHandler.post(tickRunnable);
                }
            };
        }
        return batteryReceiver;
    }

    private void scheduleNext() {
        mainHandler.removeCallbacks(tickRunnable);
        if (started) {
            mainHandler.postDelayed(tickRunnable, TICK_INTERVAL);
        }
    }

    // ------------------------------------------------------------------
    // 主扫描 tick（UI 线程）
    // ------------------------------------------------------------------

    public void tick() {
        if (!started) {
            return;
        }
        if (!ticking.compareAndSet(false, true)) {
            return;
        }
        AndroidUtilities.runOnUIThread(new Runnable() {
            @Override
            public void run() {
                try {
                    doTick();
                } catch (Exception e) {
                    FileLog.e(e);
                } finally {
                    ticking.set(false);
                }
                scheduleNext();
            }
        });
    }

    private void doTick() {
        PrefetchConfig cfg = PrefetchStore.load(currentAccount);
        PrefetchScorer.Env env = buildEnv(cfg);
        String gate = PrefetchScorer.checkHardGates(cfg, env);
        lastGateReason = gate;
        if (gate != null) {
            pauseActivePrefetch();
            return;
        }
        resumeActivePrefetch();
        int cap = computeCap(cfg);
        if (cap <= 0 || cfg.sources.isEmpty()) {
            return;
        }
        ArrayList<Long> dialogIds = new ArrayList<>(cfg.sources.keySet());
        Collections.sort(dialogIds, DIALOG_ID_COMPARATOR);
        for (int i = 0; i < dialogIds.size(); i++) {
            long dialogId = dialogIds.get(i);
            PrefetchConfig.ChatPrefetchSource src = cfg.sources.get(dialogId);
            if (src == null || src.mode == PrefetchConfig.MODE_MANUAL) {
                continue;
            }
            int inFlight = countInFlightPrefetch();
            if (inFlight >= cap) {
                break;
            }
            scanChatAndEnqueue(dialogId, src, cfg, env, cap - inFlight);
            if (countInFlightPrefetch() >= cap) {
                break;
            }
        }
    }

    private static final Comparator<Long> DIALOG_ID_COMPARATOR = new Comparator<Long>() {
        @Override
        public int compare(Long a, Long b) {
            return Long.compare(a, b);
        }
    };

    /** 预取可用并发槽 = 并发数 - 手动操作保留槽（至少 1） */
    static int computeCap(PrefetchConfig cfg) {
        return Math.max(1, cfg.concurrency - cfg.reservedUserSlots);
    }

    private int countInFlightPrefetch() {
        int count = 0;
        ArrayList<MessageObject> list = DownloadController.getInstance(currentAccount).downloadingFiles;
        for (int a = 0; a < list.size(); a++) {
            if (list.get(a).prefetch) {
                count++;
            }
        }
        return count;
    }

    private boolean isInDownloadQueue(long documentId) {
        ArrayList<MessageObject> list = DownloadController.getInstance(currentAccount).downloadingFiles;
        for (int a = 0; a < list.size(); a++) {
            TLRPC.Document doc = list.get(a).getDocument();
            if (doc != null && doc.id == documentId) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------
    // 暂停 / 恢复 / 移除（条件变化，设计文档 §3.4）
    // ------------------------------------------------------------------

    private void pauseActivePrefetch() {
        ArrayList<MessageObject> list = DownloadController.getInstance(currentAccount).downloadingFiles;
        FileLoader fl = FileLoader.getInstance(currentAccount);
        for (int a = 0; a < list.size(); a++) {
            MessageObject mo = list.get(a);
            if (!mo.prefetch || mo.getDocument() == null) {
                continue;
            }
            if (fl.isLoadingFile(mo.getFileName())) {
                fl.cancelLoadFile(mo.getDocument(), false); // 保留半成品分片
            }
        }
    }

    private void resumeActivePrefetch() {
        ArrayList<MessageObject> list = DownloadController.getInstance(currentAccount).downloadingFiles;
        FileLoader fl = FileLoader.getInstance(currentAccount);
        for (int a = 0; a < list.size(); a++) {
            MessageObject mo = list.get(a);
            if (!mo.prefetch || mo.getDocument() == null) {
                continue;
            }
            File path = fl.getPathToMessage(mo.messageOwner);
            if (path != null && path.exists()) {
                continue;
            }
            if (!fl.isLoadingFile(mo.getFileName())) {
                fl.loadFile(mo.getDocument(), mo, FileLoader.PRIORITY_LOW, 0);
            }
        }
    }

    /**
     * 移除指定聊天的所有未开始预取任务（§3.4：取消勾选立即移除）。
     * 已下载完成的文件不在此删除（交由 CacheJanitor 处理）。
     */
    public void removeChatTasks(long dialogId) {
        AndroidUtilities.runOnUIThread(new Runnable() {
            @Override
            public void run() {
                ArrayList<MessageObject> list = DownloadController.getInstance(currentAccount).downloadingFiles;
                final ArrayList<Long> docIds = new ArrayList<>();
                for (int a = list.size() - 1; a >= 0; a--) {
                    MessageObject mo = list.get(a);
                    if (!mo.prefetch || mo.getDialogId() != dialogId || mo.getDocument() == null) {
                        continue;
                    }
                    FileLoader.getInstance(currentAccount).cancelLoadFile(mo.getDocument(), false);
                    docIds.add(mo.getDocument().id);
                    list.remove(a);
                }
                if (docIds.isEmpty()) {
                    return;
                }
                MessagesStorage.getInstance(currentAccount).getStorageQueue().postRunnable(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            org.telegram.SQLite.SQLitePreparedStatement state =
                                    MessagesStorage.getInstance(currentAccount).getDatabase()
                                            .executeFast("DELETE FROM downloading_documents WHERE id = ?");
                            for (int a = 0; a < docIds.size(); a++) {
                                state.requery();
                                state.bindLong(1, docIds.get(a));
                                state.step();
                            }
                            state.dispose();
                        } catch (Exception e) {
                            FileLog.e(e);
                        }
                    }
                });
            }
        });
    }

    // ------------------------------------------------------------------
    // 环境快照（UI 线程采集）
    // ------------------------------------------------------------------

    private PrefetchScorer.Env buildEnv(PrefetchConfig cfg) {
        PrefetchScorer.Env env = new PrefetchScorer.Env();
        env.nowMillis = System.currentTimeMillis();
        env.hourOfDay = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY);
        env.netType = currentNetType();
        env.roaming = isRoamingNetwork();
        int[] battery = readBattery();
        env.charging = battery[0] == 1;
        env.batteryPct = battery[1];
        env.monthCellularUsed = PrefetchStore.getMonthCellularUsed(currentAccount);
        return env;
    }

    private int currentNetType() {
        try {
            if (ApplicationLoader.isConnectedOrConnectingToWiFi()) {
                return PrefetchScorer.NET_WIFI;
            }
            NetworkInfo info = ApplicationLoader.currentNetworkInfo;
            if (info != null && info.isConnectedOrConnecting()) {
                if (info.getType() == ConnectivityManager.TYPE_MOBILE) {
                    return isRoamingNetwork() ? PrefetchScorer.NET_ROAMING : PrefetchScorer.NET_CELLULAR;
                }
                if (info.getType() == ConnectivityManager.TYPE_WIFI || info.getType() == ConnectivityManager.TYPE_ETHERNET) {
                    return PrefetchScorer.NET_WIFI;
                }
            }
        } catch (Exception ignore) {
        }
        return PrefetchScorer.NET_OFFLINE;
    }

    private static boolean isRoamingNetwork() {
        try {
            return ApplicationLoader.isRoaming();
        } catch (Exception ignore) {
            return false;
        }
    }

    /** @return [0]=charging(1/0)，[1]=电量百分比（读取失败按 100） */
    private static int[] readBattery() {
        int[] res = new int[]{0, 100};
        try {
            Intent intent = ApplicationLoader.applicationContext.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (intent != null) {
                int status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
                if (status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL) {
                    res[0] = 1;
                }
                int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
                if (level >= 0 && scale > 0) {
                    res[1] = level * 100 / scale;
                }
            }
        } catch (Exception ignore) {
        }
        return res;
    }
}

