/*
 * LogBuffer.java
 * 日志缓冲区单例，用于在 Fragment 之间共享日志数据。
 * HomeFragment 写入日志，LogFragment 读取并展示。
 * 每条日志自动添加时间戳，便于追踪操作时序。
 */

package com.example.grayvideodl.model;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class LogBuffer {

    // 日志内容构建器
    private static StringBuilder logBuilder = new StringBuilder("等待操作...");

    // 初始状态标记
    private static boolean isInitial = true;

    // 时间戳格式：时:分:秒
    private static final SimpleDateFormat TIME_FORMAT =
            new SimpleDateFormat("HH:mm:ss", Locale.getDefault());

    /*
     * append: 追加一条日志，自动添加时间戳前缀
     * 每条日志记录格式：[HH:mm:ss] 日志内容
     * 日志之间用分隔线隔开
     * @param text 日志文本
     */
    public static void append(String text) {
        // 生成当前时间戳
        String timestamp = "[" + TIME_FORMAT.format(new Date()) + "]";

        if (isInitial) {
            logBuilder = new StringBuilder(timestamp + " " + text);
            isInitial = false;
        } else {
            logBuilder.append("\n\n──────────────\n")
                      .append(timestamp).append(" ").append(text);
        }
    }

    /*
     * getLog: 获取完整的日志文本
     * @return 日志字符串
     */
    public static String getLog() {
        return logBuilder.toString();
    }

    /*
     * clear: 清空日志缓冲区
     */
    public static void clear() {
        logBuilder = new StringBuilder("等待操作...");
        isInitial = true;
    }
}
