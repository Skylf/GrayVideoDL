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
import android.util.Log;
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

    // 日志标签，方便在 Logcat 中过滤
    private static final String TAG = "BiliLogin";

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
                Log.d(TAG, "onPageStarted: url=" + url);
                progressBar.setVisibility(View.VISIBLE);
                progressBar.setProgress(0);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                Log.d(TAG, "onPageFinished: url=" + url);
                progressBar.setVisibility(View.GONE);

                // 每次页面加载完成都尝试通过 Cookie 检测登录状态
                // 检测到 SESSDATA 即认为登录成功
                injectCookieReader();

                // URL 跳转检测：当页面跳转到 B站主站（非登录/通行证页面）时，说明登录成功
                // 此时通过 CookieManager 直接获取并保存 Cookie（可读取 httpOnly 的 SESSDATA）
                // 这是比 JS document.cookie 更可靠的检测方式（参考 BilibiliLoginDialog 的成功实现）
                if (url.contains("www.bilibili.com")
                        && !url.contains("login")
                        && !url.contains("passport")) {
                    Log.d(TAG, "onPageFinished: 检测到跳转到 B站主站，尝试通过 CookieManager 获取 Cookie");
                    String cookies = android.webkit.CookieManager.getInstance()
                            .getCookie(".bilibili.com");
                    Log.d(TAG, "onPageFinished: CookieManager.getCookie(.bilibili.com)="
                            + (cookies != null ? cookies.substring(0, Math.min(cookies.length(), 200)) : "null"));
                    if (cookies != null && cookies.contains("SESSDATA")) {
                        Log.d(TAG, "onPageFinished: 通过 CookieManager 检测到 SESSDATA，保存 Cookie");
                        saveCookiesFromJs(cookies);
                    } else {
                        Log.w(TAG, "onPageFinished: CookieManager 未检测到 SESSDATA（cookies="
                                + (cookies != null ? "非空但不含SESSDATA" : "null") + "）");
                    }
                }
            }

            @Override
            public boolean shouldOverrideUrlLoading(
                    WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();
                String method = request.getMethod();
                Log.d(TAG, "shouldOverrideUrlLoading: url=" + url + ", method=" + method);

                // 检测 B站 跨域登录 URL：登录成功后 B站 会通过跨域重定向传递登录凭证
                // URL 格式示例：
                // passport.biligame.com/x/passport-login/web/crossDomain?DedeUserID=xxx&SESSDATA=yyy&bili_jct=zzz
                // 注意：SESSDATA 仅出现在 URL 参数中，不会出现在 CookieManager 返回的 .bilibili.com Cookie 中
                if (url.contains("crossDomain") && url.contains("SESSDATA=")) {
                    Log.d(TAG, "shouldOverrideUrlLoading: 检测到跨域登录 URL，从参数中提取登录凭证");
                    extractCookiesFromUrl(url);
                }

                // 让 WebView 原生处理所有导航（包括 POST 表单提交、重定向等），
                // 确保 Set-Cookie 响应头被正确写入 CookieManager。
                // 不要手动拦截并重新 loadUrl，这会导致 POST 数据丢失和 Cookie 同步问题。
                return false;
            }
        });

        // 清除之前会话的 Cookie（异步操作），确保每次打开都是全新登录页
        // 避免上次登录的 Cookie 残留导致自动登录或跳转
        // 关键修复：使用回调确保 Cookie 清除完成后再加载登录页面，
        // 防止旧的 SESSDATA 导致 B站自动重定向到主页并重新保存登录状态
        Log.d(TAG, "setupWebView: 开始清除 Cookie...");
        android.webkit.CookieManager.getInstance().removeAllCookies(value -> {
            Log.d(TAG, "setupWebView: Cookie 清除完成（value=" + value + "），加载登录页面");
            // Cookie 清除完成后，再清除 WebView 存储并加载登录页
            android.webkit.WebStorage.getInstance().deleteAllData();

            // 加载 B站登录页面
            // B站登录页内置了二维码登录、手机号登录、密码登录多种方式
            webView.loadUrl("https://passport.bilibili.com/login");
        });
    }

    /*
     * injectCookieReader: 检测 B站 登录状态并保存 Cookie
     * 采用双重检测策略：
     *   1. JS document.cookie — 获取非 httpOnly 的 Cookie
     *   2. CookieManager — 获取所有 Cookie（包括 httpOnly，如 SESSDATA）
     * SESSDATA 是 B站 的 httpOnly Cookie，JavaScript 无法通过 document.cookie 读取，
     * 因此必须使用 CookieManager 作为 fallback 来检测登录成功。
     */
    private void injectCookieReader() {
        Log.d(TAG, "injectCookieReader: 开始检测 Cookie...");
        // 方法1: 通过 JS document.cookie 读取页面 Cookie（无法获取 httpOnly Cookie）
        webView.evaluateJavascript(
            "(function() { return document.cookie; })();",
            jsValue -> {
                String jsCookies = null;
                if (jsValue != null && !jsValue.equals("null")
                        && !jsValue.equals("\"\"") && jsValue.length() >= 3) {
                    // 去掉 JS 返回的双引号
                    jsCookies = jsValue.substring(1, jsValue.length() - 1);
                    Log.d(TAG, "injectCookieReader: JS document.cookie 返回长度="
                            + jsCookies.length() + "，包含SESSDATA="
                            + jsCookies.contains("SESSDATA"));
                } else {
                    Log.d(TAG, "injectCookieReader: JS document.cookie 返回空或无效（value=" + jsValue + "）");
                }

                // 方法2: 通过 CookieManager 读取 httpOnly Cookie（如 SESSDATA）
                String cmCookies = android.webkit.CookieManager.getInstance()
                        .getCookie(".bilibili.com");
                Log.d(TAG, "injectCookieReader: CookieManager.getCookie(.bilibili.com)="
                        + (cmCookies != null ? "非空（长度=" + cmCookies.length() + "，包含SESSDATA="
                            + cmCookies.contains("SESSDATA") + "）" : "null"));

                // 优先使用 JS 获得的完整 Cookie（包含所有非 httpOnly 字段）
                // 若 JS 未检测到 SESSDATA，则尝试 CookieManager
                String detectedCookies = null;
                if (jsCookies != null && jsCookies.contains("SESSDATA")) {
                    Log.d(TAG, "injectCookieReader: JS 检测到 SESSDATA，使用 JS Cookie");
                    detectedCookies = jsCookies;
                } else if (cmCookies != null && cmCookies.contains("SESSDATA")) {
                    Log.d(TAG, "injectCookieReader: CookieManager 检测到 SESSDATA，使用 CookieManager Cookie");
                    detectedCookies = cmCookies;
                }

                // 检测到 SESSDATA 即认为登录成功，保存 Cookie
                if (detectedCookies != null) {
                    Log.d(TAG, "injectCookieReader: 检测到 SESSDATA，调用 saveCookiesFromJs");
                    saveCookiesFromJs(detectedCookies);
                } else {
                    Log.d(TAG, "injectCookieReader: 未检测到 SESSDATA，暂不保存");
                }
            }
        );
    }

    /*
     * saveCookiesFromJs: 通过 JS 获取的 Cookie 字符串保存到文件
     */
    private void saveCookiesFromJs(String rawCookies) {
        Log.d(TAG, "saveCookiesFromJs: 开始保存 Cookie，长度="
                + (rawCookies != null ? rawCookies.length() : 0));
        try {
            // 保存 Netscape 格式文件供 yt-dlp 使用
            File cookieFile = new File(requireContext().getFilesDir(),
                    "bilibili_cookies.txt");
            String netscapeCookies = jsToNetscapeFormat(rawCookies);
            FileOutputStream fos = new FileOutputStream(cookieFile);
            fos.write(netscapeCookies.getBytes(StandardCharsets.UTF_8));
            fos.close();
            Log.d(TAG, "saveCookiesFromJs: 文件已保存到 " + cookieFile.getAbsolutePath());

            // 同时保存到 SharedPreferences
            SharedPreferences prefs = requireContext()
                    .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
            prefs.edit()
                    .putBoolean(KEY_BILI_LOGGED_IN, true)
                    .putString("bilibili_raw_cookies", rawCookies)
                    .commit();
            Log.d(TAG, "saveCookiesFromJs: SharedPreferences 已更新，bilibili_logged_in=true");

            // 通知外部登录成功
            if (callback != null) {
                Log.d(TAG, "saveCookiesFromJs: 通知回调 onLoginResult(true)");
                callback.onLoginResult(true, "B站 登录成功");
            } else {
                Log.w(TAG, "saveCookiesFromJs: callback 为 null，无法通知登录结果");
            }

            dismiss();

        } catch (Exception e) {
            Log.e(TAG, "saveCookiesFromJs: 保存 Cookie 失败", e);
            e.printStackTrace();
        }
    }

    /*
     * extractCookiesFromUrl: 从 B站 跨域登录 URL 中提取登录凭证并保存
     * B站 登录成功后，会通过跨域重定向将 SESSDATA、bili_jct、DedeUserID 等
     * 登录凭证放在 URL 查询参数中传递给子域（biligame、huasheng 等）。
     * CookieManager.getCookie(".bilibili.com") 无法获取这些凭证，
     * 因此需要从 URL 参数中手动提取。
     * 调用时机：shouldOverrideUrlLoading 拦截到 crossDomain+SESSDATA= 的 URL 时。
     */
    private void extractCookiesFromUrl(String url) {
        try {
            // 找到查询参数起始位置
            int queryIndex = url.indexOf('?');
            if (queryIndex < 0 || queryIndex >= url.length() - 1) {
                Log.w(TAG, "extractCookiesFromUrl: URL 中无查询参数");
                return;
            }
            String query = url.substring(queryIndex + 1);

            // 解析关键登录凭证参数
            String[] params = query.split("&");
            String sessdata = null;
            String biliJct = null;
            String dedeUserId = null;
            String dedeUserIdCkMd5 = null;

            for (String param : params) {
                String[] pair = param.split("=", 2);
                if (pair.length != 2) continue;
                String name = java.net.URLDecoder.decode(pair[0], "UTF-8");
                String value = java.net.URLDecoder.decode(pair[1], "UTF-8");
                switch (name) {
                    case "SESSDATA":
                        sessdata = value;
                        break;
                    case "bili_jct":
                        biliJct = value;
                        break;
                    case "DedeUserID":
                        dedeUserId = value;
                        break;
                    case "DedeUserID__ckMd5":
                        dedeUserIdCkMd5 = value;
                        break;
                }
            }

            // 必须有 SESSDATA 才认为有效
            if (sessdata == null || sessdata.isEmpty()) {
                Log.w(TAG, "extractCookiesFromUrl: URL 中未找到 SESSDATA 参数");
                return;
            }

            // 构建 Cookie 字符串
            StringBuilder sb = new StringBuilder();
            if (dedeUserId != null) {
                sb.append("DedeUserID=").append(dedeUserId);
            }
            if (sessdata != null) {
                if (sb.length() > 0) sb.append("; ");
                sb.append("SESSDATA=").append(sessdata);
            }
            if (biliJct != null) {
                if (sb.length() > 0) sb.append("; ");
                sb.append("bili_jct=").append(biliJct);
            }
            if (dedeUserIdCkMd5 != null) {
                if (sb.length() > 0) sb.append("; ");
                sb.append("DedeUserID__ckMd5=").append(dedeUserIdCkMd5);
            }

            String cookies = sb.toString();
            Log.d(TAG, "extractCookiesFromUrl: 从 URL 提取到登录凭证: " + cookies);
            saveCookiesFromJs(cookies);

        } catch (Exception e) {
            Log.e(TAG, "extractCookiesFromUrl: 解析失败", e);
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
