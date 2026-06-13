# Bug: 抖音解析后画质显示为原始描述文本而非分辨率标签

**日期**: 2026-06-10
**版本**: v0.5A
**优先级**: 中

## 现象
解析抖音视频链接后，画质列表显示的不是 "360P"、"720P" 这样标准的分辨率标签，而是显示类似 "Playback Video"、"Download Video, watermarked" 这样的原始描述文本。

## 根因
yt-dlp 的抖音 extractor 返回的 `format_note` 字段是平台内部的描述性文本（如 "Playback Video"），而非标准的分辨率标签。在 `VideoInfo.java` 的 `getResolutionDisplay()` 方法中，优先使用 `formatNote` 字段，因此直接显示了这些原始文本。

## 修复
修改 [ytdlp_bridge.py](file:///d:/COMPUTER/Android/GrayTools/Files/app/src/main/python/ytdlp_bridge.py) 新增三个函数并在 `extractVideoInfo` 中调用：

1. **新增 `_parse_resolution_height(resolution)`**（第169行）：从分辨率字符串解析视频高度，支持横竖屏（取 width 和 height 中的较大值）。

2. **新增 `_resolution_to_quality_label(height)`**（第195行）：将像素高度转为标准画质标签（360→360P、1080→1080P 等）。

3. **新增 `_normalize_platform_formats(format_list, platform)`**（第223行）：遍历格式列表，对抖音/快手平台的非标准 `format_note` 进行转换：
   - 检测 `format_note` 是否已是标准格式（含 P/K/高清等关键词）
   - 若不是，从 `resolution` 字段解析高度生成标准标签
   - 如有水印标记，在标签后附加"（含水印）"

4. **修改 `extractVideoInfo`**（第336行）：在构建完 format_list 后调用 `_normalize_platform_formats` 进行标准化。

## 验证
1. 解析抖音视频链接，显示的格式应为 "720P"、"1080P" 等标准分辨率标签
2. 含水印的格式应显示如 "720P（含水印）"
3. Bilibili 和 YouTube 的画质显示不受影响
