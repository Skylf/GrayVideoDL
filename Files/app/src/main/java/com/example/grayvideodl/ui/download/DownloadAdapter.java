/*
 * DownloadAdapter.java
 * 下载列表 RecyclerView 适配器：将 DownloadTask 列表绑定到列表项布局，
 * 支持暂停/继续和删除操作，根据任务状态动态控制进度条和按钮显隐。
 */

package com.example.grayvideodl.ui.download;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.example.grayvideodl.R;
import com.example.grayvideodl.model.DownloadTask;
import com.google.android.material.button.MaterialButton;

import java.util.List;

public class DownloadAdapter extends RecyclerView.Adapter<DownloadAdapter.ViewHolder> {

    // 下载任务列表数据源
    private List<DownloadTask> taskList;

    // 操作回调接口
    private OnTaskActionListener actionListener;

    /*
     * 任务操作回调接口
     * 取消：取消正在下载的任务
     * 删除：移除任务和文件
     * 打开文件夹：打开下载文件所在目录
     * 分享：将已下载的文件通过系统分享发送给其他应用
     */
    public interface OnTaskActionListener {
        void onCancel(DownloadTask task);
        void onDelete(DownloadTask task);
        void onOpenFolder(DownloadTask task);
        void onShare(DownloadTask task);
    }

    /*
     * 构造适配器
     * @param taskList 下载任务列表
     * @param listener 操作回调
     */
    public DownloadAdapter(List<DownloadTask> taskList, OnTaskActionListener listener) {
        this.taskList = taskList;
        this.actionListener = listener;
    }

    /*
     * 刷新数据源
     * @param newList 新的任务列表
     */
    public void updateData(List<DownloadTask> newList) {
        this.taskList = newList;
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        // 加载 item_download 布局作为列表项视图
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_download, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        // 获取当前位置的下载任务
        DownloadTask task = taskList.get(position);
        holder.bind(task, actionListener);
    }

    @Override
    public int getItemCount() {
        return taskList == null ? 0 : taskList.size();
    }

    /*
     * ViewHolder：持有列表项中的所有控件引用
     */
    static class ViewHolder extends RecyclerView.ViewHolder {

        // 视频标题文本
        private TextView tvTitle;

        // 分辨率文本
        private TextView tvResolution;

        // 文件大小文本
        private TextView tvFileSize;

        // 下载速度 + 已下载/总大小文本
        private TextView tvSpeedSize;

        // 水平进度条，显示 0-100 的下载百分比
        private ProgressBar progressBar;

        // 状态文本（下载中百分比 / 已完成 / 失败原因 / 已暂停）
        private TextView tvStatus;

        // 取消任务按钮（原暂停按钮）
        private MaterialButton btnCancel;

        // 删除任务按钮
        private MaterialButton btnDelete;

        // 分享任务按钮（仅在任务已完成时显示）
        private MaterialButton btnShare;

        // 下载路径容器布局（可点击打开文件夹）
        private View layoutDownloadPath;

        // 下载路径文本
        private TextView tvDownloadPath;

        // "打开"文字按钮
        private MaterialButton tvOpenFolder;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            // 通过 ID 获取布局中定义的所有控件
            tvTitle = itemView.findViewById(R.id.tv_task_title);
            tvResolution = itemView.findViewById(R.id.tv_task_resolution);
            tvFileSize = itemView.findViewById(R.id.tv_task_filesize);
            tvSpeedSize = itemView.findViewById(R.id.tv_speed_size);
            progressBar = itemView.findViewById(R.id.progress_bar);
            tvStatus = itemView.findViewById(R.id.tv_task_status);
            btnCancel = itemView.findViewById(R.id.btn_pause_resume);
            btnDelete = itemView.findViewById(R.id.btn_delete);
            btnShare = itemView.findViewById(R.id.btn_share);
            layoutDownloadPath = itemView.findViewById(R.id.layout_download_path);
            tvDownloadPath = itemView.findViewById(R.id.tv_download_path);
            tvOpenFolder = itemView.findViewById(R.id.tv_open_folder);
        }

        /*
         * bind: 将 DownloadTask 数据绑定到列表项控件
         * 根据任务状态动态控制控件显隐
         * 下载中显示取消按钮、进度条等
         */
        void bind(final DownloadTask task, final OnTaskActionListener listener) {
            // 设置基本信息
            tvTitle.setText(task.getTitle() != null ? task.getTitle() : "未知标题");
            tvResolution.setText(task.getResolution() != null ? task.getResolution() : "");
            tvFileSize.setText(task.getFileSizeText());

            // 根据任务状态控制 UI
            String status = task.getStatus();
            tvStatus.setText(task.getStatusText());

            if (DownloadTask.STATUS_DOWNLOADING.equals(status)) {
                // 下载中：显示进度条、速度/大小文本和取消按钮
                progressBar.setVisibility(View.VISIBLE);
                progressBar.setProgress(task.getProgress());
                btnCancel.setVisibility(View.VISIBLE);
                btnCancel.setText("取消");
                // 设置下载速度和已下载/总大小
                setSpeedSizeText(task);
                tvStatus.setTextColor(
                        itemView.getContext() .getColor(
                                android.R.color.darker_gray));

            } else if (DownloadTask.STATUS_COMPLETED.equals(status)) {
                // 已完成：隐藏进度条、速度/大小文本和取消按钮，显示分享按钮
                progressBar.setVisibility(View.GONE);
                tvSpeedSize.setVisibility(View.GONE);
                btnCancel.setVisibility(View.GONE);
                btnShare.setVisibility(View.VISIBLE);
                tvStatus.setTextColor(
                        itemView.getContext().getColor(
                                android.R.color.holo_green_dark));

            } else if (DownloadTask.STATUS_FAILED.equals(status)) {
                // 失败：隐藏进度条、速度/大小文本、取消按钮和分享按钮
                progressBar.setVisibility(View.GONE);
                tvSpeedSize.setVisibility(View.GONE);
                btnCancel.setVisibility(View.GONE);
                btnShare.setVisibility(View.GONE);
                tvStatus.setTextColor(
                        itemView.getContext().getColor(
                                android.R.color.holo_red_dark));
            } else {
                // 其他状态（如暂停等）：隐藏分享按钮
                btnShare.setVisibility(View.GONE);
            }

            // 取消按钮点击事件
            btnCancel.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (listener != null) {
                        listener.onCancel(task);
                    }
                }
            });

            // 删除按钮点击事件
            btnDelete.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (listener != null) {
                        listener.onDelete(task);
                    }
                }
            });

            // 分享按钮点击事件（通过回调通知 DownloadFragment 执行分享逻辑）
            btnShare.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (listener != null) {
                        listener.onShare(task);
                    }
                }
            });

            // 设置下载路径显示
            String pathDisplay = task.getDownloadPathDisplay();
            if (pathDisplay != null && !pathDisplay.isEmpty()) {
                tvDownloadPath.setText(pathDisplay);
                layoutDownloadPath.setVisibility(View.VISIBLE);
                // 打开文件夹点击事件
                layoutDownloadPath.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        if (listener != null) {
                            listener.onOpenFolder(task);
                        }
                    }
                });
            } else {
                layoutDownloadPath.setVisibility(View.GONE);
            }
        }

        /*
         * setSpeedSizeText: 设置下载速度和已下载/总大小文本
         * 如果速度 > 0 则显示 "⬇ 速度 · 已下载/总大小"
         * 否则仅显示 "已下载/总大小"
         */
        private void setSpeedSizeText(DownloadTask task) {
            long downloaded = task.getDownloadedBytes();
            long total = task.getTotalBytesForProgress();
            double speed = task.getSpeed();
            if (downloaded <= 0 && total <= 0) {
                tvSpeedSize.setVisibility(View.GONE);
                return;
            }
            tvSpeedSize.setVisibility(View.VISIBLE);
            StringBuilder sb = new StringBuilder();
            // 显示下载速度
            if (speed > 0) {
                sb.append("⬇ ").append(formatSpeedStatic(speed)).append(" · ");
            }
            // 显示已下载/总大小
            sb.append(formatBytesStatic(downloaded));
            if (total > 0) {
                sb.append(" / ").append(formatBytesStatic(total));
            }
            tvSpeedSize.setText(sb.toString());
        }

        /*
         * formatSpeedStatic: 将字节/秒格式化为可读速度文本（静态方法）
         */
        private static String formatSpeedStatic(double bytesPerSec) {
            if (bytesPerSec < 1024) {
                return String.format("%.0f B/s", bytesPerSec);
            } else if (bytesPerSec < 1024 * 1024) {
                return String.format("%.0f KiB/s", bytesPerSec / 1024);
            } else {
                return String.format("%.1f MiB/s", bytesPerSec / (1024 * 1024));
            }
        }

        /*
         * formatBytesStatic: 将字节数格式化为可读大小文本（静态方法）
         */
        private static String formatBytesStatic(long bytes) {
            if (bytes < 1024) {
                return bytes + " B";
            } else if (bytes < 1024 * 1024) {
                return String.format("%.0f KB", bytes / 1024.0);
            } else {
                return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
            }
        }
    }
}
