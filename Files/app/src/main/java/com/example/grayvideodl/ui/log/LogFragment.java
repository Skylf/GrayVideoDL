/*
 * LogFragment.java
 * 独立运行日志页面：展示 yt-dlp 的详细运行日志，
 * 支持复制和清空操作。
 */

package com.example.grayvideodl.ui.log;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.example.grayvideodl.R;
import com.example.grayvideodl.model.LogBuffer;
import com.google.android.material.button.MaterialButton;

public class LogFragment extends Fragment {

    private TextView tvLogOutput;
    private ScrollView svLogContent;
    private MaterialButton btnCopyLog;
    private MaterialButton btnClearLog;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_log, container, false);

        // 初始化控件
        tvLogOutput = view.findViewById(R.id.tv_log_output);
        svLogContent = view.findViewById(R.id.sv_log_content);
        btnCopyLog = view.findViewById(R.id.btn_copy_log);
        btnClearLog = view.findViewById(R.id.btn_clear_log);

        // 加载日志内容
        refreshLog();

        // 复制按钮
        btnCopyLog.setOnClickListener(v -> {
            String text = tvLogOutput.getText().toString();
            if (text.isEmpty() || text.equals("等待操作...")) {
                android.widget.Toast.makeText(getActivity(),
                        "没有可复制的日志", android.widget.Toast.LENGTH_SHORT).show();
                return;
            }
            ClipboardManager cm = (ClipboardManager)
                    requireContext().getSystemService(
                            requireContext().CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("GrayVideoDL 日志", text));
            android.widget.Toast.makeText(getActivity(),
                    "日志已复制到剪贴板", android.widget.Toast.LENGTH_SHORT).show();
        });

        // 清空按钮
        btnClearLog.setOnClickListener(v -> {
            LogBuffer.clear();
            refreshLog();
            android.widget.Toast.makeText(getActivity(),
                    "日志已清空", android.widget.Toast.LENGTH_SHORT).show();
        });

        return view;
    }

    /*
     * onHiddenChanged: Fragment 显隐状态变化时回调
     * 当用户从其他 Tab 切回日志页面时刷新日志内容
     * 使用 onHiddenChanged 替代 onResume 确保 Tab 切换时也能刷新
     */
    @Override
    public void onHiddenChanged(boolean hidden) {
        super.onHiddenChanged(hidden);
        if (!hidden) {
            refreshLog();
        }
    }

    /*
     * refreshLog: 从 LogBuffer 读取日志并刷新显示
     */
    private void refreshLog() {
        if (tvLogOutput != null) {
            tvLogOutput.setText(LogBuffer.getLog());
            // 自动滚动到底部
            svLogContent.post(() ->
                    svLogContent.fullScroll(View.FOCUS_DOWN));
        }
    }
}
