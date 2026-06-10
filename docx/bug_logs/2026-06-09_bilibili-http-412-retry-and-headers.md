# Bug: Bilibili 视频解析 HTTP 412 Precondition Failed

**日期**: 2026-06-09  
**版本**: v0.3A  
**优先级**: 高  

## 现象

解析 Bilibili 视频链接时返回 412 错误：

```
视频信息提取失败: ERROR: [BiliBili] 1VsLJ6QEeE: 
Unable to download JSON metadata: HTTP Error 412: Precondition Failed 
(caused by <HTTPError 412: Precondition Failed>)
```

环境检测正常，网络连接正常，但视频解析始终失败。

## 根因

Bilibili 的视频元数据 API 有严格的反爬检测机制，需要模拟完整浏览器请求头。当前代码存在两个问题：

### 1. 缺少 `Origin` 请求头
`_get_common_opts()` 中 `http_headers` 包含了 `User-Agent`、`Accept`、`Accept-Language`、`Referer`，但**缺少 `Origin: https://www.bilibili.com`**。Bilibili API 网关检测到缺少 Origin 头时，会认为请求来源异常，返回 412。

### 2. User-Agent 版本可能过旧
当前 User-Agent 使用 `Chrome/125.0.0.0`（约 2024 年中发布）。当前为 2026 年 6 月，该版本可能已被 Bilibili 的指纹库标记为过时或异常。

### 3. 过期 Cookie 导致 412（潜在问题）
如果用户之前登录过 Bilibili 但 Cookie 已过期，yt-dlp 发送过期 Cookie 访问 API，Bilibili 可能直接拒绝请求（返回 412）。代码中只要 Cookie 文件存在就无条件使用，没有检查内容是否有效。

## 修复

修改文件：`Files/app/src/main/python/ytdlp_bridge.py`

### 修复一：补全请求头
- 新增 `"Origin": "https://www.bilibili.com"` 头
- 更新 `User-Agent` 为 `Chrome/130.0.0.0`
- 在 `Accept` 中补充 `application/signed-exchange;v=b3;q=0.7`

### 修复二：Cookie 文件有效性检查
`_get_common_opts()` 中使用 Cookie 文件前，先读取文件内容，检查是否存在非注释、非空行的实际 Cookie 条目。如果文件只有头信息（Netscape 格式的注释行），则视为无效，不使用 Cookie。

### 修复三：提取失败时自动重试（不使用 Cookie）
`extractVideoInfo()` 添加重试机制：第一次使用 Cookie 提取，如果遇到 412/403/Precondition 错误，自动放弃 Cookie 重新尝试一次。这防止了过期 Cookie 阻塞解析。

## 验证
1. 重新编译安装 App
2. 输入 Bilibili 视频链接（如 BV1VsLJ6QEeE）
3. 应能正常解析出视频标题、时长、格式列表
4. 不再出现 412 错误
5. 如果已登录但 Cookie 过期，仍能正常解析（降级为无 Cookie 模式）
