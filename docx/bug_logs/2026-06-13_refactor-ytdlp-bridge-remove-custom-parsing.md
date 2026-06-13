# Bug: 重构视频链接解析算法 — 移除所有自定义解析逻辑，直接使用 yt-dlp

**日期**: 2026-06-13  
**版本**: v0.1.0  
**优先级**: 高  

## 现象

之前的 `ytdlp_bridge.py` 中充斥着大量自定义解析代码：

1. **`_detect_platform()`** — 通过 URL 域名匹配手动判断视频来源平台
2. **`_normalize_video_url()`** — 自定义 URL 清洗逻辑（含抖音短链重定向解析、快手多路径尝试、追踪参数去除等）
3. **`_normalize_platform_formats()`** — 自定义格式备注标准化
4. **`_extract_kuaishou_info_direct()`** — 自定义快手 HTML 页面爬虫（解析 title/meta/JSON-LD/__INITIAL_STATE__）
5. **`_is_valid_video_title()`** — 标题有效性校验
6. **`_parse_resolution_height()`** / **`_resolution_to_quality_label()`** — 分辨率解析与画质标签转换
7. **复杂重试逻辑** — 多 URL 变体尝试 + Cookie/无Cookie 重试 + generic extractor 回退 + 直接页面提取回退

这些问题导致：
- 代码量巨大且难以维护
- 每个新平台都需要手动添加检测规则
- 自定义逻辑与 yt-dlp 内部 extractor 功能重复
- 快手等平台的回退方案（直接 HTML 爬取）非常脆弱
- yt-dlp 内部已包含所有平台的 extractor，我们的自定义分析完全是多余的

## 根因

**设计理念错误**：之前试图自己分析 URL、自己判断平台、自己构造请求头、自己处理 URL 变体、甚至自己写 HTML 爬虫来回退。但 yt-dlp 本身已是一个成熟的视频链接解析工具，其内置 extractor 覆盖了所有主流平台，能自动处理短链重定向、平台识别、请求头设置、TLS 指纹模拟等。我们的自定义代码不仅重复造轮子，还引入了额外的维护成本和出错可能。

## 修复

**将 yt_dlp_bridge.py 从"自定义解析 + yt-dlp 调用"彻底重构为"纯 yt-dlp 调用"**

### 移除的函数（7 个）：

| 函数 | 行数 | 原因 |
|------|------|------|
| `_detect_platform()` | 原 ~52 行 | yt-dlp 内部 extractor 自动识别平台 |
| `_normalize_video_url()` | 原 ~140 行 | yt-dlp 自动处理短链/带参/多路径 URL |
| `_normalize_platform_formats()` | 原 ~55 行 | 保留 yt-dlp 原始 format_note |
| `_is_valid_video_title()` | 原 ~30 行 | 仅回退逻辑使用，不再需要 |
| `_extract_kuaishou_info_direct()` | 原 ~300 行 | 自定义 HTML 爬虫，不再需要 |
| `_parse_resolution_height()` | 原 ~25 行 | 仅标准化使用，不再需要 |
| `_resolution_to_quality_label()` | 原 ~30 行 | 仅标准化使用，不再需要 |

### 简化的函数（4 个）：

| 函数 | 变更说明 |
|------|----------|
| `_get_common_opts()` | 移除 `video_url` 参数和平台检测逻辑；移除自定义请求头设置；保留 yt-dlp 原生配置（cookiefile、impersonate、format_sort） |
| `extractVideoInfo()` | 移除 URL 规范化调用、移除多 URL 变体重试循环、移除 Cookie 重试逻辑、移除 generic extractor 回退、移除快手 HTML 直接提取回退、移除标题有效性校验；改为单次 yt-dlp 调用 |
| `downloadVideo()` | 移除 URL 规范化调用，直接使用原始 URL |
| `downloadVideoWithProgress()` | 移除 URL 规范化调用，直接使用原始 URL |

### 保留的 yt-dlp 原生配置：

```python
opts = {
    "quiet": True,
    "no_warnings": True,
    "format_sort": ["res", "codec", "vbr"],   # yt-dlp 原生排序参数
    "impersonate": True,                        # yt-dlp 原生 TLS 指纹模拟 (--impersonate)
}
# cookiefile 对应 yt-dlp --cookies 参数
```

### 涉及的文件：
- [ytdlp_bridge.py](file:///d:/COMPUTER/Android/GrayTools/Files/app/src/main/python/ytdlp_bridge.py) — 主重构文件
- [register_java_classes.py](file:///d:/COMPUTER/Android/GrayTools/register_java_classes.py) — 更新函数注册表信息
- [ytdlp_bridge_FunctionNameReg.xlsx](file:///d:/COMPUTER/Android/GrayTools/ProjectDescription/ytdlp_bridge_FunctionNameReg.xlsx) — 重新生成的表格

### Java 侧无需改动：
- `HomeFragment.java` 调用 `extractVideoInfo(url, cookieFile)` 接口不变
- `DownloadFragment.java` 调用 `downloadVideoWithProgress(...)` 接口不变
- `VideoInfo.java` 解析 JSON 的字段格式不变
- `PlatformCookieManager` Cookie 管理机制不变

## 验证

1. **Python 语法检查**：`python -m py_compile ytdlp_bridge.py` 无语法错误
2. **接口兼容性**：所有导出函数（`extractVideoInfo`、`downloadVideo`、`downloadVideoWithProgress`、`testEnvironment`）的参数签名和返回值格式不变
3. **JSON 字段兼容**：返回的 JSON 中 `title`、`duration`、`thumbnail`、`formats`、`status`、`error` 字段结构保持不变
4. **format_note 变化**：之前会自定义标准化（如 "Playback Video" → "720P"），现在保留 yt-dlp 原始值。这不会破坏 Java 侧解析，`VideoInfo.getResolutionDisplay()` 已正确处理原始 format_note 和 fallback 到 resolution 的逻辑
