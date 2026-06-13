# Bug: 快手短URL解析返回空数据 + 抖音短URL解析失败

**日期**: 2026-06-13  
**版本**: v0.0.1F  
**优先级**: 高  

## 现象

### Bug 1：快手短URL `v.kuaishou.com/{short_id}`
```json
{"title": "3xvmfr2sseyhgfs", "duration": 0, "formats": [], "thumbnail": "", "status": "ok", "error": ""}
```
状态为"成功"，但标题是用户ID，格式列表为空。

### Bug 2：抖音短URL `v.douyin.com/{short_id}`
```json
{"status": "error", "error": "视频信息提取失败: ERROR: Unsupported URL: https://www.douyin.com/note/7650667384164287668?...", "已尝试URL": ["https://v.douyin.com/G7qlWyLsXJ4/"]}
```
yt-dlp 解析短URL后跳转到 `/note/` 路径，但不支持该路径。

## 根因（基于 logcat 日志分析）

### Bug 1 根因
1. **短URL只有1段路径**：`v.kuaishou.com/J5uRGF2t` → `path_parts = ['J5uRGF2t']`，`len(path_parts) >= 2` 不成立，`_normalize_video_url` 未生成任何变体
2. **yt-dlp返回无效数据但被视为成功**：yt-dlp 对该 URL 处理后返回 `title=3xvmfr2sseyhgfs`（ID格式）和 `formats_count=0`。由于没抛异常，代码跳过所有回退逻辑，直接标记为成功

### Bug 2 根因
1. **短URL只有1段路径**：`v.douyin.com/G7qlWyLsXJ4/` → `path_parts = ['G7qlWyLsXJ4']`，`path_parts[0] != "note"`，未生成 `/video/` 变体
2. **yt-dlp内部解析到/note/路径**：yt-dlp 的 Douyin extractor 解析短URL后得到 `www.douyin.com/note/7650667384164287668`，但不支持 `/note/` 路径，返回 "Unsupported URL"

## 修复
修改文件 [ytdlp_bridge.py](../../Files/app/src/main/python/ytdlp_bridge.py)，涉及两处修改：

### 1. `_normalize_video_url()` — 处理单段路径的短URL
**快手短URL**：当检测到 `v.kuaishou.com` 时，即使 `path_parts` 只有1段，也生成 `photo/` 和 `short-video/` 变体

**抖音短URL**：当检测到 `v.douyin.com` 时，使用 `urllib.request` 发送 HEAD 请求获取重定向后的真实URL，从中提取数字ID，生成 `/video/` 变体

### 2. `extractVideoInfo()` — yt-dlp成功但数据无效时触发回退
在 yt-dlp 成功路径中增加有效性校验：
- 仅对快手链接
- 检查条件：标题无效（`_is_valid_video_title` 返回 False）**或** 格式列表为空
- 满足条件时抛出异常，进入现有的 `force_generic_extractor` + `_extract_kuaishou_info_direct` 回退链路

## 验证
- 快手 `v.kuaishou.com/{short_id}`：应能生成 photo/short-video 变体；如果 yt-dlp 仍返回空数据，回退到直接 HTML 提取
- 抖音 `v.douyin.com/{short_id}`：应能自动解析短URL得到数字ID，生成 `/video/` 变体
