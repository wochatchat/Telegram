/*
 * This is the source code of Telegram for Android v. 5.x.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.ui;

import android.content.Context;
import android.graphics.PorterDuff;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.DownloadController;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.UserObject;
import org.telegram.prefetch.PrefetchConfig;
import org.telegram.prefetch.PrefetchQueueLogic;
import org.telegram.prefetch.PrefetchScheduler;
import org.telegram.prefetch.PrefetchStore;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.ActionBarMenu;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BackDrawable;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Components.EmptyTextProgressView;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.NumberTextView;
import org.telegram.ui.Components.RecyclerListView;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;

/**
 * 后台预下载 · 下载队列页（设计文档 §四 Tab2）。
 * <p>
 * 绑定 {@link DownloadController} 观察者，不另建数据源（§4.4）：
 * 列表 = downloadingFiles 现状推导 + 本会话内完成/失败事件记录。
 * 状态列（排队/下载中/暂停/等待/完成/失败）+ 进度条（fileLoadProgressChanged）。
 * 单条操作（暂停/恢复/置顶/重试/删除/跳来源聊天），批量 action mode（暂停/恢复/删除），
 * 清空已完成 + 清空全部并停跑（双确认）。删除进行中任务清 FileLoader 半成品分片（§八.2）。
 */
public class PrefetchQueueActivity extends BaseFragment implements NotificationCenter.NotificationCenterDelegate {

    private static final int MENU_FILTER = 1;
    private static final int MENU_MORE = 2;

    private static final int FILTER_ID_ALL = 100;
    private static final int FILTER_ID_PREFETCH = 101;
    private static final int FILTER_ID_MANUAL = 102;

    private static final int MORE_ID_CLEAR_FINISHED = 200;
    private static final int MORE_ID_CLEAR_ALL_STOP = 201;

    private static final int BATCH_ID_SELECT_ALL = 310;
    private static final int BATCH_ID_SELECT_NONE = 311;
    private static final int BATCH_ID_INVERT = 312;
    private static final int BATCH_ID_PAUSE = 313;
    private static final int BATCH_ID_RESUME = 314;
    private static final int BATCH_ID_DELETE = 315;

    private static final int SHEET_PAUSE = 400;
    private static final int SHEET_RESUME = 401;
    private static final int SHEET_TOP = 402;
    private static final int SHEET_RETRY = 403;
    private static final int SHEET_DELETE = 404;
    private static final int SHEET_OPEN = 405;

    private static final int VIEW_TYPE_ITEM = 0;
    private static final int VIEW_TYPE_INFO = 1;

    private RecyclerListView listView;
    private ListAdapter listAdapter;
    private EmptyTextProgressView emptyView;
    private NumberTextView selectedCountTextView;

    /** 展示列表 = 活动队列（downloadingFiles 推导）+ 本会话已完成/失败记录。 */
    private final ArrayList<PrefetchQueueLogic.Item> displayItems = new ArrayList<>();
    private final ArrayList<PrefetchQueueLogic.Item> finishedItems = new ArrayList<>();

    /** fileName → 最新已下载字节（进度事件累积，session 级）。 */
    private final HashMap<String, Long> loadedBytes = new HashMap<>();
    /** fileName → MessageObject（操作句柄）。 */
    private final HashMap<String, MessageObject> moByFileName = new HashMap<>();
    /** 本会话内用户手动暂停过的文件（cancelLoadFile 保留分片）。 */
    private final HashSet<String> userPaused = new HashSet<>();

    private int filter = PrefetchQueueLogic.FILTER_ALL;
    private boolean actionModeOpen;
    private final HashSet<String> pendingSelection = new HashSet<>();

    @Override
    public boolean onFragmentCreate() {
        super.onFragmentCreate();
        getNotificationCenter().addObserver(this, NotificationCenter.fileLoaded);
        getNotificationCenter().addObserver(this, NotificationCenter.fileLoadFailed);
        getNotificationCenter().addObserver(this, NotificationCenter.fileLoadProgressChanged);
        rebuildAll();
        return true;
    }

    @Override
    public void onFragmentDestroy() {
        super.onFragmentDestroy();
        getNotificationCenter().removeObserver(this, NotificationCenter.fileLoaded);
        getNotificationCenter().removeObserver(this, NotificationCenter.fileLoadFailed);
        getNotificationCenter().removeObserver(this, NotificationCenter.fileLoadProgressChanged);
    }

    /** Keep up with theme changes like native list screens do. */
    private void applyThemeColors() {
        if (fragmentView == null) {
            return;
        }
        actionBar.setTitleColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText));
        actionBar.setItemsColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText), false);
        fragmentView.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundGray));
        listView.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundGray));
        listView.invalidateViews();
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonDrawable(new BackDrawable(false));
        actionBar.setTitle(LocaleController.getString(R.string.PrefetchQueue));
        actionBar.setAllowOverlayTitle(true);

        ActionBarMenu menu = actionBar.createMenu();
        ActionBarMenuItem filterItem = menu.addItem(MENU_FILTER, R.drawable.menu_tag_filter);
        filterItem.addSubItem(FILTER_ID_ALL, LocaleController.getString(R.string.PrefetchFilterAll));
        filterItem.addSubItem(FILTER_ID_PREFETCH, LocaleController.getString(R.string.PrefetchFilterPrefetch));
        filterItem.addSubItem(FILTER_ID_MANUAL, LocaleController.getString(R.string.PrefetchFilterManual));

        ActionBarMenuItem moreItem = menu.addItem(MENU_MORE, R.drawable.ic_ab_other);
        moreItem.addSubItem(MORE_ID_CLEAR_FINISHED, LocaleController.getString(R.string.PrefetchClearFinished));
        moreItem.addSubItem(MORE_ID_CLEAR_ALL_STOP, LocaleController.getString(R.string.PrefetchClearAllStop));

        // 批量操作 action mode（多选计数 + overflow 子菜单）
        ActionBarMenu actionMode = actionBar.createActionMode(false, null);
        selectedCountTextView = new NumberTextView(actionMode.getContext());
        selectedCountTextView.setTextSize(18);
        selectedCountTextView.setTypeface(AndroidUtilities.bold());
        selectedCountTextView.setTextColor(getThemedColor(Theme.key_actionBarActionModeDefaultIcon));
        actionMode.addView(selectedCountTextView, LayoutHelper.createLinear(0, LayoutHelper.MATCH_PARENT, 1.0f, 18, 0, 0, 0));
        selectedCountTextView.setOnTouchListener((v, event) -> true);
        ActionBarMenuItem batchItem = actionMode.addItem(0, R.drawable.ic_ab_other);
        batchItem.addSubItem(BATCH_ID_SELECT_ALL, LocaleController.getString(R.string.PrefetchBatchSelectAll));
        batchItem.addSubItem(BATCH_ID_SELECT_NONE, LocaleController.getString(R.string.PrefetchBatchSelectNone));
        batchItem.addSubItem(BATCH_ID_INVERT, LocaleController.getString(R.string.PrefetchBatchInvert));
        batchItem.addSubItem(BATCH_ID_PAUSE, LocaleController.getString(R.string.PrefetchPause));
        batchItem.addSubItem(BATCH_ID_RESUME, LocaleController.getString(R.string.PrefetchResume));
        batchItem.addSubItem(BATCH_ID_DELETE, LocaleController.getString(R.string.Delete));

        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                handleMenuClick(id);
            }
        });

        listAdapter = new ListAdapter(context);

        emptyView = new EmptyTextProgressView(context);
        emptyView.setText(LocaleController.getString(R.string.PrefetchQueueEmpty));
        emptyView.showTextView();

        listView = new RecyclerListView(context);
        listView.setLayoutManager(new LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false));
        listView.setVerticalScrollBarEnabled(false);
        listView.setAdapter(listAdapter);
        listView.setEmptyView(emptyView);
        listView.setOnItemClickListener((view, position) -> onItemClicked(position));
        listView.setOnItemLongClickListener((view, position) -> {
            onItemLongPressed(position);
            return true;
        });

        fragmentView = new ColorableFrameLayout(context, this::applyThemeColors);
        ((FrameLayout) fragmentView).addView(emptyView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        ((FrameLayout) fragmentView).addView(listView, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        applyThemeColors();
        return fragmentView;
    }

    // ------------------------------------------------------------------
    // 数据构建（§4.4 不另建数据源，全部由 DownloadController 现状推导）
    // ------------------------------------------------------------------

    /** 全量重建：活动队列 + 完成列表合并、过滤、排序后应用。 */
    private void rebuildAll() {
        activeWork.clear();
        moByFileName.clear();
        DownloadController dc = DownloadController.getInstance(currentAccount);
        FileLoader fl = FileLoader.getInstance(currentAccount);
        ArrayList<MessageObject> list = dc.downloadingFiles;
        for (int a = list.size() - 1; a >= 0; a--) {
            MessageObject mo = list.get(a);
            TLRPC.Document doc = mo.getDocument();
            if (doc == null) {
                continue;
            }
            String fileName = mo.getFileName();
            moByFileName.put(fileName, mo);
            PrefetchQueueLogic.Item it = new PrefetchQueueLogic.Item();
            it.dialogId = mo.getDialogId();
            it.title = titleFor(mo);
            it.fileName = fileName;
            it.size = doc.size;
            Long loaded = loadedBytes.get(fileName);
            it.loaded = loaded != null ? loaded : 0;
            it.prefetch = mo.prefetch || PrefetchStore.getPrefetchDocIds(currentAccount).contains(doc.id);
            File path = fl.getPathToMessage(mo.messageOwner);
            it.status = PrefetchQueueLogic.statusFor(
                    path != null && path.exists(),
                    fl.isLoadingFile(fileName),
                    userPaused.contains(fileName),
                    PrefetchScheduler.getInstance(currentAccount).lastGateReason != null,
                    PrefetchQueueLogic.RECENT_NONE);
            it.percent = PrefetchQueueLogic.percentFor(it.loaded, it.size);
            activeWork.add(it);
        }
        applyDisplay();
    }

    /** 重建过程中的工作区（rebuildAll 中转）。 */
    private final ArrayList<PrefetchQueueLogic.Item> activeWork = new ArrayList<>();

    /** 过滤 + 排序 + 应用到 adapter（活动项与完成项合并，完成/失败自然沉底）。 */
    private void applyDisplay() {
        ArrayList<PrefetchQueueLogic.Item> all = new ArrayList<>(activeWork);
        all.addAll(finishedItems);
        List<PrefetchQueueLogic.Item> visible = PrefetchQueueLogic.filter(all, filter);
        PrefetchQueueLogic.sort(visible);
        displayItems.clear();
        displayItems.addAll(visible);
        if (listAdapter != null) {
            listAdapter.notifyDataSetChanged();
        }
        if (emptyView != null) {
            emptyView.showTextView();
        }
    }

    private String titleFor(MessageObject mo) {
        long dialogId = mo.getDialogId();
        if (dialogId < 0) {
            TLRPC.Chat chat = getMessagesController().getChat(-mo.getDialogId());
            if (chat != null && chat.title != null) {
                return chat.title;
            }
        } else {
            TLRPC.User user = getMessagesController().getUser(mo.getDialogId());
            if (user != null) {
                return UserObject.getUserName(user);
            }
        }
        return String.valueOf(dialogId);
    }

    private String statusTextFor(PrefetchQueueLogic.Item it) {
        switch (it.status) {
            case PrefetchQueueLogic.STATUS_DOWNLOADING:
                return LocaleController.getString(R.string.PrefetchStatusDownloading);
            case PrefetchQueueLogic.STATUS_QUEUED:
                return LocaleController.getString(R.string.PrefetchStatusQueued);
            case PrefetchQueueLogic.STATUS_WAITING:
                return waitingText();
            case PrefetchQueueLogic.STATUS_PAUSED:
                return LocaleController.getString(R.string.PrefetchStatusPaused);
            case PrefetchQueueLogic.STATUS_FAILED:
                return LocaleController.getString(R.string.PrefetchStatusFailed);
            case PrefetchQueueLogic.STATUS_COMPLETED:
            default:
                return LocaleController.getString(R.string.PrefetchStatusCompleted);
        }
    }

    /** 硬闸原因 → 等待文案（调度器 lastGateReason，PrefetchScorer.checkHardGates 的返回值）。 */
    private String waitingText() {
        String gate = PrefetchScheduler.getInstance(currentAccount).lastGateReason;
        if (gate == null) {
            return LocaleController.getString(R.string.PrefetchStatusWaiting);
        }
        switch (gate) {
            case "hours":
                return LocaleController.getString(R.string.PrefetchGateHours);
            case "battery":
                return LocaleController.getString(R.string.PrefetchGateBattery);
            case "network":
            case "roaming":
                return LocaleController.getString(R.string.PrefetchGateNetwork);
            case "offline":
                return LocaleController.getString(R.string.PrefetchGateOffline);
            case "monthly_limit":
                return LocaleController.getString(R.string.PrefetchGateMonthly);
            case "disabled":
            default:
                return LocaleController.getString(R.string.PrefetchGateDisabled);
        }
    }

    // ------------------------------------------------------------------
    // 通知（进度/完成/失败）
    // ------------------------------------------------------------------

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.fileLoadProgressChanged) {
            String fileName = (String) args[0];
            long loadedSize = (Long) args[1];
            loadedBytes.put(fileName, loadedSize);
            updateProgressRow(fileName);
        } else if (id == NotificationCenter.fileLoaded || id == NotificationCenter.httpFileDidLoad) {
            onFileFinished((String) args[0], false);
        } else if (id == NotificationCenter.fileLoadFailed || id == NotificationCenter.httpFileDidFailedLoad) {
            Integer canceled = (Integer) args[1];
            onFileFinished((String) args[0], canceled == null || canceled != 1);
        }
    }

    /** 进度事件只刷新对应行（有百分比变化才通知，防闪动）。 */
    private void updateProgressRow(String fileName) {
        for (int i = 0; i < displayItems.size(); i++) {
            PrefetchQueueLogic.Item it = displayItems.get(i);
            if (it.fileName == null || !it.fileName.equals(fileName)) {
                continue;
            }
            Long loaded = loadedBytes.get(fileName);
            int pct = PrefetchQueueLogic.percentFor(loaded != null ? loaded : 0, it.size);
            if (pct == it.percent) {
                return;
            }
            it.loaded = loaded != null ? loaded : 0;
            it.percent = pct;
            if (listAdapter != null) {
                listAdapter.notifyItemChanged(i);
            }
            return;
        }
    }

    /** 下载结束（成功/失败）→ 记入本会话完成列表；用户主动取消（暂停/删除）不算失败。 */
    private void onFileFinished(String fileName, boolean failed) {
        MessageObject mo = moByFileName.get(fileName);
        PrefetchQueueLogic.Item done = null;
        for (int a = activeWork.size() - 1; a >= 0; a--) {
            PrefetchQueueLogic.Item it = activeWork.get(a);
            if (fileName.equals(it.fileName)) {
                done = it;
                break;
            }
        }
        if (done == null && failed) {
            return; // 与本页无关的下载失败
        }
        if (done == null) {
            return;
        }
        if (!failed) {
            done.status = PrefetchQueueLogic.STATUS_COMPLETED;
            // 预取任务完成：从持久化集合移除，防止重启后误标
            MessageObject doneMo = moByFileName.get(fileName);
            if (doneMo != null && doneMo.getDocument() != null) {
                ArrayList<Long> ids = new ArrayList<>();
                ids.add(doneMo.getDocument().id);
                PrefetchStore.removePrefetchDocIds(currentAccount, ids);
            }
        } else {
            done.status = PrefetchQueueLogic.STATUS_FAILED;
        }
        done.percent = 100;
        finishedItems.add(done);
        loadedBytes.remove(fileName);
        userPaused.remove(fileName);
        rebuildAll();
    }

    // ------------------------------------------------------------------
    // 行交互
    // ------------------------------------------------------------------

    private PrefetchQueueLogic.Item itemAt(int position) {
        if (position < 0 || position >= displayItems.size()) {
            return null;
        }
        return displayItems.get(position);
    }

    private void onItemClicked(int position) {
        PrefetchQueueLogic.Item it = itemAt(position);
        if (it == null) {
            return;
        }
        if (actionModeOpen) {
            if (pendingSelection.contains(it.fileName)) {
                pendingSelection.remove(it.fileName);
            } else {
                pendingSelection.add(it.fileName);
            }
            if (selectedCountTextView != null) {
                selectedCountTextView.setNumber(pendingSelection.size(), true);
            }
            if (listAdapter != null) {
                listAdapter.notifyItemChanged(position);
            }
        } else {
            showItemSheet(it, position);
        }
    }

    private void onItemLongPressed(int position) {
        PrefetchQueueLogic.Item it = itemAt(position);
        if (it == null || !it.fileName.equals(displayItems.get(position).fileName)) {
            return;
        }
        if (!actionModeOpen) {
            enterActionMode();
        }
        if (it.fileName != null) {
            pendingSelection.add(it.fileName);
        }
        if (selectedCountTextView != null) {
            selectedCountTextView.setNumber(pendingSelection.size(), true);
        }
        if (listAdapter != null) {
            listAdapter.notifyItemChanged(position);
        }
    }

    /** 单条操作 Sheet：按状态给可用操作（暂停/恢复/置顶/重试/删除/跳转来源聊天）。 */
    private void showItemSheet(PrefetchQueueLogic.Item it, int position) {
        ArrayList<Integer> ids = new ArrayList<>();
        ArrayList<CharSequence> labels = new ArrayList<>();
        switch (it.status) {
            case PrefetchQueueLogic.STATUS_DOWNLOADING:
            case PrefetchQueueLogic.STATUS_QUEUED:
            case PrefetchQueueLogic.STATUS_WAITING:
                ids.add(SHEET_PAUSE);
                labels.add(LocaleController.getString(R.string.PrefetchPause));
                ids.add(SHEET_TOP);
                labels.add(LocaleController.getString(R.string.PrefetchPrioritize));
                break;
            case PrefetchQueueLogic.STATUS_PAUSED:
                ids.add(SHEET_RESUME);
                labels.add(LocaleController.getString(R.string.PrefetchResume));
                break;
            case PrefetchQueueLogic.STATUS_FAILED:
                ids.add(SHEET_RETRY);
                labels.add(LocaleController.getString(R.string.Retry));
                break;
            case PrefetchQueueLogic.STATUS_COMPLETED:
            default:
                break;
        }
        if (it.status != PrefetchQueueLogic.STATUS_COMPLETED) {
            ids.add(SHEET_DELETE);
            labels.add(LocaleController.getString(R.string.Delete));
        }
        ids.add(SHEET_OPEN);
        labels.add(LocaleController.getString(R.string.PrefetchOpenSource));

        BottomSheet.Builder builder = new BottomSheet.Builder(getContext());
        builder.setTitle(it.title);
        builder.setItems(labels.toArray(new CharSequence[0]), (d, which) -> {
            int op = ids.get(which);
            MessageObject mo = moByFileName.get(it.fileName);
            if (op == SHEET_DELETE) {
                if (mo != null) {
                    PrefetchScheduler.getInstance(currentAccount).removeTask(mo);
                }
                removeFromFinished(it.fileName);
                rebuildAll();
            } else if (op == SHEET_OPEN) {
                openSourceChat(it.dialogId);
            } else if (mo != null) {
                applyOp(op, mo);
                rebuildAll();
            }
        });
        showDialog(builder.create());
    }

    private void removeFromFinished(String fileName) {
        for (int a = finishedItems.size() - 1; a >= 0; a--) {
            if (fileName != null && fileName.equals(finishedItems.get(a).fileName)) {
                finishedItems.remove(a);
            }
        }
    }

    // ------------------------------------------------------------------
    // 单条/批量操作
    // ------------------------------------------------------------------

    private void applyOp(int op, MessageObject mo) {
        if (mo == null || mo.getDocument() == null) {
            return;
        }
        FileLoader fl = FileLoader.getInstance(currentAccount);
        String fileName = mo.getFileName();
        switch (op) {
            case SHEET_PAUSE:
                if (fl.isLoadingFile(fileName)) {
                    fl.cancelLoadFile(mo.getDocument(), false); // 保留半成品分片
                }
                userPaused.add(fileName);
                break;
            case SHEET_RESUME:
                userPaused.remove(fileName);
                fl.loadFile(mo.getDocument(), mo, FileLoader.PRIORITY_LOW, 0);
                break;
            case SHEET_TOP:
                // 提升下载优先级（重新 loadFile 提级）
                fl.loadFile(mo.getDocument(), mo, FileLoader.PRIORITY_NORMAL, 0);
                break;
            case SHEET_RETRY:
                // 失败项：从完成列表移除并重新入队
                removeFromFinished(fileName);
                userPaused.remove(fileName);
                fl.loadFile(mo.getDocument(), mo, FileLoader.PRIORITY_LOW, 0);
                break;
        }
    }

    // ------------------------------------------------------------------
    // 批量操作（action mode，§四：暂停/恢复/删除）
    // ------------------------------------------------------------------

    private void enterActionMode() {
        if (actionModeOpen) {
            return;
        }
        actionModeOpen = true;
        pendingSelection.clear();
        actionBar.showActionMode();
        selectedCountTextView.setNumber(0, false);
    }

    private void exitActionMode() {
        actionModeOpen = false;
        pendingSelection.clear();
        actionBar.hideActionMode();
        listAdapter.notifyDataSetChanged();
    }

    /** 批量：对选中项执行暂停/恢复/删除（删除含 FileLoader 半成品分片清理，§八.2）。 */
    private void commitBatchOp(int op) {
        boolean isPause = op == BATCH_ID_PAUSE;
        boolean isResume = op == BATCH_ID_RESUME;
        for (String fileName : new ArrayList<>(pendingSelection)) {
            if (op == BATCH_ID_DELETE) {
                MessageObject mo = moByFileName.get(fileName);
                if (mo != null) {
                    PrefetchScheduler.getInstance(currentAccount).removeTask(mo);
                }
                removeFromFinished(fileName);
            } else if (isPause || isResume) {
                MessageObject mo = moByFileName.get(fileName);
                applyOp(isPause ? SHEET_PAUSE : SHEET_RESUME, mo);
            }
        }
        exitActionMode();
        rebuildAll();
    }



    // ------------------------------------------------------------------
    // 菜单事件
    // ------------------------------------------------------------------

    private void handleMenuClick(int id) {
        if (actionModeOpen) {
            switch (id) {
                case BATCH_ID_SELECT_ALL:
                    pendingSelection.clear();
                    for (int i = 0; i < displayItems.size(); i++) {
                        String fn = displayItems.get(i).fileName;
                        if (fn != null) {
                            pendingSelection.add(fn);
                        }
                    }
                    break;
                case BATCH_ID_SELECT_NONE:
                    pendingSelection.clear();
                    break;
                case BATCH_ID_INVERT:
                    for (int i = 0; i < displayItems.size(); i++) {
                        String fn = displayItems.get(i).fileName;
                        if (fn != null && !pendingSelection.contains(fn)) {
                            pendingSelection.add(fn);
                        }
                    }
                    break;
                case BATCH_ID_PAUSE:
                case BATCH_ID_RESUME:
                case BATCH_ID_DELETE:
                    if (pendingSelection.isEmpty()) {
                        return;
                    }
                    commitBatchOp(id);
                    return;
                default:
                    return;
            }
            selectedCountTextView.setNumber(pendingSelection.size(), true);
            listAdapter.notifyDataSetChanged();
            return;
        }
        switch (id) {
            case FILTER_ID_ALL:
                filter = PrefetchQueueLogic.FILTER_ALL;
                applyDisplay();
                break;
            case FILTER_ID_PREFETCH:
                filter = PrefetchQueueLogic.FILTER_PREFETCH;
                applyDisplay();
                break;
            case FILTER_ID_MANUAL:
                filter = PrefetchQueueLogic.FILTER_MANUAL;
                applyDisplay();
                break;
            case MORE_ID_CLEAR_FINISHED:
                finishedItems.clear();
                applyDisplay();
                break;
            case MORE_ID_CLEAR_ALL_STOP:
                confirmClearAllAndStop();
                break;
        }
    }

    // ------------------------------------------------------------------
    // 清空已完成 / 清空全部并停跑（双确认）
    // ------------------------------------------------------------------

    /** 清空全部并停跑：双确认（两段式弹窗）。 */
    private void confirmClearAllAndStop() {
        Context context = getParentActivity();
        if (context == null) {
            return;
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle(LocaleController.getString(R.string.PrefetchClearAllStop));
        builder.setMessage(LocaleController.getString(R.string.PrefetchClearAllMessage));
        builder.setPositiveButton(LocaleController.getString(R.string.PrefetchClearAllConfirm), (d, w) -> {
            // 双确认：二次弹窗
            AlertDialog.Builder second = new AlertDialog.Builder(context);
            second.setMessage(LocaleController.getString(R.string.PrefetchClearAllConfirmMessage));
            second.setPositiveButton(LocaleController.getString(R.string.OK), (d2, w2) -> doClearAllAndStop());
            second.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
            showDialog(second.create());
        });
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        showDialog(builder.create());
    }

    private void doClearAllAndStop() {
        DownloadController dc = DownloadController.getInstance(currentAccount);
        ArrayList<MessageObject> copy = new ArrayList<>(dc.downloadingFiles);
        for (int a = 0; a < copy.size(); a++) {
            PrefetchScheduler.getInstance(currentAccount).removeTask(copy.get(a));
        }
        finishedItems.clear();
        loadedBytes.clear();
        userPaused.clear();
        // 停跑：总开关关闭（可随时在设置页重新打开）
        try {
            PrefetchConfig cfg = PrefetchStore.load(currentAccount);
            cfg.enabled = false;
            PrefetchStore.save(currentAccount, cfg);
        } catch (Exception e) {
            FileLog.e(e);
        }
        PrefetchScheduler.getInstance(currentAccount).onConfigChanged();
        rebuildAll();
    }

    /** 清空全部并停跑：双确认（两段式弹窗）。 */

    // ------------------------------------------------------------------
    // 跳转来源聊天
    // ------------------------------------------------------------------

    private void openSourceChat(long dialogId) {
        if (dialogId == 0) {
            return;
        }
        Bundle args = new Bundle();
        args.putLong("dialog_id", dialogId);
        if (!getMessagesController().checkCanOpenChat(args, this)) {
            return;
        }
        presentFragment(new ChatActivity(args));
    }

    // ------------------------------------------------------------------
    // Adapter + 队列行（标题 / 状态 / 进度条 / 分隔线）
    // ------------------------------------------------------------------

    /** FrameLayout that re-applies themed colors whenever the active theme changes. */
    private static class ColorableFrameLayout extends FrameLayout implements Theme.Colorable {
        private final Runnable apply;

        ColorableFrameLayout(Context context, Runnable apply) {
            super(context);
            this.apply = apply;
        }

        @Override
        public void updateColors() {
            apply.run();
        }
    }

    private class ListAdapter extends RecyclerListView.SelectionAdapter {

        private final Context context;

        ListAdapter(Context context) {
            this.context = context;
        }

        @Override
        public int getItemCount() {
            return displayItems.size() + 1;
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            return holder.getItemViewType() == VIEW_TYPE_ITEM;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view;
            if (viewType == VIEW_TYPE_ITEM) {
                view = new QueueCell(context);
            } else {
                TextInfoPrivacyCell infoCell = new TextInfoPrivacyCell(context);
                infoCell.setBackgroundDrawable(Theme.getThemedDrawable(context, R.drawable.greydivider_bottom, getThemedColor(Theme.key_windowBackgroundGrayShadow)));
                view = infoCell;
            }
            view.setLayoutParams(new RecyclerView.LayoutParams(RecyclerView.LayoutParams.MATCH_PARENT, RecyclerView.LayoutParams.WRAP_CONTENT));
            return new RecyclerListView.Holder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            if (holder.getItemViewType() == VIEW_TYPE_ITEM) {
                PrefetchQueueLogic.Item it = displayItems.get(position);
                boolean checked = actionModeOpen && it.fileName != null && pendingSelection.contains(it.fileName);
                boolean divider = position != displayItems.size() - 1;
                ((QueueCell) holder.itemView).setItem(it, checked, divider);
            } else {
                ((TextInfoPrivacyCell) holder.itemView).setText(LocaleController.getString(R.string.PrefetchQueueInfo));
            }
        }

        @Override
        public int getItemViewType(int position) {
            if (position < displayItems.size()) {
                return VIEW_TYPE_ITEM;
            }
            return VIEW_TYPE_INFO;
        }
    }

    private class QueueCell extends FrameLayout {

        private final TextView titleTextView;
        private final TextView statusTextView;
        private final ProgressBar progressBar;
        private final View divider;

        QueueCell(Context context) {
            super(context);
            setBackgroundColor(getThemedColor(Theme.key_windowBackgroundWhite));
            setBackgroundDrawable(Theme.getSelectorDrawable(false));

            LinearLayout content = new LinearLayout(context);
            content.setOrientation(LinearLayout.VERTICAL);
            int pad = AndroidUtilities.dp(16);
            content.setPadding(pad, AndroidUtilities.dp(9), pad, AndroidUtilities.dp(10));

            titleTextView = new TextView(context);
            titleTextView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
            titleTextView.setTypeface(AndroidUtilities.bold());
            titleTextView.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText));
            titleTextView.setSingleLine(true);
            titleTextView.setEllipsize(TextUtils.TruncateAt.END);
            content.addView(titleTextView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

            statusTextView = new TextView(context);
            statusTextView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            statusTextView.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteGrayText));
            statusTextView.setSingleLine(true);
            statusTextView.setEllipsize(TextUtils.TruncateAt.END);
            content.addView(statusTextView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 3, 0, 0));

            progressBar = new ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal);
            progressBar.setMax(100);
            progressBar.getProgressDrawable().setColorFilter(getThemedColor(Theme.key_windowBackgroundWhiteValueText), PorterDuff.Mode.SRC_IN);
            progressBar.setVisibility(GONE);
            content.addView(progressBar, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, AndroidUtilities.dp(3), 0, 6, 0, 0));

            addView(content, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

            divider = new View(context);
            divider.setBackgroundColor(getThemedColor(Theme.key_divider));
            addView(divider, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 1 / (float) AndroidUtilities.density, Gravity.BOTTOM | Gravity.LEFT, 16, 0, 0, 0));
        }

        void setItem(PrefetchQueueLogic.Item it, boolean checked, boolean needDivider) {
            titleTextView.setText(it.title);
            String sizeText;
            if (it.status == PrefetchQueueLogic.STATUS_DOWNLOADING && it.size > 0) {
                statusTextView.setText(String.format(Locale.US, "%s · %d%% · %s / %s",
                        statusTextFor(it), it.percent,
                        PrefetchConfig.formatBytes(it.loaded), PrefetchConfig.formatBytes(it.size)));
            } else if (it.size > 0) {
                statusTextView.setText(String.format(Locale.US, "%s · %s",
                        statusTextFor(it), PrefetchConfig.formatBytes(it.size)));
            } else {
                statusTextView.setText(statusTextFor(it));
            }
            int statusColor;
            switch (it.status) {
                case PrefetchQueueLogic.STATUS_FAILED:
                    statusColor = Theme.getColor(Theme.key_text_RedRegular);
                    break;
                case PrefetchQueueLogic.STATUS_DOWNLOADING:
                    statusColor = Theme.getColor(Theme.key_windowBackgroundWhiteValueText);
                    break;
                default:
                    statusColor = Theme.getColor(Theme.key_windowBackgroundWhiteGrayText);
                    break;
            }
            statusTextView.setTextColor(statusColor);

            if (it.status == PrefetchQueueLogic.STATUS_DOWNLOADING && it.percent >= 0) {
                progressBar.setVisibility(VISIBLE);
                progressBar.setProgress(it.percent);
            } else {
                progressBar.setVisibility(INVISIBLE);
            }
            if (checked) {
                setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundChecked));
            } else {
                setBackgroundDrawable(Theme.getSelectorDrawable(false));
            }
            this.divider.setVisibility(needDivider ? VISIBLE : INVISIBLE);
        }
    }
}
