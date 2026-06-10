# Feature: 下载进度回调 + 下载列表页展示 + 暂停/继续/删除

**日期**: 2026-06-09  
**版本**: v0.3A  
**优先级**: 高  

## 新增功能

### 1. 下载进度回调
用户发起下载后，可在界面上实时看到下载进度百分比、速度和剩余时间。

### 2. 下载列表页展示
底部导航栏「下载」Tab 展示所有下载任务（进行中/已完成/失败/暂停），
每个任务显示标题、分辨率、进度条、状态文本、操作按钮。

### 3. 下载暂停/继续/删除
- **暂停**：正在下载的任务可暂停，进度保留
- **继续**：暂停的任务可继续，yt-dlp 自动断点续传
- **删除**：删除任务记录和已下载的文件

## 实现方案

### 进度回调机制
使用 **文件轮询** 模式实现 Python → Java 的进度传递：

1. **Python 端** (`ytdlp_bridge.py`)：
   - 新增 `downloadVideoWithProgress()` 函数
   - 利用 yt-dlp 的 `progress_hooks` 机制，在回调中将进度（percent、speed、eta）写入临时 JSON 文件
   - 检测 `cancel_flag_file` 是否存在以支持暂停/取消

2. **Java 端** (`DownloadFragment.java`)：
   - `startProgressPolling()` 每 500ms 读取一次进度文件
   - 解析 JSON 获取百分比，更新到 UI
   - 下载完成后清理临时文件

### 暂停/继续机制
- **暂停**：Java 创建 `cancel_<taskId>.flag` 标志文件 → Python progress_hook 检测到后抛出异常 → 返回 `paused` 状态
- **继续**：Java 清除标志文件 → 重新调用 `downloadVideoWithProgress()` → yt-dlp 自动从断点处继续下载（默认 `--continue`）
- **删除**：先暂停（若正在下载），再删除文件和记录

### 下载列表架构
- `DownloadFragment.java`：完整的 Fragment 实现，管理任务列表和下载生命周期
- `DownloadAdapter.java`：RecyclerView 适配器，根据任务状态动态控制 UI
- `item_download.xml`：列表项布局（标题、分辨率、进度条、状态、操作按钮）
- `DownloadTask.java`：数据模型扩展（progress、STATUS_PAUSED）

### 下载流程整合
```
HomeFragment 点击下载
    → 创建 DownloadTask (STATUS_DOWNLOADING)
    → 保存到 download_tasks.json
    → 导航到下载 Tab
    → DownloadFragment.onResume()
    → autoStartPendingTasks() 检测到待处理任务
    → 启动后台线程执行 executeDownload()
    → Python 写入进度 → Java 轮询读取 → UI 实时更新
```

## 修改的文件

| 文件 | 修改类型 | 说明 |
|------|----------|------|
| `Files/.../python/ytdlp_bridge.py` | 新增函数 | `downloadVideoWithProgress()` |
| `Files/.../model/DownloadTask.java` | 扩展 | progress 字段、STATUS_PAUSED、帮助方法 |
| `Files/.../ui/download/DownloadFragment.java` | 重写 | 完整实现下载列表、进度、暂停/继续/删除 |
| `Files/.../ui/download/DownloadAdapter.java` | 新增 | RecyclerView 适配器 |
| `Files/.../res/layout/item_download.xml` | 新增 | 列表项布局 |
| `Files/.../ui/home/HomeFragment.java` | 重构 | startDownload 改为添加到下载列表 |

## 验证方式
1. 在首页解析 B站视频，选择画质，点击下载
2. 自动跳转到下载 Tab，查看进度条实时更新
3. 点击「暂停」按钮，进度条暂停，状态变为「已暂停」
4. 点击「继续」按钮，下载从中断处继续
5. 下载完成后，状态变为「已完成」，进度条 100%
6. 点击「删除」，确认后任务和文件被清除
