/*
 * UpdateManager.java
 * 应用内更新管理器：通过 Gitee Releases 检查新版本、下载 APK、安装 APK。
 * 流程：下载 ZIP 压缩包 → 解压得到 APK → 校验完整 → 安装。
 * Gitee 国内访问速度快且稳定，无需反向代理。
 * logcat 过滤标签：adb logcat -s Update
 */

package com.example.grayvideodl;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

/*
 * UpdateManager: 管理应用更新的核心类
 * 提供检查更新→下载ZIP→解压→校验APK→安装 全流程。
 * 所有日志使用 "Update" 标签，可通过 adb logcat -s Update 过滤
 *
 * 使用说明：
 *   1. 在 Gitee 上创建公开仓库用于发布 Release
 *   2. 修改下方 GITEE_USER 和 GITEE_REPO 为实际值
 *   3. 每次发布新版本时，将 APK 压缩为 update.zip，上传到 Gitee Release
 */
public class UpdateManager {

    // logcat 过滤标签，用于调试更新相关功能
    // 使用方法：adb logcat -s Update
    private static final String TAG = "Update";

    // ========== 配置区域（修改为你的 Gitee 信息） ==========
    // Gitee 用户名
    private static final String GITEE_USER = "ABCWBZD";
    // Gitee 仓库名（用于发布 Release）
    private static final String GITEE_REPO = "gray-video-dl-update";
    // ----------------------------------------------------

    // Gitee API 基础地址
    private static final String GITEE_API_BASE = "https://gitee.com/api/v5/repos/";
    // Gitee 下载基础地址
    private static final String GITEE_DOWNLOAD_BASE = "https://gitee.com/";

    // 默认上传文件名（在 Gitee Release 中上传的 ZIP 压缩包）
    // 内含 APK 文件下载到手机后解压使用
    private static final String DEFAULT_ZIP_NAME = "update.zip";

    // 应用上下文
    private Context context;
    // 更新检查回调
    private UpdateListener updateListener;
    // 下载回调
    private DownloadCallback downloadCallback;
    // 主线程 Handler，用于回调到 UI 线程
    private Handler mainHandler;

    /*
     * UpdateListener: 检查更新结果回调接口
     */
    public interface UpdateListener {
        /*
         * onNewVersionFound: 发现新版本
         * @param versionName  新版本号（如 "0.8C"）
         * @param releaseNotes 发布说明
         * @param apkUrl       APK 下载地址
         */
        void onNewVersionFound(String versionName, String releaseNotes, String apkUrl);

        /*
         * onNoNewVersion: 已是最新版本
         */
        void onNoNewVersion();

        /*
         * onCheckError: 检查更新失败
         * @param error 错误信息
         */
        void onCheckError(String error);
    }

    /*
     * DownloadCallback: 下载进度与结果回调接口
     */
    public interface DownloadCallback {
        /*
         * PROGRESS_UNKNOWN: 当服务器未返回 Content-Length 时，传入此值表示进度未知
         */
        int PROGRESS_UNKNOWN = -1;

        /*
         * onProgress: 下载进度通知
         * @param percent 下载百分比（0~100），或 PROGRESS_UNKNOWN（-1）表示进度未知
         */
        void onProgress(int percent);

        /*
         * onSuccess: 下载成功
         * @param apkFile 下载完成的 APK 文件
         */
        void onSuccess(File apkFile);

        /*
         * onFailure: 下载失败
         * @param error 错误信息
         */
        void onFailure(String error);
    }

    /*
     * 构造函数
     * @param context 应用上下文（建议传入 Activity 或 Application 级别）
     */
    public UpdateManager(Context context) {
        // 使用 applicationContext 避免 Activity 泄漏
        this.context = context.getApplicationContext();
        this.mainHandler = new Handler(Looper.getMainLooper());
    }

    /*
     * checkUpdate: 检查更新（异步）
     * 在后台线程请求 Gitee Releases API，获取最新版本信息并与本地版本比较。
     * Gitee API 文档：https://gitee.com/api/v5/swagger
     * @param listener 检查结果回调
     */
    public void checkUpdate(UpdateListener listener) {
        this.updateListener = listener;

        new Thread(() -> {
            try {
                //
                // 调用 Gitee API 获取最新 Release
                // API: GET /repos/{owner}/{repo}/releases/latest
                // 返回单个 Release 的 JSONObject
                //
                String apiUrl = GITEE_API_BASE + GITEE_USER + "/" + GITEE_REPO + "/releases/latest";
                Log.d(TAG, "检查更新: " + apiUrl);
                String response = httpGet(apiUrl);

                // 解析返回的 JSONObject（latest Release）
                JSONObject json = new JSONObject(response);
                String tagName = json.getString("tag_name"); // 如 "v0.8C"
                String releaseNotes = json.optString("body", "");

                // 从 assets 中获取 ZIP 下载链接（上传到 Gitee Release 的是 ZIP 压缩包）
                // ZIP 内含 APK 文件，下载后解压安装
                String apkUrl = null;
                if (json.has("assets")) {
                    JSONArray assets = json.getJSONArray("assets");
                    for (int i = 0; i < assets.length(); i++) {
                        JSONObject asset = assets.getJSONObject(i);
                        // ZIP 压缩包文件名以 .zip 结尾为准（如 update.zip）
                        if (asset.getString("name").endsWith(".zip")) {
                            apkUrl = asset.getString("browser_download_url");
                            break;
                        }
                    }
                }

                // 如果没有 asset，根据 tag 构造默认 ZIP 下载链接
                if (apkUrl == null) {
                    apkUrl = GITEE_DOWNLOAD_BASE + GITEE_USER + "/" + GITEE_REPO
                            + "/releases/download/" + tagName + "/" + DEFAULT_ZIP_NAME;
                }

                // 对比版本号
                final String remoteVersion = tagName.startsWith("v") ? tagName.substring(1) : tagName;
                // 通过 PackageManager 运行时读取本地版本号，避免依赖 Gradle 编译期生成的 BuildConfig
                String localVersion = "0.0";
                try {
                    localVersion = context.getPackageManager()
                            .getPackageInfo(context.getPackageName(), 0).versionName;
                } catch (Exception e) {
                    Log.w(TAG, "读取本地版本号失败", e);
                }
                if (localVersion == null) localVersion = "0.0";

                // 使用自定义版本比较
                boolean hasNewVersion = compareVersions(remoteVersion, localVersion) > 0;

                if (hasNewVersion) {
                    Log.d(TAG, "发现新版本: " + remoteVersion + " (当前: " + localVersion + ")");
                    final String finalApkUrl = apkUrl;
                    final String finalReleaseNotes = releaseNotes;
                    mainHandler.post(() -> {
                        if (updateListener != null) {
                            updateListener.onNewVersionFound(remoteVersion, finalReleaseNotes, finalApkUrl);
                        }
                    });
                } else {
                    Log.d(TAG, "已是最新版本: " + localVersion);
                    mainHandler.post(() -> {
                        if (updateListener != null) {
                            updateListener.onNoNewVersion();
                        }
                    });
                }

            } catch (Exception e) {
                Log.e(TAG, "检查更新失败: " + e.getMessage(), e);
                final String errorMsg = e.getMessage() != null ? e.getMessage() : "未知错误";
                mainHandler.post(() -> {
                    if (updateListener != null) {
                        updateListener.onCheckError(errorMsg);
                    }
                });
            }

        }).start();
    }

    /*
     * downloadApk: 下载更新 ZIP 压缩包并解压（异步）
     * 流程：从 Gitee 下载 update.zip → 解压得到 APK → 校验完整性 → 通知回调。
     *
     * APK 保存到应用外部文件目录（getExternalFilesDir/Download/），
     * 安装时通过 external-files-path FileProvider 共享给 Package Installer。
     * 外部文件目录兼容性和安全性优于内部 cache 目录，避免部分机型安装失败问题。
     *
     * @param apkUrl   ZIP 包下载地址（来自 checkUpdate 回调）
     * @param callback 下载回调
     */
    public void downloadApk(String apkUrl, DownloadCallback callback) {
        this.downloadCallback = callback;

        new Thread(() -> {
            try {
                Log.d(TAG, "开始下载 ZIP: " + apkUrl);

                URL url = new URL(apkUrl);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(30000);
                conn.setRequestProperty("User-Agent", "Mozilla/5.0");
                conn.connect();

                int responseCode = conn.getResponseCode();
                String contentType = conn.getContentType();
                int contentLength = conn.getContentLength();
                Log.d(TAG, "下载响应: code=" + responseCode
                        + ", type=" + contentType
                        + ", length=" + contentLength);

                if (responseCode != HttpURLConnection.HTTP_OK) {
                    throw new Exception("HTTP " + responseCode + ": " + conn.getResponseMessage());
                }

                //
                // 步骤1：下载 ZIP 到缓存目录（临时存放，解压后删除）
                //
                File zipFile = new File(context.getCacheDir(), "update.zip");
                if (zipFile.exists()) zipFile.delete();

                try (InputStream in = conn.getInputStream();
                     FileOutputStream out = new FileOutputStream(zipFile)) {

                    byte[] buffer = new byte[8192];
                    int totalRead = 0;
                    int bytesRead;
                    while ((bytesRead = in.read(buffer)) != -1) {
                        out.write(buffer, 0, bytesRead);
                        totalRead += bytesRead;
                        final int progress;
                        if (contentLength > 0) {
                            progress = (int) ((long) totalRead * 100 / contentLength);
                        } else {
                            progress = DownloadCallback.PROGRESS_UNKNOWN;
                        }
                        final int reportedProgress = progress;
                        mainHandler.post(() -> {
                            if (downloadCallback != null) {
                                downloadCallback.onProgress(reportedProgress);
                            }
                        });
                    }
                }
                conn.disconnect();

                long zipSize = zipFile.length();
                Log.d(TAG, "ZIP 下载完成: " + zipSize + " bytes");

                if (zipSize < 512 * 1024) {
                    throw new Exception("下载的 ZIP 包过小 (" + zipSize + " bytes)");
                }

                //
                // 步骤2：解压 ZIP，提取 APK 到外部文件目录
                // 路径: /storage/emulated/0/Android/data/com.example.grayvideodl/files/Download/update.apk
                // 使用 external-files-path FileProvider 共享，避免部分机型 cache-path 兼容性问题
                //
                File destDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
                if (destDir == null) {
                    // 外部存储不可用时，回退到内部 files 目录
                    destDir = context.getFilesDir();
                    Log.w(TAG, "外部文件目录不可用，回退到内部: " + destDir);
                }
                destDir.mkdirs();

                File apkFile = extractApkFromZip(zipFile, destDir);
                Log.d(TAG, "APK 已解压到: " + apkFile.getAbsolutePath()
                        + " (大小: " + apkFile.length() + " bytes)");

                //
                // 步骤3：校验 APK 完整性
                //
                long apkSize = apkFile.length();
                if (apkSize < 1024 * 1024) {
                    Log.e(TAG, "APK 文件过小: " + apkSize + " bytes");
                    throw new Exception("解压后的 APK 文件大小异常 (" + apkSize + " bytes)");
                }

                if (!isValidApk(apkFile)) {
                    Log.e(TAG, "APK 不是有效的 ZIP 格式");
                    throw new Exception("APK 文件损坏，请重试");
                }
                Log.d(TAG, "APK 完整性校验通过");

                // 删除临时 ZIP
                zipFile.delete();

                final File resultFile = apkFile;
                mainHandler.post(() -> {
                    if (downloadCallback != null) {
                        downloadCallback.onSuccess(resultFile);
                    }
                });

            } catch (Exception e) {
                Log.e(TAG, "下载更新失败: " + e.getMessage());
                final String errorMsg = e.getMessage() != null ? e.getMessage() : "未知错误";
                mainHandler.post(() -> {
                    if (downloadCallback != null) {
                        downloadCallback.onFailure(errorMsg);
                    }
                });
            }

        }).start();
    }

    /*
     * installApk: 安装 APK
     * 适配 Android 7.0+（FileProvider）和 Android 8.0+（未知来源权限）。
     * 使用 ACTION_INSTALL_PACKAGE（安装专用 Intent，而非通用的 ACTION_VIEW），
     * 部分国产 ROM（如 ColorOS）对其支持更好。
     * APK 文件应保存在 getExternalFilesDir() 路径，安装时通过 FileProvider
     * 以 content:// URI 共享给系统 Package Installer。
     * @param apkFile 已下载并解压校验完成的 APK 文件
     */
    public void installApk(File apkFile) {
        if (!apkFile.exists()) {
            Log.e(TAG, "安装失败：APK 文件不存在");
            return;
        }

        long fileSize = apkFile.length();
        String filePath = apkFile.getAbsolutePath();
        Log.d(TAG, "准备安装 APK: " + filePath + " (大小: " + fileSize + " bytes)");

        // Android 8.0+（minSdk=29，条件始终满足）检查"允许安装未知来源应用"权限
        // minSdk=29 >= O(26)，此检查始终执行
        if (!context.getPackageManager().canRequestPackageInstalls()) {
            Log.w(TAG, "未开启未知来源应用安装权限，跳转设置");
            Intent intent = new Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES);
            intent.setData(Uri.parse("package:" + context.getPackageName()));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
            Toast.makeText(context, "请允许安装未知来源应用后再试", Toast.LENGTH_LONG).show();
            return;
        }

        //
        // 构造安装 Intent：使用 ACTION_INSTALL_PACKAGE（安装专用 API）
        // 此 Intent 自 Android 4.0（API 14）起可用，
        // 部分国产 ROM（ColorOS/MIUI）对此 Intent 的兼容性优于 ACTION_VIEW。
        //
        Uri apkUri;

        // minSdk=29 >= N(24)，始终使用 FileProvider 共享文件
        // 使用 FileProvider 生成 content:// URI
        apkUri = FileProvider.getUriForFile(context,
                context.getPackageName() + ".fileprovider", apkFile);
        Log.d(TAG, "FileProvider URI: " + apkUri.toString());

        // 使用 ACTION_INSTALL_PACKAGE + EXTRA_RETURN_RESULT
        Intent installIntent = new Intent(Intent.ACTION_INSTALL_PACKAGE);
        installIntent.setData(apkUri);
        installIntent.putExtra(Intent.EXTRA_RETURN_RESULT, true);
        installIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        installIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(installIntent);

        Log.d(TAG, "已启动系统安装界面（ACTION_INSTALL_PACKAGE），APK URI: " + apkUri);
    }

    /*
     * getLocalVersionName: 获取本地应用版本号
     * @param context 应用上下文
     * @return 版本名字符串（如 "0.8C"），读取失败返回 null
     */
    public static String getLocalVersionName(Context context) {
        try {
            return context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0).versionName;
        } catch (Exception e) {
            Log.e(TAG, "读取本地版本名失败", e);
            return null;
        }
    }

    /*
     * getLocalVersionCode: 获取本地应用版本码
     * @param context 应用上下文
     * @return 版本码整数，读取失败返回 0
     */
    public static int getLocalVersionCode(Context context) {
        try {
            // minSdk=29 >= P(28)，始终使用 getLongVersionCode()
            return (int) context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0)
                    .getLongVersionCode();
        } catch (Exception e) {
            Log.e(TAG, "读取版本码失败", e);
            return 0;
        }
    }

    // ==================== 私有辅助方法 ====================

    /*
     * httpGet: 执行 HTTP GET 请求
     * @param urlStr 请求地址
     * @return 响应字符串
     * @throws Exception 网络异常或响应错误
     */
    private String httpGet(String urlStr) throws Exception {
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(10000);
        // 设置 User-Agent
        conn.setRequestProperty("User-Agent", "GrayVideoDL-Updater/1.0");
        conn.connect();

        // 检查响应码
        int responseCode = conn.getResponseCode();
        if (responseCode != HttpURLConnection.HTTP_OK) {
            throw new Exception("HTTP " + responseCode + ": " + conn.getResponseMessage());
        }

        // 读取响应 body
        InputStream in = conn.getInputStream();
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[1024];
        int len;
        while ((len = in.read(buf)) != -1) {
            out.write(buf, 0, len);
        }
        in.close();
        conn.disconnect();
        return out.toString("UTF-8");
    }

    /*
     * compareVersions: 比较两个版本号
     * 支持形如 "0.8C"、"1.0.1"、"0.8B" 的版本号格式。
     * @param version1 第一个版本号
     * @param version2 第二个版本号
     * @return 正数表示 version1 > version2，负数表示小于，0 表示相等
     */
    private int compareVersions(String version1, String version2) {
        // 解析为可比较的整数值
        int val1 = parseVersionValue(version1);
        int val2 = parseVersionValue(version2);
        return Integer.compare(val1, val2);
    }

    /*
     * parseVersionValue: 将版本号解析为可比较的整数
     * 解析规则：
     *   1. 提取数字部分的各段（以点分隔），每段乘以 100 累加
     *   2. 提取尾随字母（A-Z），转换为数值（A=1, B=2, ...），作为低 2 位
     * 示例：
     *   "0.8B" -> 0*100 + 8 = 8, 再 *100 + 2(B) = 802
     *   "0.8C" -> 0*100 + 8 = 8, 再 *100 + 3(C) = 803
     *   "1.0.1" -> 1*10000 + 0*100 + 1 = 10001
     * @param versionName 版本号字符串
     * @return 可比较的整数值
     */
    private int parseVersionValue(String versionName) {
        // 去掉可能的 'v' 前缀
        String v = versionName.startsWith("v") ? versionName.substring(1) : versionName;

        // 分离数字部分和字母部分
        // 如 "0.8B" -> numericPart="0.8", letterPart="B"
        String numericPart = v.replaceAll("[A-Za-z]", "");
        int letterValue = 0;
        if (!numericPart.equals(v)) {
            // 存在字母后缀，取第一个字母的序号（A=1, B=2, ... Z=26）
            String letterPart = v.substring(numericPart.length()).trim();
            if (!letterPart.isEmpty()) {
                // 仅取第一个字母
                char firstLetter = Character.toUpperCase(letterPart.charAt(0));
                letterValue = firstLetter - 'A' + 1;
            }
        }

        // 解析数字部分："0.8" -> 8（每段 * 100 累加）
        String[] parts = numericPart.split("\\.");
        int code = 0;
        for (String part : parts) {
            code = code * 100 + Integer.parseInt(part);
        }

        // 合并：数字部分占高位，字母部分占低位
        return code * 100 + letterValue;
    }

    /*
     * isValidApk: 校验 APK 文件的完整性
     * APK 本质上是 ZIP 格式，通过尝试打开 ZIP 文件验证文件是否完整。
     * @param file 待校验的 APK 文件
     * @return true 表示文件是有效的 ZIP/APK，false 表示文件已损坏
     */
    private boolean isValidApk(File file) {
        try {
            ZipFile zf = new ZipFile(file);
            zf.close();
            return true;
        } catch (Exception e) {
            Log.w(TAG, "APK 完整性校验失败: " + e.getMessage());
            return false;
        }
    }

    /*
     * extractApkFromZip: 从 ZIP 压缩包中提取 APK 文件到指定目录
     * 遍历 ZIP 中的所有条目，找到第一个以 .apk 结尾的文件并解压到目标目录。
     * ZIP 包的 CRC32 校验由 ZipInputStream 在读取时自动完成，能有效检测下载传输中的文件损坏。
     * @param zipFile 下载的 ZIP 压缩包
     * @param destDir 解压目标目录
     * @return 解压后的 APK 文件
     * @throws Exception ZIP 中未找到 APK 或解压失败时抛出异常
     */
    private File extractApkFromZip(File zipFile, File destDir) throws Exception {
        // 解压到指定目录（通常为外部文件目录，兼容 FileProvider 共享给 Package Installer）
        File outputFile = new File(destDir, "update.apk");
        if (outputFile.exists()) {
            outputFile.delete();
        }

        // 使用 ZipInputStream 逐条目读取，CRC32 校验由系统自动完成
        try (ZipInputStream zis = new ZipInputStream(new FileInputStream(zipFile))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                // 只提取以 .apk 结尾的文件（忽略其他无关文件）
                if (entry.getName().endsWith(".apk")) {
                    Log.d(TAG, "解压条目: " + entry.getName()
                            + " (压缩: " + entry.getCompressedSize()
                            + " → 原始: " + entry.getSize() + ")");

                    try (FileOutputStream fos = new FileOutputStream(outputFile)) {
                        byte[] buffer = new byte[8192];
                        int len;
                        while ((len = zis.read(buffer)) != -1) {
                            fos.write(buffer, 0, len);
                        }
                    }

                    if (!outputFile.exists() || outputFile.length() == 0) {
                        throw new Exception("解压后 APK 文件为空");
                    }
                    return outputFile;
                }
            }
        }

        throw new Exception("ZIP 压缩包中未找到 APK 文件");
    }
}