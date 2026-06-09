/*
 * BilibiliLoginFragment.java
 * B站 登录全屏页面：使用 DialogFragment 全屏展示 WebView，
 * 支持扫码登录/密码登录，登录后自动提取 Cookie。
 * 包含顶部加载进度条，修复键盘输入问题。
 */

package com.example.grayvideodl.ui.settings;

import android.app.Dialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.Fragment;

import com.example.grayvideodl.R;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

public class BilibiliLoginFragment extends DialogFragment {

    public interface LoginCallback {
        void onLoginResult(boolean success, String message);
    }

    private static final String PREF_NAME = "grayvideodl_settings";
    private static final String KEY_BILI_LOGGED_IN = "bilibili_logged_in";

    private WebView webView;
    private ProgressBar progressBar;
    private LoginCallback callback;

    public void setCallback(LoginCallback callback) {
        this.callback = callback;
    }

    @Override
    public void onAttach(@NonNull Context context) {
        super.onAttach(context);
        // 尝试从父 Fragment 获取回调
        if (callback == null) {
            Fragment parent = getParentFragment();
            if (parent instanceof LoginCallback) {
                callback = (LoginCallback) parent;
            }
        }
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 无框全屏样式：去掉对话框边框和间距，使 WebView 填满屏幕
        setStyle(DialogFragment.STYLE_NO_FRAME,
                android.R.style.Theme_NoTitleBar_Fullscreen);
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        Dialog dialog = super.onCreateDialog(savedInstanceState);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawableResource(android.R.color.white);
            window.setLayout(WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT);
            window.setSoftInputMode(
                    WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }
        return dialog;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(
                R.layout.fragment_bilibili_login, container, false);

        // 初始化控件
        webView = view.findViewById(R.id.webview_login);
        progressBar = view.findViewById(R.id.progress_bar);

        // 关闭按钮
        TextView btnClose = view.findViewById(R.id.btn_close);
        btnClose.setOnClickListener(v -> dismiss());

        // 配置 WebView
        setupWebView();

        return view;
    }

    private void setupWebView() {
        // 启用 JavaScript（B站登录页面需要）
        webView.getSettings().setJavaScriptEnabled(true);
        // 启用 DOM 存储（用于保存登录状态）
        webView.getSettings().setDomStorageEnabled(true);
        // 设置移动端 User-Agent
        webView.getSettings().setUserAgentString(
                "Mozilla/5.0 (Linux; Android 14; Pixel 8) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/125.0.0.0 Mobile Safari/537.36");
        // 允许加载图片
        webView.getSettings().setLoadsImagesAutomatically(true);
        // 启用表单保存（用于记住密码等）
        webView.getSettings().setSaveFormData(true);
        // 支持缩放
        webView.getSettings().setSupportZoom(true);
        webView.getSettings().setBuiltInZoomControls(true);
        webView.getSettings().setDisplayZoomControls(false);

        // 加载进度监听
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                super.onProgressChanged(view, newProgress);
                // 更新进度条
                if (newProgress < 100) {
                    progressBar.setVisibility(View.VISIBLE);
                    progressBar.setProgress(newProgress);
                } else {
                    progressBar.setVisibility(View.GONE);
                }
            }
        });

        // 页面导航监听
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                super.onPageStarted(view, url, favicon);
                progressBar.setVisibility(View.VISIBLE);
                progressBar.setProgress(0);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                progressBar.setVisibility(View.GONE);

                // 每次页面加载完成都尝试通过 JS 读取 Cookie
                // 检测到 SESSDATA 即认为登录成功
                injectCookieReader();
            }

            @Override
            public boolean shouldOverrideUrlLoading(
                    WebView view, WebResourceRequest request) {
                view.loadUrl(request.getUrl().toString());
                return true;
            }
        });

        // 清除之前会话的 Cookie（异步操作），确保每次打开都是全新登录页
        // 避免上次登录的 Cookie 残留导致自动登录或跳转
        // 关键修复：使用回调确保 Cookie 清除完成后再加载登录页面，
        // 防止旧的 SESSDATA 导致 B站自动重定向到主页并重新保存登录状态
        android.webkit.CookieManager.getInstance().removeAllCookies(() -> {
            // Cookie 清除完成后，再清除 WebView 存储并加载登录页
            android.webkit.WebStorage.getInstance().deleteAllData();

            // 加载 B站登录页面
            // B站登录页内置了二维码登录、手机号登录、密码登录多种方式
            webView.loadUrl("https://passport.bilibili.com/login");
        });
    }

    /*
     * injectCookieReader: 通过 JavaScript 读取页面 Cookie
     * 直接在 WebView 页面上下文中执行 document.cookie，
     * 比 CookieManager 更可靠（避免跨域/同步问题）
     */
    private void injectCookieReader() {
        // evaluateJavascript 在 API 19+ 可用（我们的 minSdk=29 完全支持）
        webView.evaluateJavascript(
            "(function() { return document.cookie; })();",
            value -> {
                // value 是 JS 返回的字符串，包含引号
                if (value == null || value.equals("null")
                        || value.equals("\"\"") || value.length() < 3) {
                    return;
                }
                // 去掉 JS 返回的双引号
                String cookies = value.substring(1, value.length() - 1);
                // 检查是否包含 B站 登录凭证 SESSDATA
                if (cookies.contains("SESSDATA")) {
                    saveCookiesFromJs(cookies);
                }
            }
        );
    }

    /*
     * saveCookiesFromJs: 通过 JS 获取的 Cookie 字符串保存到文件
     */
    private void saveCookiesFromJs(String rawCookies) {
        try {
            // 保存 Netscape 格式文件供 yt-dlp 使用
            File cookieFile = new File(requireContext().getFilesDir(),
                    "bilibili_cookies.txt");
            String netscapeCookies = jsToNetscapeFormat(rawCookies);
            FileOutputStream fos = new FileOutputStream(cookieFile);
            fos.write(netscapeCookies.getBytes(StandardCharsets.UTF_8));
            fos.close();

            // 同时保存到 SharedPreferences
            SharedPreferences prefs = requireContext()
                    .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
            prefs.edit()
                    .putBoolean(KEY_BILI_LOGGED_IN, true)
                    .putString("bilibili_raw_cookies", rawCookies)
                    .commit();

            // 通知外部登录成功
            if (callback != null) {
                callback.onLoginResult(true, "B站 登录成功");
            }

            dismiss();

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /*
     * jsToNetscapeFormat: JS Cookie 字符串 → Netscape 格式文件
     * 输入: "SESSDATA=xxx; bili_jct=yyy; buvid3=zzz"
     * 输出: Netscape HTTP Cookie File 格式
     */
    private String jsToNetscapeFormat(String rawCookies) {
        long expiry = System.currentTimeMillis() / 1000 + 365 * 86400;
        StringBuilder sb = new StringBuilder();
        sb.append("# Netscape HTTP Cookie File\n");
        sb.append("# Generated by GrayVideoDL\n\n");

        String[] entries = rawCookies.split(";");
        for (String entry : entries) {
            entry = entry.trim();
            if (entry.isEmpty()) continue;
            String[] parts = entry.split("=", 2);
            if (parts.length == 2) {
                sb.append(".bilibili.com\tTRUE\t/\tFALSE\t")
                  .append(expiry).append("\t")
                  .append(parts[0].trim()).append("\t")
                  .append(parts[1].trim()).append("\n");
            }
        }
        return sb.toString();
    }
}
