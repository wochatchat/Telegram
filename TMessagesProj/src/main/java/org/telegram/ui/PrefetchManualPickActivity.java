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
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserObject;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BackDrawable;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.TextCell;
import org.telegram.ui.Components.EmptyTextProgressView;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;

/**
 * 手动选消息 · 第一步：选择聊天（设计文档 §3.3）。
 * <p>
 * 列出所有会话（含私聊；手动选消息不受 Tab1"频道+群"白名单限制），
 * 点击进入 {@link PrefetchVideoPickerActivity} 按日期分组视频网格。
 */
public class PrefetchManualPickActivity extends BaseFragment {

    /** 会话条目（排序字段 lastDate） */
    private static final class Entry {
        long dialogId;
        String title;
        long lastDate;
    }

    private RecyclerListView listView;
    private ListAdapter listAdapter;
    private EmptyTextProgressView emptyView;

    private final ArrayList<Entry> entries = new ArrayList<>();
    private boolean loaded;

    @Override
    public boolean onFragmentCreate() {
        super.onFragmentCreate();
        loadChats();
        return true;
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonDrawable(new BackDrawable(false));
        actionBar.setTitle(LocaleController.getString(R.string.PrefetchPickChatTitle));
        actionBar.setAllowOverlayTitle(true);

        emptyView = new EmptyTextProgressView(context);
        emptyView.setText(LocaleController.getString(R.string.PrefetchPickLoading));
        emptyView.showTextView();

        listView = new RecyclerListView(context);
        listView.setLayoutManager(new LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false));
        listView.setVerticalScrollBarEnabled(false);
        listAdapter = new ListAdapter(context);
        listView.setAdapter(listAdapter);
        listView.setEmptyView(emptyView);
        listView.setOnItemClickListener((view, position) -> {
            if (position < 0 || position >= entries.size()) {
                return;
            }
            presentFragment(new PrefetchVideoPickerActivity(entries.get(position).dialogId));
        });

        fragmentView = new android.widget.FrameLayout(context);
        fragmentView.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));
        ((android.widget.FrameLayout) fragmentView).addView(listView, new RecyclerView.LayoutParams(RecyclerView.LayoutParams.MATCH_PARENT, RecyclerView.LayoutParams.MATCH_PARENT));

        return fragmentView;
    }

    private void loadChats() {
        final MessagesController mc = MessagesController.getInstance(currentAccount);
        Utilities.globalQueue.postRunnable(() -> {
            ArrayList<Entry> result = new ArrayList<>();
            try {
                ArrayList<TLRPC.Dialog> dialogs = mc.getAllDialogs();
                for (int i = 0; i < dialogs.size(); i++) {
                    TLRPC.Dialog d = dialogs.get(i);
                    if (d == null || d.isFolder) {
                        continue;
                    }
                    if (d.id >= 0) {
                        TLRPC.User user = mc.getUser(d.id);
                        if (user == null || user.deleted || user.bot) {
                            continue;
                        }
                        Entry e = new Entry();
                        e.dialogId = d.id;
                        e.title = UserObject.getUserName(user);
                        e.lastDate = d.last_message_date;
                        result.add(e);
                    } else {
                        TLRPC.Chat chat = mc.getChat(-d.id);
                        if (chat == null || chat.title == null || chat.left || chat.kicked || chat.deactivated) {
                            continue;
                        }
                        Entry e = new Entry();
                        e.dialogId = d.id;
                        e.title = chat.title;
                        e.lastDate = d.last_message_date;
                        result.add(e);
                    }
                }
            } catch (Exception e) {
                FileLog.e(e);
            }
            Collections.sort(result, (a, b) -> Long.compare(b.lastDate, a.lastDate));
            AndroidUtilities.runOnUIThread(() -> {
                entries.clear();
                entries.addAll(result);
                loaded = true;
                if (emptyView != null) {
                    emptyView.setText(LocaleController.getString(R.string.PrefetchPickChatTitle));
                    emptyView.showTextView();
                }
                if (listAdapter != null) {
                    listAdapter.notifyDataSetChanged();
                }
            });
        });
    }

    private class ListAdapter extends RecyclerListView.SelectionAdapter {

        private final Context context;

        ListAdapter(Context context) {
            this.context = context;
        }

        @Override
        public int getItemCount() {
            return entries.size();
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            return true;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            TextCell cell = new TextCell(context);
            cell.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
            cell.setLayoutParams(new RecyclerView.LayoutParams(RecyclerView.LayoutParams.MATCH_PARENT, RecyclerView.LayoutParams.WRAP_CONTENT));
            return new RecyclerListView.Holder(cell);
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            TextCell cell = (TextCell) holder.itemView;
            Entry e = entries.get(position);
            cell.setTextAndValue(e.title, "", position != entries.size() - 1);
        }
    }
}
