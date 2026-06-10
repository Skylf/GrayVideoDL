/*
 * DownloadTask.java
 * 下载任务数据模型，记录每个下载的状态、路径、进度等信息。
 * 下载任务列表以 JSON 格式保存到应用内部存储。
 */

package com.example.grayvideodl.model;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class DownloadTask {

    public static final String STATUS_DOWNLOADING = "downloading";
    public static final String STATUS_COMPLETED = "completed";
    public static final String STATUS_FAILED = "failed";
    public static final String STATUS_PAUSED = "paused";

    private String id;            // 唯一 ID
    private String url;           // 视频链接
    private String title;         // 视频标题
    private String formatId;      // 下载的格式 ID
    private String resolution;    // 分辨率描述
    private String filepath;      // 本地文件路径（完整文件路径）
    private String downloadDir;   // 下载目录路径（用于显示和打开文件夹）
    private String status;        // downloading / completed / failed / paused
    private String error;         // 错误信息
    private long createTime;      // 创建时间戳
    private long fileSize;        // 文件大小
    private long downloadedBytes;         // 已下载字节数（实时更新）
    private long totalBytesForProgress;    // 总字节数（从进度文件实时更新）
    private double speed;                  // 下载速度（字节/秒，仅运行时，不持久化）
    private int progress;                  // 下载进度 0-100

    public DownloadTask() {
        this.id = String.valueOf(System.currentTimeMillis());
        this.createTime = System.currentTimeMillis();
        this.status = STATUS_DOWNLOADING;
        this.progress = 0;
    }

    // ===== 序列化 =====

    public JSONObject toJson() {
        try {
            return new JSONObject()
                    .put("id", id)
                    .put("url", url)
                    .put("title", title)
                    .put("format_id", formatId)
                    .put("resolution", resolution)
                    .put("filepath", filepath)
                    .put("download_dir", downloadDir)
                    .put("status", status)
                    .put("error", error)
                    .put("create_time", createTime)
                    .put("file_size", fileSize)
                    .put("downloaded_bytes", downloadedBytes)
                    .put("total_bytes_progress", totalBytesForProgress)
                    .put("progress", progress);
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    public static DownloadTask fromJson(JSONObject json) {
        DownloadTask task = new DownloadTask();
        task.id = json.optString("id", "");
        task.url = json.optString("url", "");
        task.title = json.optString("title", "");
        task.formatId = json.optString("format_id", "");
        task.resolution = json.optString("resolution", "");
        task.filepath = json.optString("filepath", "");
        task.downloadDir = json.optString("download_dir", "");
        task.status = json.optString("status", STATUS_DOWNLOADING);
        task.error = json.optString("error", "");
        task.createTime = json.optLong("create_time", 0);
        task.fileSize = json.optLong("file_size", 0);
        task.downloadedBytes = json.optLong("downloaded_bytes", 0);
        task.totalBytesForProgress = json.optLong("total_bytes_progress", 0);
        task.progress = json.optInt("progress", 0);
        return task;
    }

    // ===== 持久化 =====

    private static File getTasksFile(android.content.Context context) {
        return new File(context.getFilesDir(), "download_tasks.json");
    }

    public static void saveTaskList(android.content.Context context,
                                     List<DownloadTask> tasks) {
        try {
            JSONArray arr = new JSONArray();
            for (DownloadTask t : tasks) {
                arr.put(t.toJson());
            }
            File file = getTasksFile(context);
            java.io.FileOutputStream fos = new java.io.FileOutputStream(file);
            fos.write(arr.toString(2).getBytes(StandardCharsets.UTF_8));
            fos.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static List<DownloadTask> loadTaskList(
            android.content.Context context) {
        List<DownloadTask> tasks = new ArrayList<>();
        try {
            File file = getTasksFile(context);
            if (!file.exists()) return tasks;
            String content = new String(
                    java.nio.file.Files.readAllBytes(file.toPath()),
                    StandardCharsets.UTF_8);
            JSONArray arr = new JSONArray(content);
            for (int i = 0; i < arr.length(); i++) {
                tasks.add(fromJson(arr.getJSONObject(i)));
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return tasks;
    }

    // ===== 格式化 =====

    public String getFileSizeText() {
        if (fileSize <= 0) return "未知";
        if (fileSize < 1024 * 1024) {
            return String.format("%.0f KB", fileSize / 1024.0);
        }
        return String.format("%.1f MB", fileSize / (1024.0 * 1024.0));
    }

    public String getCreateTimeText() {
        SimpleDateFormat sdf = new SimpleDateFormat(
                "MM-dd HH:mm", Locale.getDefault());
        return sdf.format(new Date(createTime));
    }

    // ===== Getter / Setter =====

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getFormatId() { return formatId; }
    public void setFormatId(String formatId) { this.formatId = formatId; }
    public String getResolution() { return resolution; }
    public void setResolution(String resolution) { this.resolution = resolution; }
    public String getFilepath() { return filepath; }
    public void setFilepath(String filepath) { this.filepath = filepath; }
    public String getDownloadDir() { return downloadDir; }
    public void setDownloadDir(String downloadDir) { this.downloadDir = downloadDir; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getError() { return error; }
    public void setError(String error) { this.error = error; }
    public long getCreateTime() { return createTime; }
    public long getFileSize() { return fileSize; }
    public void setFileSize(long fileSize) { this.fileSize = fileSize; }
    public long getDownloadedBytes() { return downloadedBytes; }
    public void setDownloadedBytes(long downloadedBytes) { this.downloadedBytes = downloadedBytes; }
    public long getTotalBytesForProgress() { return totalBytesForProgress; }
    public void setTotalBytesForProgress(long totalBytesForProgress) { this.totalBytesForProgress = totalBytesForProgress; }
    public double getSpeed() { return speed; }
    public void setSpeed(double speed) { this.speed = speed; }
    public int getProgress() { return progress; }
    public void setProgress(int progress) { this.progress = progress; }
    public boolean isCompleted() { return STATUS_COMPLETED.equals(status); }
    public boolean isFailed() { return STATUS_FAILED.equals(status); }
    public boolean isPaused() { return STATUS_PAUSED.equals(status); }
    public boolean isDownloading() { return STATUS_DOWNLOADING.equals(status); }

    /*
     * getStatusText: 获取状态文本描述，用于 UI 展示
     * 下载中显示百分比，已完成/失败/暂停显示对应文字
     */
    public String getStatusText() {
        switch (status) {
            case STATUS_DOWNLOADING:
                return "下载中 " + progress + "%";
            case STATUS_COMPLETED:
                return "已完成";
            case STATUS_FAILED:
                return "失败: " + (error != null ? error : "未知错误");
            case STATUS_PAUSED:
                return "已暂停";
            default:
                return status;
        }
    }

    /*
     * getCancelFileName: 获取此任务的取消标志文件名
     * 用于 Python 检测是否应取消下载
     */
    public String getCancelFileName() {
        return "cancel_" + id + ".flag";
    }

    /*
     * getProgressFileName: 获取此任务的进度文件名
     * 用于 Python 写入进度数据，Java 读取
     */
    public String getProgressFileName() {
        return "progress_" + id + ".json";
    }

    /*
     * getDownloadPathDisplay: 获取下载路径显示文本
     * 如果有完整的文件路径则显示文件所在目录，否则显示下载目录
     */
    public String getDownloadPathDisplay() {
        if (filepath != null && !filepath.isEmpty()) {
            // 显示文件所在的目录
            File f = new File(filepath);
            String dir = f.getParent();
            if (dir != null) return dir;
        }
        // 回退到下载目录
        return downloadDir != null ? downloadDir : "";
    }

    /*
     * getFolderToOpen: 获取要打开的文件夹路径
     * 优先用文件所在目录，没有则用下载目录
     */
    public String getFolderToOpen() {
        if (filepath != null && !filepath.isEmpty()) {
            File f = new File(filepath);
            String dir = f.getParent();
            if (dir != null && new File(dir).exists()) return dir;
        }
        if (downloadDir != null && !downloadDir.isEmpty()) {
            if (new File(downloadDir).exists()) return downloadDir;
        }
        return null;
    }
}
