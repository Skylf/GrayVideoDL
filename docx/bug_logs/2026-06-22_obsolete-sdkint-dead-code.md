# Bug: 6 处 ObsoleteSdkInt 废弃版本检查死代码

**日期**: 2026-06-22
**发现于**: v0.9A 全面检查报告
**优先级**: 中

## 现象
项目的 `minSdk=29`（Android 10），但代码中检查了低于 29 的 API 版本：
- `LOLLIPOP(21)`、`KITKAT(19)`、`N(24)`、`O(26)`、`P(28)`

这些检查永远为 true，导致：
- 产生的死代码造成阅读混淆
- 多余的 `else` 分支（如 `Uri.fromFile()` 和 `versionCode`）永远不会执行

## 根因
开发者从旧项目迁移代码时未清理针对低版本 Android 的兼容性检查。

## 修复

### 修改的文件：

1. **[DownloadFragment.java](file:///d:/COMPUTER/Android/GrayTools/Files/app/src/main/java/com/example/grayvideodl/ui/download/DownloadFragment.java)**（3处）
   - 第642行：移除 `SDK_INT >= LOLLIPOP` 检查，保留 DocumentsContract 代码
   - 第678行：移除 `SDK_INT >= KITKAT` 检查，保留 `MediaScannerConnection.scanFile` 调用
   - 第693行：移除 `SDK_INT >= KITKAT` 检查，保留 `MediaScannerConnection.scanFile` 调用

2. **[UpdateManager.java](file:///d:/COMPUTER/Android/GrayTools/Files/app/src/main/java/com/example/grayvideodl/UpdateManager.java)**（3处）
   - 第380行：移除 `SDK_INT >= O` 检查，保留 `canRequestPackageInstalls()` 调用
   - 第400行：移除 `SDK_INT >= N` 的 if-else，只保留 FileProvider 路径
   - 第444行：移除 `SDK_INT >= P` 的 if-else，只保留 `getLongVersionCode()` 路径

## 验证
- 三处修改后无编译错误
- `./gradlew assembleDebug` 构建成功
- 功能逻辑不变，因为移除的检查条件在 minSdk=29 下始终为 true
