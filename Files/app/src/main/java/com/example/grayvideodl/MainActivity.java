/*
 * MainActivity.java
 * 主 Activity：管理底部导航栏和 Fragment 切换，
 * 支持动态显示/隐藏日志 Tab（由调试日志设置控制）。
 * 在首次运行时展示初始化检查结果对话框（自动显示、自动隐藏）。
 */

package com.example.grayvideodl;

import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.activity.EdgeToEdge;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentTransaction;

import com.example.grayvideodl.ui.download.DownloadFragment;
import com.example.grayvideodl.ui.home.HomeFragment;
import com.example.grayvideodl.ui.log.LogFragment;
import com.example.grayvideodl.ui.settings.SettingsFragment;
import com.google.android.material.bottomnavigation.BottomNavigationView;

import java.util.Random;

public class MainActivity extends AppCompatActivity {

    // Fragment 管理器
    private FragmentManager fragmentManager;

    // 底部导航栏
    private BottomNavigationView bottomNavigationView;

    // 四个 Fragment 实例
    private HomeFragment homeFragment;
    private DownloadFragment downloadFragment;
    private LogFragment logFragment;
    private SettingsFragment settingsFragment;

    // SharedPreferences
    private static final String PREF_NAME = "grayvideodl_settings";
    private static final String KEY_DEBUG_LOG_ENABLED = "debug_log_enabled";

    // 主线程 Handler，用于延迟执行和定时轮询
    private Handler mainHandler;

    // 初始化检查结果轮询计数器，超过最大次数后不再轮询
    private int checkPollCount = 0;
    private static final int MAX_POLL_COUNT = 30; // 最多等待 15 秒（30 次 × 500ms）

    // 初始化检查加载对话框（显示"正在进行初始化检查，请稍后..."）
    private AlertDialog initCheckLoadingDialog;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);

        // 系统窗口边距适配
        ViewCompat.setOnApplyWindowInsetsListener(
                findViewById(R.id.main_container), (view, windowInsets) -> {
            Insets insets = windowInsets.getInsets(
                    WindowInsetsCompat.Type.systemBars());
            view.setPadding(insets.left, insets.top,
                    insets.right, insets.bottom);
            return windowInsets;
        });

        fragmentManager = getSupportFragmentManager();
        bottomNavigationView = findViewById(R.id.bottom_navigation);
        mainHandler = new Handler(Looper.getMainLooper());

        // 底部导航选中监听
        bottomNavigationView.setOnItemSelectedListener(
                new BottomNavigationView.OnItemSelectedListener() {
            @Override
            public boolean onNavigationItemSelected(
                    @NonNull android.view.MenuItem item) {
                int itemId = item.getItemId();

                if (itemId == R.id.nav_home) {
                    switchFragment(HomeFragment.class, homeFragment, "Home");
                    return true;
                } else if (itemId == R.id.nav_download) {
                    switchFragment(DownloadFragment.class,
                            downloadFragment, "Download");
                    return true;
                } else if (itemId == R.id.nav_log) {
                    switchFragment(LogFragment.class,
                            logFragment, "Log");
                    return true;
                } else if (itemId == R.id.nav_settings) {
                    switchFragment(SettingsFragment.class,
                            settingsFragment, "Settings");
                    return true;
                }
                return false;
            }
        });

        if (savedInstanceState == null) {
            bottomNavigationView.setSelectedItemId(R.id.nav_home);
        }

        // 启动初始化检查结果轮询
        pollInitCheckResult();
    }

    /*
     * onResume: Activity 恢复可见时更新导航栏
     * 根据调试日志设置显示或隐藏日志 Tab
     */
    @Override
    protected void onResume() {
        super.onResume();
        updateNavLogVisibility();
    }

    /*
     * refreshNavLogVisibility: 公开方法，供 SettingsFragment 调用
     * 即时刷新底部导航栏的日志 Tab 显隐，无需离开当前页面
     */
    public void refreshNavLogVisibility() {
        updateNavLogVisibility();
    }

    /*
     * updateNavLogVisibility: 根据调试日志设置控制日志 Tab 的显隐
     */
    private void updateNavLogVisibility() {
        SharedPreferences prefs = getSharedPreferences(
                PREF_NAME, MODE_PRIVATE);
        boolean debugLogEnabled = prefs.getBoolean(
                KEY_DEBUG_LOG_ENABLED, false);

        // 获取日志菜单项并设置可见性
        android.view.MenuItem logMenuItem =
                bottomNavigationView.getMenu().findItem(R.id.nav_log);
        if (logMenuItem != null) {
            logMenuItem.setVisible(debugLogEnabled);
        }
    }

    /*
     * pollInitCheckResult: 轮询等待初始化检查结果
     * 先显示加载对话框（含转圈动画），再轮询检查结果。
     * 检查就绪后随机延迟 1~3 秒再展示结果对话框，提升用户体验。
     */
    private void pollInitCheckResult() {
        // 立即显示加载对话框
        showInitCheckLoadingDialog();

        // 启动轮询检查结果
        doPollInitCheck();
    }

    /*
     * showInitCheckLoadingDialog: 显示初始化检查加载对话框
     * 包含转圈的 ProgressBar 和提示文字"正在进行初始化检查，请稍后..."
     */
    private void showInitCheckLoadingDialog() {
        try {
            // 创建 ProgressBar 作为转圈动画
            ProgressBar progressBar = new ProgressBar(this);
            progressBar.setPadding(0, 32, 0, 32);

            // 构造加载对话框
            initCheckLoadingDialog = new AlertDialog.Builder(this)
                    .setTitle("正在进行初始化检查")
                    .setMessage("请稍后...")
                    .setView(progressBar)
                    .setCancelable(false)
                    .show();
        } catch (Exception e) {
            // 对话框显示失败时忽略（不影响后续逻辑）
            e.printStackTrace();
        }
    }

    /*
     * doPollInitCheck: 实际执行轮询检查结果的逻辑
     * 检查就绪后随机延迟 1~3 秒再展示结果对话框
     */
    private void doPollInitCheck() {
        if (GrayVideoDLApp.isCheckCompleted()) {
            // 检查已完成，随机延迟 1~3 秒后展示结果对话框
            int randomDelay = 1000 + new Random().nextInt(2001); // 1000~3000ms
            mainHandler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    // 关闭加载对话框
                    dismissInitCheckLoadingDialog();

                    // 获取并展示检查结果
                    InitCheckHelper.InitCheckResult result =
                            GrayVideoDLApp.getCheckResult();
                    if (result != null) {
                        showInitCheckDialog(result);
                        GrayVideoDLApp.consumeCheckResult();
                    }
                }
            }, randomDelay);
        } else {
            // 检查尚未完成，500ms 后重试
            checkPollCount++;
            if (checkPollCount <= MAX_POLL_COUNT) {
                mainHandler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        doPollInitCheck();
                    }
                }, 500);
            } else {
                // 超时：关闭加载对话框
                dismissInitCheckLoadingDialog();
            }
        }
    }

    /*
     * dismissInitCheckLoadingDialog: 关闭初始化检查加载对话框
     */
    private void dismissInitCheckLoadingDialog() {
        if (initCheckLoadingDialog != null && initCheckLoadingDialog.isShowing()) {
            try {
                initCheckLoadingDialog.dismiss();
            } catch (Exception e) {
                e.printStackTrace();
            }
            initCheckLoadingDialog = null;
        }
    }

    /*
     * showInitCheckDialog: 展示初始化检查结果的自动隐藏模态对话框
     * 使用自定义布局展示检查状态图标、标题和详细结果，
     * 3 秒后自动关闭，用户也可手动点击关闭。
     * @param result 初始化检查结果对象
     */
    private void showInitCheckDialog(
            InitCheckHelper.InitCheckResult result) {

        // 加载自定义对话框布局
        LayoutInflater inflater = LayoutInflater.from(this);
        View dialogView = inflater.inflate(
                R.layout.dialog_init_check, null);

        // 初始化布局中的控件
        TextView tvIcon = dialogView.findViewById(R.id.tv_check_icon);
        TextView tvTitle = dialogView.findViewById(R.id.tv_check_title);
        TextView tvDetail = dialogView.findViewById(R.id.tv_check_detail);

        // 根据检查结果设置状态图标和标题
        if (result.all_success) {
            tvIcon.setText("✓");
            tvIcon.setBackgroundResource(R.drawable.circle_bg_green); // 绿色背景 — 成功
            tvTitle.setText("初始化检查通过");
        } else {
            tvIcon.setText("✗");
            tvIcon.setBackgroundResource(R.drawable.circle_bg_red); // 红色背景 — 失败
            tvTitle.setText("初始化检查完成");
        }

        // 设置检查详情文本
        tvDetail.setText(result.detail_message);

        // 创建 AlertDialog 并设置为模态
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setView(dialogView)
                .setCancelable(false) // 模态：用户不可通过点击外部取消
                .create();

        // 显示对话框
        dialog.show();

        // 3 秒后自动隐藏对话框
        mainHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (dialog.isShowing()) {
                    dialog.dismiss();
                }
            }
        }, 3000);
    }

    /*
     * switchFragment: 通用的 Fragment 切换方法
     * 使用 hide/show 避免重复创建，提升性能
     */
    private <T extends Fragment> void switchFragment(
            Class<T> fragmentClass, Fragment fragmentRef, String tag) {

        Log.d("DownloadFlow", "switchFragment: 切换到 " + tag
                + ", fragmentRef=" + (fragmentRef == null ? "null" : "非空"));

        FragmentTransaction transaction = fragmentManager.beginTransaction();

        // 先隐藏所有已添加的 Fragment
        Fragment[] existingFragments = {
            fragmentManager.findFragmentByTag("Home"),
            fragmentManager.findFragmentByTag("Download"),
            fragmentManager.findFragmentByTag("Log"),
            fragmentManager.findFragmentByTag("Settings")
        };
        for (Fragment f : existingFragments) {
            if (f != null) {
                transaction.hide(f);
                Log.d("DownloadFlow", "switchFragment: 隐藏 " + f.getTag());
            }
        }

        // 查找或创建目标 Fragment
        Fragment targetFragment = fragmentManager.findFragmentByTag(tag);
        if (targetFragment == null) {
            try {
                targetFragment = fragmentClass.newInstance();
                Log.d("DownloadFlow", "switchFragment: 创建新的 " + tag
                        + " Fragment 实例");
            } catch (IllegalAccessException |
                    java.lang.InstantiationException e) {
                Log.e("DownloadFlow", "switchFragment: 创建 Fragment 失败", e);
                return;
            }
            transaction.add(R.id.fragment_container, targetFragment, tag);
        } else {
            Log.d("DownloadFlow", "switchFragment: 复用已有的 " + tag
                    + " Fragment");
        }

        transaction.show(targetFragment);

        // 更新引用
        switch (tag) {
            case "Home":
                homeFragment = (HomeFragment) targetFragment;
                break;
            case "Download":
                downloadFragment = (DownloadFragment) targetFragment;
                break;
            case "Log":
                logFragment = (LogFragment) targetFragment;
                break;
            case "Settings":
                settingsFragment = (SettingsFragment) targetFragment;
                break;
        }

        transaction.commit();
        Log.d("DownloadFlow", "switchFragment: " + tag + " 切换完成");
    }
}
