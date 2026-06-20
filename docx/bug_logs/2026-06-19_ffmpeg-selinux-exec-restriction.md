# Bug: FFmpeg 因 SELinux/noexec 限制在 Android 应用层不可执行

**日期**: 2026-06-19
**版本**: v0.5A
**优先级**: 高

## 现象
使用 `formatId+bestaudio` 下载 Bilibili DASH 视频时，yt-dlp 报告 `FFmpeg is not installed`，
即使在 Java FFmpegManager 中已部署 FFmpeg 二进制（15.5MB）并调用 `setExecutable(true)`。
下载后的视频只有画面没有声音。

## 根因
两个原因：
1. **SELinux 限制**：Android `untrusted_app` 域禁止从 `/data/data/` 和 `/data/user/0/`
   目录执行 ELF 二进制文件。即使 `chmod +x` 返回成功（Java `setExecutable(true)` 或 Python `os.chmod()`），
   `execve` 系统调用仍被 SELinux 策略拒绝（Errno 13 Permission denied）。
2. **noexec 挂载**：`/data/` 分区通常以 `noexec` 挂载，禁止从数据分区执行二进制文件。

Python `verify_ffmpeg_executable()` 使用 `subprocess.run([ffmpeg_path, '-version'])` 验证失败。
Chaquopy 环境下的 `subprocess.Popen` 也受此限制影响。

## 修复
使用 Android 系统自带的 `MediaMuxer` + `MediaExtractor` API 替代 FFmpeg：

### 新增文件
- **MediaMuxerHelper.java**：使用 Android 原生 API 合并音视频
  - `mergeVideoAudio(videoPath, audioPath, outputPath)` — 读取视频和音频轨道，MediaMuxer 封装为 MP4
  - `selectTrack(extractor, mimePrefix)` — 查找指定 MIME 类型的轨道

### 修改文件
- **ytdlp_bridge.py**：
  - 新增 `use_media_muxer` 标志变量
  - 当 FFmpeg 不可用且无已合并格式时：
    1. 先下载纯视频流（formatId）
    2. 再下载最佳音频流（bestaudio）
    3. 通过 Chaquopy jclass 桥接调用 Java MediaMuxerHelper.mergeVideoAudio()
    4. 合并成功则替换原文件，失败则保留纯视频

## 验证
1. 安装 APK 并启动，下载一个 Bilibili 视频
2. 日志应显示 `启动 MediaMuxer 合并方案` → `音频下载完成` → `MediaMuxer 合并成功`
3. 播放下载后的视频，确认有声音
4. 可通过 logcat 搜索 `MediaMuxerHelper:` 查看合并过程详情

## 文件清单
| 文件 | 类型 | 说明 |
|------|------|------|
| [MediaMuxerHelper.java](file:///d:/COMPUTER/Android/GrayTools/Files/app/src/main/java/com/example/grayvideodl/MediaMuxerHelper.java) | 新增 | Android MediaMuxer 合并类 |
| [ytdlp_bridge.py](file:///d:/COMPUTER/Android/GrayTools/Files/app/src/main/python/ytdlp_bridge.py) | 修改 | 添加 MediaMuxer 合并逻辑 |