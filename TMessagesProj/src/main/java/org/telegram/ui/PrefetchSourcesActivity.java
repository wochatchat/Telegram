/*
 * This is the source code of Telegram for Android v. 5.x.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.ui;

import android.content.Context;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;
import org.telegram.prefetch.PrefetchConfig;
import org.telegram.prefetch.PrefetchScheduler;
import org.telegram.prefetch.PrefetchSourcesLogic;
import org.telegram.prefetch.PrefetchStore;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.ActionBarMenu;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.telegram.ui.ActionBar.BackDrawable;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.CheckBoxCell;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Components.EmptyTextProgressView;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.NumberTextView;
import org.telegram.ui.Components.RecyclerListView;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 后台预下载 · 预下载源页（设计文档 §二 Tab1 / §三）。
 * <p>
 * 聊天列表（频道/超级群/普通群，私聊不进）异步加载（§八.5，后台线程构建，UI 线程应用）。
 * 点按行：勾选/取消勾选（取消时立即移除该聊天未开始任务，§3.4）；
 * 长按：行内配置 ChatPrefetchSheet（模式/单文件上限/配额/优先级/保留/深度六项，§3.2）。
 * 菜单：过滤（全部/频道/群组/已勾选）+ 排序（最近更新/已勾选优先/字母）+ 批量操作。
 * 批量操作：action mode 多选 → 全选/全不选/仅频道/反选/应用/批量设置。
 * 所有修改即时落盘并触发调度器热生效（{@link PrefetchScheduler#onConfigChanged()}）。
 */
public class PrefetchSourcesActivity extends BaseFragment {

    private static final int MENU_FILTER = 1;
    private static final int MENU_MORE = 2;

    private static final int FILTER_ID_ALL = 100;
    private static final int FILTER_ID_CHANNELS = 101;
    private static final int FILTER_ID_GROUPS = 102;
    private static final int FILTER_ID_SELECTED = 103;

    private static final int SORT_ID_RECENT = 200;
    private static final int SORT_ID_SELECTED = 201;
    private static final int SORT_ID_ALPHA = 202;

    private static final int BATCH_ID_MENU = 300;
    private static final int BATCH_ID_SELECT_ALL = 310;
    private static final int BATCH_ID_SELECT_NONE = 311;
    private static final int BATCH_ID_CHANNELS_ONLY = 312;
    private static final int BATCH_ID_INVERT = 313;
    private static final int BATCH_ID_SETTINGS = 314;
    private static final int BATCH_ID_APPLY = 315;

    private static final int SHEET_MODE = 0;
    private static final int SHEET_MAX_SIZE = 1;
    private static final int SHEET_QUOTA = 2;
    private static final int SHEET_PRIORITY = 3;
    private static final int SHEET_KEEP = 4;
    private static final int SHEET_DEPTH = 5;

    private static final int VIEW_TYPE_CHAT = 0;
    private static final int VIEW_TYPE_INFO = 1;

    private RecyclerListView listView;
    private ListAdapter listAdapter;
    private EmptyTextProgressView emptyView;
    private NumberTextView selectedCountTextView;

    private final ArrayList<PrefetchSourcesLogic.Entry> allEntries = new ArrayList<>();
    private List<PrefetchSourcesLogic.Entry> visibleEntries = new ArrayList<>();
    private boolean loaded;

    private PrefetchConfig config;
    private String query = "";
    private int filter = PrefetchSourcesLogic.FILTER_ALL;
    private int sortMode = PrefetchSourcesLogic.SORT_RECENT;

    private boolean actionModeOpen;
    private final HashSet<Long> pendingSelection = new HashSet<>();

    @Override
    public boolean onFragmentCreate() {
        super.onFragmentCreate();
        config = PrefetchStore.load(currentAccount);
        loadChats();
        return true;
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonDrawable(new BackDrawable(false));
        actionBar.setTitle(LocaleController.getString(R.string.PrefetchSources));
        actionBar.setAllowOverlayTitle(true);

        ActionBarMenu menu = actionBar.createMenu();
        ActionBarMenuItem searchItem = menu.addItem(0, R.drawable.outline_header_search)
                .setIsSearchField(true)
                .setActionBarMenuItemSearchListener(new ActionBarMenuItem.ActionBarMenuItemSearchListener() {
                    @Override
                    public void onSearchExpand() {
                    }

                    @Override
                    public void onSearchCollapse() {
                        setQuery(null);
                    }

                    @Override
                    public void onTextChanged(EditText editText) {
                        setQuery(editText.getText().toString());
                    }
                });
        searchItem.setSearchFieldHint(LocaleController.getString(R.string.Search));
        actionBar.setSearchTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText), true);
        actionBar.setSearchTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText), false);
        actionBar.setSearchCursorColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));

        ActionBarMenuItem filterItem = menu.addItem(MENU_FILTER, R.drawable.menu_tag_filter);
        filterItem.addSubItem(FILTER_ID_ALL, LocaleController.getString(R.string.PrefetchFilterAll));
        filterItem.addSubItem(FILTER_ID_CHANNELS, LocaleController.getString(R.string.PrefetchFilterChannels));
        filterItem.addSubItem(FILTER_ID_GROUPS, LocaleController.getString(R.string.PrefetchFilterGroups));
        filterItem.addSubItem(FILTER_ID_SELECTED, LocaleController.getString(R.string.PrefetchFilterSelected));

        ActionBarMenuItem moreItem = menu.addItem(MENU_MORE, R.drawable.ic_ab_other);
        moreItem.addSubItem(SORT_ID_RECENT, LocaleController.getString(R.string.PrefetchSortRecent));
        moreItem.addSubItem(SORT_ID_SELECTED, LocaleController.getString(R.string.PrefetchSortSelected));
        moreItem.addSubItem(SORT_ID_ALPHA, LocaleController.getString(R.string.PrefetchSortAlpha));
        moreItem.addSubItem(BATCH_ID_MENU, LocaleController.getString(R.string.PrefetchBatchMenu));

        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                handleMenuClick(id);
            }
        });

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
        batchItem.addSubItem(BATCH_ID_CHANNELS_ONLY, LocaleController.getString(R.string.PrefetchBatchChannelsOnly));
        batchItem.addSubItem(BATCH_ID_INVERT, LocaleController.getString(R.string.PrefetchBatchInvert));
        batchItem.addSubItem(BATCH_ID_SETTINGS, LocaleController.getString(R.string.PrefetchBatchSettings));
        batchItem.addSubItem(BATCH_ID_APPLY, LocaleController.getString(R.string.PrefetchBatchApply));

        listAdapter = new ListAdapter(context);

        emptyView = new EmptyTextProgressView(context);
        emptyView.setText(LocaleController.getString(R.string.PrefetchSourcesLoading));
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

        fragmentView = new FrameLayout(context);
        fragmentView.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));
        ((FrameLayout) fragmentView).addView(emptyView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        ((FrameLayout) fragmentView).addView(listView, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        return fragmentView;
    }

    private void refreshVisible() {
        visibleEntries = PrefetchSourcesLogic.buildVisibleList(allEntries, query, filter, sortMode);
        if (listAdapter != null) {
            listAdapter.notifyDataSetChanged();
        }
        updateEmptyView();
    }

    // ------------------------------------------------------------------
    // 行交互
    // ------------------------------------------------------------------

    private void onItemClicked(int position) {
        if (position < 0 || position >= visibleEntries.size()) {
            return;
        }
        PrefetchSourcesLogic.Entry e = visibleEntries.get(position);
        if (actionModeOpen) {
            if (pendingSelection.contains(e.dialogId)) {
                pendingSelection.remove(e.dialogId);
            } else {
                pendingSelection.add(e.dialogId);
            }
            updateSelectedCount();
            listAdapter.notifyItemChanged(position);
            return;
        }
        toggleWhitelist(e);
    }

    private void onItemLongPressed(int position) {
        if (position < 0 || position >= visibleEntries.size()) {
            return;
        }
        showChatSheet(visibleEntries.get(position).dialogId, null);
    }

    /** 勾选/取消勾选单个聊天（取消时立即移除该聊天未开始任务，§3.4）。 */
    private void toggleWhitelist(PrefetchSourcesLogic.Entry e) {
        if (config.sources.containsKey(e.dialogId)) {
            config.sources.remove(e.dialogId);
            e.whitelisted = false;
            PrefetchScheduler.getInstance(currentAccount).removeChatTasks(e.dialogId);
        } else {
            config.sources.put(e.dialogId, new PrefetchConfig.ChatPrefetchSource());
            e.whitelisted = true;
        }
        saveAndApply();
        refreshVisible();
    }

    private void saveAndApply() {
        PrefetchStore.save(currentAccount, config);
        PrefetchScheduler.getInstance(currentAccount).onConfigChanged();
    }

    // ------------------------------------------------------------------
    // 批量操作
    // ------------------------------------------------------------------

    private void enterActionMode() {
        if (actionModeOpen) {
            return;
        }
        actionModeOpen = true;
        pendingSelection.clear();
        for (PrefetchSourcesLogic.Entry e : allEntries) {
            if (e.whitelisted) {
                pendingSelection.add(e.dialogId);
            }
        }
        actionBar.showActionMode();
        updateSelectedCount();
        refreshVisible();
    }

    private void exitActionMode() {
        actionModeOpen = false;
        pendingSelection.clear();
        actionBar.hideActionMode();
        refreshVisible();
    }

    /** 批量选择操作：算出新集合后原位替换 pending，再统一刷新。 */
    private void applyBatchOp(int op) {
        HashSet<Long> next = PrefetchSourcesLogic.applyBatch(pendingSelection, visibleEntries, op);
        pendingSelection.clear();
        pendingSelection.addAll(next);
        updateSelectedCount();
        refreshVisible();
    }

    private void updateSelectedCount() {
        if (selectedCountTextView != null) {
            selectedCountTextView.setNumber(pendingSelection.size(), true);
        }
    }

    /** 提交批量选择：白名单 := pending；被移除的聊天立即取消未开始任务（§3.4）。 */
    private void commitBatch() {
        List<Long> removed = PrefetchSourcesLogic.removedChatIds(config.sources, pendingSelection);
        for (Long id : removed) {
            PrefetchScheduler.getInstance(currentAccount).removeChatTasks(id);
        }
        config.sources.keySet().retainAll(pendingSelection);
        for (Long id : pendingSelection) {
            if (!config.sources.containsKey(id)) {
                config.sources.put(id, new PrefetchConfig.ChatPrefetchSource());
            }
        }
        for (PrefetchSourcesLogic.Entry e : allEntries) {
            e.whitelisted = pendingSelection.contains(e.dialogId);
        }
        saveAndApply();
        exitActionMode();
    }

    // ------------------------------------------------------------------
    // 菜单事件（主菜单 + action mode 子菜单共用一条分发路径）
    // ------------------------------------------------------------------

    private void handleMenuClick(int id) {
        switch (id) {
            case FILTER_ID_ALL:
            case FILTER_ID_CHANNELS:
            case FILTER_ID_GROUPS:
            case FILTER_ID_SELECTED:
                filter = id - FILTER_ID_ALL;
                refreshVisible();
                break;
            case SORT_ID_RECENT:
            case SORT_ID_SELECTED:
            case SORT_ID_ALPHA:
                sortMode = id - SORT_ID_RECENT;
                refreshVisible();
                break;
            case BATCH_ID_MENU:
                enterActionMode();
                break;
            case BATCH_ID_SELECT_ALL:
            case BATCH_ID_SELECT_NONE:
            case BATCH_ID_CHANNELS_ONLY:
            case BATCH_ID_INVERT:
                applyBatchOp(batchOpFromId(id));
                break;
            case BATCH_ID_SETTINGS:
                if (!pendingSelection.isEmpty()) {
                    showChatSheet(0, pendingSelection);
                }
                break;
            case BATCH_ID_APPLY:
                commitBatch();
                break;
        }
    }

    private static int batchOpFromId(int id) {
        switch (id) {
            case BATCH_ID_SELECT_NONE:
                return PrefetchSourcesLogic.BATCH_SELECT_NONE;
            case BATCH_ID_CHANNELS_ONLY:
                return PrefetchSourcesLogic.BATCH_CHANNELS_ONLY;
            case BATCH_ID_INVERT:
                return PrefetchSourcesLogic.BATCH_INVERT;
            case BATCH_ID_SELECT_ALL:
            default:
                return PrefetchSourcesLogic.BATCH_SELECT_ALL;
        }
    }

    // ------------------------------------------------------------------
    // 数据加载（后台构建，UI 应用，§八.5）
    // ------------------------------------------------------------------

    private void loadChats() {
        final MessagesController mc = MessagesController.getInstance(currentAccount);
        Utilities.globalQueue.postRunnable(() -> {
            ArrayList<PrefetchSourcesLogic.Entry> entries = new ArrayList<>();
            try {
                ArrayList<TLRPC.Dialog> dialogs = mc.getAllDialogs();
                for (TLRPC.Dialog d : dialogs) {
                    if (d == null || d.isFolder || d.id >= 0) {
                        continue; // 私聊/用户不进列表（§三）
                    }
                    TLRPC.Chat chat = mc.getChat(-d.id);
                    if (chat == null || chat.title == null || chat.left || chat.kicked || chat.deactivated) {
                        continue;
                    }
                    PrefetchSourcesLogic.Entry e = new PrefetchSourcesLogic.Entry();
                    e.dialogId = d.id;
                    e.title = chat.title;
                    e.kind = kindOf(chat);
                    e.lastDate = d.last_message_date;
                    e.queryKey = chat.title.toLowerCase(Locale.US);
                    e.whitelisted = config.sources.containsKey(d.id);
                    entries.add(e);
                }
            } catch (Exception e) {
                FileLog.e(e);
            }
            AndroidUtilities.runOnUIThread(() -> {
                allEntries.clear();
                allEntries.addAll(entries);
                loaded = true;
                refreshVisible();
            });
        });
    }

    private static int kindOf(TLRPC.Chat chat) {
        if (chat.broadcast && !chat.megagroup) {
            return PrefetchSourcesLogic.KIND_CHANNEL;
        }
        if (chat.megagroup || chat.gigagroup) {
            return PrefetchSourcesLogic.KIND_SUPERGROUP;
        }
        return PrefetchSourcesLogic.KIND_GROUP;
    }

    private void updateEmptyView() {
        if (emptyView == null) {
            return;
        }
        emptyView.setText(loaded
                ? LocaleController.getString(R.string.PrefetchSourcesEmpty)
                : LocaleController.getString(R.string.PrefetchSourcesLoading));
    }

    private void setQuery(String text) {
        query = TextUtils.isEmpty(text) ? "" : text;
        refreshVisible();
    }

    // ------------------------------------------------------------------
    // ChatPrefetchSheet：单聊天 / 批量 六项行内配置（§3.2）
    // ------------------------------------------------------------------

    /**
     * @param batchTargets null = 单聊天（dialogId）；非 null = 批量应用到集合
     */
    private void showChatSheet(long dialogId, Set<Long> batchTargets) {
        String title;
        if (batchTargets != null) {
            title = String.format(Locale.US, "%d chats", batchTargets.size());
        } else {
            PrefetchSourcesLogic.Entry e = findEntry(dialogId);
            title = e != null ? e.title : "";
        }
        CharSequence[] items = {
                LocaleController.getString(R.string.PrefetchMode),
                LocaleController.getString(R.string.PrefetchMaxSize),
                LocaleController.getString(R.string.PrefetchQuota),
                LocaleController.getString(R.string.PrefetchPriority),
                LocaleController.getString(R.string.PrefetchKeep),
                LocaleController.getString(R.string.PrefetchDepth),
        };
        BottomSheet.Builder builder = new BottomSheet.Builder(getContext());
        builder.setTitle(title);
        builder.setItems(items, (d, which) -> showFieldPicker(which, batchTargets != null ? batchTargets : java.util.Collections.singleton(dialogId)));
        showDialog(builder.create());
    }

    private PrefetchSourcesLogic.Entry findEntry(long dialogId) {
        for (PrefetchSourcesLogic.Entry e : allEntries) {
            if (e.dialogId == dialogId) {
                return e;
            }
        }
        return null;
    }

    private PrefetchConfig.ChatPrefetchSource getOrCreateSource(long dialogId) {
        PrefetchConfig.ChatPrefetchSource src = config.sources.get(dialogId);
        if (src == null) {
            src = new PrefetchConfig.ChatPrefetchSource();
            config.sources.put(dialogId, src);
        }
        return src;
    }

    /** 应用所选档位到目标聊天（单聊或批量）；未勾选的聊天顺带加入白名单。 */
    private void applyFieldToTargets(int field, int which, Set<Long> targets) {
        for (Long id : targets) {
            PrefetchConfig.ChatPrefetchSource src = getOrCreateSource(id);
            switch (field) {
                case SHEET_MODE:
                    src.mode = which;
                    break;
                case SHEET_MAX_SIZE:
                    src.maxSize = PrefetchConfig.CHAT_MAX_SIZE_PRESETS[which];
                    break;
                case SHEET_QUOTA:
                    src.quotaPct = PrefetchConfig.clampQuotaPct(PrefetchConfig.QUOTA_PRESETS[which]);
                    break;
                case SHEET_PRIORITY:
                    src.priority = which;
                    break;
                case SHEET_KEEP:
                    src.keepDays = PrefetchConfig.KEEP_PRESETS[which];
                    break;
                case SHEET_DEPTH:
                default:
                    src.depthDays = PrefetchConfig.DEPTH_PRESETS[which];
                    break;
            }
        }
        saveAndApply();
        refreshVisible();
    }

    /** 六项配置的二级档位选择器。 */
    private void showFieldPicker(int field, Set<Long> targets) {
        CharSequence[] items;
        switch (field) {
            case SHEET_MODE:
                items = new CharSequence[]{
                        LocaleController.getString(R.string.PrefetchModeAll),
                        LocaleController.getString(R.string.PrefetchModeSmall),
                        LocaleController.getString(R.string.PrefetchModeManual),
                };
                break;
            case SHEET_MAX_SIZE:
                items = new CharSequence[PrefetchConfig.CHAT_MAX_SIZE_PRESETS.length];
                for (int i = 0; i < items.length; i++) {
                    long v = PrefetchConfig.CHAT_MAX_SIZE_PRESETS[i];
                    items[i] = v == 0
                            ? LocaleController.getString(R.string.PrefetchFollowGlobal)
                            : PrefetchConfig.formatBytes(v);
                }
                break;
            case SHEET_QUOTA:
                items = new CharSequence[PrefetchConfig.QUOTA_PRESETS.length];
                for (int i = 0; i < items.length; i++) {
                    items[i] = String.format(Locale.US, LocaleController.getString(R.string.PrefetchQuotaValue), PrefetchConfig.QUOTA_PRESETS[i]);
                }
                break;
            case SHEET_PRIORITY:
                items = new CharSequence[]{
                        LocaleController.getString(R.string.PrefetchPriorityLow),
                        LocaleController.getString(R.string.PrefetchPriorityNormal),
                        LocaleController.getString(R.string.PrefetchPriorityHigh),
                };
                break;
            case SHEET_KEEP:
                items = new CharSequence[PrefetchConfig.KEEP_PRESETS.length];
                for (int i = 0; i < items.length; i++) {
                    int v = PrefetchConfig.KEEP_PRESETS[i];
                    if (v == PrefetchConfig.KEEP_FOLLOW_GLOBAL) {
                        items[i] = LocaleController.getString(R.string.PrefetchFollowGlobal);
                    } else if (v == PrefetchConfig.KEEP_FOREVER) {
                        items[i] = LocaleController.getString(R.string.PrefetchKeepForever);
                    } else {
                        items[i] = String.format(Locale.US, LocaleController.getString(R.string.PrefetchKeepDays), v);
                    }
                }
                break;
            case SHEET_DEPTH:
            default:
                items = new CharSequence[PrefetchConfig.DEPTH_PRESETS.length];
                for (int i = 0; i < items.length; i++) {
                    int v = PrefetchConfig.DEPTH_PRESETS[i];
                    items[i] = v == 0
                            ? LocaleController.getString(R.string.PrefetchFollowGlobal)
                            : String.format(Locale.US, LocaleController.getString(R.string.PrefetchKeepDays), v);
                }
                break;
        }
        BottomSheet.Builder builder = new BottomSheet.Builder(getContext());
        builder.setTitle(fieldTitle(field));
        builder.setItems(items, (d, which) -> applyFieldToTargets(field, which, targets));
        showDialog(builder.create());
    }

    private String fieldTitle(int field) {
        switch (field) {
            case SHEET_MODE:
                return LocaleController.getString(R.string.PrefetchMode);
            case SHEET_MAX_SIZE:
                return LocaleController.getString(R.string.PrefetchMaxSize);
            case SHEET_QUOTA:
                return LocaleController.getString(R.string.PrefetchQuota);
            case SHEET_PRIORITY:
                return LocaleController.getString(R.string.PrefetchPriority);
            case SHEET_KEEP:
                return LocaleController.getString(R.string.PrefetchKeep);
            case SHEET_DEPTH:
            default:
                return LocaleController.getString(R.string.PrefetchDepth);
        }
    }

    /** 行 value：白名单聊天显示模式摘要，未勾选为空。 */
    private String valueFor(PrefetchSourcesLogic.Entry e) {
        if (actionModeOpen) {
            return "";
        }
        PrefetchConfig.ChatPrefetchSource src = config.sources.get(e.dialogId);
        if (src == null) {
            return "";
        }
        String v;
        switch (src.mode) {
            case PrefetchConfig.MODE_SMALL_ONLY:
                v = LocaleController.getString(R.string.PrefetchModeSmall);
                break;
            case PrefetchConfig.MODE_MANUAL:
                v = LocaleController.getString(R.string.PrefetchModeManual);
                break;
            default:
                v = LocaleController.getString(R.string.PrefetchModeAll);
                break;
        }
        if (src.keepDays == PrefetchConfig.KEEP_FOREVER) {
            v += " · ∞";
        } else if (src.maxSize > 0) {
            v += " · ≤" + PrefetchConfig.formatBytes(src.maxSize);
        }
        return v;
    }

    // ------------------------------------------------------------------
    // Adapter
    // ------------------------------------------------------------------

    private class ListAdapter extends RecyclerListView.SelectionAdapter {

        private final Context context;

        ListAdapter(Context context) {
            this.context = context;
        }

        @Override
        public int getItemCount() {
            return visibleEntries.size() + (loaded ? 1 : 0);
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            return holder.getItemViewType() == VIEW_TYPE_CHAT;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view;
            switch (viewType) {
                case VIEW_TYPE_CHAT:
                    view = new CheckBoxCell(context, 1);
                    break;
                case VIEW_TYPE_INFO:
                default:
                    TextInfoPrivacyCell infoCell = new TextInfoPrivacyCell(context);
                    infoCell.setBackgroundDrawable(Theme.getThemedDrawable(context, R.drawable.greydivider_bottom, Theme.getColor(Theme.key_windowBackgroundGrayShadow, getResourceProvider())));
                    view = infoCell;
                    break;
            }
            view.setLayoutParams(new RecyclerView.LayoutParams(RecyclerView.LayoutParams.MATCH_PARENT, RecyclerView.LayoutParams.WRAP_CONTENT));
            return new RecyclerListView.Holder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            switch (holder.getItemViewType()) {
                case VIEW_TYPE_CHAT: {
                    CheckBoxCell cell = (CheckBoxCell) holder.itemView;
                    PrefetchSourcesLogic.Entry e = visibleEntries.get(position);
                    boolean checked = actionModeOpen
                            ? pendingSelection.contains(e.dialogId)
                            : e.whitelisted;
                    boolean divider = position != visibleEntries.size() - 1 || !loaded;
                    cell.setText(e.title, valueFor(e), checked, divider);
                    break;
                }
                case VIEW_TYPE_INFO:
                default: {
                    TextInfoPrivacyCell cell = (TextInfoPrivacyCell) holder.itemView;
                    cell.setText(LocaleController.getString(R.string.PrefetchSourcesInfo));
                    break;
                }
            }
        }

        @Override
        public int getItemViewType(int position) {
            if (position < visibleEntries.size()) {
                return VIEW_TYPE_CHAT;
            }
            return VIEW_TYPE_INFO;
        }
    }
}

