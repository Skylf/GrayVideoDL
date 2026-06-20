# Bug: 下载成功但实际无视频文件

**日期**: 2026-06-13  
**版本**: v0.6A  
**优先级**: 高  

## 现象

下载任务显示"下载成功"，但实际视频文件并未下载到设备中。日志显示任务状态从 `downloading` 变为 `completed`，但没有实际的文件路径信息。

## 根因

Python 的 `downloadVideoWithProgress()` 函数返回的成功结果中**缺少 `filepath` 字段**：

```python
return json.dumps({'status': 'ok', 'error': ''})
```

而 Java 代码 `DownloadFragment.executeDownload()` 期望从结果中获取文件路径：

```java
taskList.get(idx).setFilepath(result.optString("filepath", ""));
```

由于 `filepath` 为空，后续的文件大小获取和复制到公共目录的操作都被跳过。

## 修复

修改 `ytdlp_bridge.py` 中的 `downloadVideoWithProgress()` 函数：

1. 在下载前先调用 `ydl.extract_info(url, download=False)` 获取视频信息
2. 下载完成后调用 `ydl.prepare_filename(info)` 构建输出文件路径
3. 将文件路径包含在返回的 JSON 中

## 修改文件

- [ytdlp_bridge.py](file:///d:/COMPUTER/Android/GrayTools/Files/app/src/main/python/ytdlp_bridge.py)
  - 修改 `downloadVideoWithProgress()` 函数，添加文件路径返回

## 验证

- 下载视频后，任务应正确显示文件大小
- 文件应出现在公共 Download/GrayVideoDL 目录中
- 任务状态应正确标记为"已完成"