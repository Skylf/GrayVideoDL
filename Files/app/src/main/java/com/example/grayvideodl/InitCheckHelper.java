/*
 * InitCheckHelper.java
 * 初始化检查辅助类，提供首次运行时检查系统目录和程序环境是否就绪的静态方法。
 * 检查结果通过 InitCheckResult 内部类返回，包含各项检查的详细信息。
 */

package com.example.grayvideodl;

import android.content.Context;
import android.os.Environment;

import com.chaquo.python.Python;
import com.chaquo.python.PyObject;
import com.chaquo.python.android.AndroidPlatform;

import java.io.File;

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
                // 某些设备可能返回 null（如无外部存储时 getExternalFilesDir 可能为 null）
                detail.append("⚠️ 目录路径为 null（可能是存储权限或设备问题）\n");
                failed++;
                checked++;
                continue;
            }

            if (dir.exists()) {
                // 目录已存在，检查是否可读写
                boolean canRead = dir.canRead();
                boolean canWrite = dir.canWrite();
                if (canRead && canWrite) {
                    detail.append("✓ ").append(dir.getAbsolutePath()).append("\n");
                } else {
                    detail.append("⚠️ ").append(dir.getAbsolutePath())
                          .append("（权限不足）\n");
                    failed++;
                }
            } else {
                // 目录不存在，尝试创建
                boolean created = dir.mkdirs();
                if (created || dir.exists()) {
                    detail.append("✓ ").append(dir.getAbsolutePath())
                          .append("（已创建）\n");
                } else {
                    detail.append("✗ ").append(dir.getAbsolutePath())
                          .append("（创建失败）\n");
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
                String detailInfo = "Python 版本: " + extractJsonValue(jsonStr, "python_version");
                String ytDlpInfo = extractJsonValue(jsonStr, "yt_dlp_version");
                if (!ytDlpInfo.isEmpty()) {
                    detailInfo += "\nyt-dlp 版本: " + ytDlpInfo;
                }
                result.detail_message += "【Python 环境检查】\n✓ Python 运行环境正常\n"
                        + detailInfo + "\n\n";
            } else {
                String errorMsg = extractJsonValue(jsonStr, "error");
                result.detail_message += "【Python 环境检查】\n✗ Python 环境异常: "
                        + errorMsg + "\n\n";
                result.all_success = false;
                result.failed_items++;
            }
        } catch (Exception e) {
            // Python 环境启动失败（例如 Chaquopy 未正确配置或 Python 模块缺失）
            result.detail_message += "【Python 环境检查】\n✗ Python 环境启动失败: "
                    + e.getMessage() + "\n\n";
            result.all_success = false;
            result.failed_items++;
        }
        result.checked_items++;
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
     * 依次执行文件目录检查和 Python 环境检查，返回汇总结果
     * @param context Android 上下文
     * @return InitCheckResult 包含所有检查项的详细结果
     */
    public static InitCheckResult performAllChecks(Context context) {
        InitCheckResult result = new InitCheckResult();

        // 第一步：检查并创建所需的文件目录
        checkDirectories(context, result);

        // 第二步：检查 Python 运行环境
        checkPythonEnvironment(context, result);

        // 生成最终摘要信息
        String summary;
        if (result.all_success) {
            summary = "✓ 所有检查项均通过，程序环境就绪。";
        } else {
            summary = "⚠ " + result.failed_items + " 项检查未通过，请查看详情。";
        }
        result.detail_message += "【检查摘要】\n" + summary;

        return result;
    }
}
