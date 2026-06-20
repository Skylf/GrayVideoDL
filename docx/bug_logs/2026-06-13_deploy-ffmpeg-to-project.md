# Bug: 部署 FFmpeg 到 Android 项目

**日期**: 2026-06-13  
**版本**: v0.0.1F  
**优先级**: 高  

## 现象
用户下载 B站视频后，播放时无法解析音频（视频无声）。

## 根因
**项目未部署 FFmpeg**：Chaquopy 嵌入的 Python 环境没有 FFmpeg 可执行文件，导致 yt-dlp 无法执行音视频合并。

## 修复
### 1. 添加 FFmpeg 依赖

**build.gradle.kts**：
```kotlin
// FFmpeg 库：用于音视频合并
implementation("com.arthenica:mobile-ffmpeg-full:6.5.0.LTS")
```

### 2. 创建 FFmpegManager 类

**FFmpegManager.java**：负责初始化 mobile-ffmpeg 库并提供 FFmpeg 路径给 Python

### 3. 在 Application 中初始化

**GrayVideoDLApp.java**：在 onCreate 中调用 `FFmpegManager.getInstance().initialize(this)`

### 4. 修改 Python 代码配置 FFmpeg 路径

**ytdlp_bridge.py**：
- 更新 `check_ffmpeg_available()` 函数，增加对 Android 路径的检查
- 添加 `set_ffmpeg_path()` 函数设置环境变量
- 在 `ydl_opts` 中添加 `ffmpeg_location` 配置

## 验证
1. 使用 `adb logcat -s GrayVideoDLApp` 查看 FFmpeg 初始化日志
2. 使用 `adb logcat -s fffmag-video` 查看下载时的 FFmpeg 使用日志
3. 下载 B站视频，验证音视频合并是否成功

## 修改文件

| 文件 | 变更 |
|------|------|
| [build.gradle.kts](file:///d:/COMPUTER/Android/GrayTools/Files/app/build.gradle.kts) | 添加 mobile-ffmpeg-full 依赖 |
| [FFmpegManager.java](file:///d:/COMPUTER/Android/GrayTools/Files/app/src/main/java/com/example/grayvideodl/FFmpegManager.java) | 新建 FFmpeg 管理器类 |
| [GrayVideoDLApp.java](file:///d:/COMPUTER/Android/GrayTools/Files/app/src/main/java/com/example/grayvideodl/GrayVideoDLApp.java) | 添加 FFmpeg 初始化调用 |
| [ytdlp_bridge.py](file:///d:/COMPUTER/Android/GrayTools/Files/app/src/main/python/ytdlp_bridge.py) | 更新 FFmpeg 检测和配置 |