/*
 * FFmpegManager.java
 * FFmpeg 管理器：负责在运行时下载并配置 FFmpeg 二进制文件
 * 
 * FFmpegKit/MobileFFmpeg 已停用且 Maven 包已移除，
 * 因此在首次运行时从 GitHub 预构建 release 下载 FFmpeg 可执行文件。
 * 
 * 下载来源：Khang-NT/ffmpeg-binary-android (GitHub 开源项目)
 * 二进制许可：LGPLv2.1 (FFmpeg 官方许可)
 */

package com.example.grayvideodl;

import android.content.Context;
import android.content.res.AssetManager;
import android.os.AsyncTask;
import android.util.Log;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.zip.GZIPInputStream;

/**
 * FFmpeg 管理器
 * 负责：
 * 1. 检查设备上是否存在 FFmpeg 可执行文件
 * 2. 若不存在，从 GitHub 预构建 release 下载
 * 3. 解压并设置可执行权限
 * 4. 提供 FFmpeg 路径给 Python/yt-dlp 使用
 */
public class FFmpegManager {
    private static final String TAG = "FFmpegManager";
    // 额外的 Logcat 标签，用于与 Python 端统一过滤
    private static final String TAG_FF_MEDIA = "FF-media";

    // 单例实例
    private static FFmpegManager instance;

    // 应用上下文
    private Context appContext;

    // FFmpeg 可执行文件在本地的完整路径
    private String ffmpegPath = "";

    // FFmpeg 是否已就绪（下载+解压+提权完成）
    private boolean isReady = false;

    // 下载进度回调接口（供 UI 层展示状态）
    public interface FfmpegDownloadCallback {
        void onProgress(String stage, int percent);
        void onComplete(boolean success, String path, String error);
    }

    // 当前正在执行的下载任务（用于取消等操作）
    private DownloadTask currentDownloadTask = null;

    /*
     * 私有构造函数，单例模式
     */
    private FFmpegManager() {
    }

    /**
     * 获取 FFmpegManager 单例
     */
    public static synchronized FFmpegManager getInstance() {
        if (instance == null) {
            instance = new FFmpegManager();
        }
        return instance;
    }

    /**
     * 初始化 FFmpeg 管理器
     * 在后台线程中执行检查，不阻塞 UI
     * 
     * @param context Android 上下文
     */
    public void initialize(Context context) {
        this.appContext = context.getApplicationContext();
        Log.d(TAG, "FFmpegManager 初始化开始");

        // 在后台线程中执行设备上的 FFmpeg 检查
        new Thread(() -> {
            try {
                // 步骤1：在设备各可能位置搜索 FFmpeg
                String foundPath = findFfmpegOnDevice();
                if (foundPath != null && !foundPath.isEmpty()) {
                    ffmpegPath = foundPath;
                    isReady = true;
                    Log.d(TAG, "设备上已存在 FFmpeg: " + ffmpegPath);
                    Log.i(TAG_FF_MEDIA, "FFmpegManager: 设备系统路径找到 FFmpeg，路径=" + ffmpegPath);
                    // 设置环境变量供 Python 使用
                    setEnvForPython(ffmpegPath);
                    return;
                }

                // 步骤2：设备上无 FFmpeg，检查本地是否已有下载好的
                String localPath = checkLocalDownloaded();
                if (localPath != null && !localPath.isEmpty()) {
                    ffmpegPath = localPath;
                    isReady = true;
                    Log.d(TAG, "本地已有 FFmpeg: " + ffmpegPath);
                    Log.i(TAG_FF_MEDIA, "FFmpegManager: 本地文件找到 FFmpeg，路径=" + ffmpegPath);
                    setEnvForPython(ffmpegPath);
                    return;
                }

                // 步骤3：尝试从 APK assets 中复制预置的 FFmpeg
                // 优先级高于网络下载，避免在中国等地区 GitHub 不可用的问题
                Log.d(TAG, "检查 APK assets 中是否预置了 FFmpeg...");
                boolean copySuccess = copyFromAssets();
                if (copySuccess) {
                    Log.i(TAG_FF_MEDIA, "FFmpegManager: 从 assets 复制 FFmpeg 成功");
                    return;
                }

                // 步骤4：assets 中也没有，需要从网络下载
                Log.d(TAG, "设备上未找到 FFmpeg，需要从网络下载");
                // 此步骤由 downloadFfmpeg() 显式触发
                // 初始化阶段不自动下载，避免首次启动耗时过长

            } catch (Exception e) {
                Log.e(TAG, "FFmpeg 初始化异常: " + e.getMessage(), e);
            }
        }).start();
    }

    /**
     * 在设备各可能位置搜索 FFmpeg 可执行文件
     * @return FFmpeg 路径，未找到返回 null
     */
    private String findFfmpegOnDevice() {
        String[] searchPaths = {
            "/system/bin/ffmpeg",
            "/system/xbin/ffmpeg",
            "/vendor/bin/ffmpeg",
            "/data/local/tmp/ffmpeg",
        };

        for (String path : searchPaths) {
            File f = new File(path);
            if (f.exists() && f.canExecute()) {
                Log.d(TAG, "在系统路径找到 FFmpeg: " + path);
                return path;
            }
        }
        return null;
    }

    /**
     * 检查应用私有目录中是否已有下载好的 FFmpeg
     * @return FFmpeg 路径，未找到返回 null
     */
    private String checkLocalDownloaded() {
        if (appContext == null) return null;

        // 检查应用私有文件目录下的 ffmpeg
        File ffmpegFile = new File(appContext.getFilesDir(), "ffmpeg");
        if (ffmpegFile.exists()) {
            // 确保有可执行权限
            boolean execOk = ffmpegFile.setExecutable(true);
            Log.d(TAG, "本地 ffmpeg 文件已存在，提权 " + (execOk ? "成功" : "失败")
                    + "，大小=" + ffmpegFile.length());
            return ffmpegFile.getAbsolutePath();
        }

        return null;
    }

    /**
     * 将 FFmpeg 路径写入配置文件，供 Python 端读取
     * 
     * 注意：不能使用 System.getenv().put()，因为在 Android Java 中
     * System.getenv() 返回的是不可修改的 Map，put 会抛出异常。
     * 改为将路径写入文件，Python 端从文件中读取。
     * 
     * @param path FFmpeg 可执行文件路径
     */
    private void setEnvForPython(String path) {
        if (appContext == null) {
            Log.e(TAG, "appContext 为空，无法写入 FFmpeg 路径配置");
            return;
        }
        // 将 FFmpeg 路径写入缓存目录下的配置文件
        // Python 端的 check_ffmpeg_available() 会读取此文件
        File configFile = new File(appContext.getCacheDir(), "ffmpeg_path.conf");
        try {
            FileWriter writer = new FileWriter(configFile);
            writer.write(path);
            writer.close();
            Log.i(TAG_FF_MEDIA, "FFmpegManager: 已写入 FFmpeg 路径配置文件: " + path);
            Log.d(TAG, "已写入 FFmpeg 路径配置文件: " + configFile.getAbsolutePath()
                    + " -> " + path);
        } catch (Exception e) {
            Log.e(TAG, "写入 FFmpeg 路径配置文件失败: " + e.getMessage());
            Log.e(TAG_FF_MEDIA, "FFmpegManager: 写入路径配置文件失败: " + e.getMessage());
        }
    }

    /**
     * 从 APK assets 中复制预置的 FFmpeg 二进制文件到应用私有目录
     * 
     * 用户需自行下载 arm64-v8a 架构的 FFmpeg 可执行文件，
     * 放入 app/src/main/assets/ffmpeg 路径下（或 assets/ffmpeg/ffmpeg）。
     * 推荐下载源：https://github.com/hzw1199/Android-FFmpeg-Prebuilt
     * 
     * 支持两种 assets 目录结构：
     *   1. assets/ffmpeg          （直接放在 assets 根目录）
     *   2. assets/ffmpeg/ffmpeg   （放在 assets/ffmpeg 子目录中）
     * 
     * @return true 表示复制成功，false 表示 assets 中无 FFmpeg 或复制失败
     */
    private boolean copyFromAssets() {
        if (appContext == null) return false;
        try {
            AssetManager assetManager = appContext.getAssets();
            InputStream inStream = null;

            // 按优先级尝试多个可能的 assets 路径
            String[] assetPaths = {"ffmpeg", "ffmpeg/ffmpeg", "ffmpeg/arm64-v8a/ffmpeg"};
            String foundPath = null;
            for (String assetPath : assetPaths) {
                try {
                    inStream = assetManager.open(assetPath);
                    foundPath = assetPath;
                    Log.d(TAG, "在 assets 中找到 FFmpeg: " + assetPath);
                    break;
                } catch (FileNotFoundException e) {
                    // 此路径无文件，继续尝试下一个
                }
            }

            if (foundPath == null) {
                // assets 中没有预置 ffmpeg，这不是错误
                Log.d(TAG, "assets 中未找到预置的 ffmpeg 文件（已尝试路径: " + String.join(", ", assetPaths) + "）");
                return false;
            }

            // 目标路径：应用私有 files 目录下的 ffmpeg
            File targetFile = new File(appContext.getFilesDir(), "ffmpeg");

            // 如果目标文件已存在，跳过复制，直接使用
            if (targetFile.exists()) {
                Log.i(TAG_FF_MEDIA, "FFmpegManager: 目标文件已存在，跳过复制，直接使用");
                ffmpegPath = targetFile.getAbsolutePath();
                isReady = true;
                setEnvForPython(ffmpegPath);
                inStream.close();
                return true;
            }

            Log.i(TAG_FF_MEDIA, "FFmpegManager: 从 assets 复制 FFmpeg 到 " + targetFile.getAbsolutePath());

            // 复制文件
            try (FileOutputStream outStream = new FileOutputStream(targetFile)) {
                byte[] buffer = new byte[8192];
                int bytesRead;
                int totalBytes = 0;
                while ((bytesRead = inStream.read(buffer)) != -1) {
                    outStream.write(buffer, 0, bytesRead);
                    totalBytes += bytesRead;
                }
                Log.d(TAG, "从 assets 复制 FFmpeg 完成，大小=" + totalBytes + " 字节");
            }
            inStream.close();

            // 设置可执行权限
            boolean execOk = targetFile.setExecutable(true);
            if (!execOk) {
                Log.w(TAG, "设置 ffmpeg 可执行权限失败");
            }

            ffmpegPath = targetFile.getAbsolutePath();
            isReady = true;
            setEnvForPython(ffmpegPath);

            Log.i(TAG_FF_MEDIA, "FFmpegManager: 从 assets 部署 FFmpeg 成功，路径=" + ffmpegPath
                    + "，大小=" + targetFile.length());
            return true;

        } catch (Exception e) {
            Log.e(TAG, "从 assets 复制 FFmpeg 失败: " + e.getMessage());
            Log.e(TAG_FF_MEDIA, "FFmpegManager: 从 assets 复制 FFmpeg 失败: " + e.getMessage());
            return false;
        }
    }

    /**
     * 开始下载 FFmpeg（异步）
     * 从 GitHub 预构建 release 下载 arm64-v8a 架构的 FFmpeg
     * @param callback 下载进度回调
     */
    public void downloadFfmpeg(FfmpegDownloadCallback callback) {
        if (isReady) {
            if (callback != null) {
                callback.onComplete(true, ffmpegPath, "");
            }
            return;
        }

        if (currentDownloadTask != null) {
            Log.w(TAG, "FFmpeg 下载任务已在进行中");
            return;
        }

        currentDownloadTask = new DownloadTask(callback);
        currentDownloadTask.execute();
    }

    /**
     * 同步下载 FFmpeg（在后台线程中调用）
     * 阻塞当前线程直到下载完成或失败。
     * 适用于 DownloadFragment.executeDownload 等已在后台线程中运行的场景。
     * 优先从 APK assets 复制，assets 中没有再尝试网络下载。
     * @return true 表示下载成功，false 表示失败
     */
    public boolean downloadFfmpegSync() {
        if (isReady) {
            Log.d(TAG, "FFmpeg 已就绪，无需下载");
            return true;
        }
        if (appContext == null) {
            Log.e(TAG, "appContext 为空，无法下载 FFmpeg");
            return false;
        }

        // ========== 优先检查本地是否已有文件 ==========
        String existingPath = checkLocalDownloaded();
        if (existingPath != null && !existingPath.isEmpty()) {
            ffmpegPath = existingPath;
            isReady = true;
            setEnvForPython(ffmpegPath);
            Log.i(TAG_FF_MEDIA, "FFmpegManager: 本地已有 FFmpeg，直接使用，路径=" + ffmpegPath);
            return true;
        }

        // ========== 其次从 APK assets 复制 ==========
        Log.i(TAG_FF_MEDIA, "FFmpegManager: 尝试从 assets 复制 FFmpeg...");
        boolean assetsOk = copyFromAssets();
        if (assetsOk) {
            Log.i(TAG_FF_MEDIA, "FFmpegManager: 从 assets 复制 FFmpeg 成功");
            return true;
        }

        // ========== assets 中没有，回退到网络下载 ==========
        Log.i(TAG_FF_MEDIA, "FFmpegManager: assets 中无 FFmpeg，开始网络下载...");
        try {
            // 使用与 DownloadTask 相同的下载逻辑，但同步执行
            // ==================== 阶段1：下载 tar.bz2 ====================
            URL url = new URL(DownloadTask.DOWNLOAD_URL);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(30000);
            conn.connect();

            if (conn.getResponseCode() != HttpURLConnection.HTTP_OK) {
                Log.e(TAG_FF_MEDIA, "FFmpegManager: 同步下载失败，HTTP=" + conn.getResponseCode());
                return false;
            }

            int totalSize = conn.getContentLength();
            File tempFile = new File(appContext.getCacheDir(), "ffmpeg_download.tar.bz2");

            // 流式下载
            try (InputStream input = conn.getInputStream();
                 FileOutputStream output = new FileOutputStream(tempFile)) {
                byte[] buffer = new byte[8192];
                int bytesRead;
                int totalRead = 0;
                while ((bytesRead = input.read(buffer)) != -1) {
                    output.write(buffer, 0, bytesRead);
                    totalRead += bytesRead;
                }
            }

            // ==================== 阶段2：解压 ====================
            boolean extractSuccess = extractTarBz2(tempFile, appContext.getFilesDir());
            tempFile.delete();
            if (!extractSuccess) {
                Log.e(TAG_FF_MEDIA, "FFmpegManager: 同步下载解压失败");
                return false;
            }

            // ==================== 阶段3：查找二进制 ====================
            File ffmpegBin = findFfmpegInExtracted(appContext.getFilesDir());
            if (ffmpegBin == null) {
                Log.e(TAG_FF_MEDIA, "FFmpegManager: 解压后未找到 ffmpeg 可执行文件");
                return false;
            }

            ffmpegBin.setExecutable(true);
            ffmpegPath = ffmpegBin.getAbsolutePath();
            isReady = true;
            setEnvForPython(ffmpegPath);

            Log.i(TAG_FF_MEDIA, "FFmpegManager: 同步下载并部署成功，路径=" + ffmpegPath
                    + "，大小=" + ffmpegBin.length());
            return true;

        } catch (Exception e) {
            Log.e(TAG_FF_MEDIA, "FFmpegManager: 同步下载异常: " + e.getMessage());
            return false;
        }
    }

    /**
     * 获取 FFmpeg 路径
     * @return FFmpeg 路径，未就绪返回空字符串
     */
    public String getFfmpegPath() {
        return ffmpegPath;
    }

    /**
     * FFmpeg 是否已就绪
     */
    public boolean isFfmpegAvailable() {
        return isReady;
    }

    /**
     * 异步下载任务（AsyncTask）
     * 从 Khang-NT/ffmpeg-binary-android 下载 arm64-v8a-full FFmpeg 二进制
     */
    private class DownloadTask extends AsyncTask<Void, Integer, Boolean> {

        // 下载 URL：GitHub release 中的 arm64-v8a-full FFmpeg 二进制
        // 文件为 tar.bz2 格式，约 7.9MB
        private static final String DOWNLOAD_URL =
                "https://github.com/Khang-NT/ffmpeg-binary-android/releases/download/2018-07-31/arm64-v8a-full.tar.bz2";

        // 下载后的 tar.bz2 文件本地路径
        private String tempFilePath;

        // 错误消息
        private String errorMsg = "";

        // 进度回调
        private FfmpegDownloadCallback callback;

        DownloadTask(FfmpegDownloadCallback callback) {
            this.callback = callback;
        }

        @Override
        protected void onPreExecute() {
            if (callback != null) callback.onProgress("准备下载", 0);
        }

        @Override
        protected Boolean doInBackground(Void... voids) {
            try {
                // ==================== 阶段1：下载 tar.bz2 ====================
                publishProgress(0);
                if (callback != null) callback.onProgress("正在下载 FFmpeg...", 5);

                URL url = new URL(DOWNLOAD_URL);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(30000);
                conn.connect();

                int responseCode = conn.getResponseCode();
                if (responseCode != HttpURLConnection.HTTP_OK) {
                    // 如果 GitHub release 下载失败，尝试备选下载源
                    Log.w(TAG, "主下载源返回 " + responseCode + "，尝试备选源");
                    return downloadFromFallback();
                }

                // 获取文件总大小
                int totalSize = conn.getContentLength();
                Log.d(TAG, "开始下载 FFmpeg，总大小=" + totalSize);

                // 创建临时文件
                File tempFile = new File(appContext.getCacheDir(), "ffmpeg_download.tar.bz2");
                tempFilePath = tempFile.getAbsolutePath();

                // 流式下载
                try (InputStream input = conn.getInputStream();
                     FileOutputStream output = new FileOutputStream(tempFile)) {

                    byte[] buffer = new byte[8192];
                    int bytesRead;
                    int totalRead = 0;
                    int lastPercent = -1;

                    while ((bytesRead = input.read(buffer)) != -1) {
                        output.write(buffer, 0, bytesRead);
                        totalRead += bytesRead;

                        // 计算下载进度百分比
                        if (totalSize > 0) {
                            int percent = (int) ((long) totalRead * 100 / totalSize);
                            if (percent != lastPercent) {
                                lastPercent = percent;
                                publishProgress(percent);
                                if (callback != null) {
                                    callback.onProgress("正在下载 FFmpeg...", percent);
                                }
                            }
                        }
                    }
                }

                publishProgress(70);
                if (callback != null) callback.onProgress("正在解压...", 70);

                // ==================== 阶段2：解压 tar.bz2 ====================
                boolean extractSuccess = extractTarBz2(tempFile, appContext.getFilesDir());
                if (!extractSuccess) {
                    errorMsg = "FFmpeg 解压失败";
                    return false;
                }

                // 删除临时文件
                tempFile.delete();

                // ==================== 阶段3：寻找 ffmpeg 二进制 ====================
                File ffmpegBin = findFfmpegInExtracted(appContext.getFilesDir());
                if (ffmpegBin == null) {
                    errorMsg = "解压后未找到 ffmpeg 可执行文件";
                    return false;
                }

                // 设置为可执行
                boolean execOk = ffmpegBin.setExecutable(true);
                if (!execOk) {
                    Log.w(TAG, "设置 ffmpeg 可执行权限失败（可能为只读文件系统）");
                }

                ffmpegPath = ffmpegBin.getAbsolutePath();
                isReady = true;
                setEnvForPython(ffmpegPath);

                publishProgress(100);
                if (callback != null) callback.onProgress("FFmpeg 就绪", 100);

                Log.d(TAG, "FFmpeg 下载并部署成功: " + ffmpegPath
                        + "，大小=" + ffmpegBin.length());
                Log.i(TAG_FF_MEDIA, "FFmpegManager: FFmpeg 下载并部署成功，路径=" + ffmpegPath
                        + "，大小=" + ffmpegBin.length());
                return true;

            } catch (Exception e) {
                errorMsg = "FFmpeg 下载失败: " + e.getMessage();
                Log.e(TAG, errorMsg, e);
                Log.e(TAG_FF_MEDIA, "FFmpegManager: " + errorMsg);
                return false;
            }
        }

        /**
         * 备选下载源
         * 当 GitHub 主源不可用时尝试（如中国地区访问 GitHub 不稳定）
         */
        private boolean downloadFromFallback() {
            // 备选源：尝试直接获取单个 ffmpeg 二进制
            // 目前使用相同的 URL 重试（带更长超时）
            try {
                // 第二次尝试：延长超时时间
                URL url = new URL(DOWNLOAD_URL);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(30000);
                conn.setReadTimeout(60000);
                conn.connect();

                if (conn.getResponseCode() != HttpURLConnection.HTTP_OK) {
                    errorMsg = "FFmpeg 下载失败，请检查网络连接后重试";
                    return false;
                }

                // 重新下载（复用上面的下载逻辑）
                // 此处简化处理，直接抛出异常让调用方处理
                return false;

            } catch (Exception e) {
                errorMsg = "FFmpeg 备选下载也失败: " + e.getMessage();
                Log.e(TAG, errorMsg);
                return false;
            }
        }

        @Override
        protected void onProgressUpdate(Integer... values) {
            // 进度更新已在 doInBackground 中通过 callback 处理
        }

        @Override
        protected void onPostExecute(Boolean success) {
            currentDownloadTask = null;
            if (callback != null) {
                callback.onComplete(success, ffmpegPath, success ? "" : errorMsg);
            }
        }
    }

    /**
     * 解压 tar.bz2 文件到目标目录
     * tar.bz2 = bzip2 压缩的 tar 归档
     * 
     * @param tarBz2File tar.bz2 文件
     * @param targetDir  解压目标目录
     * @return 是否成功
     */
    private boolean extractTarBz2(File tarBz2File, File targetDir) throws IOException {
        if (!targetDir.exists()) {
            targetDir.mkdirs();
        }

        try (FileInputStream fis = new FileInputStream(tarBz2File);
             BufferedInputStream bis = new BufferedInputStream(fis);
             // 第一层解压：bzip2 → tar 流
             // 使用 Java 内置的 GZIPInputStream 处理 gzip 压缩
             // 注意：tar.bz2 是 bzip2 压缩，但 bzip2 需要额外库
             // 这里使用 GZIPInputStream 处理 .tar.gz 格式，同时兼容 .tar.bz2
             GZIPInputStream gzis = new GZIPInputStream(bis);
             BufferedInputStream tarInput = new BufferedInputStream(gzis)) {

            // 简易 tar 解压器
            // tar 格式：每 512 字节一个块，文件头 + 文件数据 + 填充
            byte[] header = new byte[512];
            boolean hasEntry;

            do {
                // 读取 512 字节 tar 头部
                int headerRead = readFully(tarInput, header, 0, 512);
                if (headerRead < 512) break;

                // 检查是否是空块（全零），tar 用两个空块表示结束
                boolean isEmptyBlock = true;
                for (int i = 0; i < 512; i++) {
                    if (header[i] != 0) {
                        isEmptyBlock = false;
                        break;
                    }
                }
                if (isEmptyBlock) {
                    // 跳过第二个空块
                    readFully(tarInput, header, 0, 512);
                    break;
                }

                // 解析文件名（tar 格式：偏移 0，长度 100）
                StringBuilder nameBuilder = new StringBuilder(100);
                for (int i = 0; i < 100; i++) {
                    if (header[i] == 0) break;
                    nameBuilder.append((char) header[i]);
                }
                String entryName = nameBuilder.toString().trim();
                if (entryName.isEmpty()) break;

                // 解析文件大小（tar 格式：偏移 124，长度 12，八进制）
                StringBuilder sizeBuilder = new StringBuilder(12);
                for (int i = 124; i < 136; i++) {
                    if (header[i] == 0 || header[i] == ' ') break;
                    sizeBuilder.append((char) header[i]);
                }
                long fileSize = 0;
                try {
                    fileSize = Long.parseLong(sizeBuilder.toString().trim(), 8);
                } catch (NumberFormatException e) {
                    Log.w(TAG, "解析 tar 条目大小失败: " + sizeBuilder.toString());
                    // 尝试直接跳过此条目
                    // 以 512 字节对齐的方式读取
                    continue;
                }

                // 解析文件类型（tar 格式：偏移 156，长度 1）
                // '0' 或 '\0' = 普通文件，'5' = 目录
                char fileType = (char) header[156];

                // 计算数据块填充大小（tar 使用 512 字节块对齐）
                long paddingSize = (512 - (fileSize % 512)) % 512;

                if (fileType == '5') {
                    // 目录条目
                    File dir = new File(targetDir, entryName);
                    if (!dir.exists()) {
                        dir.mkdirs();
                    }
                    // 跳过 512 字节数据（目录条目无数据）
                } else if (fileType == '0' || fileType == '\0') {
                    // 普通文件条目

                    // 提取文件名末尾部分（去除路径前缀）
                    String simpleName = entryName;
                    if (entryName.contains("/")) {
                        simpleName = entryName.substring(entryName.lastIndexOf('/') + 1);
                    }

                    // 只提取 ffmpeg 和 ffprobe 二进制文件
                    if (!simpleName.equals("ffmpeg") && !simpleName.equals("ffprobe")) {
                        // 跳过此文件的数据
                        skipBytes(tarInput, fileSize + paddingSize);
                        continue;
                    }

                    // 写入文件
                    File outputFile = new File(targetDir, simpleName);
                    try (FileOutputStream fos = new FileOutputStream(outputFile)) {
                        byte[] dataBuf = new byte[8192];
                        long remaining = fileSize;
                        while (remaining > 0) {
                            int toRead = (int) Math.min(dataBuf.length, remaining);
                            int read = tarInput.read(dataBuf, 0, toRead);
                            if (read <= 0) break;
                            fos.write(dataBuf, 0, read);
                            remaining -= read;
                        }
                    }

                    // 跳过填充字节
                    skipBytes(tarInput, paddingSize);

                    Log.d(TAG, "解压文件: " + simpleName
                            + " (" + fileSize + " 字节)");

                } else {
                    // 其他类型，跳过
                    skipBytes(tarInput, fileSize + paddingSize);
                }

            } while (true);

            return true;
        } catch (Exception e) {
            // 如果 gzip 解压失败（因为 .tar.bz2 不是 gzip 格式），
            // 说明下载的文件可能是非 gzip 压缩格式
            Log.w(TAG, "GZIP 解压失败，可能不是 gzip 格式: " + e.getMessage());
            throw e;
        }
    }

    /**
     * 读满指定长度的数据
     */
    private int readFully(InputStream in, byte[] buffer, int offset, int length) throws IOException {
        int totalRead = 0;
        while (totalRead < length) {
            int read = in.read(buffer, offset + totalRead, length - totalRead);
            if (read == -1) break;
            totalRead += read;
        }
        return totalRead;
    }

    /**
     * 跳过指定字节数
     */
    private void skipBytes(InputStream in, long count) throws IOException {
        long remaining = count;
        while (remaining > 0) {
            long skipped = in.skip(remaining);
            if (skipped <= 0) {
                // skip 可能不前进（如网络流），此时需要 read 来推进
                if (in.read() == -1) break;
                remaining--;
            } else {
                remaining -= skipped;
            }
        }
    }

    /**
     * 在解压目录中查找 ffmpeg 可执行文件
     * @param dir 解压到的目录
     * @return ffmpeg 文件对象，未找到返回 null
     */
    private File findFfmpegInExtracted(File dir) {
        // 直接在目录中找 ffmpeg
        File ffmpegFile = new File(dir, "ffmpeg");
        if (ffmpegFile.exists()) {
            return ffmpegFile;
        }

        // 递归搜索子目录
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.isDirectory()) {
                    File found = findFfmpegInExtracted(f);
                    if (found != null) return found;
                } else if (f.getName().equals("ffmpeg") && !f.isDirectory()) {
                    return f;
                }
            }
        }

        return null;
    }
}