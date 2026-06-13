# Bug: 快手（kuaishou.com）链接解析失败 - Unsupported URL

**日期**: 2026-06-10  
**版本**: v0.0.1F  
**优先级**: 高  

## 现象
输入快手视频分享链接（含大量追踪参数）后，解析结果始终为空：
```json
{"title": "", "duration": 0, "formats": [], "thumbnail": "", "status": "error", "error": "视频信息提取失败: ERROR: Unsupported URL: `https://www.kuaishou.com/short-video/3xapadz3mmyrxy2?...`"}
```
错误信息显示 yt-dlp 无法识别该 URL。

## 根因
1. **快手分享链接含大量追踪参数**：从 App 分享的链接带有 `cc=`、`shareMethod=`、`shareToken=` 等数十个追踪参数，yt-dlp 的 extractor 无法匹配带大量参数的 URL。
2. **`_normalize_video_url()` 生成的 URL 变体不足以匹配 yt-dlp 的 extractor**：原有代码仅对 `short-video` 路径生成 `photo` 变体，未覆盖无 `www.` 前缀等其他格式。
3. **缺乏回退机制**：当所有 URL 变体都返回 "Unsupported URL" 时，没有尝试使用 yt-dlp 的 `force_generic_extractor`（通用提取器）来解析页面 HTML。

## 修复
修改文件 [ytdlp_bridge.py](../../Files/app/src/main/python/ytdlp_bridge.py)，涉及两处修改：

### 1. `_normalize_video_url()` 函数（第 363 行）
- 将原条件生成（仅 `short-video` 路径才生成 `photo` 变体）改为**全量生成**所有变体
- 新增 4 种 URL 变体：无参 clean URL → photo 路径 → short-video 路径 → 无 www 前缀 → 无 www + photo
- 使用 `not in urls_to_try` 去重，避免重复 URL

### 2. `extractVideoInfo()` 函数（第 519 行）
- 在**所有 URL 变体都失败**后，针对快手链接增加 `force_generic_extractor=True` 回退方案
- 回退方案使用 yt-dlp 的 generic extractor 解析页面 HTML，提取视频信息
- 若回退也失败，则在错误信息中附加上尝试过的 URL 列表用于调试

## 验证
- 输入含追踪参数的快手链接，应能正确解析出视频标题、时长、格式等信息
- 错误信息中会显示 `已尝试URL:` + 具体尝试过的所有 URL 列表
- 其他平台的 URL 解析不受影响
