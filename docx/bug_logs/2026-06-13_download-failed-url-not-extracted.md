# Bug 下载失败：原始输入未提取为纯链接 + 下载后文件路径不可靠

**日期**: 2026-06-13
**版本**: v0.6A
**优先级**: 高

## 现象

1. 点击"开始下载"后，日志显示下载瞬间完成（约 500ms），任务标记为 `completed`，但实际未下载任何视频文件
2. 视频文件未出现在公共 Download/GrayVideoDL 目录中

## 根因

### 问题1：URL 未提取（主要问题）

`HomeFragment.java` 中 `startDownload()` 方法直接使用 `etUrlInput.getText().toString().trim()` 获取用户输入的原始文本，而没有调用 `extractUrlFromInput()` 从输入中提取纯链接。

```java
// 错误代码
task.setUrl(etUrlInput.getText().toString().trim());
```

日志中可以看到传入的 URL 是：
```
5.10 复制打开抖音，看看【薰衣草～的作品】（他家🉐️ 👟👔超实惠）
@小帅Fashion 👟 ... https://v.douyin.com/QsYrxnONTbw/ ...
```

这导致 yt-dlp 收到的不是纯链接而是一段包含大量非 URL 内容的文本，yt-dlp 立即报错返回。

### 问题2：Python 文件路径获取不可靠

Python 代码中使用 `prepare_filename(info)` 来获取输出文件路径，但 `prepare_filename` 是基于 `info` 字典中的 `title` 和 `ext` 字段来推算路径的，可能与 `download()` 实际输出的文件路径不一致（例如 yt-dlp 可能会对文件名进行额外的过滤或格式转换）。

### 问题3：文件复制无日志

`copyToPublicDownloads()` 方法在源文件不存在时静默返回，没有任何日志输出，导致问题难以排查。

## 修复

### 修复1：URL 提取
修改 `HomeFragment.java` 中 `startDownload()` 方法，在保存 URL 前先调用 `extractUrlFromInput()` 提取纯链接：

```java
String rawInput = etUrlInput.getText().toString().trim();
String extractedUrl = extractUrlFromInput(rawInput);
String finalUrl = (extractedUrl != null) ? extractedUrl : rawInput;
task.setUrl(finalUrl);
```

### 修复2：Python 文件路径使用 `download()` 返回值
修改 `ytdlp_bridge.py`，优先使用 `ydl.download([url])` 返回的实际文件路径列表，备用使用 `prepare_filename`：

```python
downloaded_files = ydl.download([url])
if downloaded_files and len(downloaded_files) > 0 and downloaded_files[0]:
    filename = downloaded_files[0]
else:
    filename = ydl.prepare_filename(info)
```

### 修复3：添加日志
在 `copyToPublicDownloads()` 和 `executeDownload()` 的成功分支中添加详细日志，便于后续排查文件路径问题。

## 修改文件

- [HomeFragment.java](file:///d:/COMPUTER/Android/GrayTools/Files/app/src/main/java/com/example/grayvideodl/ui/home/HomeFragment.java)
  - `startDownload()`: URL 提取
- [ytdlp_bridge.py](file:///d:/COMPUTER/Android/GrayTools/Files/app/src/main/python/ytdlp_bridge.py)
  - `downloadVideoWithProgress()`: 使用 `download()` 返回值获取文件路径
- [DownloadFragment.java](file:///d:/COMPUTER/Android/GrayTools/Files/app/src/main/java/com/example/grayvideodl/ui/download/DownloadFragment.java)
  - `copyToPublicDownloads()`: 添加日志
  - `executeDownload()`: 添加文件路径日志

## 验证

1. 输入包含非 URL 内容（如"看看这个视频 https://v.douyin.com/xxx"），点击下载应自动提取纯链接
2. 下载完成后，文件应出现在公共 Download/GrayVideoDL 目录中
3. 日志中应输出提取后的 URL 和文件路径信息
