/*
 * GrayVideoDLApp.java
 * 自定义 Application 类，负责应用的全局初始化。
 * 在首次运行时执行系统环境检查（文件目录、Python 环境），
 * 并通过 InitCheckHelper 将检查结果传递给 MainActivity 展示。
 */

package com.example.grayvideodl;

import android.app.Application;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

/*
 * GrayVideoDLApp: 应用的 Application 入口
 * 继承 android.app.Application，在 onCreate 中触发首次运行初始化检查
 */
public class GrayVideoDLApp extends Application {

    // SharedPreferences 名称（与项目其他模块保持一致）
    private static final String PREF_NAME = "grayvideodl_settings";

    // 首次运行完成标记键名
    private static final String KEY_FIRST_RUN_DONE = "first_run_done";

    // 存储初始化检查结果，供 MainActivity 读取
    private static InitCheckHelper.InitCheckResult s_check_result = null;

    // 检查是否已完成（包括正在检查中和已完成）
    private static boolean s_check_completed = false;

    /*
     * onCreate: 应用启动时的入口方法
     * 判断是否为首次运行，若是则在后台线程执行初始化检查
     */
    @Override
    public void onCreate() {
        super.onCreate();

        // 读取首次运行标记
        SharedPreferences prefs = getSharedPreferences(PREF_NAME, MODE_PRIVATE);
        boolean firstRunDone = prefs.getBoolean(KEY_FIRST_RUN_DONE, false);

        if (!firstRunDone) {
            // 首次运行：在后台线程执行初始化检查
            performFirstRunCheck();
        } else {
            // 非首次运行，直接标记检查已完成（无需展示对话框）
            s_check_completed = true;
        }
    }

    /*
     * performFirstRunCheck: 在后台线程执行首次运行初始化检查
     * 检查完成后将结果存储到静态变量，供 MainActivity 读取展示
     */
    private void performFirstRunCheck() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                // 执行所有初始化检查（目录检查 + Python 环境检查）
                InitCheckHelper.InitCheckResult result =
                        InitCheckHelper.performAllChecks(GrayVideoDLApp.this);

                // 存储检查结果
                s_check_result = result;
                s_check_completed = true;

                // 标记首次运行已完成，下次启动不再检查
                SharedPreferences prefs =
                        getSharedPreferences(PREF_NAME, MODE_PRIVATE);
                prefs.edit().putBoolean(KEY_FIRST_RUN_DONE, true).apply();
            }
        }).start();
    }

    /*
     * getCheckResult: 获取初始化检查结果（静态方法）
     * 供 MainActivity 在适当时机读取并展示对话框
     * @return InitCheckResult 对象，若检查未完成则返回 null
     */
    public static InitCheckHelper.InitCheckResult getCheckResult() {
        return s_check_result;
    }

    /*
     * isCheckCompleted: 判断初始化检查是否已完成
     * @return true 表示检查已完成（成功或失败），false 表示仍在进行中
     */
    public static boolean isCheckCompleted() {
        return s_check_completed;
    }

    /*
     * consumeCheckResult: 消费（取走）检查结果
     * MainActivity 展示完对话框后调用，避免重复展示
     */
    public static void consumeCheckResult() {
        s_check_result = null;
    }
}
