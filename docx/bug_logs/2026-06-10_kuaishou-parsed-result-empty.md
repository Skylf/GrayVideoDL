# Bug: 快手链接解析"成功"但结果为空

**日期**: 2026-06-10  
**版本**: v0.0.1F  
**优先级**: 高  

## 现象
快手链接解析后 status 为 "ok"（显示成功），但返回的 title 为空字符串，formats 为空数组，没有任何实际数据。

## 根因
上一轮修复中为快手链接添加了 `force_generic_extractor=True` 回退方案，但存在两个问题：

1. **Generic extractor 对 JS 动态渲染页面无效**：快手页面是 SPA（单页应用），视频数据通过 JavaScript 动态加载。yt-dlp 的 generic extractor 只解析静态 HTML，提取不到视频标题、URL 等核心数据。
2. **缺少结果有效性校验**：原始代码在 generic extractor 返回后直接设置 `last_error = None`，即使提取到的全是空数据，也被当作"成功"处理，导致 UI 显示空结果。

## 修复
修改文件 [ytdlp_bridge.py](../../Files/app/src/main/python/ytdlp_bridge.py)，涉及三个改动：

### 1. 新增 `_extract_kuaishou_info_direct()` 函数
- 使用 `urllib.request` 直接下载快手页面 HTML
- 通过 4 种方法逐级提取视频信息：
  - **方法1**：解析 `<script type="application/ld+json">` 中的 JSON-LD 结构化数据（VideoObject）
  - **方法2**：解析 `window.__INITIAL_STATE__` 中的视频数据（支持多种数据路径）
  - **方法3**：从 `<meta property="og:title/og:image">` 标签提取
  - **方法4**：从 HTML 中直接搜索 `.mp4` 格式的视频 URL
- 提取成功则返回包含 title、thumbnail、duration、formats 的字典

### 2. 增加 generic extractor 结果有效性校验
- 检查 `force_generic_extractor` 返回的 title 是否非空且 formats 中至少有一个有效 URL
- 通过 `has_valid_data` 判断：`bool(generic_title) and any(fmt.get("url") for fmt in generic_formats)`
- 仅当数据有效时才标记为成功

### 3. 添加直接提取回退
- 当 generic extractor 返回空数据时，自动调用 `_extract_kuaishou_info_direct()`
- 使用清洗后的 URL（含视频 ID 的 photo 路径，无追踪参数）请求页面
- 如果直接提取成功，使用提取到的数据

## 验证
- 快手分享链接（含追踪参数）解析后应能显示视频标题
- 若页面能解析到视频 URL，应能显示可下载的格式
- 若无法解析到视频 URL（仅元数据），应至少显示标题和缩略图
- 其他平台不受影响
