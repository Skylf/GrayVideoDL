/*
 * DownloadFragment.java (v2)
 * 下载列表 Fragment：展示所有下载任务的状态和进度，
 * 支持暂停/继续、删除操作，自动刷新进行中的任务进度。
 */

package com.example.grayvideodl.ui.download;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.widget.LinearLayout;
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
import com.example.grayvideodl.FFmpegManager;
import com.example.grayvideodl.PlatformCookieManager;
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

    // 额外的 Logcat 标签，用于与 Python 端统一过滤音频合并相关日志
    private static final String TAG_FF_MEDIA = "FF-media";

    // 下载列表 RecyclerView，用于展示下载任务列表
    private RecyclerView rvDownloadList;

    // 空状态提示文本，当没有下载任务时显示
    private TextView tvEmptyDownload;

    // FFmpeg 警告浮层布局，显示音视频合并失败警告
    private com.google.android.material.card.MaterialCardView layoutFfmpegWarningToast;

    // FFmpeg 警告浮层文本，显示具体警告内容
    private TextView tvFfmpegWarningText;

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
        // FFmpeg 警告浮层控件
        layoutFfmpegWarningToast = view.findViewById(R.id.card_ffmpeg_warning_toast);
        tvFfmpegWarningText = view.findViewById(R.id.tv_ffmpeg_warning_text);
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

            // 检查是否有活动的下载任务
            boolean hasActiveTasks = false;
            for (DownloadTask task : taskList) {
                if (DownloadTask.STATUS_DOWNLOADING.equals(task.getStatus())) {
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
     * onCancel: 取消按钮点击回调
     * 取消正在下载的任务，标记为失败并显示"已取消"
     */
    @Override
    public void onCancel(DownloadTask task) {
        // 确认取消对话框
        new AlertDialog.Builder(requireContext())
                .setTitle("取消下载")
                .setMessage("确定要取消" +
                        (task.getTitle() != null ? "「" + task.getTitle() + "」" : "") +
                        "的下载吗？")
                .setPositiveButton("确定取消", (dialog, which) -> {
                    cancelDownload(task);
                })
                .setNegativeButton("继续下载", null)
                .show();
    }

    /*
     * cancelDownload: 取消指定任务的下载
     * 创建取消标志文件让 Python 端停止下载，然后将任务标记为失败
     */
    private void cancelDownload(DownloadTask task) {
        // 创建取消标志文件
        File cancelFlag = new File(requireContext().getCacheDir(),
                task.getCancelFileName());
        try {
            cancelFlag.createNewFile();
        } catch (Exception e) {
            e.printStackTrace();
        }

        // 移除线程跟踪
        runningDownloads.remove(task.getId());

        // 更新任务状态为失败（已取消）
        int index = findTaskIndexById(task.getId());
        if (index >= 0) {
            taskList.get(index).setStatus(DownloadTask.STATUS_FAILED);
            taskList.get(index).setError("已取消");
            adapter.notifyItemChanged(index);
            saveTasks();
        }
    }

    /*
     * onDelete: 删除按钮点击回调
     * 如果任务正在下载，先取消再删除文件
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
     * onOpenFolder: 打开下载文件所在文件夹（回调实现）
     * 通过系统 Intent 打开文件夹，供用户查看已下载的文件。
     * 注意：始终重定向到公共目录 /storage/emulated/0/Download/GrayVideoDL/，
     * 因为文件下载后会通过 copyToPublicDownloads 复制到该目录。
     * 如果传入了私有路径（task.filepath 的父目录），则替换为公共路径。
     */
    @Override
    public void onOpenFolder(DownloadTask task) {
        // 始终使用公共下载目录路径，因为文件会被复制到那里
        String publicDir = Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS).getAbsolutePath()
                + "/GrayVideoDL";
        File publicFolder = new File(publicDir);
        if (publicFolder.exists() && publicFolder.isDirectory()) {
            Log.d(TAG, "onOpenFolder: 使用公共目录路径: " + publicDir);
            openFolder(publicDir);
            return;
        }

        // 公共目录不存在时，回退到 task 中保存的路径
        String folderPath = task.getFolderToOpen();
        if (folderPath == null || folderPath.isEmpty()) {
            showToast("下载路径不存在");
            return;
        }
        Log.d(TAG, "onOpenFolder: 使用task中的路径: " + folderPath);
        openFolder(folderPath);
    }

    /*
     * onShare: 分享已下载的文件
     * 通过系统 Intent.ACTION_SEND 弹出应用选择器，让用户选择微信、QQ 等应用接收文件。
     * 使用 FileProvider 生成 content:// URI 确保 Android 7.0+ 跨应用文件访问。
     */
    @Override
    public void onShare(DownloadTask task) {
        // 获取已下载文件路径
        String filePath = task.getFilepath();
        if (filePath == null || filePath.isEmpty()) {
            showToast("文件路径不存在");
            return;
        }
        File file = new File(filePath);
        if (!file.exists()) {
            showToast("文件已被删除");
            return;
        }

        // 根据文件扩展名获取 MIME 类型
        String mimeType = getMimeType(file.getName());
        // 通过 FileProvider 生成 content:// URI，确保跨应用可读
        Uri fileUri = getUriForFile(requireContext(), file);

        // 构建 ACTION_SEND 分享 Intent
        Intent shareIntent = new Intent(Intent.ACTION_SEND);
        shareIntent.setType(mimeType);
        shareIntent.putExtra(Intent.EXTRA_STREAM, fileUri);
        // 临时授予目标应用读取此 URI 的权限
        shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

        // 弹出系统应用选择器
        startActivity(Intent.createChooser(shareIntent, "分享到..."));
    }

    /*
     * openFolder: 通过系统文件管理器打开指定路径的文件夹
     * 尝试多种方式打开文件夹，兼容不同 Android 版本和文件管理器。
     * 注意：DocumentsContract.buildTreeDocumentUri 依赖 MediaStore 缓存，
     * 当文件被外部工具（如MT管理器）删除时，缓存可能过时。因此在用
     * DocumentsContract 之前，先尝试 file:// URI 以获取实时文件系统状态。
     * @param folderPath 要打开的文件夹绝对路径
     */
    private void openFolder(String folderPath) {
        try {
            File folder = new File(folderPath);
            if (!folder.exists()) {
                showToast("文件夹不存在");
                return;
            }

            // ---- 第一步：触发 MediaStore 重新扫描文件夹，刷新索引缓存 ----
            // 当文件被外部工具（如MT管理器）修改/删除时，MediaStore 缓存会过时，
            // 主动扫描可以让 DocumentsProvider 获取到最新状态。
            scanMediaStore(folder);

            // ---- 第二步：尝试 file:// URI（无MIME类型） ----
            // 不设置MIME类型，让系统/文件管理器自行推断，某些管理器对无类型URI显示更完整
            try {
                Intent rawIntent = new Intent(Intent.ACTION_VIEW);
                rawIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                rawIntent.setData(android.net.Uri.fromFile(folder));
                startActivity(rawIntent);
                Log.d(TAG, "openFolder: file URI（无类型）成功");
                return;
            } catch (Exception ignored) {
                // 某些 Android 版本不支持 file URI 方式，继续尝试
            }

            // ---- 第三步：尝试 file:// URI（*/* 类型，显示所有文件） ----
            try {
                Intent fileIntent = new Intent(Intent.ACTION_VIEW);
                fileIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                fileIntent.setDataAndType(
                        android.net.Uri.fromFile(folder), "*/*");
                startActivity(fileIntent);
                Log.d(TAG, "openFolder: file URI（*/*）成功");
                return;
            } catch (Exception ignored) {
                // 某些 Android 版本不支持 file URI 方式，继续尝试
            }

            // ---- 第四步：Android 5+ DocumentsContract 方案（备用） ----
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                try {
                    android.net.Uri treeUri = android.provider.DocumentsContract
                            .buildTreeDocumentUri(
                                    "com.android.externalstorage.documents",
                                    "primary:Download/GrayVideoDL");
                    Intent docIntent = new Intent(Intent.ACTION_VIEW);
                    docIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    docIntent.setDataAndType(treeUri,
                            "vnd.android.document/directory");
                    startActivity(docIntent);
                    Log.d(TAG, "openFolder: DocumentsContract 成功");
                    return;
                } catch (Exception ignored2) {
                    // 尝试失败，提示用户手动查找
                }
            }

            // ---- 所有方式都失败 ----
            Log.w(TAG, "openFolder: 所有打开方式均失败，folderPath=" + folderPath);
            showToast("请使用文件管理器打开: " + folderPath);
        } catch (Exception e) {
            Log.e(TAG, "openFolder: 异常", e);
            showToast("无法打开文件夹: " + e.getMessage());
        }
    }

    /*
     * scanMediaStore: 触发 MediaStore 重新扫描指定文件夹
     * 当文件被外部工具修改/删除后，MediaStore 缓存可能过时。
     * 主动扫描可让 DocumentsProvider 获取最新状态。
     * @param folder 需要重新扫描的文件夹
     */
    private void scanMediaStore(File folder) {
        try {
            // 扫描文件夹本身（触发重新索引目录下所有文件）
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.KITKAT) {
                android.media.MediaScannerConnection.scanFile(
                        requireContext(),
                        new String[]{folder.getAbsolutePath()},
                        null,
                        null);
            }
            
            // 额外扫描文件夹下所有现有文件
            File[] files = folder.listFiles();
            if (files != null && files.length > 0) {
                String[] paths = new String[files.length];
                for (int i = 0; i < files.length; i++) {
                    paths[i] = files[i].getAbsolutePath();
                }
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.KITKAT) {
                    android.media.MediaScannerConnection.scanFile(
                            requireContext(),
                            paths,
                            null,
                            null);
                }
            }
            
            Log.d(TAG, "scanMediaStore: 触发扫描完成，folder=" + folder.getAbsolutePath());
        } catch (Exception e) {
            Log.w(TAG, "scanMediaStore: 触发扫描失败", e);
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
        Log.i(TAG_FF_MEDIA, "Download: 开始下载任务，formatId=" + task.getFormatId()
                + "，title=" + task.getTitle());
        Context ctx = requireContext();

        // ========== 检查 FFmpeg 可用性，分离流需要 FFmpeg 合并音视频 ==========
        // 如果 FFmpeg 未就绪，先触发同步下载（阻塞当前后台线程等待完成）
        FFmpegManager ffManager = FFmpegManager.getInstance();
        if (!ffManager.isFfmpegAvailable()) {
            Log.i(TAG_FF_MEDIA, "Download: FFmpeg 未就绪，开始同步下载...");
            boolean ffmpegDownloaded = ffManager.downloadFfmpegSync();
            if (ffmpegDownloaded) {
                Log.i(TAG_FF_MEDIA, "Download: FFmpeg 下载成功，路径=" + ffManager.getFfmpegPath());
            } else {
                Log.w(TAG_FF_MEDIA, "Download: FFmpeg 下载失败，分离流视频将无音频");
            }
        } else {
            Log.i(TAG_FF_MEDIA, "Download: FFmpeg 已就绪，路径=" + ffManager.getFfmpegPath());
        }

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

        // 根据视频链接自动检测所属平台，传入对应平台的 Cookie 文件路径
        // 不同平台使用独立的 Cookie 文件（格式：{platform}_cookies.txt）
        String cookieFile = PlatformCookieManager
                .getCookieFilePath(ctx, task.getUrl());

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
                        String filepath = result.optString("filepath", "");
                        taskList.get(idx).setProgress(100);

                        // 检查 FFmpeg 警告（音视频合并失败警告）
                        boolean ffmpegWarning = result.optBoolean("ffmpeg_warning", false);
                        String ffmpegWarningMessage = result.optString("ffmpeg_warning_message", "");
                        if (ffmpegWarning && !ffmpegWarningMessage.isEmpty()) {
                            Log.w(TAG, "executeDownload: FFmpeg警告 - " + ffmpegWarningMessage);
                            Log.w(TAG_FF_MEDIA, "FFmpeg警告: " + ffmpegWarningMessage);
                            // 显示橙色警告浮层
                            showFfmpegWarningToast(ffmpegWarningMessage);
                        }

                        // 获取文件大小
                        Log.d(TAG, "executeDownload: 下载成功，filepath=" + filepath);
                        Log.i(TAG_FF_MEDIA, "Download: 下载成功，filepath=" + filepath
                                + "，ffmpegWarning=" + ffmpegWarning);
                        if (!filepath.isEmpty()) {
                            File f = new File(filepath);
                            if (f.exists()) {
                                taskList.get(idx).setFileSize(f.length());
                                Log.d(TAG, "executeDownload: 文件存在，大小=" + f.length());
                            } else {
                                Log.w(TAG, "executeDownload: 文件不存在，filepath=" + filepath);
                            }
                        } else {
                            Log.w(TAG, "executeDownload: filepath为空");
                        }

                        // 复制到公共目录，并获取公共目录中的文件路径
                        File sourceFile = new File(filepath);
                        String publicFilePath = copyToPublicDownloads(ctx, sourceFile);
                        
                        // 如果复制成功，更新 filepath 为公共目录路径（用于打开文件夹和显示）
                        if (publicFilePath != null && !publicFilePath.isEmpty()) {
                            Log.d(TAG, "executeDownload: 复制到公共目录成功，publicFilePath=" + publicFilePath);
                            taskList.get(idx).setFilepath(publicFilePath);
                        } else {
                            // 复制失败，使用原始路径
                            taskList.get(idx).setFilepath(filepath);
                        }

                    } else if ("paused".equals(status)) {
                        // 用户取消下载
                        taskList.get(idx).setStatus(
                                DownloadTask.STATUS_FAILED);
                        taskList.get(idx).setError("已取消");
                        Log.i(TAG_FF_MEDIA, "Download: 下载已被用户取消");

                    } else {
                        // 下载失败
                        taskList.get(idx).setStatus(
                                DownloadTask.STATUS_FAILED);
                        taskList.get(idx).setError(
                                result.optString("error", "未知错误"));
                        Log.e(TAG_FF_MEDIA, "Download: 下载失败，error="
                                + result.optString("error", "未知错误"));
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
                if (isDone[0]) {
                    Log.d(TAG, "startProgressPolling: 下载已完成，停止轮询，taskId=" + task.getId());
                    return; // 下载已完成，停止轮询
                }

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

                        String progressContent = sb.toString();
                        Log.d(TAG, "startProgressPolling: 读取进度文件成功，taskId=" + task.getId()
                                + ", content=" + progressContent);

                        JSONObject progressData = new JSONObject(progressContent);
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

                        Log.d(TAG, "startProgressPolling: 解析进度数据，taskId=" + task.getId()
                                + ", percent=" + percent
                                + ", downloadedBytes=" + downloadedBytes
                                + ", totalBytes=" + totalBytes
                                + ", speed=" + speedBytes
                                + ", status=" + progStatus);

                        // 更新主线程 UI
                        mainHandler.post(new Runnable() {
                            @Override
                            public void run() {
                                int idx = findTaskIndexById(task.getId());
                                if (idx >= 0) {
                                    Log.d(TAG, "startProgressPolling: 更新UI进度，taskId=" + task.getId()
                                            + ", idx=" + idx
                                            + ", newProgress=" + percent);
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
                                    Log.d(TAG, "startProgressPolling: UI更新完成，taskId=" + task.getId()
                                            + ", statusText=" + statusText);
                                } else {
                                    Log.w(TAG, "startProgressPolling: 未找到任务，taskId=" + task.getId());
                                }
                            }
                        });

                    } catch (Exception e) {
                        Log.e(TAG, "startProgressPolling: 读取进度文件失败，taskId=" + task.getId(), e);
                    }
                } else {
                    Log.d(TAG, "startProgressPolling: 进度文件不存在，taskId=" + task.getId()
                            + ", path=" + progressFilePath);
                }

                // 继续轮询
                if (!isDone[0]) {
                    pollingHandler.postDelayed(this, 500);
                }
            }
        };

        // 启动轮询
        Log.d(TAG, "startProgressPolling: 启动进度轮询，taskId=" + task.getId()
                + ", progressFilePath=" + progressFilePath);
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
     * @return 复制后的公共目录文件路径，失败返回 null
     */
    private String copyToPublicDownloads(Context context, File sourceFile) {
        Log.d(TAG, "copyToPublicDownloads: sourceFile="
                + (sourceFile == null ? "null" : sourceFile.getAbsolutePath()
                + ", exists=" + sourceFile.exists()));
        if (sourceFile == null || !sourceFile.exists()) {
            Log.w(TAG, "copyToPublicDownloads: 源文件不存在，跳过复制");
            return null;
        }

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
                
                // 构建公共目录文件路径并返回
                String publicPath = Environment.getExternalStoragePublicDirectory(
                        Environment.DIRECTORY_DOWNLOADS).getAbsolutePath() 
                        + "/GrayVideoDL/" + fileName;
                Log.d(TAG, "copyToPublicDownloads: 复制成功，publicPath=" + publicPath);
                return publicPath;
            }
        } catch (Exception e) {
            Log.e(TAG, "copyToPublicDownloads: 复制失败", e);
        }
        return null;
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

    /*
     * showFfmpegWarningToast: 显示 FFmpeg 音视频合并失败警告浮层。
     * 当 FFmpeg 不可用导致无法合并分离的视频流和音频流时调用，
     * 提示用户下载的视频可能没有音频。
     * 警告浮层会在 6 秒后自动消失，样式与平台警告浮层一致。
     *
     * @param warningMessage 警告消息内容
     */
    private void showFfmpegWarningToast(String warningMessage) {
        // 设置警告文本
        tvFfmpegWarningText.setText(warningMessage);

        // 显示浮层并执行渐显动画
        layoutFfmpegWarningToast.setVisibility(View.VISIBLE);
        layoutFfmpegWarningToast.setAlpha(0f);

        // 渐显动画（400ms）
        AlphaAnimation fadeIn = new AlphaAnimation(0f, 1f);
        fadeIn.setDuration(400);
        layoutFfmpegWarningToast.startAnimation(fadeIn);
        layoutFfmpegWarningToast.setAlpha(1f);

        // 6 秒后渐隐消失
        mainHandler.postDelayed(() -> {
            AlphaAnimation fadeOut = new AlphaAnimation(1f, 0f);
            fadeOut.setDuration(600);
            fadeOut.setAnimationListener(new Animation.AnimationListener() {
                @Override public void onAnimationStart(Animation a) {}
                @Override public void onAnimationRepeat(Animation a) {}
                @Override public void onAnimationEnd(Animation a) {
                    layoutFfmpegWarningToast.setVisibility(View.GONE);
                }
            });
            layoutFfmpegWarningToast.startAnimation(fadeOut);
            layoutFfmpegWarningToast.setAlpha(0f);
        }, 6000);
    }

    /*
     * getMimeType: 根据文件名扩展名获取对应的 MIME 类型
     * 用于分享 Intent 的 setType 参数，让系统正确识别文件类型
     * @param fileName 文件名（含扩展名）
     * @return MIME 类型字符串，未知类型返回 &#42;&#47;&#42;（通用通配符）
     */
    private String getMimeType(String fileName) {
        if (fileName.endsWith(".mp4")) return "video/mp4";
        if (fileName.endsWith(".mkv")) return "video/x-matroska";
        if (fileName.endsWith(".webm")) return "video/webm";
        if (fileName.endsWith(".m4a")) return "audio/mp4";
        if (fileName.endsWith(".mp3")) return "audio/mpeg";
        if (fileName.endsWith(".aac")) return "audio/aac";
        return "*/*"; // 未知类型使用通用通配符
    }

    /*
     * getUriForFile: 通过 FileProvider 为指定文件生成 content:// URI
     * 用于跨应用文件分享，确保 Android 7.0+ 上目标应用能正常读取文件。
     * 底层使用 android.support.FILE_PROVIDER_PATHS 配置（res/xml/file_paths.xml）
     * 将文件路径映射为 content:// URI。
     * @param context 上下文
     * @param file 要分享的文件
     * @return content:// URI
     */
    private Uri getUriForFile(Context context, File file) {
        return androidx.core.content.FileProvider.getUriForFile(
                context,
                context.getPackageName() + ".fileprovider",
                file
        );
    }
}
