/*
 * PlatformCookieManager.java
 * 多平台 Cookie 管理工具类。
 * 根据视频链接 URL 检测所属平台，为不同平台分别管理 Cookie，
 * 供 HomeFragment 和 DownloadFragment 调用，传递给 Python 桥接模块的
 * extractVideoInfo / downloadVideo / downloadVideoWithProgress 函数。
 *
 * 每个平台使用独立的 Cookie 文件存储于应用内部存储，
 * 格式为 Netscape HTTP Cookie File（与 yt-dlp --cookies 兼容）。
 * 平台检测逻辑与 Python ytdlp_bridge._detect_platform 保持同步。
 */

package com.example.grayvideodl;

import android.content.Context;
import android.content.SharedPreferences;

import com.example.grayvideodl.ui.settings.BilibiliLoginDialog;

import java.io.File;

public class PlatformCookieManager {

    // SharedPreferences 存储名称（用于保存各平台登录状态和原始 Cookie）
    private static final String PREF_NAME = "grayvideodl_cookies";

    // ==================== 平台标识常量 ====================
    public static final String PLATFORM_BILIBILI  = "bilibili";
    public static final String PLATFORM_DOUYIN    = "douyin";
    public static final String PLATFORM_KUAISHOU  = "kuaishou";
    public static final String PLATFORM_YOUTUBE   = "youtube";
    public static final String PLATFORM_TIKTOK    = "tiktok";
    public static final String PLATFORM_TWITTER   = "twitter";
    public static final String PLATFORM_INSTAGRAM = "instagram";
    public static final String PLATFORM_WEIBO     = "weibo";
    public static final String PLATFORM_XIAOHONGSHU = "xiaohongshu";
    public static final String PLATFORM_VQQ       = "vqq";
    public static final String PLATFORM_IQIYI     = "iqiyi";
    public static final String PLATFORM_YOUKU     = "youku";
    public static final String PLATFORM_TWITCH    = "twitch";
    public static final String PLATFORM_UNKNOWN   = "unknown";

    /*
     * detectPlatform: 从视频链接 URL 检测所属平台。
     * 域名匹配逻辑应与 Python ytdlp_bridge._detect_platform 保持同步。
     *
     * @param url 视频链接（完整 URL 或域名均可）
     * @return 平台标识字符串（PLATFORM_* 常量），无法识别返回 PLATFORM_UNKNOWN
     */
    public static String detectPlatform(String url) {
        if (url == null || url.isEmpty()) {
            return PLATFORM_UNKNOWN;
        }
        String lowerUrl = url.toLowerCase().trim();

        // Bilibili 国内：bilibili.com、b23.tv
        if (lowerUrl.contains("bilibili.com") || lowerUrl.contains("b23.tv")) {
            return PLATFORM_BILIBILI;
        }
        // Bilibili 海外：biliintl.com
        if (lowerUrl.contains("biliintl.com")) {
            return PLATFORM_BILIBILI;
        }
        // 抖音：douyin.com、iesdouyin.com、douyinvideo.com
        if (lowerUrl.contains("douyin.com")
                || lowerUrl.contains("iesdouyin.com")
                || lowerUrl.contains("douyinvideo.com")) {
            return PLATFORM_DOUYIN;
        }
        // 快手：kuaishou.com、kuaishou.cn
        if (lowerUrl.contains("kuaishou.com")
                || lowerUrl.contains("kuaishou.cn")) {
            return PLATFORM_KUAISHOU;
        }
        // YouTube：youtube.com、youtu.be
        if (lowerUrl.contains("youtube.com")
                || lowerUrl.contains("youtu.be")) {
            return PLATFORM_YOUTUBE;
        }
        // TikTok：tiktok.com
        if (lowerUrl.contains("tiktok.com")) {
            return PLATFORM_TIKTOK;
        }
        // Twitter / X：注意 x.com 可能误匹配其他域名，
        // 所以优先匹配 twitter.com，再通过 URL 协议或路径前缀匹配 x.com
        if (lowerUrl.contains("twitter.com")
                || lowerUrl.contains("//x.com")
                || lowerUrl.contains("//www.x.com")
                || lowerUrl.startsWith("x.com/")
                || lowerUrl.startsWith("x.com?")) {
            return PLATFORM_TWITTER;
        }
        // Instagram：instagram.com
        if (lowerUrl.contains("instagram.com")) {
            return PLATFORM_INSTAGRAM;
        }
        // 微博：weibo.com
        if (lowerUrl.contains("weibo.com")) {
            return PLATFORM_WEIBO;
        }
        // 小红书：xiaohongshu.com
        if (lowerUrl.contains("xiaohongshu.com")) {
            return PLATFORM_XIAOHONGSHU;
        }
        // 腾讯视频：v.qq.com（注意 qq.com 的通用匹配）
        if (lowerUrl.contains("v.qq.com")) {
            return PLATFORM_VQQ;
        }
        // 爱奇艺：iqiyi.com
        if (lowerUrl.contains("iqiyi.com")) {
            return PLATFORM_IQIYI;
        }
        // 优酷：youku.com
        if (lowerUrl.contains("youku.com")) {
            return PLATFORM_YOUKU;
        }
        // Twitch：twitch.tv
        if (lowerUrl.contains("twitch.tv")) {
            return PLATFORM_TWITCH;
        }
        return PLATFORM_UNKNOWN;
    }

    /*
     * getCookieFilePath: 根据视频链接 URL 获取对应平台的 Cookie 文件路径。
     * 自动检测平台并查找是否存在对应的 Cookie 文件，
     * 不存在则返回空字符串（即不使用 Cookie）。
     *
     * @param context Android 上下文
     * @param url     视频链接（用于检测平台）
     * @return Cookie 文件绝对路径，或空字符串
     */
    public static String getCookieFilePath(Context context, String url) {
        String platform = detectPlatform(url);
        return getCookieFilePathByPlatform(context, platform);
    }

    /*
     * getCookieFilePathByPlatform: 根据平台标识获取 Cookie 文件路径。
     * 查找格式为 {platform}_cookies.txt 的 Netscape 格式 Cookie 文件。
     * 对 Bilibili 兼容旧版 BilibiliLoginDialog 存储的文件名。
     *
     * @param context  Android 上下文
     * @param platform 平台标识（PLATFORM_* 常量）
     * @return Cookie 文件绝对路径，或空字符串
     */
    public static String getCookieFilePathByPlatform(
            Context context, String platform) {
        if (platform == null || platform.isEmpty()
                || PLATFORM_UNKNOWN.equals(platform)) {
            return "";
        }

        String fileName;
        // Bilibili 兼容旧版文件名（bilibili_cookies.txt）
        if (PLATFORM_BILIBILI.equals(platform)) {
            fileName = "bilibili_cookies.txt";
        } else {
            fileName = platform + "_cookies.txt";
        }

        File cookieFile = new File(context.getFilesDir(), fileName);
        if (cookieFile.exists() && cookieFile.length() > 0) {
            return cookieFile.getAbsolutePath();
        }

        // Bilibili 回退：尝试使用 BilibiliLoginDialog 的旧版路径
        if (PLATFORM_BILIBILI.equals(platform)) {
            String legacyPath = BilibiliLoginDialog.getCookieFilePath(context);
            if (!legacyPath.isEmpty()) {
                return legacyPath;
            }
        }

        return "";
    }

    /*
     * isPlatformLoggedIn: 检查指定平台是否已登录（是否有有效的 Cookie）。
     *
     * @param context  Android 上下文
     * @param platform 平台标识（PLATFORM_* 常量）
     * @return true 表示已登录（Cookie 文件存在且非空）
     */
    public static boolean isPlatformLoggedIn(Context context, String platform) {
        String cookiePath = getCookieFilePathByPlatform(context, platform);
        return !cookiePath.isEmpty();
    }

    /*
     * isUrlLoggedIn: 检查视频链接对应的平台是否已登录。
     * 便捷方法：先检测平台，再检查登录状态。
     *
     * @param context Android 上下文
     * @param url     视频链接
     * @return true 表示已登录
     */
    public static boolean isUrlLoggedIn(Context context, String url) {
        String platform = detectPlatform(url);
        return isPlatformLoggedIn(context, platform);
    }

    /*
     * saveRawCookies: 将原始 Cookie 字符串保存到 SharedPreferences。
     * 供各平台登录 Fragment/Activity 在登录成功后调用。
     *
     * @param context   Android 上下文
     * @param platform  平台标识（PLATFORM_* 常量）
     * @param rawCookies 原始 Cookie 字符串（key=value; key=value 格式）
     */
    public static void saveRawCookies(Context context, String platform,
                                      String rawCookies) {
        if (platform == null || rawCookies == null || rawCookies.isEmpty()) {
            return;
        }
        SharedPreferences prefs = context
                .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        prefs.edit()
                .putBoolean(platform + "_logged_in", true)
                .putString(platform + "_raw_cookies", rawCookies)
                .apply();
    }

    /*
     * clearCookies: 清除指定平台的所有 Cookie 数据和登录状态。
     * 供退出登录功能调用，同时删除文件系统和 SharedPreferences 中的记录。
     *
     * @param context  Android 上下文
     * @param platform 平台标识（PLATFORM_* 常量）
     */
    public static void clearCookies(Context context, String platform) {
        if (platform == null) return;

        // 删除 Cookie 文件
        String fileName;
        if (PLATFORM_BILIBILI.equals(platform)) {
            fileName = "bilibili_cookies.txt";
        } else {
            fileName = platform + "_cookies.txt";
        }
        File cookieFile = new File(context.getFilesDir(), fileName);
        if (cookieFile.exists()) {
            cookieFile.delete();
        }

        // 清除 SharedPreferences 中的登录状态
        SharedPreferences prefs = context
                .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        prefs.edit()
                .remove(platform + "_logged_in")
                .remove(platform + "_raw_cookies")
                .apply();
    }
}
