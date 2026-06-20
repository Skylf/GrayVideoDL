# Bug: 下载进度条无法显示下载进度

**日期**: 2026-06-13  
**版本**: v0.6A  
**优先级**: 高  

## 现象

下载任务开始后，进度条始终显示为 0%，无法实时更新下载进度。

## 根因

**字段名不匹配**：Python 代码写入进度文件的字段名与 Java 代码读取时期望的字段名不一致。

Python 写入的字段：
- `progress` → Java 期望 `percent`
- `downloaded` → Java 期望 `downloaded_bytes`
- `total` → Java 期望 `total_bytes`

## 修复

### 1. 修复 Python 字段名
修改 `ytdlp_bridge.py` 中的 `progress_hook` 函数，将字段名修改为与 Java 代码一致：
- `progress` → `percent`
- `downloaded` → `downloaded_bytes`
- `total` → `total_bytes`

### 2. 添加日志调试
在 Java 的 `startProgressPolling` 方法中添加详细的 logcat 日志，便于后续排查：
- 进度轮询启动日志
- 进度文件读取日志（包含文件内容）
- 进度数据解析日志
- UI 更新日志
- 进度文件不存在时的日志

## 修改文件

### 1. Python 代码
- [ytdlp_bridge.py](file:///d:/COMPUTER/Android/GrayTools/Files/app/src/main/python/ytdlp_bridge.py)
  - 修改 `downloadVideoWithProgress()` 函数中的 `progress_hook`，修正字段名

### 2. Java 代码
- [DownloadFragment.java](file:///d:/COMPUTER/Android/GrayTools/Files/app/src/main/java/com/example/grayvideodl/ui/download/DownloadFragment.java)
  - 在 `startProgressPolling()` 方法中添加详细的 logcat 日志

## 验证

- 下载视频时，进度条应实时显示下载百分比
- 日志应输出：
  ```
  D/DownloadFlow: startProgressPolling: 启动进度轮询
  D/DownloadFlow: startProgressPolling: 读取进度文件成功，content={"percent": 45, ...}
  D/DownloadFlow: startProgressPolling: 解析进度数据，percent=45
  D/DownloadFlow: startProgressPolling: 更新UI进度，newProgress=45
  ```