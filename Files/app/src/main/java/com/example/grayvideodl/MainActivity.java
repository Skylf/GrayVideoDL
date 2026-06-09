/*
 * MainActivity.java
 * 主 Activity：管理底部导航栏和 Fragment 切换，
 * 支持动态显示/隐藏日志 Tab（由调试日志设置控制）。
 */

package com.example.grayvideodl;

import android.content.SharedPreferences;
import android.os.Bundle;

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
     * switchFragment: 通用的 Fragment 切换方法
     * 使用 hide/show 避免重复创建，提升性能
     */
    private <T extends Fragment> void switchFragment(
            Class<T> fragmentClass, Fragment fragmentRef, String tag) {

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
            }
        }

        // 查找或创建目标 Fragment
        Fragment targetFragment = fragmentManager.findFragmentByTag(tag);
        if (targetFragment == null) {
            try {
                targetFragment = fragmentClass.newInstance();
            } catch (IllegalAccessException |
                    java.lang.InstantiationException e) {
                return;
            }
            transaction.add(R.id.fragment_container, targetFragment, tag);
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
    }
}
