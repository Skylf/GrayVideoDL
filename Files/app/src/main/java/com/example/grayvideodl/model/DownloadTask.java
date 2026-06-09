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

    private String id;            // 唯一 ID
    private String url;           // 视频链接
    private String title;         // 视频标题
    private String formatId;      // 下载的格式 ID
    private String resolution;    // 分辨率描述
    private String filepath;      // 本地文件路径
    private String status;        // downloading / completed / failed
    private String error;         // 错误信息
    private long createTime;      // 创建时间戳
    private long fileSize;        // 文件大小

    public DownloadTask() {
        this.id = String.valueOf(System.currentTimeMillis());
        this.createTime = System.currentTimeMillis();
        this.status = STATUS_DOWNLOADING;
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
                    .put("status", status)
                    .put("error", error)
                    .put("create_time", createTime)
                    .put("file_size", fileSize);
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
        task.status = json.optString("status", STATUS_DOWNLOADING);
        task.error = json.optString("error", "");
        task.createTime = json.optLong("create_time", 0);
        task.fileSize = json.optLong("file_size", 0);
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
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getError() { return error; }
    public void setError(String error) { this.error = error; }
    public long getCreateTime() { return createTime; }
    public long getFileSize() { return fileSize; }
    public void setFileSize(long fileSize) { this.fileSize = fileSize; }
    public boolean isCompleted() { return STATUS_COMPLETED.equals(status); }
    public boolean isFailed() { return STATUS_FAILED.equals(status); }
}
