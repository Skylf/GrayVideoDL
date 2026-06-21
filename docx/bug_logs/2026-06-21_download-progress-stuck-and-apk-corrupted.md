# Bug: 下载进度条不动 + APK安装包损坏

**日期**: 2026-06-21
**版本**: v0.8C
**优先级**: 高

## 现象
1. 在设置页点击"检查更新"→ 下载 APK 时，进度条始终显示 0%，但实际在下载（查看缓存目录文件在增大）
2. 下载完成后安装 APK，系统提示"安装包损坏"，即使文件大小正常

## 根因

### Bug 1: 进度条不动
`UpdateManager.downloadApk()` 中进度计算逻辑：
```java
if (contentLength > 0) {
    final int progress = (int) ((long) totalRead * 100 / contentLength);
    ...
}
```
GitHub CDN 返回的响应使用 **chunked transfer encoding**（分块传输编码），`HttpURLConnection.getContentLength()` 返回 **-1**。`if (contentLength > 0)` 条件永不满足，进度回调从不触发，导致进度条始终为 0%。

### Bug 2: 安装包损坏
两种可能原因：
1. **文件类型错误**：GitHub CDN 可能在重定向或限流时返回 HTML 错误页，但代码将其当作 APK 保存下来。
2. **文件不完整**：ZIP 格式（APK 本质是 ZIP）的结构信息在文件末尾，部分下载的文件也能读完 InputStream 但末尾不完整。
3. 代码未对下载文件做任何完整性检查（Content-Type 校验、ZIP 校验），直接用于安装。

## 修复

### [UpdateManager.java](file:///d:/COMPUTER/Android/GrayTools/Files/app/src/main/java/com/example/grayvideodl/UpdateManager.java)

#### 修复 1: 进度显示（downloadApk 方法）
- 当 `contentLength <= 0` 时，传入 `DownloadCallback.PROGRESS_UNKNOWN = -1`
- 回调方（SettingsFragment）收到 -1 时将进度条切换为 indeterminate 模式（旋转动画）

#### 修复 2: Content-Type 检查（downloadApk 方法）
- 在开始下载前检查 `Content-Type`
- 若返回 `text/html` 或 `text/plain`，抛出异常并尝试下一个 URL
- 防止 HTML 错误页被当作 APK 保存

#### 修复 3: ZIP 完整性校验（新增 isValidApk 方法）
- 下载完成后，通过 `java.util.zip.ZipFile` 尝试以只读方式打开文件
- 如果文件损坏或不完整，`ZipFile` 构造器会抛出 `ZipException`，校验失败
- 同时检查文件大小是否 >= 1MB（最小 APK 阈值）

#### 修复 4: 日志标签统一
- `TAG` 从 `"UpdateManager"` 改为 `"Update"`
- 完整下载流程都加了详细日志（响应码、Content-Type、Content-Length、校验结果）
- 使用方法：`adb logcat -s Update`

### [SettingsFragment.java:248-264](file:///d:/COMPUTER/Android/GrayTools/Files/app/src/main/java/com/example/grayvideodl/ui/settings/SettingsFragment.java#L248-L264)
- `onProgress` 回调中新增对 `PROGRESS_UNKNOWN` 的处理
- 收到 -1 时：`progressBar.setIndeterminate(true)`（旋转动画）
- 收到 0~100 时：`progressBar.setIndeterminate(false)` + `setProgress(percent)`（精确进度）

## 验证
1. 编译安装后，点击"检查更新"→ 下载 APK，观察进度条是否显示动画（不再卡在 0%）
2. 查看 logcat：`adb logcat -s Update`，确认日志中有 Content-Type、文件大小、校验结果
3. 下载完成后如果能正常弹出安装界面即为修复成功
4. 若因网络问题下载到 HTML 错误页，应在校验阶段报错而非在安装时报"安装包损坏"