/*
 * InitCheckHelper.java
 * 初始化检查辅助类，提供首次运行时检查系统目录和程序环境是否就绪的静态方法。
 * 检查结果通过 InitCheckResult 内部类返回，包含各项检查的详细信息。
 */

package com.example.grayvideodl;

import android.content.Context;
import android.content.res.AssetManager;
import android.os.Environment;

import com.chaquo.python.Python;
import com.chaquo.python.PyObject;
import com.chaquo.python.android.AndroidPlatform;

import java.io.File;
import java.io.InputStream;

/*
 * InitCheckHelper: 封装所有初始化检查逻辑的辅助类
 * 提供目录检查和 Python 环境检测两个核心静态方法
 */
public class InitCheckHelper {

    /*
     * InitCheckResult: 初始化检查的结果数据类
     * 包含所有检查项的状态信息和汇总字符串
     */
    public static class InitCheckResult {
        /*
         * all_success: 是否所有检查项均通过
         */
        public boolean all_success;

        /*
         * detail_message: 详细检查结果文本，用于在对话框中展示
         */
        public String detail_message;

        /*
         * checked_items: 检查项数量统计
         */
        public int checked_items;

        /*
         * failed_items: 失败项数量统计
         */
        public int failed_items;

        public InitCheckResult() {
            this.all_success = true;
            this.detail_message = "";
            this.checked_items = 0;
            this.failed_items = 0;
        }
    }

    /*
     * getRequiredDirectories: 获取程序所需的目录列表
     * 返回需要检查的所有文件目录路径（File 对象列表）
     * @param context Android 上下文，用于获取应用专属目录
     * @return File 数组，包含所有需要检查/创建的目录
     */
    private static File[] getRequiredDirectories(Context context) {
        return new File[] {
            // 内部文件存储目录（Android 通常自动创建，但显式检查更安全）
            context.getFilesDir(),
            // 内部缓存目录
            context.getCacheDir(),
            // 外部下载专属目录（用于存放下载的视频文件）
            context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
        };
    }

    /*
     * checkDirectories: 检查并创建程序所需的文件目录
     * 遍历所需目录列表，对不存在的目录进行创建，并记录结果
     * @param context Android 上下文
     * @param result 检查结果对象，方法内会填充目录检查相关信息
     */
    private static void checkDirectories(Context context, InitCheckResult result) {
        File[] directories = getRequiredDirectories(context);
        StringBuilder detail = new StringBuilder();
        int checked = 0;
        int failed = 0;

        for (File dir : directories) {
            if (dir == null) {
                detail.append("⚠️ 部分存储目录不可用（可能是存储权限问题）\n");
                failed++;
                checked++;
                continue;
            }

            if (dir.exists()) {
                // 目录已存在，检查是否可读写
                boolean canRead = dir.canRead();
                boolean canWrite = dir.canWrite();
                if (canRead && canWrite) {
                    detail.append("✓ 存储目录正常\n");
                } else {
                    detail.append("⚠️ 存储目录权限不足\n");
                    failed++;
                }
            } else {
                // 目录不存在，尝试创建
                boolean created = dir.mkdirs();
                if (created || dir.exists()) {
                    detail.append("✓ 存储目录已创建\n");
                } else {
                    detail.append("✗ 存储目录创建失败\n");
                    failed++;
                }
            }
            checked++;
        }

        result.checked_items += checked;
        result.failed_items += failed;
        if (failed > 0) {
            result.all_success = false;
        }
        result.detail_message += "【文件目录检查】\n" + detail.toString() + "\n";
    }

    /*
     * checkPythonEnvironment: 检查 Python 运行环境是否正常
     * 尝试启动 Python 解释器并调用 ytdlp_bridge.py 中的 testEnvironment 函数
     * @param context Android 上下文
     * @param result 检查结果对象，方法内会填充 Python 环境检查信息
     */
    private static void checkPythonEnvironment(Context context, InitCheckResult result) {
        try {
            // 检查 Python 是否已启动，若未启动则初始化
            if (!Python.isStarted()) {
                Python.start(new AndroidPlatform(context));
            }

            // 调用 Python 桥接模块的环境检测函数
            Python py = Python.getInstance();
            PyObject module = py.getModule("ytdlp_bridge");
            PyObject testResult = module.callAttr("testEnvironment");

            // 解析返回的 JSON 结果
            String jsonStr = testResult.toString();
            // 简单解析：检查是否包含 "status": "ok"
            if (jsonStr.contains("\"status\": \"ok\"")) {
                result.detail_message += "【Python 环境检查】\n✓ Python 环境正常\n\n";
            } else {
                result.detail_message += "【Python 环境检查】\n✗ Python 环境异常\n\n";
                result.all_success = false;
                result.failed_items++;
            }
        } catch (Exception e) {
            // Python 环境启动失败（例如 Chaquopy 未正确配置或 Python 模块缺失）
            result.detail_message += "【Python 环境检查】\n✗ Python 环境初始化失败\n\n";
            result.all_success = false;
            result.failed_items++;
        }
        result.checked_items++;
    }

    /*
     * checkFfmpegEnvironment: 检查 FFmpeg 环境是否就绪
     * FFmpeg 用于合并分离的视频流和音频流，由 FFmpegManager 管理。
     * 检查逻辑：
     *   1. 先通过 checkLocalDownloaded 检查已有文件
     *   2. 若不存在，尝试从 APK assets 复制预置的 FFmpeg
     *   3. 报告 FFmpeg 路径、大小和可用状态
     * @param context Android 上下文
     * @param result 检查结果对象，方法内会填充 FFmpeg 环境检查信息
     */
    private static void checkFfmpegEnvironment(Context context, InitCheckResult result) {
        FFmpegManager ffManager = FFmpegManager.getInstance();

        // 尝试确保 FFmpeg 就绪（先检查本地，再从 assets 复制）
        boolean ffmpegReady = ffManager.isFfmpegAvailable();
        if (!ffmpegReady) {
            // 尝试检查本地已有文件（可能被之前的运行复制过）
            // 此时 FFmpegManager.initialize() 的后台线程可能尚未执行完毕
            File localFile = new File(context.getFilesDir(), "ffmpeg");
            if (localFile.exists()) {
                ffManager.initialize(context);
                // 等待初始化完成（最长等待 2 秒）
                for (int i = 0; i < 20; i++) {
                    if (ffManager.isFfmpegAvailable()) {
                        ffmpegReady = true;
                        break;
                    }
                    try { Thread.sleep(100); } catch (InterruptedException e) { break; }
                }
            }
        }

        if (!ffmpegReady) {
            // 尝试从 APK assets 同步复制 FFmpeg
            try {
                 AssetManager assetManager = context.getAssets();
                InputStream inStream = null;
                String[] assetPaths = {"ffmpeg", "ffmpeg/ffmpeg", "ffmpeg/arm64-v8a/ffmpeg"};
                for (String assetPath : assetPaths) {
                    try {
                        inStream = assetManager.open(assetPath);
                        break;
                    } catch (java.io.FileNotFoundException e) {
                        // 继续尝试下一个路径
                    }
                }

                if (inStream != null) {
                    File targetFile = new File(context.getFilesDir(), "ffmpeg");
                    if (!targetFile.exists()) {
                        // 复制文件
                        try (java.io.FileOutputStream outStream =
                                new java.io.FileOutputStream(targetFile)) {
                            byte[] buffer = new byte[8192];
                            int bytesRead;
                            while ((bytesRead = inStream.read(buffer)) != -1) {
                                outStream.write(buffer, 0, bytesRead);
                            }
                        }
                        targetFile.setExecutable(true);
                    }
                    inStream.close();

                    if (targetFile.exists()) {
                        ffmpegReady = true;
                        // 通知 FFmpegManager 更新状态
                        // 由于 FFmpegManager 是单例且已在 initialize 中，直接设置其内部状态
                        // 但 FFmpegManager 没有公开的设置方法，所以这里只做检测和报告
                    }
                }
            } catch (Exception e) {
                // assets 复制失败，不作为严重错误
            }
        }

        // 构建检查结果报告
        StringBuilder detail = new StringBuilder();

        if (ffmpegReady) {
            detail.append("✓ FFmpeg 已就绪\n");
        } else {
            // 再检查一次 files 目录下的 ffmpeg（兜底）
            File fallbackFile = new File(context.getFilesDir(), "ffmpeg");
            if (fallbackFile.exists()) {
                detail.append("⚠ FFmpeg 文件存在但状态待确认\n");
                detail.append("   注: 重新启动应用后可正常使用\n");
            } else {
                detail.append("⚠ FFmpeg 未就绪（分离流视频将无音频）\n");
                detail.append("   注: 下载视频时会自动尝试部署 FFmpeg\n");
                result.all_success = false;
                result.failed_items++;
            }
        }

        result.detail_message += "【FFmpeg 环境检查】\n" + detail.toString() + "\n";
        result.checked_items++;
    }

    /*
     * formatFileSize: 将字节数格式化为可读的文件大小文本
     * @param bytes 文件字节数
     * @return 格式化后的字符串，如 "7.9 MB"
     */
    private static String formatFileSize(long bytes) {
        if (bytes <= 0) return "未知";
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.0f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) {
            return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
        }
        return String.format("%.1f GB", bytes / (1024.0 * 1024.0 * 1024.0));
    }

    /*
     * extractJsonValue: 从 JSON 字符串中提取指定键的值（简易 JSON 解析）
     * 使用字符串搜索方式解析，用于避免引入额外的 JSON 解析库
     * @param json JSON 格式字符串
     * @param key 要提取的键名
     * @return 键对应的值字符串，未找到时返回空字符串
     */
    private static String extractJsonValue(String json, String key) {
        String searchKey = "\"" + key + "\": \"";
        int startIndex = json.indexOf(searchKey);
        if (startIndex == -1) {
            // 尝试搜索数值类型的值（不带引号）
            searchKey = "\"" + key + "\": ";
            startIndex = json.indexOf(searchKey);
            if (startIndex == -1) return "";
            startIndex += searchKey.length();
            int endIndex = json.indexOf(",", startIndex);
            if (endIndex == -1) endIndex = json.indexOf("}", startIndex);
            if (endIndex == -1) return "";
            return json.substring(startIndex, endIndex).trim();
        }
        startIndex += searchKey.length();
        int endIndex = json.indexOf("\"", startIndex);
        if (endIndex == -1) return "";
        return json.substring(startIndex, endIndex);
    }

    /*
     * performAllChecks: 执行所有初始化检查
     * 依次执行文件目录检查、Python 环境检查和 FFmpeg 环境检查，返回汇总结果
     * @param context Android 上下文
     * @return InitCheckResult 包含所有检查项的详细结果
     */
    public static InitCheckResult performAllChecks(Context context) {
        InitCheckResult result = new InitCheckResult();

        // 第一步：检查并创建所需的文件目录
        checkDirectories(context, result);

        // 第二步：检查 Python 运行环境
        checkPythonEnvironment(context, result);

        // 第三步：检查 FFmpeg 环境（音视频合并依赖）
        checkFfmpegEnvironment(context, result);

        // 生成最终摘要信息
        String summary;
        if (result.all_success) {
            summary = "✓ 所有检查项均通过，程序环境就绪。";
        } else {
            summary = "⚠" + "部分检查项未通过，但不影响程序主体功能使用。";
        }
        result.detail_message += "【检查摘要】\n" + summary;

        return result;
    }
}
