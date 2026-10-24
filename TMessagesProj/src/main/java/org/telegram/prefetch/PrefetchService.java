/*
 * This is the source code of Telegram for Android v. 5.x.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.prefetch;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.DownloadController;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.ui.LaunchActivity;

import java.util.ArrayList;

/**
 * 自动缓存视频的常驻前台服务（保活）。
 * <p>
 * 预取调度器原本只依赖进程内的 Handler 定时器：一旦应用进后台被系统回收，
 * 缓存就停摆。这里用 dataSync 前台服务把进程钉住，让调度器的 tick 继续跑；
 * 同时承担三件事：
 * <ul>
 *   <li>START_STICKY：被系统杀掉后自动拉起（拉起时按配置决定是否继续）</li>
 *   <li>前台通知：显示当前缓存任务数，点击回到应用；渠道走内部通知渠道（静音）</li>
 *   <li>部分唤醒锁：仅在确有下载任务时持有，空闲立即释放，避免无谓耗电</li>
 * </ul>
 * 总开关关闭时服务自杀，不残留常驻通知。
 */
public class PrefetchService extends Service implements NotificationCenter.NotificationCenterDelegate {

    public static final int NOTIFICATION_ID = 36;

    /** 进度通知最短刷新间隔，避免每字节都推一次通知 */
    private static final long NOTIFY_THROTTLE_MS = 1000L;
    /** 唤醒锁超时（毫秒）：下载期间持续续期，空闲自动过期 */
    private static final long WAKE_LOCK_TIMEOUT_MS = 10 * 60 * 1000L;

    private static volatile boolean running;
    private static volatile PrefetchService instance;

    private int currentAccount = -1;
    private NotificationCompat.Builder builder;
    private PowerManager.WakeLock wakeLock;
    private long lastNotifyTime;

    public static boolean isRunning() {
        return running;
    }

    /** 幂等启动（调用方无需判断运行状态）。 */
    public static void start() {
        start(UserConfig.selectedAccount);
    }

    /** 指定账号启动（开机恢复时按已激活账号拉起）。 */
    public static void start(int account) {
        if (running) {
            return;
        }
        Context ctx = ApplicationLoader.applicationContext;
        if (ctx == null) {
            return;
        }
        try {
            Intent intent = new Intent(ctx, PrefetchService.class);
            intent.putExtra("account", account);
            if (Build.VERSION.SDK_INT >= 26) {
                ctx.startForegroundService(intent);
            } else {
                ctx.startService(intent);
            }
        } catch (Throwable e) {
            // Android 12+ 后台禁止启动前台服务时会抛异常：静默降级，等下次前台触发
            FileLog.e(e);
        }
    }

    public static void stop() {
        Context ctx = ApplicationLoader.applicationContext;
        if (ctx == null) {
            return;
        }
        try {
            ctx.stopService(new Intent(ctx, PrefetchService.class));
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    /** 调度器每次 tick 后刷新一次通知内容（任务数可能变化）。 */
    public static void refresh() {
        PrefetchService local = instance;
        if (local != null) {
            local.updateNotification(false);
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // 被系统杀后 START_STICKY 重启时 intent 为 null，回落到当前账号
        int account = intent != null ? intent.getIntExtra("account", UserConfig.selectedAccount) : UserConfig.selectedAccount;
        if (account < 0 || account >= UserConfig.MAX_ACCOUNT_COUNT) {
            account = UserConfig.selectedAccount;
        }
        if (currentAccount != account) {
            if (currentAccount != -1) {
                NotificationCenter.getInstance(currentAccount).removeObserver(this, NotificationCenter.fileLoadProgressChanged);
            }
            NotificationCenter.getInstance(account).addObserver(this, NotificationCenter.fileLoadProgressChanged);
            currentAccount = account;
        }

        if (builder == null) {
            buildNotification();
        }
        running = true;
        instance = this;
        // 必须先 startForeground 再判断开关：Android 12+ 对 startForegroundService 有 5s 硬性要求
        try {
            startForeground(NOTIFICATION_ID, builder.build());
        } catch (Throwable e) {
            FileLog.e(e);
        }
        if (!PrefetchStore.load(currentAccount).enabled) {
            stopSelf();
            return START_NOT_STICKY;
        }
        updateNotification(true);
        // 服务刚起来立刻扫一轮，不必等下一个 15 分钟 tick
        PrefetchScheduler.getInstance(currentAccount).onConfigChanged();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        instance = null;
        if (currentAccount != -1) {
            NotificationCenter.getInstance(currentAccount).removeObserver(this, NotificationCenter.fileLoadProgressChanged);
        }
        releaseWakeLock();
        try {
            stopForeground(true);
        } catch (Throwable ignore) {
        }
        try {
            NotificationManagerCompat.from(ApplicationLoader.applicationContext).cancel(NOTIFICATION_ID);
        } catch (Throwable ignore) {
        }
        super.onDestroy();
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.fileLoadProgressChanged) {
            updateNotification(false);
        }
    }

    // ------------------------------------------------------------------
    // 通知
    // ------------------------------------------------------------------

    private void buildNotification() {
        Context ctx = ApplicationLoader.applicationContext;
        String channelId = ensureChannel(ctx);
        builder = new NotificationCompat.Builder(ctx, channelId);
        builder.setSmallIcon(R.drawable.notification);
        builder.setContentTitle(LocaleController.getString(R.string.BackgroundPrefetch));
        builder.setContentText(LocaleController.getString(R.string.PrefetchServiceIdle));
        builder.setOngoing(true);
        builder.setShowWhen(false);
        builder.setOnlyAlertOnce(true);
        builder.setPriority(NotificationCompat.PRIORITY_MIN);
        builder.setContentIntent(contentIntent());
    }

    /** 专用静音渠道：常驻服务通知不该响铃或震动。 */
    private static String ensureChannel(Context ctx) {
        if (Build.VERSION.SDK_INT < 26) {
            return "";
        }
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) {
            return "";
        }
        final String id = "prefetch_auto_cache";
        NotificationChannel existing = nm.getNotificationChannel(id);
        if (existing == null) {
            NotificationChannel channel = new NotificationChannel(id,
                    LocaleController.getString(R.string.PrefetchChannelName), NotificationManager.IMPORTANCE_LOW);
            channel.setShowBadge(false);
            channel.enableVibration(false);
            channel.setSound(null, null);
            try {
                nm.createNotificationChannel(channel);
            } catch (Throwable e) {
                FileLog.e(e);
            }
        }
        return id;
    }

    /** 点击通知回到应用（优先复用现有任务栈，不新开实例）。 */    private PendingIntent contentIntent() {
        try {
            Context ctx = ApplicationLoader.applicationContext;
            Intent intent = new Intent(ctx, LaunchActivity.class);
            intent.putExtra("currentAccount", currentAccount);
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= 23) {
                flags |= PendingIntent.FLAG_IMMUTABLE;
            }
            return PendingIntent.getActivity(ctx, 0, intent, flags);
        } catch (Throwable e) {
            FileLog.e(e);
            return null;
        }
    }

    private int countActivePrefetch() {
        int count = 0;
        try {
            ArrayList<MessageObject> list = DownloadController.getInstance(currentAccount).downloadingFiles;
            for (int a = 0; a < list.size(); a++) {
                MessageObject mo = list.get(a);
                if (mo.prefetch && mo.getDocument() != null) {
                    count++;
                }
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        return count;
    }

    private void updateNotification(boolean force) {
        if (builder == null) {
            return;
        }
        long now = System.currentTimeMillis();
        if (!force && now - lastNotifyTime < NOTIFY_THROTTLE_MS) {
            return;
        }
        lastNotifyTime = now;
        int active = countActivePrefetch();
        CharSequence text = active > 0
                ? LocaleController.formatString(R.string.PrefetchServiceActive, active)
                : LocaleController.getString(R.string.PrefetchServiceIdle);
        builder.setContentText(text);
        if (active > 0) {
            builder.setProgress(0, 0, true);
            acquireWakeLock();
        } else {
            builder.setProgress(0, 0, false);
            releaseWakeLock();
        }
        try {
            Notification notification = builder.build();
            if (running) {
                NotificationManagerCompat.from(ApplicationLoader.applicationContext).notify(NOTIFICATION_ID, notification);
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    // ------------------------------------------------------------------
    // 唤醒锁（仅下载期间持有，空闲自动过期）
    // ------------------------------------------------------------------

    private void acquireWakeLock() {
        try {
            PowerManager pm = (PowerManager) ApplicationLoader.applicationContext.getSystemService(Context.POWER_SERVICE);
            if (pm == null) {
                return;
            }
            if (wakeLock != null && wakeLock.isHeld()) {
                // 已在持有：续期超时，避免长下载被系统中途回收 CPU
                wakeLock.release();
            }
            if (wakeLock == null) {
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "telegram:prefetch");
                wakeLock.setReferenceCounted(false);
            }
            wakeLock.acquire(WAKE_LOCK_TIMEOUT_MS);
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    private void releaseWakeLock() {
        try {
            if (wakeLock != null && wakeLock.isHeld()) {
                wakeLock.release();
            }
        } catch (Throwable ignore) {
        }
    }
}
