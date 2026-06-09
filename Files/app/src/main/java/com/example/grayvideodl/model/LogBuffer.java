/*
 * LogBuffer.java
 * 日志缓冲区单例，用于在 Fragment 之间共享日志数据。
 * HomeFragment 写入日志，LogFragment 读取并展示。
 */

package com.example.grayvideodl.model;

public class LogBuffer {

    // 日志内容构建器
    private static StringBuilder logBuilder = new StringBuilder("等待操作...");

    // 初始状态标记
    private static boolean isInitial = true;

    /*
     * append: 追加一条日志
     * @param text 日志文本
     */
    public static void append(String text) {
        if (isInitial) {
            logBuilder = new StringBuilder(text);
            isInitial = false;
        } else {
            logBuilder.append("\n\n---\n").append(text);
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
