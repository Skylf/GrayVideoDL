# Bug: 快手视频解析失败 — yt-dlp 无法识别含追踪参数的分享链接

**日期**: 2026-06-10
**版本**: v0.5A
**优先级**: 高

## 现象
输入快手分享链接后解析失败，返回错误信息：
```
ERROR: Unsupported URL: `https://www.kuaishou.com/short-video/3xapadz3mmyrxy2?cc=share_copylink&followRefer=151&...`
```

## 根因
快手的 APP 分享链接携带了大量追踪参数（`cc=share_copylink&followRefer=151&shareMethod=TOKEN&docId=9&...`），并且路径使用 `/short-video/` 格式。yt-dlp 的 Kuaishou extractor 在以下情况下无法识别该 URL：

1. **追踪参数过多**：大量 query 参数可能干扰 yt-dlp 的 URL 模式匹配
2. **路径格式不支持**：部分旧版本 yt-dlp 的 Kuaishou extractor 只支持 `/photo/` 路径，不支持 `/short-video/` 路径

## 修复
修改 [ytdlp_bridge.py](file:///d:/COMPUTER/Android/GrayTools/Files/app/src/main/python/ytdlp_bridge.py)：

1. **新增 `_normalize_video_url(video_url)` 函数**（第272行）：规范化视频链接 URL
   - 提取视频 ID，构建无追踪参数的简洁 URL
   - 如果路径是 `/short-video/`，额外尝试 `/photo/` 路径格式
   - 返回依次尝试的 URL 列表

2. **修改 `extractVideoInfo`**：增加外层 URL 格式尝试循环
   - 先用原始 URL 尝试解析
   - 如果返回 "Unsupported URL" 错误，自动切换到清洗后的 URL 重试
   - 如果还不行，尝试替代路径格式

3. **修改 `downloadVideo` 和 `downloadVideoWithProgress`**：使用规范化后的 URL 进行下载

## 验证
1. 输入快手分享链接（如 `https://www.kuaishou.com/short-video/3xapadz3mmyrxy2?...`），应能正确解析
2. 普通视频链接（Bilibili、YouTube、抖音）不受影响
3. 下载功能同样生效
