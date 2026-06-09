/*
 * DownloadFragment.java
 * 下载列表 Fragment：展示所有下载任务的状态、进度，
 * 支持暂停/继续/删除等操作（后续实现）。
 */

package com.example.grayvideodl.ui.download;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.RecyclerView;

import com.example.grayvideodl.R;

public class DownloadFragment extends Fragment {

    // 下载列表 RecyclerView，用于展示下载任务列表
    private RecyclerView rvDownloadList;

    // 空状态提示文本，当没有下载任务时显示
    private TextView tvEmptyDownload;

    /*
     * onCreateView: 创建 Fragment 的视图布局
     * 加载 fragment_download 布局并初始化控件
     */
    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        // 填充 fragment_download 布局作为当前 Fragment 的视图
        View view = inflater.inflate(R.layout.fragment_download, container, false);

        // 初始化下载列表 RecyclerView 和空状态提示文本
        // 通过 findViewByid 获取布局中定义的控件实例
        rvDownloadList = view.findViewById(R.id.rv_download_list);
        tvEmptyDownload = view.findViewById(R.id.tv_empty_download);

        // TODO: 后续实现下载列表适配器和数据绑定
        // 暂时显示空状态提示，隐藏 RecyclerView
        rvDownloadList.setVisibility(View.GONE);
        tvEmptyDownload.setVisibility(View.VISIBLE);

        return view;
    }
}
