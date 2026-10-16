/*
 * This is the source code of Telegram for Android v. 5.x.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.ui;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.prefetch.PrefetchConfig;
import org.telegram.prefetch.PrefetchScheduler;
import org.telegram.prefetch.PrefetchStore;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.ActionBar.BackDrawable;
import org.telegram.ui.Cells.HeaderCell;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Cells.TextSettingsCell;
import org.telegram.ui.Components.RecyclerListView;

import java.util.Locale;

/**
 * 后台预下载 · 流量控制页（设计文档 §二 Tab3 / §五 参数项）。
 * <p>
 * 涵盖总开关、并发/保留槽、限速、网络三开关、蜂窝小视频例外、允许时段、
 * 深夜囤货、电量门槛、月度限额。所有修改即时落盘并触发调度器热生效
 * （{@link PrefetchScheduler#onConfigChanged()}，§六.3 参数直读）。
 * 预下载源（Tab1）与队列（Tab2）在后续轮次接入。
 */
public class PrefetchSettingsActivity extends BaseFragment {

    private RecyclerListView listView;
    private ListAdapter listAdapter;

    private int generalHeaderRow;
    private int toggleRow;
    private int concurrencyRow;
    private int reservedSlotsRow;
    private int speedLimitRow;
    private int networkHeaderRow;
    private int wifiRow;
    private int cellularRow;
    private int roamingRow;
    private int cellSmallAllowedRow;
    private int cellSmallSizeRow;
    private int scheduleHeaderRow;
    private int hoursRow;
    private int nightHoardingRow;
    private int batteryRow;
    private int monthlyHeaderRow;
    private int monthlyLimitRow;
    private int monthlyCellOnlyRow;
    private int monthUsedRow;
    private int infoRow;
    private int rowCount;

    private PrefetchConfig config;

    private static final int VIEW_TYPE_HEADER = 0;
    private static final int VIEW_TYPE_CHECK = 1;
    private static final int VIEW_TYPE_SETTINGS = 2;
    private static final int VIEW_TYPE_INFO = 3;

    @Override
    public boolean onFragmentCreate() {
        super.onFragmentCreate();
        config = PrefetchStore.load(currentAccount);
        updateRows();
        return true;
    }

    private void updateRows() {
        rowCount = 0;
        generalHeaderRow = rowCount++;
        toggleRow = rowCount++;
        concurrencyRow = rowCount++;
        reservedSlotsRow = rowCount++;
        speedLimitRow = rowCount++;
        networkHeaderRow = rowCount++;
        wifiRow = rowCount++;
        cellularRow = rowCount++;
        roamingRow = rowCount++;
        cellSmallAllowedRow = rowCount++;
        cellSmallSizeRow = rowCount++;
        scheduleHeaderRow = rowCount++;
        hoursRow = rowCount++;
        nightHoardingRow = rowCount++;
        batteryRow = rowCount++;
        monthlyHeaderRow = rowCount++;
        monthlyLimitRow = rowCount++;
        monthlyCellOnlyRow = rowCount++;
        monthUsedRow = rowCount++;
        infoRow = rowCount++;
        if (listAdapter != null) {
            listAdapter.notifyDataSetChanged();
        }
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonDrawable(new BackDrawable(false));
        actionBar.setTitle(LocaleController.getString(R.string.BackgroundPrefetch));
        actionBar.setAllowOverlayTitle(true);

        listAdapter = new ListAdapter(context);

        listView = new RecyclerListView(context);
        listView.setLayoutManager(new LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false));
        listView.setVerticalScrollBarEnabled(false);
        listView.setAdapter(listAdapter);
        listView.setOnItemClickListener((view, position, x, y) -> {
            if (position == toggleRow) {
                config.enabled = !config.enabled;
                saveAndApply();
                listAdapter.notifyItemChanged(toggleRow);
            } else if (position == concurrencyRow) {
                showConcurrencyPicker();
            } else if (position == reservedSlotsRow) {
                showReservedSlotsDialog();
            } else if (position == speedLimitRow) {
                showSpeedLimitDialog();
            } else if (position == wifiRow) {
                config.allowWifi = !config.allowWifi;
                saveAndApply();
                listAdapter.notifyItemChanged(wifiRow);
            } else if (position == cellularRow) {
                config.allowCellular = !config.allowCellular;
                saveAndApply();
                listAdapter.notifyItemChanged(cellularRow);
            } else if (position == roamingRow) {
                config.allowRoaming = !config.allowRoaming;
                saveAndApply();
                listAdapter.notifyItemChanged(roamingRow);
            } else if (position == cellSmallAllowedRow) {
                config.cellSmallVideoAllowed = !config.cellSmallVideoAllowed;
                saveAndApply();
                listAdapter.notifyItemChanged(cellSmallAllowedRow);
            } else if (position == cellSmallSizeRow) {
                showCellSmallSizeDialog();
            } else if (position == hoursRow) {
                showHoursDialog();
            } else if (position == nightHoardingRow) {
                config.nightHoarding = !config.nightHoarding;
                saveAndApply();
                listAdapter.notifyItemChanged(nightHoardingRow);
            } else if (position == batteryRow) {
                showBatteryDialog();
            } else if (position == monthlyLimitRow) {
                showMonthlyLimitDialog();
            } else if (position == monthlyCellOnlyRow) {
                config.monthlyLimitCellularOnly = !config.monthlyLimitCellularOnly;
                saveAndApply();
                listAdapter.notifyItemChanged(monthlyCellOnlyRow);
            }
        });

        fragmentView = new FrameLayout(context);
        fragmentView.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));
        ((FrameLayout) fragmentView).addView(listView, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        return fragmentView;
    }

    @Override
    public void onFragmentDestroy() {
        super.onFragmentDestroy();
        PrefetchStore.save(currentAccount, config);
    }

    // ------------------------------------------------------------------
    // 保存 + 热生效
    // ------------------------------------------------------------------

    private void saveAndApply() {
        PrefetchStore.save(currentAccount, config);
        PrefetchScheduler.getInstance(currentAccount).onConfigChanged();
    }

    // ------------------------------------------------------------------
    // 档位选择弹窗
    // ------------------------------------------------------------------

    private void showConcurrencyPicker() {
        CharSequence[] items = new CharSequence[4];
        for (int i = 0; i < 4; i++) {
            items[i] = String.valueOf(i + 1);
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(getContext());
        builder.setTitle(LocaleController.getString(R.string.PrefetchConcurrency));
        builder.setItems(items, (d, which) -> {
            config.concurrency = PrefetchConfig.clampConcurrency(which + 1);
            saveAndApply();
            listAdapter.notifyItemChanged(concurrencyRow);
        });
        showDialog(builder.create());
    }

    private void showReservedSlotsDialog() {
        CharSequence[] items = new CharSequence[3];
        for (int i = 0; i < 3; i++) {
            items[i] = String.valueOf(i);
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(getContext());
        builder.setTitle(LocaleController.getString(R.string.PrefetchReservedSlots));
        builder.setItems(items, (d, which) -> {
            config.reservedUserSlots = PrefetchConfig.clampReservedUserSlots(which);
            saveAndApply();
            listAdapter.notifyItemChanged(reservedSlotsRow);
        });
        showDialog(builder.create());
    }

    private void showSpeedLimitDialog() {
        String[] labels = {
                LocaleController.getString(R.string.PrefetchSpeedUnlimited),
                formatSpeedLabel(PrefetchConfig.SPEED_LIMIT_PRESETS[1]),
                formatSpeedLabel(PrefetchConfig.SPEED_LIMIT_PRESETS[2]),
                formatSpeedLabel(PrefetchConfig.SPEED_LIMIT_PRESETS[3]),
                formatSpeedLabel(PrefetchConfig.SPEED_LIMIT_PRESETS[4]),
                formatSpeedLabel(PrefetchConfig.SPEED_LIMIT_PRESETS[5]),
        };
        AlertDialog.Builder builder = new AlertDialog.Builder(getContext());
        builder.setTitle(LocaleController.getString(R.string.PrefetchSpeedLimit));
        builder.setItems(labels, (d, which) -> {
            config.speedLimitBytesPerSec = PrefetchConfig.SPEED_LIMIT_PRESETS[which];
            saveAndApply();
            listAdapter.notifyItemChanged(speedLimitRow);
        });
        showDialog(builder.create());
    }

    private void showHoursDialog() {
        String[] labels = {
                LocaleController.getString(R.string.PrefetchHoursAll),
                LocaleController.getString(R.string.PrefetchHoursDaytime),
                LocaleController.getString(R.string.PrefetchHoursNight),
                LocaleController.getString(R.string.PrefetchHoursEarlyMorning),
        };
        AlertDialog.Builder builder = new AlertDialog.Builder(getContext());
        builder.setTitle(LocaleController.getString(R.string.PrefetchHours));
        builder.setItems(labels, (d, which) -> {
            config.allowStartHour = PrefetchConfig.HOURS_PRESETS[which][0];
            config.allowEndHour = PrefetchConfig.HOURS_PRESETS[which][1];
            saveAndApply();
            listAdapter.notifyItemChanged(hoursRow);
        });
        showDialog(builder.create());
    }

    private void showBatteryDialog() {
        String[] labels = new String[PrefetchConfig.BATTERY_PRESETS.length];
        for (int i = 0; i < labels.length; i++) {
            labels[i] = String.format(Locale.US, "%d%%", PrefetchConfig.BATTERY_PRESETS[i]);
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(getContext());
        builder.setTitle(LocaleController.getString(R.string.PrefetchMinBattery));
        builder.setItems(labels, (d, which) -> {
            config.minBatteryPct = PrefetchConfig.clampBatteryPct(PrefetchConfig.BATTERY_PRESETS[which]);
            saveAndApply();
            listAdapter.notifyItemChanged(batteryRow);
        });
        showDialog(builder.create());
    }

    private void showCellSmallSizeDialog() {
        String[] labels = new String[PrefetchConfig.CELL_SMALL_SIZE_PRESETS.length];
        for (int i = 0; i < labels.length; i++) {
            labels[i] = PrefetchConfig.formatBytes(PrefetchConfig.CELL_SMALL_SIZE_PRESETS[i]);
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(getContext());
        builder.setTitle(LocaleController.getString(R.string.PrefetchCellSmallSize));
        builder.setItems(labels, (d, which) -> {
            config.cellSmallVideoMaxSize = PrefetchConfig.CELL_SMALL_SIZE_PRESETS[which];
            saveAndApply();
            listAdapter.notifyItemChanged(cellSmallSizeRow);
        });
        showDialog(builder.create());
    }

    private void showMonthlyLimitDialog() {
        String[] labels = new String[PrefetchConfig.MONTHLY_LIMIT_PRESETS.length];
        for (int i = 0; i < labels.length; i++) {
            if (PrefetchConfig.MONTHLY_LIMIT_PRESETS[i] < 0) {
                labels[i] = LocaleController.getString(R.string.PrefetchMonthlyUnlimited);
            } else {
                labels[i] = PrefetchConfig.formatBytes(PrefetchConfig.MONTHLY_LIMIT_PRESETS[i]);
            }
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(getContext());
        builder.setTitle(LocaleController.getString(R.string.PrefetchMonthlyLimit));
        builder.setItems(labels, (d, which) -> {
            config.monthlyCellularLimit = PrefetchConfig.MONTHLY_LIMIT_PRESETS[which];
            saveAndApply();
            listAdapter.notifyItemChanged(monthlyLimitRow);
        });
        showDialog(builder.create());
    }

    // ------------------------------------------------------------------
    // 格式化辅助
    // ------------------------------------------------------------------

    private String formatSpeedLabel(long bytesPerSec) {
        if (bytesPerSec <= 0) {
            return LocaleController.getString(R.string.PrefetchSpeedUnlimited);
        }
        return PrefetchConfig.formatSpeed(bytesPerSec);
    }

    private class ListAdapter extends RecyclerListView.SelectionAdapter {

        private final Context context;

        ListAdapter(Context context) {
            this.context = context;
        }

        @Override
        public int getItemCount() {
            return rowCount;
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            int position = holder.getAdapterPosition();
            return position == toggleRow
                    || position == concurrencyRow
                    || position == reservedSlotsRow
                    || position == speedLimitRow
                    || position == wifiRow
                    || position == cellularRow
                    || position == roamingRow
                    || position == cellSmallAllowedRow
                    || position == cellSmallSizeRow
                    || position == hoursRow
                    || position == nightHoardingRow
                    || position == batteryRow
                    || position == monthlyLimitRow
                    || position == monthlyCellOnlyRow;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view;
            switch (viewType) {
                case VIEW_TYPE_HEADER:
                    view = new HeaderCell(context);
                    view.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
                    break;
                case VIEW_TYPE_CHECK:
                    view = new TextCheckCell(context);
                    view.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
                    break;
                case VIEW_TYPE_SETTINGS:
                    view = new TextSettingsCell(context);
                    view.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
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
                case VIEW_TYPE_HEADER: {
                    HeaderCell cell = (HeaderCell) holder.itemView;
                    if (position == generalHeaderRow) {
                        cell.setText(LocaleController.getString(R.string.PrefetchSectionGeneral));
                    } else if (position == networkHeaderRow) {
                        cell.setText(LocaleController.getString(R.string.PrefetchSectionNetwork));
                    } else if (position == scheduleHeaderRow) {
                        cell.setText(LocaleController.getString(R.string.PrefetchSectionSchedule));
                    } else if (position == monthlyHeaderRow) {
                        cell.setText(LocaleController.getString(R.string.PrefetchSectionMonthly));
                    }
                    break;
                }
                case VIEW_TYPE_CHECK: {
                    TextCheckCell cell = (TextCheckCell) holder.itemView;
                    if (position == toggleRow) {
                        cell.setTextAndCheck(LocaleController.getString(R.string.BackgroundPrefetch), config.enabled, true);
                    } else if (position == wifiRow) {
                        cell.setTextAndCheck(LocaleController.getString(R.string.PrefetchAllowWifi), config.allowWifi, true);
                    } else if (position == cellularRow) {
                        cell.setTextAndCheck(LocaleController.getString(R.string.PrefetchAllowCellular), config.allowCellular, true);
                    } else if (position == roamingRow) {
                        cell.setTextAndCheck(LocaleController.getString(R.string.PrefetchAllowRoaming), config.allowRoaming, true);
                    } else if (position == cellSmallAllowedRow) {
                        cell.setTextAndCheck(LocaleController.getString(R.string.PrefetchCellSmallAllowed), config.cellSmallVideoAllowed, true);
                    } else if (position == nightHoardingRow) {
                        cell.setTextAndCheck(LocaleController.getString(R.string.PrefetchNightHoarding), config.nightHoarding, true);
                    } else if (position == monthlyCellOnlyRow) {
                        cell.setTextAndCheck(LocaleController.getString(R.string.PrefetchMonthlyCellOnly), config.monthlyLimitCellularOnly, true);
                    }
                    break;
                }
                case VIEW_TYPE_SETTINGS: {
                    TextSettingsCell cell = (TextSettingsCell) holder.itemView;
                    if (position == concurrencyRow) {
                        cell.setTextAndValue(LocaleController.getString(R.string.PrefetchConcurrency), String.valueOf(config.concurrency), true);
                    } else if (position == reservedSlotsRow) {
                        cell.setTextAndValue(LocaleController.getString(R.string.PrefetchReservedSlots), String.valueOf(config.reservedUserSlots), true);
                    } else if (position == speedLimitRow) {
                        cell.setTextAndValue(LocaleController.getString(R.string.PrefetchSpeedLimit), formatSpeedLabel(config.speedLimitBytesPerSec), true);
                    } else if (position == cellSmallSizeRow) {
                        cell.setTextAndValue(LocaleController.getString(R.string.PrefetchCellSmallSize), PrefetchConfig.formatBytes(config.cellSmallVideoMaxSize), true);
                    } else if (position == hoursRow) {
                        cell.setTextAndValue(LocaleController.getString(R.string.PrefetchHours), hoursValueText(), true);
                    } else if (position == batteryRow) {
                        cell.setTextAndValue(LocaleController.getString(R.string.PrefetchMinBattery), String.format(Locale.US, "%d%%", config.minBatteryPct), true);
                    } else if (position == monthlyLimitRow) {
                        cell.setTextAndValue(LocaleController.getString(R.string.PrefetchMonthlyLimit), monthlyLimitValueText(), true);
                    } else if (position == monthUsedRow) {
                        long used = PrefetchStore.getMonthCellularUsed(currentAccount);
                        cell.setTextAndValue(LocaleController.getString(R.string.PrefetchMonthUsed), PrefetchConfig.formatBytes(used), false);
                    }
                    break;
                }
                case VIEW_TYPE_INFO: {
                    TextInfoPrivacyCell cell = (TextInfoPrivacyCell) holder.itemView;
                    cell.setText(LocaleController.getString(R.string.BackgroundPrefetchInfo));
                    break;
                }
            }
        }

        @Override
        public int getItemViewType(int position) {
            if (position == generalHeaderRow || position == networkHeaderRow || position == scheduleHeaderRow || position == monthlyHeaderRow) {
                return VIEW_TYPE_HEADER;
            } else if (position == toggleRow || position == wifiRow || position == cellularRow || position == roamingRow
                    || position == cellSmallAllowedRow || position == nightHoardingRow || position == monthlyCellOnlyRow) {
                return VIEW_TYPE_CHECK;
            } else if (position == concurrencyRow || position == reservedSlotsRow || position == speedLimitRow
                    || position == cellSmallSizeRow || position == hoursRow || position == batteryRow
                    || position == monthlyLimitRow || position == monthUsedRow) {
                return VIEW_TYPE_SETTINGS;
            }
            return VIEW_TYPE_INFO;
        }
    }

    private String hoursValueText() {
        int preset = PrefetchConfig.indexOfHoursPreset(config.allowStartHour, config.allowEndHour);
        switch (preset < 0 ? -1 : preset) {
            case 0:
                return LocaleController.getString(R.string.PrefetchHoursAll);
            case 1:
                return LocaleController.getString(R.string.PrefetchHoursDaytime);
            case 2:
                return LocaleController.getString(R.string.PrefetchHoursNight);
            case 3:
                return LocaleController.getString(R.string.PrefetchHoursEarlyMorning);
            default:
                return String.format(Locale.US, "%02d:00–%02d:00", config.allowStartHour, config.allowEndHour);
        }
    }

    private String monthlyLimitValueText() {
        if (config.monthlyCellularLimit < 0) {
            return LocaleController.getString(R.string.PrefetchMonthlyUnlimited);
        }
        return PrefetchConfig.formatBytes(config.monthlyCellularLimit);
    }
}
