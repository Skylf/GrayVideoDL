# Bug: extractTarBz2 使用 GZIPInputStream 解压 .tar.bz2 导致必然崩溃

**日期**: 2026-06-21
**发现于**: v0.9A 全面检查报告
**优先级**: 高

## 现象
当用户需要从网络下载 FFmpeg 时，`extractTarBz2()` 方法使用 `GZIPInputStream` 处理 `.tar.bz2` 格式文件。bzip2 和 gzip 是完全不同的压缩算法，`GZIPInputStream` 读取 bzip2 文件时抛出 `IOException("Not in GZIP format")`，导致 FFmpeg 下载流程 100% 崩溃。

## 根因
- 下载源 URL 指向 `arm64-v8a-full.tar.bz2`，确认为 bzip2 压缩格式
- Java 标准库不包含 bzip2 解压器，只有 gzip 解压器
- 开发者对 `GZIPInputStream` 能否解压 bzip2 文件存在认知误区，注释标注"兼容 .tar.bz2"

## 修复
- **新增依赖**: 在 `libs.versions.toml` 和 `build.gradle.kts` 中添加 `org.apache.commons:commons-compress:1.26.0`
- **修改文件**: [FFmpegManager.java](file:///d:/COMPUTER/Android/GrayTools/Files/app/src/main/java/com/example/grayvideodl/FFmpegManager.java)
- **修改逻辑**: 将 `extractTarBz2()` 中的 `GZIPInputStream` 替换为 `BZip2CompressorInputStream`（来自 commons-compress）
- **更新注释**: 修正了原有注释中的误解描述

## 验证
- `./gradlew assembleDebug` 构建成功
- 无编译错误或 Lint 错误
- commons-compress 为成熟库，运行时行为稳定
