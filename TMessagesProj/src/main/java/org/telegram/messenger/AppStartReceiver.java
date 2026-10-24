/*
 * This is the source code of Telegram for Android v. 5.x.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright Nikolai Kudashov, 2013-2018.
 */

package org.telegram.messenger;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import org.telegram.prefetch.PrefetchService;
import org.telegram.prefetch.PrefetchStore;

public class AppStartReceiver extends BroadcastReceiver {

    public void onReceive(Context context, Intent intent) {
        if (intent != null && Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            AndroidUtilities.runOnUIThread(() -> {
                SharedConfig.loadConfig();
                if (SharedConfig.passcodeHash.length() > 0) {
                    SharedConfig.appLocked = true;
                    SharedConfig.saveConfig();
                }
                ApplicationLoader.startPushService();
                // 开机后若「自动缓存视频」开着，重新拉起常驻缓存服务
                try {
                    for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
                        if (UserConfig.getInstance(a).isClientActivated() && PrefetchStore.load(a).enabled) {
                            PrefetchService.start(a);
                            break;
                        }
                    }
                } catch (Throwable e) {
                    FileLog.e(e);
                }
            });
        }
    }
}
