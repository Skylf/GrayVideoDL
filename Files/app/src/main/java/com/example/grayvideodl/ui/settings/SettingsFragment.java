/*
 * SettingsFragment.java
 * 设置 Fragment：提供默认画质、调试日志、B站登录、版本更新等配置项。
 */

package com.example.grayvideodl.ui.settings;

import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.example.grayvideodl.R;
import com.example.grayvideodl.UpdateManager;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.materialswitch.MaterialSwitch;

import java.io.File;

public class SettingsFragment extends Fragment {

    private static final String TAG = "SettingsFrag";
    private static final String PREF_NAME = "grayvideodl_settings";
    private static final String KEY_DEBUG_LOG_ENABLED = "debug_log_enabled";
    private static final String KEY_DEFAULT_QUALITY = "default_quality";

    private MaterialSwitch switchDebugLog;
    private TextView tvQuality;
    private TextView tvVersion;
    private TextView tvBiliLoginStatus;
    private TextView tvBiliLoginAction;
    private TextView tvCheckUpdateAction;
    private MaterialCardView cardBiliLogin;
    private MaterialCardView cardCheckUpdate;

    private SharedPreferences sharedPreferences;

    // UpdateManager 实例，用于检查更新和下载
    private UpdateManager updateManager;

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

    /*
     * initViews: 初始化 UI 控件引用
     * @param view Fragment 的根布局
     */
    private void initViews(View view) {
        switchDebugLog = view.findViewById(R.id.switch_debug_log);
        tvQuality = view.findViewById(R.id.tv_quality);
        tvVersion = view.findViewById(R.id.tv_version);
        tvBiliLoginStatus = view.findViewById(R.id.tv_bili_login_status);
        tvBiliLoginAction = view.findViewById(R.id.tv_bili_login_action);
        tvCheckUpdateAction = view.findViewById(R.id.tv_check_update_action);
        cardBiliLogin = view.findViewById(R.id.card_bili_login);
        cardCheckUpdate = view.findViewById(R.id.card_check_update);
    }

    /*
     * restoreSettings: 从 SharedPreferences 恢复保存的设置
     */
    private void restoreSettings() {
        switchDebugLog.setChecked(
                sharedPreferences.getBoolean(KEY_DEBUG_LOG_ENABLED, false));
        tvQuality.setText(
                sharedPreferences.getString(KEY_DEFAULT_QUALITY, "自动"));
        // 通过 PackageManager 运行时读取版本号，避免依赖 Gradle 编译期生成的 BuildConfig
        String versionName = "0.9A"; // 兜底默认值
        try {
            versionName = requireContext().getPackageManager()
                    .getPackageInfo(requireContext().getPackageName(), 0).versionName;
        } catch (Exception e) {
            Log.w(TAG, "读取版本号失败", e);
        }
        tvVersion.setText("v" + (versionName != null ? versionName : "0.9A"));
        updateBiliLoginStatus();
    }

    /*
     * setupListeners: 设置所有交互控件的点击/切换监听器
     * @param view Fragment 的根布局
     */
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
                Log.d(TAG, "点击 B站 卡片：当前已登录，显示退出确认");
                showLogoutConfirm();
            } else {
                Log.d(TAG, "点击 B站 卡片：当前未登录，打开登录页面");
                BilibiliLoginFragment loginFragment =
                        new BilibiliLoginFragment();
                loginFragment.setCallback((success, msg) -> {
                    Log.d(TAG, "登录回调：success=" + success + ", msg=" + msg);
                    updateBiliLoginStatus();
                });
                loginFragment.show(getParentFragmentManager(),
                        "BilibiliLogin");
            }
        });

        // 检查更新卡片点击监听
        cardCheckUpdate.setOnClickListener(v -> checkForUpdate());
    }

    /*
     * checkForUpdate: 检查更新的入口方法
     * 调用 UpdateManager 异步检查 GitHub Releases 是否有新版本。
     */
    private void checkForUpdate() {
        // 修改按钮文字为"检查中..."，防止重复点击
        tvCheckUpdateAction.setText("检查中...");
        cardCheckUpdate.setEnabled(false);

        // 延迟创建 UpdateManager，确保 Fragment 已附着 Activity
        if (updateManager == null) {
            updateManager = new UpdateManager(requireContext());
        }

        /*
         * 调用 UpdateManager.checkUpdate() 发起异步检查。
         * UpdateManager 定义在：
         * (app/src/main/java/com/example/grayvideodl/UpdateManager.java)
         */
        updateManager.checkUpdate(new UpdateManager.UpdateListener() {
            @Override
            public void onNewVersionFound(String versionName, String releaseNotes, String apkUrl) {
                // 恢复按钮状态
                resetCheckUpdateButton();

                // 显示新版本对话框
                showUpdateDialog(versionName, releaseNotes, apkUrl);
            }

            @Override
            public void onNoNewVersion() {
                resetCheckUpdateButton();
                Toast.makeText(getContext(),
                        R.string.toast_no_new_version, Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onCheckError(String error) {
                resetCheckUpdateButton();
                String message = getString(R.string.toast_check_update_failed, error);
                Toast.makeText(getContext(), message, Toast.LENGTH_LONG).show();
            }
        });
    }

    /*
     * resetCheckUpdateButton: 恢复检查更新按钮的初始状态
     */
    private void resetCheckUpdateButton() {
        tvCheckUpdateAction.setText("检查");
        cardCheckUpdate.setEnabled(true);
    }

    /*
     * showUpdateDialog: 显示发现新版本的对话框
     * @param versionName  新版本号
     * @param releaseNotes 发布说明
     * @param apkUrl       APK 下载地址
     */
    private void showUpdateDialog(String versionName, String releaseNotes, String apkUrl) {
        String title = getString(R.string.update_dialog_title, versionName);
        String message = getString(R.string.update_dialog_message,
                releaseNotes != null ? releaseNotes : "暂无更新日志");

        new AlertDialog.Builder(requireContext())
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton(R.string.update_dialog_positive, (d, w) -> {
                    // 用户点击"立即更新"，开始下载
                    startDownload(apkUrl);
                })
                .setNegativeButton(R.string.update_dialog_negative, null)
                .show();
    }

    /*
     * startDownload: 开始下载 APK 并显示进度对话框
     * @param apkUrl APK 下载地址
     */
    private void startDownload(String apkUrl) {
        // 创建进度对话框
        AlertDialog.Builder builder = new AlertDialog.Builder(requireContext());
        builder.setTitle(R.string.downloading_title);
        builder.setMessage(R.string.downloading_message);
        builder.setCancelable(false);

        // 添加水平进度条
        ProgressBar progressBar = new ProgressBar(requireContext(), null,
                android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(100);
        progressBar.setPadding(48, 16, 48, 16);
        builder.setView(progressBar);

        AlertDialog progressDialog = builder.create();
        progressDialog.show();

        // 开始下载
        if (updateManager == null) {
            updateManager = new UpdateManager(requireContext());
        }

        /*
         * 调用 UpdateManager.downloadApk() 异步下载 APK。
         * UpdateManager 定义在：
         * (app/src/main/java/com/example/grayvideodl/UpdateManager.java)
         */
        updateManager.downloadApk(apkUrl, new UpdateManager.DownloadCallback() {
            @Override
            public void onProgress(int percent) {
                // progressBar 进度更新
                if (percent == UpdateManager.DownloadCallback.PROGRESS_UNKNOWN) {
                    // 服务器未返回 Content-Length（文件大小未知），使用不确定进度条动画
                    if (!progressBar.isIndeterminate()) {
                        progressBar.setIndeterminate(true);
                    }
                } else {
                    // 有明确的百分比进度
                    if (progressBar.isIndeterminate()) {
                        progressBar.setIndeterminate(false);
                    }
                    progressBar.setProgress(percent);
                }
            }

            @Override
            public void onSuccess(File apkFile) {
                // 关闭进度对话框
                if (progressDialog.isShowing()) {
                    progressDialog.dismiss();
                }
                // 安装 APK
                /*
                 * 调用 UpdateManager.installApk() 安装已下载的 APK。
                 * UpdateManager 定义在：
                 * (app/src/main/java/com/example/grayvideodl/UpdateManager.java)
                 */
                updateManager.installApk(apkFile);
            }

            @Override
            public void onFailure(String error) {
                // 关闭进度对话框
                if (progressDialog.isShowing()) {
                    progressDialog.dismiss();
                }
                String message = getString(R.string.toast_download_failed, error);
                Toast.makeText(getContext(), message, Toast.LENGTH_LONG).show();
            }
        });
    }

    /*
     * updateBiliLoginStatus: 更新 B站 登录状态显示
     */
    private void updateBiliLoginStatus() {
        boolean loggedIn = BilibiliLoginDialog.isLoggedIn(requireContext());
        Log.d(TAG, "updateBiliLoginStatus: isLoggedIn=" + loggedIn);
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

    /*
     * showLogoutConfirm: 显示退出登录确认对话框
     */
    private void showLogoutConfirm() {
        new AlertDialog.Builder(requireContext())
                .setTitle("退出登录")
                .setMessage("确定要退出B站登录吗？退出后高画质格式将被锁定。")
                .setPositiveButton("退出", (dialog, which) -> {
                    Log.d(TAG, "退出登录：开始清除登录状态");

                    // 1. 清除 SharedPreferences 登录状态（同步写入）
                    sharedPreferences.edit()
                            .putBoolean("bilibili_logged_in", false)
                            .putString("bilibili_raw_cookies", "")
                            .commit();
                    Log.d(TAG, "退出登录：SharedPreferences 已清除");

                    // 2. 直接删除 Cookie 文件（使用已知路径，不依赖 getCookieFilePath 的文件存在检查）
                    //    确保即使文件存在但上次检查失败也能被删除
                    java.io.File cookieFile = new java.io.File(
                            requireContext().getFilesDir(),
                            "bilibili_cookies.txt");
                    if (cookieFile.exists()) {
                        cookieFile.delete();
                        Log.d(TAG, "退出登录：Cookie 文件已删除");
                    } else {
                        Log.d(TAG, "退出登录：Cookie 文件不存在，跳过删除");
                    }

                    // 3. 清除 WebView 内部 Cookie 缓存（使用回调确保完成）
                    //    防止下次打开登录页时旧的 SESSDATA 导致自动登录
                    Log.d(TAG, "退出登录：开始清除 WebView Cookie...");
                    android.webkit.CookieManager.getInstance()
                            .removeAllCookies(value -> {
                                Log.d(TAG, "退出登录：Cookie 清除完成（value=" + value + "），清除 WebView 存储");
                                // Cookie 清除完成后，再清除 WebView 存储
                                android.webkit.WebStorage.getInstance()
                                        .deleteAllData();
                            });

                    updateBiliLoginStatus();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /*
     * showQualityDialog: 显示默认画质选择对话框
     */
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
        new AlertDialog.Builder(requireContext())
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