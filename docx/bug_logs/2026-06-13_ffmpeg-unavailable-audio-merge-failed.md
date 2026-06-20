# Bug: FFmpeg 不可用导致音视频无法合并

**日期**: 2026-06-13  
**版本**: v0.0.1F  
**优先级**: 高  

## 现象
用户下载 B站视频后，播放时无法解析音频（视频无声）。

## 根因
1. **Chaquopy 环境未打包 FFmpeg**：Chaquopy 打包的 Python 环境没有包含 FFmpeg 二进制文件，导致 yt-dlp 无法执行音视频合并。

2. **B站视频格式为分离流**：B站的视频格式都是分离的（纯视频流 + 纯音频流），没有已合并格式。当用户选择纯视频流格式时，需要额外下载音频流并用 FFmpeg 合成。

3. **yt-dlp 行为**：当 FFmpeg 不可用时，yt-dlp 只下载视频流，不下载音频流，也不合并。这就是为什么下载很快完成（3.9秒下载742秒视频），但视频没有声音。

## 日志分析
```
fffmag-video ERROR: FFmpeg 不可用: 'FFmpegPostProcessor' object has no attribute 'get_version'
fffmag-video MERGE [合并前准备]: FFmpeg可用: False
fffmag-video MERGE [合并前准备]: 选定格式(formatId=100026) 分析结果:
fffmag-video MERGE [合并前准备]:   - 视频编码: av01.0.08M.08.0.110.01.01.01.0
fffmag-video MERGE [合并前准备]:   - 音频编码: none
fffmag-video MERGE [合并前准备]:   - 是否需要合并: True
fffmag-video MERGE [合并前准备]:   - 原因: 纯视频流(无音频)，需要额外下载音频流再合并
fffmag-video MERGE [合并前准备]: 格式分布: 纯视频=15, 纯音频=3, 已合并=0
```

## 修复
修改了以下文件：

### Python 端
- **ytdlp_bridge.py**：
  - 改进 `check_ffmpeg_available()` 函数，更准确地检测 FFmpeg 可用性
  - 新增 FFmpeg 不可用时的格式选择策略：
    - 策略1：查找已合并格式（视频+音频在同一格式中），自动切换
    - 策略2：无已合并格式时使用 `best` 格式
  - 返回结果中新增 `ffmpeg_warning` 和 `ffmpeg_warning_message` 字段

### Java 端
- **fragment_download.xml**：新增 FFmpeg 警告浮层布局
- **DownloadFragment.java**：
  - 新增 `layoutFfmpegWarningToast` 和 `tvFfmpegWarningText` 成员变量
  - 新增 `showFfmpegWarningToast()` 方法
  - 在 `executeDownload()` 中检查 FFmpeg 警告并显示浮层

## 验证
1. 使用 `adb logcat -s fffmag-video` 查看日志
2. 下载 B站视频，观察日志中的格式选择策略输出
3. 如果触发 FFmpeg 警告，应显示橙色警告浮层

## 后续建议
1. **打包 FFmpeg 到 APK**：在 Chaquopy 配置中添加 FFmpeg 二进制文件，实现真正的音视频合并
2. **使用外部 FFmpeg**：通过 Termux 等方式在设备上安装 FFmpeg