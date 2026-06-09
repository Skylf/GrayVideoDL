/*
 * HomeFragment.java (v2)
 * 首页 Fragment：优化后的 UI，解析结果以卡片展示。
 * 支持：视频/音频分栏显示、合并开关联动、成功动画提示、折叠调试日志。
 */

package com.example.grayvideodl.ui.home;

import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.app.AlertDialog;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
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
import com.example.grayvideodl.model.DownloadTask;
import com.example.grayvideodl.model.LogBuffer;
import com.example.grayvideodl.model.VideoInfo;
import com.example.grayvideodl.ui.settings.BilibiliLoginDialog;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.textfield.TextInputEditText;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.OutputStream;
import java.util.List;

public class HomeFragment extends Fragment {

    // ========== 控件声明 ==========
    private TextInputEditText etUrlInput;
    private MaterialButton btnParse, btnTestEnv, btnDownload;

    // 结果卡片
    private MaterialCardView cardResult, cardError;
    private TextView tvVideoTitle, tvDuration, tvFormatCount;
    private LinearLayout layoutVideoFormats, layoutAudioFormats;
    private TextView tvVideoSectionTitle, tvAudioSectionTitle;

    // 错误卡片
    private TextView tvErrorDetail;

    // 成功提示浮层
    private LinearLayout layoutSuccessToast;

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

    private void onParseClick() {
        String url = etUrlInput.getText().toString().trim();
        if (url.isEmpty()) {
            showToast("请先输入视频链接");
            appendLog("错误：链接为空");
            return;
        }

        // 重复解析检测
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

        doParse(url);
    }

    /*
     * doParse: 执行实际的视频解析逻辑
     * @param url 视频链接
     */
    private void doParse(String url) {
        // 记录本次解析的 URL
        lastParsedUrl = url;

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
                    showError(currentVideoInfo.getError());
                }
                appendLog("解析结果:\n" + result);
            });
        }).start();
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

        // 分离视频格式和音频格式
        List<VideoInfo.Format> videoFormats = new java.util.ArrayList<>();
        List<VideoInfo.Format> audioFormats = new java.util.ArrayList<>();

        for (VideoInfo.Format fmt : info.getFormats()) {
            if (fmt.isAudioOnly()) {
                audioFormats.add(fmt);
            } else {
                videoFormats.add(fmt);
            }
        }

        // 更新统计文本
        tvFormatCount.setText(videoFormats.size() + " 种画质");
        if (!audioFormats.isEmpty()) {
            tvFormatCount.append(" · " + audioFormats.size() + " 种音质");
        }

        // 填充视频格式列表
        layoutVideoFormats.removeAllViews();
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
     * createFormatRow: 创建一个可点击的格式选择行
     */
    private View createFormatRow(VideoInfo.Format format) {
        MaterialCardView card = new MaterialCardView(requireContext());
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, 0, 0, 8);
        card.setLayoutParams(params);
        card.setStrokeWidth(1);
        card.setStrokeColor(getResources().getColor(R.color.error_border, null));
        card.setCardElevation(0);
        card.setContentPadding(12, 10, 12, 10);
        card.setRadius(8);
        card.setClickable(true);
        card.setFocusable(true);
        card.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                getResources().getColor(R.color.chip_unselected_bg, null)));

        LinearLayout row = new LinearLayout(requireContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView label = new TextView(requireContext());
        label.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        label.setTextSize(14);

        String labelText;
        if (format.isAudioOnly()) {
            labelText = format.getAcodec() + " · " + format.getExt();
        } else {
            labelText = format.getResolutionDisplay();
        }
        label.setText(labelText);

        TextView sizeLabel = new TextView(requireContext());
        sizeLabel.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        sizeLabel.setTextSize(12);

        // 锁定格式：显示锁定标记，灰色不可点击
        if (format.isLocked()) {
            sizeLabel.setText(format.getFilesizeText());
            sizeLabel.setTextColor(
                    getResources().getColor(R.color.error_border, null));
            card.setEnabled(false);
            card.setAlpha(0.45f);
        } else {
            sizeLabel.setText(format.getFilesizeText());
            sizeLabel.setTextColor(
                    getResources().getColor(android.R.color.darker_gray, null));
            card.setOnClickListener(v -> selectFormat(card, format));
        }

        row.addView(label);
        row.addView(sizeLabel);
        card.addView(row);

        card.setOnClickListener(v -> selectFormat(card, format));
        return card;
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

        // 高亮选中的卡片
        LinearLayout parent = (LinearLayout) selectedCard.getParent();
        for (int i = 0; i < parent.getChildCount(); i++) {
            View child = parent.getChildAt(i);
            if (child instanceof MaterialCardView) {
                MaterialCardView c = (MaterialCardView) child;
                if (c == selectedCard) {
                    c.setStrokeColor(getResources().getColor(R.color.error_text, null));
                    c.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                            getResources().getColor(R.color.chip_selected_bg, null)));
                } else {
                    c.setStrokeColor(getResources().getColor(R.color.error_border, null));
                    c.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                            getResources().getColor(R.color.chip_unselected_bg, null)));
                }
            }
        }
    }

    /* -------------------------- 成功提示浮层 -------------------------- */

    private void showSuccessToast() {
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
        appendLog("开始环境检测...");

        // 禁用按钮防止重复点击
        setButtonsEnabled(false);
        showLoadingDialog("正在测试环境...");

        new Thread(() -> {
            String envInfo = callPythonFunction("testEnvironment", "");
            appendLog("环境检测结果:\n" + envInfo);

            // 在主线程处理结果
            mainHandler.post(() -> {
                setButtonsEnabled(true);
                dismissLoadingDialog();

                // 解析 JSON 判断是否成功
                boolean isSuccess = false;
                try {
                    JSONObject json = new JSONObject(envInfo);
                    // yt-dlp 已安装且状态为 ok 即为成功
                    isSuccess = json.optBoolean("yt_dlp_installed", false)
                            && "ok".equals(json.optString("status", ""));
                } catch (Exception ignored) {
                }

                // 显示测试结果模态框
                showTestResultDialog(isSuccess);
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
     * startDownload: 执行视频下载
     * 在后台线程调用 Python yt-dlp 下载
     */
    private void startDownload(VideoInfo.Format format) {
        // 创建下载任务记录
        final DownloadTask task = new DownloadTask();
        task.setUrl(etUrlInput.getText().toString().trim());
        task.setTitle(currentVideoInfo.getTitle());
        task.setFormatId(format.getFormatId());
        task.setResolution(format.getResolutionDisplay());

        setButtonsEnabled(false);
        showLoadingDialog("正在下载...");

        // 读取用户设置的下载路径
        // 使用 SharedPreferences 中保存的路径
        final File downloadDir = getDownloadDirectory();

        new Thread(() -> {
            // 获取 Cookie 文件路径
            String cookieFile = BilibiliLoginDialog
                    .getCookieFilePath(requireContext());

            // 调用 Python 下载
            String resultStr = callDownloadFunction(
                    task.getUrl(), task.getFormatId(),
                    downloadDir.getAbsolutePath(), cookieFile);

            mainHandler.post(() -> {
                setButtonsEnabled(true);
                dismissLoadingDialog();

                try {
                    JSONObject result = new JSONObject(resultStr);
                    if ("ok".equals(result.optString("status"))) {
                        task.setFilepath(result.optString("filepath", ""));
                        task.setStatus(DownloadTask.STATUS_COMPLETED);

                        // 获取文件大小
                        File file = new File(task.getFilepath());
                        if (file.exists()) {
                            task.setFileSize(file.length());
                        }

                        appendLog("下载完成: " + task.getFilepath());

                        // 复制到公共 Download 目录（文件管理器中可见）
                        copyToPublicDownloads(new File(task.getFilepath()));

                        // 显示成功提示
                        showToast("下载完成: " + result.optString("title", ""));
                    } else {
                        task.setStatus(DownloadTask.STATUS_FAILED);
                        task.setError(result.optString("error", "未知错误"));
                        appendLog("下载失败: " + task.getError());
                        showToast("下载失败");
                    }
                } catch (Exception e) {
                    task.setStatus(DownloadTask.STATUS_FAILED);
                    task.setError(e.getMessage());
                    appendLog("下载失败: " + e.getMessage());
                }

                // 保存下载任务列表
                List<DownloadTask> tasks = DownloadTask
                        .loadTaskList(requireContext());
                tasks.add(0, task);
                DownloadTask.saveTaskList(requireContext(), tasks);
            });
        }).start();
    }

    /*
     * getDownloadDirectory: 获取下载目录（Python yt-dlp 写入位置）
     * 使用应用专属目录确保始终可写，下载完成后再复制到公共目录
     */
    private File getDownloadDirectory() {
        File dir = new File(requireContext()
                .getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS),
                "GrayVideoDL");
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    /*
     * copyToPublicDownloads: 将下载的文件保存到公共 Download/GrayVideoDL/ 目录
     * 使用 MediaStore API，文件在文件管理器 → Download → GrayVideoDL/ 中可见
     */
    private void copyToPublicDownloads(File sourceFile) {
        try {
            String fileName = sourceFile.getName();
            String mimeType = "video/mp4";
            if (fileName.endsWith(".m4a") || fileName.endsWith(".mp3")
                    || fileName.endsWith(".aac")) {
                mimeType = "audio/mpeg";
            }

            ContentValues values = new ContentValues();
            values.put(MediaStore.Downloads.DISPLAY_NAME, fileName);
            values.put(MediaStore.Downloads.MIME_TYPE, mimeType);
            values.put(MediaStore.Downloads.RELATIVE_PATH,
                    "Download/GrayVideoDL");
            values.put(MediaStore.Downloads.IS_PENDING, 1);

            Uri uri = requireContext().getContentResolver()
                    .insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            if (uri != null) {
                try (OutputStream out = requireContext().getContentResolver()
                        .openOutputStream(uri);
                     FileInputStream in = new FileInputStream(sourceFile)) {
                    byte[] buf = new byte[8192];
                    int len;
                    while ((len = in.read(buf)) > 0) {
                        out.write(buf, 0, len);
                    }
                }
                values.clear();
                values.put(MediaStore.Downloads.IS_PENDING, 0);
                requireContext().getContentResolver().update(uri, values,
                        null, null);
                appendLog("文件已保存到: Download/GrayVideoDL/" + fileName);
            }
        } catch (Exception e) {
            appendLog("保存文件失败: " + e.getMessage());
        }
    }

    /*
     * callDownloadFunction: 调用 Python 下载函数
     */
    private String callDownloadFunction(String url, String formatId,
                                         String outputDir, String cookies) {
        try {
            if (!Python.isStarted()) {
                Python.start(new AndroidPlatform(requireContext()));
            }
            Python py = Python.getInstance();
            PyObject mod = py.getModule("ytdlp_bridge");
            PyObject result = mod.callAttr(
                    "downloadVideo", url, formatId, outputDir, cookies);
            return result.toString();
        } catch (Exception e) {
            return "{\"status\":\"error\",\"error\":\"" + e.getMessage() + "\"}";
        }
    }

    /*
     * showTestResultDialog: 显示环境测试结果模态框
     * 成功：绿色背景 + "✅ 环境测试成功！"
     * 失败：红色背景 + "❌ 环境测试失败！"
     */
    private void showTestResultDialog(boolean isSuccess) {
        // 构建消息文本和颜色
        String message = isSuccess ? "✅ 环境测试成功！" : "❌ 环境测试失败！";
        int bgColor = getResources().getColor(
                isSuccess ? R.color.success_bg : R.color.error_border, null);
        int textColor = getResources().getColor(
                isSuccess ? R.color.success_green : R.color.error_text, null);

        // 创建一个带颜色的消息布局
        TextView messageView = new TextView(requireContext());
        messageView.setText(message);
        messageView.setTextSize(18);
        messageView.setTextColor(textColor);
        messageView.setGravity(android.view.Gravity.CENTER);
        messageView.setPadding(32, 24, 32, 24);
        messageView.setBackgroundColor(bgColor);

        // 弹出模态框
        new android.app.AlertDialog.Builder(requireContext())
                .setView(messageView)
                .setPositiveButton("知道了", null)
                .show();
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
                // 传入 Cookie 文件路径（与 yt-dlp --cookies 等效）
                String cookieFile = BilibiliLoginDialog
                        .getCookieFilePath(requireContext());
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

    private void showToast(String msg) {
        Toast.makeText(getActivity(), msg, Toast.LENGTH_SHORT).show();
    }
}
