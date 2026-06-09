/*
 * SettingsFragment.java
 * 设置 Fragment：提供默认画质、调试日志、B站登录等配置项。
 */

package com.example.grayvideodl.ui.settings;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.example.grayvideodl.R;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.materialswitch.MaterialSwitch;

public class SettingsFragment extends Fragment {

    private static final String PREF_NAME = "grayvideodl_settings";
    private static final String KEY_DEBUG_LOG_ENABLED = "debug_log_enabled";
    private static final String KEY_DEFAULT_QUALITY = "default_quality";

    private MaterialSwitch switchDebugLog;
    private TextView tvQuality;
    private TextView tvBiliLoginStatus;
    private TextView tvBiliLoginAction;
    private MaterialCardView cardBiliLogin;

    private SharedPreferences sharedPreferences;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_settings, container, false);

        sharedPreferences = requireContext()
                .getSharedPreferences(PREF_NAME, requireContext().MODE_PRIVATE);

        initViews(view);
        restoreSettings();
        setupListeners(view);

        return view;
    }

    private void initViews(View view) {
        switchDebugLog = view.findViewById(R.id.switch_debug_log);
        tvQuality = view.findViewById(R.id.tv_quality);
        tvBiliLoginStatus = view.findViewById(R.id.tv_bili_login_status);
        tvBiliLoginAction = view.findViewById(R.id.tv_bili_login_action);
        cardBiliLogin = view.findViewById(R.id.card_bili_login);
    }

    private void restoreSettings() {
        switchDebugLog.setChecked(
                sharedPreferences.getBoolean(KEY_DEBUG_LOG_ENABLED, false));
        tvQuality.setText(
                sharedPreferences.getString(KEY_DEFAULT_QUALITY, "自动"));
        updateBiliLoginStatus();
    }

    private void setupListeners(View view) {
        switchDebugLog.setOnCheckedChangeListener((buttonView, isChecked) -> {
            sharedPreferences.edit()
                    .putBoolean(KEY_DEBUG_LOG_ENABLED, isChecked)
                    .apply();
            if (getActivity() instanceof com.example.grayvideodl.MainActivity) {
                ((com.example.grayvideodl.MainActivity) getActivity())
                        .refreshNavLogVisibility();
            }
        });

        MaterialCardView cardQuality = view.findViewById(R.id.card_quality);
        cardQuality.setOnClickListener(v -> showQualityDialog());

        cardBiliLogin.setOnClickListener(v -> {
            if (BilibiliLoginDialog.isLoggedIn(requireContext())) {
                showLogoutConfirm();
            } else {
                BilibiliLoginFragment loginFragment =
                        new BilibiliLoginFragment();
                loginFragment.setCallback((success, msg) -> {
                    updateBiliLoginStatus();
                });
                loginFragment.show(getParentFragmentManager(),
                        "BilibiliLogin");
            }
        });
    }

    private void updateBiliLoginStatus() {
        boolean loggedIn = BilibiliLoginDialog.isLoggedIn(requireContext());
        if (loggedIn) {
            tvBiliLoginStatus.setText("已登录，已解锁高画质");
            tvBiliLoginStatus.setTextColor(
                    getResources().getColor(R.color.success_green, null));
            tvBiliLoginAction.setText("退出");
        } else {
            tvBiliLoginStatus.setText("未登录（点击登录以解锁高画质）");
            tvBiliLoginStatus.setTextColor(
                    getResources().getColor(android.R.color.darker_gray, null));
            tvBiliLoginAction.setText("登录");
        }
    }

    private void showLogoutConfirm() {
        new android.app.AlertDialog.Builder(requireContext())
                .setTitle("退出登录")
                .setMessage("确定要退出B站登录吗？退出后高画质格式将被锁定。")
                .setPositiveButton("退出", (dialog, which) -> {
                    // 1. 清除 SharedPreferences 登录状态（同步写入）
                    sharedPreferences.edit()
                            .putBoolean("bilibili_logged_in", false)
                            .putString("bilibili_raw_cookies", "")
                            .commit();

                    // 2. 直接删除 Cookie 文件（使用已知路径，不依赖 getCookieFilePath 的文件存在检查）
                    //    确保即使文件存在但上次检查失败也能被删除
                    java.io.File cookieFile = new java.io.File(
                            requireContext().getFilesDir(),
                            "bilibili_cookies.txt");
                    if (cookieFile.exists()) {
                        cookieFile.delete();
                    }

                    // 3. 清除 WebView 内部 Cookie 缓存（使用回调确保完成）
                    //    防止下次打开登录页时旧的 SESSDATA 导致自动登录
                    android.webkit.CookieManager.getInstance()
                            .removeAllCookies(() -> {
                                // Cookie 清除完成后，再清除 WebView 存储
                                android.webkit.WebStorage.getInstance()
                                        .deleteAllData();
                            });

                    updateBiliLoginStatus();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void showQualityDialog() {
        final String[] qualityOptions = {"自动", "4K", "2K", "1080p",
                "720p", "480p", "360p"};
        String currentQuality = tvQuality.getText().toString();
        int checkedItem = 0;
        for (int i = 0; i < qualityOptions.length; i++) {
            if (qualityOptions[i].equals(currentQuality)) {
                checkedItem = i;
                break;
            }
        }
        new android.app.AlertDialog.Builder(requireContext())
                .setTitle("选择默认画质")
                .setSingleChoiceItems(qualityOptions, checkedItem,
                        (dialog, which) -> {
                            String selected = qualityOptions[which];
                            sharedPreferences.edit()
                                    .putString(KEY_DEFAULT_QUALITY, selected)
                                    .apply();
                            tvQuality.setText(selected);
                            dialog.dismiss();
                        })
                .setNegativeButton("取消", null)
                .show();
    }
}
