# Bug: 打开下载目录显示的是 MediaStore 缓存的陈旧文件列表

**日期**: 2026-06-13  
**版本**: v0.6A  
**优先级**: 高  

## 现象

1. 下载视频 A 和 B 后，点击"打开文件夹"按钮，只显示旧文件，不显示新下载的 A 和 B
2. 用 MT 管理器查看，A 和 B 实际存在于 `/storage/emulated/0/Download/GrayVideoDL/` 中
3. 将该目录所有文件删除后重启软件，点击"打开文件夹"，**仍然显示已被删除的文件**

## 根因

`DocumentsContract.buildTreeDocumentUri()` 的 Document Provider 依赖于 **MediaStore 缓存**来列出文件。当文件被外部工具（如 MT 管理器）修改/删除时，MediaStore 的索引不会自动更新，导致：

- **新文件**：虽然通过 `copyToPublicDownloads()` 的 MediaStore API 写入，但如果 DocumentsProvider 读取的是旧的缓存快照，新文件可能不会立即显示
- **已删除文件**：通过外部工具删除后，MediaStore 索引没有删除对应记录，DocumentsProvider 仍会显示这些已不存在的文件

之前的代码中，`openFolder()` 先尝试 `file://` URI 失败后，直接 Fallback 到 DocumentsContract，没有做任何缓存刷新，导致始终显示陈旧数据。

## 修复

### 1. 新增 `scanMediaStore()` 方法
打开文件夹前，使用 `MediaScannerConnection.scanFile()` 主动触发 MediaStore 重新扫描：
- 扫描目标文件夹本身（触发重新索引）
- 扫描文件夹下所有现有文件（确保新增文件也被索引）

### 2. 四级 Fallback 策略
将 `openFolder()` 改为多级尝试：
1. **MediaStore 扫描** → 刷新索引缓存（第一步，无 UI 操作）
2. **file:// URI（无MIME类型）** → 让系统自行推断类型
3. **file:// URI（`*/*` 类型）** → 显示所有文件
4. **DocumentsContract（`vnd.android.document/directory`）** → 最终备用

### 3. 修复路径重定向
`onOpenFolder()` 原本直接使用 `task.getFolderToOpen()` 返回的路径（该路径来自 `filepath` 父目录，即私有路径），现在改为：
- 优先使用公共目录 `/storage/emulated/0/Download/GrayVideoDL/`
- 公共目录不存在时回退到 task 保存的路径

## 修改文件

- [DownloadFragment.java](file:///d:/COMPUTER/Android/GrayTools/Files/app/src/main/java/com/example/grayvideodl/ui/download/DownloadFragment.java)
  - `onOpenFolder()`: 重定向到公共目录路径
  - 重写 `openFolder()` 为四级 Fallback 策略
  - 新增 `scanMediaStore()` 方法

## 验证

- 下载视频后，点击"打开文件夹"应正确显示新下载的文件
- 用外部文件管理器删除文件夹中的文件后，点击"打开文件夹"应不再显示已删除的文件
- 日志中应输出 `scanMediaStore: 触发扫描完成`