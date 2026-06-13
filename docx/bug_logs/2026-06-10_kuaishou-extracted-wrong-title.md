# Bug: 快手链接提取到错误标题（ID而非真实标题）

**日期**: 2026-06-10  
**版本**: v0.0.1F  
**优先级**: 高  

## 现象
快手链接解析后 status 为 "ok"，但标题显示为 `3xfkbmgkw8g47fq`（用户ID/视频ID），而非视频的真实标题。formats 为空数组。

```json
{"title": "3xfkbmgkw8g47fq", "duration": 0, "formats": [], "thumbnail": "", "status": "ok", "error": ""}
```

## 根因
`_extract_kuaishou_info_direct()` 函数的提取优先级设计有误：

1. **JSON-LD 优先级过高**：代码先尝试从 JSON-LD 的 `VideoObject` 中提取 `name` 字段。但快手的 JSON-LD 数据中，`name` 字段可能是用户 ID 或视频 ID，而非真正的视频标题。
2. **后续高可靠性来源被跳过**：由于 JSON-LD 提取到了一个非空字符串（即使它是 ID），`if not title` 条件不满足，导致 `<title>` 标签和 `og:title` meta 标签这两个更可靠的 SEO 字段被跳过。
3. **缺少标题有效性校验**：没有判断提取到的"标题"是否看起来像真实标题（如包含中文、空格、标点等）。

## 修复
修改文件 [ytdlp_bridge.py](../../Files/app/src/main/python/ytdlp_bridge.py)，涉及两处改动：

### 1. 新增 `_is_valid_video_title()` 函数（第 421 行）
- 标题不能为空
- 长度至少为 2
- 不能全是纯字母数字（`^[a-zA-Z0-9_]+$`），排除 ID/用户名

### 2. 重构 `_extract_kuaishou_info_direct()` 提取优先级
新的优先级顺序（从高到低，高优先级可覆盖低优先级）：
| 优先级 | 来源 | 说明 |
|--------|------|------|
| 1 | `<title>` 标签 | SEO 核心字段，始终包含真实标题。去除 " - 快手" 后缀 |
| 2 | `<meta og:title>` | 社交分享标题，与真实标题一致 |
| 3 | JSON-LD VideoObject | 结构化数据，但需校验 `name` 是否有效 |
| 4 | `__INITIAL_STATE__` | SPA 内嵌数据，格式多变，仅作最后回退 |

每个字段独立提取，且始终使用 `_is_valid_video_title()` 校验后才接受。

## 验证
- 快手链接解析后应显示正确的视频标题（而非 ID）
- 如页面包含视频直链，应显示可下载的格式
- 如无法提取视频直链，应至少显示包含标题的元信息
- 缩略图、时长等字段应正确提取
