# Bug: DownloadTask 非静态内部类导致 FFmpegManager 内存泄漏

**日期**: 2026-06-21
**发现于**: v0.9A 全面检查报告
**优先级**: 高

## 现象
`DownloadTask` 是 `FFmpegManager` 的非静态内部类，持有外部类实例的隐式引用。当 Activity 销毁时下载任务仍在进行，`FFmpegManager` 实例无法被 GC 回收，导致内存泄漏。

## 根因
- `DownloadTask extends AsyncTask` 生命周期可能比 Activity 长
- 非静态内部类隐式持有 `FFmpegManager.this` 引用
- 回调对象 `FfmpegDownloadCallback` 通常持有 Activity 引用，形成第二条引用链

## 修复
- **修改文件**: [FFmpegManager.java](file:///d:/COMPUTER/Android/GrayTools/Files/app/src/main/java/com/example/grayvideodl/FFmpegManager.java)
- **修改内容**:
  1. 将 `DownloadTask` 声明为 `private static class`（静态内部类）
  2. 通过 `WeakReference<FFmpegManager>` 持有外部实例引用
  3. 通过 `WeakReference<FfmpegDownloadCallback>` 持有回调引用
  4. 所有对外部类成员（`appContext`, `ffmpegPath`, `isReady`, 方法调用）的访问均通过 `WeakReference.get()` 获取
  5. `downloadFfmpeg()` 中创建 `DownloadTask` 时传入 `this` 引用

## 验证
- 静态分析：`DownloadTask` 不再持有对外部类的隐式强引用
- `./gradlew assembleDebug` 构建成功
