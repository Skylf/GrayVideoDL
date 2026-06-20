# Bug: 部分视频音视频没有合并

**日期**: 2026-06-13  
**版本**: v0.6A  
**优先级**: 高  

## 现象

部分视频下载后只有视频或只有音频，音视频没有合并。项目没有报错，但合并结果不一致：
- 有些视频可以正常合并
- 有些视频只有视频轨（无声音）
- 有些视频只有音频轨（黑屏）

## 根因分析

可能的原因包括：
1. **FFmpeg 后处理器配置问题**：可能没有正确启用合并后处理器
2. **格式选择问题**：某些格式本身就是分离的（视频-only 或音频-only），需要手动合并
3. **FFmpeg 缺失或版本问题**：yt-dlp 需要 FFmpeg 来执行合并操作
4. **权限问题**：可能没有写入临时文件的权限进行合并

## 修复方案

### 1. 启用 FFmpegVideoConvertor 后处理器
添加 `FFmpegVideoConvertor` 后处理器，强制将输出转换为 mp4 格式，确保音视频合并。

### 2. 添加详细日志追踪
在下载过程中添加详细日志：
- 视频信息提取日志（标题、ID、格式）
- 可用格式列表
- 选定格式的详细信息（视频编码、音频编码、分辨率）
- 下载后的文件验证

### 3. 配置优化
- 启用 verbose 模式获取详细输出
- 设置 `keepvideo` 和 `keyaudio` 为 True，确保中间文件保留用于合并
- 设置 `merge_output_format` 为 mp4

## 修改文件

- [ytdlp_bridge.py](file:///d:/COMPUTER/Android/GrayTools/Files/app/src/main/python/ytdlp_bridge.py)
  - 添加 FFmpegVideoConvertor 后处理器
  - 添加详细日志追踪下载和合并过程
  - 启用 verbose 模式

## 日志输出

修复后，Python 端会输出以下日志：

```
ytdlp_bridge: 下载配置: formatId=xxx, outputDir=/xxx/GrayVideoDL
ytdlp_bridge: postprocessors=[{'key': 'FFmpegVideoConvertor', 'preferedformat': 'mp4'}]
ytdlp_bridge: 开始提取视频信息，URL=https://xxx
ytdlp_bridge: 视频信息提取成功
ytdlp_bridge: 视频标题: xxx
ytdlp_bridge: 可用格式数量: 20
ytdlp_bridge: 有视频流: True, 有音频流: True
ytdlp_bridge: 选定格式详细信息:
  - format_id: xxx
  - vcodec: avc1.xxx
  - acodec: mp4a.xxx
  - resolution: 1920x1080
ytdlp_bridge: 开始下载视频
ytdlp_bridge: 下载完成，文件大小: 12345678 字节
```

## 验证

下载不同平台的视频后，检查：
1. 日志中是否输出了详细的格式信息
2. 下载的视频文件是否包含音视频轨道
3. 使用媒体播放器验证视频是否有声音和画面