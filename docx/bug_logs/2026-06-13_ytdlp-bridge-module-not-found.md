# Bug: Python 调用失败 ModuleNotFoundError: No module named 'ytdlp_bridge'

**日期**: 2026-06-13  
**版本**: v0.6A  
**优先级**: 高  

## 现象

```
Python 调用失败 [extractVideoInfo]: ModuleNotFoundError: No module named 'ytdlp_bridge'

解析结果: 
{"status":"error","error":"Python 调用失败 [extractVideoInfo]: ModuleNotFoundError: No module named 'ytdlp_bridge'"}
```

用户点击"解析视频"按钮后，应用无法解析任何视频链接，始终返回上述错误。

## 根因

Chaquopy 插件在构建 Android 应用时，需要读取 Python 源码文件（`.py`）进行编译和打包。

经检查发现：
- `Files/app/src/main/python/ytdlp_bridge.py` **源码文件不存在**
- 只有编译后的缓存文件 `__pycache__/ytdlp_bridge.cpython-311.pyc`

由于缺少源码文件，Chaquopy 无法将 `ytdlp_bridge` 模块打包进 APK，导致运行时无法找到该模块。

## 修复

重新创建缺失的 `ytdlp_bridge.py` 源码文件，包含以下函数：

1. **`extractVideoInfo(url, cookieFile)`** — 解析视频信息
2. **`downloadVideo(url, formatId, outputDir, cookieFile)`** — 下载视频（不带进度）
3. **`downloadVideoWithProgress(url, formatId, outputDir, cookieFile, progressFile, cancelFlag)`** — 下载视频（带进度回调）
4. **`testEnvironment()`** — 测试 yt-dlp 环境

同时添加了平台特定的请求头和配置：
- 抖音：设置 referer 和 extractor_args
- 快手：设置 referer 并启用通用提取器

## 修改文件

- [ytdlp_bridge.py](file:///d:/COMPUTER/Android/GrayTools/Files/app/src/main/python/ytdlp_bridge.py) — 新建文件

## 验证

- 运行"测试环境"按钮，应显示 yt-dlp 版本信息
- 输入有效的视频链接（如 Bilibili），应能正常解析出视频信息
- 输入快手链接，应弹出平台兼容性警告，但仍尝试解析