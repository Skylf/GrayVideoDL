/*
 * HomeFragment.java (v2)
 * 首页 Fragment：优化后的 UI，解析结果以卡片展示。
 * 支持：视频/音频分栏显示、合并开关联动、成功动画提示、折叠调试日志。
 */

package com.example.grayvideodl.ui.home;

import android.util.Log;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.TypedArray;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.chaquo.python.Python;
import com.chaquo.python.PyObject;
import com.chaquo.python.android.AndroidPlatform;
import android.os.Environment;

import com.example.grayvideodl.R;
import com.example.grayvideodl.FFmpegManager;
import com.example.grayvideodl.model.DownloadTask;
import com.example.grayvideodl.model.LogBuffer;
import com.example.grayvideodl.model.VideoInfo;
import com.example.grayvideodl.PlatformCookieManager;
import com.example.grayvideodl.ui.settings.BilibiliLoginDialog;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.textfield.TextInputEditText;

import org.json.JSONObject;

import java.io.File;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class HomeFragment extends Fragment {

    // 日志标签，用于调试下载流程
    private static final String TAG = "DownloadFlow";

    // ========== 控件声明 ==========
    private TextInputEditText etUrlInput;
    private MaterialButton btnParse, btnTestEnv, btnDownload;

    /*
     * URL_PATTERN: 用于从用户输入的文本中提取链接的正则表达式。
     * 支持 http/https 协议，匹配 URL 中常见的合法字符，
     * 确保结尾不以标点符号结束（避免匹配到句尾的句号/逗号）。
     */
    private static final Pattern URL_PATTERN = Pattern.compile(
            "https?://[-A-Za-z0-9+&@#/%?=~_|!:,.;]*[-A-Za-z0-9+&@#/%=~_|]");

    /*
     * PLATFORMS_WITHOUT_EXTRACTOR: yt-dlp 没有内置提取器的平台集合。
     * 这些平台的链接只能使用 yt-dlp 的通用提取器（generic extractor）进行解析，
     * 成功率较低。检测到用户输入这些平台的链接时，弹出警告提示。
     * 如需添加更多平台，在此集合中新增对应的 PLATFORM_* 常量即可。
     */
    private static final Set<String> PLATFORMS_WITHOUT_EXTRACTOR = new HashSet<>(
            Arrays.asList(
                    // 快手：yt-dlp 无专用提取器，通用提取器通常只能获取部分信息或失败
                    PlatformCookieManager.PLATFORM_KUAISHOU
                    // 后续发现其他无内置提取器的平台，在此追加
            ));

    // 结果卡片
    private MaterialCardView cardResult, cardError;
    private TextView tvVideoTitle, tvDuration, tvFormatCount;
    private LinearLayout layoutVideoFormats, layoutAudioFormats;
    private TextView tvVideoSectionTitle, tvAudioSectionTitle;

    // 错误卡片
    private TextView tvErrorDetail;

    // 成功提示浮层
    private LinearLayout layoutSuccessToast;

    // 平台兼容性警告浮层
    private LinearLayout layoutPlatformWarningToast;
    private TextView tvPlatformWarningText;

    // 画质警告横幅
    private LinearLayout layoutQualityWarning;
    private TextView tvWarningText;

    // 合并开关
    private MaterialSwitch switchMerge;

    // 状态
    private VideoInfo currentVideoInfo;
    private String selectedFormatId = "";
    private View selectedFormatView = null;
    private Handler mainHandler;

    // 加载状态对话框
    private AlertDialog loadingDialog;

    // 上次解析的 URL，用于重复检测
    private String lastParsedUrl = "";

    // SharedPreferences 名称和键名（与 SettingsFragment 保持一致）
    private static final String PREF_NAME = "grayvideodl_settings";
    private static final String KEY_MERGE_ENABLED = "merge_enabled";
    private static final String KEY_DEBUG_LOG_ENABLED = "debug_log_enabled";
    private static final String KEY_DEFAULT_QUALITY = "default_quality";

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_home, container, false);
        mainHandler = new Handler(Looper.getMainLooper());
        initViews(view);
        setupListeners();
        return view;
    }

    /*
     * onResume: Fragment 恢复可见时回调
     * 暂时保留以备后续需要
     */
    @Override
    public void onResume() {
        super.onResume();
    }

    private void initViews(View view) {
        etUrlInput = view.findViewById(R.id.et_url_input);
        btnParse = view.findViewById(R.id.btn_parse);
        btnTestEnv = view.findViewById(R.id.btn_test_env);
        btnDownload = view.findViewById(R.id.btn_download);

        cardResult = view.findViewById(R.id.card_result);
        tvVideoTitle = view.findViewById(R.id.tv_video_title);
        tvDuration = view.findViewById(R.id.tv_duration);
        tvFormatCount = view.findViewById(R.id.tv_format_count);
        layoutVideoFormats = view.findViewById(R.id.layout_video_formats);
        layoutAudioFormats = view.findViewById(R.id.layout_audio_formats);
        tvVideoSectionTitle = view.findViewById(R.id.tv_video_section_title);
        tvAudioSectionTitle = view.findViewById(R.id.tv_audio_section_title);

        cardError = view.findViewById(R.id.card_error);
        tvErrorDetail = view.findViewById(R.id.tv_error_detail);

        layoutSuccessToast = view.findViewById(R.id.layout_success_toast);

        // 平台兼容性警告浮层
        layoutPlatformWarningToast = view.findViewById(R.id.layout_platform_warning_toast);
        tvPlatformWarningText = view.findViewById(R.id.tv_platform_warning_text);

        // 画质警告横幅
        layoutQualityWarning = view.findViewById(R.id.layout_quality_warning);
        tvWarningText = view.findViewById(R.id.tv_warning_text);

        // 合并开关
        switchMerge = view.findViewById(R.id.switch_merge);

        // 从 SharedPreferences 恢复合并开关状态
        SharedPreferences prefs = requireContext()
                .getSharedPreferences(PREF_NAME, requireContext().MODE_PRIVATE);
        switchMerge.setChecked(prefs.getBoolean(KEY_MERGE_ENABLED, true));
    }

    private void setupListeners() {
        btnParse.setOnClickListener(v -> onParseClick());
        btnTestEnv.setOnClickListener(v -> onTestEnvClick());
        btnDownload.setOnClickListener(v -> onDownloadClick());

        // 合并开关：变化时保存到 SharedPreferences
        // 下一次解析时会自动读取最新状态
        switchMerge.setOnCheckedChangeListener((buttonView, isChecked) -> {
            requireContext().getSharedPreferences(PREF_NAME,
                    requireContext().MODE_PRIVATE)
                    .edit()
                    .putBoolean(KEY_MERGE_ENABLED, isChecked)
                    .apply();
        });
    }

    /* -------------------------- 解析逻辑 -------------------------- */

    /*
     * onParseClick: 解析按钮点击处理。
     * 处理流程:
     *   1. 从输入框中获取原始文本
     *   2. 使用正则表达式从中提取链接（支持用户输入包含链接的一段话）
     *   3. 检测链接对应的平台是否为 yt-dlp 无内置提取器的平台（如快手）
     *   4. 若为无内置提取器平台，弹出自动消失的模态警告
     *   5. 进行重复解析检测
     *   6. 调用 doParse 执行解析
     */
    private void onParseClick() {
        // 步骤1: 获取输入框原始文本
        String rawInput = etUrlInput.getText().toString().trim();
        if (rawInput.isEmpty()) {
            showToast("请先输入视频链接");
            appendLog("错误：链接为空");
            return;
        }

        // 步骤2: 使用正则表达式从输入文本中提取链接
        String url = extractUrlFromInput(rawInput);
        if (url == null) {
            // 未在输入中找到有效的 http/https 链接
            showToast("未找到有效的视频链接，请检查输入");
            appendLog("错误：输入中未找到有效链接 - " + rawInput);
            return;
        }

        // 若提取出的链接与原文本不同（说明输入中包含了额外文字），提示用户已自动提取
        if (!url.equals(rawInput)) {
            showToast("已自动提取链接");
            appendLog("从输入中提取链接: " + url);
        }

        // 步骤3: 检测链接对应的平台是否为 yt-dlp 无内置提取器的平台
        String platform = PlatformCookieManager.detectPlatform(url);
        if (PLATFORMS_WITHOUT_EXTRACTOR.contains(platform)) {
            // 步骤4: 对于无内置提取器的平台，弹出自动消失的模态警告
            showUnsupportedPlatformWarning(getPlatformDisplayName(platform));
        }

        // 步骤5: 重复解析检测
        if (url.equals(lastParsedUrl)) {
            // URL 与上次相同，弹出确认对话框
            new AlertDialog.Builder(requireContext())
                    .setTitle("重复解析")
                    .setMessage("当前链接与上次解析相同，是否继续？")
                    .setPositiveButton("继续", (dialog, which) -> {
                        doParse(url);
                    })
                    .setNegativeButton("取消", null)
                    .show();
            return;
        }

        // 步骤6: 执行解析
        doParse(url);
    }

    /*
     * extractUrlFromInput: 使用正则表达式从用户输入的文本中提取链接。
     * 支持用户粘贴包含链接的一段话（如"看看这个视频 https://xxx.com 怎么样"），
     * 自动从中提取出有效的 http/https 链接。
     *
     * @param input 用户输入的原始文本
     * @return 提取出的第一个有效链接，若未找到则返回 null
     */
    private String extractUrlFromInput(String input) {
        if (input == null || input.isEmpty()) {
            return null;
        }
        // 使用预编译的正则表达式匹配链接
        Matcher matcher = URL_PATTERN.matcher(input.trim());
        if (matcher.find()) {
            // 获取匹配到的链接
            String url = matcher.group();
            
            // 额外验证：确保URL以http/https开头
            if (url.startsWith("http://") || url.startsWith("https://")) {
                // 移除末尾可能的引号、反引号或其他特殊字符
                url = url.replaceAll("[\"'`\\s]+$", "");
                return url;
            }
        }
        return null;
    }

    /*
     * showUnsupportedPlatformWarning: 显示"平台暂未独立支持"的自动消失浮层警告。
     * 当检测到用户输入的链接属于 yt-dlp 没有内置提取器的平台（如快手）时调用，
     * 提示用户只能使用通用提取器，成功率较低。
     * 警告浮层会在 6 秒后自动消失，样式与解析成功提示框一致。
     *
     * @param platformName 平台的中文显示名称
     */
    private void showUnsupportedPlatformWarning(String platformName) {
        // 构建警告消息：包含平台名称和风险提示
        String warningMessage = platformName + " 暂未独立支持，只能使用通用提取器，大概率会失败";

        // 设置警告文本
        tvPlatformWarningText.setText(warningMessage);

        // 显示浮层并执行渐显动画
        layoutPlatformWarningToast.setVisibility(View.VISIBLE);
        layoutPlatformWarningToast.setAlpha(0f);

        // 渐显动画（400ms）
        AlphaAnimation fadeIn = new AlphaAnimation(0f, 1f);
        fadeIn.setDuration(400);
        layoutPlatformWarningToast.startAnimation(fadeIn);
        layoutPlatformWarningToast.setAlpha(1f);

        // 6 秒后渐隐消失
        mainHandler.postDelayed(() -> {
            AlphaAnimation fadeOut = new AlphaAnimation(1f, 0f);
            fadeOut.setDuration(600);
            fadeOut.setAnimationListener(new Animation.AnimationListener() {
                @Override public void onAnimationStart(Animation a) {}
                @Override public void onAnimationRepeat(Animation a) {}
                @Override public void onAnimationEnd(Animation a) {
                    layoutPlatformWarningToast.setVisibility(View.GONE);
                }
            });
            layoutPlatformWarningToast.startAnimation(fadeOut);
            layoutPlatformWarningToast.setAlpha(0f);
        }, 6000);
    }

    /*
     * hidePlatformWarningToast: 隐藏平台兼容性警告浮层
     */
    private void hidePlatformWarningToast() {
        layoutPlatformWarningToast.setVisibility(View.GONE);
        layoutPlatformWarningToast.setAlpha(0f);
    }

    /*
     * getPlatformDisplayName: 将平台标识常量转换为用户可读的中文显示名称。
     * 用于在警告提示框中向用户展示当前检测到的平台名称。
     *
     * @param platform 平台标识常量（PlatformCookieManager 中的 PLATFORM_*）
     * @return 平台的中文显示名称，未知平台返回原值
     */
    private String getPlatformDisplayName(String platform) {
        if (platform == null) {
            return "未知";
        }
        switch (platform) {
            case PlatformCookieManager.PLATFORM_BILIBILI:
                return "Bilibili（哔哩哔哩）";
            case PlatformCookieManager.PLATFORM_DOUYIN:
                return "抖音";
            case PlatformCookieManager.PLATFORM_KUAISHOU:
                return "快手";
            case PlatformCookieManager.PLATFORM_YOUTUBE:
                return "YouTube";
            case PlatformCookieManager.PLATFORM_TIKTOK:
                return "TikTok";
            case PlatformCookieManager.PLATFORM_TWITTER:
                return "Twitter / X";
            case PlatformCookieManager.PLATFORM_INSTAGRAM:
                return "Instagram";
            case PlatformCookieManager.PLATFORM_WEIBO:
                return "微博";
            case PlatformCookieManager.PLATFORM_XIAOHONGSHU:
                return "小红书";
            case PlatformCookieManager.PLATFORM_VQQ:
                return "腾讯视频";
            case PlatformCookieManager.PLATFORM_IQIYI:
                return "爱奇艺";
            case PlatformCookieManager.PLATFORM_YOUKU:
                return "优酷";
            case PlatformCookieManager.PLATFORM_TWITCH:
                return "Twitch";
            default:
                return platform;
        }
    }

    /*
     * doParse: 执行实际的视频解析逻辑
     * @param url 视频链接
     */
    private void doParse(String url) {
        // 记录本次解析的 URL
        lastParsedUrl = url;

        // 检测抖音图文链接（URL包含 /note/），图文没有视频资源，无法解析
        if (url.contains("/note/") || url.contains("douyin.com/note")) {
            showImageTextWarning("抖音");
            appendLog("检测到抖音图文链接，无法提取视频资源: " + url);
            return;
        }

        cardResult.setVisibility(View.GONE);
        cardError.setVisibility(View.GONE);
        btnDownload.setVisibility(View.GONE);
        selectedFormatId = "";
        selectedFormatView = null;
        hideSuccessToast();
        hideQualityWarning();

        // 显示加载状态
        setButtonsEnabled(false);
        showLoadingDialog("正在解析中...");

        appendLog("开始解析链接: " + url);

        new Thread(() -> {
            String result = callPythonFunction("extractVideoInfo", url);
            mainHandler.post(() -> {
                // 解析完成：恢复按钮 + 关闭加载对话框
                setButtonsEnabled(true);
                dismissLoadingDialog();

                currentVideoInfo = VideoInfo.parseFromJson(result);
                if (currentVideoInfo.isSuccess()) {
                    showVideoInfo(currentVideoInfo);
                    showSuccessToast();
                } else {
                    // 检测解析错误是否为 Unsupported URL（可能是图文链接）
                    String error = currentVideoInfo.getError();
                    if (error != null && error.contains("Unsupported URL")) {
                        // 再次检测是否为抖音链接
                        if (url.contains("douyin.com")) {
                            showImageTextWarning("抖音");
                            appendLog("解析失败：Unsupported URL，可能是图文链接");
                        } else {
                            showError(error);
                        }
                    } else {
                        showError(error);
                    }
                }
                appendLog("解析结果:\n" + result);
            });
        }).start();
    }

    /*
     * showImageTextWarning: 显示"图文链接无视频资源"的橙色警告浮层。
     * 当检测到用户输入的链接为图文（如抖音图文）时调用，
     * 提示用户该链接不存在视频资源，无法下载。
     * 警告浮层会在 6 秒后自动消失。
     *
     * @param platformName 平台的中文显示名称
     */
    private void showImageTextWarning(String platformName) {
        // 构建警告消息：图文链接无视频资源
        String warningMessage = "无法提取视频资源！该链接为" + platformName + "图文，不存在视频资源";

        // 设置警告文本
        tvPlatformWarningText.setText(warningMessage);

        // 显示浮层并执行渐显动画
        layoutPlatformWarningToast.setVisibility(View.VISIBLE);
        layoutPlatformWarningToast.setAlpha(0f);

        // 渐显动画（400ms）
        AlphaAnimation fadeIn = new AlphaAnimation(0f, 1f);
        fadeIn.setDuration(400);
        layoutPlatformWarningToast.startAnimation(fadeIn);
        layoutPlatformWarningToast.setAlpha(1f);

        // 6 秒后渐隐消失
        mainHandler.postDelayed(() -> {
            AlphaAnimation fadeOut = new AlphaAnimation(1f, 0f);
            fadeOut.setDuration(600);
            fadeOut.setAnimationListener(new Animation.AnimationListener() {
                @Override public void onAnimationStart(Animation a) {}
                @Override public void onAnimationRepeat(Animation a) {}
                @Override public void onAnimationEnd(Animation a) {
                    layoutPlatformWarningToast.setVisibility(View.GONE);
                }
            });
            layoutPlatformWarningToast.startAnimation(fadeOut);
            layoutPlatformWarningToast.setAlpha(0f);
        }, 6000);
    }

    /*
     * showVideoInfo: 展示视频信息，根据合并开关分栏显示格式
     */
    private void showVideoInfo(VideoInfo info) {
        tvVideoTitle.setText(info.getTitle());
        tvDuration.setText("时长: " + info.getDurationText());

        // 读取合并开关状态
        SharedPreferences prefs = requireContext()
                .getSharedPreferences(PREF_NAME, requireContext().MODE_PRIVATE);
        boolean mergeEnabled = prefs.getBoolean(KEY_MERGE_ENABLED, true);

        // 聚合视频格式（按画质去重，每种画质只保留一个最佳格式）
        // yt-dlp 对同一分辨率会返回多种编码（h264/h265/AV1）和纯视频流，
        // aggregateByQuality 会按 360p/720p/1080p 分组并选最优格式
        List<VideoInfo.Format> videoFormats = info.aggregateByQuality();
        // 收集音频格式列表（不合并模式时展示）
        List<VideoInfo.Format> audioFormats = new java.util.ArrayList<>();
        for (VideoInfo.Format fmt : info.getFormats()) {
            if (fmt.isAudioOnly()) {
                audioFormats.add(fmt);
            }
        }

        // 更新统计文本（使用去重后的视频格式数量）
        tvFormatCount.setText(videoFormats.size() + " 种画质");
        if (!audioFormats.isEmpty()) {
            tvFormatCount.append(" · " + audioFormats.size() + " 种音质");
        }

        // 添加提示文字：提醒用户点击列表选择画质
        layoutVideoFormats.removeAllViews();
        layoutVideoFormats.addView(createFormatHintView());
        // 添加表格列标题行（画质 | 格式 | 编码方式 | 大小）
        layoutVideoFormats.addView(createTableHeader(false));
        for (VideoInfo.Format fmt : videoFormats) {
            layoutVideoFormats.addView(createFormatRow(fmt));
        }

        if (mergeEnabled) {
            // 合并模式：隐藏音频区域
            tvAudioSectionTitle.setVisibility(View.GONE);
            layoutAudioFormats.setVisibility(View.GONE);
        } else {
            // 不合并：显示音频区域
            tvAudioSectionTitle.setVisibility(View.VISIBLE);
            layoutAudioFormats.setVisibility(View.VISIBLE);
            layoutAudioFormats.removeAllViews();
            // 添加表格列标题行（音频：音频 | 格式 | 编码方式 | 大小 | 操作）
            layoutAudioFormats.addView(createTableHeader(true));
            for (VideoInfo.Format fmt : audioFormats) {
                layoutAudioFormats.addView(createFormatRow(fmt));
            }
        }

        cardResult.setVisibility(View.VISIBLE);
        btnDownload.setVisibility(View.VISIBLE);
        btnDownload.setEnabled(false);
        btnDownload.setText("请先选择画质");

        // 根据默认画质设置自动选中最匹配的格式
        String defaultQuality = prefs.getString(KEY_DEFAULT_QUALITY, "自动");
        autoSelectFormat(videoFormats, audioFormats, defaultQuality, mergeEnabled);
    }

    /*
     * autoSelectFormat: 根据默认画质设置自动选中最匹配的格式
     * 如果找不到满足要求的画质，自动选择最高可用画质并弹出提示
     */
    private void autoSelectFormat(List<VideoInfo.Format> videoFormats,
                                   List<VideoInfo.Format> audioFormats,
                                   String defaultQuality, boolean mergeEnabled) {
        // 1. 确定要搜索的格式列表
        List<VideoInfo.Format> searchList = new java.util.ArrayList<>();
        // 先在视频格式中搜索
        if (videoFormats != null) searchList.addAll(videoFormats);
        // 不合并模式下也搜索音频格式（但画质匹配只针对视频）
        if (!mergeEnabled && audioFormats != null) searchList.addAll(audioFormats);

        if (searchList.isEmpty()) return;

        // 2. 将默认画质转换为最低高度阈值
        int minHeight = qualityToMinHeight(defaultQuality);

        // 3. 找最佳匹配的格式
        VideoInfo.Format bestMatch = null;
        VideoInfo.Format highestFormat = null;
        int bestHeight = 0;
        int highestHeight = 0;

        // 在视频格式中查找
        List<VideoInfo.Format> targets = (mergeEnabled || defaultQuality.equals("自动"))
                ? videoFormats : searchList;

        for (VideoInfo.Format fmt : targets) {
            int height = parseResolutionHeight(fmt.getResolution());
            if (height > highestHeight) {
                highestHeight = height;
                highestFormat = fmt;
            }
            // 找最接近且不低于阈值的格式
            if (height >= minHeight && height >= bestHeight) {
                bestHeight = height;
                bestMatch = fmt;
            }
        }

        // 4. 如果没找到满足阈值的，用最高画质并弹出提示
        if (bestMatch == null && highestFormat != null) {
            bestMatch = highestFormat;
            final VideoInfo.Format finalBest = bestMatch;
            final String targetQuality = defaultQuality;
            // 延迟显示警告横幅，等 UI 渲染完成后再显示
            mainHandler.postDelayed(() -> showQualityWarning(
                    targetQuality, finalBest), 300);
        }

        // 5. 自动选中格式
        if (bestMatch != null) {
            final VideoInfo.Format finalMatch = bestMatch;
            // 在对应的格式列表中查找并点击
            mainHandler.post(() -> {
                // 在视频格式列表中查找
                for (int i = 0; i < layoutVideoFormats.getChildCount(); i++) {
                    View child = layoutVideoFormats.getChildAt(i);
                    if (child instanceof MaterialCardView) {
                        child.performClick();
                        return;
                    }
                }
                // 如果在视频列表中没找到（可能在音频列表中）
                if (!mergeEnabled) {
                    for (int i = 0; i < layoutAudioFormats.getChildCount(); i++) {
                        View child = layoutAudioFormats.getChildAt(i);
                        if (child instanceof MaterialCardView) {
                            child.performClick();
                            return;
                        }
                    }
                }
            });
        }
    }

    /*
     * qualityToMinHeight: 将画质名称转换为最低高度像素阈值
     */
    private int qualityToMinHeight(String quality) {
        switch (quality) {
            case "4K":    return 2160;
            case "2K":    return 1440;
            case "1080p": return 1080;
            case "720p":  return 720;
            case "480p":  return 480;
            case "360p":  return 360;
            default:      return 0;  // 自动 = 0，选最高
        }
    }

    /*
     * parseResolutionHeight: 从分辨率字符串中解析高度值
     * "1920x1080" → 1080, "640x360" → 360, "audio only" → 0
     */
    private int parseResolutionHeight(String resolution) {
        if (resolution == null || resolution.isEmpty()) return 0;
        try {
            // 格式通常为 "640x360" 或 "1920x1080"
            String[] parts = resolution.split("x");
            if (parts.length == 2) {
                return Integer.parseInt(parts[1].trim());
            }
        } catch (NumberFormatException e) {
            // 解析失败时忽略
        }
        return 0;
    }

    /*
     * showQualityWarning: 在按钮下方显示画质警告横幅
     * 替代原来的 AlertDialog 模态框，改为页面内联提示
     */
    private void showQualityWarning(String targetQuality,
                                     VideoInfo.Format actualFormat) {
        String actualQuality;
        int height = parseResolutionHeight(actualFormat.getResolution());
        if (height >= 2160) actualQuality = "4K";
        else if (height >= 1440) actualQuality = "2K";
        else if (height >= 1080) actualQuality = "1080p";
        else if (height >= 720) actualQuality = "720p";
        else if (height >= 480) actualQuality = "480p";
        else actualQuality = "360p";

        String warningText = "该视频画质不满足默认画质设置要求"
                + "（您的设置：" + targetQuality
                + "，最高可用：" + actualQuality + "）"
                + "，已为您自动选择 " + actualQuality;

        tvWarningText.setText(warningText);
        layoutQualityWarning.setVisibility(View.VISIBLE);
    }

    /*
     * hideQualityWarning: 隐藏画质警告横幅
     */
    private void hideQualityWarning() {
        layoutQualityWarning.setVisibility(View.GONE);
    }

    /*
     * createTableHeader: 创建表格列标题行
     * 为格式列表添加标题行，区分视频和音频。
     * 标题行使用浅蓝灰背景，深蓝灰粗体文字，视觉上作为表格头部。
     * @param isAudio true表示音频列表标题，false表示视频列表标题
     */
    private View createTableHeader(boolean isAudio) {
        // 标题卡片：圆角矩形，浅蓝灰背景，无边框
        MaterialCardView headerCard = new MaterialCardView(requireContext());
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, 0, 0, 4);
        headerCard.setLayoutParams(params);
        headerCard.setStrokeWidth(0);
        headerCard.setCardElevation(0);
        headerCard.setContentPadding(12, 12, 12, 12);
        headerCard.setRadius(6);
        headerCard.setEnabled(false);
        headerCard.setBackgroundTintList(
                android.content.res.ColorStateList.valueOf(
                        getResources().getColor(R.color.table_header_bg)));

        LinearLayout headerRow = new LinearLayout(requireContext());
        headerRow.setOrientation(LinearLayout.HORIZONTAL);
        headerRow.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        // 列标题
        String[] headers;
        if (isAudio) {
            headers = new String[]{"音频", "格式", "编码方式", "大小"};
        } else {
            headers = new String[]{"画质", "格式", "编码方式", "大小"};
        }
        float[] weights = {1.2f, 0.6f, 0.8f, 1.0f};

        for (int i = 0; i < headers.length; i++) {
            TextView headerView = new TextView(requireContext());
            headerView.setLayoutParams(new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, weights[i]));
            headerView.setTextSize(12);
            headerView.setText(headers[i]);
            headerView.setTextColor(getResources().getColor(
                    R.color.table_header_text));
            headerView.setPadding(4, 6, 4, 6);
            headerView.setTypeface(null, android.graphics.Typeface.BOLD);
            headerRow.addView(headerView);
        }

        headerCard.addView(headerRow);
        return headerCard;
    }

    /*
     * createFormatHintView: 创建格式列表上方的提示文字
     * 提示用户点击表格中的某一列选择画质，否则无法下载。
     * 返回一个带有信息图标的浅色提示条，置于表格上方。
     */
    private View createFormatHintView() {
        LinearLayout hintLayout = new LinearLayout(requireContext());
        hintLayout.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 0, 8);
        hintLayout.setLayoutParams(lp);
        hintLayout.setPadding(12, 8, 12, 8);
        hintLayout.setBackgroundResource(R.drawable.bg_platform_warning_toast);

        // 左侧提示图标
        TextView iconView = new TextView(requireContext());
        iconView.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        iconView.setText("💡");
        iconView.setTextSize(14);

        // 右侧提示文字
        TextView hintView = new TextView(requireContext());
        hintView.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f));
        hintView.setPadding(6, 0, 0, 0);
        hintView.setTextSize(12);
        hintView.setText("点击下方列表项选择画质，选中后即可下载");
        hintView.setTextColor(getResources().getColor(R.color.warning_text));

        hintLayout.addView(iconView);
        hintLayout.addView(hintView);
        return hintLayout;
    }

    /*
     * createFormatRow: 创建一个可点击的格式选择行（表格卡片样式）
     * 每行是一个圆角白色卡片，带浅灰边框。
     * 视频行文字色使用 table_text_primary（深灰），大小列使用 table_text_secondary（中灰）。
     * 选中时卡片边框变为蓝色，背景变为浅蓝。
     */
    private View createFormatRow(VideoInfo.Format format) {
        MaterialCardView card = new MaterialCardView(requireContext());
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, 0, 0, 6);
        card.setLayoutParams(params);
        card.setStrokeWidth(3);
        card.setStrokeColor(getResources().getColor(R.color.table_card_border));
        card.setCardElevation(1);
        card.setContentPadding(12, 12, 12, 12);
        card.setRadius(6);
        card.setClickable(true);
        card.setFocusable(true);
        card.setCardBackgroundColor(getResources().getColor(R.color.table_card_bg));
        // 使用主题水波纹背景作为前景，增强点击视觉反馈
        TypedArray a = requireContext().obtainStyledAttributes(
                new int[]{android.R.attr.selectableItemBackground});
        android.graphics.drawable.Drawable selectableBg = a.getDrawable(0);
        a.recycle();
        card.setForeground(selectableBg);

        // 整体行布局：水平排列的表格列
        LinearLayout tableRow = new LinearLayout(requireContext());
        tableRow.setOrientation(LinearLayout.HORIZONTAL);
        tableRow.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        if (format.isAudioOnly()) {
            // ===== 音频格式行（背景色与画质解析结果一致） =====

            String[] columns = {format.getExt(), format.getAcodec(),
                    format.getFilesizeText()};
            float[] weights = {1.2f, 0.6f, 0.8f, 1.0f};
            // 音频标签（第一列）
            TextView audioLabel = new TextView(requireContext());
            audioLabel.setLayoutParams(new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, weights[0]));
            audioLabel.setTextSize(13);
            audioLabel.setText("音频");
            audioLabel.setTextColor(getResources().getColor(
                    R.color.table_text_primary));
            audioLabel.setPadding(4, 10, 4, 10);
            audioLabel.setTypeface(null, android.graphics.Typeface.BOLD);
            tableRow.addView(audioLabel);
            // 其余数据列
            for (int i = 0; i < columns.length; i++) {
                TextView col = new TextView(requireContext());
                col.setLayoutParams(new LinearLayout.LayoutParams(
                        0, LinearLayout.LayoutParams.WRAP_CONTENT,
                        weights[i + 1]));
                col.setTextSize(13);
                col.setText(columns[i]);
                // 大小列使用次要色，其余列使用主色
                if (i == columns.length - 1) {
                    col.setTextColor(getResources().getColor(
                            R.color.table_text_secondary));
                } else {
                    col.setTextColor(getResources().getColor(
                            R.color.table_text_primary));
                }
                col.setPadding(4, 10, 4, 10);
                col.setEllipsize(android.text.TextUtils.TruncateAt.END);
                col.setMaxLines(1);
                tableRow.addView(col);
            }

            // 锁定格式：灰色不可点击
            if (format.isLocked()) {
                card.setEnabled(false);
                card.setAlpha(0.45f);
            } else {
                card.setOnClickListener(v -> selectFormat(card, format));
            }
        } else {
            // ===== 视频格式行 =====
            String vcodec = format.getVcodec();
            String codecSimple = simplifyCodec(vcodec != null ? vcodec : "");
            String[] columns = {format.getResolutionDisplay(), format.getExt(),
                    codecSimple, format.getFilesizeText()};
            float[] weights = {1.2f, 0.6f, 0.8f, 1.0f};
            for (int i = 0; i < columns.length; i++) {
                TextView col = new TextView(requireContext());
                col.setLayoutParams(new LinearLayout.LayoutParams(
                        0, LinearLayout.LayoutParams.WRAP_CONTENT,
                        weights[i]));
                col.setTextSize(13);
                col.setText(columns[i]);
                // 画质列使用粗体，大小列使用次要色
                if (i == 0) {
                    col.setTypeface(null, android.graphics.Typeface.BOLD);
                    col.setTextColor(getResources().getColor(
                            R.color.table_text_primary));
                } else if (i == columns.length - 1) {
                    col.setTextColor(getResources().getColor(
                            R.color.table_text_secondary));
                } else {
                    col.setTextColor(getResources().getColor(
                            R.color.table_text_primary));
                }
                col.setPadding(4, 10, 4, 10);
                col.setEllipsize(android.text.TextUtils.TruncateAt.END);
                col.setMaxLines(1);
                tableRow.addView(col);
            }

            if (format.isLocked()) {
                // 锁定格式：灰色不可点击
                card.setEnabled(false);
                card.setAlpha(0.45f);
            } else {
                card.setOnClickListener(v -> selectFormat(card, format));
            }
        }

        card.addView(tableRow);
        return card;
    }

    /*
     * simplifyCodec: 简化视频编码名称，将 yt-dlp 返回的编码标识转为可读形式
     * avc1.640028 → h264,  hevc → h265,  av01.0.05M.08 → av1
     * @param vcodec yt-dlp 返回的原始视频编码标识
     * @return 简化的编码名称
     */
    private String simplifyCodec(String vcodec) {
        if (vcodec == null || vcodec.isEmpty() || "none".equals(vcodec)) {
            return "";
        }
        String lower = vcodec.toLowerCase();
        if (lower.contains("avc") || lower.contains("h264")) {
            return "h264";
        } else if (lower.contains("hev") || lower.contains("h265")) {
            return "h265";
        } else if (lower.contains("av01") || lower.contains("av1")) {
            return "av1";
        } else if (lower.contains("vp9")) {
            return "vp9";
        } else if (lower.contains("vp8")) {
            return "vp8";
        }
        // 其他编码直接返回原始值的前8个字符
        return vcodec.length() > 8 ? vcodec.substring(0, 8) + "…" : vcodec;
    }

    /*
     * selectFormat: 选中某个格式，高亮并启用下载按钮
     */
    private void selectFormat(MaterialCardView selectedCard, VideoInfo.Format format) {
        selectedFormatId = format.getFormatId();

        // 更新下载按钮文本
        String btnText;
        if (format.isAudioOnly()) {
            btnText = "下载音频 · " + format.getAcodec();
        } else {
            btnText = "下载 · " + format.getResolutionDisplay();
        }
        btnDownload.setEnabled(true);
        btnDownload.setText(btnText);

        // 高亮选中的卡片：蓝色边框 + 浅蓝背景
        LinearLayout parent = (LinearLayout) selectedCard.getParent();
        for (int i = 0; i < parent.getChildCount(); i++) {
            View child = parent.getChildAt(i);
            if (child instanceof MaterialCardView) {
                MaterialCardView c = (MaterialCardView) child;
                if (c == selectedCard) {
                    c.setStrokeColor(getResources().getColor(
                            R.color.table_selected_border));
                    c.setCardBackgroundColor(getResources().getColor(
                            R.color.table_selected_bg));
                } else {
                    c.setStrokeColor(getResources().getColor(
                            R.color.table_card_border));
                    c.setCardBackgroundColor(getResources().getColor(
                            R.color.table_card_bg));
                }
            }
        }
    }

    /* -------------------------- 成功提示浮层 -------------------------- */

    private void showSuccessToast() {
        // 如果警告浮层正在显示，延迟显示成功提示（等待警告消失）
        if (layoutPlatformWarningToast.getVisibility() == View.VISIBLE) {
            // 警告浮层显示6秒后消失，延迟7秒后再显示成功提示
            mainHandler.postDelayed(this::showSuccessToastInternal, 7000);
            return;
        }
        
        showSuccessToastInternal();
    }

    /**
     * 显示成功提示浮层（内部方法）
     */
    private void showSuccessToastInternal() {
        layoutSuccessToast.setVisibility(View.VISIBLE);
        layoutSuccessToast.setAlpha(0f);

        // 渐显动画
        AlphaAnimation fadeIn = new AlphaAnimation(0f, 1f);
        fadeIn.setDuration(400);
        layoutSuccessToast.startAnimation(fadeIn);
        layoutSuccessToast.setAlpha(1f);

        // 5 秒后渐隐消失
        mainHandler.postDelayed(() -> {
            AlphaAnimation fadeOut = new AlphaAnimation(1f, 0f);
            fadeOut.setDuration(600);
            fadeOut.setAnimationListener(new Animation.AnimationListener() {
                @Override public void onAnimationStart(Animation a) {}
                @Override public void onAnimationRepeat(Animation a) {}
                @Override public void onAnimationEnd(Animation a) {
                    layoutSuccessToast.setVisibility(View.GONE);
                }
            });
            layoutSuccessToast.startAnimation(fadeOut);
            layoutSuccessToast.setAlpha(0f);
        }, 5000);
    }

    private void hideSuccessToast() {
        layoutSuccessToast.setVisibility(View.GONE);
        layoutSuccessToast.setAlpha(0f);
    }

    /* -------------------------- 错误 / 测试 / Python -------------------------- */

    private void showError(String msg) {
        tvErrorDetail.setText(msg);
        cardError.setVisibility(View.VISIBLE);
    }

    private void onTestEnvClick() {
        appendLog("开始完整环境检测...");

        // 禁用按钮防止重复点击
        setButtonsEnabled(false);
        showLoadingDialog("正在全面检测环境...");

        new Thread(() -> {
            // ========== 1. 检测 Python / yt-dlp 环境 ==========
            boolean pythonOk = false;
            try {
                String envInfo = callPythonFunction("testEnvironment", "");
                appendLog("Python 环境检测结果:\n" + envInfo);
                JSONObject json = new JSONObject(envInfo);
                pythonOk = json.optBoolean("yt_dlp_installed", false)
                        && "ok".equals(json.optString("status", ""));
            } catch (Exception e) {
                appendLog("Python 环境检测异常: " + e.getMessage());
            }

            // ========== 2. 检测 FFmpeg 环境 ==========
            boolean ffmpegOk = false;
            FFmpegManager ffManager = FFmpegManager.getInstance();
            ffmpegOk = ffManager.isFfmpegAvailable();
            if (!ffmpegOk) {
                // 尝试检查 files 目录下的 ffmpeg
                File fallbackFile = new File(
                        requireContext().getFilesDir(), "ffmpeg");
                if (fallbackFile.exists()) {
                    ffmpegOk = true;
                }
            }
            appendLog("FFmpeg 检测: " + (ffmpegOk ? "已就绪" : "未就绪"));

            // ========== 3. 检测存储目录 ==========
            boolean storageOk = false;
            String storageMsg = "";
            try {
                File downloadDir = new File(
                        Environment.getExternalStoragePublicDirectory(
                                Environment.DIRECTORY_DOWNLOADS),
                        "GrayVideoDL");
                if (!downloadDir.exists()) {
                    boolean created = downloadDir.mkdirs();
                    storageMsg = created ? "目录已创建" : "目录创建失败";
                } else {
                    storageMsg = "目录正常";
                }
                boolean canWrite = downloadDir.exists()
                        && downloadDir.canWrite();
                storageOk = downloadDir.exists() && canWrite;
                if (!canWrite) {
                    storageMsg = "目录无写入权限";
                }
                appendLog("存储目录检测: " + storageMsg);
            } catch (Exception e) {
                storageMsg = "检测异常: " + e.getMessage();
                appendLog("存储目录检测异常: " + e.getMessage());
            }

            // ========== 4. 检测网络连通性 ==========
            boolean networkOk = false;
            String networkMsg = "";
            try {
                // 简单检测：尝试建立 HTTP 连接到常用 CDN
                java.net.URL url = new java.net.URL("https://www.baidu.com");
                java.net.HttpURLConnection conn =
                        (java.net.HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(3000);
                conn.setReadTimeout(2000);
                conn.setRequestMethod("HEAD");
                int responseCode = conn.getResponseCode();
                networkOk = responseCode == java.net.HttpURLConnection.HTTP_OK;
                networkMsg = "网络正常 (响应码: " + responseCode + ")";
                conn.disconnect();
                appendLog("网络连通性: " + networkMsg);
            } catch (Exception e) {
                networkMsg = "无法连接网络";
                appendLog("网络连通性: " + networkMsg);
            }

            // ========== 5. 检测 Cookie 文件状态 ==========
            boolean cookieOk = false;
            String cookieMsg = "";
            try {
                File cookieDir = new File(
                        requireContext().getFilesDir(), "cookies");
                if (cookieDir.exists() && cookieDir.isDirectory()) {
                    File[] cookieFiles = cookieDir.listFiles(
                            (dir, name) -> name.endsWith(".txt"));
                    if (cookieFiles != null && cookieFiles.length > 0) {
                        cookieOk = true;
                        cookieMsg = "已配置 "
                                + cookieFiles.length + " 个 Cookies";
                    } else {
                        cookieMsg = "未找到 Cookie 文件（非会员无需配置）";
                    }
                } else {
                    cookieMsg = "未配置 Cookie（非会员无需配置）";
                }
                appendLog("Cookie 状态: " + cookieMsg);
            } catch (Exception e) {
                cookieMsg = "检测异常";
                appendLog("Cookie 检测异常: " + e.getMessage());
            }

            final boolean finalPythonOk = pythonOk;
            final boolean finalFfmpegOk = ffmpegOk;
            final boolean finalStorageOk = storageOk;
            final String finalStorageMsg = storageMsg;
            final boolean finalNetworkOk = networkOk;
            final String finalNetworkMsg = networkMsg;
            final boolean finalCookieOk = cookieOk;
            final String finalCookieMsg = cookieMsg;

            // 在主线程处理结果
            mainHandler.post(() -> {
                setButtonsEnabled(true);
                dismissLoadingDialog();

                // 显示完整环境检测结果
                showTestResultDialog(
                        finalPythonOk,
                        finalFfmpegOk,
                        finalStorageOk, finalStorageMsg,
                        finalNetworkOk, finalNetworkMsg,
                        finalCookieOk, finalCookieMsg);
            });
        }).start();
    }

    /*
     * onDownloadClick: 下载按钮点击处理
     * 使用 yt-dlp 下载选中的视频格式
     */
    private void onDownloadClick() {
        if (currentVideoInfo == null || selectedFormatId.isEmpty()) {
            showToast("请先选择要下载的格式");
            return;
        }

        // 获取当前选中的格式信息
        VideoInfo.Format selectedFormat = null;
        for (VideoInfo.Format fmt : currentVideoInfo.getFormats()) {
            if (fmt.getFormatId().equals(selectedFormatId)) {
                selectedFormat = fmt;
                break;
            }
        }
        if (selectedFormat == null) {
            showToast("所选格式无效");
            return;
        }

        // 下载确认
        final String formatDesc = selectedFormat.getResolutionDisplay();
        final VideoInfo.Format selected = selectedFormat;
        new AlertDialog.Builder(requireContext())
                .setTitle("确认下载")
                .setMessage("视频：" + currentVideoInfo.getTitle()
                        + "\n画质：" + formatDesc)
                .setPositiveButton("开始下载", (dialog, which) -> {
                    startDownload(selected);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /*
     * startDownload: 执行视频下载（v2 版本）
     * 将下载任务添加到下载列表，由 DownloadFragment 统一管理下载执行和进度回调。
     * 不再在此处直接执行下载，而是创建任务后导航到下载 Tab。
     */
    private void startDownload(VideoInfo.Format format) {
        // 创建下载任务记录
        // 注意：使用 extractUrlFromInput 从原始输入中提取纯链接，避免将非URL内容传给yt-dlp
        String rawInput = etUrlInput.getText().toString().trim();
        String extractedUrl = extractUrlFromInput(rawInput);
        // 如果提取到链接则使用提取后的链接，否则回退使用原始输入
        String finalUrl = (extractedUrl != null) ? extractedUrl : rawInput;
        Log.d(TAG, "startDownload: 原始输入=" + rawInput + ", 提取后URL=" + finalUrl);

        final DownloadTask task = new DownloadTask();
        task.setUrl(finalUrl);
        task.setTitle(currentVideoInfo.getTitle());
        task.setFormatId(format.getFormatId());
        task.setResolution(format.getResolutionDisplay());
        // 设置下载目录路径为公共目录（用于在下载列表中显示和打开文件夹）
        // 文件会先下载到私有目录，然后复制到公共目录，所以显示公共目录路径
        String publicDownloadDir = Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS).getAbsolutePath()
                + "/GrayVideoDL";
        task.setDownloadDir(publicDownloadDir);
        Log.d(TAG, "startDownload: 任务URL=" + task.getUrl()
                + ", downloadDir=" + publicDownloadDir);

        Log.d(TAG, "startDownload: 创建下载任务 id=" + task.getId()
                + ", title=" + task.getTitle()
                + ", formatId=" + task.getFormatId()
                + ", status=" + task.getStatus());

        // 保存下载任务列表（将新任务添加到头部）
        List<DownloadTask> tasks = DownloadTask
                .loadTaskList(requireContext());
        Log.d(TAG, "startDownload: 从JSON加载已有任务 " + tasks.size() + " 个");
        tasks.add(0, task);
        DownloadTask.saveTaskList(requireContext(), tasks);
        Log.d(TAG, "startDownload: 任务已保存到JSON，总任务数=" + tasks.size());
        appendLog("下载任务已添加: " + task.getTitle());

        // 通过 MainActivity 切换到下载 Tab，让 DownloadFragment 接管下载
        if (getActivity() != null) {
            com.google.android.material.bottomnavigation
                    .BottomNavigationView nav = getActivity()
                    .findViewById(R.id.bottom_navigation);
            if (nav != null) {
                Log.d(TAG, "startDownload: 即将切换到下载Tab");
                nav.setSelectedItemId(R.id.nav_download);
                Log.d(TAG, "startDownload: 已触发切换到下载Tab");
            } else {
                Log.w(TAG, "startDownload: bottom_navigation 为 null");
            }
        } else {
            Log.w(TAG, "startDownload: getActivity() 为 null");
        }

        showToast("下载任务已添加到列表");
    }

    /*
     * showTestResultDialog: 显示环境测试结果对话框
     * 全部通过时仅显示"环境检查通过"；失败时显示"环境出错"并列出具体失败项
     */
    private void showTestResultDialog(
            boolean pythonOk,
            boolean ffmpegOk,
            boolean storageOk, String storageMsg,
            boolean networkOk, String networkMsg,
            boolean cookieOk, String cookieMsg) {
        // 计算是否全部通过（Cookie 为可选，不计入全部通过条件）
        boolean allCriticalOk = pythonOk && ffmpegOk && storageOk && networkOk;

        LinearLayout layout = new LinearLayout(requireContext());
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(40, 28, 40, 20);

        if (allCriticalOk) {
            // 全部通过：仅显示成功标题
            TextView titleView = new TextView(requireContext());
            titleView.setText("环境检查通过");
            titleView.setTextSize(18);
            titleView.setTextColor(getResources().getColor(R.color.success_green));
            titleView.setGravity(android.view.Gravity.CENTER);
            layout.addView(titleView);
        } else {
            // 有失败项：显示"环境出错"及具体失败详情
            TextView titleView = new TextView(requireContext());
            titleView.setText("环境出错");
            titleView.setTextSize(18);
            titleView.setTextColor(getResources().getColor(R.color.error_text));
            titleView.setGravity(android.view.Gravity.CENTER);
            titleView.setPadding(0, 0, 0, 16);
            layout.addView(titleView);

            // 构建失败详情文本，只列出未通过的项目
            StringBuilder failBuilder = new StringBuilder();
            if (!pythonOk) {
                failBuilder.append("✗ Python环境异常\n");
            }
            if (!ffmpegOk) {
                failBuilder.append("✗ FFmpeg未就绪（分离流视频将无音频）\n");
            }
            if (!storageOk) {
                failBuilder.append("✗ 存储目录异常");
                if (storageMsg != null && !storageMsg.isEmpty()) {
                    failBuilder.append("（").append(storageMsg).append("）");
                }
                failBuilder.append("\n");
            }
            if (!networkOk) {
                failBuilder.append("✗ 网络连接异常");
                if (networkMsg != null && !networkMsg.isEmpty()) {
                    failBuilder.append("（").append(networkMsg).append("）");
                }
                failBuilder.append("\n");
            }

            TextView detailView = new TextView(requireContext());
            detailView.setText(failBuilder.toString().trim());
            detailView.setTextSize(14);
            detailView.setTextColor(getResources().getColor(R.color.error_text));
            detailView.setGravity(android.view.Gravity.START);
            layout.addView(detailView);
        }

        // 弹出模态框
        new android.app.AlertDialog.Builder(requireContext())
                .setView(layout)
                .setPositiveButton("知道了", null)
                .show();
    }

    /**
     * formatFileSizeForEnv: 将字节数格式化为可读的文件大小文本（供环境检测使用）
     * @param bytes 文件字节数
     * @return 格式化后的字符串，如 "14.8 MB"
     */
    private String formatFileSizeForEnv(long bytes) {
        if (bytes <= 0) return "未知";
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.0f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) {
            return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
        }
        return String.format("%.1f GB", bytes / (1024.0 * 1024.0 * 1024.0));
    }

    private String callPythonFunction(String name, String param) {
        try {
            if (!Python.isStarted()) {
                Python.start(new AndroidPlatform(requireContext()));
                appendLog("Python 环境初始化完成");
            }
            Python py = Python.getInstance();
            PyObject mod = py.getModule("ytdlp_bridge");

            PyObject result;
            if ("extractVideoInfo".equals(name)) {
                // 根据视频链接自动检测所属平台，传入对应平台的 Cookie 文件路径
                // 不同平台使用独立的 Cookie 文件（格式：{platform}_cookies.txt）
                // 与 yt-dlp --cookies 参数等效，用于解锁需登录才能访问的内容
                String cookieFile = PlatformCookieManager
                        .getCookieFilePath(requireContext(), param);
                result = mod.callAttr(name, param, cookieFile);
            } else if (param.isEmpty()) {
                result = mod.callAttr(name);
            } else {
                result = mod.callAttr(name, param);
            }
            return result.toString();
        } catch (Exception e) {
            String err = "Python 调用失败 [" + name + "]: " + e.getMessage();
            appendLog(err);
            return "{\"status\":\"error\",\"error\":\"" + err + "\"}";
        }
    }

    /* -------------------------- 日志记录 -------------------------- */

    private void appendLog(String text) {
        // 写入全局 LogBuffer，供独立的 LogFragment 页面读取
        LogBuffer.append(text);
    }

    /*
     * setButtonsEnabled: 启用或禁用操作按钮
     * 解析过程中禁用按钮防止重复点击，解析完成后恢复
     */
    private void setButtonsEnabled(boolean enabled) {
        btnParse.setEnabled(enabled);
        btnTestEnv.setEnabled(enabled);
    }

    /*
     * showLoadingDialog: 显示加载对话框
     * 包含旋转进度条，不可取消
     * @param title 对话框标题文字
     */
    private void showLoadingDialog(String title) {
        // 创建 ProgressBar
        ProgressBar progressBar = new ProgressBar(requireContext());
        progressBar.setPadding(0, 24, 0, 24);

        // 构建对话框
        loadingDialog = new AlertDialog.Builder(requireContext())
                .setTitle(title)
                .setMessage("请稍候...")
                .setView(progressBar)
                .setCancelable(false)
                .show();
    }

    /*
     * dismissLoadingDialog: 关闭加载对话框
     * 解析完成后调用，无论成功或失败
     */
    private void dismissLoadingDialog() {
        if (loadingDialog != null && loadingDialog.isShowing()) {
            loadingDialog.dismiss();
            loadingDialog = null;
        }
    }

    /*
     * getDownloadDirectory: 获取下载文件保存目录
     */
    private File getDownloadDirectory(Context context) {
        File dir = new File(
                context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS),
                "GrayVideoDL");
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    private void showToast(String msg) {
        Toast.makeText(getActivity(), msg, Toast.LENGTH_SHORT).show();
    }
}
