# Bug: Bilibili 视频解析返回 HTTP 412 Precondition Failed

**日期**: 2026-06-08
**版本**: v1.0.0
**优先级**: 高

## 现象
输入 B站 视频链接（如 `b23.tv` 或 `bilibili.com`）点击"解析视频"后，日志面板显示：
```
HTTP Error 412: Precondition Failed
(caused by <HTTPError 412: Precondition Failed>)
```

环境检测正常，说明网络权限已通，但 yt-dlp 请求被 B站 服务器拦截。

## 根因
B站 等大型视频平台使用反爬机制检测 HTTP 请求头。yt-dlp 默认的 User-Agent 和请求头特征容易被识别为爬虫/脚本工具，服务器因此返回 `412 Precondition Failed` 拒绝服务。

具体原因：
1. yt-dlp 默认 User-Agent 为 `Python-urllib/x.xx`，B站 对此类请求头直接拦截
2. 缺少浏览器标准的 Accept、Accept-Language、Referer 等请求头
3. 缺少 B站 特定提取器的配置参数

## 修复
修改文件：`Files/app/src/main/python/ytdlp_bridge.py`

在 `extractVideoInfo` 函数的 yt-dlp 配置中添加：
1. **自定义 HTTP 请求头** — 模拟 Chrome 浏览器的完整请求头：
   - `User-Agent`: Chrome 125 标准的 UA 字符串
   - `Accept`: 标准浏览器 Accept 头
   - `Accept-Language`: `zh-CN,zh;q=0.9,en;q=0.8`
   - `Origin`: `https://www.bilibili.com`
   - `Referer`: `https://www.bilibili.com/`
2. **B站 特定提取器参数**：
   - `extractor_args`: `{"BiliBili": ["web"]}` — 模拟网页端访问

## 验证
1. 重新编译安装后，输入 B站 视频链接
2. 应能正常解析出视频标题、时长、格式列表等信息
3. 不再出现 412 错误
