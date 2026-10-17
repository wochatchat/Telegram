/*
 * This is the source code of Telegram for Android v. 5.x.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.ui;

import android.content.Context;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.DownloadController;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.ImageLocation;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.R;
import org.telegram.prefetch.PrefetchPickerLogic;
import org.telegram.tgnet.NativeByteBuffer;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BackDrawable;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.CheckBox2;
import org.telegram.ui.Components.EmptyTextProgressView;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;

/**
 * 手动选消息 · 第二步：视频网格（设计文档 §3.3）。
 * <p>
 * 按日期分组的视频网格（3 列），多选后一次性入队下载：
 * 走 {@link FileLoader} 常规路径（prefetch=false = 手动任务），
 * 不参与自动预取调度；入队后返回，任务在队列页可见（手动过滤 chip）。
 */
public class PrefetchVideoPickerActivity extends BaseFragment {

    private static final int VIEW_TYPE_HEADER = 0;
    private static final int VIEW_TYPE_VIDEO = 1;
    private static final int COLUMNS = 3;
    private static final int MAX_VIDEOS = 500;

    private RecyclerListView listView;
    private ListAdapter listAdapter;
    private EmptyTextProgressView emptyView;
    private TextView downloadButton;

    /** 展示行：String（日期 header）或 MessageObject（视频） */
    private final ArrayList<Object> rows = new ArrayList<>();
    private final ArrayList<MessageObject> videos = new ArrayList<>();
    private final HashSet<Long> selected = new HashSet<>();
    private final HashMap<Long, String> dayLabels = new HashMap<>();

    private final long dialogId;

    public PrefetchVideoPickerActivity(long dialogId) {
        this.dialogId = dialogId;
    }

    @Override
    public boolean onFragmentCreate() {
        super.onFragmentCreate();
        loadVideos();
        return true;
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonDrawable(new BackDrawable(false));
        actionBar.setTitle(LocaleController.getString(R.string.PrefetchManualPicker));
        actionBar.setAllowOverlayTitle(true);

        emptyView = new EmptyTextProgressView(context);
        emptyView.setText(LocaleController.getString(R.string.PrefetchPickEmptyChat));
        emptyView.showTextView();

        listAdapter = new ListAdapter(context);

        GridLayoutManager grid = new GridLayoutManager(context, COLUMNS);
        grid.setSpanSizeLookup(new GridLayoutManager.SpanSizeLookup() {
            @Override
            public int getSpanSize(int position) {
                return position < rows.size() && rows.get(position) instanceof String ? COLUMNS : 1;
            }
        });

        listView = new RecyclerListView(context);
        listView.setLayoutManager(grid);
        listView.setVerticalScrollBarEnabled(false);
        listView.setAdapter(listAdapter);
        listView.setEmptyView(emptyView);
        listView.setPadding(0, 0, 0, AndroidUtilities.dp(56));
        listView.setClipToPadding(false);
        listView.setOnItemClickListener((view, position) -> {
            if (position < 0 || position >= rows.size() || !(rows.get(position) instanceof MessageObject)) {
                return;
            }
            MessageObject mo = (MessageObject) rows.get(position);
            PrefetchPickerLogic.toggle(selected, mo.getId());
            listAdapter.notifyItemChanged(position);
            updateDownloadButton();
        });

        FrameLayout root = new FrameLayout(context);
        root.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        root.addView(listView, new RecyclerView.LayoutParams(RecyclerView.LayoutParams.MATCH_PARENT, RecyclerView.LayoutParams.MATCH_PARENT));

        // 底部一次性下载按钮（有选中才显示）
        downloadButton = new TextView(context);
        downloadButton.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        downloadButton.setTypeface(AndroidUtilities.bold());
        downloadButton.setTextColor(Theme.getColor(Theme.key_featuredStickers_buttonText));
        downloadButton.setPadding(AndroidUtilities.dp(24), AndroidUtilities.dp(12), AndroidUtilities.dp(24), AndroidUtilities.dp(12));
        downloadButton.setBackgroundDrawable(Theme.createSimpleSelectorRoundRectDrawable(AndroidUtilities.dp(24),
                Theme.getColor(Theme.key_featuredStickers_addButton), Theme.getColor(Theme.key_featuredStickers_addButtonPressed)));
        downloadButton.setOnClickListener(v -> commitSelection());
        downloadButton.setVisibility(View.INVISIBLE);
        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        blp.bottomMargin = AndroidUtilities.dp(8);
        root.addView(downloadButton, blp);

        updateDownloadButton();
        return root;
    }

    // ------------------------------------------------------------------
    // 视频装载（沿用 media_v4 直查，type=0 含视频，R2 落点备忘）
    // ------------------------------------------------------------------

    private void loadVideos() {
        final MessagesStorage storage = MessagesStorage.getInstance(currentAccount);
        final String query = String.format(Locale.US,
                "SELECT m.data, m.mid FROM media_v4 m WHERE m.uid = %d AND m.type = 0 AND m.mid > 0 ORDER BY m.date DESC, m.mid DESC LIMIT %d",
                dialogId, MAX_VIDEOS);
        storage.getStorageQueue().postRunnable(() -> {
            ArrayList<TLRPC.Message> messages = new ArrayList<>();
            try {
                org.telegram.SQLite.SQLiteCursor cursor = storage.getDatabase().queryFinalized(query);
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
            AndroidUtilities.runOnUIThread(() -> {
                buildRows(messages);
                if (listAdapter != null) {
                    listAdapter.notifyDataSetChanged();
                }
                updateDownloadButton();
            });
        });
    }

    private void buildRows(ArrayList<TLRPC.Message> messages) {
        rows.clear();
        videos.clear();
        selected.clear();
        java.util.Calendar cal = java.util.Calendar.getInstance();
        SimpleDateFormat fmt = new SimpleDateFormat("d MMMM yyyy", Locale.getDefault());
        long lastDay = -1;
        HashSet<Long> seenDocIds = new HashSet<>();
        for (int i = 0; i < messages.size(); i++) {
            TLRPC.Message msg = messages.get(i);
            if (msg == null || msg.media == null || msg.media.document == null) {
                continue;
            }
            if (!MessageObject.isVideoDocument(msg.media.document)) {
                continue;
            }
            if (!seenDocIds.add(msg.media.document.id)) {
                continue;
            }
            MessageObject mo = new MessageObject(currentAccount, msg, false, false);
            videos.add(mo);
            long dayKey = PrefetchPickerLogic.dayKeyOf(msg.date, cal);
            if (dayKey != lastDay) {
                String label = dayLabels.get(dayKey);
                if (label == null) {
                    label = fmt.format(new Date(msg.date * 1000L));
                    dayLabels.put(dayKey, label);
                }
                rows.add(label);
                lastDay = dayKey;
            }
            rows.add(mo);
        }
    }

    // ------------------------------------------------------------------
    // 下载提交（一次性任务，§3.3）
    // ------------------------------------------------------------------

    private void commitSelection() {
        if (selected.isEmpty()) {
            return;
        }
        FileLoader fl = FileLoader.getInstance(currentAccount);
        for (int i = 0; i < videos.size(); i++) {
            MessageObject mo = videos.get(i);
            if (!selected.contains(mo.getId()) || mo.getDocument() == null) {
                continue;
            }
            // 已在本地或已在队列的消息跳过
            File path = fl.getPathToMessage(mo.messageOwner);
            if (path != null && path.exists()) {
                continue;
            }
            if (DownloadController.getInstance(currentAccount).downloadingFiles.contains(mo)) {
                continue;
            }
            fl.loadFile(mo.getDocument(), mo, FileLoader.PRIORITY_NORMAL, 0);
        }
        finishFragment();
    }

    private void updateDownloadButton() {
        if (downloadButton == null) {
            return;
        }
        int n = selected.size();
        if (n == 0) {
            downloadButton.setVisibility(View.INVISIBLE);
        } else {
            downloadButton.setVisibility(View.VISIBLE);
            if (n == 1) {
                downloadButton.setText(LocaleController.getString(R.string.PrefetchPickDownloadOne));
            } else {
                downloadButton.setText(String.format(Locale.US, LocaleController.getString(R.string.PrefetchPickDownload), n));
            }
        }
    }

    // ------------------------------------------------------------------
    // Adapter + 网格 cell
    // ------------------------------------------------------------------

    private class ListAdapter extends RecyclerListView.SelectionAdapter {

        private final Context context;

        ListAdapter(Context context) {
            this.context = context;
        }

        @Override
        public int getItemCount() {
            return rows.size();
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            return holder.getItemViewType() == VIEW_TYPE_VIDEO;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view;
            if (viewType == VIEW_TYPE_HEADER) {
                TextView header = new TextView(context);
                header.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
                header.setTypeface(AndroidUtilities.bold());
                header.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2));
                header.setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(12), AndroidUtilities.dp(16), AndroidUtilities.dp(6));
                view = header;
                view.setLayoutParams(new RecyclerView.LayoutParams(RecyclerView.LayoutParams.MATCH_PARENT, RecyclerView.LayoutParams.WRAP_CONTENT));
            } else {
                int cellSize = AndroidUtilities.displaySize.x / COLUMNS;
                view = new VideoCell(context, cellSize);
                view.setLayoutParams(new RecyclerView.LayoutParams(RecyclerView.LayoutParams.MATCH_PARENT, cellSize));
            }
            return new RecyclerListView.Holder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            Object row = rows.get(position);
            if (holder.getItemViewType() == VIEW_TYPE_HEADER) {
                ((TextView) holder.itemView).setText((String) row);
            } else {
                ((VideoCell) holder.itemView).setItem((MessageObject) row, selected.contains(((MessageObject) row).getId()));
            }
        }

        @Override
        public int getItemViewType(int position) {
            return rows.get(position) instanceof String ? VIEW_TYPE_HEADER : VIEW_TYPE_VIDEO;
        }
    }

    /** 视频网格 cell：缩略图 + 时长角标 + 选中勾选框 */
    private class VideoCell extends FrameLayout {

        private final BackupImageView imageView;
        private final TextView durationTextView;
        private final View dimView;
        private final CheckBox2 checkBox;

        VideoCell(Context context, int cellSize) {
            super(context);
            imageView = new BackupImageView(context);
            addView(imageView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

            dimView = new View(context);
            dimView.setBackgroundColor(0x54000000);
            dimView.setVisibility(INVISIBLE);
            addView(dimView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

            durationTextView = new TextView(context);
            durationTextView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
            durationTextView.setTextColor(0xffffffff);
            durationTextView.setTypeface(AndroidUtilities.bold());
            durationTextView.setSingleLine(true);
            addView(durationTextView, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.BOTTOM | Gravity.LEFT, 6, 0, 0, 5));

            checkBox = new CheckBox2(context, 21);
            checkBox.setVisibility(INVISIBLE);
            checkBox.setColor(-1, Theme.key_sharedMedia_photoPlaceholder, Theme.key_checkboxCheck);
            checkBox.setDrawUnchecked(false);
            checkBox.setDrawBackgroundAsArc(1);
            addView(checkBox, LayoutHelper.createFrame(24, 24, Gravity.TOP | Gravity.RIGHT, 0, 1, 1, 0));

            setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, cellSize));
        }

        void setItem(MessageObject mo, boolean checked) {
            TLRPC.Document document = mo.getDocument();
            TLRPC.PhotoSize thumb = FileLoader.getClosestPhotoSizeWithSize(document.thumbs, 50);
            TLRPC.PhotoSize qualityThumb = FileLoader.getClosestPhotoSizeWithSize(document.thumbs, 320);
            if (thumb == qualityThumb) {
                qualityThumb = null;
            }
            if (thumb != null) {
                if (mo.strippedThumb != null) {
                    imageView.setImage(ImageLocation.getForDocument(qualityThumb, document), "100_100", null, mo.strippedThumb, mo);
                } else {
                    imageView.setImage(ImageLocation.getForDocument(qualityThumb, document), "100_100", ImageLocation.getForDocument(thumb, document), "b", getContext().getResources().getDrawable(R.drawable.photo_placeholder_in), null, null, 0, mo);
                }
            } else {
                imageView.setImageResource(R.drawable.photo_placeholder_in);
            }
            durationTextView.setText(AndroidUtilities.formatShortDuration((int) mo.getDuration()));
            if (checked) {
                dimView.setVisibility(VISIBLE);
                checkBox.setVisibility(VISIBLE);
            } else {
                dimView.setVisibility(INVISIBLE);
                checkBox.setVisibility(INVISIBLE);
            }
            checkBox.setChecked(checked, false);
        }
    }
}
