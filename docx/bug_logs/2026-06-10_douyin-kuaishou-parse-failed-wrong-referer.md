# Bug: 抖音/快手视频解析失败 — 错误的 Referer 请求头

**日期**: 2026-06-10
**版本**: v0.5A
**优先级**: 高

## 现象
用户输入抖音（douyin.com）或快手（kuaishou.com）视频链接后，解析失败，返回错误信息（如 403 Forbidden）。

YouTube 和 Bilibili 的解析正常。

## 根因
`ytdlp_bridge.py` 中的 `_get_common_opts()` 函数硬编码了 Bilibili 的请求头：

```python
headers = {
    "Referer": "https://www.bilibili.com/",
    "Origin": "https://www.bilibili.com",
    ...
}
```

当 yt-dlp 的 Douyin/Kuaishou extractor 请求目标服务器 API 时，携带了 `Referer: https://www.bilibili.com/` 这个错误的请求头。抖音和快手的服务器检测到 Referer 不匹配，判定为异常请求，返回 HTTP 403（Forbidden）拒绝访问。

## 修复
修改 [ytdlp_bridge.py](file:///d:/COMPUTER/Android/GrayTools/Files/app/src/main/python/ytdlp_bridge.py) 中的逻辑：

1. **新增 `_detect_platform(video_url)` 函数**（第40-64行）：根据 URL 域名检测视频来源平台（bilibili/douyin/kuaishou/youtube）。

2. **修改 `_get_common_opts(video_url="", cookie_file="")` 函数**（第67-166行）：
   - 新增 `video_url` 参数用于平台识别
   - 建立平台→请求头的映射表，为不同平台设置对应的 Referer/Origin
     - Bilibili → `https://www.bilibili.com/`
     - 抖音 → `https://www.douyin.com/`
     - 快手 → `https://www.kuaishou.com/`
     - YouTube → `https://www.youtube.com/`
   - 移除全局硬编码的 Bilibili 请求头
   - Bilibili 的 extractor_args（`BiliBili: ["web"]`）改为只在检测到 Bilibili 平台时设置

3. **更新三处调用**：`extractVideoInfo`、`downloadVideo`、`downloadVideoWithProgress` 均将 `video_url` 传入 `_get_common_opts`。

## 验证
1. 输入抖音链接（如 `https://www.douyin.com/video/xxx`），应该能正确解析出视频信息
2. 输入快手链接（如 `https://www.kuaishou.com/xxx`），应该能正确解析出视频信息
3. Bilibili 和 YouTube 的解析不受影响，功能正常
4. 下载功能同样生效
