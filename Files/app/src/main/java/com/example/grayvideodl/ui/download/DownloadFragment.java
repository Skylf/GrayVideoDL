/*
 * DownloadFragment.java (v2)
 * 下载列表 Fragment：展示所有下载任务的状态和进度，
 * 支持暂停/继续、删除操作，自动刷新进行中的任务进度。
 */

package com.example.grayvideodl.ui.download;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.chaquo.python.Python;
import com.chaquo.python.PyObject;
import com.chaquo.python.android.AndroidPlatform;
import com.example.grayvideodl.R;
import com.example.grayvideodl.model.DownloadTask;
import com.example.grayvideodl.ui.settings.BilibiliLoginDialog;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class DownloadFragment extends Fragment
        implements DownloadAdapter.OnTaskActionListener {

    // 日志标签，用于调试下载列表加载和显示问题
    private static final String TAG = "DownloadFlow";

    // 下载列表 RecyclerView，用于展示下载任务列表
    private RecyclerView rvDownloadList;

    // 空状态提示文本，当没有下载任务时显示
    private TextView tvEmptyDownload;

    // RecyclerView 适配器
    private DownloadAdapter adapter;

    // 下载任务列表数据
    private List<DownloadTask> taskList;

    // 主线程 Handler，用于 UI 更新
    private Handler mainHandler;

    // 自动刷新运行标志
    private boolean isRefreshing = false;

    // 正在执行下载的线程跟踪（taskId -> Thread）
    private Map<String, Thread> runningDownloads;

    // 任务锁，防止并发修改
    private final Object taskLock = new Object();

    /*
     * onCreateView: 创建 Fragment 的视图布局
     * 加载 fragment_download 布局并初始化控件和适配器
     */
    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        // 填充 fragment_download 布局作为当前 Fragment 的视图
        View view = inflater.inflate(R.layout.fragment_download, container, false);

        // 初始化控件和数据结构
        rvDownloadList = view.findViewById(R.id.rv_download_list);
        tvEmptyDownload = view.findViewById(R.id.tv_empty_download);
        mainHandler = new Handler(Looper.getMainLooper());
        runningDownloads = new HashMap<>();

        // 设置 RecyclerView 布局管理器和适配器
        rvDownloadList.setLayoutManager(
                new LinearLayoutManager(requireContext()));
        taskList = new ArrayList<>();
        adapter = new DownloadAdapter(taskList, this);
        rvDownloadList.setAdapter(adapter);

        // 加载已有的下载任务（首次加载不自动启动，onResume 中会再次加载并启动）
        loadTasks(false);

        Log.d(TAG, "onCreateView: DownloadFragment 视图创建完成");
        return view;
    }

    /*
     * onResume: Fragment 恢复可见时回调
     * 重新加载任务列表，启动自动刷新，
     * 并自动开始处于"下载中"状态但尚未运行的任务。
     * 注意：autoStartPendingTasks 在 loadTasks 加载完成后才调用，避免异步时序问题
     */
    @Override
    public void onResume() {
        super.onResume();
        Log.d(TAG, "onResume: DownloadFragment 恢复可见，taskList.size="
                + (taskList == null ? "null" : taskList.size()));
        Log.d(TAG, "onResume: 当前 runningDownloads 中的任务数="
                + (runningDownloads == null ? "null" : runningDownloads.size()));
        loadTasks(true); // 传入 true 表示加载完成后自动启动待处理任务
        startAutoRefresh();
    }

    /*
     * onHiddenChanged: Fragment 显隐状态变化时回调
     * 当使用 hide/show 切换 Fragment 时，onResume 不会被触发，
     * 需要在此处补充加载任务列表和自动启动下载的逻辑。
     */
    @Override
    public void onHiddenChanged(boolean hidden) {
        super.onHiddenChanged(hidden);
        if (!hidden) {
            // Fragment 从隐藏变为可见（被 show 出来）
            Log.d(TAG, "onHiddenChanged: Fragment 变为可见（show），重新加载任务并检查自动启动");
            loadTasks(true);
            startAutoRefresh();
        } else {
            // Fragment 被隐藏（hide）
            Log.d(TAG, "onHiddenChanged: Fragment 被隐藏（hide），停止自动刷新");
            stopAutoRefresh();
        }
    }

    /*
     * autoStartPendingTasks: 启动所有状态为"下载中"但尚未运行的任务
     * 用于从 HomeFragment 添加新任务后自动开始下载
     */
    private void autoStartPendingTasks() {
        Log.d(TAG, "autoStartPendingTasks: 开始检查待处理任务，taskList.size="
                + taskList.size());
        if (taskList.isEmpty()) {
            Log.w(TAG, "autoStartPendingTasks: taskList 为空，无任务可启动");
            return;
        }
        int startedCount = 0;
        for (final DownloadTask task : taskList) {
            boolean isDownloading = DownloadTask.STATUS_DOWNLOADING
                    .equals(task.getStatus());
            boolean alreadyRunning = runningDownloads.containsKey(task.getId());
            Log.d(TAG, "autoStartPendingTasks: 任务 id=" + task.getId()
                    + ", title=" + task.getTitle()
                    + ", status=" + task.getStatus()
                    + ", isDownloading=" + isDownloading
                    + ", alreadyRunning=" + alreadyRunning);
            if (isDownloading && !alreadyRunning) {
                // 此任务处于下载中状态但尚未有运行中的线程，启动它
                startedCount++;
                final DownloadTask taskCopy = task;
                Thread downloadThread = new Thread(new Runnable() {
                    @Override
                    public void run() {
                        executeDownload(taskCopy);
                    }
                });
                downloadThread.setDaemon(true);
                downloadThread.start();
                runningDownloads.put(task.getId(), downloadThread);
                Log.d(TAG, "autoStartPendingTasks: 已启动下载线程，taskId="
                        + task.getId() + ", title=" + task.getTitle());
            }
        }
        Log.d(TAG, "autoStartPendingTasks: 完成，共启动 " + startedCount + " 个任务");
    }

    /*
     * onPause: Fragment 失去可见时回调
     * 停止自动刷新以节省资源
     */
    @Override
    public void onPause() {
        super.onPause();
        stopAutoRefresh();
    }

    /*
     * loadTasks: 从持久化文件加载下载任务列表
     * @param autoStart 加载完成后是否自动启动待处理的下载任务
     * 在 UI 线程中刷新适配器和空状态
     */
    private void loadTasks(final boolean autoStart) {
        Log.d(TAG, "loadTasks: 开始加载任务列表，autoStart=" + autoStart);
        // 在后台线程加载数据，避免阻塞 UI
        new Thread(new Runnable() {
            @Override
            public void run() {
                final List<DownloadTask> loadedTasks;
                synchronized (taskLock) {
                    loadedTasks = DownloadTask.loadTaskList(requireContext());
                }
                Log.d(TAG, "loadTasks: 后台线程加载完成，loadedTasks.size="
                        + loadedTasks.size());
                for (DownloadTask t : loadedTasks) {
                    Log.d(TAG, "loadTasks:   -> 任务 id=" + t.getId()
                            + ", title=" + t.getTitle()
                            + ", status=" + t.getStatus()
                            + ", progress=" + t.getProgress());
                }
                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        taskList.clear();
                        taskList.addAll(loadedTasks);
                        adapter.updateData(taskList);
                        updateEmptyState();
                        Log.d(TAG, "loadTasks: UI 更新完成，taskList.size="
                                + taskList.size()
                                + ", autoStart=" + autoStart);
                        // 加载完成后，如果需要则自动启动待处理的任务
                        if (autoStart) {
                            Log.d(TAG, "loadTasks: 加载完成，即将调用 autoStartPendingTasks");
                            autoStartPendingTasks();
                        } else {
                            Log.d(TAG, "loadTasks: autoStart=false，不自动启动任务");
                        }
                    }
                });
            }
        }).start();
    }

    /*
     * updateEmptyState: 更新空状态提示的显隐
     * 有任务时显示列表，没有时显示"暂无下载任务"
     */
    private void updateEmptyState() {
        boolean isEmpty = taskList.isEmpty();
        rvDownloadList.setVisibility(isEmpty ? View.GONE : View.VISIBLE);
        tvEmptyDownload.setVisibility(isEmpty ? View.VISIBLE : View.GONE);
    }

    /*
     * startAutoRefresh: 启动自动刷新定时器
     * 每 1 秒刷新一次列表，仅在有下载中或暂停中任务时启动
     */
    private void startAutoRefresh() {
        if (isRefreshing) return;
        isRefreshing = true;
        mainHandler.post(refreshRunnable);
    }

    /*
     * stopAutoRefresh: 停止自动刷新
     */
    private void stopAutoRefresh() {
        isRefreshing = false;
        mainHandler.removeCallbacks(refreshRunnable);
    }

    /*
     * refreshRunnable: 自动刷新任务，每秒执行一次
     * 只刷新UI（从内存中读取进度数据），不从JSON文件重载
     * 进度数据由 progressPolling 实时更新到内存中的 taskList
     */
    private Runnable refreshRunnable = new Runnable() {
        @Override
        public void run() {
            if (!isRefreshing) return;

            // 检查是否有活动的下载或暂停任务
            boolean hasActiveTasks = false;
            for (DownloadTask task : taskList) {
                if (DownloadTask.STATUS_DOWNLOADING.equals(task.getStatus())
                        || DownloadTask.STATUS_PAUSED.equals(task.getStatus())) {
                    hasActiveTasks = true;
                    break;
                }
            }

            if (hasActiveTasks) {
                // 仅刷新UI显示，不重新加载文件（避免覆盖内存中的实时进度数据）
                adapter.notifyDataSetChanged();
                // 继续定时刷新
                mainHandler.postDelayed(this, 1000);
            } else {
                // 没有活动任务，停止自动刷新
                isRefreshing = false;
            }
        }
    };

    /*
     * saveTasks: 将当前任务列表持久化保存
     */
    private void saveTasks() {
        synchronized (taskLock) {
            DownloadTask.saveTaskList(requireContext(), taskList);
        }
    }

    /*
     * findTaskIndexById: 根据任务 ID 查找在列表中的索引
     * @param taskId 任务唯一 ID
     * @return 索引，未找到返回 -1
     */
    private int findTaskIndexById(String taskId) {
        for (int i = 0; i < taskList.size(); i++) {
            if (taskList.get(i).getId().equals(taskId)) {
                return i;
            }
        }
        return -1;
    }

    /*
     * updateTaskInList: 更新列表中指定 ID 的任务对象
     * @param updatedTask 更新后的任务对象
     */
    private void updateTaskInList(DownloadTask updatedTask) {
        int index = findTaskIndexById(updatedTask.getId());
        if (index >= 0) {
            taskList.set(index, updatedTask);
            adapter.notifyItemChanged(index);
        }
    }

    // ===================== 操作回调实现 =====================

    /*
     * onPauseResume: 暂停/继续按钮点击回调
     * 根据任务当前状态切换：下载中→暂停，已暂停→继续
     */
    @Override
    public void onPauseResume(DownloadTask task) {
        if (DownloadTask.STATUS_DOWNLOADING.equals(task.getStatus())) {
            // 下载中 → 暂停：创建取消标志文件
            pauseDownload(task);
        } else if (DownloadTask.STATUS_PAUSED.equals(task.getStatus())) {
            // 已暂停 → 继续：重新启动下载
            resumeDownload(task);
        }
    }

    /*
     * onDelete: 删除按钮点击回调
     * 如果任务正在下载，先暂停再删除文件
     */
    @Override
    public void onDelete(DownloadTask task) {
        // 确认删除对话框
        new AlertDialog.Builder(requireContext())
                .setTitle("删除下载任务")
                .setMessage("确定要删除" +
                        (task.getTitle() != null ? "「" + task.getTitle() + "」" : "") +
                        "的下载记录吗？" +
                        (task.isCompleted() ? "\n（已下载的文件也将被删除）" : ""))
                .setPositiveButton("删除", (dialog, which) -> {
                    deleteTask(task);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /*
     * pauseDownload: 暂停指定任务的下载
     * 创建取消标志文件，Python 的 progress_hook 检测到后自动停止
     */
    private void pauseDownload(DownloadTask task) {
        // 创建取消标志文件
        File cancelFlag = new File(requireContext().getCacheDir(),
                task.getCancelFileName());
        try {
            cancelFlag.createNewFile();
        } catch (Exception e) {
            e.printStackTrace();
        }

        // 更新任务状态为暂停
        int index = findTaskIndexById(task.getId());
        if (index >= 0) {
            taskList.get(index).setStatus(DownloadTask.STATUS_PAUSED);
            adapter.notifyItemChanged(index);
            saveTasks();
        }

        // 移除线程跟踪（线程将自行检测到 cancel flag 后退出）
        runningDownloads.remove(task.getId());
    }

    /*
     * resumeDownload: 继续暂停的下载任务
     * 重新启动下载线程，调用 Python 的 downloadVideoWithProgress
     */
    private void resumeDownload(DownloadTask task) {
        // 更新状态为下载中
        int index = findTaskIndexById(task.getId());
        if (index < 0) return;

        taskList.get(index).setStatus(DownloadTask.STATUS_DOWNLOADING);
        adapter.notifyItemChanged(index);
        saveTasks();

        // 启动自动刷新
        startAutoRefresh();

        // 在新线程中执行下载
        final DownloadTask taskCopy = task;
        Thread downloadThread = new Thread(new Runnable() {
            @Override
            public void run() {
                executeDownload(taskCopy);
            }
        });
        downloadThread.setDaemon(true);
        downloadThread.start();
        runningDownloads.put(task.getId(), downloadThread);
    }

    /*
     * onOpenFolder: 打开下载文件所在文件夹（回调实现）
     * 通过系统 Intent 打开文件夹，供用户查看已下载的文件
     */
    @Override
    public void onOpenFolder(DownloadTask task) {
        String folderPath = task.getFolderToOpen();
        if (folderPath == null || folderPath.isEmpty()) {
            showToast("下载路径不存在");
            return;
        }
        openFolder(folderPath);
    }

    /*
     * openFolder: 通过系统文件管理器打开指定路径的文件夹
     * 尝试多种方式打开文件夹，兼容不同 Android 版本和文件管理器
     * @param folderPath 要打开的文件夹绝对路径
     */
    private void openFolder(String folderPath) {
        try {
            File folder = new File(folderPath);
            if (!folder.exists()) {
                showToast("文件夹不存在");
                return;
            }
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                // Android 5+：尝试通过 DocumentsContract 打开
                try {
                    android.net.Uri treeUri = android.provider.DocumentsContract
                            .buildTreeDocumentUri(
                                    "com.android.externalstorage.documents",
                                    "primary:Download/GrayVideoDL");
                    intent.setDataAndType(treeUri,
                            "vnd.android.document/directory");
                    startActivity(intent);
                    return;
                } catch (Exception ignored) {
                    // 尝试失败，继续走 file URI 方式
                }
            }

            // 备用方式：使用 file URI（部分文件管理器支持）
            intent.setDataAndType(
                    android.net.Uri.fromFile(folder), "*/*");
            try {
                startActivity(intent);
            } catch (Exception e) {
                // 如果 file URI 方式也失败，提示用户手动查找
                showToast("请使用文件管理器打开: " + folderPath);
            }
        } catch (Exception e) {
            showToast("无法打开文件夹: " + e.getMessage());
        }
    }

    /*
     * showToast: 显示简短提示
     */
    private void showToast(String msg) {
        Toast.makeText(getActivity(), msg, Toast.LENGTH_SHORT).show();
    }

    /*
     * deleteTask: 删除下载任务及对应的文件
     * 先取消正在进行的下载，再删除文件和任务记录
     */
    private void deleteTask(DownloadTask task) {
        // 1. 如果正在下载，先创建取消标志
        if (DownloadTask.STATUS_DOWNLOADING.equals(task.getStatus())) {
            File cancelFlag = new File(requireContext().getCacheDir(),
                    task.getCancelFileName());
            try {
                cancelFlag.createNewFile();
            } catch (Exception e) {
                e.printStackTrace();
            }
            runningDownloads.remove(task.getId());
        }

        // 2. 删除已下载的文件
        if (task.getFilepath() != null && !task.getFilepath().isEmpty()) {
            File file = new File(task.getFilepath());
            if (file.exists()) {
                file.delete();
            }
        }

        // 3. 从列表中移除
        int index = findTaskIndexById(task.getId());
        if (index >= 0) {
            taskList.remove(index);
            adapter.notifyDataSetChanged();
            updateEmptyState();
        }

        // 4. 保存列表
        saveTasks();

        // 5. 清理取消标志和进度文件
        File cancelFlag = new File(requireContext().getCacheDir(),
                task.getCancelFileName());
        if (cancelFlag.exists()) cancelFlag.delete();

        File progressFile = new File(requireContext().getCacheDir(),
                task.getProgressFileName());
        if (progressFile.exists()) progressFile.delete();
    }

    // ===================== 下载执行逻辑 =====================

    /*
     * executeDownload: 在后台线程中执行实际下载
     * 调用 Python 的 downloadVideoWithProgress 并轮询进度文件
     * @param task 要下载的任务
     */
    private void executeDownload(DownloadTask task) {
        Log.d(TAG, "executeDownload: 开始执行下载，taskId=" + task.getId()
                + ", title=" + task.getTitle()
                + ", url=" + task.getUrl()
                + ", formatId=" + task.getFormatId());
        Context ctx = requireContext();

        // 获取 Cache 目录中的进度文件和取消标志文件路径
        final String progressFilePath = new File(ctx.getCacheDir(),
                task.getProgressFileName()).getAbsolutePath();
        final String cancelFlagPath = new File(ctx.getCacheDir(),
                task.getCancelFileName()).getAbsolutePath();
        Log.d(TAG, "executeDownload: progressFilePath=" + progressFilePath
                + ", cancelFlagPath=" + cancelFlagPath);

        // 清理可能残留的旧进度文件和取消标志
        File oldProgress = new File(progressFilePath);
        if (oldProgress.exists()) oldProgress.delete();
        File oldCancel = new File(cancelFlagPath);
        if (oldCancel.exists()) oldCancel.delete();

        // 获取下载目录
        File downloadDir = getDownloadDirectory(ctx);

        // 获取 Cookie 文件
        String cookieFile = BilibiliLoginDialog.getCookieFilePath(ctx);

        // 启动进度轮询（每 500ms 读取一次进度文件）
        final boolean[] isDownloadDone = {false};
        startProgressPolling(task, progressFilePath, isDownloadDone);

        try {
            // 调用 Python 下载函数
            String resultStr = callDownloadWithProgress(
                    task.getUrl(), task.getFormatId(),
                    downloadDir.getAbsolutePath(), cookieFile,
                    progressFilePath, cancelFlagPath);

            // 解析结果
            JSONObject result = new JSONObject(resultStr);
            final String status = result.optString("status", "error");

            mainHandler.post(new Runnable() {
                @Override
                public void run() {
                    isDownloadDone[0] = true;
                    int idx = findTaskIndexById(task.getId());
                    if (idx < 0) return;

                    if ("ok".equals(status)) {
                        // 下载成功
                        taskList.get(idx).setStatus(
                                DownloadTask.STATUS_COMPLETED);
                        taskList.get(idx).setFilepath(
                                result.optString("filepath", ""));
                        taskList.get(idx).setProgress(100);

                        // 获取文件大小
                        String fp = result.optString("filepath", "");
                        if (!fp.isEmpty()) {
                            File f = new File(fp);
                            if (f.exists()) {
                                taskList.get(idx).setFileSize(f.length());
                            }
                        }

                        // 复制到公共目录
                        copyToPublicDownloads(ctx,
                                new File(result.optString("filepath", "")));

                    } else if ("paused".equals(status)) {
                        // 用户暂停
                        taskList.get(idx).setStatus(
                                DownloadTask.STATUS_PAUSED);

                    } else {
                        // 下载失败
                        taskList.get(idx).setStatus(
                                DownloadTask.STATUS_FAILED);
                        taskList.get(idx).setError(
                                result.optString("error", "未知错误"));
                    }

                    adapter.notifyItemChanged(idx);
                    saveTasks();
                }
            });

        } catch (Exception e) {
            mainHandler.post(new Runnable() {
                @Override
                public void run() {
                    isDownloadDone[0] = true;
                    int idx = findTaskIndexById(task.getId());
                    if (idx < 0) return;

                    taskList.get(idx).setStatus(
                            DownloadTask.STATUS_FAILED);
                    taskList.get(idx).setError(e.getMessage());
                    adapter.notifyItemChanged(idx);
                    saveTasks();
                }
            });
        } finally {
            // 清理进度文件
            new File(progressFilePath).delete();
        }
    }

    /*
     * startProgressPolling: 启动进度轮询
     * 每 500ms 读取一次进度文件，更新任务进度到 UI
     */
    private void startProgressPolling(final DownloadTask task,
                                       final String progressFilePath,
                                       final boolean[] isDone) {
        final Handler pollingHandler = new Handler(Looper.getMainLooper());
        final Runnable pollingRunnable = new Runnable() {
            @Override
            public void run() {
                if (isDone[0]) return; // 下载已完成，停止轮询

                // 读取进度文件
                File pf = new File(progressFilePath);
                if (pf.exists()) {
                    try {
                        BufferedReader br = new BufferedReader(
                                new FileReader(pf));
                        StringBuilder sb = new StringBuilder();
                        String line;
                        while ((line = br.readLine()) != null) {
                            sb.append(line);
                        }
                        br.close();

                        JSONObject progressData = new JSONObject(sb.toString());
                        final int percent = progressData.optInt("percent", 0);
                        final String progStatus = progressData.optString(
                                "status", "");

                        // 从进度文件中解析下载字节数、总字节数和速度
                        final long downloadedBytes = progressData.optLong(
                                "downloaded_bytes", 0);
                        final long totalBytes = progressData.optLong(
                                "total_bytes", 0);
                        final double speedBytes = progressData.optDouble(
                                "speed", 0);

                        // 更新主线程 UI
                        mainHandler.post(new Runnable() {
                            @Override
                            public void run() {
                                int idx = findTaskIndexById(task.getId());
                                if (idx >= 0) {
                                    // 更新进度
                                    taskList.get(idx).setProgress(percent);
                                    // 更新下载字节数、总字节数和速度（供适配器显示）
                                    taskList.get(idx).setDownloadedBytes(
                                            downloadedBytes);
                                    if (totalBytes > 0) {
                                        taskList.get(idx).setTotalBytesForProgress(
                                                totalBytes);
                                    }
                                    taskList.get(idx).setSpeed(speedBytes);

                                    // 从 progress 文件中解析 ETA 信息
                                    long eta = progressData.optLong("eta", 0);
                                    String etaText = "";
                                    if (eta > 0) {
                                        etaText = formatEta(eta);
                                    }

                                    // 更新状态文本（百分比 + 剩余时间）
                                    String statusText = "下载中 " + percent + "%";
                                    if (!etaText.isEmpty()) {
                                        statusText += " · 剩余" + etaText;
                                    }
                                    // 适配器会重新绑定数据，速度+大小由 tv_speed_size 显示
                                    adapter.notifyItemChanged(idx);
                                }
                            }
                        });

                    } catch (Exception e) {
                        // 忽略读取错误
                    }
                }

                // 继续轮询
                if (!isDone[0]) {
                    pollingHandler.postDelayed(this, 500);
                }
            }
        };

        // 启动轮询
        pollingHandler.postDelayed(pollingRunnable, 500);
    }

    /*
     * callDownloadWithProgress: 调用 Python 的 downloadVideoWithProgress 函数
     * 通过 Chaquopy 桥接调用
     */
    private String callDownloadWithProgress(String url, String formatId,
                                             String outputDir, String cookies,
                                             String progressFile,
                                             String cancelFlag) {
        try {
            if (!Python.isStarted()) {
                Python.start(new AndroidPlatform(requireContext()));
            }
            Python py = Python.getInstance();
            PyObject mod = py.getModule("ytdlp_bridge");
            PyObject result = mod.callAttr(
                    "downloadVideoWithProgress",
                    url, formatId, outputDir,
                    cookies, progressFile, cancelFlag);
            return result.toString();
        } catch (Exception e) {
            return "{\"status\":\"error\",\"error\":\""
                    + e.getMessage() + "\"}";
        }
    }

    /*
     * getDownloadDirectory: 获取 Python yt-dlp 写入的下载目录
     */
    private File getDownloadDirectory(Context context) {
        File dir = new File(
                context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS),
                "GrayVideoDL");
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    /*
     * copyToPublicDownloads: 将下载的文件保存到公共 Download 目录
     * 使用 MediaStore API 确保文件管理器中可见
     */
    private void copyToPublicDownloads(Context context, File sourceFile) {
        if (sourceFile == null || !sourceFile.exists()) return;

        try {
            String fileName = sourceFile.getName();
            String mimeType = "video/mp4";
            if (fileName.endsWith(".m4a") || fileName.endsWith(".mp3")
                    || fileName.endsWith(".aac")) {
                mimeType = "audio/mpeg";
            }

            android.content.ContentValues values =
                    new android.content.ContentValues();
            values.put(android.provider.MediaStore.Downloads.DISPLAY_NAME,
                    fileName);
            values.put(android.provider.MediaStore.Downloads.MIME_TYPE,
                    mimeType);
            values.put(android.provider.MediaStore.Downloads.RELATIVE_PATH,
                    "Download/GrayVideoDL");
            values.put(android.provider.MediaStore.Downloads.IS_PENDING, 1);

            android.net.Uri uri = context.getContentResolver().insert(
                    android.provider.MediaStore.Downloads
                            .EXTERNAL_CONTENT_URI, values);
            if (uri != null) {
                try (java.io.OutputStream out =
                             context.getContentResolver()
                                     .openOutputStream(uri);
                     java.io.FileInputStream in =
                             new java.io.FileInputStream(sourceFile)) {
                    byte[] buf = new byte[8192];
                    int len;
                    while ((len = in.read(buf)) > 0) {
                        if (out != null) out.write(buf, 0, len);
                    }
                }
                values.clear();
                values.put(android.provider.MediaStore.Downloads.IS_PENDING, 0);
                context.getContentResolver().update(uri, values, null, null);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /*
     * formatSpeed: 将字节/秒的下载速度格式化为可读文本
     * @param bytesPerSec 每秒字节数
     * @return 格式化的速度文本，如 "2.3 MiB/s"
     */
    private String formatSpeed(double bytesPerSec) {
        if (bytesPerSec < 1024) {
            return String.format("%.0f B/s", bytesPerSec);
        } else if (bytesPerSec < 1024 * 1024) {
            return String.format("%.0f KiB/s", bytesPerSec / 1024);
        } else {
            return String.format("%.1f MiB/s", bytesPerSec / (1024 * 1024));
        }
    }

    /*
     * formatEta: 将剩余秒数格式化为可读文本
     * @param etaSeconds 剩余秒数
     * @return 格式化的时间文本，如 "01:30"
     */
    private String formatEta(long etaSeconds) {
        if (etaSeconds <= 0) return "";
        long minutes = etaSeconds / 60;
        long seconds = etaSeconds % 60;
        return String.format("%02d:%02d", minutes, seconds);
    }

    /*
     * addExternalTask: 供外部（如 HomeFragment）调用的公开方法
     * 将新创建的下载任务添加到列表并立即执行下载
     * @param task 已配置好基本信息的下载任务
     */
    public void addExternalTask(DownloadTask task) {
        Log.d(TAG, "addExternalTask: 收到外部添加的任务，id=" + task.getId()
                + ", title=" + task.getTitle()
                + ", status=" + task.getStatus());
        // 添加到列表头部
        taskList.add(0, task);
        adapter.notifyItemInserted(0);
        updateEmptyState();
        saveTasks();
        Log.d(TAG, "addExternalTask: 任务已添加到列表头部，taskList.size="
                + taskList.size());

        // 启动自动刷新
        startAutoRefresh();

        // 立即在新线程中执行下载
        final DownloadTask taskCopy = task;
        Thread downloadThread = new Thread(new Runnable() {
            @Override
            public void run() {
                executeDownload(taskCopy);
            }
        });
        downloadThread.setDaemon(true);
        downloadThread.start();
        runningDownloads.put(task.getId(), downloadThread);
        Log.d(TAG, "addExternalTask: 下载线程已启动，taskId=" + task.getId());
    }
}
