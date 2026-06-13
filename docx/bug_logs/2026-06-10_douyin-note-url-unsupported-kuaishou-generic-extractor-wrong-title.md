# Bug: 抖音/note路径解析失败 + 快手generic extractor提取到错误标题

**日期**: 2026-06-10  
**版本**: v0.0.1F  
**优先级**: 高  

## 现象

### Bug 1：抖音 /note/ 链接解析失败
```json
{"status": "error", "error": "视频信息提取失败: ERROR: Unsupported URL: `https://www.douyin.com/note/7650667384164287668?...`"}
```

### Bug 2：快手解析后标题为ID而非视频标题
```json
{"title": "3xvmfr2sseyhgfs", "duration": 0, "formats": [], "thumbnail": "", "status": "ok", "error": ""}
```

## 根因

### Bug 1 根因
抖音 `/note/` 路径用于图文/照片合集类内容。yt-dlp 的 Douyin extractor 标准支持的是 `/video/` 路径。部分 yt-dlp 版本不支持 `/note/` 路径，导致返回 "Unsupported URL"。

### Bug 2 根因
`force_generic_extractor` 回退路径中，generic extractor 将页面中的非标题文本（ID/用户名 `3xvmfr2sseyhgfs`）误提取为标题。`has_valid_data` 只检查了 `bool(generic_title)`（非空即可），未调用 `_is_valid_video_title()` 做进一步校验，导致 ID 被当作有效标题使用。

## 修复
修改文件 [ytdlp_bridge.py](../../Files/app/src/main/python/ytdlp_bridge.py)，涉及两处修改：

### 1. `_normalize_video_url()` — 增加抖音 /note/ → /video/ URL 变体
新增抖音链接处理逻辑：
- 检测 `/note/` 路径时，生成 `/video/` 路径的备选 URL
- 同时生成去除追踪参数的简洁 `/note/` URL

### 2. `has_valid_data` 增加标题校验
在 `extractVideoInfo` 的 `force_generic_extractor` 回退中：
- 原代码：`bool(generic_title) and any(...)`
- 修复后：`_is_valid_video_title(generic_title) and any(...)`
- 确保 generic extractor 返回的标题必须通过有效性校验（排除纯字母数字的 ID 字符串）

## 验证
- 抖音 `/note/` 链接：应能通过 `/video/` 变体正常解析
- 快手链接：不应再出现 ID 作为标题的情况
- 若所有变体均无法解析，错误信息会显示已尝试的 URL 列表
