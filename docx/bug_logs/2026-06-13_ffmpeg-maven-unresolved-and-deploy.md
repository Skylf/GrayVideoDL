# Bug: FFmpegKit Maven 依赖无法解析 + 部署 FFmpeg 到项目

**日期**: 2026-06-13  
**版本**: v0.0.1F  
**优先级**: 高  

## 现象

Gradle 构建报错：
```
Could not find com.arthenica:ffmpeg-kit-full:6.5.0
```
同时下载 B站视频时无声（音视频无法合并）。

## 根因

1. **FFmpegKit 已停用**：FFmpegKit 项目已正式退休，所有 Maven 包已从 Maven Central 移除。
2. **MobileFFmpeg 更早停用**：被 FFmpegKit 取代，已不可用。
3. **依赖不可解析**：使用阿里云镜像和官方 Maven 仓库均找不到该 artifact。

## 修复

### 删除 Maven 依赖

`build.gradle.kts` — 移除 `com.arthenica:ffmpeg-kit-full:6.5.0`

### 重写 FFmpegManager

从 Java 库包装器改为**运行时下载 FFmpeg 二进制**的方案：

1. **首次启动检查设备**：搜索 `/system/bin/ffmpeg` 等系统位置
2. **检查本地缓存**：查看 `files/ffmpeg` 是否已有下载好的文件
3. **从 GitHub 下载**：
   - 来源：[Khang-NT/ffmpeg-binary-android](https://github.com/Khang-NT/ffmpeg-binary-android) release 2018-07-31
   - 架构：arm64-v8a-full（~7.9MB）
   - 格式：tar.bz2
4. **解压部署**：内置简易 tar 解压器，提取 ffmpeg 可执行文件
5. **设置环境变量**：设置 `FFMPEG_PATH` 供 Python 读取
6. **备选重试**：首次下载失败时延长超时重试

### 修改文件

| 文件 | 变更 |
|------|------|
| [build.gradle.kts](file:///d:/COMPUTER/Android/GrayTools/Files/app/build.gradle.kts) | 移除 FFmpegKit 依赖 |
| [FFmpegManager.java](file:///d:/COMPUTER/Android/GrayTools/Files/app/src/main/java/com/example/grayvideodl/FFmpegManager.java) | 完全重写，改为运行时下载方案 |
| [GrayVideoDLApp.java](file:///d:/COMPUTER/Android/GrayTools/Files/app/src/main/java/com/example/grayvideodl/GrayVideoDLApp.java) | 适配异步初始化 |
| [ytdlp_bridge.py](file:///d:/COMPUTER/Android/GrayTools/Files/app/src/main/python/ytdlp_bridge.py) | 增加 FFMPEG_PATH 环境变量检查 |

## 验证

1. 执行 `./gradlew assembleDebug`，应成功编译通过
2. 安装 APK 后，首次运行日志应显示：
   - `FFmpegManager: 设备上未找到 FFmpeg，需要从网络下载`
3. 在下载视频时触发 FFmpeg 下载，查看日志：
   - `FFmpegManager: 开始下载 FFmpeg，总大小=8xxx`
   - `FFmpegManager: 解压文件: ffmpeg (7xxx 字节)`
   - `FFmpegManager: FFmpeg 下载并部署成功`
4. 后续查看 `adb logcat -s fffmag-video` 日志：
   - `FFMPEG_PATH 环境变量指向的 FFmpeg: /data/data/.../files/ffmpeg`

## 注意事项

- 首次使用 FFmpeg 功能时需要下载 ~8MB 数据
- 下载后保存在应用私有目录，不会重复下载
- GitHub 在中国地区可能访问不稳定，有备选重试机制